# 厂商 / 系统版本适配层（vendor）

这个包解决一个具体的可维护性问题：**不同厂商 / 系统的内部实现类名、字段名、定制逻辑各不相同，如果全部用
`if 小米 … else if OPPO …` 堆进同一个 Hook 函数里，函数会迅速膨胀到无法维护。** 这里用"每个系统一个适配器
（Strategy 模式）"把这些差异按系统拆开，各走各的规则，而共享 Hook 代码完全不感知分支。

> 想了解如何从零定位某个系统内部类 / 方法，见仓库根 `xposed/ADAPTATION_GUIDE.md`。本文只讲这个适配框架怎么用。

---

## 适配粒度：只分到"厂商"和"系统大版本"两层，不按具体机型分

**同一厂商的子品牌 / 旗舰机型通常用的是同一套系统，内部实现差异很小**，按具体机型（市场型号）拆分适配器
只会制造大量没必要的重复文件，所以这里**不**提供机型级的适配层。真正会让 framework 内部实现发生跨度性
变化、需要单独适配的，是**系统大版本跨度**——比如小米的 HyperOS 3 升到 HyperOS 4 这种跨大版本更新，
可能伴随 Android 基线版本升级、部分系统服务被重写。所以适配粒度是：

```
                       ┌────────────────────────┐
 共享 Hook 代码 ──────► │      VendorRegistry     │  ← 唯一入口，调用方只跟它打交道
 (SystemXxxHooker)     └───────────┬────────────┘
                                   │ 启动时按 priority 选出唯一命中的适配器
        ┌──────────────────┬──────────────┬──────────────┬───────────────┐
        ▼                  ▼              ▼              ▼               ▼
   HyperOs4Vendor     HyperOsVendor   ColorOsVendor   OneUiVendor   AospVendor(兜底)
 (系统版本级，可选)      (priority 100)  (priority 90)   (priority 90)  (Int.MIN_VALUE)
 priority = parent+10, parent = HyperOsVendor
```

- **厂商级**（`profiles/` 下）：按"是不是小米/HyperOS"这种粗粒度判断，覆盖该厂商绝大多数机型、绝大多数
  系统版本共有的规则。
- **系统版本级**（`profiles/versions/` 下，继承 `SystemVersionVendor`）：可选的第二层，只在"同一厂商下
  某个系统大版本确实需要不一样的规则"时才新增，默认优先级比它所属的厂商级适配器高，命中后可以选择性
  覆盖父适配器的规则、其它未覆盖的部分自动透传。绝大多数版本不需要这一层，直接被对应的厂商级适配器
  （再不行就 `AospVendor`）覆盖即可。

---

## 它是怎么运转的

1. **`VendorProfile`**：进程启动时采集一次当前设备画像（厂商 / 品牌 / 机型、API Level、各家 ROM 标识
   属性——包括版本号相关的属性，如 `ro.mi.os.version.name`），全程缓存。
2. **各 `SystemHookVendor` 适配器**（`profiles/` 下每个厂商一个 `object`，`profiles/versions/` 下每个
   系统版本一个 `object`）：只声明"自己这套系统/版本和上一层基线的差异"。
3. **`VendorRegistry`**：按 `priority` 选出唯一命中的适配器（选不到就落 `AospVendor`），并对外提供四个能力：
   - `resolveClass(component, classLoaders…)` — 按"当前适配器候选 → AOSP 基线候选"解析系统服务实现类；
   - `classCandidates(component)` — 合并去重后的候选类名清单（供需要自己做多 ClassLoader 扫描的场景）；
   - `additionalExemptPackages` — 当前适配器追加的豁免包名，供 `SystemHookUtils.EXEMPT_PACKAGES` 合并；
   - `installExtraHooks(hooker, classLoader)` — 触发当前适配器专属的额外 Hook。
4. **共享 Hook 代码**在每个"查找系统服务类"的地方，先问 `VendorRegistry.resolveClass(...)`，未命中再回落到自己原有的查找逻辑——所以接入是纯追加、零回归。

---

## 三个扩展点，覆盖三类差异

| 差异类型 | 用哪个扩展点 | 说明 |
|---|---|---|
| **只是实现类名不同** | 覆写 `classCandidates(component)` | 最常见。返回候选类名即可，`VendorRegistry` 会自动在其后接上上一层基线兜底。 |
| **厂商/版本自有的、不该被模拟的系统包** | 覆写 `additionalExemptPackages()` | 厂商自己的定位融合 / 场景感知 / 省电策略服务（例如小米 MetokNLP）。`VendorRegistry` 会自动把它和 `SystemHookUtils` 里的通用基线豁免名单合并。 |
| **有基线覆盖不了的定制逻辑** | 覆写 `installExtraHooks(hooker, cl)` | 逃生舱。在基线 Hook 之后被调用，纯追加，只在本适配器命中的设备上执行，不污染其它路径。 |

如果某系统/版本跟上一层完全一致，三个都不用覆写——它只需要能被 `matches()` 正确识别（为了日志里自报家门 + 将来有地方补差异）。

---

## 如何新增一个厂商适配器

假设要新增"vivo OriginOS"：

1. 在 `profiles/` 下新建 `OriginOsVendor.kt`，实现 `SystemHookVendor`：
   ```kotlin
   object OriginOsVendor : SystemHookVendor {
       override val id = "originos"
       override val family = RomFamily.ORIGINOS_FUNTOUCH
       override val priority = 90
       override fun matches(profile: VendorProfile): Boolean {
           if (profile.hasProp("ro.vivo.os.version")) return true
           val m = profile.manufacturer.lowercase()
           return m == "vivo" || m == "iqoo"
       }
       // 只有确认某组件类名和 AOSP 不同时才覆写；否则留空走基线。
       override fun classCandidates(component: SystemComponent) = when (component) {
           SystemComponent.WIFI_SERVICE -> listOf("com.vivo.services.wifi.VivoWifiServiceImpl")
           else -> emptyList()
       }
   }
   ```
2. 到 `VendorRegistry.ALL` 列表里把 `OriginOsVendor` 加进去（`AospVendor` 保持在最后）。
3. 完成。**不需要改动任何 `SystemXxxHooker` 共享代码。**

> 填 `classCandidates` 里的类名前，务必按 `ADAPTATION_GUIDE.md` 在实机上反编译确认，不要凭空猜类名。

### 需要按机型区分的是一个"新组件"？

如果你要 Hook 的系统服务还没被纳入 `SystemComponent` 枚举：

1. 在 `SystemComponent` 加一个枚举项；
2. 在 `AospVendor.classCandidates` 里补上它的 AOSP 基线候选；
3. 在共享 Hook 代码查找该类的地方，改成 `VendorRegistry.resolveClass(新枚举, cl) ?: 原有逻辑`。

---

## 用户可以手动覆盖自动识别

`VendorProfile` 的自动识别依赖设备属性/厂商名，个别设备属性可能被裁剪或者不在预期格式内，导致选错
适配器。App 内"厂商适配方案"设置页（`app-ui/.../ui/screen/settings/VendorSchemeScreen.kt`）允许用户
从一个精简的下拉列表（`core-data/.../data/model/VendorScheme.kt`：AUTO/HyperOS/ColorOS/One UI/AOSP）
手动选一个，选中后经由和其它设置项完全一样的落盘路径（`ConfigManager.saveConfig` → 多路径 JSON 配置
文件）传到 system_server，由 `VendorRegistry.applyManualOverride` 消费——命中后直接返回对应适配器，
跳过 `matches()` 判断。

对适配器作者的影响：**没有**。手动覆盖只是换了"谁被选中"，`classCandidates`/`additionalExemptPackages`/
`installExtraHooks` 该怎么写还怎么写，不需要为此单独处理。唯一要注意的约束：[`SystemHookVendor.id`](SystemHookVendor.kt)
从此不再"仅用于日志"，而是被 `VendorScheme.id` 和用户已保存的设置持久化引用，**改名前先确认
`VendorScheme.kt` 里是不是也要跟着改**，否则老用户保存的手动选择会因为找不到匹配项静默回退到自动识别。

新增厂商适配器时，如果希望用户能手动选中它，记得在 `VendorScheme.kt` 里加一项（core-data 模块不依赖
xposed 模块，两边的 id 字符串只能人工保持同步，没有编译期检查）。`profiles/versions/` 下的系统版本级
适配器目前不出现在这个下拉列表里，手动选择只精确到"厂商"这一层。

---

## 精确到"系统大版本"：什么时候要用 `SystemVersionVendor`

厂商级适配器只能按"是不是小米/HyperOS"这种粗粒度判断。如果发现**同一厂商下某个系统大版本跨度**
（比如 HyperOS 3 升到 HyperOS 4）确实需要和该厂商其它版本不一样的规则，不要在厂商级适配器里加
`if (版本号 == ...)` 这种散落的特判——继承 [`SystemVersionVendor`](SystemVersionVendor.kt) 新开一个
更精确的适配器：

1. **不要直接改模板**，复制 [`profiles/versions/SystemVersionVendorTemplate.kt`](profiles/versions/SystemVersionVendorTemplate.kt)，
   改名成具体版本（如 `HyperOs4Vendor.kt`），同样放在 `profiles/versions/` 目录下——这样以后
   HyperOS 5、6 需要单独适配时，这份带完整说明的模板还在，可以继续复制。
2. 在目标版本的真机上跑，拿到真实的版本号属性原始值（**不要凭空假设格式**）：
   ```bash
   adb shell getprop ro.mi.os.version.name
   adb shell getprop ro.mi.os.version.code
   adb shell getprop ro.build.version.sdk
   ```
   把实测确认过的判定逻辑写进 `matchesVersion`（可以用基类的 `extractLeadingMajorVersion` 辅助
   提取主版本号，也可以直接用 `profile.prop(...)` 做字符串匹配，取决于真实格式）。
3. 只覆写"和厂商级适配器（`parent`）不一样的那部分"——`classCandidates`/`additionalExemptPackages`/
   `installExtraHooks` 默认全部透传给 `parent`，没有差异的方法保持注释掉即可，**不要把 parent 已经有的
   规则重复抄一遍**。
4. 到 `VendorRegistry.ALL` 列表里注册（和厂商级适配器同一个列表，不需要单独处理）。

选择优先级由 `SystemVersionVendor` 自动处理：默认 `priority = parent.priority + 10`，保证"精确匹配到
具体版本"总是优先于"只匹配到厂商"，该厂商下没被精确匹配到版本的其它设备仍然正常落回厂商级适配器。

---

## 设计约束（改动前请遵守）

- **适配器必须无状态**：都是 `object` 单例，会被并发访问，不要在里面存可变字段。
- **`matches()` 不要抛异常**：`VendorRegistry` 已用 try/catch 兜底，但仍应让判定依据稳定信号（属性、厂商名）。
- **`AospVendor` 是不可删的兜底**：`priority = Int.MIN_VALUE`、`matches` 恒 `true`，保证任何设备至少有基线可用；新系统版本导致的原生类名变化优先"追加"到它这里（别删旧候选，以免影响老设备）。
- **接入共享代码时只加不改**：新站点一律写成 `VendorRegistry.resolveClass(...) ?: <原有查找逻辑>`，保证厂商层未命中时行为和改造前完全一致。
- **不按具体机型分层**：同厂商子品牌/旗舰差异小，不需要机型级适配器；如果真的发现某个具体机型有特殊情况，优先怀疑是不是其实是版本差异（该机型率先升级/停留在某个系统版本），按版本适配，而不是重新引入机型分层。
- **版本判定必须实测，不能凭空编造**：`SystemVersionVendor.matchesVersion` 里用到的属性名和取值格式，一律先用 `adb shell getprop` 在目标真机上确认过再写，不要假设某个属性一定存在或者格式是纯数字。

---

## 现状

| 适配器 | 层级 | 状态 |
|---|---|---|
| `AospVendor` | 基线 | ✅ 维护全部原生候选类名 |
| `HyperOsVendor` | 厂商级 | ✅ 已在实机完整验证（小米 17 Pro Max / HyperOS 4，定位/Wi-Fi/基站/AppOps/NetworkCapabilities 脱敏全链路实测通过）；`classCandidates` 经验证无需覆盖（与 AOSP 同名），`additionalExemptPackages` 已填充 6 个小米自有系统包（MetokNLP/场景感知/省电策略），`installExtraHooks` 暂无已确认需要的定制项 |
| `ColorOsVendor` | 厂商级 | ⚠️ 模板：仅能识别，完全走基线，待实机验证后填充 |
| `OneUiVendor` | 厂商级 | ⚠️ 模板：仅能识别，完全走基线，待实机验证后填充 |
| `SystemVersionVendorTemplate` | 系统版本级 | 💤 安全的不生效模板（`matchesVersion` 恒返回 `false`），复制改名后按本文档步骤填入真实版本判定逻辑即可激活 |

> 给下一个要适配新系统的人：`HyperOsVendor` 就是照着抄的范例——`classCandidates` 该不该填、`additionalExemptPackages` 里每一条豁免包名为什么要豁免、`installExtraHooks` 什么时候该留空，都在它的代码注释里写清楚了原因，不是空文件。当前只在**厂商级**验证过；HyperOS 3→4 这类跨版本差异尚未实测确认过是否存在，`SystemVersionVendorTemplate` 就是留给这一步用的。
