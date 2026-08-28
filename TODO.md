# TODO

## Setup

- [x] Bootstrap the Android project
- [x] Add repository development rules
- [x] Verify the local Android development environment
- [x] Build and install the app on Xiaomi 15

## Spike A — Wireless connection

- [ ] Detect Sony a7C II over BLE
- [ ] Inspect BLE advertisements, services, and characteristics
- [ ] Reproduce the Sony BLE-to-Wi-Fi bootstrap
- [ ] Join the camera Wi-Fi with Android networking APIs
- [ ] Bind camera traffic to the camera network
- [ ] Establish a PTP/IP session
- [ ] Verify reconnect after camera sleep, app switching, and screen lock

## Spike B — PTP photo pipeline

- [ ] List photo objects on the camera
- [ ] Fetch PTP thumbnails
- [ ] Fetch the best available embedded preview
- [ ] Detect newly captured photos
- [ ] Stream original ARW and JPEG files directly to storage
- [ ] Report transfer progress and support cancellation
- [ ] Persist active transfers and resume after reconnect

## Spike C — RAW experience

- [ ] Inspect embedded previews from real a7C II RAW files
- [ ] Test Compressed RAW
- [ ] Test Lossless Compressed RAW L
- [ ] Test Lossless Compressed RAW M
- [ ] Test Lossless Compressed RAW S
- [ ] Test Uncompressed RAW
- [ ] Verify whether embedded previews are sufficient for focus checking
- [ ] Verify current Snapseed can open the original ARW directly
- [ ] Open downloaded ARW in Snapseed with a content URI
- [ ] Support standard Android sharing for downloaded originals

## V1 app

- [ ] Keep camera connection ownership outside the UI lifecycle
- [ ] Add the connected-device foreground service
- [ ] Add Room-backed photo and transfer state
- [ ] Make the home screen a photo grid
- [ ] Add a simple photo detail viewer with zoom
- [ ] Show explicit file type, dimensions, and size before export
- [ ] Export the original ARW without modification
- [ ] Export the original JPEG without modification
- [ ] Keep background connection and transfers working reliably
- [ ] Automatically reconnect without repeating the full setup flow
- [ ] Keep the release package under 30 MB if practical
- [ ] Test the complete workflow on Sony a7C II firmware 2.0.1 and Xiaomi 15 / Android 16
