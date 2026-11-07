# SonyCam Remote

Professional remote control for compatible Sony cameras on Android phones and tablets — Live View, remote
shutter and movie recording, exposure and focus control, media transfer and multi-camera management, in a dark
cinema-style interface.

> Independent project, not affiliated with or endorsed by Sony. "Sony", "Alpha", "Cinema Line" are Sony trademarks.

**Compatibility is not universal.** Sony bodies use different protocols. Features appear only when the connected
camera advertises them. No model is marked hardware-verified yet — see [docs/COMPATIBILITY.md](docs/COMPATIBILITY.md).

## Connection methods

| Method | For | Camera setting |
|---|---|---|
| Wi-Fi — Legacy Camera Remote API | ~2013–2018 bodies (a6000–a6500, a7 II gen, RX100 III–V, QX, Action Cam) | *Ctrl w/ Smartphone* / Smart Remote app |
| USB OTG — Sony PTP ("PC Remote") | Current Alpha, FX, ZV, PXW/HXR models | *USB Connection → PC Remote* |
| Wi-Fi/LAN — Sony PTP-IP | Bodies with network PC Remote | *Network → PC Remote*, access authentication off |
| SDK bridge host | Anything the official Camera Remote SDK supports | Bridge host next to camera |

Details and the protocol research: [docs/PROTOCOLS.md](docs/PROTOCOLS.md).

## Features

- Discovery: USB, SSDP, camera-AP gateway probe; direct Wi-Fi join (Android 10+), camera QR code, manual IP; saved
  profiles with Keystore-encrypted passwords; automatic reconnection.
- Live View: real camera frames, landscape workspace + portrait layout, pinch preview zoom (display-only, labelled),
  rule-of-thirds / crosshair / safe-area guides, histogram and highlight-clipping warning from the actual frame,
  touch AF, fps and command-latency readouts.
- Shooting: shutter (optional AF half-press), movie record with timer, intervalometer / timelapse with start delay.
- Exposure: mode, shutter, aperture, ISO, EV, WB, Kelvin, metering, drive, file format, aspect, self-timer — values
  and options exactly as reported by the camera.
- Focus: AF half-press hold, near/far drive, focus mode. Zoom: camera zoom (hold).
- Camera info dashboard: model, firmware, battery, card, remaining shots/time, current settings.
- Media: browse, thumbnails, multi-select batch download to the gallery with progress, share.
- Multi-camera: several sessions, switch active camera, group shoot/record with measured dispatch spread.
- Presets / shooting recipes, diagnostics log, capability report, session history, compatibility database.

## Build

Requirements: JDK 17, Android SDK 36 (Android Studio Meerkat+ or command line). minSdk 26, targetSdk 36.

```bash
./gradlew :camera-engine:test :app:testDebugUnitTest   # unit tests
./gradlew :app:lintDebug
./gradlew :app:assembleDebug                          # app/build/outputs/apk/debug/
./gradlew :app:bundleRelease                          # needs signing env vars, see docs/SIGNING.md
```

## CI/CD

- `.github/workflows/android-ci.yml` — every push/PR: tests, lint, debug APK, release APK, AAB uploaded as
  artifacts (Actions → run → *Artifacts* → download the APK).
- `.github/workflows/android-release.yml` — tag `vX.Y.Z`: signed APK + AAB published as a GitHub Release.
  Signing secrets: [docs/SIGNING.md](docs/SIGNING.md).

## Install

Download `SonyCamRemote-…-debug-apk` (or the signed release APK) from GitHub Actions/Releases, allow
"Install unknown apps" for your browser/files app, open the APK. Android 8.0 (API 26) or later.

## Docs

[Architecture](docs/ARCHITECTURE.md) · [Protocols](docs/PROTOCOLS.md) · [Compatibility](docs/COMPATIBILITY.md) ·
[Bridge API](docs/BRIDGE_PROTOCOL.md) · [Signing](docs/SIGNING.md) · [Privacy](docs/PRIVACY.md)

## Known limitations

- Sony PTP codes come from libgphoto2's public documentation, pending verification against Sony's licensed
  Command Reference. PTP zoom, PTP touch-AF and PTP-IP Access Authentication are not implemented until then.
- Sony PC Remote mode does not expose the memory card, so media transfer works over the legacy Wi-Fi API or the bridge.
- A phone can join only one camera access point at a time; for several Wi-Fi cameras put them on one router/AP.
- No Bluetooth control: Sony does not publish its BLE remote protocol.
- Zebra and focus peaking are not streamed by any Sony remote protocol and are not imitated.
- Interval shooting needs the app in the foreground (screen kept on).
