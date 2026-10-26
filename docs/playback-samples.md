# Playback samples

The sample files that check the player handles every format (#10) on the stick, without the Provider. `scripts/playback_samples.sh` makes them with ffmpeg and pushes them into the app's own storage on the stick; the debug build's `SampleActivity` plays one with the app's player and logs (tag `Samples`) each Track, the audio output `DeviceProfile.audioOutput` predicts, and the path Media3 actually took.

```bash
scripts/playback_samples.sh /tmp/samples 192.168.4.86:5555
```

```bash
adb shell am start -f 0x10008000 -n dev.betteriptv/dev.betteriptv.app.prototype.SampleActivity -d file:///sdcard/Android/data/dev.betteriptv/files/samples/mkv_dts_ass.mkv --ei audio 0 --ei subtitle 0
```

```bash
adb logcat -s Samples
```

`audio` and `subtitle` pick Tracks by index; `--es sidecar file:///…/plain.vtt` adds a subtitle file beside the video, as a downloaded subtitle will be.

## Results

Recorded on Media3 1.8.0 with Jellyfin's FFmpeg build. The player has since moved to Media3 1.11.1 with NextLib's FFmpeg, where only DTS and ASS have been rerun so far; the full rerun is #38.

Fire TV Stick 4K Max (AFTKA, Fire OS 7, Android 9), on a TV that passes through AC-3 and E-AC-3 only, 2026-10-25.

| Sample | Audio | Subtitles | Result |
|---|---|---|---|
| `mkv_dts_ass.mkv` | DTS 5.1 | ASS with colours, italics, `\pos`, `\frz` rotation, outlines | DTS decoded by FFmpeg (`dca`) to 5.1 PCM, as predicted (`SoftwareDecoder`). ASS drawn by libass with every style. |
| `mkv_ass_attached_font.mkv` | AC-3 5.1 | ASS in a font attached to the MKV (Chalkduster, which the stick lacks) | ASS drawn in the attached font, as anime releases need. AC-3 passed through, as predicted. |
| `mkv_truehd_srt.mkv` | TrueHD 5.1 | SRT | TrueHD decoded by FFmpeg (`truehd`) to 5.1 PCM, as predicted. SRT shows. |
| `mp4_aac_tx3g.mp4` | AAC 5.1 | MP4 timed text | AAC on the stick's decoder, as predicted (`PlatformDecoder`). Timed text shows. |
| `mp4_aac_tx3g.mp4` + `plain.vtt` | | WebVTT, as a sidecar | WebVTT shows. |
| `avi_mpeg4_mp3.avi` | MP3 | | MPEG-4 video on the stick's decoder, MP3 on the stick's decoder. |
| `ts_dts_ac3.ts` | DTS 5.1 (en), AC-3 5.1 (fr) | | DTS decoded by FFmpeg; AC-3 passed through to the TV (`ENCODING_AC3`), as predicted (`Passthrough`). |

### Real samples

Files from Media3's own test data (`libraries/test_data/src/test/assets/media/` in [androidx/media](https://github.com/androidx/media), branch `release`), which ffmpeg can't make. Same stick and TV, 2026-10-25.

| Sample | Result |
|---|---|
| `mkv/sample_with_pgs_subtitles.mkv` | PGS shows. |
| `mkv/sample_with_vobsub.mkv` | VobSub shows. |
| `mkv/sample_with_vtt_subtitles.mkv` | WebVTT in MKV (`S_TEXT/WEBVTT`) shows. |
| `mkv/sample_with_dts_hd_ma.mkv` | DTS-HD MA 7.1 decoded by FFmpeg (`dca`) to 7.1 PCM, as predicted. |
| `ts/sample_dts_hd_ma.ts` | **Fails.** Media3 1.8's TS reader hands the DTS-HD extension to the decoder as a separate DTS Express format, which the FFmpeg decoder has no codec for: playback stops with an error. Fixed upstream in Media3 1.11 ([androidx/media#3147](https://github.com/androidx/media/issues/3147)). |
| `mp4/fragmented_captions.mp4`, remuxed to TS with `ffmpeg -c copy` | CEA-608 closed captions show. Media3 reads captions from TS only when the app names a caption format, which the player now does; it doesn't read them from MP4 or MKV files. |

Still not covered: DTS or TrueHD passthrough, which needs a soundbar that takes them. ffmpeg writes WebVTT in MKV with the codec ID `D_WEBVTT/SUBTITLES`, which Media3 doesn't read; mkvmerge's `S_TEXT/WEBVTT` plays. `audioOutput` doesn't model channel limits: a 7.1 Track a soundbar takes only as 5.1 may be decoded where it predicts `Passthrough`.
