# Camera Eyes

Android app that displays video from two USB (UVC) webcams in a single side-by-side view.
Target hardware: Android devices with USB host/OTG, including Meta Quest 2.

## Cameras (lsusb on linux)

Two identical units — same VID:PID, distinguishable only by USB port path:

```
Bus 001 Device 051: ID 0c45:6366 Microdia Webcam Vitade AF
Bus 001 Device 052: ID 0c45:6366 Microdia Webcam Vitade AF
```

Decimal for the manifest device filter: vendor-id 3141, product-id 25446.

## Architecture

- Camera2/CameraX **cannot** drive two external USB cameras (OEM/HAL-dependent, single-camera).
  Instead we use [AndroidUSBCamera (AUSBC)](https://github.com/jiangdongguo/AndroidUSBCamera)
  `libausbc`, which talks UVC over `UsbManager` (libuvc/libusb prebuilt in the AAR). Despite driving
  cameras over raw USB, the runtime `CAMERA` permission IS required: since Android 10,
  `UsbUserPermissionManager` silently denies USB access to any device with video capture unless the
  app holds CAMERA (logcat: "USB Camera permission required for USB video class devices"). On Meta
  Horizon OS (Quest), the additional runtime permission `horizonos.permission.USB_CAMERA` is required
  as well — both are declared in the manifest and requested by `MainActivity` before the fragment
  attaches.
- `MainActivity` (landscape) hosts `SideBySideCameraFragment`, which extends AUSBC's
  `MultiCameraFragment`: attach → auto USB-permission request → connect → `openCamera()` onto one of
  two 50/50 `AspectRatioTextureView` slots (left/right filled in connection order).
- 720p per camera; MJPEG is the UVC default in this AUSBC version (required — two uncompressed
  streams exceed USB 2.0 bandwidth). If the second camera errors, lower
  `PREVIEW_WIDTH`/`PREVIEW_HEIGHT` in the fragment.
- Aspect modes: AUSBC 3.2.7 never calls `setAspectRatio` on the view in the `openCamera` path
  (verified in the AAR bytecode) — it renders each frame stretched to fill the whole surface, hence
  distortion. The fragment corrects at display time via `TextureView.setTransform` scaled around the
  pane center, using the negotiated `camera.getPreviewSize()` (often 640x480, not the requested
  720p). Four modes cycled by an on-screen button, persisted in SharedPreferences: Full frame
  (default, letterbox), H-fit (fill width), V-fit (fill height), Stretch (legacy fill).
- Debug aids: `FileLogger` (logcat + app-external-files file + in-memory buffer feeding an on-screen
  log overlay with show/hide toggle, hidden by default), per-slot status overlays, and a "retry
  permission" button.

## Critical dependency pins (do not "upgrade" blindly)

- `com.github.jiangdongguo.AndroidUSBCamera:libausbc:3.2.7` via JitPack.
  **All 3.3.x tags fail their native build on JitPack** (libnative-3.3.x.aar 404s). Also note the
  3.3.x API (`MultiCameraClient.ICamera`, `generateCamera`, `CameraUVC`, `setPreviewFormat`) differs
  from 3.2.7 (`MultiCameraClient.Camera`, no format setter) — code here targets 3.2.7.
- Aliyun public Maven mirror in `settings.gradle.kts`: AUSBC pulls dead jcenter-only transitive deps
  (e.g. `com.gyf.immersionbar:immersionbar:3.0.0`) that only that mirror still hosts.

## Build (local rootless toolchain — no system JDK/SDK/Gradle)

```bash
export JAVA_HOME="$HOME/Android/jdk-17.0.19+10"
export ANDROID_HOME="$HOME/Android/Sdk"
./gradlew assembleDebug   # → app/build/outputs/apk/debug/app-debug.apk
```

`local.properties` points at `~/Android/Sdk`. minSdk 24 / target+compileSdk 34, Kotlin, viewBinding.
abiFilters arm64-v8a + armeabi-v7a — no x86, and the emulator has no USB host anyway; hardware
testing requires a real device (no ADB access to it; use the on-screen log overlay for feedback).

## Field setup

Powered USB hub (mandatory — one OTG port can't power two cameras) → OTG cable → device. Grant one
USB permission dialog per camera. Left/right slot depends on hub port, not on which physical unit.

## Status / known issues

- Build verified locally; APK is sideloaded manually (or `adb install -r` when the Quest is reachable
  over `adb connect <ip>:5555`).
- RESOLVED (2026-07-09): panes stuck at "Connecting…" was the missing `CAMERA` +
  `horizonos.permission.USB_CAMERA` runtime permissions (see Architecture) — the framework silently
  denied every USB permission request, so `onCameraConnected` never fired. With both granted, both
  cameras open and stream MJPEG simultaneously on Quest 2.
- Permissions can be pre-granted over adb, bypassing the in-headset dialogs:
  `adb shell pm grant com.adiglobal.cameraeyes android.permission.CAMERA` and same for
  `horizonos.permission.USB_CAMERA`.
- Note: logs showed the negotiated preview at 640x480@MJPEG despite requesting 1280x720 — resolution
  negotiation may need follow-up if 720p matters.
- On-screen log overlay was added to diagnose when adb wasn't available (a USB thumb drive is NOT an
  `getExternalFilesDirs` volume; only an SD card is).
