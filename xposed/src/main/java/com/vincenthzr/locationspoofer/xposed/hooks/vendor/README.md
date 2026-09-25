# 厂商 / 系统版本适配层（vendor）

不同厂商 / 系统的系统服务实现类名、需要豁免的自有服务、定制逻辑各不相同。这个包用"每个系统一个适配器"
把这些差异按系统拆开，共享的 Hook 代码（`hooks/SystemXxxHooker.kt`）完全不感知是哪家厂商。

> 本文讲适配框架怎么用。怎么在真机上找到某个系统的内部类 / 方法，见 [`xposed/ADAPTATION_GUIDE.md`](../../../../../../../../../ADAPTATION_GUIDE.md)；
> 各系统的实机验证结果记录在仓库根目录的 [`ADAPTATION_PROGRESS.md`](../../../../../../../../../../ADAPTATION_PROGRESS.md)。

---

## 新增一个系统：完整检查清单

以新增 vivo OriginOS 为例，按顺序做完即可，每一步都有编译期或单元测试兜底：

| # | 改哪里 | 做什么 |
|---|---|---|
| 1 | `core-geo/.../vendor/RomFamily.kt`、`RomRules.kt` | 家族已经存在就跳过；否则加一个 `RomFamily`，在 `RomRules.familyOf` 里加识别条件，要读新的系统属性时同步加到 `PROBED_PROPS`，在 `romVersion` 里写版本号的取法 |
| 2 | `core-geo/.../vendor/VendorScheme.kt` | 加一项（如 `ORIGINOS("originos")`），并在 `forFamily` 里把家族指向它——App 的"厂商适配方案"选项和自动识别结果都来自这里 |
| 3 | `vendor/profiles/OriginOsVendor.kt` | 新建适配器：`id = VendorScheme.ORIGINOS.id`，`matches(profile) = profile.family == family`，只写与 AOSP 基线不同的部分（见下文"三个扩展点"） |
| 4 | `VendorRegistry.ALL` | 注册新适配器（`AospVendor` 保持在最后） |
| 5 | `app-ui/.../settings/VendorSchemeScreen.kt` 与 `values*/strings.xml` | 厂商方案选项列表里加一项和它的显示名 `vendor_scheme_originos` |
| 6 | 真机验证 | 装全局版并重启，打开 App"系统适配 → Hook 运行状态"，确认每个组件都是"已挂载"（见下文"Hook 运行状态报告"） |
| 7 | `ADAPTATION_PROGRESS.md` | 登记验证结果；"按系统"表格第一列要包含 `RomFamily.progressKeywords` 里的关键字，App 才能在"本机状态"里查到这一行 |

第 1~4 步写错时 `./gradlew :core-geo:test :xposed:testGlobalDebugUnitTest` 会失败：`RomRulesTest` 检查识别规则，
`VendorRegistryTest` 检查每个 `VendorScheme` 都有同 id 的适配器、自动识别选中的适配器与 App 显示的一致。

---

## 结构

```
 core-geo（Hook 端与 App 共用，纯 Kotlin）
   VendorProfile ── 设备画像：厂商 / 品牌 / 机型 / 系统属性（反射读取，全程缓存）
   RomRules ─────── 唯一一套识别规则：画像 → RomFamily、系统名与版本号
   VendorScheme ─── 厂商级适配器的 id，也是 App 设置页的选项
   HookStatusFiles ─ Hook 运行状态报告的落盘路径

 xposed/hooks/vendor（本目录）
   SystemHookVendor ── 适配器接口；profiles/ 下每个厂商一个 object，profiles/versions/ 下每个系统大版本一个
   VendorRegistry ──── 按 priority 选出唯一命中的适配器（可被用户手动覆盖），选不到时落 AospVendor
   SystemComponent ─── 需要按系统区分类名的系统服务，以及它所在的进程
   SystemClassLocator ─ 按"当前适配器候选 → AOSP 基线候选"逐层查找组件的实现类，结果记进 HookStatus

 共享 Hook 代码（hooks/SystemXxxHooker.kt）
   SystemClassLocator.locate(组件, …) → 拿到类 → 挂方法
```

```
                             ┌──────────────────┐
 SystemXxxHooker ──locate──► │ SystemClassLocator│──classCandidates──► VendorRegistry.active
                             └──────────────────┘                           │
          ┌───────────────────┬──────────────┬──────────────┬───────────────┤
          ▼                   ▼              ▼              ▼               ▼
   HyperOs4Vendor       HyperOsVendor   ColorOsVendor   OneUiVendor   AospVendor(兜底)
 (版本级，可选，parent+10)  (100)          (90)           (90)        (Int.MIN_VALUE)
```

适配粒度只分"厂商"和"系统大版本"两层，不按具体机型拆：同一厂商的子品牌 / 机型通常共用同一套系统，
真正会让系统服务实现发生变化的是系统大版本升级（如 HyperOS 3 → 4）。

---

## 三个扩展点

| 差异类型 | 覆写 | 说明 |
|---|---|---|
| **实现类名不同** | `classCandidates(component)` | 最常见。返回定制类名即可，`VendorRegistry` 自动在后面接上 AOSP 基线候选。所有查找层（包括 APEX 服务的线程 / LocalServices / ServiceManager 兜底）都只用这份候选，所以填了就一定生效 |
| **厂商自有、不该被模拟的系统服务** | `additionalExemptPackages()` | 例如小米的 MetokNLP 定位融合服务，喂假坐标会让系统自身定位打架。与 `SystemHookUtils` 的通用豁免名单自动合并 |
| **基线覆盖不了的定制逻辑** | `installExtraHooks(hooker, cl)` | 逃生舱。在基线 Hook 装完之后调用，只在本适配器命中时执行。方法被改名、签名大改等情况也在这里补 |

跟 AOSP 完全一致的系统三个都不用覆写，只需要能被正确识别。`HyperOsVendor` 是照着写的范例，每个覆写点该不该填、为什么，
都写在它的注释里。

### 精确到系统大版本

同一厂商下某个大版本确实需要不同规则时，不要在厂商级适配器里写 `if (版本号 == …)`，而是复制
`profiles/versions/SystemVersionVendorTemplate.kt`，改名（如 `HyperOs4Vendor.kt`），在 `matchesVersion` 里写
**真机上用 `adb shell getprop` 确认过格式**的版本判断，只覆写和父适配器不同的部分，然后注册进 `VendorRegistry.ALL`。
它默认比父适配器优先级高 10，没被精确匹配到的设备仍然落回父适配器。版本级适配器不出现在 App 的手动选择列表里。

### 需要按系统区分的是一个新组件

1. `SystemComponent` 加一项，注明所在进程；
2. `AospVendor.classCandidates` 补上它的 AOSP 基线候选（`VendorRegistryTest` 会检查每个组件都有）；
3. 共享 Hook 代码用 `SystemClassLocator.locate(新组件, classLoader, …)` 查找——服务在 APEX 里时传 `threadKeywords` /
   `serviceNames` / `deepScan` / `apexJars` 打开相应的兜底层；通过 `ServiceManager.getService` / `addService` 等途径
   直接拿到服务实例时，调用 `HookStatus.classFound(组件, 实例.javaClass, "途径")` 记录；
4. App 的 `HookStatusScreen.componentName` 与字符串资源里补上显示名。

---

## Hook 运行状态报告

全局方案下，system_server / 电话 / 蓝牙三个进程各自把运行状态写成一份 JSON（路径见 `HookStatusFiles`），
App 通过 root 读取，显示在"系统适配 → Hook 运行状态"页：

- 命中的适配器，以及是否为用户手动指定；
- 每个组件：在哪一层找到了哪个类（`classLoader` / `thread:…` / `ServiceManager[…]` / `LocalServices` / `apex:…` 等），
  还是没找到；
- 每个被挂载的方法实际挂上了几个重载（由 `XposedHelpers.hookAllMethods` 自动记录）——**×0 表示这个系统版本上没有这个方法**，
  通常意味着方法被改名或挪到了别的类，需要用 `installExtraHooks` 或版本级适配器补；
- 部署过程中抛出的异常。

报告早于本次开机时间时，页面会提示"本次开机模块没有在这个进程里生效"。需要更细的信息时，打开页面底部的
"开机时转储系统服务结构"，重启后各候选类的全部方法和字段会写进 Xposed 日志。

---

## 用户手动覆盖

自动识别选错时，用户可以在 App"系统适配 → 厂商适配方案"里手动指定一个 `VendorScheme`，选择随配置文件传到系统进程，
由 `VendorRegistry.applyManualOverride` 消费（必须早于 `VendorRegistry.active` 首次求值，`LocationHooker` 已处理），
重启后生效。适配器作者不需要为此做任何处理；`VendorScheme` 的 id 会被持久化，发布后不要改名。

---

## 设计约束

- **适配器无状态**：都是 `object` 单例，会被并发访问，不要持有可变字段；`matches()` 不要抛异常。
- **`AospVendor` 不可删**：`matches` 恒为 `true`、优先级最低，保证任何设备都有基线可用。新 Android 版本导致的原生类名变化
  追加到它的候选里，不要删旧候选。
- **类名只写在适配器里**：共享 Hook 代码不写死系统服务类名，一律通过 `SystemClassLocator` 查找。
- **进程是固定的**：适配器只能改"在哪个类里 Hook"，改不了"在哪个进程里 Hook"。如果某个系统把定位等服务挪到了
  system_server / com.android.phone / com.android.bluetooth 之外的进程，需要同时修改 `SystemProcess`、
  `xposed/src/global/resources/META-INF/xposed/scope.list` 与 `LocationHooker.handleSystemProcessGlobal` 的进程判断，
  以及 `HookStatusFiles` 的报告路径。
- **版本判定必须实测**：属性名和取值格式先在目标真机上用 `adb shell getprop` 确认，不要假设。

---

## 现状

具体在哪些机型、系统版本、目标应用上实测过，统一记录在 [ADAPTATION_PROGRESS.md](../../../../../../../../../../ADAPTATION_PROGRESS.md)。

| 适配器 | 层级 | 状态 |
|---|---|---|
| `AospVendor` | 基线 | ✅ 维护全部原生候选类名 |
| `HyperOsVendor` | 厂商级 | ✅ HyperOS 4 实机完整验证；类名与 AOSP 相同无需覆盖，已填 6 个小米自有豁免包名 |
| `ColorOsVendor` | 厂商级 | ⚠️ 仅能识别，完全走基线，待实机验证 |
| `OneUiVendor` | 厂商级 | ⚠️ 仅能识别，完全走基线，待实机验证 |
| `SystemVersionVendorTemplate` | 版本级 | 💤 不生效的模板（`matchesVersion` 恒为 `false`），复制改名后使用 |

vivo OriginOS、荣耀 MagicOS、魅族 Flyme 已能被识别（App 会正确显示系统名），但还没有专用适配器，走 `AospVendor`。
