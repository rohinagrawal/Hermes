---
description: Architecture map for Hermes (Vert.x + Guice WhatsApp bot)
alwaysApply: true
---

# Hermes Architecture

Hermes is a Vert.x WhatsApp chatbot backend. It receives WhatsApp webhook messages,
drives users through a per-tenant JSON-defined conversational flow, and replies via a
pluggable WhatsApp channel adapter (Twilio or Meta's WhatsApp Cloud API today) —
including plain text, media (image/video/audio/document), and (when a tenant has
registered one) interactive buttons/lists/cards via that provider's template mechanism.

## Product intent

Hermes lets each tenant configure its own WhatsApp conversation workflow (the flow graph)
purely through a JSON file — no code change needed to add a new menu, API integration,
payment step, or media message. When a user sends something that *isn't* on the current
menu, an LLM agent answers instead of a canned error (the other original product-intent
goal, now built):

- `FlowManager`'s `LIST`/`BUTTON` handling calls `service/LlmAgentService` on unmatched
  input (`handleUnmatchedInput`), which replies via Claude and then re-shows the menu.
  When the tenant hasn't enabled the LLM (or no `ANTHROPIC_API_KEY` is set), it falls back
  to the literal `"Invalid input. Try again."` re-prompt — so the bot works with or
  without an LLM configured. See "LLM fallback agent" below.

Multi-tenant flow configuration, external API calls, payments, multimedia, and the LLM
fallback are all built — see below. The `clinic` tenant
(`flows/clinic/flow.json`) is a worked end-to-end example: a paid consultation booking
(collect name → reason → preferred slot → `PAYMENT` → confirmation with the mock pay
link) plus the LLM fallback enabled with a clinic-front-desk persona.

## Two-verticle split over the event bus

Wired together with Guice (not Vert.x's own DI):

- `HttpVerticle` (`src/main/java/com/flauntik/verticle/HttpVerticle.java`) — owns the HTTP server/router. Routes (`com.flauntik.constant.URIConstant`) are thin: they package the request body+params (including path params, e.g. `:tenantId`) into a `JsonObject` and `vertx.eventBus().request(...)` it to a named address, then map the eventbus reply to an HTTP `Response`. No business logic lives here.
- `APIVerticle` (`.../verticle/APIVerticle.java`) — consumes event bus addresses (`TEST`, `SET_LOGGING_EVENT`, `INCOMING_MESSAGE_EVENT`, `LIST_TENANTS_EVENT`) and dispatches to the relevant `service/` class (`AdminService`, `HermesService`). This is a worker verticle (`workerPoolSize: 20` in `config.json`), so blocking work here is fine.

Both verticles are instantiated through `GuiceVerticleFactory`, which resolves verticle classes via the Guice `Injector` — verticle constructors use `@Inject` and get services (e.g. `HermesConfig`, `AdminService`) injected directly. Verticle names passed to `vertx.deployVerticle` are prefixed with `java-guice:` (`GuiceVerticleFactory.PREFIX`) via `GuiceVertxDeploymentManager`.

The process entry point is `com.flauntik.guice.GuiceVertxLauncher`, not a plain `main()` in `MainVerticle`. `MainVerticle.start()` reads env-based config via Vert.x `ConfigRetriever`, builds the Guice injector (`HermesModule`), registers the `GuiceVerticleFactory`, and deploys `HttpVerticle` + `APIVerticle` using deployment options from `config.json`.

## Request flow for an incoming WhatsApp message

Every inbound route ends up parsed into the same canonical JSON shape
(`{tenantId, from, text.body}`, `IncomingMessageRequest`) before it touches the event bus,
so everything downstream is provider-agnostic:

`HttpVerticle` (any of the three inbound routes below) → event bus (`incomingMessage`)
→ `APIVerticle` → `HermesService` → `MessageHandler.handleIncomingMessage` →
`FlowManager.getNextStep(tenantId, userId, input)` (async; looks up per-tenant, per-user
conversation state and advances through that tenant's flow) → `MessageHandler` picks a
WhatsApp send method based on the resulting `FlowStepResult` (`sendTemplateMessage` if
`contentSid` is set, else `sendMediaMessage` if media fields are set, else plain
`sendMessage`) and fires it at `WhatsAppService` without blocking the HTTP ack (a failed
outbound call is logged, not fatal to the webhook response).

### Three inbound routes, one canonical shape

- `POST /hermes/:tenantId/incoming_message` — the original simplified JSON path (used for
  local testing/demos, or any provider that already speaks the canonical shape). Parsed
  directly, unchanged.
- `POST /hermes/:tenantId/webhook` — the real per-tenant provider webhook. Twilio's webhook
  URL is configured per WhatsApp number in the Twilio console, so the tenant is already
  known from the `:tenantId` path segment; `HttpVerticle` parses the Twilio-shaped
  form-urlencoded body (`From`/`Body`) via `service/channel/TwilioInboundAdapter`.
- `POST /hermes/webhook/whatsapp` (+ `GET` for the registration handshake) — the real
  **shared** Meta WhatsApp Cloud API webhook. Meta delivers every tenant's number to one
  app-level webhook URL, so the tenant can't come from the path — `HttpVerticle` resolves
  it from the payload's `phone_number_id` via `HermesConfig.findTenantIdByCloudApiPhoneNumberId`,
  using `service/channel/WhatsAppCloudApiInboundAdapter`. Before trusting the payload it:
  - Validates `X-Hub-Signature-256` (HMAC-SHA256 of the raw body, keyed by
    `HermesConfig.whatsAppCloudApiAppSecret`) — rejects with `401` on mismatch/missing.
  - On the `GET` handshake, echoes `hub.challenge` only if `hub.verify_token` matches
    `HermesConfig.whatsAppCloudApiVerifyToken` — `403` otherwise.
  - If the signature is valid but no tenant matches the `phone_number_id`, still responds
    `200` (Meta disables a webhook that doesn't return 200) while logging a warning and
    dropping the message — a deliberate Meta-compliance quirk, not an oversight.

### Outbound: pluggable per-tenant provider

`WhatsAppService` is a thin dispatcher: it looks up the target tenant's
`TenantConfig.provider` (`TWILIO` or `WHATSAPP_CLOUD_API`, defaults to `TWILIO`) and
delegates to the matching `service/channel/OutboundMessageSender` — `TwilioMessageSender`
(today's Twilio Messages API calls, unchanged) or `WhatsAppCloudApiMessageSender` (Meta
Graph API `POST /{phone_number_id}/messages`), bound per provider via a Guice
`MapBinder<WhatsAppProviderType, OutboundMessageSender>` in `HermesModule`.
`MessageHandler` is unaware of which provider is in play — it always calls
`WhatsAppService`.

Adding a third BSP means one new `WhatsAppProviderType` value, one new
`InboundChannelAdapter`, one new `OutboundMessageSender`, and one `MapBinder` entry —
`FlowManager`, `MessageHandler`, and the event-bus contract don't change.

`GET /hermes/admin/tenants` (`AdminService.listTenants`) lists every loaded tenant id + step
count, so an operator can confirm a given tenant's flow loaded and validated cleanly.

## Flow engine

### Per-tenant flow loading (`service/flow/TenantFlowRegistry`, `service/flow/FlowValidator`)

At startup, `TenantFlowRegistry` reads `flows/tenants-registry.json` (classpath resource, a
plain `["demo", ...]` array of tenant ids) and loads `flows/{tenantId}/flow.json` for each —
via `ClassLoader.getResourceAsStream`, so it works both from `src/main/resources` in the
IDE and from inside the packaged fat jar (no more relative-disk-path reads).
`FlowValidator` then checks every tenant's flow graph — a `start` step exists, every `next`
target (string or map value) points at a real step, `LIST`/`BUTTON` options line up with
`next` map keys, and each step type's required fields are present — and **aggregates
every error found** into one startup exception. A broken flow file fails the process
immediately with a full report instead of NPE-ing at runtime for whichever user hits
the bad step.

### Step types (`enums/FlowStepType`, `pojo/FlowStep`)

- `MESSAGE` / `LIST` / `BUTTON` — unchanged text/menu steps, "interactive": each is sent
  to the user and execution pauses at that step until their next message arrives.
- `MEDIA` — sends an image/video/audio/document (`mediaType`, `mediaUrl`,
  `mediaCaption`, all `{{var}}`-templated). Interactive like `MESSAGE`: pauses for the
  user's next input before advancing via its (single, unconditional) `next`.
- `API_CALL` — calls an external HTTP API (`apiUrl`, `apiMethod`, `apiHeaders`,
  `apiBody`, all templated) via `service/ApiCallService`, extracts fields from the JSON
  response into the user's context via `responseMapping` (`contextVar -> JSON Pointer`,
  resolved with Jackson's built-in `JsonNode.at(...)`), and **cascades automatically**
  (no user input needed) to `next` — a plain string always, or a `{"success":...,
  "failure":...}` map to branch on the HTTP outcome.
- `PAYMENT` — creates a payment link via the injected `PaymentProvider`
  (`paymentAmount`/`paymentCurrency`/`paymentDescription`, templated), puts
  `paymentLink`/`paymentReferenceId` into context, and cascades to `next` the same way
  as `API_CALL` (success/failure branching supported).
- `ACTION` — invokes a **named in-process handler** (`service/action/StepActionHandler`,
  bound by name in `HermesModule`'s action `MapBinder`), passing templated `actionParams`;
  merges the handler's returned map into context and cascades on the outcome
  (`_actionSuccess:false` or a failed Future → `failure` branch), exactly like `API_CALL`.
  This is the in-process twin of `API_CALL` (external HTTP): use `ACTION` for internal
  logic — DB lookups, inventory reservation, business rules, internal services. Flow
  authors reference a handler by name; developers implement/register it in code. A flow
  referencing an unregistered handler **fails startup** (`FlowValidator` injects the
  registry's key set and checks it). Shipped example: `SlotAvailabilityHandler`
  (`"check_slot_availability"`), used by the `clinic` flow to reject Sundays.
- `BRANCH` — **conditional routing on earlier answers** (`pojo/Branch`,
  `FlowStep.branches`). This is how a choice made early changes the flow much later,
  without duplicating the downstream steps. Immediate branching (route on *this* step's
  choice) is already handled by a `LIST`/`BUTTON` `next` map; `BRANCH` is for *deferred*
  branching — routing on something answered several steps back. Each rule is
  `{"when": "<templated>", "equals": "<value>", "next": "<stepId>"}`; `when` is rendered
  against the accumulated context (e.g. `{{patient_type}}` resolves to the option the user
  pressed at the `patient_type` step) and compared to `equals`. First match wins; if none
  match, the step's plain-string `next` is the default. `BRANCH` is automatic (no user
  input) and cascades immediately. Worked example: `flows/clinic/flow.json`'s `route_fee`
  sends a new patient to a Rs.700 first-consultation payment and a returning patient to a
  Rs.500 one, based on a new-vs-returning choice made three steps earlier — both then
  converge on the same confirmation step.
- Any step may also set `contentSid` + `contentVariables` to send via a pre-registered
  Twilio Content Template instead of plain text — the real mechanism for WhatsApp
  interactive buttons/lists/cards (WhatsApp requires template pre-approval; there's no
  way around that). No `contentSid` configured -> falls back to the existing
  numbered-text-menu rendering, so tenants without templates still get a fully working
  bot.

`API_CALL`/`PAYMENT`/`ACTION`/`BRANCH` are "automatic" (no user input, cascade
immediately); everything else pauses and waits for the user's next message.

### `FlowManager` (`service/FlowManager.java`)

Per-user state (current step id) and context (accumulated answers, keyed by step id,
plus vars written by `API_CALL`/`PAYMENT`) live together in a `pojo/ConversationSession`,
held by `repository/SessionStore` (interface) / `repository/inMemory/InMemorySessionStore`
(default binding) — a Guava `Cache` with a **sliding TTL**
(`expireAfterAccess`, default 5 min: each message resets the idle clock) and an **LRU
size bound** (`maximumSize`, default 10 000 sessions). Both are tunable via
`HermesConfig.sessionTtlMinutes` / `sessionMaxSize`. When a session expires (idle) or is
LRU-evicted, the user's next message just starts a fresh session at `start` — no error,
no orphaned state, and memory stays bounded regardless of traffic. Still process-local
(see Known gaps for the multi-instance/durability path). `getNextStep(tenantId, userId,
input)` returns `Future<FlowStepResult>` since `API_CALL`/`PAYMENT` need network I/O.

A brand-new (or just-expired) session has a null `currentStepId`, so `FlowManager`
renders `start` directly instead of treating the user's first message as an answer to it
— a first-message-off-by-one bug in the original single-tenant engine. If a session's
stored step id points at a step that no longer exists in the tenant's flow (e.g. the flow
file changed), or at a non-interactive step (shouldn't happen, but guarded),
`FlowManager` self-heals by resetting to `start` rather than throwing.

Templating: `util/TemplateUtil.render(String, Map<String,Object>)` substitutes
`{{key}}` tokens against the user's context (a flat map keyed by step id for raw
recorded input, or by the explicit var names an `API_CALL`/`PAYMENT` step writes).

## New services

- `service/ApiCallService` — Vert.x `WebClient`-based, per the `API_CALL` step above.
- `service/payment/PaymentProvider` (interface) + `service/payment/MockPaymentProvider`
  (default Guice binding in `HermesModule`) — generates a fake link and logs clearly
  that it's a mock. Swap the binding for a real Razorpay/Stripe implementation to go
  live; `FlowManager`/flow.json need no changes.
- `service/WhatsAppService` — `sendMessage`, `sendMediaMessage`, `sendTemplateMessage`,
  all keyed by `tenantId`; dispatches to the tenant's configured `OutboundMessageSender` (see
  "Outbound: pluggable per-tenant provider" above).
- `service/channel/` — the inbound/outbound channel adapter abstraction:
  `InboundChannelAdapter` (+ `TwilioInboundAdapter`, `WhatsAppCloudApiInboundAdapter`)
  and `OutboundMessageSender` (+ `TwilioMessageSender`, `WhatsAppCloudApiMessageSender`).
- `service/action/` — the `ACTION`-step handler registry: `StepActionHandler` (interface)
  + implementations (e.g. `SlotAvailabilityHandler`), bound by name in a Guice
  `MapBinder<String, StepActionHandler>` in `HermesModule`. Add in-process business logic
  a flow can invoke by registering a new handler here.
- `repository/SessionStore` (interface) + `repository/inMemory/InMemorySessionStore`
  (default binding) — per-user `ConversationSession` cache with idle TTL + LRU (see the
  `FlowManager` section). Lives under `repository/`, not `service/`, since it's a storage
  concern (bound like `PaymentProvider`: interface + swappable implementation) rather than
  business logic — a future `repository/redis/RedisSessionStore` would bind the same way.

### LLM fallback agent (`service/LlmAgentService`)

Answers off-menu messages via Claude, using the official `com.anthropic:anthropic-java`
SDK. Key points:

- The API key is read from the `ANTHROPIC_API_KEY` **environment variable** only (via
  `AnthropicOkHttpClient.fromEnv()`) — never from `config.json`. If the key is absent the
  service self-disables at startup (logs a warning) and `FlowManager` uses the old
  re-prompt, so the app boots and runs fine without it.
- Per-tenant config lives in `TenantConfig.llm` (`config/LlmConfig`): `enabled`, `model`
  (default `claude-opus-4-8`), `maxTokens` (default 400), and a `systemPrompt` that gives
  the agent its persona/guardrails. `isAvailableFor(tenantId)` gates the fallback on both the
  client being ready and the tenant opting in.
- The Anthropic SDK is blocking (OkHttp), so the call runs on Vert.x's worker pool via
  `vertx.executeBlocking(...)`, keeping `FlowManager`'s `Future`-based contract. The
  request omits extended thinking (fast, short replies) and passes the current menu text
  in the system prompt so the model can answer *and* steer the user back to a valid
  option. Any failure (bad key, network, timeout) is caught and degrades to the default
  re-prompt — a broken LLM never breaks the workflow.
- The fallback only triggers at `LIST`/`BUTTON` steps (fixed option set). At `MESSAGE`
  steps any text is accepted as the answer, so free-text there is captured as data, not
  sent to the LLM.

## Config

`HermesConfig` is bound as a Guice singleton, populated from `config.json` (`profile`,
`port`, per-verticle `verticleDeploymentOptions`, `tenants: Map<String,TenantConfig>`, and two
Meta-app-level fields used by the shared Cloud API webhook:
`whatsAppCloudApiVerifyToken`/`whatsAppCloudApiAppSecret`).

`TenantConfig` now carries `provider` (`TWILIO` | `WHATSAPP_CLOUD_API`, defaults to
`TWILIO` so existing configs need no changes) plus provider-specific fields: Twilio's
`twilioAccountSid`/`twilioAuthToken`/`twilioFromWhatsAppNumber`, or Cloud API's
`cloudApiPhoneNumberId`/`cloudApiAccessToken`, and an optional `llm` block
(`config/LlmConfig`) for the fallback agent. `config.json` ships a `demo` (Twilio),
`demo_direct` (Cloud API), and `clinic` (Twilio + LLM enabled) tenant as worked examples,
all with clearly marked `REPLACE_ME` placeholders. The Anthropic API key is **not** in
`config.json` — it comes from the `ANTHROPIC_API_KEY` env var.

### Optional persistence & async ingestion (both config-gated, off by default)

Two integrations activate **only when their config block is present** in `config.json`;
absent, behavior is byte-identical to the in-memory/synchronous default. Both are bound
conditionally in `HermesModule` (the same pattern as `PaymentProvider`/LLM).

- **Persistent sessions (`database` block → `config/DatabaseConfig`).** When present,
  `HermesModule` binds `SessionStore` to `repository/jdbc/JdbcSessionStore` (JDBI + HikariCP
  over MySQL) instead of `InMemorySessionStore`; absent, the in-memory store is used. The
  JDBC store is **cache-aside**: it wraps the *same* Guava TTL+LRU cache (hot path unchanged)
  over a `sessions` table (`PRIMARY KEY (tenant_id, user_id)`, `context_json` TEXT), created
  on startup via `CREATE TABLE IF NOT EXISTS`. A cache miss falls through to a `SELECT`, so a
  restarted instance recovers a conversation instead of resetting to `start`.
  `SessionStore.save(...)` returns a `Future<Void>`: the in-memory store returns an
  already-completed future (`get` already handed back a live reference, nothing to write),
  and the JDBC store dispatches its upsert to a worker via `executeBlocking` so the
  event loop is never blocked on JDBC — `FlowManager` calls `save` exactly once per message,
  at the end of the async chain. Config fields: `jdbcUrl`, `username`, `password`,
  `maximumPoolSize`.

- **Async ingestion + flow events (`kafka` block → `config/KafkaConfig`).** When present,
  the two **real-provider webhook routes only** (`POST /hermes/:tenantId/webhook` Twilio,
  `POST /hermes/webhook/whatsapp` Meta) produce the canonical message to `incomingTopic` and
  ack immediately (generic 200, not the flow reply); a `KafkaIngestionVerticle` (deployed by
  `MainVerticle` only when `kafka` is set) consumes and drives the **same**
  `HermesService.handleIncomingMessage` on a worker. The JSON test route
  (`/hermes/:tenantId/incoming_message`) stays fully synchronous and still returns the real
  reply. The partition key is `tenantId + "|" + from`, so one user's messages always land on
  one partition and are processed in order; the consumer processes records on an *ordered*
  blocking executor and commits each offset only after processing (at-least-once).
  Additionally, `FlowManager` publishes a `{tenantId, userId, resultingStepId, ts}`
  flow-completion event to `eventsTopic` per message (fire-and-forget; `NoopFlowEventPublisher`
  when Kafka is off). Producer/consumer connection settings reuse `config/KafkaProducerConfig`
  and `config/KafkaConsumerConfig` built via `util/KafkaUtil`; `KafkaConfig` adds the two
  Hermes topic names (`incomingTopic`, `eventsTopic`). Absent config → webhooks fall back to
  the synchronous event-bus dispatch, unchanged.

## Logging

Log4j2 via `@Log4j2` (Lombok) throughout; config in `log4j2.properties`. `AdminService.setLogLevel` allows runtime log-level changes over `POST /hermes/admin/set_logging`, targeting logger `com.flauntik`.

## HTTP responses

All API replies flow through the shared `dto/response/Response` DTO, built via `Response.getSuccessResponse()`/`getFailureResponse()`, keeping the `HttpVerticle` → `APIVerticle` reply contract uniform regardless of which address handled the request.

## Known gaps / in-progress state

- No live flow hot-reload / admin upload API — flows are loaded once at startup from
  `flows/{tenantId}/flow.json`. Adding or editing a tenant's workflow means committing a
  file and restarting.
- `PaymentProvider` only has the mock implementation — no real Razorpay/Stripe account
  wired up yet.
- Conversation sessions are process-local **by default** (`InMemorySessionStore`): the
  5-min idle TTL + LRU cap keep memory bounded and expire abandoned chats, but state isn't
  shared across instances and doesn't survive a restart. Adding a `database` block opts into
  the MySQL-backed `JdbcSessionStore` (see "Optional persistence & async ingestion"), which
  **does** survive a restart; cross-instance sharing works too since all instances read/write
  the same table, though the per-instance Guava cache means a session updated on instance A
  is only visible on instance B after A's write lands and B's cache misses (eventually
  consistent, not a distributed lock). A Redis-backed store could bind the same way for
  lower-latency sharing.
- No test suite currently exists (`src/test` still doesn't exist) — this codebase is
  verified by running the app and driving it end-to-end over HTTP.
- `LlmAgentService` is wired and verified end-to-end *except the final valid reply*:
  driving an off-menu message with a dummy `ANTHROPIC_API_KEY` confirmed the client
  initialises, `isAvailableFor` gates correctly, the call runs on the worker pool, and a
  401 degrades cleanly to the re-prompt — but a real reply needs a valid key set in the
  environment. No automated eval of reply quality/persona adherence yet.
- `WhatsAppCloudApiMessageSender`/`WhatsAppCloudApiInboundAdapter` are implemented
  against Meta's documented WhatsApp Cloud API/webhook shapes and were verified with
  synthetic payloads (correct request shape, HMAC signature validation, tenant resolution)
  plus one real call to `graph.facebook.com` (rejected only on the placeholder auth
  token, confirming the request itself is well-formed) — but not against a live Meta
  WhatsApp Business Account end-to-end. Treat as needing a real sandbox check before
  production use. `sendTemplateMessage`'s mapping of `contentVariables` to Meta template
  body parameters is positional (Meta templates take an ordered parameter list, not
  named variables like Twilio Content Templates) — the registered template's placeholder
  order must match the caller's map iteration order.
