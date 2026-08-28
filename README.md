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
