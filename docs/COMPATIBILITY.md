# Camera compatibility

**Status (2026-11): no model has been verified on real hardware with this app yet.** Every row below is
an *expectation* taken from Sony's published model lists or from community reports. The app never relies
on this table to enable features: controls are shown only when the connected camera advertises them
at runtime (see `CameraCapabilityManager`). The same data is in the app under *Compatibility*.

Sources
- Camera Remote SDK / Camera Remote Command supported-model lists — <https://support.d-imaging.sony.co.jp/app/sdk/en/index.html>, <https://support.d-imaging.sony.co.jp/app/cameraremotecommand/en/index.html> (checked 2026-11-07)
- Sony Camera Remote API (legacy, Wi-Fi JSON-RPC) beta compatibility list (archived Sony developer pages)
- libgphoto2 camera list (community reports for Sony PTP/"PC Remote" over USB)

`*` PTP-IP (Wi-Fi/LAN) only on bodies whose Sony README lists LAN/Wi-Fi control. Sony supports only the latest
firmware for SDK/Command control.

## Feature matrix legend

The per-feature columns (ISO, shutter, aperture, focus, zoom, WB, media transfer, battery) are *not* filled in
statically because they depend on protocol **and** firmware **and** camera mode. Use the in-app
**Diagnostics → Capability report** on the real camera; submit it with the test-suite result below to move a model to
"Verified".

| Model | Name | Family | Protocols | Evidence | Live View | Notes | HW verified |
|---|---|---|---|---|---|---|---|
| ILCE-1M2 | Alpha 1 II | Alpha 1 | PTP USB · PTP-IP* · SDK bridge | Listed by Sony | Expected | Latest FW | ✗ |
| ILCE-1 | Alpha 1 | Alpha 1 | PTP USB · PTP-IP* · SDK bridge | Listed by Sony | Expected | Latest FW | ✗ |
| ILCE-9M3 | Alpha 9 III | Alpha 9 | PTP USB · PTP-IP* · SDK bridge | Listed by Sony | Expected | Latest FW | ✗ |
| ILCE-9M2 | Alpha 9 II | Alpha 9 | PTP USB · PTP-IP* · SDK bridge | Listed by Sony | Expected | Latest FW | ✗ |
| ILCE-7RM6 | Alpha 7R VI | Alpha 7 | PTP USB · PTP-IP* · SDK bridge | Listed by Sony | Expected | Latest FW | ✗ |
| ILCE-7RM5 | Alpha 7R V | Alpha 7 | PTP USB · PTP-IP* · SDK bridge | Listed by Sony | Expected | Latest FW | ✗ |
| ILCE-7RM4A | Alpha 7R IVA | Alpha 7 | PTP USB · PTP-IP* · SDK bridge | Listed by Sony | Expected | Latest FW | ✗ |
| ILCE-7RM4 | Alpha 7R IV | Alpha 7 | PTP USB · PTP-IP* · SDK bridge | Listed by Sony | Expected | Latest FW | ✗ |
| ILCE-7CR | Alpha 7CR | Alpha 7 | PTP USB · PTP-IP* · SDK bridge | Listed by Sony | Expected | Latest FW | ✗ |
| ILCE-7SM3 | Alpha 7S III | Alpha 7 | PTP USB · PTP-IP* · SDK bridge | Listed by Sony | Expected | Latest FW | ✗ |
| ILCE-7M5 | Alpha 7 V | Alpha 7 | PTP USB · PTP-IP* · SDK bridge | Listed by Sony | Expected | Latest FW | ✗ |
| ILCE-7M4 | Alpha 7 IV | Alpha 7 | PTP USB · PTP-IP* · SDK bridge | Listed by Sony | Expected | Latest FW | ✗ |
| ILCE-7CM2 | Alpha 7C II | Alpha 7 | PTP USB · PTP-IP* · SDK bridge | Listed by Sony | Expected | Latest FW | ✗ |
| ILCE-7C | Alpha 7C | Alpha 7 | PTP USB · PTP-IP* · SDK bridge | Listed by Sony | Expected | Latest FW | ✗ |
| ILCE-6700 | Alpha 6700 | Alpha 6xxx | PTP USB · PTP-IP* · SDK bridge | Listed by Sony | Expected | Latest FW | ✗ |
| ILX-LR1 | ILX-LR1 | Industrial | PTP USB · PTP-IP* · SDK bridge | Listed by Sony | Expected | Latest FW | ✗ |
| MPC-2610 | BURANO | Cinema Line | PTP USB · PTP-IP* · SDK bridge | Listed by Sony | Expected | Latest FW | ✗ |
| ILME-FX6 | FX6 (FX6V / FX6T) | Cinema Line | PTP USB · PTP-IP* · SDK bridge | Listed by Sony | Expected | FW Ver. 3.00 or later | ✗ |
| ILME-FX3A | FX3A | Cinema Line | PTP USB · PTP-IP* · SDK bridge | Listed by Sony | Expected | Latest FW | ✗ |
| ILME-FX3 | FX3 | Cinema Line | PTP USB · PTP-IP* · SDK bridge | Listed by Sony | Expected | FW Ver. 2.00 or later | ✗ |
| ILME-FX2 | FX2 | Cinema Line | PTP USB · PTP-IP* · SDK bridge | Listed by Sony | Expected | Latest FW | ✗ |
| ILME-FX30 | FX30 | Cinema Line | PTP USB · PTP-IP* · SDK bridge | Listed by Sony | Expected | Latest FW | ✗ |
| ILME-FR7 | FR7 | Cinema Line PTZ | PTP USB · PTP-IP* · SDK bridge | Listed by Sony | Expected | FW Ver. 3.00 or later | ✗ |
| PXW-Z300 | PXW-Z300 | PXW | PTP USB · PTP-IP* · SDK bridge | Listed by Sony | Expected | Latest FW | ✗ |
| PXW-Z380 | PXW-Z380 | PXW | PTP USB · PTP-IP* · SDK bridge | Listed by Sony | Expected | Latest FW | ✗ |
| PXW-Z200 | PXW-Z200 | PXW | PTP USB · PTP-IP* · SDK bridge | Listed by Sony | Expected | Latest FW | ✗ |
| HXR-NX800 | HXR-NX800 | HXR | PTP USB · PTP-IP* · SDK bridge | Listed by Sony | Expected | Latest FW | ✗ |
| BRC-AM7 | BRC-AM7 | PTZ | PTP USB · PTP-IP* · SDK bridge | Listed by Sony | Expected | Latest FW | ✗ |
| ZV-E1 | ZV-E1 | ZV | PTP USB · PTP-IP* · SDK bridge | Listed by Sony | Expected | Latest FW | ✗ |
| ZV-E10M2 | ZV-E10 II | ZV | PTP USB · PTP-IP* · SDK bridge | Listed by Sony | Expected | Latest FW | ✗ |
| DSC-RX1RM3 | RX1R III | RX | PTP USB · PTP-IP* · SDK bridge | Listed by Sony | Expected | Latest FW | ✗ |
| DSC-RX0M2 | RX0 II | RX | PTP USB · PTP-IP* · SDK bridge | Listed by Sony | Expected | FW Ver. 3.00 or later | ✗ |
| ILCE-7M3 | Alpha 7 III | Alpha 7 | PTP USB (PC Remote) | Community | Unknown | Not on Sony SDK list | ✗ |
| ILCE-6400 | Alpha 6400 | Alpha 6xxx | PTP USB (PC Remote) | Community | Unknown | Not on Sony SDK list | ✗ |
| ILCE-6100 | Alpha 6100 | Alpha 6xxx | PTP USB (PC Remote) | Community | Unknown | Not on Sony SDK list | ✗ |
| ZV-E10 | ZV-E10 | ZV | PTP USB (PC Remote) | Community | Unknown | Not on Sony SDK list | ✗ |
| ZV-1 | ZV-1 | ZV | PTP USB (PC Remote) | Community | Unknown | Not on Sony SDK list | ✗ |
| ZV-1F | ZV-1F | ZV | PTP USB (PC Remote) | Community | Unknown | Not on Sony SDK list | ✗ |
| DSC-RX100M7 | RX100 VII | RX | PTP USB (PC Remote) | Community | Unknown | Not on Sony SDK list | ✗ |
| DSC-RX100M6 | RX100 VI | RX | PTP USB (PC Remote) | Community | Unknown | Not on Sony SDK list | ✗ |
| DSC-RX10M4 | RX10 IV | RX | PTP USB (PC Remote) | Community | Unknown | Not on Sony SDK list | ✗ |
| DSC-RX0 | RX0 | RX | PTP USB (PC Remote) | Community | Unknown | Not on Sony SDK list | ✗ |
| ILCE-7M2 | Alpha 7 II | Alpha 7 | Legacy Remote API (Wi-Fi) | Listed by Sony (legacy) | Expected | Needs Smart Remote Control app | ✗ |
| ILCE-7RM2 | Alpha 7R II | Alpha 7 | Legacy Remote API (Wi-Fi) | Listed by Sony (legacy) | Expected | Needs Smart Remote Control app | ✗ |
| ILCE-7SM2 | Alpha 7S II | Alpha 7 | Legacy Remote API (Wi-Fi) | Listed by Sony (legacy) | Expected | Needs Smart Remote Control app | ✗ |
| ILCE-7 | Alpha 7 | Alpha 7 | Legacy Remote API (Wi-Fi) | Listed by Sony (legacy) | Expected | Needs Smart Remote Control app | ✗ |
| ILCE-7R | Alpha 7R | Alpha 7 | Legacy Remote API (Wi-Fi) | Listed by Sony (legacy) | Expected | Needs Smart Remote Control app | ✗ |
| ILCE-7S | Alpha 7S | Alpha 7 | Legacy Remote API (Wi-Fi) | Listed by Sony (legacy) | Expected | Needs Smart Remote Control app | ✗ |
| ILCE-6500 | Alpha 6500 | Alpha 6xxx | Legacy Remote API (Wi-Fi) | Listed by Sony (legacy) | Expected | Built-in remote | ✗ |
| ILCE-6300 | Alpha 6300 | Alpha 6xxx | Legacy Remote API (Wi-Fi) | Listed by Sony (legacy) | Expected | Built-in remote | ✗ |
| ILCE-6000 | Alpha 6000 | Alpha 6xxx | Legacy Remote API (Wi-Fi) | Listed by Sony (legacy) | Expected | Needs Smart Remote Control app | ✗ |
| ILCE-5100 | Alpha 5100 | Alpha 5xxx | Legacy Remote API (Wi-Fi) | Listed by Sony (legacy) | Expected | Needs Smart Remote Control app | ✗ |
| ILCE-5000 | Alpha 5000 | Alpha 5xxx | Legacy Remote API (Wi-Fi) | Listed by Sony (legacy) | Expected | Needs Smart Remote Control app | ✗ |
| ILCE-QX1 | QX1 | QX | Legacy Remote API (Wi-Fi) | Listed by Sony (legacy) | Expected | Built-in remote | ✗ |
| DSC-QX100 | QX100 | QX | Legacy Remote API (Wi-Fi) | Listed by Sony (legacy) | Expected | Built-in remote | ✗ |
| DSC-QX30 | QX30 | QX | Legacy Remote API (Wi-Fi) | Listed by Sony (legacy) | Expected | Built-in remote | ✗ |
| DSC-QX10 | QX10 | QX | Legacy Remote API (Wi-Fi) | Listed by Sony (legacy) | Expected | Built-in remote | ✗ |
| DSC-RX100M5 | RX100 V | RX | Legacy Remote API (Wi-Fi) | Listed by Sony (legacy) | Expected | Built-in remote | ✗ |
| DSC-RX100M4 | RX100 IV | RX | Legacy Remote API (Wi-Fi) | Listed by Sony (legacy) | Expected | Needs Smart Remote Control app | ✗ |
| DSC-RX100M3 | RX100 III | RX | Legacy Remote API (Wi-Fi) | Listed by Sony (legacy) | Expected | Needs Smart Remote Control app | ✗ |
| DSC-RX100M2 | RX100 II | RX | Legacy Remote API (Wi-Fi) | Listed by Sony (legacy) | Expected | Built-in remote | ✗ |
| DSC-RX10M3 | RX10 III | RX | Legacy Remote API (Wi-Fi) | Listed by Sony (legacy) | Expected | Needs Smart Remote Control app | ✗ |
| DSC-RX10M2 | RX10 II | RX | Legacy Remote API (Wi-Fi) | Listed by Sony (legacy) | Expected | Needs Smart Remote Control app | ✗ |
| DSC-RX1RM2 | RX1R II | RX | Legacy Remote API (Wi-Fi) | Listed by Sony (legacy) | Expected | Needs Smart Remote Control app | ✗ |
| DSC-HX90V | HX90V | Cyber-shot | Legacy Remote API (Wi-Fi) | Listed by Sony (legacy) | Expected | Built-in remote | ✗ |
| DSC-HX80 | HX80 | Cyber-shot | Legacy Remote API (Wi-Fi) | Listed by Sony (legacy) | Expected | Built-in remote | ✗ |
| FDR-X3000 | FDR-X3000 | Action Cam | Legacy Remote API (Wi-Fi) | Listed by Sony (legacy) | Expected | Built-in remote | ✗ |
| HDR-AS300 | HDR-AS300 | Action Cam | Legacy Remote API (Wi-Fi) | Listed by Sony (legacy) | Expected | Built-in remote | ✗ |

## Protocol-specific limitations

| Area | Legacy Remote API | Sony PTP (USB / PTP-IP) | SDK bridge |
|---|---|---|---|
| Live View | Yes (HTTP stream) | Yes (`GetObject 0xFFFFC002`) | Yes (bridge JPEG) |
| Still capture / AF half-press | If advertised | If control codes advertised | Per SDK |
| Movie record | If advertised | Toggle control; state tracked by app | Per SDK |
| ISO / shutter / aperture / EV / WB | If `set*` API advertised | If property reported | Per SDK |
| Touch AF | If `setTouchAFPosition` advertised | **Not implemented** (needs official codes) | Per SDK |
| Focus near/far | **Not available** in this API | If `0xD2D1` advertised | Per SDK |
| Zoom | If `actZoom` advertised | **Not implemented** (needs official codes) | Per SDK |
| Media browse/transfer | Via `avContent` ("Contents Transfer" mode) | **Not available** in PC Remote mode | Per bridge |
| Temperature warning | Not reported | Not mapped | If bridge reports |
| Zebra / focus peaking | Not exposed to remote clients | Not exposed | Not exposed |

Zebra and peaking are drawn by the camera on its own screen; no supported protocol streams them, so the app
does not imitate them. It does show a histogram and a highlight-clipping warning computed from the real
Live View frame.

## Hardware compatibility test suite

Run on each body + firmware, then record the result in `CompatibilityDatabase` (`EvidenceLevel.HARDWARE_VERIFIED`).

1. Update the camera to the latest firmware. Note model, firmware, connection type.
2. Connect (Wi-Fi discovery, manual IP, QR, or USB "PC Remote"). Expect: model + firmware shown correctly.
3. Export **Diagnostics → Capability report**. Compare with the expectations above.
4. Live View: frames appear, fps > 5, latency chip updates; cover the lens → image goes dark (proves real frames).
5. Capture: one still, then 10 in burst via intervalometer at 2 s. All files on card.
6. For every exposure control shown: change value on phone → camera LCD matches; change on camera → app updates within 2 s.
7. Movie: start/stop 3×; app recording state matches camera.
8. Focus: half-press AF, touch AF (if shown), near/far (if shown).
9. Zoom (if shown) in/out.
10. Pull the Wi-Fi/USB link for 10 s and restore → automatic reconnect.
11. Unsupported features must be hidden, not erroring.
12. Media (if shown): list, thumbnail, download one JPEG and one movie, share.

Record pass/fail per step with the capability report attached.
