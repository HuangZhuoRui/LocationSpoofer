# 系统级 Hook 适配指南

本文档写给想要维护、扩展 `xposed` 模块的开发者，尤其是遇到"某个功能在新的 Android 版本 / 新机型 / 新 ROM 上失效了"这类问题时，该如何独立定位到需要 Hook 的系统内部类与方法，而不是每次都靠猜。

---

## 目录

1. [为什么没有"标准 API"可以照抄](#为什么没有标准-api-可以照抄)
2. [五种定位系统内部 API 的方法](#五种定位系统内部-api-的方法)
3. [写 Hook 时的防御性套路（本项目的约定）](#写-hook-时的防御性套路本项目的约定)
4. [OEM 定制 ROM 的特殊坑](#oem-定制-rom-的特殊坑)
5. [完整适配工作流 Checklist](#完整适配工作流-checklist)
6. [参考资料](#参考资料)

---

## 为什么没有"标准 API"可以照抄

本项目 Hook 的对象——`LocationManagerService`、`WifiServiceImpl`、`PhoneInterfaceManager`、`ConnectivityService`、`AppOpsService`、`TelephonyRegistry`……——全部是 **`system_server` 进程内部的实现类**，不是 Android SDK 对外暴露的公共 API。这意味着：

* 它们**没有版本兼容承诺**。Google 每个 Android 大版本都可能重命名、拆分、合并这些类，或者把它们的返回类型从裸的 `List<T>` 换成 `ParceledListSlice<T>`（`getScanResults` 就是活生生的例子，见 [SystemWifiServiceHooker.kt](src/main/java/com/suseoaa/locationspoofer/xposed/hooks/SystemWifiServiceHooker.kt) 里的返回类型反射判断）。
* Android 12 起，部分系统服务（Wi-Fi、Connectivity 等）被搬进了 **APEX 模块**（`com.android.wifi`、`com.android.tethering` 等），运行在独立的 ClassLoader 里，`system_server` 的默认 ClassLoader 根本 `Class.forName` 不到它们。
* **小米 HyperOS/MIUI、ColorOS、EMUI/HarmonyOS(套壳安卓的版本)** 这些定制 ROM，会在 AOSP 实现基础上插入自己的中间层、重写方法逻辑，甚至换掉整个实现类。同一个 Android 13，不同厂商的 `LocationManagerService` 内部字段名可能都不一样。

所以维护这个项目的核心心法是：**永远不要假设某个内部类/方法在下一个系统版本上还长这个样子，要有一套方法论去现场验证，而不是死记硬背当前代码里的类名**。

---

## 五种定位系统内部 API 的方法

遇到"某系统版本/某机型上这个 Hook 不生效了"，按下面顺序排查，通常足够定位到真正需要 Hook 的类和方法。

### 方法一：AOSP 源码比对（最快，优先用）

打开 [cs.android.com](https://cs.android.com/android/platform/superproject) 或 [androidxref.com](http://androidxref.com/)，**先确认目标设备的确切 API Level / Android 版本号**（`adb shell getprop ro.build.version.sdk` 和 `ro.build.version.release`），切到对应的 tag/分支去看源码，而不是看 `master`/最新分支——AOSP `master` 往往比任何在售设备的系统新出好几个版本，直接对着 `master` 抄大概率对不上现网设备。

在源码里搜索目标功能对应的关键字（比如 "getScanResults" "registerLocationListener"），确认：
* 类的完整包名路径（`com.android.server.location.LocationManagerService` 还是 `com.android.server.location.provider.LocationProviderManager`？这两个在不同 Android 版本里都存在过，职责也不一样）；
* 方法签名（参数个数、参数类型、返回类型）；
* 这个方法是否在当前版本里已经被拆分/废弃，逻辑挪去了哪个新类。

### 方法二：从真机拉取实际 framework 反编译

AOSP 公开源码只能代表"官方原版"，**OEM 定制 ROM 的真实实现可能完全不同**，这一步是验证"这台设备到底跑的是什么代码"的关键手段。

```bash
# 找到目标类所在的 jar/apex（framework.jar、services.jar，或某个 apex 模块）
adb shell pm path android
adb pull /system/framework/framework.jar
adb pull /system/framework/services.jar
# Android 12+ 的 Wi-Fi/Connectivity 等模块要去 APEX 里找
adb shell ls /apex | grep -E "wifi|tethering"
adb pull /apex/com.android.wifi/javalib/service-wifi.jar
```

用 [jadx-gui](https://github.com/skylot/jadx) 打开反编译，直接定位到目标类，肉眼确认：
* 类名、方法名、字段名是否和 AOSP 源码一致（OEM 经常会重命名内部字段，或者把一个方法拆成两个）；
* 有没有 OEM 自己加的额外校验逻辑（典型例子：MIUI 在系统定位/网络服务里插入的"位置模拟检测"分支，这是本项目 `hooks/AntiDetectionHooker.kt`、`SystemAppOpsHooker.kt` 存在的直接原因）。

### 方法三：`dumpsys` / `service list` 反查服务真实宿主类

不确定某个系统服务当前具体绑定到哪个类的实例时，用系统自带工具直接问系统本身：

```bash
adb shell service list                 # 列出所有已注册的 Binder 服务名
adb shell dumpsys location             # 定位服务的运行时状态，通常会打印内部 provider/manager 的类名
adb shell dumpsys wifi
adb shell dumpsys connectivity
adb shell dumpsys package android      # 查看 framework 包的版本/签名信息，辅助判断具体 ROM 分支
```

`dumpsys <service>` 的输出里经常直接带类的全限定名或者调用栈片段，比反编译更快。

### 方法四：项目里已有的"多候选名 + ClassLoader 扫描 + addService 拦截"三重兜底

对于 APEX 模块化、或者初始化时机不确定的服务，本项目已经沉淀了一套通用兜底模式，新写 Hook 时应该直接复用这个模式，而不是自己发明新写法。以 [SystemWifiServiceHooker.kt](src/main/java/com/suseoaa/locationspoofer/xposed/hooks/SystemWifiServiceHooker.kt) 里的 `findWifiServiceClass` / `findConnectivityServiceClass` 为例，思路分三层：

1. **直接 `findClassIfExists`**：先假设是最常见的情况，用几个已知候选类名直接尝试加载；
2. **扫描所有活跃线程的 `contextClassLoader`**：APEX 模块的类通常挂在专属 Handler 线程（比如 `WifiHandlerThread`）的 ClassLoader 上，遍历 `Thread.getAllStackTraces().keys`，按线程名关键字筛出可能相关的线程，从它们的 ClassLoader 里再找一次；也可以检查 `com.android.server.LocalServices` 的 `sLocalServiceObjects` 静态字段，里面登记了所有 "LocalService" 的运行时实例，直接从实例反查 `.javaClass.classLoader`；
3. **Hook `ServiceManager.addService`**：如果以上两步都找不到（说明这个服务在当前 Hook 时机还没初始化完成），就 Hook 服务注册的入口方法，等它真正被注册的那一刻再拿到实例并挂载 Hook——这也是为什么 `LocationHooker` 内置了一个后台轮询线程，会反复重试挂载直到成功。

### 方法五：动态插桩验证（无 Root 精确定位调用栈时用）

如果连"这个功能到底走了哪个类"都不确定，可以用 [Frida](https://frida.re/) 对 `system_server` 进程做动态插桩，在猜测的几个候选方法上都打日志，跑一遍目标 App 的定位/联网流程，看哪个方法实际被触发、参数长什么样。这一步成本比反编译更高，只在反编译看不出调用关系（比如接口分派、AIDL 生成代码绕了好几层）时才需要。

---

## 写 Hook 时的防御性套路（本项目的约定）

找到目标类和方法只是第一步，**新系统上方法签名/返回类型的细微差异随时可能让 Hook 直接崩溃或者静默失效**。本项目已经形成了几条约定，新增 Hook 时应遵循：

* **用 `hookAllMethods` 按方法名匹配，而不是按精确签名匹配**：不同 API 级别里同名方法的参数列表经常不一样（比如老版本 `getCellLocation()` 没有参数，新版本可能多了一个 `callingPackage` 参数），按名字匹配 + 在回调里用 `chain.args` 动态适配参数个数，比在编译期写死某个精确重载更抗版本差异。
* **返回值类型用反射动态判断，分支兼容**：参考 `getScanResults` 的写法——先看 `executable.returnType` 是不是 `ParceledListSlice`，不是的话再看真实返回值的运行时类型，两者都不匹配时再退回构造裸 `List`。永远不要假设"这个方法在所有版本上返回类型都一样"。
* **字段读写用"方法优先、反射字段兜底"双保险**：既尝试调用 `setLatitude()` 这类公开 setter，也用 `XposedHelpers.setDoubleField(obj, "mLatitude", ...)` 直接改字段，两者都包一层 `try/catch` 各自独立失败不影响另一条路径——因为不同版本/不同 OEM 对同一个字段可能只留了其中一种访问方式。
* **多候选类名逐个尝试，不要在拿不到类时直接崩**：`findClassIfExists(name1, cl) ?: findClassIfExists(name2, cl) ?: ...`，找不到就 `return` 跳过这个 Hook 点而不是抛异常——保证一个 Hook 点适配失败不会拖垮整个模块在这台设备上的所有其他 Hook。
* **每个关键分支都要打日志**：本项目所有 Hook 都遵循 `[SysXxx] 描述性文本` 的日志前缀约定（`XposedBridge.log(...)`），包括"找到了类""挂载成功""挂载失败原因""这次调用命中/未命中目标应用"。真机上出问题时通常没法挂调试器，**日志是唯一的排障手段**，新 Hook 如果不打日志，出问题了基本没法远程排查用户反馈的日志。

---

## 按厂商 / 系统版本分包：vendor 适配框架

上面讲的都是"怎么找到目标类"，找到之后**别把各家的类名/差异堆进同一个 Hook 函数里**——项目已经内置了一套适配框架，位于 [`hooks/vendor/`](src/main/java/com/suseoaa/locationspoofer/xposed/hooks/vendor/)，专门收纳这些差异。

适配粒度只分两层：**厂商**（是不是小米/HyperOS、OPPO/ColorOS……）和**系统大版本**（同一厂商内跨大版本更新，比如 HyperOS 3 升到 HyperOS 4）。不按具体机型（市场型号）分——同厂商子品牌/旗舰机型通常共用同一套系统，差异很小，没必要为每个机型单开文件。

核心用法一句话：共享 Hook 代码在查找系统服务类时先问 `VendorRegistry.resolveClass(组件, classLoader)`，未命中再回落自己原有逻辑；各厂商的类名候选/定制逻辑写在 `vendor/profiles/` 下各自的 `object` 里，某厂商内需要跨版本区分时再在 `vendor/profiles/versions/` 下加一个继承 `SystemVersionVendor` 的适配器覆盖差异部分。新增一个厂商或版本只需加一个文件 + 在 `VendorRegistry.ALL` 注册，**不用动任何共享 Hook 代码**。

详细的架构说明、三个扩展点（`classCandidates` / `additionalExemptPackages` / `installExtraHooks`）的取舍、以及"如何新增厂商适配器 / 系统版本适配器"的完整步骤，见该包内的 [`README.md`](src/main/java/com/suseoaa/locationspoofer/xposed/hooks/vendor/README.md)。所以本文方法一~五定位到的差异，最终都应该落到对应的 vendor 适配器里，而不是散落在各个 `SystemXxxHooker` 中。

---

## OEM 定制 ROM 的特殊坑

* **小米 HyperOS / MIUI**：会在系统定位、Wi-Fi、AppOps 等服务里插入自己的风控/检测逻辑（对应本项目 `AntiDetectionHooker.kt`、`SystemAppOpsHooker.kt` 里专门处理的部分），排查时除了看 AOSP 对应类，还要留意 `com.miui.*`、`com.xiaomi.*` 包名下有没有相关的辅助类参与了判断。
* **SELinux 域名因方案而异**：不同 Root 方案（Magisk / APatch / KernelSU 及其分支）打 sepolicy 补丁用的工具、参数语法、以及内核里实际存在的域名/属性都可能不同（比如某些定制内核压根没有某个 `untrusted_app_*` 变体）。参考 [RootManager.kt](../core-data/src/main/java/com/suseoaa/locationspoofer/utils/RootManager.kt) 里 `TOOL_CANDIDATES` 按方案分组、`SEPOLICY_READ_DOMAINS` 拆成单条 `allow` 语句分别下发再统计成功率的写法——新增域名或适配新方案时延续这个"分组探测、单条容错"的模式，不要写成一条大杂烩规则一次性下发。
* **APEX 模块版本漂移**：同一 Android 大版本号下，不同设备的 Google Play 系统更新（Project Mainline）可能已经把 APEX 模块升级到了不同的小版本，AOSP 源码 tag 对应的 APEX 代码不一定和真机完全一致，遇到诡异的方法签名不匹配问题时，优先信真机反编译结果，不要迷信源码 tag。

---

## 完整适配工作流 Checklist

收到"某功能在新系统/新机型上失效"的反馈后，按顺序走一遍：

1. 让反馈者提供 **Android 版本、`ro.build.version.sdk`、机型/ROM 名称与版本号**，以及开启 Hook 后的完整 `adb logcat | grep LocationSpoofer` 日志。
2. 看日志里对应的 `[SysXxx]` 诊断输出，判断卡在哪一步：类都没找到？找到了类但方法没挂上？方法挂上了但没触发（说明目标类找错了）？触发了但改写没生效（说明字段/返回类型判断分支没走对）？
3. 按[方法一](#方法一aosp-源码比对最快优先用)~[方法五](#方法五动态插桩验证无-root-精确定位调用栈时用)依次排查，确认新版本上目标类的真实包名、方法签名、返回类型/字段名。
4. 按[防御性套路](#写-hook-时的防御性套路本项目的约定)落地代码：优先在现有的多候选名列表里追加新候选，而不是删掉旧的重写——保证老版本设备不受影响。
5. 补齐诊断日志，本地至少在一台可复现问题的设备/模拟环境上验证 Hook 确实挂载成功且生效。
6. 在 PR 描述里注明"验证过的具体 Android 版本 + 机型/ROM"，方便后续维护者知道这个分支覆盖了哪些真实设备，没覆盖到的仍然需要人肉验证。
7. 把验证结果登记到仓库根目录的 [ADAPTATION_PROGRESS.md](../ADAPTATION_PROGRESS.md)（机型、系统版本、验证了哪些组件/应用、日期），没测过的项保持"未验证"。

---

## 参考资料

* [cs.android.com](https://cs.android.com/android/platform/superproject) — Google 官方 AOSP 源码检索（支持按 tag 切版本）
* [androidxref.com](http://androidxref.com/) — 另一个 AOSP 源码检索站，界面更轻量
* [jadx](https://github.com/skylot/jadx) — Java/Kotlin 反编译工具，用于分析真机 framework/APEX jar
* [Frida](https://frida.re/) — 动态插桩框架，适合运行时追踪调用栈
* [LSPosed / libxposed API 文档](https://github.com/libxposed/api) — 本项目 Hook 层依赖的 API 101+ 规范
