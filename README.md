# Timer

A minimal analog countdown timer for Android, built with Kotlin and Jetpack Compose in a single file.




https://github.com/user-attachments/assets/71d728d0-f0ac-42ae-9731-1303a1904f66


[Download the APK](apk/timer.apk)

## Dials

Chronograph, Bezel, Tachometer, Dot matrix, Drum, Flood, Flip clock, Hourglass and Moon. Every dial uses the same cream or black face with a red accent.

## Controls

- Drag the dial to set 1 to 60 minutes. Drum, Flood, Flip clock and Hourglass are set by dragging up or down.
- Tap to start or pause. Long-press to reset.
- The top-right buttons switch the dial, the seconds hand (ticking or sweeping) and the face colour.
- The app goes full screen while the timer runs and plays a chime when it ends.
- Turn the phone to any side, including upside down, and the dial turns to face you. Tilt it and the dial leans up to 15° over a moving dot grid. In Flood and Hourglass the surface stays level, and in Flood you can tip the phone to pour the ink out once time is up.

## Build

```
./gradlew assembleRelease
adb install build/outputs/apk/release/timer-release.apk
```
