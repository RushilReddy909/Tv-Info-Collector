# TV Info Collector

A read-only app for an Android TV whose power button cold-boots instead of going to standby.
It collects the facts needed to identify the right firmware, or to find a hidden OEM standby
setting, and writes them to a text report.

- Needs no permissions and has no network access. Everything stays on the TV and the USB drive.
- Changes nothing on the TV. It only reads.
- Redacts serial numbers, MAC-style addresses, device names and tokens. Read the report before sharing it anyway.

## What the report contains

Build fingerprint, board / hardware / chipset properties, all system properties (with the
standby/boot/power ones listed first), kernel and partition info, deep-sleep time since boot
(stays at 0 if the TV never truly suspends), Settings values, the list of system packages
(including which partition they live on), and exported settings/power/factory-like screens.
Nothing is launched.

## Building the APK

This project was written without access to the Android SDK, so it has not been compiled yet.
If the first build reports an error, send the error text and it will be fixed.

### Option A: GitHub Actions (no SDK needed on your laptop)

1. Create a new private GitHub repository and upload everything in this folder
   (keep the `.github/workflows/build.yml` path).
2. Open the repository's **Actions** tab, pick **Build APK**, and run it (or just push).
3. When it finishes, open the run and download the **tv-info-collector-apk** artifact.
   Unzip it to get `app-debug.apk`.

### Option B: Android Studio

1. Open this folder in Android Studio and let it sync (it will offer to install SDK 34).
2. Menu **Build > Build APK(s)**. The file is `app/build/outputs/apk/debug/app-debug.apk`.

The debug APK is signed automatically and installs fine for this purpose.

## Installing it on the TV

1. Copy `app-debug.apk` onto a USB drive (FAT32 or exFAT).
2. On the TV, install a file manager that works with a remote from the Play Store
   (for example X-plore or File Commander).
3. Allow that file manager to install unknown apps. The setting is usually under
   Settings > Device Preferences > Security & restrictions > Unknown sources.
4. Plug in the USB drive, open the file manager, select the APK and install it.
5. Open **TV Info Collector** from the Apps row, or from Settings > Device Preferences > Apps.

## Using it

The report appears on screen. UP/DOWN scroll, OK pages down, BACK exits. The screen also lists the exact
file paths it saved to, one per storage volume (internal storage and, if the TV mounts it,
the USB drive). A drive that mounts as portable storage holds the file under
`Android/data/com.tvinfo.collector/files/`. If nothing lands on the drive, copy the file
from the TV's internal storage with the file manager, or photograph the screen.
