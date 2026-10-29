# IPTV

Prototype IPTV client for Stalker / Ministra (MAG / STBEmu-style) portals.

- `server.js` logs into the portal as a MAG box, exposes a small JSON API, and proxies streams.
- `public/` is the browser UI: categories, search, favourites, player with now/next EPG.

## Run

1. Copy `config.example.json` to `config.json` and set `portal` (the URL you use in STBEmu) and `mac`.
2. `npm start`
3. Open http://127.0.0.1:3000

## Keys

| Key | Action |
| --- | --- |
| `/` | Focus search |
| Up / Down (in player) | Previous / next channel |
| `i` | Show channel info |
| Esc / Backspace | Close player |
