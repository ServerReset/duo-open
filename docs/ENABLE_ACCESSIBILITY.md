# Enabling the full-screen fold (Android "restricted settings")

Duo Open Live uses an **Accessibility service** to draw the fold over the whole
screen. Since **Android 13**, apps installed outside the Play Store (like this
APK) can't be granted Accessibility until you explicitly allow **restricted
settings** for the app. Samsung also calls this "Power Saving"-era security,
and newer Samsung phones add **Auto Blocker**, which blocks the flow entirely
until it's turned off.

The app can't grant this itself — it's a deliberate Android protection. This
guide gets you through it.

> **The step almost every guide misses:** the **"Allow restricted settings"**
> option is *hidden* until you try to turn the Accessibility service on once
> and Android refuses you. Do step 1 first, then step 2.

---

## Quick version (stock Android, Pixel, Motorola)

1. **Trigger the refusal.** Open **Settings → Accessibility → Installed apps
   (or "Downloaded apps")**, find **Duo Open Live full-screen fold**, and tap
   the toggle. Android shows *"Restricted setting — for your security, this
   setting is currently unavailable."* Dismiss that dialog. *(This is the
   important bit.)*
2. **Allow it.** Open **Settings → Apps → Duo Open Live → ⋮ (top-right) →
   Allow restricted settings**, then confirm with your PIN / pattern /
   fingerprint.
3. **Turn it on.** Go back to **Settings → Accessibility → Installed apps** and
   enable **Duo Open Live full-screen fold**. Tap **Allow** when asked.

In the app you can tap **Tune → Setup guide**, which deep-links to both screens
and tracks whether it worked.

---

## Samsung (One UI)

### One UI 5.x (Android 13)

1. Trigger: **Settings → Accessibility → Installed apps**, try to toggle
   *Duo Open Live full-screen fold* on, dismiss the refusal.
2. **Settings → Apps → Duo Open Live → ⋮ (top-right) → Allow restricted
   settings**, confirm.
3. Back to **Accessibility**, enable it.

### One UI 6.x (Android 14)

On many builds there is **no ⋮ menu** on the app info page. Instead the option
is a plain item: scroll down on the **Duo Open Live** app info page until you
find **Allow restricted settings**.

### One UI 6.1.1+ / 7.x (Auto Blocker)

Newer Galaxy phones ship **Auto Blocker**, which blocks sideloaded apps
outright. Turn it off first:

1. **Settings → Security and privacy → Auto Blocker → Off.**
2. Then follow either One UI section above.

If the toggle still stays greyed after "Allow restricted settings", reboot and
repeat steps 2–3.

---

## Other brands

| Brand | Where "Allow restricted settings" lives |
| --- | --- |
| **Pixel / Motorola** (stock) | App info → ⋮ → Allow restricted settings |
| **Samsung** | App info → ⋮ (One UI 5), or a visible item lower down (One UI 6+) |
| **Xiaomi** (MIUI/HyperOS) | Settings → Apps → Manage apps → Duo Open Live → ⋮ |
| **Oppo** (ColorOS) | Settings → Apps → App management → Duo Open Live → ⋮ or a visible item |
| **OnePlus** (OxygenOS 13/14) | Settings → Apps → App info → Duo Open Live → ⋮ |
| **OnePlus** (OxygenOS 15) | ⋮ menu removed — use the ADB or SAI route below |

---

## If the option never appears

### Option A — install with a "session" installer

Install the APK with an app like **SAI (Split APK Installer)** from the Play
Store. Session-based installs are treated as store installs, so the restriction
never applies and you can enable Accessibility immediately.

### Option B — ADB (needs a computer)

If you have Android platform-tools and USB debugging enabled:

```bash
adb shell appops set com.duoopen.live ACCESS_RESTRICTED_SETTINGS allow
```

Then enable the service under **Settings → Accessibility**. (On the phone,
`ACCESS_RESTRICTED_SETTINGS` is the app-op that the "Allow restricted settings"
switch flips. Verified working on Android 14.)

### Option C — reinstall from the file manager

If **Duo Open Live** doesn't even show up under Accessibility → Installed apps,
uninstall it and reinstall by **tapping the APK in your file manager** (My
Files, Files, etc.). Installing over ADB or through some browsers can leave the
system unsure whether the app was sideloaded, which hides both the app and the
restricted-settings option.

---

## Why "App not installed" / two apps?

This fork uses the app id **`com.duoopen.live`** and its own signing key, so it
installs **next to** the original Duo Open (`com.duoopen`) rather than
conflicting with it. Both can be installed; you only need to enable
Accessibility for the one you want to use (**Duo Open Live**).

---

## After it's enabled

- **Tune → Test it now** replays the effect without folding.
- If the effect stops after a reboot, some OEMs disable sideloaded
  Accessibility services on update — just re-enable it.
- Battery Saver stands the full-screen fold down on purpose; the **Reduce in
  Battery Saver** switch in Tune controls that.
