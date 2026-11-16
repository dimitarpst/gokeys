<p align="center"><img src="branding/gokeys_logo_sm.png" width="120" alt="gokeys"></p>

# gokeys

Press saved shortcuts on another PC. Each PC keeps a list of keys like *Volume down = Shift+Ä*. A paired PC presses
them with its own hotkeys, or from its tray menu. It works for any
shortcut: volume, media, push-to-talk, anything an app listens for.

- **App:** one native Windows exe, built with Kotlin/Native on Win32 directly (no Java). About 6 MB and 20 MB of RAM.
  It lives in the tray.
- **Relay:** a Cloudflare Worker (`relay/`) that every PC connects out to, so nobody opens ports. Each pair of PCs
  gets a private room.

## How it works

- **Keys:** a key belongs to the PC that presses it. That PC replays it with `SendInput` **by scan code**, so its own
  layout gives the keys their meaning: on a German keyboard, `Shift+Ä` comes out exactly as recorded. Either PC can
  add or edit the keys, and the owner applies the change.
- **Hotkeys:** a hotkey is a shortcut on *this* keyboard for another PC's key. A keyboard hook watches for it and
  lets every key through, so the hotkey still works locally too. Any key is allowed.
- **Pairing:** one PC shows a code (`K7Q-4MX`, single use, valid 10 minutes) and the other types it in. There are no
  accounts.
- **Safety:** only keys on the owner's list can ever be pressed, never arbitrary keystrokes. **Unpair** revokes a
  PC's secret.
- **Opening the window:** run `gokeys.exe` again, or click the tray icon. Right-click the icon for the menu: up to
  five keys to press right away, then Pause, Start with Windows and Quit.

## Setting up a new PC

1. Send someone `gokeys.exe`.
2. They run it once. SmartScreen will warn because the exe isn't code-signed: **More info → Run anyway**.
   - It copies itself to `%LOCALAPPDATA%\gokeys` and turns on Start with Windows.
   - It opens on **Pair with another PC**.
3. Pair: one PC clicks **Show code**, the other clicks **Enter code**.
4. Add keys on either PC.

A newer `gokeys.exe` installs the same way and replaces the running copy. Settings are stored in
`%APPDATA%\gokeys\settings.properties`.

## Build

You need JDK 21 as `JAVA_HOME`; Gradle's Corretto 21 in `%USERPROFILE%\.gradle\jdks` works. The first build downloads
the Kotlin/Native toolchain.

```
gradlew linkReleaseExecutableMingwX64   # build/bin/mingwX64/releaseExecutable/gokeys.exe, talks to keys.govrse.app
gradlew linkDebugExecutableMingwX64     # talks to localhost:8787, runs where it is (no install, no autostart)
```

These environment variables help with debugging:

- `GOKEYS_PROFILE=b`: a second instance on the same PC, with its own settings.
- `GOKEYS_RELAY=<url>`: point a build at another relay.
- `GOKEYS_PREVIEW=<folder>`: debug builds only. Draws the windows to `.bmp` files without opening anything.

Branding (the logo, icons and cut-down Roboto) comes from `branding/make-logo.mjs`. Regenerate it with:

```
cd branding && npm install && npm run logo
```

## Relay

```
cd relay && npm install
npm run dev       # local relay on :8787
npm test          # two fake PCs against it: pairing, press, edit, unpair
npm run deploy    # keys.govrse.app
```

From the repo root, `node relay/test/app-e2e.mjs` runs a real debug `gokeys.exe` against the local relay (tray only,
no window): it registers, pairs, publishes, applies edits and starts over when unpaired.
