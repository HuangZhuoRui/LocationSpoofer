# LocationSpoofer

> 💬 **[Join the Telegram discussion group](https://t.me/+GYuDlpQLzRc3OTc1)** · Discuss testing needs, report issues, follow development updates, or just chat.

An Android project for location, route, and environmental data simulation, now transitioning to an Android emulator for developer testing.

[简体中文](README.md) | [English](README_EN.md)

## Project direction and development update

We previously archived LocationSpoofer because of compliance risks and cases of misuse that departed from its original purpose of development and testing.

To reduce safety risks while continuing to help developers test their own software, we have decided to change direction: **we are building an Android emulator for developers, providing location, route, and device-environment simulation in a controlled testing environment.**

The new tool will provide these capabilities through the Android system we maintain, for development and debugging of applications you own or are explicitly authorized to test. It is not intended for cracking third-party software or bypassing anti-cheat systems.

### Supported and planned platforms

- **macOS (Apple Silicon, M-series chips)**: The primary development and validation platform, with a native client.
- **Windows (Intel / AMD x86_64)**: Support is planned. Work on the system and ARM64 application compatibility is underway; the client and Windows environment still require validation.
- Release plans for Intel Macs and a Linux desktop client have not yet been determined.

Platform availability will be confirmed in release announcements. The first version is not guaranteed to launch on all platforms simultaneously.

### Main features

The new tool is based on Android 17. Features already implemented or being refined include:

- **Application debugging**: APK installation and launch, automatic package-name detection, Logcat viewing, per-application filtering, and search.
- **Location and route simulation**: Setting locations, route editing, GPX import, and route playback.
- **Device-environment simulation**: Sensors, network bandwidth and latency, Wi-Fi, Bluetooth BLE, and NFC testing.
- **Custom device profiles**: Configurable model, brand, serial number, IMEI, resolution, and other testing parameters.
- **Fingerprint authentication debugging**: Virtual fingerprints and a bridge between macOS Touch ID and Android test authentication flows.
- **Test scenario management**: Saving, importing, exporting, and repeating test scenarios.
- **Accounts and memberships**: Account sign-in, membership authorization, and online loading of advanced testing modules.

Some features depend on the accompanying system, host platform, or an active membership. Refer to the release notes for the exact scope. Simulation is intended for development and testing and is not equivalent to physical phone hardware. Touch ID bridging and software security simulation do not give Android genuine hardware-backed TEE or StrongBox capabilities.

### Expected release timeframe

**We currently expect to release the first development-testing version before November 2026, aiming for the end of October.**

We are refining features and validating stability, security, and compatibility. The schedule may change if testing reveals issues that need to be resolved. Updates will be shared in this repository and the Telegram group.

We plan to publish developer-tool builds through this repository's [Releases](https://github.com/HuangZhuoRui/LocationSpoofer/releases), with compliance and safety as design goals and subject to the relevant testing and review.

### Discussion and feedback

Join our new Telegram group to discuss development and testing needs, follow project updates, or simply chat:

👉 **[Join the Telegram discussion group](https://t.me/+GYuDlpQLzRc3OTc1)**

You can report problems, bugs, and feature requests through this repository's [Issues](https://github.com/HuangZhuoRui/LocationSpoofer/issues) or directly in the Telegram group.

When reporting an issue, please include the software version, operating system, reproduction steps, and relevant screenshots or logs. Remove account details, tokens, and sensitive personal information before sharing.

**The reopened repository will be used for updates and future releases of the new project. The legacy LocationSpoofer application will no longer receive maintenance or compatibility updates.**

---

Historical project information and usage guidance are retained below.

## About the project

Historical versions of this project contain implementations for location data simulation, route playback, wireless environment data collection, and sensor data simulation. Some features involve Root privileges and system interface hooking through LSPosed/Xposed.

These capabilities can be used with applications you own or in test environments for which you have explicit authorization, for example:

- Testing location and coordinate transformations.
- Route playback and application compatibility testing.
- Testing wireless environment and sensor interfaces.
- Research into Android system interfaces and inter-process communication.

Simulated data does not represent the device's actual location, actual physical activity, or actual wireless environment. It should not be used as evidence of attendance, athletic performance, or other real-world activity.

## Safety and use guidance

Before use, ensure that you have the required authorization to operate the device and the system being tested, and comply with applicable laws and relevant service agreements.

Do not use this project for false attendance, falsifying assessment or exercise records, fraud, or unauthorized interference with third-party services.

Root access and system-level hooks can affect system stability, application compatibility, and data security. Use a dedicated test device where possible and back up important data beforehand.

These are lawful-use and safety notices. They do not add to or replace the permissions and conditions of the open-source license.

## Data and privacy

Some historical features may access device location, Wi-Fi, cellular, Bluetooth, and sensor information. Refer to the code and configuration of the relevant version for its actual data processing behavior.

When using or developing the project further, clearly disclose the purposes and scope of processing, retention periods, and recipients; obtain the necessary authorization under applicable law; and provide appropriate data management and deletion mechanisms.

Do not submit account passwords, access tokens, private keys, precise personal location histories, or other sensitive information in public issues, logs, or code.

## Third-party services and components

Map, search, and wireless environment data services are subject to their respective licenses, terms of service, and applicable laws.

This project's open-source license does not grant equivalent rights to third-party data, APIs, trademarks, or map content. Check the relevant permissions before accessing, caching, displaying, or redistributing such content, and retain required copyright notices and attribution.

## License

This project is provided under GNU GPL version 3.0 or later, as declared in [LICENSE](LICENSE).

Third-party components retain the copyrights and licenses of their respective rights holders. Follow the applicable licenses and retain relevant notices when copying, modifying, or distributing them.

The project is provided under the applicable license, without warranties beyond those required by that license. This notice does not exclude liability that cannot lawfully be excluded.

Copyright © 2026 SuseOAA
