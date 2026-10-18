# LocationSpoofer

Historical project information for an Android project for location, route, and environmental data simulation.

> **Repository status: archived and no longer maintained.**
>
> This GitHub repository retains project documentation and license notices only. It no longer hosts source code or release packages, publishes new versions, accepts feature requests, or provides system adaptation support. Compatibility depends on the actual behavior of each historical version.

[简体中文](README.md) | [English](README_EN.md)

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
