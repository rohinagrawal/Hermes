# Going live: real WhatsApp → Hermes → workflow / LLM → reply

Hermes is fully built to receive a real WhatsApp message, run the tenant's workflow (or the
LLM agent for off-script messages), and reply on WhatsApp. What it needs from you is the
three things only you can provide: **provider credentials**, a **public URL** the
provider can reach, and (for the LLM fallback) an **Anthropic API key**. None of these
can be committed to the repo.

The fastest path to a real, working WhatsApp message is the **Twilio WhatsApp sandbox**
(no business verification, works in minutes). Meta's Cloud API is also fully supported —
see the bottom.

---

## 1. Set the LLM key (enables the off-menu agent)

```bash
export ANTHROPIC_API_KEY="sk-ant-..."   # your key from console.anthropic.com
```

Without it, the bot still runs — off-menu messages just get the "Invalid input" re-prompt
instead of an AI reply. The `clinic` tenant already has the LLM enabled in `config.json`.

## 2. Put your Twilio sandbox creds in `config.json`

In Twilio Console → Messaging → Try it out → **WhatsApp sandbox**, you get an Account SID,
Auth Token, a sandbox number, and a join code. Fill the `clinic` tenant (or `demo`):

```json
"clinic": {
  "provider": "TWILIO",
  "twilioAccountSid": "ACxxxxxxxx",
  "twilioAuthToken":  "your_auth_token",
  "twilioFromWhatsAppNumber": "whatsapp:+14155238886",   // the sandbox number
  "llm": { "enabled": true, "model": "claude-opus-4-8", ... }
}
```

## 3. Expose the webhook publicly

Twilio must reach your machine. In a separate terminal:

```bash
ngrok http 8080         # or: cloudflared tunnel --url http://localhost:8080
```

Copy the `https://<something>.ngrok.app` URL.

## 4. Point Twilio at Hermes

In the WhatsApp sandbox settings, set **"When a message comes in"** to:

```
https://<something>.ngrok.app/hermes/clinic/webhook      (HTTP POST)
```

That's the Twilio-shaped inbound route — Hermes parses Twilio's `From`/`Body` form fields,
runs the `clinic` flow, and replies through the Twilio Messages API using the creds above.

## 5. Build, run, and message it

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
mvn -q -DskipTests package
java -Dvertx.logger-delegate-factory-class-name=io.vertx.core.logging.Log4j2LogDelegateFactory \
     -Dlog4j2.configurationFile=file:log4j2.properties \
     -jar target/Hermes-1.0-SNAPSHOT-fat.jar run com.flauntik.verticle.MainVerticle -conf config.json
```

From your phone: send the sandbox join code to the sandbox number, then message it.
- `hi` → the BrightCare menu
- `1` → walks you through name → reason → slot → a Rs.500 payment link → confirmation
- `do you treat migraines?` (instead of a menu number) → the LLM front-desk agent replies,
  then re-shows the menu

---

## Meta WhatsApp Cloud API instead of Twilio

Set the tenant's `provider` to `WHATSAPP_CLOUD_API` with `cloudApiPhoneNumberId` +
`cloudApiAccessToken`, and set the two Meta-app-level fields at the top of `config.json`
(`whatsAppCloudApiVerifyToken`, `whatsAppCloudApiAppSecret`). In the Meta App dashboard,
register **one** webhook for the whole app:

```
Callback URL:  https://<something>.ngrok.app/hermes/webhook/whatsapp
Verify token:  (must match whatsAppCloudApiVerifyToken)
```

Meta sends a `GET` verification handshake (Hermes answers it) and then `POST`s messages
signed with `X-Hub-Signature-256` (Hermes validates against the app secret) — the tenant is
resolved from the payload's `phone_number_id`. See `.agents/rules/hermes-architecture.md`
→ "Three inbound routes".

---

## Optional: durable sessions (MySQL)

By default conversation state is in-memory and resets on restart. To make it survive a
restart, add a top-level `database` block to `config.json`; absent, nothing changes.

```jsonc
"database": {
  "jdbcUrl": "jdbc:mysql://localhost:3306/hermes?useSSL=false&allowPublicKeyRetrieval=true",
  "username": "hermes",
  "password": "…",
  "maximumPoolSize": 10
}
```

The `sessions` table is created automatically on startup (`CREATE TABLE IF NOT EXISTS`).
The store stays cache-first (same in-memory TTL+LRU); MySQL is the durable backing that a
cache miss / restart reads from. Locally: `brew install mysql`, `brew services start mysql`,
`mysql -u root -e "CREATE DATABASE hermes;"`.

## Optional: async ingestion + flow events (Kafka)

To decouple real-provider webhook ingestion from flow processing under load, add a top-level
`kafka` block. When present, the **Twilio/Meta webhook routes** produce to `incomingTopic`
and ack immediately; a background consumer drives the flow. The JSON test route
(`/hermes/:tenantId/incoming_message`) stays synchronous. A flow-completion event is
published to `eventsTopic` per message. Absent, webhooks dispatch synchronously as before.

```jsonc
"kafka": {
  "incomingTopic": "hermes.incoming-messages",
  "eventsTopic": "hermes.flow-events",
  "producer": {
    "bootstrap.servers": "localhost:9092",
    "key.serializer": "org.apache.kafka.common.serialization.StringSerializer",
    "value.serializer": "org.apache.kafka.common.serialization.StringSerializer",
    "request.acks": "1"
  },
  "consumer": {
    "bootstrap.servers": "localhost:9092",
    "key.deserializer": "org.apache.kafka.common.serialization.StringDeserializer",
    "value.deserializer": "org.apache.kafka.common.serialization.StringDeserializer",
    "static.group.id": "hermes-ingestion",
    "enable.auto.commit": false,
    "autoOffSetResetConfig": "earliest"
  }
}
```

Messages are partitioned by `tenantId|from` to preserve per-user ordering. Locally:
`brew install kafka`, `brew services start kafka`, then create the topics with
`kafka-topics --bootstrap-server localhost:9092 --create --topic hermes.incoming-messages …`
(and `hermes.flow-events`).

---

## Notes

- **Payments are mock.** The Rs.500 link is generated by `MockPaymentProvider`. Swap that
  one Guice binding in `HermesModule` for a real Razorpay/Stripe provider to take live
  payments — the flow JSON doesn't change.
- **State is in-memory unless you add a `database` block** (see above). With it, conversation
  position survives a restart.
- **One key, many tenants.** `ANTHROPIC_API_KEY` is process-wide; each tenant sets its own
  `model`/`systemPrompt`/`maxTokens` and can turn the LLM on or off independently.
