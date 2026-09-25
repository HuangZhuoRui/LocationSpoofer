@file:Suppress(
    "UNUSED_PARAMETER",
    "UNUSED_VARIABLE",
    "UNNECESSARY_NOT_NULL_ASSERTION",
    "DEPRECATION",
    "NAME_SHADOWING",
    "FunctionName",
    "PrivatePropertyName",
    "SpellCheckingInspection",
    "RedundantUnitReturnType",
    "RemoveRedundantQualifierName",
    "OPT_IN_USAGE",
    "unused",
    "UnusedImport"
)

package com.suseoaa.locationspoofer.xposed.hooks.network

import com.suseoaa.locationspoofer.xposed.LocationHooker
import com.suseoaa.locationspoofer.xposed.utils.*
import com.suseoaa.locationspoofer.xposed.hooks.*
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.lang.reflect.Member
import kotlin.math.*
import io.github.libxposed.api.*

/**
 * 伪造 ServiceState / SignalStrength / CellIdentity。
 */
internal fun LocationHooker.buildFakeServiceState(
    classLoader: ClassLoader,
    cellArray: org.json.JSONArray?
): Any? {
    XposedBridge.logOpenCellIdEvery(
        "buildFakeServiceState:called:${cellArray?.length() ?: 0}",
        "buildFakeServiceState called cellJsonCount=${cellArray?.length() ?: 0}"
    )
    return try {
        val clazz = XposedHelpers.findClass("android.telephony.ServiceState", classLoader)
        val state = XposedHelpers.newInstance(clazz)
        val cell =
            if (cellArray != null && cellArray.length() > 0) cellArray.optJSONObject(0) else null
        val operator = if (cell != null) {
            val mcc = positiveJsonInt(cell, "mcc", default = 460)
            val mnc = positiveJsonInt(cell, "mnc", "net", default = 0)
            String.format(java.util.Locale.US, "%d%02d", mcc, mnc)
        } else {
            "46000"
        }
        val operatorName = when (operator.takeLast(2).toIntOrNull() ?: 0) {
            1, 6, 9 -> "中国联通"
            3, 5, 11 -> "中国电信"
            else -> "中国移动"
        }

        try {
            XposedHelpers.callMethod(state, "setState", 0)
        } catch (_: Throwable) {
        }
        try {
            XposedHelpers.callMethod(state, "setVoiceRegState", 0)
        } catch (_: Throwable) {
        }
        try {
            XposedHelpers.callMethod(state, "setDataRegState", 0)
        } catch (_: Throwable) {
        }
        try {
            XposedHelpers.callMethod(
                state,
                "setOperatorName",
                operatorName,
                operatorName,
                operator
            )
        } catch (_: Throwable) {
        }
        try {
            XposedHelpers.setIntField(state, "mVoiceRegState", 0)
        } catch (_: Throwable) {
        }
        try {
            XposedHelpers.setIntField(state, "mDataRegState", 0)
        } catch (_: Throwable) {
        }
        try {
            XposedHelpers.setObjectField(state, "mVoiceOperatorAlphaLong", operatorName)
        } catch (_: Throwable) {
        }
        try {
            XposedHelpers.setObjectField(state, "mVoiceOperatorAlphaShort", operatorName)
        } catch (_: Throwable) {
        }
        try {
            XposedHelpers.setObjectField(state, "mVoiceOperatorNumeric", operator)
        } catch (_: Throwable) {
        }
        try {
            XposedHelpers.setObjectField(state, "mDataOperatorAlphaLong", operatorName)
        } catch (_: Throwable) {
        }
        try {
            XposedHelpers.setObjectField(state, "mDataOperatorAlphaShort", operatorName)
        } catch (_: Throwable) {
        }
        try {
            XposedHelpers.setObjectField(state, "mDataOperatorNumeric", operator)
        } catch (_: Throwable) {
        }
        XposedBridge.logOpenCellIdEvery(
            "buildFakeServiceState:success:$operator:$operatorName",
            "buildFakeServiceState success operator=$operator operatorName=$operatorName"
        )
        state
    } catch (e: Throwable) {
        XposedBridge.logOpenCellId("buildFakeServiceState failed", e)
        null
    }
}

internal fun LocationHooker.buildFakeSignalStrength(
    classLoader: ClassLoader,
    config: org.json.JSONObject?
): Any? {
    val cellCount = config?.optJSONArray("cell_json")?.length() ?: 0
    XposedBridge.logOpenCellIdEvery(
        "buildFakeSignalStrength:called:$cellCount",
        "buildFakeSignalStrength called cellJsonCount=$cellCount"
    )
    return try {
        val clazz = XposedHelpers.findClass("android.telephony.SignalStrength", classLoader)
        val signalStrength = XposedHelpers.newInstance(clazz)
        val dbm = signalDbm(firstCell(config) ?: org.json.JSONObject(), 0)
        val lteSignalClass =
            XposedHelpers.findClass("android.telephony.CellSignalStrengthLte", classLoader)
        val lteSignal = XposedHelpers.newInstance(lteSignalClass)
        try {
            XposedHelpers.setIntField(lteSignal, "mRsrp", dbm)
        } catch (_: Throwable) {
        }
        try {
            XposedHelpers.setIntField(lteSignal, "mRsrq", -10)
        } catch (_: Throwable) {
        }
        try {
            XposedHelpers.setIntField(lteSignal, "mRssnr", 300)
        } catch (_: Throwable) {
        }
        try {
            XposedHelpers.setIntField(lteSignal, "mSignalStrength", dbm + 113)
        } catch (_: Throwable) {
        }
        try {
            XposedHelpers.setObjectField(signalStrength, "mLte", lteSignal)
        } catch (_: Throwable) {
        }
        try {
            XposedHelpers.setObjectField(
                signalStrength,
                "mCellSignalStrengths",
                listOf(lteSignal)
            )
        } catch (_: Throwable) {
        }
        try {
            XposedHelpers.setBooleanField(signalStrength, "mLteAsPrimaryInNrNsa", true)
        } catch (_: Throwable) {
        }
        XposedBridge.logOpenCellIdEvery(
            "buildFakeSignalStrength:success:$dbm",
            "buildFakeSignalStrength success dbm=$dbm"
        )
        signalStrength
    } catch (e: Throwable) {
        XposedBridge.logOpenCellId("buildFakeSignalStrength failed", e)
        null
    }
}

/**
 * 构造 CellIdentity 派生类，完整兼容 Android 9 ~ Android 14+。
 * 按以下优先级尝试：
 *   1. 各 Android 版本已知的有参构造器（最优，字段由构造器写入）
 *   2. sun.misc.Unsafe.allocateInstance + 反射写字段
 *      （字段初始值为 0 而非 MAX_VALUE，避免 JIT 内联问题）
 *   3. 最小参数构造器 + 默认值填充（最后手段）
 */
internal fun LocationHooker.constructCellIdentityByType(
    type: String,
    clazz: Class<*>,
    mcc: Int, mccStr: String,
    mnc: Int, mncStr: String,
    tacOrLac: Int, ciOrCid: Int, pci: Int
): Any {
    val ctors = clazz.declaredConstructors.onEach { it.isAccessible = true }

    // 按参数个数匹配构造器，调用失败则返回 null
    fun tryNewInstance(vararg args: Any?): Any? = ctors
        .firstOrNull { it.parameterCount == args.size }
        ?.runCatching { newInstance(*args) }
        ?.getOrNull()

    // 阶段一：尝试各版本有参构造器
    val identity: Any? = when (type) {
        "LTE" -> {
            // Android 9 / API 28: (int mcc, int mnc, int ci, int pci, int tac) — 5 参数
            tryNewInstance(mcc, mnc, ciOrCid, pci, tacOrLac)
            // Android 10 / API 29: (int ci, int pci, int tac, int earfcn, int bandwidth, String mcc, String mnc, String alphaLong, String alphaShort) — 9 参数
                ?: tryNewInstance(ciOrCid, pci, tacOrLac, 0, 0, mccStr, mncStr, "", "")
                // Android 11+ / API 30+: (int ci, int pci, int tac, int earfcn, int[] bands, int bandwidth, String mcc, String mnc, String alphaLong, String alphaShort, Collection, ClosedSubscriberGroupInfo) — 12 参数
                ?: tryNewInstance(
                    ciOrCid,
                    pci,
                    tacOrLac,
                    0,
                    IntArray(0),
                    0,
                    mccStr,
                    mncStr,
                    "",
                    "",
                    emptyList<Any>(),
                    null
                )
        }

        "GSM" -> {
            // Android 9 / API 28: (int mcc, int mnc, int lac, int cid) — 4 参数
            tryNewInstance(mcc, mnc, tacOrLac, ciOrCid)
            // Android 9 / API 28: (int mcc, int mnc, int lac, int cid, int arfcn, int bsic) — 6 参数
                ?: tryNewInstance(mcc, mnc, tacOrLac, ciOrCid, 0, 0)
                // Android 10 / API 29: (int lac, int cid, int arfcn, int bsic, String mcc, String mnc, String alphaLong, String alphaShort) — 8 参数
                ?: tryNewInstance(tacOrLac, ciOrCid, 0, 0, mccStr, mncStr, "", "")
                // Android 11+ / API 30+: 10 参数
                ?: tryNewInstance(
                    tacOrLac,
                    ciOrCid,
                    0,
                    0,
                    mccStr,
                    mncStr,
                    "",
                    "",
                    emptyList<Any>(),
                    null
                )
        }

        "WCDMA", "UMTS" -> {
            // Android 9: (int mcc, int mnc, int lac, int cid, int psc, int uarfcn) — 6 参数
            tryNewInstance(mcc, mnc, tacOrLac, ciOrCid, pci, 0)
            // Android 10: (int lac, int cid, int psc, int uarfcn, String mcc, String mnc, String alphaLong, String alphaShort) — 8 参数
                ?: tryNewInstance(tacOrLac, ciOrCid, pci, 0, mccStr, mncStr, "", "")
                // Android 11+: 10 参数
                ?: tryNewInstance(
                    tacOrLac,
                    ciOrCid,
                    pci,
                    0,
                    mccStr,
                    mncStr,
                    "",
                    "",
                    emptyList<Any>(),
                    null
                )
        }

        "NR" -> {
            // Android 10: (int pci, int tac, int nrArfcn, String mcc, String mnc, long nci, String alphaLong, String alphaShort) — 8 参数
            tryNewInstance(pci, tacOrLac, 0, mccStr, mncStr, ciOrCid.toLong(), "", "")
            // Android 11+: (int pci, int tac, int nrArfcn, int[] bands, String mcc, String mnc, long nci, String alphaLong, String alphaShort, Collection additionalPlmns) — 10 参数
                ?: tryNewInstance(
                    pci,
                    tacOrLac,
                    0,
                    IntArray(0),
                    mccStr,
                    mncStr,
                    ciOrCid.toLong(),
                    "",
                    "",
                    emptyList<Any>()
                )
        }

        else -> null
    }

    val arraySetClass = XposedHelpers.findClassIfExists("android.util.ArraySet", clazz.classLoader)
    val emptyPlmnSet: Any = if (arraySetClass != null) {
        try {
            XposedHelpers.newInstance(arraySetClass)
        } catch (_: Throwable) {
            java.util.Collections.emptySet<String>()
        }
    } else {
        java.util.Collections.emptySet<String>()
    }

    fun sanitizeCellIdentity(target: Any) {
        try {
            val plmns = XposedHelpers.getObjectField(target, "mAdditionalPlmns")
            if (plmns == null) {
                XposedHelpers.setObjectField(target, "mAdditionalPlmns", emptyPlmnSet)
            }
        } catch (_: Throwable) {
            try {
                XposedHelpers.setObjectField(target, "mAdditionalPlmns", emptyPlmnSet)
            } catch (_: Throwable) {}
        }
        try {
            if (XposedHelpers.getObjectField(target, "mAlphaLong") == null) {
                XposedHelpers.setObjectField(target, "mAlphaLong", "")
            }
        } catch (_: Throwable) {}
        try {
            if (XposedHelpers.getObjectField(target, "mAlphaShort") == null) {
                XposedHelpers.setObjectField(target, "mAlphaShort", "")
            }
        } catch (_: Throwable) {}
    }

    if (identity != null) {
        sanitizeCellIdentity(identity)
        return identity
    }

    // 阶段二：Unsafe.allocateInstance + 反射写字段
    // 字段初始值为 0（非 MAX_VALUE），避免 JIT 内联问题
    try {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val unsafeField = unsafeClass.getDeclaredField("theUnsafe")
        unsafeField.isAccessible = true
        val unsafe = unsafeField.get(null)
        val allocate = unsafeClass.getMethod("allocateInstance", Class::class.java)
        val obj = allocate.invoke(unsafe, clazz) as Any

        // 设置类型标识（CellIdentity.mType）
        val typeInt = when (type) {
            "GSM" -> 1; "LTE" -> 3; "WCDMA", "UMTS" -> 4; "NR" -> 6; else -> 3
        }
        try {
            XposedHelpers.setIntField(obj, "mType", typeInt)
        } catch (_: Throwable) {
        }
        // MCC/MNC（Int 版 Android 9，String 版 Android 10+）
        try {
            XposedHelpers.setIntField(obj, "mMcc", mcc)
        } catch (_: Throwable) {
        }
        try {
            XposedHelpers.setIntField(obj, "mMnc", mnc)
        } catch (_: Throwable) {
        }
        try {
            XposedHelpers.setObjectField(obj, "mMccStr", mccStr)
        } catch (_: Throwable) {
        }
        try {
            XposedHelpers.setObjectField(obj, "mMncStr", mncStr)
        } catch (_: Throwable) {
        }
        try {
            XposedHelpers.setObjectField(obj, "mAlphaLong", "")
        } catch (_: Throwable) {
        }
        try {
            XposedHelpers.setObjectField(obj, "mAlphaShort", "")
        } catch (_: Throwable) {
        }

        when (type) {
            "LTE" -> {
                try {
                    XposedHelpers.setIntField(obj, "mCi", ciOrCid)
                } catch (_: Throwable) {
                }
                try {
                    XposedHelpers.setIntField(obj, "mTac", tacOrLac)
                } catch (_: Throwable) {
                }
                try {
                    XposedHelpers.setIntField(obj, "mPci", pci)
                } catch (_: Throwable) {
                }
                try {
                    XposedHelpers.setObjectField(obj, "mBands", IntArray(0))
                } catch (_: Throwable) {
                }
            }

            "GSM" -> {
                try {
                    XposedHelpers.setIntField(obj, "mLac", tacOrLac)
                } catch (_: Throwable) {
                }
                try {
                    XposedHelpers.setIntField(obj, "mCid", ciOrCid)
                } catch (_: Throwable) {
                }
            }

            "WCDMA", "UMTS" -> {
                try {
                    XposedHelpers.setIntField(obj, "mLac", tacOrLac)
                } catch (_: Throwable) {
                }
                try {
                    XposedHelpers.setIntField(obj, "mCid", ciOrCid)
                } catch (_: Throwable) {
                }
                try {
                    XposedHelpers.setIntField(obj, "mPsc", pci)
                } catch (_: Throwable) {
                }
            }

            "NR" -> {
                try {
                    XposedHelpers.setIntField(obj, "mTac", tacOrLac)
                } catch (_: Throwable) {
                }
                try {
                    XposedHelpers.setLongField(obj, "mNci", ciOrCid.toLong())
                } catch (_: Throwable) {
                }
                try {
                    XposedHelpers.setIntField(obj, "mPci", pci)
                } catch (_: Throwable) {
                }
                try {
                    XposedHelpers.setObjectField(obj, "mBands", IntArray(0))
                } catch (_: Throwable) {
                }
            }
        }
        XposedBridge.log("[LocationSpoofer][CellMock] Unsafe.allocateInstance succeeded for $type: CI=$ciOrCid, TAC=$tacOrLac")
        sanitizeCellIdentity(obj)
        return obj
    } catch (e: Throwable) {
        XposedBridge.log("[LocationSpoofer][CellMock] Unsafe failed for $type: $e")
    }

    // 阶段三：最小参数构造器 + 安全默认值填充（绝对保底）
    val minCtor = ctors.minByOrNull { it.parameterCount }
        ?: throw IllegalStateException("No constructors for ${clazz.name}")
    val safeArgs = minCtor.parameterTypes.map { t ->
        when {
            t == Int::class.javaPrimitiveType -> 0
            t == Long::class.javaPrimitiveType -> 0L
            t == Boolean::class.javaPrimitiveType -> false
            t == Float::class.javaPrimitiveType -> 0f
            t == Double::class.javaPrimitiveType -> 0.0
            t == IntArray::class.java -> IntArray(0)
            t == java.util.Collection::class.java || t.isAssignableFrom(java.util.ArrayList::class.java) -> emptyList<Any>()
            else -> null
        }
    }.toTypedArray()
    val fallbackObj = try {
        minCtor.newInstance(*safeArgs)
    } catch (e: Throwable) {
        throw IllegalStateException("Cannot construct ${clazz.name}: $e")
    }
    // 写字段
    try {
        XposedHelpers.setIntField(fallbackObj, "mMcc", mcc)
    } catch (_: Throwable) {
    }
    try {
        XposedHelpers.setIntField(fallbackObj, "mMnc", mnc)
    } catch (_: Throwable) {
    }
    try {
        XposedHelpers.setObjectField(fallbackObj, "mMccStr", mccStr)
    } catch (_: Throwable) {
    }
    try {
        XposedHelpers.setObjectField(fallbackObj, "mMncStr", mncStr)
    } catch (_: Throwable) {
    }
    when (type) {
        "LTE" -> {
            try {
                XposedHelpers.setIntField(fallbackObj, "mCi", ciOrCid)
            } catch (_: Throwable) {
            }
            try {
                XposedHelpers.setIntField(fallbackObj, "mTac", tacOrLac)
            } catch (_: Throwable) {
            }
            try {
                XposedHelpers.setIntField(fallbackObj, "mPci", pci)
            } catch (_: Throwable) {
            }
        }

        "GSM" -> {
            try {
                XposedHelpers.setIntField(fallbackObj, "mLac", tacOrLac)
            } catch (_: Throwable) {
            }
            try {
                XposedHelpers.setIntField(fallbackObj, "mCid", ciOrCid)
            } catch (_: Throwable) {
            }
        }

        "WCDMA", "UMTS" -> {
            try {
                XposedHelpers.setIntField(fallbackObj, "mLac", tacOrLac)
            } catch (_: Throwable) {
            }
            try {
                XposedHelpers.setIntField(fallbackObj, "mCid", ciOrCid)
            } catch (_: Throwable) {
            }
            try {
                XposedHelpers.setIntField(fallbackObj, "mPsc", pci)
            } catch (_: Throwable) {
            }
        }

        "NR" -> {
            try {
                XposedHelpers.setIntField(fallbackObj, "mTac", tacOrLac)
            } catch (_: Throwable) {
            }
            try {
                XposedHelpers.setLongField(fallbackObj, "mNci", ciOrCid.toLong())
            } catch (_: Throwable) {
            }
            try {
                XposedHelpers.setIntField(fallbackObj, "mPci", pci)
            } catch (_: Throwable) {
            }
        }
    }
    XposedBridge.log("[LocationSpoofer][CellMock] MinCtor fallback used for $type identity")
    sanitizeCellIdentity(fallbackObj)
    return fallbackObj
}
