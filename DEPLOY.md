# Installing the Android TV app on your TV

This guide takes you from a fresh clone of this repo to the app running on an Android TV / Google TV
(Sony Bravia, TCL, Hisense, Chromecast with Google TV, Nvidia Shield, …). It takes about 30–45 minutes the
first time, mostly downloads.

> **You need your own IPTV subscription** that uses a **Stalker / Ministra portal** (the kind you'd normally
> use with STBEmu or a MAG box). This project is only a player — it has no content of its own.

**Why build it yourself?** Your portal login is built into the APK when you compile it, so there's no
ready-made download: everyone builds their own copy with their own details. Don't share your APK — it contains
your account.

---

## What you need

| | |
|---|---|
| A TV | Android TV / Google TV, Android 7.0 or newer |
| A computer | Windows, macOS or Linux, on the **same Wi-Fi/network** as the TV |
| Portal details | Portal URL and MAC address (see step 1) |
| Disk space | ~3 GB for the build tools |

---

## 1. Collect your portal details

Your provider gave you a **portal URL** and registered a **MAC address** for you. If you already use STBEmu,
everything is in its settings: **Settings → Profiles → *your profile***:

| Setting in STBEmu | Goes into `config.json` as | Required? |
|---|---|---|
| Portal URL (e.g. `http://example.com:8080/c/`) | `portal` | yes |
| MAC address (e.g. `00:1A:79:12:34:56`) | `mac` | yes |
| STB Configuration → **STB model** (e.g. `MAG250`, `MAG424`) | `stbModel` | if not MAG250 |
| STB Configuration → **Serial number** | `serialNumber` | often |
| STB Configuration → **Device ID / Device ID 2 / Signature** | `deviceId`, `deviceId2`, `signature` | only if set (not greyed out) |

Many portals tie your account to the exact box identity STBEmu uses. If you later see
**"device_id mismatch"**, copy the model and serial number from STBEmu — that's usually all it takes.

## 2. Install the build tools

You need **JDK 17** and the **Android SDK**. Pick one of the two routes.

### Route A — Android Studio (easiest, ~4 GB)

1. Install [Android Studio](https://developer.android.com/studio). It includes a JDK and the SDK.
2. Open it once and finish the setup wizard (it downloads the SDK).
3. Skip to step 3. (You can build from Android Studio by opening the `android/` folder, or follow the
   command-line steps below — both work.)

### Route B — command-line tools only (~1 GB)

**JDK 17**

| OS | Command |
|---|---|
| Windows | `winget install Microsoft.OpenJDK.17` |
| macOS | `brew install --cask temurin@17` |
| Ubuntu/Debian | `sudo apt install openjdk-17-jdk` |

**Android SDK command-line tools**

1. Download *Command line tools only* for your OS from
   [developer.android.com/studio](https://developer.android.com/studio#command-line-tools-only).
2. Unzip it so the path is `<SDK>/cmdline-tools/latest/bin/` — for example:
   - Windows: `%LOCALAPPDATA%\Android\Sdk\cmdline-tools\latest\`
   - macOS: `~/Library/Android/sdk/cmdline-tools/latest/`
   - Linux: `~/Android/Sdk/cmdline-tools/latest/`
3. Set environment variables (then open a new terminal):
   - `ANDROID_HOME` = your `<SDK>` folder
   - `JAVA_HOME` = your JDK 17 folder (Windows example: `C:\Program Files\Microsoft\jdk-17.x.x-hotspot`)
   - add `<SDK>/platform-tools` to `PATH` (this is where `adb` lives)
4. Accept the SDK licences and install `adb`:

   ```sh
   sdkmanager --licenses          # answer y to each
   sdkmanager "platform-tools"
   ```

   (On Windows the tool is `sdkmanager.bat`. Newer SDK versions print a deprecation warning — it still works.)
   The build downloads the remaining SDK pieces it needs by itself.

## 3. Get the code and add your details

```sh
git clone <this repo's URL>
cd <repo folder>
cp config.example.json config.json      # Windows: copy config.example.json config.json
```

Edit **`config.json`** (in the repo root, *not* inside `android/`):

```json
{
  "portal": "http://your-provider.example:8080/c/",
  "mac": "00:1A:79:12:34:56",
  "timezone": "America/Toronto",
  "stbModel": "MAG250",
  "serialNumber": "",
  "deviceId": "",
  "deviceId2": "",
  "signature": ""
}
```

- `timezone` — your [tz name](https://en.wikipedia.org/wiki/List_of_tz_database_time_zones), used for the TV guide.
- Leave fields empty if STBEmu doesn't show them. `port` is only used by the web player and can be left as is.
- `config.json` is git-ignored, so it never gets committed.

**Optional: check your details on the computer first.** If you have [Node.js](https://nodejs.org) 18+, run
`npm start` in the repo folder and open <http://127.0.0.1:3000>. If channels load there, the TV app will log in
too. Stop it (Ctrl+C) before using the TV — many providers allow only one device at a time.

## 4. Build the app

```sh
cd android
./gradlew assembleDebug        # Windows: gradlew.bat assembleDebug
```

The first build downloads Gradle and libraries and takes 5–10 minutes; later builds take under a minute.
The result is:

```
android/app/build/outputs/apk/debug/app-debug.apk
```

## 5. Prepare the TV (one time)

1. **Turn on developer options.** Settings → **System → About** (on some TVs **Device Preferences → About**).
   Scroll to **Build** (or *Android TV OS build*) and press OK on it **7 times** until it says
   *"You are now a developer"*.
2. **Turn on debugging.** Go back to Settings → **System → Developer options** and enable
   **USB debugging**. If you also see **Network debugging** / **ADB debugging**, enable that too.
3. **Find the TV's IP address.** Settings → **Network & Internet** → your Wi-Fi network (e.g. `192.168.1.45`).

## 6. Install on the TV

From the computer (same network as the TV):

```sh
adb connect 192.168.1.45:5555                                   # your TV's IP
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
```

- The first time, the TV shows **"Allow USB debugging?"** — tick **Always allow from this computer** and press
  **OK**, then run `adb connect` again. `adb devices` should list the TV as `device` (not `unauthorized`).
- **Android 11+ "Wireless debugging" instead of port 5555?** Some TVs only offer *Wireless debugging* with a
  pairing code. Open it on the TV, choose **Pair device with pairing code**, then on the computer run
  `adb pair <ip>:<pairing port>` (enter the code), followed by `adb connect <ip>:<port shown on the main screen>`.

The app appears as **IPTV** in the TV's **Apps** list. Long-press it to add it to your favourites/home screen.
You can turn developer options off again afterwards if you like; the app stays installed.

## 7. Using it

| Where | Button | Does |
|---|---|---|
| Anywhere | Arrows / OK | Move / open |
| Anywhere | **Hold OK** | Add to / remove from My List (in Continue Watching: remove) |
| Live TV player | Up / Down, CH+ / CH− | Previous / next channel |
| Live TV player | OK | Show channel info |
| Movie / episode player | ◀ ▶ | Back / forward 10 s (hold for 30 s) |
| Movie / episode player | OK or ⏯ | Pause / play |
| Movie / episode player | CH+ or ⏭ | Next episode |
| Search | OK on the box | Opens the keyboard — hold the 🎤 button to speak |
| Any player | Back | Exit (your position is saved) |

## Updating

```sh
git pull
cd android && ./gradlew assembleDebug
adb connect <tv-ip>:5555 && adb install -r app/build/outputs/apk/debug/app-debug.apk
```

`install -r` keeps your My List, Continue Watching and resume positions. If you change `config.json`, rebuild
and reinstall — the details are built into the APK.

## Troubleshooting

| Problem | Fix |
|---|---|
| *"Portal rejected this device: device_id mismatch"* | Copy `stbModel` and `serialNumber` (and Device IDs if not greyed out) from STBEmu into `config.json`, rebuild, reinstall. |
| *"The portal is busy (too many requests)"* / HTTP 429 | Your provider rate-limits logins. Wait a minute or two and try again. |
| Works for a while, then streams stop | Your provider may allow one device at a time — close STBEmu or the web player. |
| `adb devices` shows `unauthorized` | Accept the prompt on the TV (step 6). If it never appeared: Developer options → *Revoke USB debugging authorizations*, then `adb connect` again. |
| `adb connect` fails / times out | Check the IP, that debugging is on, and that the computer and TV are on the same network. Try the *Wireless debugging* pairing method. |
| Build fails: *SDK location not found* | Set `ANDROID_HOME`, or create `android/local.properties` with `sdk.dir=/path/to/Sdk` (Windows: `sdk.dir=C\:\\Users\\you\\AppData\\Local\\Android\\Sdk`). |
| Build fails: *Unsupported class file major version* / Java errors | `JAVA_HOME` must point to **JDK 17**. |
| Build fails: *licences not accepted* | Run `sdkmanager --licenses`. |
| App opens but says *Could not load channels* | Your details are wrong or the portal is down. Test them with the web player (`npm start`). |
| App isn't on the home screen | Open the TV's **Apps** list; it's there as **IPTV**. |
