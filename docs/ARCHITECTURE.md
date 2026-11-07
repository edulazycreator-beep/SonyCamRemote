# Architecture

```
┌──────────────────────────── :app (Android, Kotlin, Compose, Hilt) ────────────────────────────┐
│ feature/  dashboard · connect · liveview · info · media · multicamera · presets ·             │
│           diagnostics · compat · settings        (Compose screens + @HiltViewModel)           │
│ ui/       theme (Dark Cinema) · components · overlays · navigation                            │
│ data/camera                                                                                    │
│   CameraSessionManager  ← CameraRepository (multi-session, active camera, recovery, group cmd) │
│   CameraDiscoveryService (USB + SSDP + gateway port probe)                                     │
│   ProtocolFactory · ConnectionProfile · SavedCameraRepository                                 │
│   LiveViewStreamManager (decode, fps, histogram, clipping) · MediaSaver (MediaStore)          │
│   usb/  UsbPtpTransport · UsbCameraManager (permission)                                       │
│   wifi/ WifiCameraConnector (WifiNetworkSpecifier, bind process, multicast lock, QR parse)    │
│ data/db (Room) · data/prefs (DataStore) · data/security (Keystore AES-GCM)                     │
└───────────────────────────────────────────────┬───────────────────────────────────────────────┘
                                                │ depends on
┌───────────────────────────── :camera-engine (pure Kotlin/JVM) ────────────────────────────────┐
│ protocol/CameraProtocol  ◄─ implemented by ─┬─ legacy/LegacySonyApiAdapter (JSON-RPC, HTTP)    │
│                                              ├─ ptp/SonyPtpAdapter ── PtpTransport ─┬ PtpIpTransport (TCP 15740)
│                                              │                                     └ UsbPtpTransport (in :app)
│                                              └─ bridge/SonyBridgeAdapter (HTTP → SDK host)     │
│ capability/CameraCapabilityManager · compat/CompatibilityDatabase                             │
│ command/CameraCommandQueue · diagnostics/DiagnosticsLog · discovery/SsdpDiscovery, PortProbe  │
│ model/ (CameraState, Capability, PropertyId, SettingValue, CameraCommand, MediaItem, errors)  │
└───────────────────────────────────────────────────────────────────────────────────────────────┘
```

The spec's `CameraConnectionManager` role is played by `CameraSessionManager` + `ProtocolFactory`;
`CameraStateMonitor` is each adapter's event/poll loop feeding `CameraProtocol.state`;
`ConnectionRecoveryManager` is `CameraSessionManager.recover()`.

## Why two modules

The protocol engine has no Android dependency, so every parser, adapter and the command queue is unit-tested on the
JVM in CI with scripted fakes (`MockWebServer`, a fake PTP device). Android-only pieces — USB host, Wi-Fi network
requests, MediaStore, Compose — live in `:app`.

## Key rules enforced in code

- **Capability-gated UI.** Every control checks `Capability in state.capabilities`, which adapters derive from what
  the camera advertises (`getAvailableApiList`, PTP ext-device-info and property list, bridge report). The
  compatibility database never enables anything; it only produces warnings in the capability report.
- **Camera-reported values only.** Pickers list `CameraProperty.options` exactly as reported. PTP values the app
  cannot label are shown in hex rather than guessed. Presets are applied by matching labels and skip values the
  camera does not currently offer.
- **No synthetic data.** Live View frames are camera JPEGs; nothing is drawn in place of a missing stream.
- **Serialised commands.** `CameraCommandQueue` (mutex + timeout + diagnostics) per adapter; PTP transactions are
  additionally serialised by `PtpSession`.
- **Structured concurrency and lifecycle.** Each `CameraSession` owns a `CoroutineScope`; disconnect cancels it,
  closes sockets/USB interfaces, and releases the bound Wi-Fi network when the last session closes. Live View runs
  only between `ON_START`/`ON_STOP` of the Live View screen, with frame conflation so the preview never lags.
- **Reconnection.** On `ConnectionStatus.FAILED`, exponential back-off 1 → 16 s, 6 attempts (setting-controlled).
- **Multi-camera.** Sessions are independent. Group commands are dispatched concurrently and the measured dispatch
  spread is shown; the UI states that this is not frame-accurate sync.

## Threading

Network/USB I/O on `Dispatchers.IO`; JPEG decoding and histogram on `Dispatchers.Default`; UI state via
`StateFlow` collected with `collectAsStateWithLifecycle`. Nothing blocks the main thread.

## Permissions

`INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`, `CHANGE_WIFI_STATE`, `CHANGE_WIFI_MULTICAST_STATE` (SSDP),
`NEARBY_WIFI_DEVICES` (Android 13+, `neverForLocation`, requested only when joining a camera network),
`CAMERA` (only when scanning the QR code), `WRITE_EXTERNAL_STORAGE` (Android 8–9 only, when saving downloads).
USB access uses the per-device system permission dialog. Cleartext HTTP is allowed because cameras serve plain
HTTP on the LAN at addresses not known in advance.

## Testing

- `camera-engine/src/test`: Live View stream parser, device description & SSDP parsing, legacy adapter against a
  scripted JSON-RPC server, PTP codec, Sony property parser, PTP adapter handshake/capture order against a fake
  device, unsupported-command handling, command queue serialisation and timeouts, compatibility database
  invariants (nothing marked hardware-verified).
- `app/src/test`: Wi-Fi QR parsing.
- Hardware: the manual compatibility test suite in [COMPATIBILITY.md](COMPATIBILITY.md). Fakes prove protocol
  handling only — they are never counted as camera compatibility.
