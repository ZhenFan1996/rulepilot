import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import test from "node:test";

const ROOT_DIR = join(dirname(fileURLToPath(import.meta.url)), "..");

function read(relativePath) {
  return readFileSync(join(ROOT_DIR, relativePath), "utf8");
}

test("root instructions route future Codex tasks to relevant MCP evidence", () => {
  const agents = read("AGENTS.md");
  const section = agents.match(/## MCP usage([\s\S]*?)(?=\n## |$)/)?.[1];
  assert.ok(section, "AGENTS.md must contain repository-wide MCP usage guidance");

  for (const server of [
    "rulepilot_grafana",
    "rulepilot_tempo",
    "rulepilot_context7",
  ]) {
    assert.ok(section.includes(`\`${server}\``), `${server} has no usage route`);
  }
  assert.match(section, /runtime|latency/i);
  assert.match(section, /GitHub plugin/i);
  assert.match(section, /Browser.*Playwright|Playwright.*Browser/is);
  assert.match(section, /codex mcp list/);
  assert.match(section, /make mcp-smoke/);
});

test("persistent MCP guidance keeps optional services and sensitive data safe", () => {
  const agents = read("AGENTS.md");
  const config = read(".codex/config.toml");

  assert.match(agents, /Do not call an irrelevant MCP/i);
  assert.match(agents, /credentials/i);
  assert.match(agents, /copyrighted rulebook/i);
  for (const server of [
    "rulepilot_grafana",
    "rulepilot_tempo",
    "rulepilot_context7",
  ]) {
    const section = config.match(
      new RegExp(`\\[mcp_servers\\.${server}\\]([\\s\\S]*?)(?=\\n\\[|$)`),
    )?.[1];
    assert.ok(section, `${server} is missing from project config`);
    assert.match(section, /required\s*=\s*false/);
  }
});
