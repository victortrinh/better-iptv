#!/usr/bin/env bash
# Makes the playback sample files (docs/playback-samples.md) with ffmpeg and pushes them to the
# stick, where the debug build's SampleActivity plays them.
#
# Usage: scripts/playback_samples.sh OUT_DIR [ADB_SERIAL]
set -euo pipefail
out=$1
mkdir -p "$out"
cd "$out"

cat > styled.ass <<'ASS'
[Script Info]
ScriptType: v4.00+
PlayResX: 1280
PlayResY: 720

[V4+ Styles]
Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
Style: Default,Arial,48,&H00FFFFFF,&H000000FF,&H00000000,&H80000000,0,0,0,0,100,100,0,0,1,3,2,2,40,40,40,1
Style: Sign,Arial,64,&H0000FFFF,&H000000FF,&H00800000,&H00000000,1,0,0,0,100,100,0,0,1,4,0,8,40,40,60,1

[Events]
Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
Dialogue: 0,0:00:00.50,0:00:19.50,Default,,0,0,0,,ASS bottom: {\c&H0000FF&}red{\c} and {\i1}italic{\i0}
Dialogue: 0,0:00:00.50,0:00:19.50,Sign,,0,0,0,,{\pos(640,200)\frz15\bord6}ROTATED SIGN
Dialogue: 0,0:00:00.50,0:00:19.50,Default,,0,0,0,,{\an7\pos(60,60)\fs36\c&H00FF00&}Top-left green
ASS
printf '1\n00:00:00,500 --> 00:00:19,500\nSRT subtitle line\n' > plain.srt
printf 'WEBVTT\n\n00:00:00.500 --> 00:00:19.500\nWebVTT subtitle line\n' > plain.vtt

video="-f lavfi -i testsrc2=size=1280x720:rate=24:duration=20"
surround="-f lavfi -i sine=frequency=440:duration=20,pan=5.1|c0=c0|c1=c0|c2=c0|c3=c0|c4=c0|c5=c0"
h264="-c:v libx264 -preset veryfast -pix_fmt yuv420p"
ffmpeg="ffmpeg -nostdin -y -v error"

$ffmpeg $video $surround -i styled.ass -map 0 -map 1 -map 2 $h264 -c:a dca -strict -2 -c:s ass \
    -metadata:s:a:0 language=eng -metadata:s:s:0 language=eng mkv_dts_ass.mkv
$ffmpeg $video $surround -i plain.srt -map 0 -map 1 -map 2 $h264 -c:a truehd -strict -2 -c:s srt \
    -metadata:s:a:0 language=eng mkv_truehd_srt.mkv
$ffmpeg $video $surround -i plain.srt -map 0 -map 1 -map 2 $h264 -c:a aac -c:s mov_text mp4_aac_tx3g.mp4
$ffmpeg $video -f lavfi -i sine=frequency=440:duration=20 -map 0 -map 1 -c:v mpeg4 -q:v 5 -c:a libmp3lame avi_mpeg4_mp3.avi
# Anime ASS relies on fonts attached to the MKV: this one is a Mac font the stick doesn't have.
font=${ASS_FONT:-/System/Library/Fonts/Supplemental/Chalkduster.ttf}
if [ -f "$font" ]; then
    sed 's/^Style: Default,Arial,/Style: Default,Chalkduster,/' styled.ass > attached_font.ass
    $ffmpeg $video $surround -i attached_font.ass -map 0 -map 1 -map 2 $h264 -c:a ac3 -c:s ass \
        -attach "$font" -metadata:s:t mimetype=application/x-truetype-font mkv_ass_attached_font.mkv
fi
# ffmpeg writes DTS in TS under the type Blu-ray gives it.
$ffmpeg $video $surround $surround -map 0 -map 1 -map 2 $h264 -c:a:0 dca -c:a:1 ac3 -strict -2 \
    -metadata:s:a:0 language=eng -metadata:s:a:1 language=fre ts_dts_ac3.ts

dir=/sdcard/Android/data/dev.betteriptv.debug/files/samples
serial=${2:+-s $2}
adb $serial shell mkdir -p $dir </dev/null
for file in *.mkv *.mp4 *.avi *.ts plain.vtt; do adb $serial push "$file" $dir/ </dev/null; done
