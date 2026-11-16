# Highland Mirror

One Android app for phone mirroring, music and touchscreen control on a CFMOTO U10 Pro Highland over wireless CarPlay. Add and arrange your apps on the Highland home page.

[Download preview 8](https://github.com/blazinsheath/slipstream-highland/releases/download/v0.1.0-highland-mirror-preview.8/Highland-Mirror-0.1.0-preview.8.apk) · [Setup and support](docs/HIGHLAND-MIRROR.md)

Preview 8 fixes reconnection attempts selecting headphones instead of CFMOTO and repeating Bluetooth reset instructions. The app remembers the working vehicle, recognizes Bluetooth being turned back on, and limits automatic resets across retries. Guided setup, audio, touch and the customizable home page are retained.

All 95 automated tests passed. The updated Stop → Start behavior still needs a physical vehicle test.

Install updates over the existing app to keep pairing and shortcuts. Android requires one-time Wireless debugging authorization and screen-sharing approval. Keep the phone unlocked with the screen on. Protected Netflix video and cellular-call audio are unsupported.

Source and dependency attribution are in [open-source notices](carjack-android/app/mirror/assets/licenses/NOTICE.txt), [Apache license](LICENSE-APACHE) and [MIT license](LICENSE-MIT). Original source history and notices are retained.
