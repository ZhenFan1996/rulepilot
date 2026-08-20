import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import test from "node:test";

const ROOT_DIR = join(dirname(fileURLToPath(import.meta.url)), "..");
const TEMPO_TOOLS = [
  "traceql-search",
  "traceql-metrics-instant",
  "traceql-metrics-range",
  "get-trace",
  "get-attribute-names",
  "get-attribute-values",
  "docs-traceql",
];

function read(relativePath) {
  return readFileSync(join(ROOT_DIR, relativePath), "utf8");
}

function tempoSection(config) {
  const match = config.match(
    /\[mcp_servers\.rulepilot_tempo\]([\s\S]*?)(?=\n\[|$)/,
  );
  assert.ok(match, "project config must declare rulepilot_tempo");
  return match[1];
}

function quotedArray(section, key) {
  const match = section.match(
    new RegExp(`${key}\\s*=\\s*\\[([\\s\\S]*?)\\]`),
  );
  assert.ok(match, `${key} must be a TOML array`);
  return [...match[1].matchAll(/"([^"]+)"/g)].map((item) => item[1]);
}

test("Codex exposes only Tempo 2.10.5's seven local read-only trace tools", () => {
  const section = tempoSection(read(".codex/config.toml"));

  assert.match(section, /url\s*=\s*"http:\/\/127\.0\.0\.1:3200\/api\/mcp"/);
  assert.match(section, /enabled\s*=\s*true/);
  assert.match(section, /required\s*=\s*false/);
  assert.match(section, /default_tools_approval_mode\s*=\s*"writes"/);
  assert.deepEqual(quotedArray(section, "enabled_tools"), TEMPO_TOOLS);
  assert.doesNotMatch(section, /bearer_token|http_headers|env\s*=/);
});

test("Tempo MCP stays loopback-only and is not duplicated through Grafana", () => {
  const compose = read("infra/compose.yml");
  const tempo = read("infra/observability/tempo.yml");
  const grafanaLauncher = read("scripts/run-grafana-mcp.sh");

  assert.match(compose, /"127\.0\.0\.1:\$\{TEMPO_PORT:-3200\}:3200"/);
  assert.match(tempo, /query_frontend:\s*\n\s+mcp_server:\s*\n\s+enabled:\s*true/);
  assert.match(grafanaLauncher, /--disable-proxied/);
});

test("Tempo MCP has an explicit live smoke outside normal CI", () => {
  const makefile = read("Makefile");
  const smoke = read("scripts/smoke-tempo-mcp.mjs");

  assert.match(makefile, /^mcp-tempo-smoke:/m);
  assert.match(
    makefile,
    /^mcp-smoke: mcp-grafana-smoke mcp-tempo-smoke mcp-context7-smoke/m,
  );
  assert.match(smoke, /http:\/\/127\.0\.0\.1:3200\/api\/mcp/);
  for (const tool of TEMPO_TOOLS) {
    assert.ok(smoke.includes(`"${tool}"`), `smoke does not verify ${tool}`);
  }
  assert.doesNotMatch(smoke, /process\.env/);
});
