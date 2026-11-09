package com.vincenthzr.locationspoofer.vendor

/**
 * 当前设备的"系统画像"：一次性采集、全程缓存的只读快照，xposed 模块与 App 共用。
 *
 * 采集内容都是判断"该走哪套 OEM 规则"时会用到的信号：厂商 / 品牌 / 机型、Android API Level，
 * 以及各家用来标识自身 ROM 的系统属性（`ro.miui.ui.version.name`、`ro.mi.os.version.name` 等）。
 *
 * 本模块是纯 Kotlin，不依赖 Android SDK，`Build` 与 `SystemProperties`（`@hide` 隐藏 API）都通过反射读取；
 * 反射失败时对应字段为空，不会抛异常。
 */
data class VendorProfile(
    val manufacturer: String,
    val brand: String,
    val model: String,
    val device: String,
    val sdkInt: Int,
    val release: String,
    /** 采集到的原始系统属性，供识别规则与适配器做更细的判断（键为属性名，值为属性值，缺失则不入表） */
    val props: Map<String, String>,
) {
    fun prop(key: String): String = props[key].orEmpty()
    fun hasProp(key: String): Boolean = props[key]?.isNotBlank() == true

    /** 本机所属的 ROM 家族 */
    val family: RomFamily get() = RomRules.familyOf(this)

    /** 例如 "HyperOS 4.0 · Android 17"；原生系统只显示 Android 版本 */
    val systemLabel: String get() = RomRules.systemLabel(this)

    override fun toString(): String =
        "VendorProfile(manufacturer=$manufacturer, brand=$brand, model=$model, sdk=$sdkInt, release=$release, props=$props)"

    companion object {
        /** 全程缓存的当前设备画像，首次访问时采集。 */
        val current: VendorProfile by lazy { detect() }

        private fun detect(): VendorProfile {
            val props = LinkedHashMap<String, String>()
            for (key in RomRules.PROBED_PROPS) {
                val v = readProp(key)
                if (v.isNotBlank()) props[key] = v
            }
            return VendorProfile(
                manufacturer = buildField("android.os.Build", "MANUFACTURER") as? String ?: "",
                brand = buildField("android.os.Build", "BRAND") as? String ?: "",
                model = buildField("android.os.Build", "MODEL") as? String ?: "",
                device = buildField("android.os.Build", "DEVICE") as? String ?: "",
                sdkInt = buildField("android.os.Build\$VERSION", "SDK_INT") as? Int ?: 0,
                release = buildField("android.os.Build\$VERSION", "RELEASE") as? String ?: "",
                props = props,
            )
        }

        private fun buildField(className: String, field: String): Any? = try {
            Class.forName(className).getField(field).get(null)
        } catch (_: Throwable) {
            null
        }

        private fun readProp(key: String): String = try {
            val sp = Class.forName("android.os.SystemProperties")
            (sp.getMethod("get", String::class.java).invoke(null, key) as? String).orEmpty()
        } catch (_: Throwable) {
            ""
        }
    }
}
