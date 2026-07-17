---
description: How to onboard a new tenant and author its flow.json (all step types, branching, templating, validation)
alwaysApply: false
---

# Onboarding a tenant & authoring flows

Read this when adding a new tenant to Hermes or writing/editing a `flow.json`.
For the runtime architecture see [hermes-architecture.md](hermes-architecture.md); for
build/run see [maven-build.md](maven-build.md); for connecting a real WhatsApp number see
[GO_LIVE.md](../../GO_LIVE.md).

Everything below is **data-driven** — onboarding a tenant and defining its conversation is
config + JSON, no code changes.

---

## Part 1 — Onboard a new tenant (5 steps)

Say the new tenant is `acme`.

1. **Create the flow file** at `src/main/resources/flows/acme/flow.json` (see Part 2).

2. **Register the tenant id** in `src/main/resources/flows/tenants-registry.json`:
   ```json
   ["demo", "demo_direct", "clinic", "acme"]
   ```
   `TenantFlowRegistry` loads `flows/{tenantId}/flow.json` for each id here at startup, from the
   classpath (works in the IDE and the packaged fat jar).

3. **Add the tenant to `config.json`** under `tenants`. Pick a provider and give it credentials
   (use clearly-marked placeholders until you have real ones — never commit real secrets):

   ```json
   "acme": {
     "provider": "TWILIO",
     "twilioAccountSid": "ACxxxx",
     "twilioAuthToken": "xxxx",
     "twilioFromWhatsAppNumber": "whatsapp:+1415xxxxxxx"
   }
   ```
   For Meta instead: `"provider": "WHATSAPP_CLOUD_API"`, `"cloudApiPhoneNumberId": "..."`,
   `"cloudApiAccessToken": "..."`. `provider` defaults to `TWILIO` if omitted.

4. **(Optional) Enable the LLM fallback** so off-menu messages get an AI reply instead of
   a canned re-prompt. Add an `llm` block to the tenant and set `ANTHROPIC_API_KEY` in the
   environment (the key is **never** put in `config.json`):
   ```json
   "llm": {
     "enabled": true,
     "model": "claude-opus-4-8",
     "maxTokens": 400,
     "systemPrompt": "You are the front-desk assistant for Acme Corp. Answer questions about our services and guide the user back to the menu. Do not invent prices or make commitments; if unsure, suggest the relevant menu option."
   }
   ```

5. **Build, run, verify.** `mvn -q -DskipTests package` then run the fat jar (see
   maven-build.md). At startup you should see
   `Loaded and validated N tenant flow(s): [..., acme]`. Confirm with:
   ```bash
   curl -s localhost:8080/hermes/admin/tenants        # lists each tenant, step count, validated:true
   ```
   Drive it without a real number via the JSON test route:
   ```bash
   curl -s -X POST localhost:8080/hermes/acme/incoming_message \
     -H 'Content-Type: application/json' -d '{"from":"123","text":{"body":"hi"}}'
   ```
   To take real WhatsApp traffic, point the provider's webhook at Hermes — see
   [GO_LIVE.md](../../GO_LIVE.md).

> A broken flow **fails startup loudly** with an aggregated error report (every problem at
> once), not a runtime NPE. If the app won't boot after adding a tenant, read the
> `Refusing to start: found N flow configuration error(s)` block — it names the exact
> step and problem.

---

## Part 2 — Authoring `flow.json`

### The mental model

A flow is a JSON object mapping **step id → step**. Execution is a state machine:

- **`start` is required** — it's the entry point every new/expired session renders first.
- Each step has a `type` and a `next`. Interactive steps (MESSAGE/LIST/BUTTON/MEDIA)
  render to the user and **pause** for their reply. Automatic steps (API_CALL/PAYMENT/
  BRANCH) run immediately and **cascade** to the next step with no user input.
- Every answer the user gives is saved in their **session context**, keyed by the step id
  that asked it. Later steps read those answers by **templating**: `{{step_id}}`.
- Sessions have a 5-minute idle TTL (configurable). An expired user simply restarts at
  `start` — design flows so that's graceful (terminal steps usually loop `next` back to
  `start`).

### Templating

Any string field supports `{{key}}` tokens, substituted from the session context:
`{{collect_name}}` → whatever the user answered at the `collect_name` step;
`{{orderId}}` → a variable an `API_CALL` extracted; `{{paymentLink}}` → written by a
`PAYMENT` step. **Unresolved tokens are left literal** (e.g. `{{typo}}` stays as-is) so
authoring mistakes are visible rather than silently blank.

### `next`: how routing works

`next` is either a **string** (one unconditional target) or a **map**:

| Step type | `next` shape | Keyed by |
|---|---|---|
| MESSAGE / MEDIA | string | — (single target) |
| LIST / BUTTON | map | the option the user pressed (`"1"`, `"2"`, …) |
| API_CALL / PAYMENT / ACTION | string, or map with `success`/`failure` | the call/handler outcome |
| BRANCH | string (the default) + a `branches` list | earlier answers (see below) |

### Step type reference

Every field is optional unless noted. `type` values are lowercase.

**`message`** — send text, pause for the user's reply.
```json
{ "type": "message", "message": "What's your name?", "next": "collect_email" }
```
Whatever the user types next is stored under this step's id and accepted as the answer —
there's no validation, so use MESSAGE for free-text (names, descriptions, times).

**`list` / `button`** — a numbered menu; the user must pick a valid option.
```json
{
  "type": "list",
  "message": "Choose an option:",
  "options": { "1": "Sales", "2": "Support" },
  "next":    { "1": "sales_flow", "2": "support_flow" }
}
```
`options` and `next` keys must line up (the validator enforces it). `list` vs `button` are
identical in text mode; the distinction only matters if you attach a Content Template
(see below). If the user types something that isn't an option, the **LLM fallback** runs
(if enabled) — it answers their message and re-shows this menu; otherwise they get
`Invalid input. Try again.` + the menu. Either way they stay on this step.

**`media`** — send an image/video/audio/document, then pause.
```json
{ "type": "media", "mediaType": "image", "mediaUrl": "https://.../banner.png",
  "mediaCaption": "Our menu", "next": "start" }
```
`mediaType` + `mediaUrl` required. `mediaType` is `image` | `video` | `audio` | `document`.

**`api_call`** — call an external HTTP API, extract fields into context, branch on outcome.
Automatic (no user input).
```json
{
  "type": "api_call",
  "apiMethod": "GET",
  "apiUrl": "https://api.example.com/orders/{{order_id}}",
  "apiHeaders": { "Authorization": "Bearer {{token}}" },
  "apiBody": { },
  "responseMapping": { "status": "/data/status", "eta": "/data/eta" },
  "next": { "success": "show_status", "failure": "lookup_failed" }
}
```
`apiUrl` required. `responseMapping` maps `contextVar → JSON Pointer` into the JSON
response (e.g. `/data/status`), so `{{status}}` is usable downstream. A non-2xx/error
takes the `failure` branch (a plain-string `next` is always taken regardless of outcome).

**`payment`** — create a payment link via the configured `PaymentProvider`, then cascade.
Automatic.
```json
{
  "type": "payment",
  "paymentAmount": "500",
  "paymentCurrency": "INR",
  "paymentDescription": "Consultation: {{reason}}",
  "next": { "success": "confirmed", "failure": "payment_failed" }
}
```
`paymentAmount` required. On success it writes `{{paymentLink}}` and
`{{paymentReferenceId}}` into context for the confirmation step; on failure,
`{{paymentError}}`. (The default provider is a **mock** — swap the Guice binding in
`HermesModule` for a real Razorpay/Stripe provider; the flow JSON doesn't change.)

**`action`** — invoke a named **in-process handler** (custom Java business logic), then
cascade on the outcome. Automatic. This is the in-process counterpart to `api_call`: use
`api_call` to hit an external HTTP API, use `action` to run logic inside Hermes (check a
DB, reserve inventory, apply a rule, call an internal service).
```json
{
  "type": "action",
  "action": "check_slot_availability",
  "actionParams": { "slot": "{{collect_slot}}" },
  "next": { "success": "route_fee", "failure": "slot_unavailable" }
}
```
`action` is the handler's registered name. `actionParams` (templated) are passed to it;
whatever the handler returns is merged into context (usable as `{{var}}` downstream). The
handler signals failure — taking the `failure` branch — by returning `_actionSuccess:false`
or erroring; otherwise it's success. **Flow authors reference a handler by name; developers
implement and register it in code** — you can't inject arbitrary Java from JSON. Register a
handler by implementing `service/action/StepActionHandler` and binding it by name in
`HermesModule`'s action `MapBinder` (`SlotAvailabilityHandler` /
`"check_slot_availability"` is the shipped example). A flow that references an unregistered
handler **fails startup** with an error listing the registered names.

**`branch`** — route to different steps based on **earlier** answers. Automatic. This is
how a choice made early changes the flow much later, without duplicating downstream steps.
```json
{
  "type": "branch",
  "branches": [
    { "when": "{{patient_type}}", "equals": "1", "next": "pay_new" },
    { "when": "{{patient_type}}", "equals": "2", "next": "pay_returning" }
  ],
  "next": "pay_returning"
}
```
Each rule renders `when` against context and compares to `equals` (exact match); first
match wins. If none match, the step's plain-string `next` is the default. Use LIST/BUTTON
`next` maps for branching on the *current* choice; use BRANCH for branching on something
answered several steps back. Chain multiple BRANCH steps for multi-factor routing.

**Content Templates (any step)** — for *real* WhatsApp interactive buttons/lists/cards you
must register a template with the provider and reference it:
```json
{ "type": "list", "message": "...", "options": {...}, "next": {...},
  "contentSid": "HXxxxxxxxx", "contentVariables": { "1": "{{name}}" } }
```
With no `contentSid`, steps fall back to the numbered-text-menu rendering (fully working
without any templates). See hermes-architecture.md → "rich message types" for provider
differences (Twilio Content SID vs Meta template name).

### What the validator checks (startup)

`FlowValidator` aggregates *all* errors and refuses to start if any are found:
- a `start` step exists;
- every `next` target (string or map value, including BRANCH branch targets and defaults)
  points at a defined step;
- LIST/BUTTON have non-empty `options` and a `next` map whose keys cover the options;
- API_CALL has `apiUrl`; PAYMENT has `paymentAmount`; MEDIA has `mediaType` + `mediaUrl`;
- ACTION has an `action` name that matches a **registered** handler;
- BRANCH has a non-empty `branches` list (each with `when` + `equals`) and a default `next`.

### Gotchas

- **MESSAGE accepts anything.** Free-text at a MESSAGE step is captured as the answer, so
  the LLM fallback only triggers at LIST/BUTTON (fixed-option) steps.
- **Terminal steps should loop to `start`.** A MESSAGE with `"next": "start"` means the
  user's next message returns them to the menu — the idiom for "type anything to go back".
- **BRANCH compares stored input**, i.e. the option *key* (`"1"`), not the label
  (`"New patient"`), or the raw free-text typed at a MESSAGE step. Matching is exact.
- **Idle > TTL resets the session.** Don't rely on context surviving a long pause; after
  the TTL (default 5 min) the user restarts at `start`.

### Full worked example

`src/main/resources/flows/clinic/flow.json` is a complete, validated reference: a paid
consultation booking (menu → new/returning → name → reason → slot → **ACTION** availability
check → **BRANCH** to a ₹700-vs-₹500 payment → confirmation) with the LLM fallback enabled.
It exercises MESSAGE, LIST, ACTION, BRANCH, and PAYMENT in one flow. Read it alongside this
guide when authoring a new flow.
