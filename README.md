# Camera Eyes

A simple Android app that displays two USB (UVC) webcams **side-by-side** on one screen.

Built for two identical *Microdia Webcam Vitade AF* units (`0c45:6366`), but works with any two
UVC cameras. Uses [AndroidUSBCamera (AUSBC)](https://github.com/jiangdongguo/AndroidUSBCamera)
`libausbc` to drive both cameras over USB host mode — **not** Camera2/CameraX, which can't reliably
open two external USB cameras at once.

## What it does

- Opens up to two UVC cameras and shows each in its own half of the screen (left / right).
- Per-camera status overlay: *Waiting → Connecting → Opening → (live)*, plus *Disconnected* and
  *Camera error* messages.
- Handles plug/unplug: a removed camera's slot returns to *Waiting* and recovers on reattach.

## Hardware requirements

- An Android device with **USB host / OTG** support.
- A **powered USB hub** (a single OTG port can't power two cameras — expect disconnects/green frames
  without external power). Plug both cameras into the hub, hub into the phone via an OTG cable.
- Both cameras share the same VID:PID, so which feed appears left vs right depends on **which hub
  port** each is plugged into. Fix the cabling if a stable left/right matters.

## Build

The Android toolchain for this project is installed locally under `~/Android` (rootless):

- JDK 17: `~/Android/jdk-17.0.19+10`
- Android SDK: `~/Android/Sdk` (also recorded in `local.properties`)

```bash
cd ~/repos/camera-eyes
export JAVA_HOME="$HOME/Android/jdk-17.0.19+10"
export ANDROID_HOME="$HOME/Android/Sdk"
./gradlew assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`

> First build downloads AGP, Kotlin, AndroidX, and the AUSBC AAR. The AUSBC dependency is pinned to
> **`libausbc:3.2.7`** — every `3.3.x` tag fails its native build on JitPack (`libnative-3.3.x.aar`
> 404s). AUSBC also pulls old jcenter-only transitive deps (e.g. `com.gyf.immersionbar`), so the
> Aliyun public Maven mirror is added in `settings.gradle.kts` to serve them.

## Install & run on a device

```bash
export ANDROID_HOME="$HOME/Android/Sdk"
"$ANDROID_HOME/platform-tools/adb" install -r app/build/outputs/apk/debug/app-debug.apk
```

Then:
1. Connect the powered hub + both cameras via OTG.
2. Launch **Camera Eyes** (or accept the "open with" prompt Android shows when a camera is attached).
3. Grant the USB permission dialog for **each** camera (two prompts, shown one at a time).
4. Both feeds should appear side-by-side.

## Tuning / troubleshooting

- **Second camera errors or won't open:** almost always USB 2.0 bandwidth. Lower the resolution in
  [`SideBySideCameraFragment.kt`](app/src/main/java/com/adiglobal/cameraeyes/SideBySideCameraFragment.kt)
  — change `PREVIEW_WIDTH` / `PREVIEW_HEIGHT` (e.g. to `640` / `480`) and rebuild. MJPEG is the
  UVC strategy's default in 3.2.7, which is required for two streams to share the bus.
- **No "open" prompt on attach:** confirm the device actually supports USB host mode and the hub is
  externally powered.

## Project layout

- [`app/src/main/java/.../MainActivity.kt`](app/src/main/java/com/adiglobal/cameraeyes/MainActivity.kt) — hosts the fragment.
- [`app/src/main/java/.../SideBySideCameraFragment.kt`](app/src/main/java/com/adiglobal/cameraeyes/SideBySideCameraFragment.kt) — all camera logic (slotting, permissions, status).
- [`app/src/main/res/layout/fragment_side_by_side.xml`](app/src/main/res/layout/fragment_side_by_side.xml) — two 50/50 `AspectRatioTextureView`s with status overlays.
- [`app/src/main/res/xml/device_filter.xml`](app/src/main/res/xml/device_filter.xml) — USB device filter (`3141:25446` = `0c45:6366`).
