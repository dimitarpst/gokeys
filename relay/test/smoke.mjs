// Two fake PCs against `npm run dev`: register, pair with a code, see each other, press, edit, unpair.
// Run:  node test/smoke.mjs   (relay on http://localhost:8787)
const BASE = process.env.RELAY ?? "http://localhost:8787";
const post = async (path, body, token) => {
  const res = await fetch(BASE + path, {
    method: "POST",
    headers: { "content-type": "application/json", ...(token ? { authorization: `Bearer ${token}` } : {}) },
    body: JSON.stringify(body ?? {}),
  });
  return { status: res.status, body: res.headers.get("content-type")?.includes("json") ? await res.json() : await res.text() };
};

function connect(token, name, keys) {
  const ws = new WebSocket(BASE.replace("http", "ws") + "/live", { headers: { authorization: `Bearer ${token}` } });
  const inbox = [];
  ws.onmessage = (e) => e.data !== "pong" && inbox.push(JSON.parse(e.data));
  ws.onopen = () => ws.send(JSON.stringify({ t: "hello", name, keys }));
  const next = async (t, ms = 3000) => {
    const start = Date.now();
    while (Date.now() - start < ms) {
      const i = inbox.findIndex((m) => m.t === t);
      if (i >= 0) return inbox.splice(i, 1)[0];
      await new Promise((r) => setTimeout(r, 30));
    }
    throw new Error(`${name}: no ${t}`);
  };
  const drain = () => inbox.splice(0);
  return { ws, next, drain, send: (m) => ws.send(JSON.stringify(m)) };
}

const ok = (cond, what) => { if (!cond) throw new Error("FAIL " + what); console.log("ok  " + what); };

const a = (await post("/register", { name: "PC-A" })).body.token;
const b = (await post("/register", { name: "PC-B" })).body.token;
ok(a && b, "register gives tokens");
ok((await post("/code", {}, "x.y.z")).status === 401, "bad token is refused");

const code = (await post("/code", {}, b)).body.code;
ok(/^[A-Z0-9]{3}-[A-Z0-9]{3}$/.test(code), `code looks like ${code}`);
const joined = await post("/join", { code: code.toLowerCase(), name: "PC-A" }, a);
ok(joined.status === 200, "join with the code");
ok((await post("/join", { code, name: "x" })).status === 404, "a code works once");
ok((await post("/code", {}, a)).status === 401, "old token stops working after joining");
const a2 = joined.body.token;

const other = connect(b, "PC-B", [{ id: "k1", name: "Volume down", label: "Shift+Ä" }]);
await other.next("room");
const me = connect(a2, "PC-A", []);
const room = await me.next("room");
const pcB = room.pcs.find((p) => p.name === "PC-B");
ok(room.pcs.length === 2 && pcB.online && pcB.keys[0].label === "Shift+Ä", "room shows both PCs and B's keys");

me.send({ t: "press", to: pcB.id, key: "k1" });
const press = await other.next("press");
ok(press.key === "k1" && press.from === room.you, "press reaches PC B");

other.drain();
me.send({ t: "press", to: pcB.id, key: "nope" });
await new Promise((r) => setTimeout(r, 300));
ok(!other.drain().some((m) => m.t === "press"), "unlisted keys are never pressed");

me.send({ t: "edit", to: pcB.id, key: "k2", name: "Mute", shortcut: "1d:a2:0,32:4d:0" });
const edit = await other.next("edit");
ok(edit.name === "Mute" && edit.shortcut === "1d:a2:0,32:4d:0", "edit reaches the owner");

other.ws.close();
await new Promise((r) => setTimeout(r, 300));
me.drain();
me.send({ t: "press", to: pcB.id, key: "k1" });
ok((await me.next("note")).text === "PC-B is offline", "offline PC gives a note");

me.send({ t: "remove", pc: pcB.id });
const after = await me.next("room");
ok(after.pcs.length === 1, "remove unpairs");
ok((await post("/code", {}, b)).status === 401, "removed PC's token is revoked");
me.ws.close();
console.log("all good");
process.exit(0);
