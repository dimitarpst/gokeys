# gokeys: the brief

> **gokeys presses saved shortcuts on another PC.** Trigger them from your own PC's hotkeys, from its tray menu, or (later) from your phone or Claude.

Tiny, polished, and done once: a small app in the govrse family.

## Words

| Word | Meaning |
|---|---|
| **PC** | One gokeys install. It has a name (the Windows computer name) and its own secret. |
| **Pair** | PCs that share a private room on the relay. Every PC in a pair can press the others' keys. |
| **Key** | A name plus a shortcut, e.g. *Volume down = Shift+Ä*. A key belongs to the PC that presses it, because only that PC's layout and apps give it meaning. It is replayed by scan code. |
| **Hotkey** | A shortcut on *this* keyboard that presses a key on another PC. It passes through, so it still works locally. A bare key is allowed. |

## Decisions

| Decision | Why |
|---|---|
| No Sender/Receiver switch: every PC can press and be pressed | Fewer settings. A PC with no hotkeys just waits. |
| Pairing code (`K7Q-4MX`, single use, 10 min) shown on one PC, typed on the other | Works for anyone, with no accounts and no tokens baked into the exe |
| Shared relay at keys.govrse.app, one Durable Object room per pair; a self-hosted URL as an advanced option | Works out of the box. The free tier covers it. |
| Keys live on the PC that presses them, but either PC can add or edit them (the edit is sent to the owner) | Layout-correct, and another PC's keys can be set up from yours |
| Only listed keys can ever be pressed; never arbitrary keystrokes | Remote key injection must stay narrow |
| Hotkeys pass through (watched with a hook, not RegisterHotKey) | A hotkey like Ctrl+' keeps working on your own PC |
| One small native exe (Kotlin/Native, about 6 MB, about 20 MB RAM), the relay in TypeScript | Kotlin is the default, but a Cloudflare Worker has to be TypeScript |

## The app

- **Opening:** double-clicking the exe, or the tray icon, opens the window, even when gokeys is already running. The running copy is brought to the front.
- **Window:** one colour, go* style (pill buttons, 28dp cards, Roboto, mint #5ED6A0).
  - **This PC:** its keys, plus **Add key**.
  - **Each paired PC:** its keys, each with the hotkey this PC uses for it ("Set hotkey"), plus **Add key** (sent to that PC).
  - **Pair a PC:** **Show code** or **Enter code**. **Unpair** removes a PC.
  - Start with Windows and Pause stay in the tray menu, which keeps the window about keys only.
- **Tray menu:**
  - **Open gokeys** at the top.
  - Up to 5 keys from the other PCs. Clicking one presses it now, and its hotkey is shown on the right.
  - If there are more: **All 8 keys…**, which opens the window.
  - Then Pause, Start with Windows, Quit.
- **Tray icon:** mint when connected, grey when offline, dark when paused.
- **First run:**
  - The window opens on **Pair with another PC**.
  - Setting up a new PC: open the exe, enter the code, add its keys. Its keys can also be added from an already paired PC.
  - The app installs itself and starts with Windows.

## Relay (keys.govrse.app)

- `POST /register` gives a new PC a token in a room of its own. `POST /code` makes a pairing code for this PC's room. `POST /join` uses a code to move into that room. Failed codes are rate-limited.
- **Live socket:** `GET /live` (WebSocket, `Authorization: Bearer <token>`) is each PC's one connection. JSON messages:
  - `press {to, key}`
  - `keys {list}` (the owner publishes its list; the room keeps a copy so others see it while it's offline)
  - `edit {to, key}` (add, rename, record or delete; the owner applies it and republishes)
  - `room` (everyone gets the whole room on every change)
- **Unpairing a PC:** revokes its secret.
- **Hibernation:** WebSocket hibernation, with ping/pong that doesn't wake the object.
- **Later, same Worker:**
  - A phone page per pair.
  - An MCP endpoint (`list_pcs`, `list_keys`, `press_key`) so Claude can press keys.

## Not now

Phone app, MCP, macOS/Linux, key sequences or macros, per-app keys.
