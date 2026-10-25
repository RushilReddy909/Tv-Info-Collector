# Motorola / Skyworth Android TV (SWTV) Standby Issue - Full Context Summary

## 1. Problem Overview
* **Issue**: Pressing the power button on the TV remote causes an immediate, hard shutdown (cold poweroff) instead of entering low-power standby / sleep mode. Turning the TV back on requires a full Android cold boot with the startup animation every time.
* **Origin**: Started immediately after a factory reset. The TV originally shipped with Android 8 and Motorola's customized overlay/UI where standby worked normally. Following the reset, it booted into a generic Skyworth factory Android 9 build with no Power menu or Quick Start setting in `Settings > Device Preferences`.
* **Prior Attempts**: Network ADB (port 5555) failed to connect. Android 9 build has no Wireless Debugging menu.

---

## 2. Verified Hardware & Firmware Identity (from Diagnostic Report)

The diagnostic tool (`com.tvinfo.collector`) was compiled and run directly on the TV, revealing the exact internal specifications:

* **Brand / Marketing Name**: Motorola Android TV (Flipkart licensed, manufactured by Skyworth)
* **Exact TV Model / PID**: `9K21T-43Q21-F` (Motorola 43-inch 4K UHD, Chassis `9K21T` / `43Q21`)
* **Customer Profile**: `IND-FLIPKART-T4E4E`
* **Panel Version**: `7618-43A30L-K020` (43-inch 4K UHD, 60Hz)
* **Model in Settings**: `SWTV` (Internal code: `SW3H_ATV_T`)
* **Chassis / Board**: `SW3H` (Device: `SW3H_ATV`)
* **SoC / Chipset**: MediaTek **MT5886** / **MT5660** (`ro.vendor.nrdp.modelgroup=SW3ATV5660`, 4-core Cortex-A53 / ARMv7)
* **Resolution**: 4K UHD (`3840x2160`)
* **OS / Build**: Android 9 (API 28), Build ID: `PTT1.181130.001 release-keys`
* **Incremental**: `www-data20200812` (Build date: August 12, 2020)
* **Security Patch**: July 5, 2020
* **Target Region**: `IND` (India) — `ro.oem.key1=ATV00104319`
* **Official Support Contacts**: `support.moto@flipkart.com` / Phone: `080-45471080` / `080-69290890`
* **Manufacturer**: Shenzhen Chuangwei-RGB Electronics (Skyworth)
* **OTA System**: Android A/B dual partition scheme (`pm.dexopt.ab-ota=speed-profile`)

---

## 3. Key Diagnostic Findings

### A. The Direct Culprit in System Properties
The report pinpointed the exact system property disabling standby:
```properties
persist.sys.fast_standby=0
persist.sys.auto_standby=0
persist.sys.power=0
```
When `persist.sys.fast_standby=0`, the MediaTek power HAL intercepts the remote power key and triggers a full kernel shutdown rather than Suspend-to-RAM (STR).

### B. Confirmed Deep-Sleep Inactivity
* **Elapsed Realtime**: 0h 37m 24s
* **Awake Time**: 0h 37m 24s
* **Deep Sleep Time**: **`0h 00m 00s`** (Kernel never enters sleep)

### C. ADB Daemon Status
`adbd` is actually **running** on the TV (`init.svc.adbd=running`, `sys.usb.state=mtp,adb`, `adb_enabled=1`), but network ADB fails because TCP listening (`service.adb.tcp.port=5555`) is disabled by default.

### D. Exported OEM Factory Menu Located
The Skyworth Engineer/Factory app is installed and exposed on the TV:
* **Package**: `com.smartdevice.factory` (`/system/app/SkyFactory/SkyFactory.apk`)
* **Exported Activity**: `com.smartdevice.factory.SkyFactory`

---

## 4. Factory Menu Investigation Progress

The user successfully entered the **Skyworth Factory Menu** on screen:

### Main Factory Menu Items:
* `Hotkey` [Off]
* `5V_Antenna_Power` [Off]
* `MEMC LD run led` [Off]
* `PVR` [Off]
* `JB Control` [Off]
* `OFFRS Control` [Off]
* `OLED TEST MODE Control` [Off]
* `Info View >>`
* `White Balance >>`
* `Test Pattern` [Off]
* `Update Datas >>`
* `Detail Info & Order >>`
* `Others >>`

### Clarifications Made:
* **"Power On Mode"**: Located on the main diagnostic page, set to `Standby` (options: `Standby` / `On`). This setting strictly controls what the TV does when plugged into the wall outlet (AC mains), not what the remote button does. It was kept on `Standby`.

### Contents of `Others >>` Submenu:
* `Aging Mode`
* `Channel Preset`
* `Out Factory Reset`
* `Restore Default Values`
* `Hotel Mode`
* `Serial Port Mode` `< UART_DEBUG >`
* `Backup Data >>`
* `Restore Data >>`
* `OTA update >>`
* `Install App` `[Off]`
* `Audio Test Mode`
* `Hardware Info >>`
* `Software Info >>`
* `Sound Curve >>`
* `Nonlinearity >>`
* `Over Scan >>`
* `Finish Keys >>`

---

## 5. Next Steps for Next Session

1. **Firmware Rollback / Reflash (If Motorola Stock UI is ever desired)**:
   * Use the exact board strings (`SW3H`, `MT5886`, `SW3ATV5660`, `IND`) and Order ID `9K21T-43Q21-F` to request official firmware from Flipkart/Motorola support (`support.moto@flipkart.com`).

---

## 6. The Verified Working Solution (No ADB, No Reflash Needed!)

The root cause was that Skyworth separates the **Android OS Settings** from the **Hardware TV Tuner / Input Settings**. The Fast Boot toggle is completely missing from the Android Settings app, but exists inside the hardware overlay menu:

### Step-by-Step Fix:
1. **Switch to an Input Source**:
   * Press the **SOURCE** or **INPUT** button on the remote and select any input (e.g. **HDMI 1, HDMI 2, or AV**).
   * *Critical*: If the TV is on the Android Home Screen, this menu does not appear. You must be on an active input.
2. **Open the Skyworth Hardware Menu**:
   * Press the **MENU** button on the TV remote.
3. **Navigate to "MORE"**:
   * Scroll to the bottom of the side-menu to highlight **MORE** and press **OK**.
4. **Access "TV Settings"**:
   * Select **TV Settings** (the first option) and press **OK**.
5. **Turn On Fast Boot**:
   * Scroll down to the **Settings** section.
   * Highlight **Fast Boot** and toggle it **ON**.
   * *(Optional)* Set **Auto Standby** according to preference.

### Result:
Toggling **Fast Boot ON** sets `persist.sys.fast_standby=1` at the hardware level. The TV now instantly enters low-power standby mode when pressing the remote's power button and wakes immediately without a cold boot!

