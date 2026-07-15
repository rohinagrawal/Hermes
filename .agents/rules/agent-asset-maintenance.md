---
description: Shared agent asset improvement rules for Quest
alwaysApply: false
---

# Agent Asset Maintenance Rules

Apply this rule when using, debugging, modifying, or adding shared agent assets under `.agents/`, including skills, tools, rules, MCP servers, adapters, and their documentation.

## Continuous Improvement

When a shared agent asset is used and the actual workflow reveals repeated token waste, slow responses, noisy output, fragile request construction, brittle instructions, missing debugging ergonomics, or repeated manual steps, it may enhance itself by turning that learning into a durable improvement in its own code, instructions, tools, examples, or docs. If the learning belongs elsewhere, update the closest shared asset that owns the behavior.

Do not change shared agent assets just because a related solution, module, or code path changed elsewhere. Do not make every application-code change trigger agent asset changes. Instead, when an asset is used and the workflow reveals friction, optimize that asset. That is the intended self-improving loop.

Improve an asset only when that asset is being used or maintained and the learning affects its surface or instructions.

Useful improvements can include:

- Smaller default responses, summary-first modes, field selection, pagination helpers, response caps, or redaction.
- New focused tools or skill steps for common debugging paths.
- Safer request builders, dry-run/normalize modes, or clearer mutation guards.
- Updated README guidance, examples, rules, or skills that reduce repeated manual work.
- Shared-source updates under `.agents/` with thin `.codex/` and `.cursor/` adapters where tool-specific exposure is needed.
- Manifest updates in `.agents/manifest.json` when shared assets, host adapters, or MCP tool surfaces are added, moved, renamed, or removed.
- Ephemeral session artifacts (draft prompts, scratch notes, MCP args) under `.agents/workspace/`, not at the `.agents/` root.

## Standard Entrypoints

Prefer open or tool-recognized entrypoints over proprietary duplication. For
repo-wide coding-agent instructions, keep root `AGENTS.md` as the predictable
entrypoint and keep the substantive source in `.agents/README.md` through a
symlink or thin compatibility file.

For additional tools, expose `.agents/` assets through that tool's native config
or discovery path, but keep the adapter as small as possible. If a subtree needs
local overrides, use the closest scoped `AGENTS.md` and keep it limited to the
subtree-specific delta.

## Compatibility

Preserve existing functionality unless the user explicitly requests a breaking change.

- Keep exact/raw output available when a compact default is added.
- Keep tool names and argument compatibility where feasible.
- Keep Codex and Cursor behavior in parity unless there is a deliberate tool-specific reason for a split.
- When a shared asset changes, update every applicable adapter for that asset in the same change. Use `.agents/manifest.json` `adapter_hosts` as the source of truth; if a host is no longer applicable, update the manifest and explain why.
- Never commit tokens or secrets in config, server code, skills, rules, examples, or docs.
- Keep unrelated worktree edits untouched.

## Validation

Before reporting a shared agent asset change as complete, validate the affected behavior thoroughly:

1. Run syntax/compile checks for changed executable code.
2. For MCP servers, verify `initialize` and `tools/list` through the configured adapter path.
3. Exercise at least one representative existing workflow to check backward compatibility.
4. Exercise each new or changed tool, skill path, rule behavior, or helper, including compact/default behavior and exact/raw fallback when applicable.
5. For live services, prefer read-only validation; require explicit user approval for mutating calls.
6. If both Codex and Cursor adapters expose the asset, verify both adapter paths or explain why one could not be validated.
7. Update the relevant README, skill, rule, or tool guidance that future agents should use.
8. Run `python3 .agents/scripts/validate-agent-assets.py` for full validation, or at minimum `python3 .agents/scripts/validate-agent-assets.py --mode adapters` for adapter-only drift checks when the change is documentation-only.
