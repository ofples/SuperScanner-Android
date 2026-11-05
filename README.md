# SuperScanner

**Source snapshot:** this README describes the source checked into this repository. A distributed build can differ; use the source tag associated with a specific release.

SuperScanner is an independent Android companion for working with Supernote notebook pages. The app can display a clearly labeled offline sample or connect to a Supernote on the same local Wi-Fi network for live screen mirroring. Users can optionally send selected pages and recognition instructions to OpenAI, then edit, copy, save, or share the result.

## Features

- Try an offline sample made from fictional pages and a prepared transcript. The sample is isolated from live scans and does not contact OpenAI or a device.
- Capture pages from a Supernote screen mirror, open a page, pinch to zoom, and drag to pan. Keep an editable transcript locally.
- Choose between Sign in with ChatGPT plan usage or a user-provided OpenAI API key. Each provider is optional and follows its own account, limit, billing, and data policies.
- Before first use of live recognition, review the **Send pages to OpenAI?** disclosure. Recognition sends selected page images and instructions to OpenAI over HTTPS only after consent.
- Save or share images and text using Android's explicit system actions.
- Experimental Supernote Linking supports clipboard text and keyboard input on the local network.

SuperScanner is an independent project, not affiliated with or endorsed by Ratta, Supernote, or OpenAI. Their names and marks belong to their respective owners.

## Privacy and trust boundaries

The app does not create a SuperScanner account or run a server for scan content. It stores captures, editable text, and settings in app-private storage. The OpenAI API key and ChatGPT session credentials are encrypted locally with Android Keystore-backed encryption. The app contains no advertising, analytics, or tracking SDKs. Clearing app storage or uninstalling removes the app-private copy; it does not remove Gallery/share/clipboard copies or data already sent to OpenAI.

Recognition is optional. Selected images and recognition instructions are sent to the selected OpenAI service only after the in-app confirmation. OpenAI's retention and account settings apply; API usage is billed separately from ChatGPT subscriptions. The app sets the Responses API `store` option to false, which is not a zero-retention guarantee.

Live mirroring uses the Supernote's local-network HTTP/socket protocol without HTTPS. Supernote Linking also transfers clipboard text and keyboard events without transport encryption. Use these features only on a trusted network and with a device you recognize.

See the [privacy policy](docs/privacy.html) and its links to provider privacy and data-control information.

## Build

Requirements for the checked-in app source: JDK 17, Android SDK Platform 35 and 36, and Android Build Tools 36.0.0. The app supports Android 8.0 (API 26) and newer and targets Android 16 (API 36). For the signed-release verification scripts, install the Android NDK component that provides `readelf`; contributors do not need signing credentials for a debug build.

On Windows PowerShell:

```powershell
.\gradlew.bat :app:assembleDebug
```

On Linux or macOS:

```sh
./gradlew :app:assembleDebug
```

The app's Android module is `app/`. Shared local-protocol code is in `nativelink/` and `clipboardbridge/`. `companion/` is a separate legacy Supernote clipboard-helper application; it is not a dependency of or required by the main SuperScanner app.

Debug builds do not need API credentials. Live OCR requires a user-controlled OpenAI provider credential and may incur OpenAI usage charges.

## Contributing and licensing

This project is released under the [MIT License](LICENSE). Contributions should use focused pull requests and include relevant tests. Never commit API keys, OAuth tokens, personal captures, recognized text, local network addresses, signing keys, or generated build artifacts.

## Contact

For privacy or support questions, email [plesserofer@gmail.com](mailto:plesserofer@gmail.com).
