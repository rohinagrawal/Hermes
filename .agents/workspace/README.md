# Agent Workspace

Ephemeral scratch space for agent sessions. Put temporary payloads, draft prompts,
MCP call args, and other one-off artifacts here — not at the `.agents/` root.

## Layout

- `prompts/` — draft agent prompts and extracted notes
- `mcp/` — MCP invocation args and request/response scratch files
- `drafts/` — in-progress problem statements, design notes, or review comments

Create additional subfolders as needed for a task. Prefer descriptive names and
date prefixes when keeping artifacts longer than a single session.

## Rules

- Do not commit workspace contents; they are gitignored except this README.
- Do not treat files here as shared agent assets. Durable skills, rules, MCP
  code, and docs belong under `.agents/skills/`, `.agents/rules/`, `.agents/mcp/`,
  or the relevant skill `references/` directory.
- Delete or archive stale files periodically so local workspace stays small.
