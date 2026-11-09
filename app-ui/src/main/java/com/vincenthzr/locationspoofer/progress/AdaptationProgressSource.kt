package com.vincenthzr.locationspoofer.progress

import android.content.Context
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 适配进度文档的来源：优先联网取仓库 main 分支的最新版（改了文档不用发版用户就能看到），
 * 取不到时依次退回上次成功获取的缓存、安装包内置的一份（构建时从仓库根目录复制进 assets）。
 */
class AdaptationProgressSource(context: Context) {

    enum class Origin { REMOTE, CACHE, BUNDLED }

    data class Document(val markdown: String, val origin: Origin)

    private val appContext = context.applicationContext
    private val cacheFile = File(appContext.filesDir, FILE_NAME)

    /** 不联网即可读到的版本：缓存优先，没有缓存用内置 */
    fun local(): Document {
        runCatching { cacheFile.readText() }.getOrNull()?.takeIf { it.isNotBlank() }?.let {
            return Document(it, Origin.CACHE)
        }
        val bundled = runCatching { appContext.assets.open(FILE_NAME).bufferedReader().use { it.readText() } }
        return Document(bundled.getOrDefault(""), Origin.BUNDLED)
    }

    /** 依次尝试 GitHub 与 jsDelivr 镜像（国内网络常连不上 raw.githubusercontent.com），成功后写入缓存 */
    suspend fun fetchRemote(): Document? = withContext(Dispatchers.IO) {
        for (url in REMOTE_URLS) {
            val text = runCatching {
                client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                    if (resp.isSuccessful) resp.body?.string() else null
                }
            }.getOrNull()
            // 只接受看起来确实是这份文档的内容，避免把代理 / 运营商的拦截页存进缓存
            if (text != null && text.contains("# 适配进度")) {
                runCatching { cacheFile.writeText(text) }
                return@withContext Document(text, Origin.REMOTE)
            }
        }
        null
    }

    private companion object {
        const val FILE_NAME = "ADAPTATION_PROGRESS.md"
        val REMOTE_URLS = listOf(
            "https://raw.githubusercontent.com/HuangZhuoRui/LocationSpoofer/main/$FILE_NAME",
            "https://cdn.jsdelivr.net/gh/HuangZhuoRui/LocationSpoofer@main/$FILE_NAME"
        )
        val client: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build()
    }
}
