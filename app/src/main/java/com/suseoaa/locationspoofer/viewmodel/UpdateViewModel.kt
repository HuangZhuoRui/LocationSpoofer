package com.suseoaa.locationspoofer.viewmodel

import android.app.DownloadManager
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.suseoaa.locationspoofer.data.model.GithubRelease
import com.suseoaa.locationspoofer.utils.UpdateManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray

data class UpdateUiState(
    val isLoading: Boolean = false,
    val error: String? = null,
    val releases: List<GithubRelease> = emptyList(),
    val activeDownloadId: Long? = null,
    val activeDownloadUrl: String? = null,
    val downloadProgress: Int = 0,
    val downloadStatus: Int = DownloadManager.STATUS_PENDING
)

class UpdateViewModel(private val context: Context) : ViewModel() {
    private val _uiState = MutableStateFlow(UpdateUiState())
    val uiState: StateFlow<UpdateUiState> = _uiState.asStateFlow()

    private val updateManager = UpdateManager(context)
    private val okHttpClient = OkHttpClient()

    fun fetchReleases() {
        _uiState.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url("https://api.github.com/repos/HuangZhuoRui/LocationSpoofer/releases")
                    .header("Accept", "application/vnd.github.v3+json")
                    .build()

                val response = okHttpClient.newCall(request).execute()
                if (!response.isSuccessful) {
                    throw Exception("Failed to fetch updates: ${response.code}")
                }

                val jsonStr = response.body?.string() ?: "[]"
                val jsonArray = JSONArray(jsonStr)
                val releaseList = mutableListOf<GithubRelease>()

                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    val tagName = obj.optString("tag_name", "Unknown")
                    val body = obj.optString("body", "")
                    val publishedAt = obj.optString("published_at", "")
                    val isPrerelease = obj.optBoolean("prerelease", false)

                    var downloadUrl: String? = null
                    var downloadUrl32Bit: String? = null
                    val assets = obj.optJSONArray("assets")
                    if (assets != null) {
                        for (j in 0 until assets.length()) {
                            val asset = assets.getJSONObject(j)
                            val assetName = asset.optString("name", "")
                            if (assetName.endsWith(".apk")) {
                                val url = asset.optString("browser_download_url")
                                if (assetName.contains("armeabi-v7a") || assetName.contains("32")) {
                                    downloadUrl32Bit = url
                                } else if (assetName.contains("arm64-v8a")) {
                                    downloadUrl = url
                                } else {
                                    // 通用安装包，如果没有专门的64位，就默认用通用包作为主链接
                                    if (downloadUrl == null) {
                                        downloadUrl = url
                                    }
                                }
                            }
                        }
                    }

                    releaseList.add(
                        GithubRelease(
                            tagName,
                            body,
                            downloadUrl,
                            downloadUrl32Bit,
                            publishedAt,
                            isPrerelease
                        )
                    )
                }

                withContext(Dispatchers.Main) {
                    _uiState.update { it.copy(isLoading = false, releases = releaseList) }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _uiState.update { it.copy(isLoading = false, error = e.message) }
                }
            }
        }
    }

    fun startDownload(url: String, versionName: String) {
        val fileName = "LocationSpoofer_$versionName.apk"
        val downloadId = updateManager.downloadApk(url, fileName)
        _uiState.update {
            it.copy(
                activeDownloadId = downloadId,
                activeDownloadUrl = url,
                downloadProgress = 0,
                downloadStatus = DownloadManager.STATUS_PENDING
            )
        }
        monitorDownload(downloadId)
    }

    private fun monitorDownload(downloadId: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            while (true) {
                val status = updateManager.getDownloadStatus(downloadId)
                val progress = updateManager.getDownloadProgress(downloadId)

                withContext(Dispatchers.Main) {
                    _uiState.update {
                        it.copy(
                            downloadStatus = status,
                            downloadProgress = progress
                        )
                    }
                }

                if (status == DownloadManager.STATUS_SUCCESSFUL || status == DownloadManager.STATUS_FAILED) {
                    break
                }
                delay(500)
            }
        }
    }

    fun installApk() {
        _uiState.value.activeDownloadId?.let { downloadId ->
            updateManager.installApk(downloadId)
        }
    }

    fun cancelDownload() {
        _uiState.value.activeDownloadId?.let { downloadId ->
            updateManager.cancelDownload(downloadId)
            _uiState.update {
                it.copy(
                    activeDownloadId = null,
                    activeDownloadUrl = null,
                    downloadProgress = 0,
                    downloadStatus = DownloadManager.STATUS_PENDING
                )
            }
        }
    }
}
