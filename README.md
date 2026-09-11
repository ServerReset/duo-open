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
focus. Both panels take part: the cover screen frosts in over the first ~20°
of an open, then the inner screen picks up frosted and clears. Closing plays
it in reverse.

It works over *everything* — your own wallpaper, icons, widgets, the lock
screen, whatever app is open — because it runs as an accessibility service
that takes one screenshot per fold phase and draws it through the shader in a
touch-transparent overlay tracking the hinge. There's also a plain live
wallpaper mode if you'd rather not enable an accessibility service.

## Install

1. Download `DuoOpen-<version>.apk` from
   [Releases](../../releases) and install it.
2. Open **Duo Open** → **Tune** → **Turn on in Accessibility** → enable
   *Duo Open full-screen fold*.
   - Android 13+ blocks accessibility for sideloaded apps until you allow
     it: if the toggle is greyed out, go to *Settings → Apps → Duo Open → ⋮
     (top right) → Allow restricted settings*, then try again.
3. Fold the phone partway and open it. **Tune → Test it now** replays the
   effect without folding.

The **Tune** sheet has strength, frost, darkening, eye distance, which half
moves (left/right/both), which edge the cover-screen frost comes from, and
a hinge simulator.

Wallpaper-only mode: **Set live wallpaper** in the app (home + lock screen).
Only the wallpaper folds in that mode; icons stay sharp.

## Privacy

The accessibility service takes a screenshot of the display each time a fold
phase starts and keeps it in memory only while the overlay is on screen.
Nothing is stored, logged or sent anywhere; the app has no network
permission. Screens the system marks secure (banking apps, DRM video) can't
be captured and the effect simply doesn't play there.

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
`adb shell settings put secure enabled_accessibility_services com.duoopen/com.duoopen.overlay.FoldOverlayService`,
replay the effect with `adb shell am broadcast -a com.duoopen.DEMO`,
watch it with `adb logcat -s DuoOverlay`.

## Layout

```
app/src/main/res/raw/duo_unfold.agsl      fold shader (hinge line, moving side, eye)
fold/DuoShader.kt                         uniforms, hinge→tilt mapping, fold placement
fold/HingeAngleSource.kt                  TYPE_HINGE_ANGLE discovery + vendor fallback
fold/AngleFilter.kt                       adaptive 1€ filter: live, jitter-free angle
fold/TiltFollower.kt                      per-vsync ease that hides the sensor's steps
fold/Panels.kt                            inner vs cover panel from the display mode
overlay/FoldOverlayService.kt             accessibility service: screenshot + overlay
overlay/FoldOverlayView.kt                draws the snapshot through the shader (half-res layer)
wallpaper/DuoWallpaperService.kt          live wallpaper engine
wallpaper/WallpaperImage.kt               picked image / generated default
ui/                                       Compose app: preview, Tune sheet
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
  (usually alongside a wake-up variant). The app lists every hinge sensor it
  finds on the **Tune** sheet and prefers the continuous one.
- Its readings arrive in coarse steps with a little noise, so the raw value is
  run through an adaptive 1€ filter (`fold/AngleFilter.kt`): heavy smoothing at
  rest, light while the hinge moves. The **Tune** readout shows one decimal, so
  live motion is visible instead of jumping whole degrees.

## License

MIT — see [LICENSE](LICENSE). The shader is adapted from
[Atomicx7/Duo-animation](https://github.com/Atomicx7/Duo-animation).
