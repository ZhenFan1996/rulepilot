import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import test from "node:test";

const ROOT_DIR = join(dirname(fileURLToPath(import.meta.url)), "..");

const CODEX_TOOLS = [
  "search_dashboards",
  "list_datasources",
  "get_datasource",
  "check_datasources_health",
  "list_prometheus_metric_names",
  "list_prometheus_label_names",
  "list_prometheus_label_values",
  "query_prometheus",
  "query_prometheus_histogram",
  "get_dashboard_summary",
  "get_dashboard_property",
  "get_dashboard_panel_queries",
  "generate_deeplink",
];

function read(relativePath) {
  return readFileSync(join(ROOT_DIR, relativePath), "utf8");
}

function mcpSection(config) {
  const match = config.match(
    /\[mcp_servers\.rulepilot_grafana\]([\s\S]*?)(?=\n\[|$)/,
  );
  assert.ok(match, "project config must declare rulepilot_grafana");
  return match[1];
}

function quotedArray(section, key) {
  const match = section.match(
    new RegExp(`${key}\\s*=\\s*\\[([\\s\\S]*?)\\]`),
  );
  assert.ok(match, `${key} must be a TOML array`);
  return [...match[1].matchAll(/"([^"]+)"/g)].map((item) => item[1]);
}

test("Codex exposes only the intended read-only Grafana tools", () => {
  const config = read(".codex/config.toml");
  const section = mcpSection(config);

  assert.match(section, /command\s*=\s*"sh"/);
  assert.deepEqual(quotedArray(section, "args"), [
    "scripts/run-grafana-mcp.sh",
  ]);
  assert.match(section, /required\s*=\s*false/);
  assert.match(section, /default_tools_approval_mode\s*=\s*"writes"/);
  assert.deepEqual(quotedArray(section, "enabled_tools"), CODEX_TOOLS);
  assert.doesNotMatch(
    section,
    /(service[_-]?account[_-]?token|grafana[_-]?password)\s*=/i,
    "project config must not contain Grafana credentials",
  );
});

test("installer pins the official release and launcher removes write surfaces", () => {
  const setup = read("scripts/setup-grafana-mcp.sh");
  const launcher = read("scripts/run-grafana-mcp.sh");

  assert.match(setup, /MCP_VERSION=1\.1\.0/);
  assert.match(
    setup,
    /github\.com\/grafana\/mcp-grafana\/releases\/download\/v\$MCP_VERSION/,
  );
  for (const checksum of [
    "96ccc022d1618a9e9a853f4b765dbaa3f86edeb1b489c1fca7fc150710c9df72",
    "060a71a78d13e9e9f7181b1fa3b3b56c8ed80936a5d254cabafdc2f5e866e715",
    "23074b93313a7ae2ee7770b4cb5b4859f2acf1830e56f39e0cf49ce48a49e8ae",
    "8468b1e159412eb1ab738786cf0d2755a1ea0a44103ca8c0040849a227746e07",
  ]) {
    assert.ok(setup.includes(checksum));
  }
  assert.doesNotMatch(setup, /releases\/latest|@latest/);
  assert.match(
    setup,
    /formula_version[\s\S]*versions\.stable[\s\S]*"\$formula_version" = "\$MCP_VERSION"/,
  );
  assert.match(
    setup,
    /HOMEBREW_NO_AUTO_UPDATE=1 HOMEBREW_NO_INSTALL_CLEANUP=1 brew install mcp-grafana/,
  );
  assert.match(
    setup,
    /"\$brew_installation" = "mcp-grafana \$MCP_VERSION"/,
  );
  assert.match(launcher, /--disable-write/);
  assert.match(launcher, /--disable-proxied/);
  assert.match(
    launcher,
    /--enabled-tools search,datasource,prometheus,dashboard,navigation/,
  );
  assert.match(
    launcher,
    /GRAFANA_SERVICE_ACCOUNT_TOKEN_FILE="\$TOKEN_FILE"/,
  );
  assert.match(
    launcher,
    /"\$brew_installation" = "mcp-grafana \$MCP_VERSION"/,
  );
  assert.doesNotMatch(launcher, /GRAFANA_SERVICE_ACCOUNT_TOKEN=/);
});

test("repository commands keep credential setup explicit and local", () => {
  const makefile = read("Makefile");
  const setup = read("scripts/setup-grafana-mcp.sh");
  const launcher = read("scripts/run-grafana-mcp.sh");
  const smoke = read("scripts/smoke-grafana-mcp.mjs");

  assert.match(makefile, /^mcp-grafana-setup:/m);
  assert.match(makefile, /^mcp-grafana-smoke:/m);
  assert.match(setup, /"role":"Viewer"/);
  assert.match(setup, /umask 077/);
  assert.match(setup, /TOKEN_DIR="\$ROOT_DIR\/\.local\/grafana-mcp"/);
  assert.match(setup, /TOKEN_FILE="\$TOKEN_DIR\/service-account-token"/);
  assert.doesNotMatch(setup, /echo "[^\n]*`/);
  assert.match(launcher, /\.local\/grafana-mcp\/service-account-token/);
  assert.match(launcher, /Run `make mcp-grafana-setup` first/);
  assert.match(smoke, /name: "query_prometheus"/);
  assert.match(smoke, /expr: "up"/);
});
