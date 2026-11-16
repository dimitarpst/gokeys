// The updater against a fake GitHub: a debug gokeys.exe (preview mode, so no window and no relay) checks for an
// update, downloads it, and must accept it only when size and SHA-256 match. Run from the repo root:
//   node relay/test/update-check.mjs
import { execFile } from "node:child_process";
import { promisify } from "node:util";
import crypto from "node:crypto";
import fs from "node:fs";
import http from "node:http";
import os from "node:os";
import path from "node:path";

const exe = path.resolve("build/bin/mingwX64/debugExecutable/gokeys.exe");
const payload = crypto.randomBytes(200_000); // stands in for the new gokeys.exe
const real = crypto.createHash("sha256").update(payload).digest("hex");
const ok = (cond, what) => { if (!cond) throw new Error("FAIL " + what); console.log("ok  " + what); };

let release;
const server = http.createServer((req, res) => {
  if (req.url === "/latest") return res.end(JSON.stringify(release));
  if (req.url === "/gokeys.exe") return res.end(payload);
  res.statusCode = 404;
  res.end();
}).listen(0);
const base = `http://127.0.0.1:${server.address().port}`;
const asset = (digest) => ({ name: "gokeys.exe", state: "uploaded", size: payload.length, digest: `sha256:${digest}`, browser_download_url: `${base}/gokeys.exe` });

async function check() { // async: the fake server must keep answering while gokeys runs
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "gokeys-update-"));
  await promisify(execFile)(exe, [], { env: { ...process.env, GOKEYS_PROFILE: "update-test", GOKEYS_PREVIEW: dir, GOKEYS_UPDATES: `${base}/latest` } });
  return fs.readFileSync(path.join(dir, "update.txt"), "utf8");
}

try {
  release = { tag_name: "v99.0.0", draft: false, prerelease: false, assets: [asset(real)] };
  let out = await check();
  ok(out.startsWith("found 99.0.0 at "), `a newer release is found and downloaded (${out})`);
  const saved = out.replace("found 99.0.0 at ", "");
  ok(fs.readFileSync(saved).equals(payload), "the saved file is exactly what was published");

  release = { ...release, assets: [asset("0".repeat(64))] };
  out = await check();
  ok(out.startsWith("failed:"), `a hash mismatch is refused (${out})`);

  release = { ...release, tag_name: "v0.0.1", assets: [asset(real)] };
  ok((await check()) === "no update", "an older release is ignored");

  release = { tag_name: "v99.0.0", draft: false, prerelease: true, assets: [asset(real)] };
  ok((await check()) === "no update", "a pre-release is ignored");
  console.log("all good");
} finally {
  server.close();
  fs.rmSync(path.join(process.env.APPDATA, "gokeys", "settings-update-test.properties"), { force: true });
  fs.rmSync(path.join(process.env.LOCALAPPDATA, "gokeys", "update", "gokeys.exe"), { force: true });
}
