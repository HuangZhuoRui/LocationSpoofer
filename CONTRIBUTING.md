[简体中文](CONTRIBUTING.md) | [English](CONTRIBUTING_EN.md)

---

# LocationSpoofer 贡献指南

感谢你关注并愿意为 LocationSpoofer 做出贡献！🎉

来自开源社区的每一份贡献都能帮助 LocationSpoofer 变得更加稳定、高效与可靠。

---

## 📑 目录

1. [行为准则](#行为准则)
2. [我能如何做出贡献？](#我能如何做出贡献)
   * [反馈缺陷 (Bug)](#反馈缺陷-bug)
   * [提出新功能建议](#提出新功能建议)
   * [提交代码 (Pull Request)](#提交代码-pull-request)
3. [本地开发与编译](#本地开发与编译)
4. [项目架构与开发规范](#项目架构与开发规范)
   * [编码规范](#编码规范)
5. [Git 提交信息规范 (Commit Conventions)](#git-提交信息规范-commit-conventions)

---

## 行为准则

本项目及所有参与者均受 [LocationSpoofer 行为准则](CODE_OF_CONDUCT.md) 的约束。参与项目即表示你同意遵守该准则。若发现违规行为，请及时联系项目维护者。

---

## 我能如何做出贡献？

### 反馈缺陷 (Bug)

在提交 Issue 前，请确认：
* 检索 [已有的 Issues](https://github.com/your-username/LocationSpoofer/issues) 确保问题未被重复汇报。
* 确保运行在支持的基础环境中（**Android 8.0+**、**KernelSU / APatch / Magisk**、**LSPosed API 101+**）。

通过 **缺陷报告模板** 提交问题时，请尽可能提供详细信息：
* **设备与环境**：Android 系统版本、机型 / ROM（如 HyperOS、LineageOS）、Root 方案（KernelSU/APatch/Magisk 及版本）、LSPosed 框架版本。
* **目标应用**：发生问题的目标应用名称与版本号。
* **复现步骤**：清晰的步骤说明。
* **日志与现象**：相关 Logcat 错误日志或 LSPosed 模块运行日志（特别是崩溃堆栈或异常回退坐标）。

### 提出新功能建议

非常欢迎提出各种功能建议！通过 **功能建议模板** 提交时，请阐述：
* 你目前遇到的痛点或现有功能的局限性。
* 你期望的实现方案与具体行为。
* 可能的边缘场景或兼容性考虑。

### 提交代码 (Pull Request)

1. **Fork 本仓库**，并从 `main` 分支切出你的特性分支：
   ```bash
   git checkout -b feat/your-feature-name
   ```
2. **编写代码**，严格遵循 Kotlin / Jetpack Compose / MVVM 编码风格与架构规范。
3. **本地编译验证**：
   ```bash
   ./gradlew assembleDebug
   ```
4. **提交代码**，遵循标准 Commit 格式（详见 [Git 提交规范](#git-提交信息规范-commit-conventions)）。
5. **推送到你的 Fork 仓库**，并在 GitHub 发起面向 `main` 分支的 Pull Request。
6. 按照 PR 模板详细填写变更说明并完成自检清单。

---

## 本地开发与编译

### 前置环境
* **Android Studio**：Android Studio Hedgehog / Iguana / Jellyfish 或更新版本。
* **JDK**：OpenJDK 17 或 OpenJDK 21。
* **Android SDK**：compileSdk `37`（minSdk `26`），Build Tools 版本由 AGP 自动匹配，无需手动指定。
* **测试设备**：一台已 Root 并安装 **KernelSU / APatch / Magisk** 及 **LSPosed (API 101+)** 的实体测试机。

### 编译构建
```bash
# 克隆仓库
git clone https://github.com/your-username/LocationSpoofer.git

# 进入目录
cd LocationSpoofer

# 编译 Debug APK
./gradlew assembleDebug

# 直接安装到已连接的设备
./gradlew installDebug
```

---

## 项目架构与开发规范

LocationSpoofer 基于 **MVVM + Clean Architecture** 构建，代码按职责拆分为 6 个 Gradle 模块：

| 模块 | 类型 | 职责 |
|---|---|---|
| `app` | Android App | 宿主壳工程：`Application` / `MainActivity`、签名打包配置、聚合各模块 Koin DI；仅打包 `xposed` 模块产物供 LSPosed 扫描，自身不直接调用其代码 |
| `app-ui` | Android Library | 全部 Compose UI（页面/弹窗/`ui/liquid` 组件）与 ViewModel 层 |
| `service` | Android Library | 前台保活服务、悬浮摇杆服务、开机自启广播等 |
| `xposed` | Android Library | LSPosed/Xposed 注入模块本体，`hooks/`、`hooks/network/` 下的各 Hook 实现 |
| `core-data` | Android Library | `app`/`app-ui`/`service` 三端共用的数据与业务层：Room 数据库、Repository、`ConfigManager`/`RootManager`/`EnvironmentScanner` 等工具类 |
| `core-geo` | 纯 Kotlin/JVM | 唯一不依赖 Android 的模块，坐标系换算 |

新增代码前请先想清楚该放进哪个模块：**纯业务逻辑/持久化**放 `core-data`；**只和 Compose UI 相关**放 `app-ui`；**Hook 实现**只放 `xposed`（且只能依赖 `core-geo`，不能反向依赖 `app-ui`/`core-data`，否则会把 Room/Compose 等重量级依赖一起打进目标 App 进程）。

* **语言**：100% Kotlin，使用 Coroutines 与 StateFlow 处理异步流。
* **UI 交互**：Jetpack Compose、Material Design 3，叠加第三方 [Miuix](https://github.com/miuix-kmp/miuix)（`top.yukonga.miuix.kmp`）提供的模糊/毛玻璃底层能力；`app-ui/ui/liquid` 包在此基础上实现了"液态玻璃"悬浮底栏、透镜与阻尼拖拽效果。新增基础组件优先复用 Miuix 提供的控件，而不是重新造轮子。
* **依赖注入**：Koin。按模块拆分为 `coreDataModule`（`core-data`）、`serviceModule`（`service`）、`viewModelModule`（`app-ui`），由 `app` 模块的 `appModules`（`List<Module>`）统一聚合并在 `LocationApp.onCreate()` 中 `startKoin`。新增可注入类型时，在对应模块的 `di/XxxModule.kt` 里补充绑定，不要绕开 DI 手动 `new`。
* **本地存储**：Room Database（SQLite，位于 `core-data`），涉及空间查询的部分需配备空间索引优化。
* **Xposed Hook 核心层**：
  * 位于 `xposed` 模块的 `com.vincenthzr.locationspoofer.xposed` 包，入口类 `LocationHooker`；具体 Hook 实现按类型拆分在 `hooks/`（定位/GNSS/地图 SDK/计步/反检测）与 `hooks/network/`（Wi-Fi/基站/蓝牙/连接状态）两个子包，新增 Hook 时优先归类到已有子包，而不是堆到 `xposed` 根包或 `LocationHooker.kt` 里。
  * 严格遵循 **LSPosed API 101+ / libxposed (Service 模式)** 规范。
  * 高频 Hook 线程 0-IO 原则：`LocationHooker` 内置后台守护线程按调用方 UID 轮询多份配置文件路径（默认 1000ms，读取失败时退避到 10s/60s）写入内存缓存，Hook 方法只从内存直读，不做任何同步 IO。
  * 跨进程配置传递不使用 `ContentProvider`（Android 11+ 包可见性下会卡死主线程），而是由 `core-data` 的 `ConfigManager` 以 Root 权限把 JSON 配置同时写入 `/data/local/tmp/`、`/data/system/`、应用私有目录三份路径，权限收紧为 `644`，并由 `RootManager` 动态注入专属 SELinux 类型（而非笼统的 `shell_data_file`）按需授权，不要为了图省事退回到 `777` 或复用通用 SELinux 类型。
  * MultiDex 兼容安全性：通过动态 ClassLoader 拦截定位组件，并通过 `/proc/self/cmdline` 锁定宿主进程主包名，避免插件或内嵌 Webview 破坏全局上下文。
  * 保持调用栈深度清洗，避免暴露 Xposed 检查痕迹。

### 编码规范

* **ViewModel 组织方式**：只有 `app-ui` 里的 `MainViewModel` 这个承载主界面全部状态的"大 ViewModel"才按功能拆成多个文件（`MainViewModel.kt` 只放字段与构造，行为全部以 `internal fun MainViewModel.xxx()` 扩展函数的形式分散在 `MainViewModelSpoofing.kt`/`MainViewModelDataIO.kt`/`MainViewModelLocation.kt`/`MainViewModelRoute.kt`/`MainViewModelSettings.kt` 里）。这是 `MainViewModel` 专属的组织方式，**不是**项目通用约定——像 `ManageDataViewModel`、`UpdateViewModel` 这种职责单一的 ViewModel 应该保持单文件、方法作为类成员，不要生搬硬套扩展函数拆分。往 `MainViewModel` 加新逻辑时，按"这属于模拟开关/数据导入导出/定位获取/路线规划/设置"里的哪一类，加进对应的 `MainViewModelXxx.kt`，而不是塞进 `MainViewModel.kt` 本体。
* **注释风格**：项目里的注释绝大多数是中文，且以解释"为什么这么写"（隐藏约束、踩过的坑、看似多余实则必要的判断）为主，而不是复述代码在做什么。例如解释为什么用某个 SELinux 属性而不是逐个枚举、为什么某个判断顺序不能颠倒、为什么用高斯噪声而不是确定性正弦波。新增注释时按这个标准自问：如果删掉这行注释，读者会不会因为看不出背后的原因而在后续修改时踩坑？会才写，纯复述代码在做什么的注释不要加。
* **`app-ui/ui/` 包结构**：`components/`（跨页面复用的弹窗与控件）、`components/map/`（各地图引擎适配器）、`liquid/`（自研液态玻璃组件）、`theme/`（配色与主题）、`screen/`（各个独立页面）；页面自身逻辑复杂时在 `screen/` 下按功能开子包（如 `screen/managedata/`、`screen/settings/`、`screen/tabs/`），把该页面专属的子组件、对话框、UI State 都收进对应子包，不要平铺在 `screen/` 根目录。
* **静态检查**：目前项目**没有配置** ktlint / detekt 等静态检查工具，代码风格仅靠人工 Review 把关，请提交前自行对照本文档与现有代码风格检查，而不是等 CI 报错。
* **多语言资源**：`app-ui` 与 `service` 下的 `res/values`（默认）实际是**英文**字符串，`values-zh` 才是中文翻译，`values-ar` 是阿拉伯语翻译（目前落后于前两者，缺失的字符串会自动回退到默认英文）。新增界面文案时，请先在 `values/strings.xml` 加英文原文，再补 `values-zh/strings.xml` 的中文翻译；只改中文而漏改默认英文资源是常见疏漏，请特别注意。

---

## Git 提交信息规范 (Commit Conventions)

我们遵循 [Conventional Commits](https://www.conventionalcommits.org/zh-hans/) 提交规范：

```
<type>(<scope>): <subject>
```

### 常用 Type 类型：
* `feat`: 新增功能
* `fix`: 修复缺陷
* `docs`: 文档变更
* `style`: 代码格式调整（不影响代码逻辑）
* `refactor`: 代码重构（既不修复 bug 也不添加特性的代码变更）
* `perf`: 性能优化
* `test`: 测试用例相关
* `chore`: 构建流程、依赖更新或辅助工具变动

### 提交范例：
```
feat(hook): 增加对次级 MultiDex 动态定位监听器的挂钩支持
fix(coords): 修复百度地图渲染图层坐标系偏移问题
docs: 更新 README 中关于 LSPosed API 101+ 的规范描述
```

---

再次感谢你对 LocationSpoofer 开源社区的支持与贡献！
