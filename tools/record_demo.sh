#!/bin/bash
set -euo pipefail

DEVICE="${DEVICE:-emulator-5554}"
ADB="$HOME/Library/Android/sdk/platform-tools/adb -s $DEVICE"
PACKAGE="dev.aahnik.timer"
WORK_DIR="$(mktemp -d ./demo-recording.XXXX)"
OUTPUT="docs/demo.mp4"

tap() { $ADB shell input tap "$1" "$2"; }
next_dial() { tap 744 226; sleep 0.8; }
toggle_face() { tap 996 226; sleep 0.6; }
turn_dial() { $ADB shell input swipe 900 1212 540 1640 600; sleep 0.4; }
drag_up() { $ADB shell input swipe 540 1700 540 1350 600; sleep 0.4; }
run_briefly() { tap 540 2100; sleep "$1"; tap 540 2100; sleep 0.4; }
long_press_reset() { $ADB shell input swipe 540 2100 541 2100 800; sleep 0.8; }

record() {
    $ADB shell screenrecord --time-limit "$1" --size 720x1616 --bit-rate 6000000 "/sdcard/$2.mp4" &
    RECORDER=$!
    sleep 1
}

$ADB shell pm clear "$PACKAGE" > /dev/null
$ADB shell am start -n "$PACKAGE/.MainActivity" > /dev/null
sleep 2

record 45 tour
turn_dial; run_briefly 2; long_press_reset
next_dial; turn_dial; run_briefly 1.6
next_dial; run_briefly 1.6
next_dial; toggle_face; run_briefly 1.6
next_dial; drag_up; run_briefly 2
next_dial; toggle_face; run_briefly 1.4
next_dial; long_press_reset; run_briefly 2.2
next_dial; run_briefly 2
next_dial; tap 870 226; run_briefly 2
wait "$RECORDER"

for _ in 1 2 3 4 5 6; do next_dial; done
long_press_reset
for _ in 1 2 3; do $ADB shell input swipe 540 600 540 2200 500; done
sleep 0.6
record 64 flood
tap 540 2100
wait "$RECORDER"

$ADB pull /sdcard/tour.mp4 "$WORK_DIR/tour.mp4" > /dev/null
$ADB pull /sdcard/flood.mp4 "$WORK_DIR/flood.mp4" > /dev/null
$ADB shell rm /sdcard/tour.mp4 /sdcard/flood.mp4

mkdir -p docs
ffmpeg -loglevel error -y -i "$WORK_DIR/tour.mp4" -i "$WORK_DIR/flood.mp4" -filter_complex \
    "[0:v]setpts=PTS-STARTPTS,fps=30[tour];[1:v]setpts=(PTS-STARTPTS)/6,fps=30[flood];[tour][flood]concat=n=2:v=1:a=0[video]" \
    -map "[video]" -c:v libx264 -crf 26 -pix_fmt yuv420p -movflags +faststart "$OUTPUT"
rm -rf "$WORK_DIR"
ffprobe -v error -show_entries format=duration -of csv=p=0 "$OUTPUT"
