# Privacy

ultimateVE is built so that your footage and projects never leave your device and nothing about how you use the
app is collected. This is a hard rule of the project, not a setting.

## What the app does not do

- **No network access.** The app declares no network permission (not even `INTERNET`), so Android itself prevents it
  from connecting to anything. Cleartext traffic is disabled as well.
- **No accounts, no sign-in, no cloud.** Projects live in the app's private storage.
- **No analytics, no telemetry, no crash-reporting service, no advertising.** There is no SDK for any of these in the
  app, and nothing is sent anywhere. If the app crashes it writes a short text report **on your device only** (exception types,
shortened messages with any file path, name or content address removed, stack frames, app version, phone model and Android
version). The five latest reports are kept (`last-crash.txt`, `last-crash.1.txt` ... `last-crash.4.txt`). You can read, copy, share
or delete them in About; they only leave the phone if you tap Share, through the standard share sheet.
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
| Preferences (layout, last choices, the language you picked, whether the first-run tips were shown) | App-private preferences | Remember your settings |
| Translations (English, Spanish and any later language) | Inside the app (string resources, bundled at build time) | Nothing is downloaded and no translation service is used; the language setting is Android's own per-app language, kept on the phone |
| Last crash reports (`files/crash/last-crash.txt` and four older ones, at most 64 KB each) | App-private storage | Lets you share a bug report if you choose (it also summarises a native crash or ANR the system recorded for the app: no media or project names); deletable in About |
| Your media | **Not copied**: projects keep a `content://` reference | Saves space; you stay in control |

Exports are written only where you choose with the system file picker.

## Permissions

The app declares exactly five permissions, and none of them is about the network. Four exist so that an export
can keep running when you leave the app, switch to another app or the screen turns off; the fifth is the microphone, for the voice-over only:

| Permission | What it is for |
|---|---|
| `FOREGROUND_SERVICE` | Run the export as a foreground service, which Android keeps alive while its notification is shown |
| `FOREGROUND_SERVICE_MEDIA_PROCESSING` | The service type for Android 15 and later: "this service is processing media" |
| `FOREGROUND_SERVICE_DATA_SYNC` | The service type Android 12 to 14 offers for a long task. Nothing is synchronised and nothing is sent anywhere; it is only the closest label that exists before Android 15 |
| `POST_NOTIFICATIONS` | Show the export's progress notification (Android 13 and later). It is asked for when you start an export, and it is optional: if you say no, the export still runs, you just see no notification |
| `RECORD_AUDIO` | Record a voice-over. It is asked for the first time you press the microphone button (**Voice-over**) in the tool row of the editor, never at start, and it is optional: if you say no, nothing else changes and the system's own recorder app can be used instead |

The microphone is used only between the moment the record button starts the count-in and the moment you stop the take (or the app leaves the
screen). The take is written as a sound file in the project's own folder on the phone, becomes an ordinary audio clip on the timeline and is
deleted with the project; nothing is analysed, transcribed or sent anywhere, and the app never listens in the background. Android shows its
microphone indicator while the app records.

The app still has **no** network, location, contacts, camera or storage permission and no analytics: reading media
goes through the Android system file picker (Storage Access Framework), which
gives it access only to the files you pick. The export notification shows the project name and a percentage on your own
device, and nothing else. When you choose a file, Android may grant the app a persistent read
permission for that file so a project can reopen it; the app releases permissions that no project uses any more. When you link a
folder in the media tray, Android grants the app a persistent read permission for that folder and everything inside it. The list of
linked folders is kept in the app's private preferences, the app only reads the folder (it never writes, moves or copies anything in
it) and unlinking a folder removes it from the list.

## How to verify it yourself

1. Look at the manifest: `app/src/main/AndroidManifest.xml` declares only the five `uses-permission` entries above. In the built APK,
   `aapt2 dump permissions app-debug.apk` lists those five and `com.qtekfun.ultimatevideoeditor.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`,
   a private permission that AndroidX adds so the app's own broadcast receivers cannot be called from other apps; it
   grants access to nothing.
2. On the phone: Settings > Apps > ultimateVE > Permissions shows at most Notifications and Microphone (the latter only once you have used the voice-over); in a firewall app or with
   "Restrict mobile data and Wi-Fi", the app has no traffic because it never opens a connection.
3. In the repository: `./gradlew :app:testDebugUnitTest --tests '*OfflineGuaranteeTest*'` fails if any permission other than those five appears, or a network permission,
   a networking API (HTTP, sockets, WebView, download managers), an analytics, crash-reporting or advertising
   dependency, or cleartext traffic is ever introduced. CI runs it on every pull request.
4. The source is open (GPL-3.0): read it.

## Changes to this document

Any change that would weaken these guarantees needs an explicit decision recorded in `DECISIONS.md` and an update of
the test above; the default answer is no.
