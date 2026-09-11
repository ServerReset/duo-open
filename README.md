# Duo Open

The iPhone "Duo" frosted-glass fold, playing system-wide on a book-style
foldable as you open and close it. Driven by the real hinge angle — no root.
Built and tested on the OnePlus Open; Pixel Fold, Galaxy Z Fold, OPPO Find N
and friends use the same platform `TYPE_HINGE_ANGLE` sensor and work too, with
a vendor-tolerant sensor picker and an adaptive filter that turns the Galaxy Z
Fold's stepped, slightly noisy readings into a smooth live curve.

Based on the AGSL shader from
[Atomicx7/Duo-animation](https://github.com/Atomicx7/Duo-animation).

## What it does

Each half of the screen acts as a pane of frosted glass hinged at the crease.
While the phone is partly folded the moving half is blurred and darkened by
how far it is from flat; as the hinge reaches 180° the picture settles into
focus. This is the original Duo look, kept as-is because it reads well on
hardware. Both panels take part: the cover screen frosts in over the first
~20° of an open, then the inner screen picks up frosted and clears.

The hinge is read at full rate and passed straight through — no smoothing or
lag — and a slow keep-alive re-registers the sensor if it goes quiet, so the
effect tracks the hinge in real time on hinges that report once and then stay
silent instead of only updating when the app is reopened.

It works over *everything* — your own wallpaper, icons, widgets, the lock
screen, whatever app is open — because it runs as an accessibility service
that takes one screenshot per fold phase and draws it through the shader in a
touch-transparent overlay tracking the hinge. There's also a plain live
wallpaper mode if you'd rather not enable an accessibility service.

## Install

1. Download `DuoOpen-<version>-release.apk` from
   [Releases](../../releases) and install it. (The Actions artifact is a zip;
   use the Releases page for a directly installable `.apk`.)
   - This fork uses the app id `com.duoopen.live` and its own signing key, so
     it installs **alongside** the original Duo Open instead of failing with
     "App not installed". Uninstalling the original is optional.
2. Open **Duo Open Live** → **Tune** → tap **Setup guide** (or **Turn on in
   Accessibility**) and follow it.
   - Android 13+ blocks Accessibility for sideloaded apps ("restricted
     settings"). The **Allow restricted settings** option stays **hidden until
     you first try to toggle the service on and Android refuses you** — then go
     to *Settings → Apps → Duo Open Live → ⋮ → Allow restricted settings* and
     try again.
   - Samsung: on One UI 6+ it may be a plain item lower on the app info page;
     on One UI 6.1.1+ turn off *Settings → Security and privacy → Auto Blocker*
     first.
   - Full per-brand walkthrough, plus an ADB shortcut:
     [docs/ENABLE_ACCESSIBILITY.md](docs/ENABLE_ACCESSIBILITY.md).
3. Fold the phone partway and open it. **Tune → Test it now** replays the
   effect without folding.

The **Tune** sheet has strength, frost, darkening, eye distance, which
half moves (left/right/both) and which edge the cover-screen frost comes from.
The **main screen** has the hinge simulator (switch + slider) so you can play
the effect in real time, plus the live hinge-sensor readout.

Wallpaper-only mode: **Set live wallpaper** in the app (home + lock screen).
Only the wallpaper folds in that mode; icons stay sharp.

## Privacy

The accessibility service takes a screenshot of the display each time a fold
phase starts and keeps it in memory only while the overlay is on screen.
Nothing is stored, logged or sent anywhere; the app has no network
permission. Screens the system marks secure (banking apps, DRM video) can't
be captured and the effect simply doesn't play there.

## Battery

The effect is built to cost nothing when the phone isn't folding:

- The hinge sensor is on-change at ~50 Hz, so it's silent at rest. The in-app
  preview stops listening when it isn't in front; the accessibility service and
  the live wallpaper both stop the sensor while the screen is off.
- The overlay only renders while a fold is actually happening and idles the
  moment it settles. With the default clear look it's a single texture sample
  per pixel at native resolution — no blur passes, no half-res upscale.

It also respects Android's Battery Saver (Samsung Power Saving). When the
system turns it on, the app stands the full-screen fold down, drops the sensor
to ~15 Hz, and eases the wallpaper with fewer frames. There's a **Reduce in
Battery Saver** switch in Tune (on by default); turn it off to keep the full
effect regardless of power state.

## Known limits

- Android allows one screenshot every ~333 ms, and a freshly-lit panel shows
  the system's own black-to-reveal for ~0.4 s first. On a fast flick the
  second phase (inner screen on open) may not have time to appear; you'll get
  the cover-screen phase only. Normal-speed folds get both.
- If you stop partway (tent mode) the overlay fades out after ~0.7 s so the
  live screen isn't hidden.
- Reinstalling the app turns the accessibility service off again.

## Build

```
./gradlew assembleDebug        # debug-signed
./gradlew assembleRelease      # signed with keystore.properties if present
```
Release signing reads `keystore.properties` in the project root
(`storeFile`, `storePassword`, `keyAlias`, `keyPassword`); without it the
release build uses the debug key.

Handy adb bits: enable the service with
`adb shell settings put secure enabled_accessibility_services com.duoopen.live/com.duoopen.overlay.FoldOverlayService`,
replay the effect with `adb shell am broadcast -a com.duoopen.DEMO`,
watch it with `adb logcat -s DuoOverlay` (sensor logs are under `DuoHinge`).

## Layout

```
app/src/main/res/raw/duo_unfold.agsl      fold shader (hinge, moving side, eye, blur)
fold/DuoShader.kt                         uniforms, hinge→tilt mapping, fold placement
fold/HingeAngleSource.kt                  TYPE_HINGE_ANGLE discovery + vendor fallback
fold/AngleFilter.kt                       adaptive 1€ filter: live, jitter-free angle
fold/TiltFollower.kt                      per-vsync ease that hides the sensor's steps
fold/Panels.kt                            inner vs cover panel from the display mode
overlay/FoldOverlayService.kt             accessibility service: screenshot + overlay
overlay/FoldOverlayView.kt                draws the snapshot through the shader (half-res layer)
wallpaper/DuoWallpaperService.kt          live wallpaper engine
wallpaper/WallpaperImage.kt               picked image / generated default
ui/                                       Compose app: preview, Tune sheet, setup guide
ui/AccessibilityGuide.kt                  restricted-settings walkthrough (deep links)
docs/ENABLE_ACCESSIBILITY.md              written per-brand enablement guide
settings/DuoSettings.kt                   shared tuning (SharedPreferences + StateFlow)
```

## OnePlus Open notes

- Inner panel 2268×2440, fold splits the short side; display 0 swaps between
  the cover (1116×2484) and inner panels at ~10–30° depending on speed.
- The hinge sensor is wake-up only and sends nothing on registration, goes
  quiet at ~30° during a close, and idles anywhere from 0–5° when shut. The
  service compensates for all three.

## Galaxy Z Fold notes

- Samsung exposes the hinge through the platform `TYPE_HINGE_ANGLE` sensor
  (usually alongside a wake-up variant). The app picks the continuous one
  first, then a wake-up/vendor one, and reads it at full rate. The **main
  screen** shows a live sensor line (`name · Hz · raw · age`) so you can see at
  a glance whether readings are arriving.
- Some hinges only emit a reading or two per fold rather than a stream. The
  effect measures how far apart readings arrive and eases across that gap, so
  it animates smoothly between them instead of jumping from one to the next.
- The sensor is left registered and untouched — re-registering it to "poll"
  resets some hinges' change detector and starves the feed.
- The main screen also has the **Simulate hinge** switch and slider, so you can
  play the effect and watch it in real time without folding (handy when the
  Accessibility service isn't enabled yet).

## License

MIT — see [LICENSE](LICENSE). The shader is adapted from
[Atomicx7/Duo-animation](https://github.com/Atomicx7/Duo-animation).
