# Releasing ultimateVE

How a release is built, signed and listed. Nothing here needs an account with a third-party service to
*build*; publishing to a store is the maintainer's own step.

## Versioning

- **One place:** `gradle/version.properties` (`versionName=MAJOR.MINOR.PATCH`).
- **`versionCode`** is derived: `major * 10000 + minor * 100 + patch` (0.1.0 is 100, 1.2.3 is 10203). Minor and patch
  must be below 100. For a one-off build the code can be forced with `-Puveditor.versionCode=<n>`.
- **Tags:** a release is the commit that sets the version plus an annotated tag `v<versionName>`. The release
  workflow refuses a tag that does not match the file.
- The version is shown in **About** and written into local crash reports.

## Building

```bash
./gradlew :app:assembleRelease :app:bundleRelease
# app/build/outputs/apk/release/app-release-unsigned.apk  (or app-release.apk when signed)
# app/build/outputs/bundle/release/app-release.aab
# app/build/outputs/mapping/release/mapping.txt            (keep it with the build)
```

The release variant is minified and shrunk with R8 (`app/proguard-rules.pro`). JNI classes, the callbacks the engine
calls by name and the `@Serializable` project models are kept. Keep the `mapping.txt` of every shipped build: it turns
a minified stack trace from a crash report back into readable names.

Without any signing configuration the build is **unsigned**, which is what the CI workflow produces.

## Signing

The keystore and its passwords are never committed (`keystore.properties`, `*.jks` and `*.keystore` are ignored).

1. Create a keystore once and keep backups offline (losing it means no more updates under the same identity):

   ```bash
   keytool -genkeypair -v -keystore ~/ultimatevideo-release.jks -alias ultimatevideo \
     -keyalg RSA -keysize 4096 -validity 10000
   ```

2. Give Gradle the values, either with a `keystore.properties` file at the repository root:

   ```properties
   storeFile=/home/you/ultimatevideo-release.jks
   storePassword=...
   keyAlias=ultimatevideo
   keyPassword=...
   ```

   or with environment variables: `UVEDITOR_KEYSTORE_FILE`, `UVEDITOR_KEYSTORE_PASSWORD`, `UVEDITOR_KEY_ALIAS`,
   `UVEDITOR_KEY_PASSWORD`.

3. Build and verify:

   ```bash
   ./gradlew :app:assembleRelease :app:bundleRelease
   "$ANDROID_HOME/build-tools/<version>/apksigner" verify --print-certs app/build/outputs/apk/release/app-release.apk
   ```

If only some of the four values are present the build stays unsigned, so a partial setup cannot sign with a wrong key.

## CI

- **`ci.yml`** runs the unit tests, host tests and a debug build on every pull request.
- **`release.yml`** (manual run or a `v*` tag) runs the tests, lint, and builds the APK and AAB. A **tag** publishes a
  GitHub Release with `ultimateVE-<version>.apk`, the AAB, `SHA256SUMS.txt` and the R8 `mapping.txt`; it is marked as a
  pre-release while the major version is 0. A tagged build **fails if the signing secrets are missing**, so an unsigned
  file is never published. A manual run only keeps the files as workflow artifacts (30 days).

### GitHub secrets (one time, same scheme as the other ultimate* repositories)

Create the key on your own machine (step 1 of Signing above) and then add the four repository secrets:

```sh
base64 -w0 ~/ultimatevideo-release.jks | gh secret set UVEDITOR_KEYSTORE_BASE64 -R qtekfun/UltimateVideoEditor
gh secret set UVEDITOR_KEYSTORE_PASSWORD -R qtekfun/UltimateVideoEditor
gh secret set UVEDITOR_KEY_ALIAS -R qtekfun/UltimateVideoEditor        # ultimatevideo
gh secret set UVEDITOR_KEY_PASSWORD -R qtekfun/UltimateVideoEditor
```

To publish: set `versionName` in `gradle/version.properties`, merge, then `git tag v<versionName> && git push origin v<versionName>`.

## Release checklist

1. `gradle/version.properties` bumped; `PLAN.md` and `docs/USER_GUIDE.md` match what ships.
2. `./gradlew :app:testDebugUnitTest :app:lintRelease` is green (the unit tests include `OfflineGuaranteeTest`: no
   network permission, no networking APIs, no tracking dependencies).
3. **Permissions review:** `aapt2 dump badging app-release.apk` lists exactly these `uses-permission` entries and nothing else:
   `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PROCESSING`, `FOREGROUND_SERVICE_DATA_SYNC` and `POST_NOTIFICATIONS`
   (all for the export's foreground service and its notification, see `docs/PRIVACY.md`), plus the app's own
   `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` (added by AndroidX; it only guards the app's own receivers). `INTERNET` or any
   other permission appearing is a release blocker (`OfflineGuaranteeTest` checks the manifest source; this step checks the merged result,
   which can gain entries from a library). The manifest declares one service, `ExportService` (not exported).
4. **Size:** note the APK and AAB sizes below; investigate a jump of more than 10 %.
5. `THIRD_PARTY_NOTICES.md` lists every bundled library and its licence (it is packed into the app and shown in About).
6. `docs/PRIVACY.md` is accurate (it is packed into the app and shown in About).
7. A signed build is installed on the reference phone (OPPO CPH2841) and a smoke test is done: create a project, import a
   clip, split, play with sound, export, open About, tips shown once.
8. Tag `v<versionName>`, run the release workflow, download the artifacts, check the checksums, keep `mapping.txt`.
9. Store listing updated (below).

Size log (R8-minified, arm64-v8a only):

| Build | APK | AAB |
|---|---|---|
| Before minification (0.1.0, unminified release) | 30,142,527 bytes | n/a |
| 0.1.0 with R8 and resource shrinking (on master at #62 plus multicam) | 6,616,210 bytes | 7,843,562 bytes |

## Store listing (Google Play, English)

**App name:** ultimateVE

**Short description (80 characters):** Private multitrack video editor: edit, grade and export offline.

**Full description:**

> ultimateVE is a fast, multitrack video editor for Android built for people who want control over their footage and
> nothing else watching it. It works entirely offline: no account, no ads, no analytics, no network permission.
>
> Edit like on a desktop timeline:
> - A magnetic base track with free overlay tracks above it, and audio below.
> - Split, trim, move, overwrite, insert and group edits, with unlimited undo.
> - Drag and drop from a media tray, with a live indicator of what a drop will do.
> - A layout you can resize and arrange.
>
> Make it look and sound right:
> - Colour tools with waveform, vectorscope and histogram, curves, looks and 3D LUTs.
> - SDR and HDR (HLG) clips in one project, converted per clip.
> - Keyframes on position, scale, rotation, opacity and effect values, speed ramps, reverse and freeze frames.
> - Audio mixer with pan, EQ, noise reduction, loudness normalisation and ducking.
> - Titles with several layers, your own fonts, stickers, captions from subtitle files and animated caption styles.
> - Chroma key, masks, blend modes and a stabiliser.
>
> Share anywhere:
> - Export MP4 (H.264 or HEVC, HDR HLG when your phone supports it) with presets for YouTube, Shorts, TikTok and Reels.
> - Projects, EDL and FCPXML files stay yours: export a bundle and open it on another device.
>
> Free software (GPL-3.0). Source code and privacy statement are in the app under About.

*Check each claim against the build being released before publishing; remove what is not shipped yet.*

**Category:** Video Players & Editors. **Content rating:** Everyone (no user-generated content sharing inside the app).

## Data safety form (Google Play)

- **Does the app collect or share any of the required user data types?** No.
- **Is all of the user data collected by your app encrypted in transit?** Not applicable (no data collected, no network).
- **Do you provide a way for users to request that their data be deleted?** Not applicable (no data leaves the device;
  users can delete projects and caches in the app, or uninstall).
- **Permissions declared:** `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PROCESSING`, `FOREGROUND_SERVICE_DATA_SYNC` (a foreground
  service so an export keeps running when the user leaves the app) and `POST_NOTIFICATIONS` (its progress notification, optional).
  No network, location, contacts, storage, camera or microphone permission. The foreground service types are declared as
  `mediaProcessing` (media encoding); `dataSync` is only the type Android 12 to 14 offers and no data is synchronised.
- **Data handling statement:** all projects, media references and caches stay in the app's private storage on the
  device. Media files are read through the system file picker. Crash reports are text files stored locally and only
  shared if the user taps Share.

## Screenshots checklist

Phone (portrait), at least 4 and at most 8, 1080x1920 or larger, real content (use footage you own):

1. Project list with the New project sheet (aspect, resolution, frame rate, colour space selectors).
2. Editor: timeline with a base track, two overlay lanes, audio, and the preview.
3. Media tray open with the drop indicator while dragging a clip.
4. Colour tools with scopes visible.
5. Inspector with keyframes or the audio mixer.
6. Export dialog with a platform preset and the time estimate.
7. About screen showing the privacy statement.

Tablet (7 and 10 inch), if offered: the two-panel layout preset with the tray on the left.
Feature graphic 1024x500 and a 512x512 icon: the launcher icon is an original adaptive icon (three timeline clips and
an amber playhead on a deep blue gradient; layers in `res/drawable/ic_launcher_{background,foreground,monochrome}.xml`,
declared in `res/mipmap-anydpi/ic_launcher.xml`, with a monochrome layer for themed icons). All foreground content lies
inside the 66dp safe-zone circle (farthest point about 30 units from the centre of the 108 x 108 canvas), which
`IconGeometryTest` checks. Export the 512x512 Play icon by rendering the foreground over the background at 512 px (for
example with Android Studio's Image Asset tool or `rsvg-convert` after merging the two vectors); it has not been
rendered here, so look at it once at 48dp, 72dp and with a circular mask before publishing.
