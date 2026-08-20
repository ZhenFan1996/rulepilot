import assert from "node:assert/strict";

const SERVER_URL = "https://mcp.context7.com/mcp";
const PROTOCOL_VERSION = "2025-06-18";
const EXPECTED_TOOLS = ["resolve-library-id", "query-docs"];
let nextId = 1;

function headers() {
  const result = {
    Accept: "application/json, text/event-stream",
    "Content-Type": "application/json",
    "MCP-Protocol-Version": PROTOCOL_VERSION,
  };
  if (process.env.CONTEXT7_API_KEY) {
    result.Authorization = `Bearer ${process.env.CONTEXT7_API_KEY}`;
  }
  return result;
}

function parseResponse(body, expectedId) {
  const payloads = body
    .split("\n")
    .filter((line) => line.startsWith("data:"))
    .map((line) => JSON.parse(line.slice("data:".length).trim()));
  const message = payloads.find((payload) => payload.id === expectedId);
  assert.ok(message, `Context7 returned no MCP response for request ${expectedId}`);
  if (message.error) {
    throw new Error(`Context7 MCP error: ${JSON.stringify(message.error)}`);
  }
  return message.result;
}

async function request(method, params = {}) {
  const id = nextId++;
  const response = await fetch(SERVER_URL, {
    method: "POST",
    headers: headers(),
    body: JSON.stringify({ jsonrpc: "2.0", id, method, params }),
    signal: AbortSignal.timeout(30_000),
  });
  assert.ok(response.ok, `Context7 ${method} returned HTTP ${response.status}`);
  return parseResponse(await response.text(), id);
}

async function notify(method, params = {}) {
  const response = await fetch(SERVER_URL, {
    method: "POST",
    headers: headers(),
    body: JSON.stringify({ jsonrpc: "2.0", method, params }),
    signal: AbortSignal.timeout(20_000),
  });
  assert.ok(response.ok, `Context7 ${method} returned HTTP ${response.status}`);
}

const initialized = await request("initialize", {
  protocolVersion: PROTOCOL_VERSION,
  capabilities: {},
  clientInfo: { name: "rulepilot-context7-smoke", version: "1.0.0" },
});
assert.equal(initialized.serverInfo?.name, "Context7");
await notify("notifications/initialized");

const listed = await request("tools/list");
const tools = listed.tools ?? [];
for (const expected of EXPECTED_TOOLS) {
  const tool = tools.find((candidate) => candidate.name === expected);
  assert.ok(tool, `Context7 is missing ${expected}`);
  assert.equal(tool.annotations?.readOnlyHint, true, `${expected} is not read-only`);
  assert.equal(
    tool.annotations?.destructiveHint,
    false,
    `${expected} is destructive`,
  );
}

const resolved = await request("tools/call", {
  name: "resolve-library-id",
  arguments: {
    libraryName: "Spring Modulith",
    query: "application module verification",
  },
});
assert.notEqual(resolved.isError, true, JSON.stringify(resolved.content));
assert.ok(resolved.content?.length > 0, "Context7 returned no library matches");

console.log(
  `PASS initialized ${initialized.serverInfo.name} ${initialized.serverInfo.version}`,
);
console.log("PASS verified both Context7 tools are read-only and non-destructive");
console.log("PASS resolved Spring Modulith documentation without project data");
