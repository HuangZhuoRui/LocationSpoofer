# Awesome Edition

Two products for Android development and testing: the **LocationSpoofer** Android client and the **AwesomeDroid** desktop emulator.

[简体中文](README.md) · [Downloads and releases](https://github.com/HuangZhuoRui/LocationSpoofer/releases) · [Apply for test access](https://apply.awesomeedition.com/) · [Telegram community](https://t.me/+GYuDlpQLzRc3OTc1)

## Android client: LocationSpoofer

LocationSpoofer provides location, route, and device-environment testing on Android devices with the owner's authorization. The maintained client uses the global scheme; available capabilities depend on the device, system, Root solution, and framework compatibility.

For safety, Android testing requires an account and approval of the target application package names:

1. Create an account in the Android client or on the application website, then submit the target package names and test purpose at the [application website](https://apply.awesomeedition.com/).
2. Each account can have at most **10 approved or pending package names**. Applications will be reviewed as soon as possible after submission.
3. Once approved, use an active membership to test the approved applications. Approval remains in place when membership expires. You can still sign in, check for updates, and renew; maps and simulation features require an active membership.

The Android client includes map selection, route planning and replay, route recording, location and motion simulation, and environment-data collection. Google roadmap, satellite imagery, and place search require an active membership and a network connection. Actual capabilities are subject to the client version and device status.

## Desktop emulator: AwesomeDroid

AwesomeDroid is a separate Android development and testing emulator built around its accompanying Android 17 system. It provides application debugging, location and route testing, sensors, and wireless-environment tools. Each product follows its own version and service documentation.

- **macOS on Apple Silicon**: the main development and validation platform.
- **Windows x86_64**: under development and validation; availability will be announced separately.
- Plans for other desktop platforms will be announced when available.

This Release will provide Android client packages first. Emulator downloads and platform availability will be announced separately.

**Release timing:** AwesomeDroid remains under development and testing. Dates for public testing and general release have not yet been set. The availability and download details for macOS Apple Silicon and Windows will be announced in this repository and on Telegram once confirmed. The Android client Release is not an emulator release.

## Downloads and updates

Android packages will be available through this repository's [Releases](https://github.com/HuangZhuoRui/LocationSpoofer/releases) and the in-app updater. Choose ARM64 for most 64-bit Android devices, or ARM32 for a supported 32-bit device.

A compatibility update remains available for older non-global installations; the installed implementation uses the global scheme. Keep application data when updating, then configure the recommended global framework scope and restart the device.

## Safety, permitted use, and disclaimer

This project is intended for development, debugging, and testing of applications you own or are explicitly authorized to test. Simulated output does not represent actual location, physical activity, or wireless surroundings and must not be used as evidence of attendance, work, or athletic performance.

Provide accurate package names and purposes when applying. **Applications involving package names or intended uses suspected of fraud will be rejected.** If an approved application is later found to involve such concerns, its test access will be revoked. Users are responsible for ensuring their use is lawful and for their use of the project and test data; this project does not support or authorize fraud or other unlawful uses.

Root access and system-level interfaces can affect stability and data security. Use devices you are authorized to operate and back up important data. Maps and third-party services are subject to their respective terms and licenses; retain applicable source attribution.

Relevant features may process account information, device identifiers, and test data selected by the user. Do not post passwords, access tokens, private keys, or personal location histories in issue reports.

## License and source

This project is provided under GNU GPL v3.0 or later as declared in [LICENSE](LICENSE). The Android release will include a corresponding source snapshot without Git history, server secrets, or databases. Third-party components retain their own copyrights and license terms.

Copyright © 2026 AwesomeEdition.com
