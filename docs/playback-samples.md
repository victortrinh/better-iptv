# Playback samples

The sample files that check the player handles every format (#10) on the stick, without the Provider. `scripts/playback_samples.sh` makes them with ffmpeg and pushes them into the app's own storage on the stick; the debug build's `SampleActivity` plays one with the app's player and logs (tag `Samples`) each Track, the audio output `DeviceProfile.audioOutput` predicts, and the path Media3 actually took.

```bash
scripts/playback_samples.sh /tmp/samples 192.168.4.98:5555
```

```bash
adb shell am start -f 0x10008000 -n dev.betteriptv.debug/dev.betteriptv.app.prototype.SampleActivity -d file:///sdcard/Android/data/dev.betteriptv.debug/files/samples/mkv_dts_ass.mkv --ei audio 0 --ei subtitle 0
```

```bash
adb logcat -s Samples
```

`audio` and `subtitle` pick Tracks by index; `--es sidecar file:///…/plain.vtt` adds a subtitle file beside the video, as a downloaded subtitle will be.

Between samples, press Back so `SampleActivity` finishes and releases the player, and check that no passthrough track is left:

```bash
adb shell input keyevent BACK
```

```bash
adb shell dumpsys media.audio_flinger | grep -A12 OFFLOAD
```

Don't `am force-stop dev.betteriptv` while a sample passes audio through: on Fire OS 7 the audio server keeps the killed process's direct output until a reboot, and every later passthrough sample then fails with `AUDIO_TRACK_INIT_FAILED`. If a track is stuck, `adb reboot`.

## Results

Media3 1.11.1 with NextLib's FFmpeg (`ffmpegLavc63.1.101`) and ass-media 0.5.1. Fire TV Stick 4K Max (AFTKA, Fire OS 7, Android 9), on a TV that passes through AC-3 and E-AC-3 only, 2026-10-27. The first run, on Media3 1.8.0 with Jellyfin's FFmpeg on 2026-10-25, gave the same results except for the DTS-HD MA TS file, which failed then.

| Sample | Audio | Subtitles | Result |
|---|---|---|---|
| `mkv_dts_ass.mkv` | DTS 5.1 | ASS with colours, italics, `\pos`, `\frz` rotation, outlines | DTS decoded by FFmpeg (`dca`) to 5.1 PCM, as predicted (`SoftwareDecoder`). ASS: see below. |
| `mkv_ass_attached_font.mkv` | AC-3 5.1 | ASS in a font attached to the MKV (Chalkduster, which the stick lacks) | AC-3 passed through, as predicted (`Passthrough`). libass picks the attached font. ASS: see below. |
| `mkv_truehd_srt.mkv` | TrueHD 5.1 | SRT | TrueHD decoded by FFmpeg (`truehd`) to 5.1 PCM, as predicted. SRT shows. |
| `mp4_aac_tx3g.mp4` | AAC 5.1 | MP4 timed text | AAC on the stick's decoder, as predicted (`PlatformDecoder`). Timed text shows. |
| `mp4_aac_tx3g.mp4` + `plain.vtt` | | WebVTT, as a sidecar (`--ei subtitle 1`) | WebVTT shows. |
| `avi_mpeg4_mp3.avi` | MP3 | | MPEG-4 video on the stick's decoder, MP3 on the stick's decoder. |
| `ts_dts_ac3.ts` | DTS 5.1 (en), AC-3 5.1 (fr) | | DTS decoded by FFmpeg; AC-3 passed through to the TV (`ENCODING_AC3`), as predicted. Media3 1.11 lists the AC-3 Track first (`--ei audio 0`) and DTS second. Every TS file now also shows a CEA-608 Track, since the player names that caption format. |

ASS is drawn by libass, in the render type the player uses (`OVERLAY_OPEN_GL`). A screenshot (`adb exec-out screencap`) shows no subtitles, on the Media3 1.8 build too, but the GL overlay does reach the TV: someone watching `mkv_dts_ass.mkv` there saw the red and italic words at the bottom and the rotated yellow sign (#60). So the player keeps `OVERLAY_OPEN_GL`, and checking ASS on the stick takes a look at the TV, not a screenshot. A build switched to `OVERLAY_CANVAS` shows every style in the screenshot, attached font included.

The one line that didn't show on the TV is the green one at `\an7\pos(60,60)`, 60 px from the top-left corner of a 1280×720 script. The same line moved to `\pos(320,300)` showed. So it's lost at the screen's edge, likely the TV's overscan, not left undrawn; whether the TV crops the video's corner the same way wasn't checked.

### Real samples

Files from Media3's own test data (`libraries/test_data/src/test/assets/media/` in [androidx/media](https://github.com/androidx/media), branch `release`), which ffmpeg can't make. Same stick, TV and day.

| Sample | Result |
|---|---|
| `mkv/sample_with_pgs_subtitles.mkv` | PGS shows. |
| `mkv/sample_with_vobsub.mkv` | VobSub shows. |
| `mkv/sample_with_vtt_subtitles.mkv` | WebVTT in MKV (`S_TEXT/WEBVTT`) shows. |
| `mkv/sample_with_dts_hd_ma.mkv` | DTS-HD MA 7.1 decoded by FFmpeg (`dca`) to 7.1 PCM, as predicted. |
| `ts/sample_dts_hd_ma.ts` | DTS-HD MA 7.1 decoded by FFmpeg (`dca`) to 7.1 PCM, as predicted. It failed on Media3 1.8, whose TS reader handed the DTS-HD extension to the decoder as a separate DTS Express format; 1.11 merges it into one sample ([androidx/media#3147](https://github.com/androidx/media/issues/3147)). |
| `mp4/fragmented_captions.mp4`, remuxed to TS with `ffmpeg -c copy` | CEA-608 closed captions show. Media3 reads captions from TS only when the app names a caption format, which the player does; it doesn't read them from MP4 or MKV files. |

The three subtitle MKVs are 1.2 s long, with mono 44.1 kHz AC-3 that the stick passes through. Their first subtitle shows, but playback never reaches the end: about 10 s in, Media3 1.11's stuck-player check stops it with `ERROR_CODE_TIMEOUT`; 1.8 left it stalled without an error. It's the clip, not the format: the PGS file without its audio loops cleanly, and 15 s clips of mono or 44.1 kHz AC-3 pass through without stalling.

Still not covered: ASS on the VLC engine, which `SampleActivity` can't drive. DTS or TrueHD passthrough, which needs a soundbar that takes them. ffmpeg writes WebVTT in MKV with the codec ID `D_WEBVTT/SUBTITLES`, which Media3 doesn't read; mkvmerge's `S_TEXT/WEBVTT` plays. `audioOutput` doesn't model channel limits: a 7.1 Track a soundbar takes only as 5.1 may be decoded where it predicts `Passthrough`.
