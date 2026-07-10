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
  (default, letterbox), H-fit (fill width), V-fit (fill height), Stretch (legacy fill). The scale is
  computed generically (`axisScale`) as the on-pane lengths of the video's width/height axes, so it
  composes cleanly with rotation.
- Orientation: rotation (0/90/180/270, cycled button) and a horizontal-flip toggle, both persisted,
  applied in the same `setTransform` matrix (`orientMatrix`: `setScale` then `postRotate` about the
  pane center; flip negates the x scale). Rotation×flip covers all 8 orientations, so the correct
  one is dialed in on-device rather than hard-coded. Note: the raw frames from these cameras are
  already upright and correctly-handed — the default is rotation 0 / flip off (an earlier assumed
  SurfaceTexture V-inversion / UVC mirror did NOT exist; the neutral transform is correct).
- Settings UI: a transparent **⚙ Settings full-width bar spans the top of the panel** (over
  passthrough, above/separate from the video frame). Tapping it toggles a transparent **vertical
  column of controls centered over the video** (`settingsScroll`/`settingsList`) holding rotation,
  flip, aspect, follow, smoothing, passthrough, curve/scale/gap sliders, swap, and a Debug button.
  The gap slider sizes a transparent spacer (`gapSpacer`, weight-based: 100% = half the row) between
  the two panes; each pane is black but the row is transparent, so passthrough shows in the gap. The
  column is hidden by default; only the bar shows until opened. Backgrounds are ~35% scrim
  (`settings_item_bg`, grayscale hover/focus/press states — no color) with text drop-shadows so
  passthrough/video shows behind. A full-screen transparent `settingsScrim` sits behind the column
  while open so tapping anywhere outside it closes the menu. The panel (`ImmersiveActivity`) is
  grown taller than the video (1920x1160 px / 2.4x1.45 m vs the 1920x960 video region) so the top
  bar sits over passthrough rather than shrinking the video.
- Panel transparency: passthrough-through-the-panel requires ALL of: `enableTransparent = true` in
  the `PanelRegistration.config` block (without it the panel composites opaque and every
  transparent pixel renders black), a transparent window (`Theme.AstralProjector` sets
  `windowBackground` transparent + `windowIsTranslucent`), and no opaque view backgrounds in the
  hierarchy (`activity_main.xml` container and the fragment root/`videoRow` have none; only the
  camera panes themselves are black).
- Passthrough toggle: persists `SpatialControls.KEY_PASSTHROUGH` (default on); `ImmersiveActivity`
  applies it on scene-ready and live via its `prefListener` calling `scene.enablePassthrough(...)`,
  turning the mixed-reality background on/off without a relaunch.
- Controller input: `MainActivity.dispatchKeyEvent`/`onGenericMotionEvent` forward to the fragment
  (`handleControllerKey`/`handleControllerMotion`). MENU or Y opens the column (and focuses the
  first item) or hides it if already open; while open and focused, D-pad/stick up-down step through
  the column (`moveFocusStep`, geometry-independent), left-right nudge a focused slider (committing
  directly, since programmatic `setProgress` doesn't fire `fromUser`), A/DPAD-center clicks, and
  B/BACK hides the column. Navigation keys are only intercepted while the controls hold focus, so
  pointer/hand-ray use is unaffected. Pointer taps remain the guaranteed path; controller support is
  untested on hardware.
- Debug aids: `FileLogger` (logcat + app-external-files file + in-memory buffer feeding an on-screen
  **Debug** overlay, opened by the "Debug" button in the settings menu, hidden by default). The
  Debug overlay's bottom control row holds the build timestamp, the **retry-permission** button, and
  a close button (kept at the bottom so the full-width settings bar at the top can't overlap them).
  Also per-slot status overlays.
- Swap toggle: corrects which physical camera renders left vs right (hub-port-dependent, see Field
  setup) without recabling. Persisted in SharedPreferences. Implemented as an indirection
  (`displayIndexFor`) from logical connection slot -> display pane, **not** by reparenting the
  pane `View`s — reparenting a `TextureView` (even just moving its container within a still-attached
  parent) detaches it from the window, which destroys its `SurfaceTexture`; AUSBC never rebinds to
  the new one, so the pane goes blank until the app is restarted. Toggling instead calls
  `camera.closeCamera()` then re-`openCamera()`s onto the other (already-attached) `TextureView`
  after a short delay, mirroring the same close/open lifecycle already used for physical
  detach/reconnect.

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
  `adb shell pm grant com.compuglobal.astralprojector android.permission.CAMERA` and same for
  `horizonos.permission.USB_CAMERA`.
- Note: logs showed the negotiated preview at 640x480@MJPEG despite requesting 1280x720 — resolution
  negotiation may need follow-up if 720p matters.
- On-screen log overlay was added to diagnose when adb wasn't available (a USB thumb drive is NOT an
  `getExternalFilesDirs` volume; only an SD card is).
