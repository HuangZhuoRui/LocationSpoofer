package com.vincenthzr.locationspoofer.vendor

/**
 * 从 [VendorProfile] 识别 ROM 家族与版本的唯一一套规则。
 * xposed 模块的厂商级适配器（`matches()`）和 App 的"本机系统"显示都走这里，改识别规则只改这一处。
 *
 * 识别顺序即优先级：先认 ROM 自身暴露的标识属性，属性被裁剪时再按厂商 / 品牌名兜底。
 * 新增一个家族：在 [RomFamily] 加一项、在 [familyOf] 加识别条件（需要读新的系统属性时同步加到 [PROBED_PROPS]），
 * 并在 [VendorScheme.forFamily] 里指定它用哪个适配器。
 */
object RomRules {

    /** 采集设备画像时会尝试读取的系统属性——各 OEM 用来标识自身 ROM 与版本的关键属性都放这里。 */
    val PROBED_PROPS = listOf(
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

    private val XIAOMI_BRANDS = setOf("xiaomi", "redmi", "poco")
    private val OPLUS_BRANDS = setOf("oppo", "oneplus", "realme")
    private val VIVO_BRANDS = setOf("vivo", "iqoo")
    private val HONOR_HUAWEI_BRANDS = setOf("huawei", "honor")

    fun familyOf(p: VendorProfile): RomFamily {
        val names = setOf(p.manufacturer.lowercase(), p.brand.lowercase())
        fun brandIn(set: Set<String>) = names.any { it in set }
        return when {
            p.hasProp("ro.mi.os.version.name") || p.hasProp("ro.miui.ui.version.name") || brandIn(XIAOMI_BRANDS) ->
                RomFamily.HYPEROS_MIUI
            p.hasProp("ro.build.version.oplusrom") || p.hasProp("ro.build.version.opporom") || brandIn(OPLUS_BRANDS) ->
                RomFamily.COLOROS
            p.hasProp("ro.build.version.oneui") || brandIn(setOf("samsung")) -> RomFamily.ONEUI
            p.hasProp("ro.vivo.os.version") || brandIn(VIVO_BRANDS) -> RomFamily.ORIGINOS_FUNTOUCH
            p.hasProp("ro.build.version.magic") || p.hasProp("ro.build.version.emui") || brandIn(HONOR_HUAWEI_BRANDS) ->
                RomFamily.MAGICOS_EMUI
            p.hasProp("ro.flyme.published") || brandIn(setOf("meizu")) -> RomFamily.FLYME
            else -> RomFamily.AOSP
        }
    }

    /** 界面上显示的系统名：小米系按是否暴露 HyperOS 属性区分 HyperOS / MIUI */
    fun romName(p: VendorProfile): String = when (val family = familyOf(p)) {
        RomFamily.HYPEROS_MIUI ->
            if (!p.hasProp("ro.mi.os.version.name") && p.hasProp("ro.miui.ui.version.name")) "MIUI" else family.displayName
        else -> family.displayName
    }

    /** ROM 自身的版本号（如 HyperOS "OS4.0" → "4.0"，One UI "60100" → "6.1"），读不到时为空 */
    fun romVersion(p: VendorProfile): String = when (familyOf(p)) {
        RomFamily.HYPEROS_MIUI -> p.prop("ro.mi.os.version.name").removePrefix("OS")
            .ifBlank { p.prop("ro.miui.ui.version.name") }
        RomFamily.COLOROS -> p.prop("ro.build.version.oplusrom").ifBlank { p.prop("ro.build.version.opporom") }
            .removePrefix("V")
        RomFamily.ONEUI -> formatOneUi(p.prop("ro.build.version.oneui"))
        RomFamily.ORIGINOS_FUNTOUCH -> p.prop("ro.vivo.os.version")
        RomFamily.MAGICOS_EMUI -> p.prop("ro.build.version.magic").ifBlank { p.prop("ro.build.version.emui") }
        RomFamily.FLYME, RomFamily.AOSP -> ""
    }

    fun systemLabel(p: VendorProfile): String {
        val android = "Android ${p.release}"
        if (familyOf(p) == RomFamily.AOSP) return android
        val version = romVersion(p)
        return if (version.isBlank()) "${romName(p)} · $android" else "${romName(p)} $version · $android"
    }

    /** ro.build.version.oneui 形如 "60100"，表示 One UI 6.1 */
    private fun formatOneUi(raw: String): String {
        val v = raw.toIntOrNull() ?: return raw
        return "${v / 10000}.${v % 10000 / 100}"
    }
}
