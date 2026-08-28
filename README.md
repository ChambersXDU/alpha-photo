# Alpha Photo

Alpha Photo is a small Android app for effortless wireless photo access from Sony cameras.

The prototype is intentionally narrow:

- Camera: Sony a7C II (ILCE-7CM2), firmware 2.0.1
- Phone: Xiaomi 15, Android 16
- Transport: wireless only
- No USB fallback
- No remote shooting controls in the first prototype

## Product goal

The app should make camera photos feel immediately available on the phone:

1. Turn on the camera.
2. The phone notices it.
3. Wi-Fi comes up without a manual connection ritual.
4. Open the app and browse photos.
5. Export the exact file format the user chooses.

RAW conversion and JPEG generation are later parts of the same flow, not separate user workflows.

## First milestone

The first technical milestone is the wireless path:

1. Detect the paired camera over Bluetooth LE.
2. Establish the camera Wi-Fi connection without asking the user to navigate camera menus.
3. Open a PTP/IP session.
4. List camera objects and fetch a preview.
5. Download the selected original file.

No UI work should hide uncertainty in the wireless protocol. We will validate the real camera behavior first.

## Validated RAW fixture

The repository contains a real a7C II Lossless Compressed RAW L fixture at
`assets/ARW-Lossless-L.ARW`.

GitHub Actions inspection verified:

- ARW size: 38,547,456 bytes
- RAW dimensions: 7008 × 4672
- Embedded thumbnail: 160 × 120, 6,789 bytes
- Embedded preview: 1616 × 1080, 172,776 bytes
- Embedded `JpgFromRaw`: 7008 × 4672, 1,708,751 bytes
- `JpgFromRaw` starts at byte 372,736 and ends at byte 2,081,487

For this fixture, a full-resolution camera-rendered JPEG is therefore available within
roughly the first 2.1 MB of a 38.5 MB ARW. If the real camera supports the required PTP
partial-object read, this can provide a high-resolution detail preview without downloading
the complete RAW file. That transport behavior still needs to be verified on the camera.

## Build

Requirements:

- JDK 17
- Android SDK 36
- Android Studio with Android SDK tooling
- A physical Android 16 device for the current prototype

Build:

```sh
./gradlew :app:assembleDebug
```

Install:

```sh
./gradlew :app:installDebug
```
