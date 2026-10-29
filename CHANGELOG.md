# Changelog

Format: newest first. `1.0.x` releases only fix bugs; `1.x.0` releases add features and never break saved projects (older files load,
and fields added later are optional in the project JSON). The detail behind each decision is in `DECISIONS.md`.

## Unreleased

- **4K exports no longer stall on phones with few video decoders.** While a video is exported the editor's preview lets go of its decoders
  (titles and photos are still shown) and no proxy is made, so the export gets every hardware decoder. On some phones the preview's
  decoders left none for a 4K export with several clips, and it failed with "the decoder stalled". The preview comes back when the export ends.
- **A video the phone's software decoder cannot read is no longer handed to it.** 10-bit, HDR or larger-than-1080p video used to fall
  back to it and stall; the export now first frees the decoders the current frame does not use and tries again, and otherwise says plainly
  that no decoder could be started.

## 1.4.5

- **Deleting a clip from the base track no longer cuts the clips above it.** Clips on the other tracks are tied to the moment they start: one
  that starts after the deleted clip moves back with the base (B-roll stays with its A-roll), one that starts earlier or over it stays whole
  where it is. If a clip that stayed now runs into one that moved, it is shortened to end where that one starts. The clip's own linked audio
  still goes with it. (1.4.3 only fixed this for the scissors.) Auto cut and empty template slots still remove the range from every track.

## 1.4.4

- **Proxies no longer compete with the preview.** No new proxy starts while the preview plays, and when the system warns it is short of memory the
  running proxy is put back in the queue and the queue waits two minutes. This avoids the app being closed by the system when a 4K preview and a
  proxy ran at once.
- **The preview sizes itself to the device.** The decoded-frame cache was a fixed 1 GiB, meant for high-end phones. It is now 96 MB on phones
  with 3 GB or less (or flagged low-RAM), 256 MB up to 6 GB and 512 MB above, with two, three or four preview decoders at most. When free memory
  runs low the cache shrinks to a half or a quarter for a minute, and proxies step back too.
- **The "new video track" drop target stays put.** While dragging a media item or a clip toward a new video track, the target no longer
  opens and closes again on its own when the timeline grows by a row or the finger jitters.

## 1.4.3

- **The screen stays on while the preview plays.** It no longer turns off in the middle of a playback; paused or idle it times out as usual.
- **Playing the preview stops other apps' music.** The preview takes audio focus when it starts and gives it back when it stops. If another app
  (a call, other media) takes focus, the preview pauses. A short duck is ignored.
- **Cut works with nothing selected.** The scissors button was greyed out until a clip was selected, so the "cut every lane under the
  playhead" behaviour could not be reached from the toolbar. It is always enabled now. Cutting a selected base clip never touches the
  lanes above it (covered by new tests).
- **Photos and stickers look cleaner in the preview.** Pictures are drawn with mipmaps, so a still shown smaller than its texture (a 4K project on
  the phone screen, a scaled-down clip) no longer shimmers. A portrait photo on a landscape canvas (and the reverse) is now decoded close to the
  size it is shown at, instead of up to three times larger. Stickers are drawn at twice their size, so they stay sharp when scaled up.

## 1.4.2

- **The cropped picture after a rotation goes away sooner.** The surfaces are rebuilt as soon as the view has kept its size for a few frames,
  instead of after a fixed wait.

## 1.4.1

- **Rotating back to portrait no longer leaves the preview and the timeline cropped.** The system could keep the size of a frame in the middle of the
  turn animation for both surfaces. Once the turn settles they are now rebuilt at the final size. (1.3.1 did not fix it.)

## 1.4.0

- **Zoom to detail (optional).** A switch in the layout sheet adds a button next to Fit, and a double tap on the time ruler, that zoom the
  timeline all the way in to single frames around the playhead (or the tapped point) and, pressed again, back out to the whole project.
  Off by default.

## 1.3.1

- **The preview and the timeline no longer stay cropped after rotating.** Going back to portrait could leave both drawn in a third of the
  width. A resize now takes the preview's window surface again, as the timeline already did, and the timeline does it on every size callback.

## 1.3.0

- **HDR footage no longer dazzles next to photos and SDR video.** HLG and PQ clips are measured in the background (a dozen frames of the original,
  once per file) and, when their everyday content (skin, walls) is brighter than the 203 nit reference white, dimmed to it, never by more than
  one and a half stops and never brightened. The gain is applied to the source before effects, in the preview and in the export alike, and
  also before the tone map in an SDR project. Project setting "HDR brightness" (Comfortable, the default, or Original) and a per-clip line
  "HDR level: -0.8 steps (auto)" with a switch in the inspector.
- **Ultra HDR photos keep their gain map in an HDR project.** The photo is made into its HDR picture (HLG, white at 203 nit) instead of its
  SDR rendition; photos without a gain map and every photo in an SDR project are shown as before.

## 1.2.2

- **Cutting with several clips selected no longer empties the lane.** After a cut only the parts that were cut stay selected, so deleting one
  piece leaves the other selected clips alone.

## 1.2.1

- **Dragging several selected clips from a clip's edge moves them all.** It used to trim the clip you grabbed.

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
