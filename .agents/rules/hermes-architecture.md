---
description: Architecture map for Hermes (Vert.x + Guice WhatsApp bot)
alwaysApply: true
---

# Hermes Architecture

Hermes is a Vert.x WhatsApp chatbot backend. It receives WhatsApp webhook messages, drives users through a JSON-defined conversational flow (`src/main/resources/flow.json`), and is intended to reply via a WhatsApp/Twilio API.

## Product intent

Hermes is meant to let an org configure its own WhatsApp conversation workflow (the flow graph), and fall back to an LLM for any incoming message that doesn't match a step in that workflow — so users aren't stuck with "Invalid input, try again." when they go off-script. Neither piece of this is built yet:

- The flow is currently a single hardcoded `flow.json` file read from disk, not a per-org/tenant configuration. There is no concept of multiple orgs or workflows in the code.
- There is no LLM integration anywhere in the codebase. The natural hook point is `FlowManager.getNextStep` in the `LIST`/`BUTTON` case, where an unmatched `input` currently just returns the literal string `"Invalid input. Try again."` (`src/main/java/com/flauntik/service/FlowManager.java`) — that's where an LLM fallback would plug in.

When working on workflow-authoring or LLM-fallback features, treat this as the target design, and treat the single-tenant `flow.json` + no-LLM state below as the current baseline to build from, not as fixed constraints.

## Two-verticle split over the event bus

Wired together with Guice (not Vert.x's own DI):

- `HttpVerticle` (`src/main/java/com/flauntik/verticle/HttpVerticle.java`) — owns the HTTP server/router. Routes (`com.flauntik.constant.URIConstant`) are thin: they package the request body+params into a `JsonObject` and `vertx.eventBus().request(...)` it to a named address, then map the eventbus reply to an HTTP `Response`. No business logic lives here.
- `APIVerticle` (`.../verticle/APIVerticle.java`) — consumes event bus addresses (`TEST`, `SET_LOGGING_EVENT`, `INCOMING_MESSAGE_EVENT`) and dispatches to the relevant `service/` class (`AdminService`, `HermesService`). This is a worker verticle (`workerPoolSize: 20` in `config.json`), so blocking work here is fine.

Both verticles are instantiated through `GuiceVerticleFactory`, which resolves verticle classes via the Guice `Injector` — verticle constructors use `@Inject` and get services (e.g. `HermesConfig`, `AdminService`) injected directly. Verticle names passed to `vertx.deployVerticle` are prefixed with `java-guice:` (`GuiceVerticleFactory.PREFIX`) via `GuiceVertxDeploymentManager`.

The process entry point is `com.flauntik.guice.GuiceVertxLauncher`, not a plain `main()` in `MainVerticle`. `MainVerticle.start()` reads env-based config via Vert.x `ConfigRetriever`, builds the Guice injector (`HermesModule`), registers the `GuiceVerticleFactory`, and deploys `HttpVerticle` + `APIVerticle` using deployment options from `config.json`.

## Request flow for an incoming WhatsApp message

`HttpVerticle` (`POST /hermes/incoming_message`) → event bus (`incomingMessage`) → `APIVerticle` → `HermesService.handleIncomingMessage` → `MessageHandler.handleIncomingMessage` → `FlowManager.getNextStep` (looks up conversation state per user, advances through `flow.json`) → `WhatsAppService.sendMessage` (currently a Twilio WhatsApp API stub with placeholder credentials, not wired to real config).

## Flow engine (`FlowManager` + `flow.json`)

Conversation state machine keyed by WhatsApp user id (`from`). Each node in `flow.json` is a `FlowStep` with a `FlowStepType` (`MESSAGE`, `LIST`, `BUTTON`, `FUNCTION`) and a `next` pointer (string, or a `Map<String,String>` for branching — deserialized via the custom `NextFieldDeserializer`). `FlowManager` keeps in-memory `userState` and `userHistory` maps (`ConcurrentHashMap`, not persisted). `FUNCTION` steps dispatch to `performAction`, whose action bodies (`fetchOrderStatus`, `sendPaymentLink`) are unimplemented stubs.

## Config

`HermesConfig` is bound as a Guice singleton, populated from `config.json` (`profile`, `port`, per-verticle `verticleDeploymentOptions`). `KafkaConsumerConfig`/`KafkaProducerConfig` and `KafkaUtil` exist for Kafka producer/consumer setup but are not currently referenced by any verticle or service — Kafka is not on the active request path.

## Logging

Log4j2 via `@Log4j2` (Lombok) throughout; config in `log4j2.properties`. `AdminService.setLogLevel` allows runtime log-level changes over `POST /hermes/admin/set_logging` (note: it hardcodes the logger name `com.makemytrip`, which looks like leftover from a different codebase and should probably be `com.flauntik`).

## HTTP responses

All API replies flow through the shared `dto/response/Response` DTO, built via `Response.getSuccessResponse()`/`getFailureResponse()`, keeping the `HttpVerticle` → `APIVerticle` reply contract uniform regardless of which address handled the request.

## Known gaps / in-progress state

- `WhatsAppService` has hardcoded placeholder Twilio credentials/URL — not reading from `HermesConfig`.
- `FlowManager` state/history is in-memory only (lost on restart), and reads `flow.json` off disk by relative path instead of classpath resource.
- `FUNCTION` flow actions (`fetchOrderStatus`, `sendPaymentLink`) are no-ops.
- No test suite currently exists.
- No multi-org/multi-workflow support and no LLM fallback yet — see "Product intent" above.
