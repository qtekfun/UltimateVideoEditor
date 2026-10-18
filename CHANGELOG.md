# Changelog

Format: newest first. `1.0.x` releases only fix bugs; `1.x.0` releases add features and never break saved projects (older files load,
and fields added later are optional in the project JSON). The detail behind each decision is in `DECISIONS.md`.

## 1.2.0

- **Selected clips move together to wherever you let go.** Base clips can be dragged to an overlay lane (the base closes behind them); dragging above
  the top lane makes new lanes; a group with base clips can also be dropped into a cut of the base.
- **Select mode scrolls.** Dragging empty space no longer draws a rectangle: taps select, drags scroll.
- **The lane new media goes to follows the selected clip** when it changes lane.

## 1.1.0

- **Spanish everywhere.** The media tray, library, captions, titles, multicam, proxies, templates, scopes, stickers, frame saving, the first-run
  tips, the toolbar guide, the privacy statement, the labels on the timeline and the error and report messages of the lower layers are translated.
- **Drop mode button** (Auto / Insert / Overwrite) in the tool row; overwrite works for a group of clips too (see 1.0.1).
- **Flag list** exports Markdown (`- [ ] 00:00:12:05 · name: note`), a `.md` file or CSV.

## 1.0.1

- Drop mode button, group overwrite on the base track, Markdown flag list, flag popup layout, privacy scrub of the repository.

## 1.0.0

First stable release. Same code as 0.5.13 plus the maintenance documents (this file, `SECURITY.md`, the support policy in `docs/RELEASE.md`).

## 0.5.x (highlights)

- **0.5.13**: flag popup colours no longer squeezed; flag list group names capitalised.
- **0.5.12**: drop a selection of overlay clips (photos on V2) into a cut of the base track together, in order, in one undo step; the
  playhead goes to the end of what was inserted; the media tray's *Select* mode numbers the media you pick and *Add N* inserts them in
  order; the flag list has its own toolbar button.
- **0.5.11**: flag list (shot list): tick flags as shot, group by colour, filter, share as checkbox text or save as CSV.
- **0.5.10**: the preview follows a proxy that finishes (and shows the HDR / SDR proxy label).
- **0.5.9 and 0.5.8**: HDR proxies (HLG 10-bit), paste asks Content or Style, clip volume button, drag axis lock.
- **0.5.0 to 0.5.7**: projects screen in library style, relink by folder scan, missing-media placeholder, sticky markers, Spanish
  translation of the editor, bundle export / import with progress, Dependabot.

## 0.4.x and earlier

0.4.0 renamed the application id to `com.qtekfun.ultimatevideoeditor` (projects of the old app move with a bundle export / import).
0.3.x added the native timeline waveform redesign, fades and the volume curve, detach audio with links, insert / overwrite drag and the
QA smoke runner. Earlier versions built the editor, engine, export, audio, colour and HDR pipelines (see `PLAN.md`).
