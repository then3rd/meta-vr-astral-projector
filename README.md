# Astral Projector

An Android / Meta Quest app that displays two USB (UVC) webcams **side-by-side** on a floating
spatial panel, turning a pair of cheap webcams into a live stereo "window" you can view in VR with
passthrough around it.

Built for two identical *Microdia Webcam Vitade AF* units (`0c45:6366`), but works with any two
UVC cameras. Uses [AndroidUSBCamera (AUSBC)](https://github.com/jiangdongguo/AndroidUSBCamera)
`libausbc` to drive both cameras over USB host mode — **not** Camera2/CameraX, which can't reliably
open two external USB cameras at once.

## What it does

- **Dual USB camera view** — opens up to two UVC cameras and shows each in its own half of the
  screen (left / right), with a per-camera status overlay (*Waiting → Connecting → Opening → live*,
  plus *Disconnected* / *Camera error*). Handles plug/unplug and recovers on reattach.
- **Spatial panel with passthrough** — on Quest the video floats on a transparent panel with the
  mixed-reality passthrough view showing around and through it. Passthrough can be toggled on/off.
- **Stereo (binocular) mode** — routes the left pane to the left eye and the right pane to the right
  eye for true stereoscopic depth. Flat-quad only; dial in orientation and swap in mono first.
- **Orientation & framing controls** — per-view rotation (0/90/180/270) and horizontal flip, plus
  four aspect modes (Full frame, H-fit, V-fit, Stretch) to fix distortion or upside-down feeds
  without recabling.
- **Panel shaping** — curve, scale, and gap sliders bend the panel, resize it, and open a
  passthrough gap between the two eyes. (Curve is mono-only.)
- **Head-tracking follow** — an optional mode that keeps the panel in front of you as you look
  around, with adjustable smoothing.
- **Swap** — flips which physical camera renders left vs right (handy since both cameras share the
  same USB ID and left/right depends on which hub port each is in).
- **Recording** — one MP4 per open USB camera (H.264 + mic audio), plus an optional passthrough
  capture on Quest 3 / 3S. Files land in *This headset → Recordings*.
- **Settings menu** — a ⚙ Settings bar across the top opens a control grid over the video; all
  settings persist between runs. Reset returns everything to defaults.
- **Input** — works with hand-ray / pointer taps and (untested) VR controllers.

All settings persist across launches, except stereo mode — the app always boots in mono.

## Hardware requirements

- An Android device with **USB host / OTG** support (e.g. Meta Quest 2/3/3S).
- A **powered USB hub** (a single OTG port can't power two cameras — expect disconnects/green frames
  without external power). Plug both cameras into the hub, hub into the headset via an OTG cable.
- Both cameras share the same VID:PID, so which feed appears left vs right depends on **which hub
  port** each is plugged into. Use the **Swap** toggle (or fix the cabling) if a stable left/right
  matters.
- Stereo recording of passthrough requires a **Quest 3 / 3S** (v74+); on Quest 2 it is skipped.

## Build

All build and device tasks run through [`just`](https://github.com/casey/just) — see the
[`justfile`](justfile) for the full list (`just` on its own lists every recipe).

The Android toolchain is installed locally under `~/Android` (rootless) — JDK 17 at
`~/Android/jdk-17.0.19+10` and the Android SDK at `~/Android/Sdk` (also recorded in
`local.properties`). The recipes export `JAVA_HOME`/`ANDROID_HOME` for you, so no shell setup is
needed.

```bash
just install-deps   # one-time: fetch JDK 17 + Android SDK if missing
just verify-env     # sanity-check the toolchain
just build-debug    # → app/build/outputs/apk/debug/app-debug.apk
```

> First build downloads AGP, Kotlin, AndroidX, and the AUSBC AAR. The AUSBC dependency is pinned to
> **`libausbc:3.2.7`** — every `3.3.x` tag fails its native build on JitPack (`libnative-3.3.x.aar`
> 404s). AUSBC also pulls old jcenter-only transitive deps (e.g. `com.gyf.immersionbar`), so the
> Aliyun public Maven mirror is added in `settings.gradle.kts` to serve them.

## Install & run on a device

For a Quest reachable over Wi-Fi, first `just adb-tcp-connect <ip>`. Then the one-shot recipe builds,
installs, grants permissions, and launches:

```bash
just refresh        # build-debug + install-debug + grant-permissions + run-debug
```

Use `just refresh-clean` instead if an existing build was signed with a different debug keystore
(`INSTALL_FAILED_UPDATE_INCOMPATIBLE`) — it uninstalls first. Individual steps
(`build-debug`, `install-debug`, `grant-permissions`, `run-debug`, `devices`) are also available.

Then:
1. Connect the powered hub + both cameras via OTG.
2. The app launches (or accept the "open with" prompt Android shows when a camera is attached).
3. `just grant-permissions` pre-grants everything (camera / USB camera, plus audio and storage for
   recording) — required, since the Horizon OS and storage permissions can't be granted via the
   in-headset runtime dialog. Re-run it after every fresh install.
4. Both feeds should appear side-by-side on the panel.

## Tuning / troubleshooting

- **Feeds look distorted or upside-down:** open ⚙ Settings and use the rotation, flip, and aspect
  controls. Use **Swap** if left/right are reversed.
- **Second camera errors or won't open:** almost always USB 2.0 bandwidth. Lower the resolution in
  [`SideBySideCameraFragment.kt`](app/src/main/java/com/compuglobal/astralprojector/SideBySideCameraFragment.kt)
  — change `PREVIEW_WIDTH` / `PREVIEW_HEIGHT` (e.g. to `640` / `480`) and rebuild. MJPEG is the
  UVC strategy's default in 3.2.7, which is required for two streams to share the bus.
- **No "open" prompt on attach:** confirm the device actually supports USB host mode and the hub is
  externally powered.
- **Recording produces no files:** the storage and audio permissions must be granted
  (`just grant-permissions`); recording is disabled until they are. Pull captures off the device
  with `just pull-recordings`.
- **Debugging:** `just logs` streams the app log live, `just logs-pull` grabs the on-disk log file,
  and `just screencap` captures the headset's mirror view.
- **Panel renders black instead of passthrough:** passthrough may be toggled off, or the device
  doesn't support it — check the passthrough toggle in Settings.

## Project layout

- [`app/src/main/java/.../MainActivity.kt`](app/src/main/java/com/compuglobal/astralprojector/MainActivity.kt) — hosts the fragment; forwards controller input.
- [`app/src/main/java/.../ImmersiveActivity.kt`](app/src/main/java/com/compuglobal/astralprojector/ImmersiveActivity.kt) — the spatial panel, passthrough, stereo, and panel shaping.
- [`app/src/main/java/.../SideBySideCameraFragment.kt`](app/src/main/java/com/compuglobal/astralprojector/SideBySideCameraFragment.kt) — all camera logic (slotting, permissions, orientation, recording, settings UI).
- [`app/src/main/res/layout/fragment_side_by_side.xml`](app/src/main/res/layout/fragment_side_by_side.xml) — the two 50/50 camera panes, status overlays, and settings controls.
- [`app/src/main/res/xml/device_filter.xml`](app/src/main/res/xml/device_filter.xml) — USB device filter (`3141:25446` = `0c45:6366`).

For architecture, dependency pins, and implementation detail, see [`CLAUDE.md`](CLAUDE.md).
