import { DurableObject } from "cloudflare:workers";

/**
 * gokeys relay. Every gokeys PC belongs to one room (a fresh install gets a room of its own); pairing moves a PC into
 * another PC's room. Each PC keeps one WebSocket to its room and talks in small JSON messages.
 *
 *   POST /register {name}         -> {token}        a new PC in a new room
 *   POST /code   (auth)           -> {code}         a single-use code (10 min) for another PC to join this room
 *   POST /join {code, name} (auth)-> {token}        move into the code's room (the old one is left)
 *   GET  /live   (auth, WebSocket)                  the PC's connection to its room
 *   GET  /me     (auth)                             200 while this PC still belongs to a room
 *
 * Auth is `Authorization: Bearer <room>.<pc>.<secret>`; the room keeps only a hash of the secret.
 */

interface Env {
	ROOM: DurableObjectNamespace<Room>;
	CODES: DurableObjectNamespace<Codes>;
}

/** A key as its owner publishes it: what other PCs see and can press. */
interface KeyInfo {
	id: string;
	name: string;
	label: string; // the shortcut as the owner's layout names it, e.g. "Shift+Ä"; "" = not recorded yet
}

interface Pc {
	name: string;
	hash: string;
	keys: KeyInfo[];
}

type Incoming =
	| { t: "hello"; name: string; keys: KeyInfo[] }
	| { t: "keys"; keys: KeyInfo[] }
	| { t: "press"; to: string; key: string }
	| { t: "edit"; to: string; key: string; name?: string; shortcut?: string; remove?: boolean }
	| { t: "remove"; pc: string };

const STALE_MS = 75_000; // PCs ping every 30 s
const MAX_PCS = 16;
const MAX_KEYS = 64;

const encoder = new TextEncoder();
const random = (bytes: number) => btoa(String.fromCharCode(...crypto.getRandomValues(new Uint8Array(bytes))))
	.replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");

async function sha256(text: string): Promise<string> {
	const digest = await crypto.subtle.digest("SHA-256", encoder.encode(text));
	return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

function sameText(a: string, b: string): boolean {
	const x = encoder.encode(a), y = encoder.encode(b);
	return x.byteLength === y.byteLength && crypto.subtle.timingSafeEqual(x, y);
}

const clip = (s: unknown, max: number) => (typeof s === "string" ? s.slice(0, max) : "");
const cleanKeys = (keys: unknown): KeyInfo[] => (Array.isArray(keys) ? keys : []).slice(0, MAX_KEYS)
	.map((k) => ({ id: clip(k?.id, 32), name: clip(k?.name, 60), label: clip(k?.label, 60) }))
	.filter((k) => k.id);

/**
 * One pair of PCs (or more). With WebSocket hibernation the object sleeps between messages, and "ping" is answered
 * with "pong" without waking it, so always-connected PCs cost next to nothing.
 */
export class Room extends DurableObject<Env> {
	constructor(ctx: DurableObjectState, env: Env) {
		super(ctx, env);
		ctx.setWebSocketAutoResponse(new WebSocketRequestResponsePair("ping", "pong"));
	}

	private async pcs(): Promise<Record<string, Pc>> {
		return (await this.ctx.storage.get<Record<string, Pc>>("pcs")) ?? {};
	}

	private async save(pcs: Record<string, Pc>) {
		await this.ctx.storage.put("pcs", pcs);
	}

	/** A new PC in this room; returns its id and secret (the secret is never stored). */
	async addPc(name: string): Promise<{ id: string; secret: string } | null> {
		const pcs = await this.pcs();
		if (Object.keys(pcs).length >= MAX_PCS) return null;
		const id = random(6), secret = random(24);
		pcs[id] = { name: clip(name, 60) || "PC", hash: await sha256(secret), keys: [] };
		await this.save(pcs);
		await this.broadcast();
		return { id, secret };
	}

	async check(id: string, secret: string): Promise<boolean> {
		const pc = (await this.pcs())[id];
		return !!pc && sameText(pc.hash, await sha256(secret));
	}

	async removePc(id: string) {
		const pcs = await this.pcs();
		delete pcs[id];
		await this.save(pcs);
		for (const ws of this.ctx.getWebSockets(id)) ws.close(4001, "removed");
		await this.broadcast();
	}

	async fetch(req: Request): Promise<Response> {
		const id = req.headers.get("x-pc") ?? "";
		const [client, server] = Object.values(new WebSocketPair());
		this.ctx.acceptWebSocket(server, [id]);
		server.serializeAttachment({ id, since: Date.now() });
		return new Response(null, { status: 101, webSocket: client });
	}

	async webSocketMessage(ws: WebSocket, raw: string | ArrayBuffer): Promise<void> {
		const from: string = ws.deserializeAttachment()?.id;
		let msg: Incoming;
		try {
			msg = JSON.parse(typeof raw === "string" ? raw : new TextDecoder().decode(raw));
		} catch {
			return;
		}
		const pcs = await this.pcs();
		const me = pcs[from];
		if (!me) return ws.close(4001, "removed");
		const name = (id: string) => pcs[id]?.name ?? "That PC";
		const toOwner = (to: string, out: object) => {
			const sockets = this.live(to);
			for (const s of sockets) s.send(JSON.stringify(out));
			if (!sockets.length) ws.send(JSON.stringify({ t: "note", text: `${name(to)} is offline` }));
		};
		switch (msg.t) {
			case "hello":
				me.name = clip(msg.name, 60) || me.name;
				me.keys = cleanKeys(msg.keys);
				await this.save(pcs);
				return this.broadcast();
			case "keys":
				me.keys = cleanKeys(msg.keys);
				await this.save(pcs);
				return this.broadcast();
			case "press":
				if (!pcs[msg.to]?.keys.some((k) => k.id === msg.key)) return; // only listed keys, ever
				return toOwner(msg.to, { t: "press", from, key: msg.key });
			case "edit":
				if (!pcs[msg.to]) return;
				return toOwner(msg.to, {
					t: "edit", from, key: clip(msg.key, 32), name: msg.name === undefined ? undefined : clip(msg.name, 60),
					shortcut: msg.shortcut === undefined ? undefined : clip(msg.shortcut, 200), remove: !!msg.remove,
				});
			case "remove":
				if (pcs[msg.pc] && msg.pc !== from) await this.removePc(msg.pc);
				return;
		}
	}

	async webSocketClose(ws: WebSocket, code: number, reason: string): Promise<void> {
		try {
			ws.close(code, reason);
		} catch {
			// already closed
		}
		await this.broadcast();
	}

	/** Sockets that pinged recently: a PC that went to sleep without closing its socket doesn't count. */
	private live(tag?: string): WebSocket[] {
		return this.ctx.getWebSockets(tag).filter((ws) => {
			if (ws.readyState !== WebSocket.OPEN) return false;
			const seen = Math.max(this.ctx.getWebSocketAutoResponseTimestamp(ws)?.getTime() ?? 0, ws.deserializeAttachment()?.since ?? 0);
			return Date.now() - seen < STALE_MS;
		});
	}

	/** Everyone gets the room as it is now: each PC, whether it's online, and its keys. */
	private async broadcast() {
		const pcs = await this.pcs();
		const online = new Set(this.live().map((ws) => ws.deserializeAttachment()?.id));
		const list = Object.entries(pcs).map(([id, pc]) => ({ id, name: pc.name, online: online.has(id), keys: pc.keys }));
		for (const ws of this.live()) {
			ws.send(JSON.stringify({ t: "room", you: ws.deserializeAttachment()?.id, pcs: list }));
		}
	}
}

/** Pairing codes: one object for all of them, so a code can be looked up without knowing its room. */
export class Codes extends DurableObject<Env> {
	private failures = new Map<string, { count: number; since: number }>(); // per IP, in memory: enough to stop guessing

	async create(room: string): Promise<string> {
		const alphabet = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"; // no 0/O, 1/I/L
		const pick = crypto.getRandomValues(new Uint8Array(6));
		const code = [...pick].map((n) => alphabet[n % alphabet.length]).join("");
		await this.ctx.storage.put(`code:${code}`, { room, until: Date.now() + 10 * 60_000 });
		return `${code.slice(0, 3)}-${code.slice(3)}`;
	}

	/** The room for [code], used up; null if it's wrong, expired, or this IP has guessed too often. */
	async redeem(code: string, ip: string): Promise<string | null> {
		const now = Date.now();
		const f = this.failures.get(ip);
		if (f && now - f.since < 10 * 60_000 && f.count >= 20) return null;
		const clean = code.toUpperCase().replace(/[^A-Z0-9]/g, "");
		const entry = await this.ctx.storage.get<{ room: string; until: number }>(`code:${clean}`);
		if (!entry || entry.until < now) {
			this.failures.set(ip, f && now - f.since < 10 * 60_000 ? { count: f.count + 1, since: f.since } : { count: 1, since: now });
			return null;
		}
		await this.ctx.storage.delete(`code:${clean}`);
		return entry.room;
	}
}

const json = (body: object, status = 200) => Response.json(body, { status });
const text = (body: string, status = 200) => new Response(body, { status, headers: { "content-type": "text/plain; charset=utf-8" } });

/** The room and PC behind a valid token, or null. */
async function auth(req: Request, env: Env) {
	const token = (req.headers.get("authorization") ?? "").replace(/^Bearer /, "");
	const [room, id, secret] = token.split(".");
	if (!room || !id || !secret) return null;
	const stub = env.ROOM.get(env.ROOM.idFromName(room));
	return (await stub.check(id, secret)) ? { room, id, stub } : null;
}

async function body(req: Request): Promise<Record<string, unknown>> {
	try {
		return (await req.json()) as Record<string, unknown>;
	} catch {
		return {};
	}
}

export default {
	async fetch(req: Request, env: Env): Promise<Response> {
		const path = new URL(req.url).pathname;
		const codes = env.CODES.get(env.CODES.idFromName("codes"));

		if (path === "/") return text("gokeys relay");

		if (path === "/register" && req.method === "POST") {
			const room = random(16);
			const pc = await env.ROOM.get(env.ROOM.idFromName(room)).addPc(clip((await body(req)).name, 60));
			return pc ? json({ token: `${room}.${pc.id}.${pc.secret}` }) : text("room is full", 409);
		}

		if (path === "/join" && req.method === "POST") {
			const b = await body(req);
			const room = await codes.redeem(clip(b.code, 16), req.headers.get("cf-connecting-ip") ?? "");
			if (!room) return text("That code didn't work. Codes last 10 minutes and work once.", 404);
			const old = await auth(req, env);
			if (old?.room === room) return text("This PC is already paired with that one.", 409);
			const pc = await env.ROOM.get(env.ROOM.idFromName(room)).addPc(clip(b.name, 60));
			if (!pc) return text("That pair is full.", 409);
			if (old) await old.stub.removePc(old.id);
			return json({ token: `${room}.${pc.id}.${pc.secret}` });
		}

		const me = await auth(req, env);
		if (!me) return text("nope", 401);

		if (path === "/me") return text("ok"); // lets a PC tell 'removed' apart from 'offline'

		if (path === "/code" && req.method === "POST") return json({ code: await codes.create(me.room) });

		if (path === "/live") {
			if (req.headers.get("upgrade")?.toLowerCase() !== "websocket") return text("expected a websocket", 426);
			const forward = new Request(req, { headers: new Headers(req.headers) });
			forward.headers.set("x-pc", me.id);
			return me.stub.fetch(forward);
		}

		return text("not found", 404);
	},
} satisfies ExportedHandler<Env>;
