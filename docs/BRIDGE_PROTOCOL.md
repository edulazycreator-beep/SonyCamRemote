# SDK bridge protocol (v1)

The official Camera Remote SDK only runs on Windows, macOS and Linux (x86-64 / ARMv7 / ARMv8). To use it from
Android, run a small HTTP server on a host next to the camera (Raspberry Pi 4/5 works) that links against your own
licensed copy of the SDK. SonyCam Remote's `SonyBridgeAdapter` is the client for this API.

**This repository does not contain the SDK or a server binary** — you build the server against the SDK you
downloaded from Sony, under Sony's licence. Anything below is the contract the Android client expects.

Base URL: `http://<host>:<port>/api/v1/cameras/{id}` (`{id}` = the SDK's camera index or serial).
Optional auth: `Authorization: Bearer <token>`. All bodies are JSON (UTF-8).

| Method | Path | Body / response |
|---|---|---|
| POST | `/connect` | `{}` → 200 when the SDK has connected to the camera |
| POST | `/disconnect` | `{}` |
| GET | `/info` | `{"model":"ILCE-7M4","firmware":"3.01","serial":"…","sdkVersion":"2.x"}` |
| GET | `/state` | see below |
| POST | `/property` | `{"id":"ISO","token":"<opaque>"}` |
| POST | `/command` | `{"name":"takePicture"}` — also `halfPressDown`, `halfPressRelease`, `startRecording`, `stopRecording`, `zoom` (`direction` IN/OUT, `movement` START/STOP/ONE_SHOT), `touchFocus` (`x`,`y` 0..1), `cancelTouchFocus`, `focusStep` (`steps`) |
| GET | `/liveview.jpg[?size=large]` | `image/jpeg` of the latest real Live View frame |
| GET | `/media?offset=0&count=50` | `[{"id":"…","fileName":"DSC0001.JPG","kind":"still","createdAt":"…","size":123}]` |
| GET | `/media/{mediaId}/file` | original file bytes |
| GET | `/media/{mediaId}/thumbnail` | JPEG thumbnail |

`/state` response:

```json
{
  "capabilities": ["LIVE_VIEW", "STILL_CAPTURE", "ISO", "SHUTTER_SPEED"],
  "properties": {
    "ISO": {"current": {"token": "800", "label": "ISO 800"},
            "options": [{"token": "100", "label": "ISO 100"}], "writable": true}
  },
  "recording": false,
  "batteryPercent": 82,
  "storagePresent": true,
  "remainingShots": 1450,
  "remainingRecordSeconds": 7200,
  "overheating": false
}
```

`capabilities` uses the names of `com.sonycamremote.engine.model.Capability`; property keys use
`PropertyId`. Report a capability **only** when the SDK says the camera supports it — the app shows exactly
what the bridge reports. Error responses: non-2xx with a short text body; the app displays it.

Security: bind the server to the local network only, set a token, and do not expose it to the internet.
