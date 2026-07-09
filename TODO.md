## Head tracking (research findings)

A 2D panel app **cannot** be head-locked on Horizon OS — no manifest flag or setting exists; panels are world-anchored and only the user controls presentation (theater mode, v81 window pinning are user-invoked). To follow the head you'd go immersive, and the good news is the **Meta Spatial SDK** is a medium-effort retrofit, not a rewrite:

- It's Kotlin/Android-native; you host your existing camera layout as a panel inside an immersive scene (Meta's `HybridSample` demonstrates exactly this 2D↔immersive switch).
- Head-locking is ~20 lines: an ECS system reads the head pose (`AvatarAttachment` type "head") and sets the panel transform each frame. Meta recommends smoothed "lazy follow" over hard lock for comfort.
- **Quest 2 is supported** (`supportedDevices "quest2|..."`, Horizon OS v69+), and USB/UVC keeps working — a Spatial SDK app is still a normal APK, and Meta's own Ocean `ExternalCamera` demo and HDMI Link do UVC in immersive Quest apps with the same CAMERA-permission requirement you already handle.
- A full 360 sphere buys nothing with two fixed forward-facing webcams — they only cover their own FOV. A curved cylinder panel for comfort is the sensible shape. Caveat: 640x480 MJPEG magnified across a big FOV will look soft, so the resolution-negotiation follow-up becomes more relevant.

## Native passthrough underlay (research findings)

**Yes, but only from an immersive app** — which pairs perfectly with the head-tracking work:

- In the Spatial SDK it's essentially one line: `scene.enablePassthrough(true)` (plus hiding the skybox). Works on Quest 2, in **grayscale** (color needs Quest 3/3S). No extra permission — the compositor renders passthrough behind your panels; your app never touches the pixels.
- The current 2D app can't request it. Workaround today: the *user* can enable Passthrough Home and pin the app window over it (v81+), but the app can't force or detect that.
- The raw-frames Passthrough Camera Access API is **Quest 3/3S-only** (v74+, `horizonos.permission.HEADSET_CAMERA`) — not needed for an underlay anyway.

**Recommended path if you want both features:** one Spatial SDK retrofit keeps all the AUSBC/USB/permission code, adds a head-following (lazy-follow) panel, and gets a grayscale passthrough underlay on Quest 2 nearly for free. Happy to start that migration when you're ready.