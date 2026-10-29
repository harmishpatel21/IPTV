# IPTV

A Netflix-style player for IPTV subscriptions delivered through a **Stalker / Ministra portal**
(the kind used with MAG boxes and STBEmu): live TV with a guide, the provider's Video Club (movies and series),
search, My List, and Continue Watching with resume.

It's only a player: bring your own subscription (portal URL + MAC address).

| | |
|---|---|
| **Android TV app** (`android/`) | Native Kotlin app (Compose for TV + ExoPlayer) for Android TV / Google TV. Install the APK, then enter your portal details on the TV. **[Install guide → DEPLOY.md](DEPLOY.md)** |
| **Web player** (`server.js`, `public/`) | Small Node server + browser UI. Handy for checking your portal details and for watching on a computer. |

## Features

- **Live TV** — ~all channels by category, now/next programme info, channel up/down
- **Movies & Shows** — the portal's Video Club with posters, categories by language, sorting, seasons and episodes
- **Search** across channels, movies and shows (TV app: on-screen keyboard or voice)
- **Continue Watching** for channels, movies and episodes, with resume and auto-play of the next episode
- **My List** for channels and titles

## Web player

1. Copy `config.example.json` to `config.json` and fill in `portal` and `mac`
   (plus `stbModel` / `serialNumber` if your portal needs them — see [DEPLOY.md](DEPLOY.md#1-collect-your-portal-details)).
2. `npm start` (Node.js 18+, no dependencies)
3. Open <http://127.0.0.1:3000>

| Key | Action |
| --- | --- |
| Arrows / Enter | Move around / open |
| `/` | Search |
| `F` | Add focused item to My List |
| Up / Down (live player) | Previous / next channel |
| ← / → (movie player) | Skip 10 s |
| Space (movie player) | Pause |
| `N` (movie player) | Next episode |
| Esc / Backspace | Back |

## Android TV app

**[Download the latest APK](https://github.com/harmishpatel21/IPTV/releases/latest/download/IPTV-tv.apk)**
(or [build it](DEPLOY.md#c-build-it-yourself)), install it on the TV, and enter your portal URL and MAC on the
setup screen. **[DEPLOY.md](DEPLOY.md)** has step-by-step instructions, including installing without a computer.

## Notes

- `config.json` holds your account details and is git-ignored. The web player uses it; the TV app only bakes
  it into **debug** builds (never share those). Release APKs contain no account; you enter it on the TV.
- Many providers allow one active device per account — the web player, the TV app and STBEmu may log each
  other out.
- Use it only with a subscription you're entitled to.
