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
- **`release.yml`** (manual run or a `v*` tag) runs the tests, lint, and builds the unsigned APK and AAB, then uploads
  them with `SHA256SUMS.txt` and the R8 mapping as workflow artifacts (30 days). It needs no secrets and publishes
  nothing. The commented block shows how a maintainer can add signing secrets later.

## Release checklist

1. `gradle/version.properties` bumped; `PLAN.md` and `docs/USER_GUIDE.md` match what ships.
2. `./gradlew :app:testDebugUnitTest :app:lintRelease` is green (the unit tests include `OfflineGuaranteeTest`: no
   network permission, no networking APIs, no tracking dependencies).
3. **Permissions review:** `aapt2 dump badging app-release.apk` lists no `uses-permission` other than the app's own
   `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` (added by AndroidX; it only guards the app's own receivers).
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
| 0.1.0 with R8 and resource shrinking | about 6.5 MB | about 7.8 MB |

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
- **Permissions declared:** none.
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
Feature graphic 1024x500 and a 512x512 icon: the current launcher icon is a placeholder vector and should be replaced
by a designed icon before publishing.
