# Hermes Agent Guide

This repository keeps shared agent assets under `.agents/`. Every coding-agent surface (Claude Code, Codex, Cursor, or any other tool that reads `AGENTS.md`/`CLAUDE.md`) should use this directory as its single source of truth instead of duplicating instructions in tool-specific files.

The repo root `AGENTS.md` and `CLAUDE.md` are thin compatibility files that point at this guide so every tool gets the same source of truth.

## Project Context

Hermes is a Java 21 / Vert.x WhatsApp chatbot backend, wired with Guice. See `.agents/rules/hermes-architecture.md` for the full architecture map (verticle split, event bus flow, the `flow.json` conversation state machine, and known gaps) and `.agents/rules/maven-build.md` for build/run commands.

## Directory Layout

```
.agents/
├── README.md              # Shared agent guide
├── manifest.json          # Shared asset registry
├── .env.example           # Optional local overrides template
├── rules/                 # Repo-wide agent rules
├── skills/                # Task playbooks and setup helpers
├── mcp/                   # Shared MCP source and docs, if used
├── scripts/               # Validation and maintenance tooling
└── workspace/             # Ephemeral session scratch (gitignored)
    └── README.md          # Workspace conventions (tracked)
```

Keep durable assets in the tracked directories above. Put temporary payloads, draft prompts, and other throwaway artifacts under `.agents/workspace/` instead of the `.agents/` root.

Add `.agents/mcp/` only when this repo needs a shared MCP server or local tool surface.

## Shared Assets

- Shared rules: `.agents/rules/`
- Shared skills: `.agents/skills/`
- Shared MCP source: `.agents/mcp/` when this repo needs an agent-facing service
- Shared asset manifest: `.agents/manifest.json`
- Shared validation command: `python3 .agents/scripts/validate-agent-assets.py`
- Ephemeral session scratch: `.agents/workspace/` (see `.agents/workspace/README.md`)

Tool-specific folders such as `.codex/` and `.cursor/` should be thin adapters or symlinks to `.agents/` wherever possible. Avoid duplicating rule, skill, or MCP content in multiple tool folders.

When adding support for another agent surface, prefer the same pattern:

1. Keep shared instructions, skills, rules, tools, and MCP code under `.agents/`.
2. Expose the shared asset through that tool's native entrypoint or config.
3. Use a symlink or the smallest possible wrapper when the tool cannot read `.agents/` directly.
4. Keep tool-specific behavior in the adapter only when the tool genuinely needs it.
5. Update every applicable adapter for the asset in the same change.
6. Update `.agents/manifest.json` with the source path, docs, adapter coverage, and any exposed tool names.

If a subtree needs different instructions, add the closest scoped `AGENTS.md` for that subtree and keep it limited to the local override. The nearest `AGENTS.md` should be treated as the more specific instruction source for files under that subtree.

## Rules

Before working in this repo, read every rule file under `.agents/rules/*`.

Treat rules with `alwaysApply: true` as baseline instructions for every task. Use rules with `alwaysApply: false` when their title, description, or content matches the task.

## Manifest and Validation

`.agents/manifest.json` lists each shared guide, rule, skill, MCP server, config adapter, and the hosts that consume it. Keep this manifest current whenever adding, moving, or exposing an agent asset.

When changing a shared asset, update every applicable host adapter in the same change. `adapter_hosts` records which hosts need direct adapter coverage; omit a host only when it consumes the asset indirectly or the asset is genuinely not applicable to that host.

Run validation before reporting shared agent asset work complete:

```bash
python3 .agents/scripts/validate-agent-assets.py --mode adapters
```

For a full validation pass that also checks Python compile and Maven compile, run:

```bash
python3 .agents/scripts/validate-agent-assets.py
```

If you only need to inspect MCP startup behavior after adding a server, use:

```bash
python3 .agents/scripts/validate-agent-assets.py --mode mcp
```

## Skills

For quick setup of this shared-agent layout in a new project, use:

- `.agents/skills/agents-bootstrap/SKILL.md`

The source of truth for skills is `.agents/skills/`. If a tool needs a separate install location or symlink, keep that as a thin adapter only.

## Self-Improvement Boundary

Do not make every application-code change trigger agent asset changes. Instead, when a shared asset is used and the workflow reveals friction, improve the closest shared asset that owns that behavior. Examples of real friction include slow or noisy output, fragile request construction, missing guards, unclear docs, stale templates, incorrect metadata, or repeated manual steps.
