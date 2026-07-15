#!/usr/bin/env python3
"""Validate Quest shared agent assets and thin host adapters."""

from __future__ import annotations

import argparse
import json
import os
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path
from typing import Any


REPO_ROOT = Path(__file__).resolve().parents[2]
MANIFEST_PATH = REPO_ROOT / ".agents" / "manifest.json"
PROTOCOL_VERSION = "2025-06-18"


class Reporter:
    def __init__(self) -> None:
        self.failures: list[str] = []
        self.warnings: list[str] = []
        self.passes: list[str] = []

    def ok(self, message: str) -> None:
        self.passes.append(message)

    def warn(self, message: str) -> None:
        self.warnings.append(message)

    def fail(self, message: str) -> None:
        self.failures.append(message)

    def print(self) -> None:
        for message in self.passes:
            print(f"OK   {message}")
        for message in self.warnings:
            print(f"WARN {message}")
        for message in self.failures:
            print(f"FAIL {message}")


def rel(path: Path) -> str:
    try:
        return str(path.resolve().relative_to(REPO_ROOT))
    except ValueError:
        return str(path)


def repo_path(value: str) -> Path:
    return REPO_ROOT / value


def load_manifest(reporter: Reporter) -> dict[str, Any]:
    if not MANIFEST_PATH.is_file():
        reporter.fail(f"missing manifest: {rel(MANIFEST_PATH)}")
        return {}
    try:
        data = json.loads(MANIFEST_PATH.read_text(encoding="utf-8"))
    except json.JSONDecodeError as exc:
        reporter.fail(f"invalid manifest JSON: {exc}")
        return {}
    if data.get("schema_version") != 1:
        reporter.fail("manifest schema_version must be 1")
    return data


def assets_by_id(manifest: dict[str, Any]) -> dict[str, dict[str, Any]]:
    return {asset["id"]: asset for asset in manifest.get("assets", []) if "id" in asset}


def check_paths_and_docs(manifest: dict[str, Any], reporter: Reporter) -> None:
    for asset in manifest.get("assets", []):
        asset_id = asset.get("id", "<unknown>")
        source = asset.get("source")
        if source and not repo_path(source).exists():
            reporter.fail(f"{asset_id}: missing source {source}")
        elif source:
            reporter.ok(f"{asset_id}: source exists")

        for doc in asset.get("docs", []):
            if not repo_path(doc).exists():
                reporter.fail(f"{asset_id}: missing doc {doc}")

        source_path = repo_path(source) if source else None
        if source_path and source_path.is_file() and source_path.suffix in {".py", ".java", ".md", ".json", ".toml"}:
            text = source_path.read_text(encoding="utf-8", errors="replace")
            for marker in ("API_TOKEN=", "SECRET=", "PASSWORD="):
                if marker in text and "<" not in text.split(marker, 1)[1][:20]:
                    reporter.fail(f"{asset_id}: possible committed secret in {source}")


def check_adapters(manifest: dict[str, Any], reporter: Reporter) -> None:
    seen: set[str] = set()
    for asset in manifest.get("assets", []):
        asset_id = asset.get("id", "<unknown>")
        adapter_hosts = set(asset.get("adapter_hosts") or [])
        if asset.get("adapters") and not adapter_hosts:
            reporter.fail(f"{asset_id}: assets with adapters must declare adapter_hosts")
        asset_hosts = set(asset.get("hosts") or [])
        undeclared_hosts = sorted(adapter_hosts - asset_hosts)
        if undeclared_hosts:
            reporter.fail(f"{asset_id}: adapter_hosts not listed in hosts: {', '.join(undeclared_hosts)}")

        adapters_by_host: dict[str, list[dict[str, Any]]] = {}
        for adapter in asset.get("adapters", []):
            adapters_by_host.setdefault(str(adapter.get("host")), []).append(adapter)
            adapter_path = repo_path(adapter["path"])
            target_path = repo_path(adapter["target"])
            key = adapter["path"]
            if key in seen:
                continue
            seen.add(key)
            if adapter.get("type") != "symlink":
                reporter.fail(f"{asset_id}: unsupported adapter type {adapter.get('type')}")
                continue
            if not adapter_path.exists() and not adapter_path.is_symlink():
                reporter.fail(f"{asset_id}: missing adapter {adapter['path']}")
                continue
            if not adapter_path.is_symlink():
                reporter.fail(f"{asset_id}: adapter must be a symlink: {adapter['path']}")
                continue
            if adapter_path.resolve() != target_path.resolve():
                reporter.fail(
                    f"{asset_id}: adapter {adapter['path']} points to {rel(adapter_path.resolve())}, "
                    f"expected {adapter['target']}"
                )
                continue
            reporter.ok(f"{asset_id}: adapter {adapter['path']} -> {adapter['target']}")

        for host in sorted(adapter_hosts):
            if not adapters_by_host.get(host):
                reporter.fail(f"{asset_id}: missing applicable {host} adapter")
            else:
                reporter.ok(f"{asset_id}: applicable {host} adapter covered")


def check_config_references(manifest: dict[str, Any], reporter: Reporter) -> None:
    for asset in manifest.get("assets", []):
        refs = asset.get("config_references") or []
        if not refs:
            continue
        source = asset.get("source")
        path = repo_path(source)
        if not path.is_file():
            reporter.fail(f"{asset.get('id')}: missing config {source}")
            continue
        text = path.read_text(encoding="utf-8", errors="replace")
        for ref in refs:
            if ref not in text:
                reporter.fail(f"{asset.get('id')}: config {source} does not reference {ref}")
        reporter.ok(f"{asset.get('id')}: config references checked")


def git_ls_files(paths: list[str]) -> list[Path]:
    completed = subprocess.run(
        ["git", "ls-files", *paths],
        cwd=REPO_ROOT,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
    )
    if completed.returncode != 0:
        return []
    return [repo_path(line) for line in completed.stdout.splitlines() if line.strip()]


def check_duplicate_adapter_content(reporter: Reporter) -> None:
    allowed_regular = {
        ".codex/config.toml",
        ".codex/environments/environment.toml",
        ".cursor/mcp.json",
    }
    for path in git_ls_files([".codex", ".cursor"]):
        rel_path = rel(path)
        if rel_path in allowed_regular:
            continue
        if rel_path.startswith(".codex/mcp/") or rel_path.startswith(".cursor/mcp/"):
            if not path.is_symlink():
                reporter.fail(f"tracked MCP adapter is not a symlink: {rel_path}")
        if rel_path.startswith(".codex/skills/") or rel_path.startswith(".cursor/skills/"):
            if not path.is_symlink():
                reporter.fail(f"tracked skill adapter is not a symlink: {rel_path}")
        if rel_path == ".cursor/rules" and not path.is_symlink():
            reporter.fail(".cursor/rules must be a symlink to .agents/rules")
    reporter.ok("tracked .codex/.cursor adapter drift checked")


def python_for_command(command: list[str], startup_id: str, reporter: Reporter) -> list[str] | None:
    if not command:
        reporter.fail(f"{startup_id}: empty command")
        return None
    first = command[0]
    if first.startswith("."):
        first_path = repo_path(first)
        if first_path.exists():
            return [str(first_path), *command[1:]]
        return None
    resolved = shutil.which(first)
    if not resolved:
        return None
    return [resolved, *command[1:]]


def run_process(command: list[str], stdin_text: str, timeout_seconds: int, extra_env: dict[str, str] | None = None) -> subprocess.CompletedProcess[str]:
    env = os.environ.copy()
    if extra_env:
        env.update(extra_env)
    with tempfile.TemporaryDirectory(prefix="agent-assets-pycache-") as pycache:
        env.setdefault("PYTHONPYCACHEPREFIX", pycache)
        return subprocess.run(
            command,
            cwd=REPO_ROOT,
            input=stdin_text,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            timeout=timeout_seconds,
            env=env,
            check=False,
        )


def parse_json_lines(stdout: str) -> list[dict[str, Any]]:
    messages: list[dict[str, Any]] = []
    for raw_line in stdout.splitlines():
        line = raw_line.strip()
        if not line.startswith("{"):
            continue
        try:
            messages.append(json.loads(line))
        except json.JSONDecodeError:
            continue
    return messages


def mcp_exchange(command: list[str], messages: list[dict[str, Any]], timeout_seconds: int = 20) -> tuple[int, list[dict[str, Any]], str]:
    stdin_text = "\n".join(json.dumps(message, separators=(",", ":")) for message in messages) + "\n"
    completed = run_process(
        command,
        stdin_text,
        timeout_seconds,
        extra_env={},
    )
    return completed.returncode, parse_json_lines(completed.stdout), completed.stderr


def startup_messages(extra_messages: list[dict[str, Any]] | None = None) -> list[dict[str, Any]]:
    messages = [
        {
            "jsonrpc": "2.0",
            "id": 1,
            "method": "initialize",
            "params": {
                "protocolVersion": PROTOCOL_VERSION,
                "capabilities": {},
                "clientInfo": {"name": "agent-asset-validator", "version": "1"},
            },
        },
        {"jsonrpc": "2.0", "method": "notifications/initialized", "params": {}},
        {"jsonrpc": "2.0", "id": 2, "method": "tools/list", "params": {}},
    ]
    if extra_messages:
        messages.extend(extra_messages)
    return messages


def tools_from_messages(messages: list[dict[str, Any]]) -> list[dict[str, Any]]:
    for message in messages:
        if message.get("id") == 2:
            return ((message.get("result") or {}).get("tools") or [])
    return []


def property_schema(tool: dict[str, Any], prop: str) -> dict[str, Any]:
    return (((tool.get("inputSchema") or {}).get("properties") or {}).get(prop) or {})


def check_summary_first(asset: dict[str, Any], host: str, tools: list[dict[str, Any]], reporter: Reporter, startup_id: str) -> None:
    by_name = {tool.get("name"): tool for tool in tools}
    raw_modes = set(asset.get("raw_modes") or ["raw"])
    for tool_name in (asset.get("summary_first_tools") or {}).get(host, []):
        tool = by_name.get(tool_name)
        if not tool:
            reporter.fail(f"{startup_id}: missing summary-first tool {tool_name}")
            continue
        response_mode = property_schema(tool, "response_mode")
        if response_mode.get("default") != "summary":
            reporter.fail(f"{startup_id}: {tool_name}.response_mode must default to summary")
        enum_values = set(response_mode.get("enum") or [])
        description = str(response_mode.get("description") or tool.get("description") or "").lower()
        if not raw_modes.intersection(enum_values) and "raw" not in description:
            reporter.fail(f"{startup_id}: {tool_name} does not advertise raw/exact mode")
    if (asset.get("summary_first_tools") or {}).get(host):
        reporter.ok(f"{startup_id}: summary-first/raw schemas checked")


def check_mutation_schema(asset: dict[str, Any], tools: list[dict[str, Any]], reporter: Reporter, startup_id: str) -> None:
    by_name = {tool.get("name"): tool for tool in tools}
    for tool_name in asset.get("mutation_guard_tools") or []:
        tool = by_name.get(tool_name)
        if not tool:
            reporter.fail(f"{startup_id}: missing mutation guard tool {tool_name}")
            continue
        execute_default = property_schema(tool, "execute").get("default")
        allow_mutation = property_schema(tool, "allow_mutation")
        if execute_default is not False:
            reporter.fail(f"{startup_id}: {tool_name}.execute must default to false")
        if allow_mutation.get("default") is not False:
            reporter.fail(f"{startup_id}: {tool_name}.allow_mutation must default to false")
    if asset.get("mutation_guard_tools"):
        reporter.ok(f"{startup_id}: mutation guard schemas checked")


def check_mutation_runtime(command: list[str], reporter: Reporter, startup_id: str) -> None:
    probe = {
        "jsonrpc": "2.0",
        "id": 3,
        "method": "tools/call",
        "params": {
            "name": "cm_call",
            "arguments": {
                "service": "ClientBackendService",
                "method": "updateTitleOfConversation",
                "request_json": {},
                "execute": True,
            },
        },
    }
    returncode, messages, stderr = mcp_exchange(command, startup_messages([probe]))
    if returncode != 0:
        reporter.fail(f"{startup_id}: mutation probe failed to start: {stderr[-1000:]}")
        return
    result = next((message.get("result") for message in messages if message.get("id") == 3), None)
    if not isinstance(result, dict) or not result.get("isError"):
        reporter.fail(f"{startup_id}: mutating cm_call execute=true without allow_mutation was not rejected")
        return
    structured = result.get("structuredContent") or {}
    if "mcp_telemetry" not in structured:
        reporter.fail(f"{startup_id}: mutation guard response is missing mcp_telemetry")
        return
    reporter.ok(f"{startup_id}: mutation guard runtime checked")


def resolve_startup_command(startup: dict[str, Any], reporter: Reporter) -> list[str] | None:
    command = python_for_command(startup.get("command") or [], startup["id"], reporter)
    if command is not None:
        return command
    fallback = startup.get("fallback_command")
    if fallback:
        fallback_command = python_for_command(fallback, startup["id"], reporter)
        if fallback_command is not None:
            reporter.warn(f"{startup['id']}: using fallback command {fallback}")
            return fallback_command
    reporter.fail(f"{startup['id']}: command not available: {startup.get('command')}")
    return None


def check_mcp_startups(manifest: dict[str, Any], reporter: Reporter) -> None:
    assets = assets_by_id(manifest)
    for startup in manifest.get("mcp_startups", []):
        startup_id = startup["id"]
        asset = assets.get(startup["asset"])
        if not asset:
            reporter.fail(f"{startup_id}: unknown asset {startup['asset']}")
            continue
        command = resolve_startup_command(startup, reporter)
        if command is None:
            continue
        try:
            returncode, messages, stderr = mcp_exchange(command, startup_messages())
        except subprocess.TimeoutExpired:
            reporter.fail(f"{startup_id}: MCP startup timed out")
            continue
        if returncode != 0:
            reporter.fail(f"{startup_id}: MCP process exited {returncode}: {stderr[-1000:]}")
            continue
        tools = tools_from_messages(messages)
        if not tools:
            reporter.fail(f"{startup_id}: tools/list returned no tools")
            continue
        expected = set((asset.get("tools") or {}).get(startup["host"], []))
        actual = {tool.get("name") for tool in tools}
        missing = sorted(expected - actual)
        if missing:
            reporter.fail(f"{startup_id}: missing tools: {', '.join(missing)}")
        else:
            reporter.ok(f"{startup_id}: tools/list returned {len(actual)} tools")
        check_summary_first(asset, startup["host"], tools, reporter, startup_id)
        check_mutation_schema(asset, tools, reporter, startup_id)
        if startup.get("mutation_guard_probe"):
            check_mutation_runtime(command, reporter, startup_id)


def _parse_codex_toml_mcp_servers(config_path: Path) -> dict[str, dict[str, Any]]:
    """Parse [mcp_servers.X] sections from a Codex config.toml without requiring tomllib."""
    import re as _re

    text = config_path.read_text(encoding="utf-8")
    servers: dict[str, dict[str, Any]] = {}
    current_server: str | None = None
    current_sub: str | None = None

    for line in text.splitlines():
        stripped = line.strip()
        header_match = _re.match(r"^\[mcp_servers\.([A-Za-z0-9_-]+)\]$", stripped)
        sub_match = _re.match(r"^\[mcp_servers\.([A-Za-z0-9_-]+)\.([A-Za-z0-9_-]+)\]$", stripped)
        if sub_match:
            current_server = sub_match.group(1)
            current_sub = sub_match.group(2)
            servers.setdefault(current_server, {})[current_sub] = {}
        elif header_match:
            current_server = header_match.group(1)
            current_sub = None
            servers.setdefault(current_server, {})
        elif "=" in stripped and current_server:
            key, val = stripped.split("=", 1)
            key = key.strip()
            val = val.strip()
            if val.startswith('"') and val.endswith('"'):
                val = val[1:-1]
            elif val.startswith("[") and val.endswith("]"):
                val = json.loads(val)
            elif val == "true":
                val = True
            elif val == "false":
                val = False
            else:
                try:
                    val = int(val)
                except ValueError:
                    pass
            target = servers[current_server]
            if current_sub:
                if not isinstance(target.get(current_sub), dict):
                    target[current_sub] = {}
                target[current_sub][key] = val
            else:
                target[key] = val

    return servers


def check_host_config_startups(manifest: dict[str, Any], reporter: Reporter) -> None:
    """Verify MCP servers start using the exact command from host config files.

    This catches mismatches where the validator's own test command works (e.g. using
    a venv python) but the actual host config uses a different interpreter that lacks
    required dependencies.
    """
    for asset in manifest.get("assets", []):
        if asset.get("kind") != "config":
            continue
        source = asset.get("source", "")
        asset_id = asset.get("id", "<unknown>")
        config_path = repo_path(source)
        if not config_path.is_file():
            continue

        servers: dict[str, dict[str, Any]] = {}
        if source.endswith(".json"):
            try:
                config_data = json.loads(config_path.read_text(encoding="utf-8"))
                servers = config_data.get("mcpServers", {})
            except (json.JSONDecodeError, KeyError):
                continue
        elif source.endswith(".toml"):
            try:
                servers = _parse_codex_toml_mcp_servers(config_path)
            except Exception:
                continue
        else:
            continue

        for server_name, server_conf in servers.items():
            command_str = server_conf.get("command", "")
            args = server_conf.get("args", [])
            if not command_str:
                continue

            if command_str.startswith(".") or command_str.startswith("/"):
                command_path = repo_path(command_str) if command_str.startswith(".") else Path(command_str)
                if not command_path.exists():
                    reporter.fail(
                        f"{asset_id}/{server_name}: command not found: {command_str}"
                    )
                    continue
                full_command = [str(command_path)] + [str(a) for a in args]
            else:
                resolved = shutil.which(command_str)
                if not resolved:
                    reporter.fail(
                        f"{asset_id}/{server_name}: command not in PATH: {command_str}"
                    )
                    continue
                full_command = [resolved] + [str(a) for a in args]

            extra_env = {}
            if server_conf.get("env"):
                extra_env.update(server_conf["env"])

            stdin_text = "\n".join(
                json.dumps(m, separators=(",", ":"))
                for m in startup_messages()
            ) + "\n"

            try:
                env = os.environ.copy()
                env.update(extra_env)
                with tempfile.TemporaryDirectory(prefix="agent-assets-pycache-") as pycache:
                    env.setdefault("PYTHONPYCACHEPREFIX", pycache)
                    completed = subprocess.run(
                        full_command,
                        cwd=REPO_ROOT,
                        input=stdin_text,
                        text=True,
                        stdout=subprocess.PIPE,
                        stderr=subprocess.PIPE,
                        timeout=20,
                        env=env,
                        check=False,
                    )
            except subprocess.TimeoutExpired:
                reporter.fail(f"{asset_id}/{server_name}: host config startup timed out")
                continue

            if completed.returncode != 0:
                stderr_tail = completed.stderr.strip().splitlines()
                last_lines = "\n".join(stderr_tail[-5:]) if stderr_tail else "(no stderr)"
                reporter.fail(
                    f"{asset_id}/{server_name}: host config startup failed (exit {completed.returncode}): {last_lines}"
                )
                continue

            messages = parse_json_lines(completed.stdout)
            tools = tools_from_messages(messages)
            if not tools:
                reporter.fail(f"{asset_id}/{server_name}: host config startup returned no tools")
                continue

            reporter.ok(f"{asset_id}/{server_name}: host config startup OK ({len(tools)} tools)")


AGENTS_ROOT = REPO_ROOT / ".agents"
ALLOWED_AGENTS_ROOT_ENTRIES = {
    "README.md",
    "manifest.json",
    ".env.example",
    ".env.local",
    "rules",
    "skills",
    "mcp",
    "scripts",
    "workspace",
}


def check_agents_root_layout(reporter: Reporter) -> None:
    if not AGENTS_ROOT.is_dir():
        reporter.fail("missing .agents directory")
        return

    unexpected: list[str] = []
    for entry in sorted(AGENTS_ROOT.iterdir(), key=lambda path: path.name):
        if entry.name not in ALLOWED_AGENTS_ROOT_ENTRIES:
            unexpected.append(rel(entry))

    if unexpected:
        reporter.fail(
            "unexpected entries at .agents/ root; move ephemeral files to "
            f".agents/workspace/: {', '.join(unexpected)}"
        )
    else:
        reporter.ok(".agents/ root layout is clean")

    workspace_readme = AGENTS_ROOT / "workspace" / "README.md"
    if not workspace_readme.is_file():
        reporter.fail("missing workspace guide: .agents/workspace/README.md")
    else:
        reporter.ok("workspace guide exists")


def check_python_compile(reporter: Reporter) -> None:
    python_files = sorted({str(path) for path in (REPO_ROOT / ".agents").rglob("*.py")})
    if not python_files:
        reporter.warn("no Python files found under .agents")
        return
    with tempfile.TemporaryDirectory(prefix="agent-assets-pycache-") as pycache:
        env = os.environ.copy()
        env["PYTHONPYCACHEPREFIX"] = pycache
        completed = subprocess.run(
            ["python3", "-m", "py_compile", *python_files],
            cwd=REPO_ROOT,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            env=env,
            check=False,
        )
    if completed.returncode != 0:
        reporter.fail(f"Python compile failed: {completed.stderr[-2000:]}")
    else:
        reporter.ok(f"Python compile checked {len(python_files)} files")


def check_java_compile(reporter: Reporter, timeout_seconds: int) -> None:
    completed = subprocess.run(
        ["mvn", "-q", "-DskipTests", "compile"],
        cwd=REPO_ROOT,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        timeout=timeout_seconds,
        check=False,
    )
    if completed.returncode != 0:
        reporter.fail(f"Java compile failed: {(completed.stderr or completed.stdout)[-3000:]}")
    else:
        reporter.ok("Java compile checked with mvn -q -DskipTests compile")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mode", choices=["all", "adapters", "mcp", "compile"], default="all")
    parser.add_argument("--skip-java", action="store_true", help="Skip Maven compile in all/compile mode.")
    parser.add_argument("--java-timeout-seconds", type=int, default=300)
    args = parser.parse_args()

    reporter = Reporter()
    manifest = load_manifest(reporter)
    if not manifest:
        reporter.print()
        return 1

    if args.mode in {"all", "adapters"}:
        check_paths_and_docs(manifest, reporter)
        check_adapters(manifest, reporter)
        check_config_references(manifest, reporter)
        check_duplicate_adapter_content(reporter)
        check_agents_root_layout(reporter)

    if args.mode in {"all", "mcp"}:
        check_mcp_startups(manifest, reporter)
        check_host_config_startups(manifest, reporter)

    if args.mode in {"all", "compile"}:
        check_python_compile(reporter)
        if args.skip_java:
            reporter.warn("Java compile skipped by --skip-java")
        else:
            check_java_compile(reporter, args.java_timeout_seconds)

    reporter.print()
    return 1 if reporter.failures else 0


if __name__ == "__main__":
    raise SystemExit(main())
