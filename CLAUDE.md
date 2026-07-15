# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

Shared agent instructions for this repo live under [.agents/](.agents/README.md), not here — this file is a thin pointer so Claude Code and other tools stay in sync with a single source of truth.

Read, in order:

1. [.agents/README.md](.agents/README.md) — how the `.agents/` directory is organized and how to use it.
2. [.agents/rules/hermes-architecture.md](.agents/rules/hermes-architecture.md) — what Hermes is and how it's built.
3. [.agents/rules/maven-build.md](.agents/rules/maven-build.md) — build, run, and validation commands.
4. Every other file under `.agents/rules/` — read all of them before working in this repo.

When you learn something new about this repo that future agents need, update the relevant file under `.agents/` (not this file) and keep `.agents/manifest.json` current. See `.agents/rules/agent-asset-maintenance.md` for the maintenance rules.
