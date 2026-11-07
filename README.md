# Awesome Edition

面向 Android 应用开发与测试的两条产品线：手机端 **LocationSpoofer** 和桌面模拟器 **AwesomeDroid**。

[English](README_EN.md) · [下载与版本](https://github.com/HuangZhuoRui/LocationSpoofer/releases/latest) · [申请测试权限](https://apply.awesomeedition.com/) · [Telegram 交流群](https://t.me/+GYuDlpQLzRc3OTc1)

## 下载

### 手机端 · LocationSpoofer

| 版本 | 架构 | 安装包 |
| --- | --- | --- |
| 全局 | 32 位 ARM | [下载 ARM32 安装包](https://github.com/HuangZhuoRui/LocationSpoofer/releases/download/v3.1.6/LocationSpoofer-v3.1.6-global-32bit-armeabi-v7a.apk) |
| 全局 | 64 位 ARM | [下载 ARM64 安装包](https://github.com/HuangZhuoRui/LocationSpoofer/releases/download/v3.1.6/LocationSpoofer-v3.1.6-global-64bit-arm64-v8a.apk) |

### 模拟器 · AwesomeDroid

| 平台 | 架构 | 安装包 |
| --- | --- | --- |
| Windows | x64（x86_64） | [下载 AwesomeDroid 0.2.28](https://github.com/HuangZhuoRui/LocationSpoofer/releases/download/v3.1.6/AwesomeDroid-0.2.28-Windows-x64-Setup.exe) |
| macOS | ARM64 | 开发中 |

## 手机端：LocationSpoofer

LocationSpoofer 在用户授权的 Android 设备上提供位置、路线及设备环境测试能力。当前只维护全局方案，实际支持情况取决于系统、Root 方案及框架兼容性。

### 开始使用

1. 在客户端注册并登录账号，注册邮箱为选填。
2. 打开“申请测试包名”，从应用列表选择需要测试的应用，填写测试用途。支持多选应用，共用一份用途说明；也可以使用 [申请网站](https://apply.awesomeedition.com/)。
3. 每个账号最多保留 **10 个已批准或待审核的包名**，提交后会尽快审核；客户端可查看申请状态与审核结果。
4. 申请通过后，在生效应用列表中选择需要模拟的应用。已申请的包名支持删除，以释放名额。

### 功能与权限

- **地图与搜索**：符合手机免费使用条件、已通过审核的账号可使用 OSM 地图与地点搜索；Google 平面图、卫星图、搜索、逆地理编码和道路规划需有效会员。
- **位置与路线**：支持地图选点、收藏、定点模拟、路线模拟、路线录制、手绘路线和路线导入。循环路线从终点接回起点后继续，转弯采用平滑衔接。
- **环境数据**：自己采集的 WiFi、基站数据可用于本地模拟；云端 WiFi、基站数据需有效会员。
- **申请与会员**：已批准包名不因会员到期而失效。到期后仍可登录、检查更新及续费；符合条件的手机账号继续使用免费功能。
- **设备限制**：模拟器及无 TEE／直接登录模式仍需有效会员才能使用地图、模拟等功能。具体状态以客户端显示为准。

地图、传感器及其他系统能力的可用范围以设备实际运行情况为准。Google 服务需要网络连接。

### 安装与更新

64 位 Android 设备优先选择 ARM64，支持的 32 位设备选择 ARM32。使用全局方案需要相应的 Root 和框架支持，请按客户端提示配置推荐作用域。

更新时保留数据覆盖安装，并重启设备加载模块。旧非全局版本保留兼容更新入口，更新后的实现统一为全局方案。客户端更新页面显示最新版更新日志；有新版时提供下载，已是最新版时可继续检查更新。

## Windows 模拟器：AwesomeDroid

AwesomeDroid 是独立的 Android 开发与测试模拟器，提供配套 Android 17 系统，以及应用安装与调试、位置和路线等测试能力。

Windows **0.2.28** 提供完整 x64 安装包，包含客户端、Emulator、ADB、运行依赖和系统镜像，无需另行下载镜像。

1. 下载并双击 Windows 安装程序，按提示完成安装。
2. 缺少 WebView2 时，安装器会联网补装。
3. 启动后按客户端提示登录或注册，按账号权益使用对应功能。

设备和应用数据保存在独立目录中，安装和更新不会主动清空该目录。登录就绪后自动检查更新，发现新版会显示版本号和更新日志；下载与安装需用户确认。

**macOS ARM64** 版本继续开发，公开下载时间以本仓库和 Telegram 公告为准。本次不提供 macOS 安装包。

## 安全使用与免责声明

本项目面向自有应用或已获得明确授权的开发、调试和测试。模拟结果不代表真实位置、运动或无线环境，不应作为实际到场、考勤或运动成绩的证明。

申请时须如实填写待测试的包名和用途。**任何涉嫌诈骗的应用包名或申请动机均不予通过**；如发现已通过的申请涉及此类情况，将撤销相应测试权限。用户应确保其用途合法，并自行承担使用本项目及测试数据的责任；本项目不支持、不授权任何欺诈或其他违法用途。

Root 和系统级接口可能影响设备稳定性与数据安全，请使用有权操作的设备并备份重要数据。地图及第三方服务受其各自的条款和许可证约束；展示时请保留相应的来源与署名。

使用相关功能时，服务可能处理账号、设备标识及用户选择的测试数据。反馈问题时请勿公开密码、访问令牌、私钥或个人轨迹。

## 许可与第三方组件

本仓库 LocationSpoofer 代码按 [LICENSE](LICENSE) 中声明的 GNU GPL v3.0 或更新版本提供。AwesomeDroid 及其随附组件以各自的许可声明为准；第三方组件的版权和许可按各自声明执行。

Copyright © 2026 AwesomeEdition.com
