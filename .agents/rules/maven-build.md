---
description: Build and run guidance for Hermes
alwaysApply: true
---

# Hermes Build Rules

Hermes is a Java 21 / Maven project using the standard `src/main/java` source root (groupId `com.flauntik`). There is no test source set yet (`src/test` does not exist).

## Commands

```bash
mvn -q -DskipTests compile   # quick compile sanity check
mvn package                  # build shaded/fat jar via maven-shade-plugin
mvn test                     # no-op today; add tests under src/test before relying on this
java --version                # requires Java 21 (release level set in pom.xml)
```

## Running

The entry point is `com.flauntik.guice.GuiceVertxLauncher` (a `io.vertx.core.Launcher` subclass), invoked with Vert.x's `run` command and a config file:

```bash
mvn exec:java   # uses exec-maven-plugin, defaults to run com.flauntik.verticle.MainVerticle
```

or directly, matching `.run/Hermes-Run.run.xml`:

```bash
java -Dvertx.logger-delegate-factory-class-name=io.vertx.core.logging.Log4j2LogDelegateFactory \
     -Dlog4j2.configurationFile=file:log4j2.properties \
     -cp <classpath> com.flauntik.guice.GuiceVertxLauncher \
     run com.flauntik.verticle.MainVerticle -conf config.json --debug
```

`config.json` at the repo root supplies `HermesConfig` (port, profile, per-verticle deployment options, per-org Twilio creds under `orgs`). Org flow definitions are loaded from the classpath (`flows/orgs-registry.json` + `flows/{orgId}/flow.json` under `src/main/resources/`), so they work the same whether run from the IDE or the packaged fat jar.

## Validation Notes

- If `mvn compile` fails with "cannot find symbol" for methods/fields that are clearly
  Lombok-generated (`getX`/`setX`/`log` from `@Data`/`@Log4j2`), this is not a code bug —
  it means `JAVA_HOME` is unset or points at a JDK newer than Lombok supports (this repo
  targets `release: 21` in `pom.xml`; running Maven under a much newer JDK breaks Lombok
  annotation processing silently, with no error naming Lombok at all). Fix:
  `export JAVA_HOME=$(/usr/libexec/java_home -v 21)` (or point directly at an installed
  Corretto/Temurin 21 home) before invoking `mvn`.
- Do not assume a `src/test` tree exists; check before referencing test commands.
- If you add tests, wire them into `mvn test` and update this rule and `.agents/manifest.json` in the same change.
- If you change the build layout (source roots, shading config, launcher class), update `pom.xml`, `.run/Hermes-Run.run.xml`, and this rule together.

## Useful Commands

```bash
mvn -q -DskipTests compile
mvn package
java --version
mvn --version
python3 .agents/scripts/validate-agent-assets.py --mode adapters
```
