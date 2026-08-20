import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const ROOT_DIR = join(dirname(fileURLToPath(import.meta.url)), "..");
const EXPECTED_TOOLS = [
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

const child = spawn("sh", ["scripts/run-grafana-mcp.sh"], {
  cwd: ROOT_DIR,
  stdio: ["pipe", "pipe", "pipe"],
});

let nextId = 1;
let stdoutBuffer = "";
let stderrBuffer = "";
let processFailure;
const pending = new Map();

const processExited = new Promise((resolve) => {
  child.once("exit", (code, signal) => resolve({ code, signal }));
});

function failPending(error) {
  for (const { reject, timer } of pending.values()) {
    clearTimeout(timer);
    reject(error);
  }
  pending.clear();
}

child.once("error", (error) => {
  processFailure = error;
  failPending(error);
});

child.stderr.on("data", (chunk) => {
  const text = chunk.toString();
  stderrBuffer = `${stderrBuffer}${text}`.slice(-20_000);
  process.stderr.write(text);
});

child.stdout.on("data", (chunk) => {
  stdoutBuffer += chunk.toString();
  for (;;) {
    const newline = stdoutBuffer.indexOf("\n");
    if (newline === -1) {
      return;
    }

    const line = stdoutBuffer.slice(0, newline).trim();
    stdoutBuffer = stdoutBuffer.slice(newline + 1);
    if (!line) {
      continue;
    }

    let message;
    try {
      message = JSON.parse(line);
    } catch (error) {
      processFailure = new Error(
        `Grafana MCP wrote non-JSON data to stdout: ${line.slice(0, 300)}`,
        { cause: error },
      );
      failPending(processFailure);
      continue;
    }

    if (message.id === undefined || !pending.has(message.id)) {
      continue;
    }

    const request = pending.get(message.id);
    pending.delete(message.id);
    clearTimeout(request.timer);
    if (message.error) {
      request.reject(
        new Error(`MCP ${request.method} failed: ${JSON.stringify(message.error)}`),
      );
    } else {
      request.resolve(message.result);
    }
  }
});

child.once("exit", (code, signal) => {
  if (pending.size > 0) {
    const suffix = stderrBuffer.trim() ? `\n${stderrBuffer.trim()}` : "";
    failPending(
      new Error(
        `Grafana MCP exited before responding (code=${code}, signal=${signal}).${suffix}`,
      ),
    );
  }
});

function send(message) {
  child.stdin.write(`${JSON.stringify(message)}\n`);
}

function request(method, params = {}, timeoutMs = 30_000) {
  const id = nextId++;
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      pending.delete(id);
      reject(new Error(`Timed out waiting for MCP ${method}`));
    }, timeoutMs);
    pending.set(id, { method, resolve, reject, timer });
    send({ jsonrpc: "2.0", id, method, params });
  });
}

function notify(method, params = {}) {
  send({ jsonrpc: "2.0", method, params });
}

const overallTimeout = setTimeout(() => {
  processFailure = new Error("Grafana MCP smoke test exceeded 120 seconds");
  failPending(processFailure);
  child.kill("SIGTERM");
}, 120_000);

try {
  const initialized = await request(
    "initialize",
    {
      protocolVersion: "2025-06-18",
      capabilities: {},
      clientInfo: { name: "rulepilot-grafana-mcp-smoke", version: "1.0.0" },
    },
    110_000,
  );
  assert.equal(initialized.serverInfo?.name, "mcp-grafana");
  notify("notifications/initialized");

  const listed = await request("tools/list");
  const tools = listed.tools ?? [];
  const toolNames = new Set(tools.map((tool) => tool.name));
  for (const expected of EXPECTED_TOOLS) {
    assert.ok(toolNames.has(expected), `Grafana MCP is missing ${expected}`);
  }

  const unsafeTools = tools.filter(
    (tool) =>
      tool.annotations?.readOnlyHint !== true ||
      tool.annotations?.destructiveHint === true,
  );
  assert.deepEqual(
    unsafeTools.map((tool) => tool.name),
    [],
    "server exposed a tool without an explicit non-destructive read-only annotation",
  );

  const query = await request("tools/call", {
    name: "query_prometheus",
    arguments: {
      datasourceUid: "prometheus",
      expr: "up",
      endTime: "now",
      queryType: "instant",
    },
  });
  assert.notEqual(query.isError, true, JSON.stringify(query.content));
  assert.ok(query.content?.length > 0, "Prometheus query returned no MCP content");

  console.log(
    `PASS initialized ${initialized.serverInfo.name} ${initialized.serverInfo.version}`,
  );
  console.log(`PASS listed ${tools.length} explicitly read-only tools`);
  console.log("PASS queried local Prometheus through Grafana MCP");
} catch (error) {
  processFailure = error;
} finally {
  clearTimeout(overallTimeout);
  child.stdin.end();
  let forceExitTimer;
  const exited = await Promise.race([
    processExited,
    new Promise((resolve) =>
      (forceExitTimer = setTimeout(() => {
        child.kill("SIGTERM");
        resolve({ code: null, signal: "SIGTERM" });
      }, 5_000)),
    ),
  ]);
  clearTimeout(forceExitTimer);
  if (!processFailure && exited.code !== 0) {
    processFailure = new Error(
      `Grafana MCP did not exit cleanly (code=${exited.code}, signal=${exited.signal})`,
    );
  }
}

if (processFailure) {
  throw processFailure;
}
