import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import test from "node:test";

const ROOT_DIR = join(dirname(fileURLToPath(import.meta.url)), "..");

function read(relativePath) {
  return readFileSync(join(ROOT_DIR, relativePath), "utf8");
}

function context7Section(config) {
  const match = config.match(
    /\[mcp_servers\.rulepilot_context7\]([\s\S]*?)(?=\n\[|$)/,
  );
  assert.ok(match, "project config must declare rulepilot_context7");
  return match[1];
}

function quotedArray(section, key) {
  const match = section.match(
    new RegExp(`${key}\\s*=\\s*\\[([\\s\\S]*?)\\]`),
  );
  assert.ok(match, `${key} must be a TOML array`);
  return [...match[1].matchAll(/"([^"]+)"/g)].map((item) => item[1]);
}

test("Codex exposes only the two read-only Context7 documentation tools", () => {
  const section = context7Section(read(".codex/config.toml"));

  assert.match(section, /url\s*=\s*"https:\/\/mcp\.context7\.com\/mcp"/);
  assert.match(section, /bearer_token_env_var\s*=\s*"CONTEXT7_API_KEY"/);
  assert.match(section, /enabled\s*=\s*true/);
  assert.match(section, /required\s*=\s*false/);
  assert.match(section, /default_tools_approval_mode\s*=\s*"writes"/);
  assert.deepEqual(quotedArray(section, "enabled_tools"), [
    "resolve-library-id",
    "query-docs",
  ]);
  assert.doesNotMatch(section, /http_headers\s*=/);
  assert.doesNotMatch(section, /CONTEXT7_API_KEY\s*=\s*"[^\"]+"/);
});

test("Context7 smoke remains explicit and sends only a generic library query", () => {
  const makefile = read("Makefile");
  const smoke = read("scripts/smoke-context7-mcp.mjs");

  assert.match(makefile, /^mcp-context7-smoke:/m);
  assert.match(
    makefile,
    /^mcp-smoke: mcp-grafana-smoke mcp-tempo-smoke mcp-context7-smoke/m,
  );
  assert.match(smoke, /https:\/\/mcp\.context7\.com\/mcp/);
  assert.match(smoke, /libraryName: "Spring Modulith"/);
  assert.match(smoke, /query: "application module verification"/);
  assert.doesNotMatch(smoke, /process\.env\.(?!CONTEXT7_API_KEY)/);
});
