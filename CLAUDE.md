# IPTV — project context

## Goal
The user has an IPTV subscription delivered as a **Stalker/Ministra portal link** (MAG set-top-box style),
currently watched on a **Sony Bravia (Google TV / Android TV)** via the **STBEmu** app, whose UI is unpleasant.
We are building our own client: nicer channel browsing, fast search, favourites, EPG.

## Plan
1. **Web prototype (done, untested against the real portal)** — Node server + browser UI, to prove the
   portal API works with the user's provider.
2. **Android TV app (next)** — native Kotlin + Media3/ExoPlayer, sideloaded APK on the Bravia, D-pad-friendly UI.
   Talks to the portal directly (no proxy needed on Android). Reuse the portal logic from `server.js`.
   Tooling (installed 2026-10-29, no Android Studio — "light" setup): Microsoft OpenJDK 17
   (`C:\Program Files\Microsoft\jdk-17.0.21.21-hotspot`), Android SDK at `%LOCALAPPDATA%\Android\Sdk` (cmdline-tools
   latest = new `android` CLI; `sdkmanager` deprecated), platform-tools (adb), build-tools 37.0.0, platforms android-36.
   User env vars JAVA_HOME / ANDROID_HOME / PATH set. The `android create` template ships AGP 9.0.1, which can't use
   API 37 (and current Coil/Compose need compileSdk 37) — upgraded to AGP 9.5.0 + Gradle 9.8.2 (pinned sha256).
   Node 22 and Python 3.12 also installed.

## How the portal works (Stalker / Ministra)
- Portal URL looks like `http://host:port/c/` (or `/stalker_portal/c/`). Auth is by **MAC address**
  (usually `00:1A:79:...`), not username/password.
- API endpoint is one of `/portal.php`, `/stalker_portal/server/load.php`, `/server/load.php` (we auto-detect).
- Every request: MAG `User-Agent`, `X-User-Agent: Model: MAG250; Link: WiFi`, cookie `mac=...; stb_lang=en; timezone=...`,
  `Authorization: Bearer <token>`, and query param `JsHttpRequest=1-xml`. Responses are JSON `{ "js": ... }`.
- Flow: `type=stb&action=handshake` → token → `type=stb&action=get_profile` → then:
  - `type=itv&action=get_genres` — categories (`id='*'` is "All", skipped)
  - `type=itv&action=get_all_channels` — channels (`js.data`: id, name, number, tv_genre_id, logo, cmd)
  - `type=itv&action=create_link&cmd=<cmd>` — real stream URL (strip `ffmpeg `/`ffrt ` prefix)
  - `type=itv&action=get_short_epg&ch_id=<id>` — now/next EPG
  - Video Club (VOD): everything is under `type=vod` (`type=series` unused on this portal):
    - `get_categories` (~124, titles like `HINDI MOVIES | LATEST`); `get_ordered_list&category=&sortby=added|name|rating&search=&p=`
      — 14 items/page; items have `is_series`, `screenshot_uri` (relative poster), year, description, actors…
    - series: `get_ordered_list&movie_id=<id>&season_id=0&episode_id=0` → seasons (`is_season`, paged!);
      `…&season_id=<sid>&episode_id=0` → episodes (`is_episode`, `series_number`, `series_name`)
    - playable link (movies AND episodes): `get_ordered_list&movie_id=&season_id=&episode_id=` → files (`is_file`, `cmd`),
      then `type=vod&action=create_link&cmd=<file cmd>` → HLS URL. The movie's own `cmd` gives `nothing_to_play`.
- Tokens expire silently → re-handshake and retry once on failure.
- Many providers allow **one active device per MAC** — STBEmu and our app may kick each other off.

## Current code
- `server.js` — zero-dependency Node server. `Portal` class (login, caching 10 min, genres, channels,
  streamUrl, epg cached 60 s, vod*). Routes: `/api/genres`, `/api/channels`, `/api/link?cmd=`, `/api/epg?id=`,
  `/api/vod/{categories,list,seasons,episodes,link}`,
  `/proxy?url=` (streams; rewrites HLS playlists so segments/keys also go through the proxy; forwards Range), static `public/`.
  Binds to `127.0.0.1` by default (`host`/`port` configurable).
- `public/index.html`, `public/app.js`, `public/style.css` — Netflix-style UI: top nav (Home / Live TV / Movies & Shows /
  My List / search), hero (resume last channel or episode), rows: Continue Watching (mixed live + VOD, localStorage `recent`;
  VOD positions in `vodProgress`), My Channels (`favs`), My Movies & Shows (`vodList`), one lazy row per live category;
  Live TV grid with category chips; typographic channel tiles (portal has **no channel logos**, colour hashed from name,
  4K/HD badge parsed from name). Movies & Shows: language chips (first word of category title) → lazy poster rows per category
  → category grid with sort + infinite paging; detail modal (movie Play/Resume, or seasons → episodes). Search covers
  channels + VOD. Player: live (LIVE OSD, Up/Down zap) or VOD (seek ←/→, Space, N next episode, auto-next, resume).
  Arrow-key spatial focus nav everywhere.
- `config.example.json` → copy to `config.json` (gitignored) with `portal`, `mac`, optional `timezone`, `port`,
  `stbModel`, `serialNumber`, `deviceId`, `deviceId2`, `signature` — copy these from STBEmu's STB Configuration if the portal says "device_id mismatch" (the user's portal needs `stbModel: MAG424` + serial; device IDs empty). API path is `/stalker_portal/server/load.php`; portal rate-limits rapid logins (HTTP 429).
- Run: `npm start`, open http://127.0.0.1:3000

## Android TV app (`android/`)
- Kotlin + Compose for TV (tv-material 1.1) + Media3 ExoPlayer (HLS) + OkHttp; AGP 9.5 / Gradle 9.8.2; compileSdk 37,
  targetSdk 36, minSdk 24. Package `com.iptv.tv`. Portal credentials are read at build time from **../config.json**
  into BuildConfig (no secrets in git).
- `data/Portal.kt` (port of server.js; stream-parses the 12 MB channel list with JsonReader), `data/Store.kt`
  (SharedPreferences favs/recent + compact channel cache ~0.8 MB in filesDir, refreshed when >6 h old),
  `ui/AppViewModel.kt`, `ui/Screens.kt` (TopNav, Home w/ focus-driven hero + rows, Live TV genres+grid, My List),
  `ui/Cards.kt`, `ui/Player.kt` (Up/Down zap, OK = info, Back), `ui/App.kt`. Hold OK = add to My List
  (or remove from Continue Watching). Focus restore after the player uses one FocusRequester attached to the card
  whose key == `vm.focusKey`.
- Build: `cd android && ./gradlew.bat assembleDebug` (JAVA_HOME = JDK 17). Install: `adb connect <tv-ip>:5555`
  then `adb install -r app/build/outputs/apk/debug/app-debug.apk`. TV: Sony BRAVIA BF1, Android 12 (API 31),
  armeabi-v7a, ~2.4 GB RAM, UI 1920x1080 @ density 320 (= 960x540 dp). Screenshot: `adb exec-out screencap -p`;
  wake from Ambient mode with `adb shell input keyevent KEYCODE_WAKEUP`. Drive with `input keyevent KEYCODE_DPAD_*`.
- Feature parity with the web player (verified on the TV 2026-10-29): Search tab (channels + VOD, Google TV keyboard
  incl. voice; the box is a button until OK so the keyboard doesn't trap D-pad users), Movies & Shows (language
  chips → poster rows → category grid w/ sort + paging), details overlay (`ui/VodScreens.kt`: Play/Resume, seasons,
  episodes w/ progress), `ui/VodPlayer.kt` (◀▶ 10 s / hold 30 s, OK pause, CH+/⏭ next episode, auto-next, saves
  position every 5 s), mixed Continue Watching, My List for channels + titles (Coil posters).
- Portal login on TV: remembers the working endpoint in prefs, backs off on HTTP 429 instead of re-logging in
  (repeated restarts during testing triggered the portal's rate limit).

## Status
- **Working against the user's real portal (2026-10-29):** login, categories, ~10.9k channels (~12 MB `get_all_channels`), playback;
  Video Club movies + series/seasons/episodes play (HLS), resume works.
- Portal quirks: API at `/stalker_portal/server/load.php`; needs `stbModel: MAG424` + STBEmu serial (device IDs empty);
  rate-limits rapid repeated logins (HTTP 429); channel `cmd`s are `ffrt http://localhost/ch/<id>` placeholders resolved via `create_link`.
- Known gaps: no channel logos from the portal; history/lists are per-browser (localStorage).
- Headless-browser testing: unmuted `play()` is blocked without a user gesture — mute to verify playback.

## Next steps
1. Android TV app stage 2: Movies & Shows (port VOD from server.js + public/app.js).
2. Later: full EPG grid, catch-up if the portal supports it.

## Notes
- Legitimacy: the user is using their own subscription; we're only building a client for it.
- The project was originally created in `D:\projects\TV` and moved here.
