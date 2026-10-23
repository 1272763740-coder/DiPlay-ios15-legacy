# DiPlay — legacy wired CarPlay for iOS 15 and earlier

[English](README.md) · [简体中文](README.zh-CN.md) · [繁體中文](README.zh-TW.md)

This repository is a derivative of **DiPlay 0.2.21** that adds an experimental **legacy wired USB
CarPlay** path for iPhones that stop at iOS 15 (target device: iPhone 7, iOS 15.8.3). The standard
wired (iOS 16+) and wireless paths come from upstream and are unchanged. Install the APK on the
**car head unit**, not the iPhone.

> **Status:** the legacy path is implemented and CI builds pass. It has **not** been validated end to
> end on a car. The USB role switch and gadget setup depend on the head unit firmware and can
> interrupt ADB or other USB devices. Treat every build here as a test build.

## What the legacy path does

- Sends Apple's vendor request `0x51` so the iPhone leaves the host bus, then switches the head
  unit's USB port from host to device.
- Presents a FunctionFS iAP2 + CDC-NCM composite gadget and authenticates MFi over that link.
- Identifies as `IAP2-USBHost` (interface 1) without claiming the standard wired profile, and never
  sends or answers `0x52`, `0x4300` or `0x4301` on this path.
- Carries AirPlay and mDNS over the USB network (`169.254.1.1` / `fe80::1`).

The root helper is a PIE executable packaged for `arm64-v8a`, `armeabi-v7a`, `x86` and `x86_64` and
extracted from `nativeLibraryDir` on install. CI checks the packaged helper with
`scripts/verify_legacy_gadget_apk.py`.

## Download

[Releases](https://github.com/1272763740-coder/DiPlay-ios15-legacy/releases) ·
[Issues](https://github.com/1272763740-coder/DiPlay-ios15-legacy/issues)

The current release is `v0.2.21-legacy.1`. Its APK is a **source-only debug build**:

- Package `com.shihab.diplay.hudtest`, version `0.2.21-hud-test`, debug-signed. It installs alongside
  the regular `com.shihab.diplay`.
- It contains **no MFi identity**. `assets/offline-mfi/identity.pk8` and `certificate.p7b` are
  excluded from this source tree and from CI builds.
- CarPlay authentication therefore needs an MFi backend that survives the USB role flip: the head
  unit's internal I2C MFi, Remote MFi, or a CH341 attached to a different USB controller. The
  `LOCAL` offline backend only works in a build where you inject identity assets yourself (see
  [Build](#build)).
- Without a working MFi backend the CarPlay screen can open, but authentication cannot complete.

## Requirements

- A rooted BYD-compatible Android head unit (Android 7.0+), with `su` available to the app.
- configfs with FunctionFS and `usb_f_ncm` (built in or loadable with `modprobe`), a writable UDC,
  and a writable USB role switch for the port the iPhone uses.
- An MFi backend as described above.
- For the legacy path, an iPhone on iOS 15 or earlier. iOS 16 and later use the standard wired path.

Root, UDC and role-switch support are firmware specific. Use **Settings → Advanced → Legacy wired
CarPlay (experimental) → Check compatibility**; it reports the first missing requirement instead of
failing silently.

## Use

1. Install the APK on the head unit and open it.
2. Open **Settings → Advanced → Legacy wired CarPlay (experimental)** and press **Check compatibility**.
3. Select **Legacy (iOS 15 and earlier)**, confirm the warning, and let it apply at the next
   connection.
4. Connect the iPhone by USB and watch the diagnostic log for `legacy ...` lines.
5. To go back, select the standard mode and reconnect. iOS 16+ must use the standard mode.

Legacy mode can drop a running ADB connection and disturbs other USB gadgets on the same controller.
Do not enable it on a port you still need for ADB.

## Build

CI (`.github/workflows/android.yml`) builds the debug APK, verifies the helper payload, then runs
unit tests and lint. Local builds need JDK 25, Android SDK 37 and NDK 28.2.13676358:

```sh
./gradlew :mobile:assembleDebug                 # source-only, no MFi identity
./gradlew :shared:testDebugUnitTest :common:testDebugUnitTest :home:testDebugUnitTest \
  :mobile:lintDebug :home:lintDebug :maphost:lintDebug
```

For a standalone car-test APK with local offline MFi, provide your own identity assets and build the
standalone variant:

```sh
DIPLAY_AUTH_ASSETS_DIR=/path/to/assets ./gradlew :mobile:assembleStandaloneDebug
```

`/path/to/assets` must contain `offline-mfi/identity.pk8` and `offline-mfi/certificate.p7b`. Never
commit those files; the build rejects unexpected credentials in APK assets.

## Verification status

- CI passes: assemble, helper verification, unit tests, lint and test reports.
- The APK is checked for the four ABI helpers, `extractNativeLibs=true`, and the legacy markers in
  dex. The release notes record the exact build fingerprint and SHA-256.
- Still requires a car: `0x51` acceptance, the iPhone leaving the host bus, the role switch, MFi,
  NCM bring-up, mDNS discovery and the first AirPlay frame.

## Limits

- Not Apple-certified and not affiliated with Apple or BYD. BYD is the only supported brand.
- The legacy path only helps iOS 15 and earlier; it is not a fallback for iOS 16+.
- Role switching and gadget creation are firmware-dependent and may fail or leave USB in a changed
  state.
- The bundled helper runs as root and writes to configfs. Read the source before installing.

## Source and credits

Based on [DiPlay](https://github.com/shihabal3amri/DiPlay) 0.2.21 (GPL-3.0), which builds on
[xcertplay](https://github.com/shilapi/xcertplay) (GPL-3.0) and adapts
[DiAuto](https://github.com/shihabal3amri/DiAuto) for the home/settings UI (AGPL-3.0). License
notices are kept in `docs/licenses` and `LICENSE`; preserve them when redistributing modifications.
The application id `com.shihab.diplay` and the upstream name are retained for compatibility.
