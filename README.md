# Hermes

Named after Hermes, the Greek messenger god, symbolizing communication.

Hermes is a Java 21 / Vert.x WhatsApp chatbot backend. Each tenant configures its
own WhatsApp conversation workflow purely through a JSON file — menus, external API calls,
in-process actions, payments, media, and conditional branching — with an LLM agent that
answers anything off-script. Inbound and outbound WhatsApp are pluggable per tenant (Twilio or
Meta Cloud API).

## Documentation

**New here? Start with the overview** — it explains the whole system with a diagram.

| Doc | For |
|---|---|
| **[docs/OVERVIEW.md](docs/OVERVIEW.md)** | Understand the whole system end-to-end (start here) |
| **[docs/LLD.md](docs/LLD.md)** | Code-level low-level design — every class, every diagram, how it's wired |
| [GO_LIVE.md](GO_LIVE.md) | Connect a real WhatsApp number and message it (Twilio sandbox or Meta) |
| [.agents/rules/flow-authoring.md](.agents/rules/flow-authoring.md) | Onboard a new tenant and author its `flow.json` (every step type, branching, validation) |
| [.agents/rules/hermes-architecture.md](.agents/rules/hermes-architecture.md) | Code internals (verticles, event bus, channel adapters, flow engine, sessions) |
| [.agents/rules/maven-build.md](.agents/rules/maven-build.md) | Build, run, and validation commands |
