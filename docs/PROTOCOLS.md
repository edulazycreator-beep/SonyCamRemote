# Sony remote-control protocols — what runs on Android

Research summary (checked 2026-11-07) and how each option is used by SonyCam Remote.

## 1. Camera Remote SDK (official) — **cannot be embedded in Android**

- Sony's SDK is a native library for **Windows 11 (x64), macOS 14.1+, Linux x86-64, Linux ARMv7 and ARMv8**.
  Android is not a supported platform.
- Free of charge; apps built with it may be sold for legitimate purposes, under Sony's licence agreement.
- Supports USB, wired LAN and Wi-Fi depending on the body.
- **This app does not bundle or redistribute the SDK.** Instead it can talk to an optional *bridge host*
  (e.g. a Raspberry Pi 4/5 on ARMv8 Linux) that runs your own SDK-based server — see
  [BRIDGE_PROTOCOL.md](BRIDGE_PROTOCOL.md). `SonyBridgeAdapter` is the client.

## 2. Camera Remote Command (official) — implementable on Android, docs are licensed

- Sony's published command codes for its **proprietary extension of ISO 15740 (PTP)**. Interfaces: USB, wired
  LAN and Wi-Fi; PTP-IP control was added in version 2024.1.0.
- Documentation (Command Reference + example code) is free but **available to corporate customers only**, under a
  licence agreement. It is not included in this repository.
- Because PTP over USB host and PTP-IP over TCP are both available on Android, this is the realistic route for
  modern bodies. Implemented as `SonyPtpAdapter` + `UsbPtpTransport` / `PtpIpTransport`.
- **Codes used today come from the open-source libgphoto2 project** (`camlibs/ptp2/ptp.h`), not from Sony's
  reference. They are isolated in `SonyPtpCodes.kt`. Before release, a licensee should check each code against the
  official Command Reference. Features whose codes are not known reliably (zoom, touch-AF position over PTP,
  recording-state property, Access Authentication for PTP-IP) are **not implemented** rather than guessed; the UI
  hides them.

Handshake used (`SonyPtpAdapter.connect`):
1. `GetDeviceInfo` (0x1001) → model, firmware (`DeviceVersion`), supported operations.
2. `OpenSession` (0x1002).
3. `SDIO_Connect` 0x9201 with phases 1, 2 → `SDIO_GetExtDeviceInfo` 0x9202 (protocol 0x12C, fallback 0xC8) → phase 3.
4. `GetAllExtDevicePropInfo` 0x9209 polled every 500 ms for state.
5. Properties set with `SetControlDeviceA` 0x9205; buttons with `SetControlDeviceB` 0x9207 (2 = press, 1 = release).
6. Live View = `GetObject` of handle `0xFFFFC002`; the JPEG is located between SOI/EOI markers.

Camera setup: *USB Connection* → **PC Remote** (USB), or *Network → PC Remote Function* (Wi-Fi/LAN). For PTP-IP,
cameras with **Access Authentication** enabled will reject the connection until authentication is implemented
from the official docs; disable it on the camera for now.

## 3. Legacy Camera Remote API — fully implemented

- Public Sony API (JSON-RPC over HTTP) for ~2013–2018 bodies; discontinued for new models.
- Discovery via SSDP `urn:schemas-sony-com:service:ScalarWebAPI:1` → device description XML → service URLs.
- Implemented as `LegacySonyApiAdapter`: `getAvailableApiList` drives capabilities; `getEvent` long-polling drives
  state; Live View stream parsed per Sony's packet format (`LiveViewStreamParser`); media via `avContent`.
- Many bodies need the *Smart Remote Control* PlayMemories camera app. That store has closed: if the app is not
  already on the camera it cannot be added.

## 4. Imaging Edge Mobile / Creators' App pairing

Sony's own phone apps use a proprietary pairing and (for newer bodies) Bluetooth LE handshake that Sony does not
document for third parties. This app does **not** reverse-engineer or imitate it.

## 5. Bluetooth

Sony's Bluetooth remote (as used by the RMT-P1BT grip/remote and Sony's apps) is not publicly documented by Sony.
Per the project rule "Bluetooth only where officially supported", **no Bluetooth control is implemented**.
If Sony publishes a BLE remote specification, add a `SonyBleAdapter` implementing `CameraProtocol`.

## Summary

| Route | Runs on Android | Official docs | Status in app |
|---|---|---|---|
| Camera Remote SDK | No (desktop/Linux only) | Yes (licensed, free) | Via bridge host (client implemented, server is yours) |
| Camera Remote Command (PTP USB / PTP-IP) | Yes | Yes (corporate customers) | Implemented with community codes; verify with official reference |
| Legacy Camera Remote API | Yes | Yes (public, archived) | Implemented |
| Imaging Edge pairing / BLE | Technically | No | Not implemented by design |
