import assert from "node:assert/strict";

const SERVER_URL = "http://127.0.0.1:3200/api/mcp";
const PROTOCOL_VERSION = "2025-06-18";
const EXPECTED_TOOLS = [
  "traceql-search",
  "traceql-metrics-instant",
  "traceql-metrics-range",
  "get-trace",
  "get-attribute-names",
  "get-attribute-values",
  "docs-traceql",
];
let nextId = 1;
let sessionId;

function headers() {
  const result = {
    Accept: "application/json, text/event-stream",
    "Content-Type": "application/json",
    "MCP-Protocol-Version": PROTOCOL_VERSION,
  };
  if (sessionId) {
    result["Mcp-Session-Id"] = sessionId;
  }
  return result;
}

function parseResponse(body, contentType, expectedId) {
  const payloads = contentType.includes("text/event-stream")
    ? body
        .split("\n")
        .filter((line) => line.startsWith("data:"))
        .map((line) => JSON.parse(line.slice("data:".length).trim()))
    : [JSON.parse(body)];
  const message = payloads.find((payload) => payload.id === expectedId);
  assert.ok(message, `Tempo returned no MCP response for request ${expectedId}`);
  if (message.error) {
    throw new Error(`Tempo MCP error: ${JSON.stringify(message.error)}`);
  }
  return message.result;
}

async function post(payload, timeoutMs) {
  const response = await fetch(SERVER_URL, {
    method: "POST",
    headers: headers(),
    body: JSON.stringify(payload),
    signal: AbortSignal.timeout(timeoutMs),
  });
  assert.ok(response.ok, `Tempo ${payload.method} returned HTTP ${response.status}`);
  sessionId = response.headers.get("mcp-session-id") ?? sessionId;
  return response;
}

async function request(method, params = {}) {
  const id = nextId++;
  const response = await post({ jsonrpc: "2.0", id, method, params }, 30_000);
  return parseResponse(
    await response.text(),
    response.headers.get("content-type") ?? "",
    id,
  );
}

async function notify(method, params = {}) {
  await post({ jsonrpc: "2.0", method, params }, 20_000);
}

async function closeSession() {
  if (!sessionId) return;
  const response = await fetch(SERVER_URL, {
    method: "DELETE",
    headers: headers(),
    signal: AbortSignal.timeout(10_000),
  });
  assert.ok(response.ok, `Tempo session close returned HTTP ${response.status}`);
}

try {
  const initialized = await request("initialize", {
    protocolVersion: PROTOCOL_VERSION,
    capabilities: {},
    clientInfo: { name: "rulepilot-tempo-mcp-smoke", version: "1.0.0" },
  });
  assert.equal(initialized.serverInfo?.name, "tempo");
  assert.ok(sessionId, "Tempo did not establish an MCP session");
  await notify("notifications/initialized");

  const listed = await request("tools/list");
  const tools = listed.tools ?? [];
  assert.deepEqual(
    tools.map((tool) => tool.name).sort(),
    EXPECTED_TOOLS.toSorted(),
    "Tempo exposed a different tool surface than its pinned 2.10.5 contract",
  );
  for (const tool of tools) {
    assert.equal(tool.annotations?.readOnlyHint, true, `${tool.name} is not read-only`);
    assert.equal(tool.annotations?.destructiveHint, false, `${tool.name} is destructive`);
    assert.equal(tool.annotations?.openWorldHint, false, `${tool.name} is open-world`);
  }

  const query = await request("tools/call", {
    name: "traceql-search",
    arguments: { query: '{ resource.service.name = "rulepilot" }' },
  });
  assert.notEqual(query.isError, true, JSON.stringify(query.content));
  assert.ok(query.content?.length > 0, "Tempo returned no TraceQL result content");

  console.log(
    `PASS initialized ${initialized.serverInfo.name} ${initialized.serverInfo.version}`,
  );
  console.log("PASS verified seven local Tempo tools are read-only and closed-world");
  console.log("PASS queried local RulePilot traces through Tempo MCP");
} finally {
  await closeSession();
}
