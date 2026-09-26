# 电话、蓝牙与报告读取修复（ColorOS 16）

> 本文记录拆分前完整版本的历史验证（88 项测试及下述 APK 散列）。当前无原生传感器评审版及新增配置失败路径，见 [ColorOS 16 说明](ColorOS16.md)。

## 修复范围

- `SystemFileCommands` 通过 `/proc/1/root` 访问 init 的系统文件视图，避免 su 继承应用隔离目录后读不到 PHONE / BLUETOOTH 报告，或把配置写入应用视图中的同名空目录。不会退回可能含有旧配置的隔离视图。
- 全局配置以同目录临时文件、正确 UID 和 SELinux 标签、原子替换写入系统服务目录。已有服务目录同步失败会返回错误；`RootManager` 检查退出码并持续读取输出，避免空输出被误判为成功或管道阻塞。只在成功后更新内存中的已保存配置。
- 无报告／旧报告文案只描述读取状态，不再直接断言模块未生效。
- `TelephonyCallbackDelivery` 在框架完成权限检查后，根据实际注册记录的 UID、包名及回调 binder 决定替换结果。不会改写 `TelephonyRegistry` 公共缓存或传入的共享对象；注销和 binder 死亡由框架管理，每次回调重新查找记录并读取目标配置。
- ColorOS 16 使用 `usesFrameworkBleDelivery` 选择 `ColorOs16BleDelivery`，普通回调与 PendingIntent 统一走真实扫描队列；不再同时启动通用心跳。保留过滤器、扫描权限、批量延迟、扫描停止、发现／丢失及死亡清理。
- 扫描注册通过继承自 ContextMap 的实际 `add` 方法识别；处理回调调用方的编译内联。仅明确来自应用的 flush 请求可以提前发送，系统周期刷新与硬件缓冲区阈值刷新仍遵守配置间隔。
- 高频目标匹配日志使用现有的节流机制。

没有改动传感器建模、原生 SensorService 挂钩、定位算法或 LSPosed 作用域；没有使用设备指纹、固件哈希、固定内存地址。

## 验证方法

`app/src/androidTest/.../SystemFilesInstrumentation.kt` 在主应用的 UID 和挂载命名空间中执行生产代码：读取三个进程的报告，以生产配置写入命令写入测试数据并确认五份配置一致，调用实际配置同步入口，再校验非零退出码不会被误报成功。默认模式恢复测试前文件及元数据；传入 config 参数只用于外部实机验证脚本，由脚本负责备份和恢复。

外部普通应用探针位于 `E:/LocationSpoofer-main/coloros-main-review-evidence/sensor-probe`，目标包与对照包都不加入 LSPosed 作用域。测试模拟只对探针目标包启用，结束后恢复全部五份配置并核对内容。

## 构建与自动测试

- core-data：28 项；core-geo：30 项；xposed：30 项，总计 88 项，全部通过。
- 全局和非全局 debug 构建通过，保留两个 ABI 的既有构建设置。
- 最终 APK SHA-256：`6ca14094e28e6b13e27eff201b1836939ce4a82a016fdee4fd03a6f3d43a50ba`。
- 证书 SHA-256：`d463293b3e9bc1e35268e06875101600e880546b48eb3c2630eb8d1279e26b7f`，与修复前安装包一致。
- APK：`E:/LocationSpoofer-main/dist/LocationSpoofer-ColorOS16-phone-ble-report-fix-global-arm64-debug.apk`。

## 实机证据

证据目录：`E:/LocationSpoofer-main/coloros-main-review-evidence/report-fix`。

首次重启验证确认应用环境可读取全部报告、五份配置同步、目标基站缓存／请求／监听正确、对照应用基站信息保持真实。蓝牙初版发现继承方法查找遗漏，已修正。

后续蓝牙复测确认普通与 PendingIntent 扫描仅接收配置数据，不匹配的地址过滤返回零结果，发现／丢失回调各一次，取消目标后恢复真实扫描。进一步修正系统内部刷新导致批量提前发送的问题。最终蓝牙单独复测中，普通批量首次约 5.14 秒，后续间隔约 5.95、5.04 秒；8 秒时主动 flush 在约 62 毫秒内返回，随后恢复 5 秒间隔。

最终版本重新安装后，已启动进程的 Hook 状态不代表最终版本已统一加载，因此必须重新开机后联合复验，不能合并不同加载状态的结果宣称整体验证通过。

最终开机（boot ID `4cfa6795-e6d2-4898-90c1-4d57b7722a5e`）联合复验完成，`system_server` PID 4944 全程保持不变：

- 应用自己的运行环境可读取 SYSTEM_SERVER / PHONE / BLUETOOTH 全部报告；三份报告均属于本次启动，错误列表均为空。
- 生产配置写入及同步测试中五份配置内容一致，非零 root 命令退出码正确返回失败。
- 目标应用基站缓存查询、异步请求、监听均返回配置的小区；对照应用三个入口都保持真实数据。
- 仅回调扫描、普通回调与 PendingIntent 同时扫描都正常，只向目标应用返回配置蓝牙结果；对照应用保持真实结果。
- 不匹配的地址过滤返回零结果。
- 5 秒批量扫描首次 5.804 秒，后续间隔 5.011、5.852 秒；没有单条回调混入，PendingIntent 批量间隔也通过检查。
- 发现／丢失回调各一次；配置设备移除后按丢失超时发送事件。
- 应用主动 flush 在约 53 毫秒内返回，之后继续遵守 5 秒间隔。
- 扫描中从目标列表移除应用后恢复真实结果，稳定窗口不再包含模拟地址；这轮测试没有停止后的回调记录。
- 测试结束后先恢复全部测试前配置，再将原有系统配置同步到此前陈旧的电话／蓝牙副本，五份配置最终一致，当前模拟保持原先的关闭状态；临时测试包与 13 个测试备份目录已清理。
- 手机安装的 APK 散列与交付文件完全一致。11 个原生库（包括 `liblocation_accel.so`、`liblocation_steps.so`）与修复前安装包逐字节一致。

机器判定结果：`report-fix/final-validation.json`，15 项检查全部通过。完整采集：`verify-final.log`、各探针结果文本及 `report-final-*.json`。

## 验证边界

仅在连接的 ColorOS 16 / Android 16 设备上验证。普通与 PendingIntent 同时请求硬件 FIRST_MATCH / MATCH_LOST 时，系统曾拒绝第二个扫描并返回状态 5（硬件资源不足）；没有绕过平台资源检查，发现／丢失验证改用单独的普通回调扫描。常规和批量 PendingIntent 路径另行验证。

本次没有重做之前的完整步频与原生传感器实测，相关证据见独立传感器分支的 `docs/NativeSensors-ColorOS16.md`。没有提交、推送或建立 PR。
