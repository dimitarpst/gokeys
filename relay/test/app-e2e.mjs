// A real gokeys.exe (debug, GOKEYS_PROFILE=e2e, --tray so no window) against `npm run dev`, with this script as the
// other PC: the app registers itself, gets paired, publishes its keys, applies a remote edit, and starts over
// in a room of its own when unpaired. Its one key is F13, so the test press types nothing visible.
// Run from the repo root:  node relay/test/app-e2e.mjs
import { spawn } from "node:child_process";
import fs from "node:fs";
import path from "node:path";

const BASE = "http://localhost:8787";
const settings = path.join(process.env.APPDATA, "gokeys", "settings-e2e.properties");
const exe = path.resolve("build/bin/mingwX64/debugExecutable/gokeys.exe");
const ok = (cond, what) => { if (!cond) throw new Error("FAIL " + what); console.log("ok  " + what); };
const wait = async (fn, what, ms = 15000) => {
  const start = Date.now();
  while (Date.now() - start < ms) { const v = await fn(); if (v) return v; await new Promise((r) => setTimeout(r, 200)); }
  throw new Error("timed out: " + what);
};
const token = () => fs.readFileSync(settings, "utf8").match(/^token=(.+)$/m)?.[1]?.trim();

fs.mkdirSync(path.dirname(settings), { recursive: true });
fs.writeFileSync(settings, "name=E2E-PC\npaused=false\nkey=k1|64:7c:0|Test key\n");
const app = spawn(exe, ["--tray"], { env: { ...process.env, GOKEYS_PROFILE: "e2e" }, detached: false });
try {
  const first = await wait(token, "the app registers");
  ok(first.split(".").length === 3, "the app registered itself with the relay");

  const code = (await (await fetch(BASE + "/code", { method: "POST", headers: { authorization: `Bearer ${first}` } })).json()).code;
  const joined = await (await fetch(BASE + "/join", { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify({ code, name: "SCRIPT-PC" }) })).json();
  ok(!!joined.token, "this script paired with the app's code");

  const ws = new WebSocket(BASE.replace("http", "ws") + "/live", { headers: { authorization: `Bearer ${joined.token}` } });
  const inbox = [];
  ws.onmessage = (e) => e.data !== "pong" && inbox.push(JSON.parse(e.data));
  await new Promise((r) => (ws.onopen = r));
  ws.send(JSON.stringify({ t: "hello", name: "SCRIPT-PC", keys: [] }));
  const room = () => [...inbox].reverse().find((m) => m.t === "room");
  const appPc = () => room()?.pcs.find((p) => p.name === "E2E-PC");

  const pc = await wait(() => appPc()?.online && appPc(), "the app shows up online");
  ok(pc.keys.length === 1 && pc.keys[0].name === "Test key" && pc.keys[0].label === "F13", `the app publishes its key (${pc.keys[0]?.label})`);

  ws.send(JSON.stringify({ t: "edit", to: pc.id, key: "k1", name: "Renamed" }));
  await wait(() => appPc()?.keys[0]?.name === "Renamed", "the app applies the edit");
  ok(fs.readFileSync(settings, "utf8").includes("|Renamed"), "the edit is saved in the app's settings");

  ws.send(JSON.stringify({ t: "edit", to: pc.id, key: "k2", name: "Added remotely", shortcut: "1d:a2:0,64:7c:0" }));
  await wait(() => appPc()?.keys.some((k) => k.name === "Added remotely" && k.label.endsWith("F13")), "a key added from here");
  ok(true, "a key added from another PC appears with its label");

  ws.send(JSON.stringify({ t: "press", to: pc.id, key: "k1" })); // F13 on this PC: nothing visible
  await new Promise((r) => setTimeout(r, 500));
  ok(app.exitCode === null, "the app is still running after a press");

  ws.send(JSON.stringify({ t: "remove", pc: pc.id }));
  const fresh = await wait(() => { const t = token(); return t && t !== first && t; }, "the app starts over");
  ok(fresh.split(".")[0] !== first.split(".")[0], "unpaired, the app registered a room of its own");
  ws.close();
  console.log("all good");
} finally {
  app.kill();
  await new Promise((r) => setTimeout(r, 300));
  fs.rmSync(settings, { force: true });
}
process.exit(0);
