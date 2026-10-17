# Security policy

ultimateVE works entirely offline: it has no network permission, no account, no analytics and no downloaded code or models
(`docs/PRIVACY.md`). The things that can still go wrong are in the code that reads files you give it (media, `.srt`/`.vtt` captions,
fonts, LUTs, project bundles and LumaFusion / Final Cut / EDL imports) and in how the app stores projects.

## Supported versions

| Version | Supported |
|---|---|
| 1.0.x (latest) | Yes: bug and security fixes |
| 0.x | No: update to 1.0 |

Fixes go into a `1.0.x` release built from the `release/1.0` branch when a newer `1.x` has already shipped features.

## Reporting a vulnerability

Please do not open a public issue for a security problem. Use GitHub's private vulnerability reporting for this repository
(Security tab, "Report a vulnerability"). Include the version (About screen), the Android version and device, and, if you can, the file
that triggers it. You can expect an answer within a week, a fix in a `1.0.x` release, and a mention in the changelog unless you prefer not.

Out of scope: anything that needs the device to be rooted or the app's private storage to be already readable.
