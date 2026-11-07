# Awesome Edition

Two products for Android development and testing: the **LocationSpoofer** Android client and the **AwesomeDroid** desktop emulator.

[简体中文](README.md) · [Downloads and releases](https://github.com/HuangZhuoRui/LocationSpoofer/releases/latest) · [Apply for test access](https://apply.awesomeedition.com/) · [Telegram community](https://t.me/+GYuDlpQLzRc3OTc1)

## Downloads

### Android client · LocationSpoofer

| Edition | Architecture | Installer |
| --- | --- | --- |
| Global | 32-bit ARM | [Download ARM32 APK](https://github.com/HuangZhuoRui/LocationSpoofer/releases/download/v3.1.6/LocationSpoofer-v3.1.6-global-32bit-armeabi-v7a.apk) |
| Global | 64-bit ARM | [Download ARM64 APK](https://github.com/HuangZhuoRui/LocationSpoofer/releases/download/v3.1.6/LocationSpoofer-v3.1.6-global-64bit-arm64-v8a.apk) |

### Desktop emulator · AwesomeDroid

| Platform | Architecture | Installer |
| --- | --- | --- |
| Windows | x64 (x86_64) | [Download AwesomeDroid 0.2.28](https://github.com/HuangZhuoRui/LocationSpoofer/releases/download/v3.1.6/AwesomeDroid-0.2.28-Windows-x64-Setup.exe) |
| macOS | ARM64 | In development |

## Android client: LocationSpoofer

LocationSpoofer provides location, route, and device-environment testing on Android devices with the owner's authorization. Only the global scheme is maintained. Compatibility depends on the system, Root solution, and framework.

### Getting started

1. Register and sign in through the client. Email is optional during registration.
2. Open the test-package application page, select the applications to test, and describe your purpose. Multiple applications can share one purpose statement. The [application website](https://apply.awesomeedition.com/) is also available.
3. Each account can have at most **10 approved or pending package names**. Applications will be reviewed as soon as possible; status and review results are available in the client.
4. After approval, select which approved applications should receive simulated data. Registered package names can be removed to free up slots.

### Features and access

- **Maps and search:** approved accounts on eligible phones can use OSM maps and place search for free. Google roadmap, satellite imagery, search, reverse geocoding, and road routing require an active membership.
- **Location and routes:** map selection, favorites, fixed-location simulation, route simulation, physical route recording, freehand drawing, and route import. Looping routes connect the end back to the start and repeat in the original direction, with smooth turns.
- **Environment data:** locally collected WiFi and cell data can be used for local simulation. Cloud WiFi and cell datasets require an active membership.
- **Applications and membership:** approved packages remain approved after membership expires. Users can still sign in, check for updates, and renew; eligible phone accounts retain access to free features.
- **Device restrictions:** emulators and devices using the no-TEE/direct-login mode still require an active membership for maps, simulation, and related features. Check the client for the device's current status.

Map, sensor, and other system capabilities depend on the actual device environment. Google services require a network connection.

### Installation and updates

Choose ARM64 for 64-bit Android devices, or ARM32 for supported 32-bit devices. The global scheme requires compatible Root and framework support; follow the client's recommended scope settings.

Install updates over the existing application without clearing data, then restart the device to load the module. Older non-global clients retain a compatible update path and migrate to the global implementation. The update page displays the latest release notes and offers downloads when a newer version is available. Users already on the latest version can continue checking for updates.

## Windows emulator: AwesomeDroid

AwesomeDroid is a separate Android development and testing emulator with an accompanying Android 17 system and tools for application installation, debugging, location, and route testing.

Windows **0.2.28** is available as a complete x64 installer containing the client, Emulator, ADB, runtime dependencies, and system image. No separate image download is required.

1. Download and run the Windows installer, then follow its instructions.
2. If WebView2 is missing, the installer downloads and installs it.
3. Launch the client and follow the sign-in or registration prompts. Feature access follows the account's entitlements.

Device and application data are stored separately; installation and updates do not intentionally clear that directory. Once signed in and ready, the client checks for updates automatically. New-version prompts show the version and release notes; downloads and installation require confirmation.

The **macOS ARM64** version remains in development. Public download availability will be announced in this repository and on Telegram. No macOS installer is included in this release.

## Safety, permitted use, and disclaimer

This project is intended for development, debugging, and testing of applications you own or are explicitly authorized to test. Simulated output does not represent actual location, physical activity, or wireless surroundings and must not be used as evidence of attendance, work, or athletic performance.

Provide accurate package names and purposes when applying. **Applications involving package names or intended uses suspected of fraud will be rejected.** If an approved application is later found to involve such concerns, its test access will be revoked. Users are responsible for ensuring their use is lawful and for their use of the project and test data; this project does not support or authorize fraud or other unlawful uses.

Root access and system-level interfaces can affect stability and data security. Use devices you are authorized to operate and back up important data. Maps and third-party services are subject to their respective terms and licenses; retain applicable source attribution.

Relevant features may process account information, device identifiers, and test data selected by the user. Do not post passwords, access tokens, private keys, or personal location histories in issue reports.

## License and third-party components

LocationSpoofer code in this repository is provided under GNU GPL v3.0 or later as declared in [LICENSE](LICENSE). AwesomeDroid and its bundled components are subject to their respective license notices. Third-party components retain their own copyrights and license terms.

Copyright © 2026 AwesomeEdition.com
