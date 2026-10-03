# Privacy

ultimateVE is built so that your footage and projects never leave your device and nothing about how you use the
app is collected. This is a hard rule of the project, not a setting.

## What the app does not do

- **No network access.** The app declares no permissions (not even `INTERNET`), so Android itself prevents it
  from connecting to anything. Cleartext traffic is disabled as well.
- **No accounts, no sign-in, no cloud.** Projects live in the app's private storage.
- **No analytics, no telemetry, no crash-reporting service, no advertising.** There is no SDK for any of these in the
  app, and nothing is sent anywhere. Crash information, if you need to share it, is your own `adb logcat` output.
- **No AI or machine-learning features** and no models, downloaded or bundled. Captions are typed by you or imported
  from a subtitle file; features that would need a model or a server are out of scope for the project.
- **No third-party services.** The app depends only on open-source libraries that run inside it (Android Jetpack,
  Kotlin and kotlinx libraries, Oboe). See `THIRD_PARTY_NOTICES.md`.
- **No backups to the cloud.** Android backup is disabled for the app (`allowBackup="false"`).

## What is stored, and where

All of it is on your device, in the app's private storage unless you export it yourself:

| Data | Where | Why |
|---|---|---|
| Projects (`project.json` and its `.bak`) | App-private `files/projects/` | Your timelines |
| Waveform and thumbnail caches | App-private cache folders | Speed; safe to delete |
| 3D LUT library | App-private storage | LUT files you imported |
| Preferences (layout, last choices) | App-private preferences | Remember your settings |
| Your media | **Not copied**: projects keep a `content://` reference | Saves space; you stay in control |

Exports are written only where you choose with the system file picker.

## Permissions

The app asks for **none**. It reads media through the Android system file picker (Storage Access Framework), which
gives it access only to the files you pick. When you choose a file, Android may grant the app a persistent read
permission for that file so a project can reopen it; the app releases permissions that no project uses any more.

## How to verify it yourself

1. Look at the manifest: `app/src/main/AndroidManifest.xml` declares no `uses-permission` entries. In the built APK,
   `aapt2 dump permissions app-debug.apk` lists only `com.ultimatevideo.uveditor.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`,
   a private permission that AndroidX adds so the app's own broadcast receivers cannot be called from other apps; it
   grants access to nothing.
2. On the phone: Settings > Apps > ultimateVE > Permissions shows none; in a firewall app or with
   "Restrict mobile data and Wi-Fi", the app has no traffic because it never opens a connection.
3. In the repository: `./gradlew :app:testDebugUnitTest --tests '*OfflineGuaranteeTest*'` fails if a network permission,
   a networking API (HTTP, sockets, WebView, download managers), an analytics, crash-reporting or advertising
   dependency, or cleartext traffic is ever introduced. CI runs it on every pull request.
4. The source is open (GPL-3.0): read it.

## Changes to this document

Any change that would weaken these guarantees needs an explicit decision recorded in `DECISIONS.md` and an update of
the test above; the default answer is no.
