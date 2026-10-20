# Tappy

A Brick-style app blocker for Android. Pick the apps that distract you, tap your NFC tag, and
they're locked. Tap the same tag again to get them back. While a block is active, Tappy can't
be uninstalled or switched off.

## How it works

| Piece | What it does |
| --- | --- |
| **NFC tag** | Paired by its UID. On pairing Tappy also writes a `tappy://toggle` record + Android Application Record to the tag (if writable) so a tap reaches Tappy from the home screen or any other app (the phone must be unlocked). |
| **Accessibility service** (`BlockerService`) | Watches the foreground window. When a blocked app comes to the front it sends you home and shows a "blocked" screen. |
| **Device admin** (`TappyAdminReceiver`) | An active device admin can't be uninstalled until it's deactivated. |
| **Self-protection** | While blocking, any Settings / package-installer screen that mentions Tappy (app info, uninstall dialog, accessibility toggle, device-admin deactivation) is bounced back to home. So you can't turn off the service, deactivate the admin, force-stop or uninstall Tappy until you tap your tag. |
| **Lock now** | Once a tag is paired, a button on the main screen starts a block without the tag (after a confirmation), for when you forgot to tap before leaving. Ending the block still needs the tag (or auto-unlock / an emergency unlock). |
| **Auto-unlock** | Every block ends by itself at a daily time (10 PM by default). The time is set in the app and can only be changed while not blocking. The deadline is fixed when the block starts: a block started at 11 PM runs until 10 PM the next day. The block ends the first time you use the phone after that time (plus a backstop alarm). Moving the phone's clock forward doesn't end it early. |
| **Emergency unlock** | 3 one-time unlocks in case you lose the tag. They never recharge. |
| **Never-blocked apps** | The phone app, clock/alarm apps, Settings and the home screen can't be selected, so calls and alarms always come through. |

A block won't start until the blocker service and uninstall protection are both on.

## Install

- **GitHub:** download the latest `tappy-vX.Y.Z.apk` from
  [Releases](https://github.com/Kjblootens/Tappy/releases) and open it on your phone.
- **F-Droid:** coming soon.

The two are signed with different keys, so pick one and stick with it.

## Build from source

Requires Android 8.0+ (API 26) and a phone with NFC.

```sh
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

Every push also builds the APK in GitHub Actions (`Build APK` workflow); download it from the
run's artifacts.

## Setup on the phone

1. Open Tappy, turn on NFC if needed.
2. **Pair** a tag and hold it to the back of the phone. Any NTAG213/215/216 (or MIFARE Ultralight)
   sticker or keyfob works. Bank and travel cards are refused, so tapping one by accident
   during pairing does nothing.
3. **App blocker service** → *Open* → Accessibility → Tappy → on.
   On Android 13+ a sideloaded app's accessibility toggle is greyed out at first: go to
   *Settings → Apps → Tappy → ⋮ → Allow restricted settings*, then try again.
4. **Uninstall protection** → *Enable* → Activate.
5. Tick the apps to block, then tap your tag.

## Optional: device-owner mode (strongest)

As a regular device admin Tappy relies on the accessibility service to guard its own settings.
If you make Tappy the **device owner**, it additionally:

- hard-blocks its own uninstall (`setUninstallBlocked`),
- suspends the blocked apps at the OS level (greyed out, can't be opened at all),
- disables safe-mode boot during a block (safe mode would otherwise disable Tappy).

Android only allows setting a device owner when **no accounts** are on the device, so you'll
need to temporarily remove your Google (and other) accounts under *Settings → Accounts*, then:

```sh
adb shell dpm set-device-owner io.github.lotusz.tappy/.TappyAdminReceiver
```

Re-add your accounts afterwards. To undo it, use **Remove owner** in Tappy (only available
while not blocking).

## If you get stuck

The app has the same list under **If you get stuck**. Easiest first:

1. Tap your tag. If tapping from another app doesn't work, open Tappy and tap it there.
2. Wait for the auto-unlock time, if it's on.
3. Use an emergency unlock (3 in total).
4. Normal mode only: reboot into safe mode, deactivate Tappy's device admin and uninstall it.
5. From a computer with USB debugging on:
   `adb shell settings put secure enabled_accessibility_services ""`, then deactivate the admin and uninstall.

In device-owner mode only 1–3 work. The last resort is a factory reset.

## Limits worth knowing

- Without device-owner mode, booting into **safe mode** disables third-party accessibility
  services, so the app block is off there; the device admin still prevents uninstalling, but
  it can be deactivated in safe mode. A factory reset always wins.
- The settings guard matches Tappy's name on screen. OEM settings apps with very different
  package names may slip through; the device admin still stops an uninstall outright.
- The auto-unlock clock check holds within one boot. After a reboot it falls back to the
  phone's clock, so rebooting *and* moving the clock forward would end a block early.
- Tags are matched by UID, which is unique per tag but not cryptographically secure. It's a
  self-control tool, not a security product.

## Privacy

No internet permission, ads, analytics or accounts. See [PRIVACY.md](PRIVACY.md).

## License

[GPL-3.0](LICENSE). You're free to use, study, change and share Tappy; copies and modified
versions must stay open source under the same licence.
