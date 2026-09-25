@file:Suppress("SpellCheckingInspection", "unused", "MemberVisibilityCanBePrivate")

package com.suseoaa.locationspoofer.xposed.hooks.vendor

/**
 * 已知的 ROM 家族。用于让厂商适配器声明自己针对哪一类系统，也用于诊断日志。
 *
 * 注意：这里只是"家族"级别的粗分类，真正决定用哪套规则的是每个 [SystemHookVendor.matches]
 * 的判定逻辑（可以进一步按 Android 版本、具体机型、ROM 小版本细分），家族枚举本身不参与选择。
 */
enum class RomFamily {
    /** 原生 / 接近原生（Pixel、AOSP、LineageOS 等），也是所有机型的兜底基线 */
    AOSP,

    /** 小米 / 红米 / POCO：HyperOS 与旧版 MIUI */
    HYPEROS_MIUI,

    /** OPPO / OnePlus / realme：ColorOS / OxygenOS（新版已同源） */
    COLOROS,

    /** 三星：One UI */
    ONEUI,

    /** vivo / iQOO：OriginOS / Funtouch OS */
    ORIGINOS_FUNTOUCH,

    /** 华为 / 荣耀：EMUI / MagicOS */
    MAGICOS_EMUI,

    /** 魅族：Flyme */
    FLYME,

    /** 其它未单独适配的定制 ROM */
    OTHER,
}

/**
 * 当前设备的"系统画像"：一次性采集、全程缓存的只读快照。
 *
 * 采集内容都是判断"该走哪套 OEM 规则"时会用到的信号：厂商 / 品牌 / 机型、Android API Level，
 * 以及各家用来标识自身 ROM 的系统属性（`ro.miui.ui.version.name`、`ro.mi.os.version.name` 等）。
 *
 * 之所以用 [android.os.SystemProperties] 反射读取而不是直接引用：该类是 `@hide` 的隐藏 API，
 * 直接引用在部分编译配置 / 高版本 Android 上会触发隐藏 API 限制，反射调用则不受影响。
 */
data class VendorProfile(
    val manufacturer: String,
    val brand: String,
    val model: String,
    val device: String,
    val sdkInt: Int,
    val release: String,
    /** 采集到的原始系统属性，供适配器做更细的判断（键为属性名，值为属性值，缺失则不入表） */
    val props: Map<String, String>,
) {
    fun prop(key: String): String = props[key].orEmpty()
    fun hasProp(key: String): Boolean = props[key]?.isNotBlank() == true

    override fun toString(): String =
        "VendorProfile(manufacturer=$manufacturer, brand=$brand, model=$model, sdk=$sdkInt, release=$release, props=$props)"

    companion object {
        /** 采集时会尝试读取的系统属性清单——各 OEM 用来标识自身 ROM 的关键属性都放这里。 */
        private val PROBED_PROPS = listOf(
            // 小米 HyperOS / MIUI
            "ro.mi.os.version.name",
            "ro.mi.os.version.code",
            "ro.miui.ui.version.name",
            "ro.miui.ui.version.code",
            // OPPO / OnePlus / realme ColorOS
            "ro.build.version.oplusrom",
            "ro.build.version.opporom",
            "ro.oplus.image.my_product.type",
            // 三星 One UI
            "ro.build.version.oneui",
            "ro.build.version.sem",
            // vivo / iQOO
            "ro.vivo.os.version",
            "ro.vivo.product.series",
            // 华为 / 荣耀
            "ro.build.version.emui",
            "ro.build.version.magic",
            // 魅族 Flyme
            "ro.flyme.published",
            "persist.sys.flyme.pushsdk",
        )

        /** 全程缓存的当前设备画像，首次访问时采集。 */
        val current: VendorProfile by lazy { detect() }

        private fun detect(): VendorProfile {
            val props = LinkedHashMap<String, String>()
            for (key in PROBED_PROPS) {
                val v = readProp(key)
                if (v.isNotBlank()) props[key] = v
            }
            return VendorProfile(
                manufacturer = safeBuild("MANUFACTURER"),
                brand = safeBuild("BRAND"),
                model = safeBuild("MODEL"),
                device = safeBuild("DEVICE"),
                sdkInt = android.os.Build.VERSION.SDK_INT,
                release = android.os.Build.VERSION.RELEASE.orEmpty(),
                props = props,
            )
        }

        private fun safeBuild(field: String): String = try {
            (android.os.Build::class.java.getField(field).get(null) as? String).orEmpty()
        } catch (_: Throwable) {
            ""
        }

        private fun readProp(key: String): String = try {
            val sp = Class.forName("android.os.SystemProperties")
            val get = sp.getMethod("get", String::class.java)
            (get.invoke(null, key) as? String).orEmpty()
        } catch (_: Throwable) {
            ""
        }
    }
}
