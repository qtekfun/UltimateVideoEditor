# Export benchmarks on real devices

Real exports from the project owner's own devices, taken from the export dialog ("Exported in M:SS, checked in N s", SPECS 5.35 / post-export check). Speed is the project length divided by the export time; frames per second is frames written divided by the export time. These are single runs of real projects, not a controlled benchmark: the settings the screenshot does not show are marked "not recorded", and nobody should compare rows with different content.

| Date | Device | SoC / memory | Android | App | Project | Output | Length | Export time | Check | Speed | Written fps |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 2026-10-07 | OPPO Find X9 Ultra (CPH2841, global) | Snapdragon 8 Elite Gen 5, 12 GB RAM / 512 GB | 16 | not shown (latest release at that time was 0.3.10) | "OPPO Find X10 Pro Max": several 4K clips, HDR HLG project, 60 fps | `OPPO Find X10 Pro Max.mp4`; codec, bitrate and resolution not recorded | 16:36.8 (59,810 frames) | 8:58 | complete, 8 s | 1.85x real time | about 111 fps |

How to add a row: note the numbers from the export dialog right after an export (the dialog shows the saved name, the frame count and length, the export time and the check time) and, if possible, the codec, bitrate, resolution and whether the project is HDR from the export dialog before starting it. Use the same project on another device to compare devices, and the same device with another app version to compare versions.

The scripted device checks (`scripts/qa-smoke.sh`, `docs/QA.md`) are a different thing: they assert correctness on synthetic media and are not timed for comparison.
