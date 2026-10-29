# Installing the Android TV app

Get the app onto an Android TV / Google TV (Sony Bravia, TCL, Hisense, Chromecast with Google TV,
Nvidia Shield, …), then sign in with your own IPTV details on the TV.

> **You need your own IPTV subscription** that uses a **Stalker / Ministra portal** (the kind you'd normally
> use with STBEmu or a MAG box): a **portal URL** and a **MAC address** registered with your provider.
> This project is only a player; it has no content of its own.

There are three ways to install it. **Most people want option A.**

| | Option | You need |
|---|---|---|
| **A** | [Download the APK on the TV](#a-download-on-the-tv-no-computer) | Just the TV |
| **B** | [Install the APK from a computer](#b-install-from-a-computer-adb) | A computer on the same network |
| **C** | [Build it yourself](#c-build-it-yourself) | A computer + ~3 GB of build tools |

After installing, open the app and [enter your portal details](#first-launch-enter-your-portal-details).

---

## A. Download on the TV (no computer)

1. On the TV, install **Downloader** by AFTVnews from the Google Play Store.
2. Allow it to install apps: Settings → **Apps → Security & restrictions → Unknown sources** (or
   *Install unknown apps*), then turn on **Downloader**. The wording varies slightly between TVs.
3. Open Downloader and enter this address:

   ```
   https://github.com/harmishpatel21/IPTV/releases/latest/download/IPTV-tv.apk
   ```

4. When the download finishes, choose **Install**, then **Open**.

The app also appears as **IPTV** in the TV's **Apps** list. Long-press it to add it to your home screen.

## B. Install from a computer (adb)

Use this if your TV has no Downloader app, or you built the APK yourself.

1. **Get the APK.** Download `IPTV-tv.apk` from this repo's
   [Releases page](https://github.com/harmishpatel21/IPTV/releases/latest), or build it (option C).
2. **Install adb.** It's in Google's
   [SDK Platform Tools](https://developer.android.com/tools/releases/platform-tools). Unzip it anywhere.
3. **Turn on developer options on the TV.** Settings → **System → About** (on some TVs **Device Preferences
   → About**). Scroll to **Build** and press OK on it **7 times** until it says *"You are now a developer"*.
4. **Turn on debugging.** Settings → **System → Developer options** → enable **USB debugging**, and
   **Network debugging** / **ADB debugging** if you see it.
5. **Find the TV's IP address.** Settings → **Network & Internet** → your network (e.g. `192.168.1.45`).
6. From the computer (same network as the TV):

   ```sh
   adb connect 192.168.1.45:5555        # your TV's IP
   adb install -r IPTV-tv.apk
   ```

   - The first time, the TV asks **"Allow USB debugging?"**. Tick **Always allow from this computer**, press
     **OK**, then run `adb connect` again. `adb devices` should list the TV as `device`, not `unauthorized`.
   - **Only "Wireless debugging" (Android 11+)?** Open it on the TV, choose **Pair device with pairing code**,
     run `adb pair <ip>:<pairing port>` and enter the code, then `adb connect <ip>:<port on the main screen>`.

You can turn developer options off again afterwards. The app stays installed.

## C. Build it yourself

### 1. Install the build tools (JDK 17 + Android SDK)

**Easiest:** install [Android Studio](https://developer.android.com/studio) and finish its setup wizard. It
includes everything.

**Or command-line only (~1 GB):**

| | Windows | macOS | Ubuntu / Debian |
|---|---|---|---|
| JDK 17 | `winget install Microsoft.OpenJDK.17` | `brew install --cask temurin@17` | `sudo apt install openjdk-17-jdk` |

Then download *Command line tools only* from
[developer.android.com/studio](https://developer.android.com/studio#command-line-tools-only) and unzip it so
the path is `<SDK>/cmdline-tools/latest/bin/`. Set `ANDROID_HOME=<SDK>` and `JAVA_HOME=<your JDK 17>`,
add `<SDK>/platform-tools` to `PATH`, open a new terminal and run:

```sh
sdkmanager --licenses          # answer y to each  (Windows: sdkmanager.bat)
sdkmanager "platform-tools"
```

The build downloads the rest of the SDK by itself.

### 2. Build

```sh
git clone https://github.com/harmishpatel21/IPTV.git
cd IPTV/android
./gradlew assembleRelease      # Windows: gradlew.bat assembleRelease
```

The first build takes 5–10 minutes. Result: `android/app/build/outputs/apk/release/app-release.apk`.
It contains no account details, so it's safe to share. Install it with option B.

**Optional: build your details in.** A **debug** build (`./gradlew assembleDebug`) reads `config.json` from
the repo root (copy `config.example.json`; it's git-ignored), so your own TV skips the setup screen. The
details then sit inside that APK, so **don't share a debug build**. The result is
`android/app/build/outputs/apk/debug/app-debug.apk`.

---

## First launch: enter your portal details

The first time it opens, the app asks for your account. Use the arrows to move and **OK** on a field to
open the keyboard. Hold the 🎤 button on the remote to dictate instead of typing.

| Field | Where to find it | Required? |
|---|---|---|
| **Portal URL** | From your provider, e.g. `http://example.com:8080/c/` (`http://` is added if you leave it out) | yes |
| **MAC address** | From your provider, e.g. `00:1A:79:12:34:56` (colons optional) | yes |
| **STB model** | Pick with the arrows (default MAG250) | if your provider expects another |
| **Serial number** | Often needed: see below | sometimes |
| **Device ID / Device ID 2 / Signature** | Only if STBEmu shows values for them | rarely |
| **Time zone** | Filled in automatically; used for the TV guide | no |

Press **Test & Save**. The app logs in to check the details, then opens your channels. You can change them
any time under the **⚙** tab.

**Coming from STBEmu?** Copy what it uses, from **Settings → Profiles → your profile**: the portal URL and MAC,
plus, under **STB Configuration**, the **STB model** and **Serial number** (and the Device IDs if they aren't
greyed out). Many portals only accept the exact box identity your account was activated with.

## Using it

| Where | Button | Does |
|---|---|---|
| Anywhere | Arrows / OK | Move / open |
| Anywhere | **Hold OK** | Add to / remove from My List (in Continue Watching: remove) |
| Live TV player | Up / Down, CH+ / CH− | Previous / next channel |
| Live TV player | OK | Show channel info |
| Movie / episode player | ◀ ▶ | Back / forward 10 s (hold for 30 s) |
| Movie / episode player | OK or ⏯ | Pause / play |
| Movie / episode player | CH+ or ⏭ | Next episode |
| Search | OK on the box | Opens the keyboard (or hold 🎤 to speak) |
| Any player | Back | Exit (your position is saved) |

## Updating

Install the new APK the same way (Downloader, or `adb install -r IPTV-tv.apk`). Your details, My List,
Continue Watching and resume positions are kept.

If the install fails with *"App not installed"* or *signatures don't match*, the new APK was signed with a
different key (e.g. you switched between your own build and a downloaded one). Uninstall the app first
(this clears its data), then install again.

## Troubleshooting

| Problem | Fix |
|---|---|
| *"Portal rejected this device: device_id mismatch"* | Set the **STB model** and **Serial number** (and Device IDs if STBEmu shows them) to match STBEmu. |
| *"Could not log in to the portal…"* | Check the portal URL and MAC. Your provider may also need your MAC activated first. |
| *"The portal is busy (too many requests)"* | Your provider rate-limits logins. Wait a minute or two, then try again. |
| Streams stop after a while / *"…another device"* | Many providers allow one device at a time. Close STBEmu or any other player on the same account. |
| Downloader says it can't install | Allow **Unknown sources / Install unknown apps** for Downloader (option A, step 2). |
| `adb devices` shows `unauthorized` | Accept the prompt on the TV. If it never appeared: Developer options → *Revoke USB debugging authorizations*, then `adb connect` again. |
| `adb connect` fails / times out | Check the IP, that debugging is on, and that both are on the same network. Try the *Wireless debugging* pairing method. |
| Build: *SDK location not found* | Set `ANDROID_HOME`, or create `android/local.properties` with `sdk.dir=/path/to/Sdk` (Windows: `sdk.dir=C\:\\Users\\you\\AppData\\Local\\Android\\Sdk`). |
| Build: *Unsupported class file major version* | `JAVA_HOME` must point to **JDK 17**. |
| Build: *licences not accepted* | Run `sdkmanager --licenses`. |

---

## For maintainers: publishing releases

The **Android APK** workflow (`.github/workflows/android.yml`) builds the shareable release APK on every push
that touches `android/`, and uploads it as a workflow artifact. To publish a downloadable release:

```sh
git tag v0.2 && git push origin v0.2
```

The APK is attached to a GitHub Release as `IPTV-tv.apk`, so the
`…/releases/latest/download/IPTV-tv.apk` link in option A always points to the newest version.

**Use a fixed signing key**, or users can't install updates over an older version. Create one once and keep it
safe (losing it means users must uninstall to update):

```sh
keytool -genkeypair -v -keystore iptv-release.keystore -alias iptv -keyalg RSA -keysize 4096 -validity 10000
```

Then add these **repository secrets** (Settings → Secrets and variables → Actions):

| Secret | Value |
|---|---|
| `RELEASE_KEYSTORE_BASE64` | the keystore file as base64 (`base64 -w0 iptv-release.keystore`; PowerShell: `[Convert]::ToBase64String([IO.File]::ReadAllBytes("iptv-release.keystore"))`) |
| `RELEASE_KEYSTORE_PASSWORD` | keystore password |
| `RELEASE_KEY_ALIAS` | `iptv` (or the alias you chose) |
| `RELEASE_KEY_PASSWORD` | key password |

Without them the workflow still builds, but signs with a throwaway key. Fine for testing, but each release
then needs an uninstall to update.
