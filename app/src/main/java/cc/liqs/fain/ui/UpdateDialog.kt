package cc.liqs.fain.ui

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import cc.liqs.fain.ui.theme.FainTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

data class UpdateInfo(
    val latestVersion: String,
    val releaseUrl: String,
    val downloadUrl: String,
    val releaseNotes: String?,
)

enum class UpdateDialogState {
    DETAILS,
    DOWNLOADING,
    PERMISSION_REQUIRED,
    READY_TO_INSTALL,
    ERROR
}

object UpdateChecker {
    private const val GITHUB_RELEASES_API = "https://api.github.com/repos/lqsaf/fain-app/releases/latest"
    const val GITHUB_RELEASES_WEB = "https://github.com/lqsaf/fain-app/releases"

    suspend fun checkForUpdates(currentVersion: String): UpdateInfo? = withContext(Dispatchers.IO) {
        val apiResult = tryFetchFromApi(currentVersion)
        if (apiResult != null) return@withContext apiResult

        Log.d("UpdateChecker", "API check failed or rate-limited, trying redirect fallback...")
        return@withContext tryFetchFromRedirectFallback(currentVersion)
    }

    private fun tryFetchFromApi(currentVersion: String): UpdateInfo? {
        try {
            val url = URL(GITHUB_RELEASES_API)
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 6000
                readTimeout = 6000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "FainApp")
            }
            val code = connection.responseCode
            if (code == 200) {
                val responseText = connection.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(responseText)
                val tagName = json.optString("tag_name", "").trim()
                val htmlUrl = json.optString("html_url", GITHUB_RELEASES_WEB)
                val body = json.optString("body", "").trim()

                var downloadUrl = ""
                val assets = json.optJSONArray("assets")
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val asset = assets.optJSONObject(i)
                        val name = asset?.optString("name", "") ?: ""
                        if (name.endsWith(".apk")) {
                            downloadUrl = asset?.optString("browser_download_url") ?: ""
                            break
                        }
                    }
                }

                if (downloadUrl.isBlank() && tagName.isNotEmpty()) {
                    downloadUrl = "https://github.com/lqsaf/fain-app/releases/download/$tagName/fain-$tagName.apk"
                }

                if (tagName.isNotEmpty() && isNewerVersion(currentVersion, tagName)) {
                    return UpdateInfo(
                        latestVersion = tagName,
                        releaseUrl = htmlUrl,
                        downloadUrl = downloadUrl.ifBlank { htmlUrl },
                        releaseNotes = body.ifBlank { null },
                    )
                }
            } else {
                Log.w("UpdateChecker", "GitHub API returned code $code")
            }
        } catch (e: Exception) {
            Log.e("UpdateChecker", "Error checking GitHub API", e)
        }
        return null
    }

    private fun tryFetchFromRedirectFallback(currentVersion: String): UpdateInfo? {
        try {
            val url = URL("https://github.com/lqsaf/fain-app/releases/latest")
            val connection = (url.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                requestMethod = "GET"
                connectTimeout = 6000
                readTimeout = 6000
                setRequestProperty("User-Agent", "FainApp")
            }
            val redirectUrl = connection.getHeaderField("Location") ?: ""
            if (redirectUrl.contains("/releases/tag/")) {
                val tagName = redirectUrl.substringAfterLast("/tag/").trim()
                if (tagName.isNotEmpty() && isNewerVersion(currentVersion, tagName)) {
                    val directApkUrl = "https://github.com/lqsaf/fain-app/releases/download/$tagName/fain-$tagName.apk"
                    return UpdateInfo(
                        latestVersion = tagName,
                        releaseUrl = redirectUrl,
                        downloadUrl = directApkUrl,
                        releaseNotes = null,
                    )
                }
            }
        } catch (e: Exception) {
            Log.e("UpdateChecker", "Error checking redirect fallback", e)
        }
        return null
    }

    fun isNewerVersion(current: String, latest: String): Boolean {
        val cleanCurrent = current.trim().lowercase().removePrefix("v")
        val cleanLatest = latest.trim().lowercase().removePrefix("v")

        val currentParts = cleanCurrent.split('.').mapNotNull { it.takeWhile { char -> char.isDigit() }.toIntOrNull() }
        val latestParts = cleanLatest.split('.').mapNotNull { it.takeWhile { char -> char.isDigit() }.toIntOrNull() }

        val maxLen = maxOf(currentParts.size, latestParts.size)
        for (i in 0 until maxLen) {
            val c = currentParts.getOrElse(i) { 0 }
            val l = latestParts.getOrElse(i) { 0 }
            if (l > c) return true
            if (l < c) return false
        }
        return false
    }

    fun getAppVersion(context: Context): String {
        return runCatching {
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(context.packageName, android.content.pm.PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
            packageInfo.versionName ?: "0.4"
        }.getOrNull() ?: "0.4"
    }

    suspend fun downloadApk(
        downloadUrl: String,
        destinationFile: File,
        onProgress: (bytesDownloaded: Long, totalBytes: Long) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        var currentUrl = downloadUrl
        var redirects = 0
        val maxRedirects = 8

        try {
            destinationFile.parentFile?.mkdirs()
            if (destinationFile.exists()) {
                destinationFile.delete()
            }
            val tempFile = File(destinationFile.parentFile, "${destinationFile.name}.tmp")
            if (tempFile.exists()) {
                tempFile.delete()
            }

            var connection: HttpURLConnection? = null
            while (redirects < maxRedirects) {
                val url = URL(currentUrl)
                connection = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15000
                    readTimeout = 30000
                    instanceFollowRedirects = false
                    setRequestProperty("User-Agent", "FainApp")
                    setRequestProperty("Accept", "application/octet-stream, */*")
                }
                val responseCode = connection.responseCode
                if (responseCode in 300..399) {
                    val newUrl = connection.getHeaderField("Location")
                    connection.disconnect()
                    if (newUrl.isNullOrBlank()) {
                        return@withContext false
                    }
                    currentUrl = if (newUrl.startsWith("http://") || newUrl.startsWith("https://")) {
                        newUrl
                    } else {
                        URL(url, newUrl).toString()
                    }
                    redirects++
                } else if (responseCode == HttpURLConnection.HTTP_OK) {
                    break
                } else {
                    connection.disconnect()
                    return@withContext false
                }
            }

            val conn = connection ?: return@withContext false
            val totalBytes = conn.contentLengthLong
            var downloadedBytes = 0L

            conn.inputStream.use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(16 * 1024)
                    var bytesRead: Int
                    var lastProgressUpdate = 0L
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        downloadedBytes += bytesRead
                        val now = System.currentTimeMillis()
                        if (now - lastProgressUpdate > 100 || downloadedBytes == totalBytes) {
                            lastProgressUpdate = now
                            onProgress(downloadedBytes, totalBytes)
                        }
                    }
                    output.flush()
                }
            }
            conn.disconnect()

            if (tempFile.renameTo(destinationFile)) {
                true
            } else {
                tempFile.copyTo(destinationFile, overwrite = true)
                tempFile.delete()
                true
            }
        } catch (e: Exception) {
            Log.e("UpdateChecker", "Download APK error", e)
            false
        }
    }

    fun canInstallPackages(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    fun openInstallPermissionSettings(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val intent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                "package:${context.packageName}".toUri()
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { context.startActivity(intent) }
        }
    }

    fun installApk(context: Context, apkFile: File) {
        try {
            val apkUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e("UpdateChecker", "Install APK error", e)
        }
    }
}

@Composable
fun UpdateDialog(
    updateInfo: UpdateInfo,
    currentVersion: String,
    bgColor: Color,
    textColor: Color,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var state by remember { mutableStateOf(UpdateDialogState.DETAILS) }
    var downloadProgress by remember { mutableFloatStateOf(0f) }
    var downloadedBytes by remember { mutableLongStateOf(0L) }
    var totalBytes by remember { mutableLongStateOf(0L) }
    var downloadJob by remember { mutableStateOf<Job?>(null) }

    val apkFile = remember(updateInfo.latestVersion) {
        File(context.cacheDir, "updates/fain-${updateInfo.latestVersion}.apk")
    }

    val lifecycleOwner = context as? LifecycleOwner
    DisposableEffect(lifecycleOwner, state) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                if (state == UpdateDialogState.PERMISSION_REQUIRED && UpdateChecker.canInstallPackages(context)) {
                    if (apkFile.exists()) {
                        state = UpdateDialogState.READY_TO_INSTALL
                        UpdateChecker.installApk(context, apkFile)
                    }
                }
            }
        }
        lifecycleOwner?.lifecycle?.addObserver(observer)
        onDispose {
            lifecycleOwner?.lifecycle?.removeObserver(observer)
        }
    }

    fun startDownload() {
        downloadJob?.cancel()
        state = UpdateDialogState.DOWNLOADING
        downloadProgress = 0f
        downloadedBytes = 0L
        totalBytes = 0L

        downloadJob = coroutineScope.launch {
            val success = UpdateChecker.downloadApk(
                downloadUrl = updateInfo.downloadUrl,
                destinationFile = apkFile,
                onProgress = { current, total ->
                    downloadedBytes = current
                    totalBytes = total
                    if (total > 0) {
                        downloadProgress = current.toFloat() / total.toFloat()
                    }
                }
            )

            if (success && apkFile.exists()) {
                if (UpdateChecker.canInstallPackages(context)) {
                    state = UpdateDialogState.READY_TO_INSTALL
                    UpdateChecker.installApk(context, apkFile)
                } else {
                    state = UpdateDialogState.PERMISSION_REQUIRED
                }
            } else {
                state = UpdateDialogState.ERROR
            }
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
        apkFile.delete()
        state = UpdateDialogState.DETAILS
    }

    Dialog(
        onDismissRequest = {
            downloadJob?.cancel()
            onDismiss()
        },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        UpdateDialogLayout(
            updateInfo = updateInfo,
            currentVersion = currentVersion,
            state = state,
            downloadProgress = downloadProgress,
            downloadedBytes = downloadedBytes,
            totalBytes = totalBytes,
            bgColor = bgColor,
            textColor = textColor,
            onDismiss = {
                downloadJob?.cancel()
                onDismiss()
            },
            onStartDownload = { startDownload() },
            onCancelDownload = { cancelDownload() },
            onOpenSettings = {
                UpdateChecker.openInstallPermissionSettings(context)
            },
            onInstall = {
                if (apkFile.exists()) {
                    UpdateChecker.installApk(context, apkFile)
                } else {
                    startDownload()
                }
            },
            onOpenBrowser = {
                val intent = Intent(Intent.ACTION_VIEW, updateInfo.releaseUrl.toUri()).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                onDismiss()
            }
        )
    }
}

@Composable
private fun UpdateDialogLayout(
    updateInfo: UpdateInfo,
    currentVersion: String,
    state: UpdateDialogState,
    downloadProgress: Float,
    downloadedBytes: Long,
    totalBytes: Long,
    bgColor: Color,
    textColor: Color,
    onDismiss: () -> Unit,
    onStartDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onOpenSettings: () -> Unit,
    onInstall: () -> Unit,
    onOpenBrowser: () -> Unit,
) {
    val isLight = bgColor.luminance() > 0.5f

    val textMain = if ((textColor != Color.Unspecified) && (textColor != Color.Black) && (textColor != Color.White)) {
        textColor
    } else {
        if (isLight) Color(0xFF121212) else Color(0xFFFFFFFF)
    }

    val textMuted = if (isLight) Color(0xFF4A4A46) else Color(0xFF9A9A9A)
    val textDim = if (isLight) Color(0xFF85857F) else Color(0xFF525252)
    val borderSubtle = if (isLight) Color(0xFFDDDDD5) else Color(0xFF1F1F1F)
    val analysisBg = if (isLight) Color(0xFFEAEAE2) else Color(0xFF0A0A0A)
    val dialogBg = if (isLight) Color(0xFFF4F4F0) else Color(0xFF000000)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.75f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss
            ),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .width(360.dp)
                .background(dialogBg)
                .border(1.dp, borderSubtle)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { /* prevent click through */ }
                .padding(20.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth()
            ) {
                val headerTag = when (state) {
                    UpdateDialogState.DETAILS -> "// доступно обновление"
                    UpdateDialogState.DOWNLOADING -> "// загрузка обновления"
                    UpdateDialogState.PERMISSION_REQUIRED -> "// требуется разрешение"
                    UpdateDialogState.READY_TO_INSTALL -> "// обновление готово"
                    UpdateDialogState.ERROR -> "// ошибка загрузки"
                }

                Text(
                    text = headerTag,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = textDim,
                    letterSpacing = 2.sp
                )

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = "fain ${updateInfo.latestVersion}",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = textMain,
                    letterSpacing = 1.sp
                )

                Spacer(modifier = Modifier.height(4.dp))

                val subtitleText = when (state) {
                    UpdateDialogState.DETAILS -> "текущая версия: v$currentVersion"
                    UpdateDialogState.DOWNLOADING -> {
                        val pct = (downloadProgress * 100).toInt()
                        if (totalBytes > 0) {
                            "$pct% (${formatFileSize(downloadedBytes)} / ${formatFileSize(totalBytes)})"
                        } else if (downloadedBytes > 0) {
                            formatFileSize(downloadedBytes)
                        } else {
                            "соединение..."
                        }
                    }
                    UpdateDialogState.PERMISSION_REQUIRED -> "установка из неизвестных источников"
                    UpdateDialogState.READY_TO_INSTALL -> "установщик пакета запущен"
                    UpdateDialogState.ERROR -> "не удалось скачать apk"
                }

                Text(
                    text = subtitleText,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = textMuted,
                    letterSpacing = 0.5.sp
                )

                Spacer(modifier = Modifier.height(16.dp))

                when (state) {
                    UpdateDialogState.DETAILS -> {
                        if (!updateInfo.releaseNotes.isNullOrBlank()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(analysisBg)
                                    .border(width = 1.dp, color = borderSubtle)
                                    .padding(12.dp)
                            ) {
                                Column {
                                    Text(
                                        text = "ЧТО НОВОГО:",
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp,
                                        color = textDim,
                                        letterSpacing = 1.5.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )

                                    Spacer(modifier = Modifier.height(6.dp))

                                    val scrollState = rememberScrollState()
                                    Text(
                                        text = cleanReleaseNotes(updateInfo.releaseNotes),
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 13.sp,
                                        color = textMuted,
                                        lineHeight = 18.sp,
                                        modifier = Modifier
                                            .heightIn(max = 140.dp)
                                            .verticalScroll(scrollState)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(20.dp))
                        } else {
                            Spacer(modifier = Modifier.height(8.dp))
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TermButton(
                                text = "[позже]",
                                isActive = false,
                                textMain = textMain,
                                textMuted = textMuted,
                                borderSubtle = borderSubtle,
                                dialogBg = dialogBg,
                                onClick = onDismiss
                            )

                            Spacer(modifier = Modifier.width(10.dp))

                            TermButton(
                                text = "[обновиться]",
                                isActive = true,
                                textMain = textMain,
                                textMuted = textMuted,
                                borderSubtle = borderSubtle,
                                dialogBg = dialogBg,
                                onClick = onStartDownload
                            )
                        }
                    }

                    UpdateDialogState.DOWNLOADING -> {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(analysisBg)
                                .border(width = 1.dp, color = borderSubtle)
                                .padding(12.dp)
                        ) {
                            Column {
                                Text(
                                    text = formatProgressBar(downloadProgress),
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = textMain,
                                    letterSpacing = 1.sp
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "загрузка apk напрямую...",
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    color = textDim
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(20.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TermButton(
                                text = "[отмена]",
                                isActive = false,
                                textMain = textMain,
                                textMuted = textMuted,
                                borderSubtle = borderSubtle,
                                dialogBg = dialogBg,
                                onClick = onCancelDownload
                            )
                        }
                    }

                    UpdateDialogState.PERMISSION_REQUIRED -> {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(analysisBg)
                                .border(width = 1.dp, color = borderSubtle)
                                .padding(12.dp)
                        ) {
                            Text(
                                text = "для завершения установки разрешите fain установку приложений в системных настройках.",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                color = textMuted,
                                lineHeight = 17.sp
                            )
                        }

                        Spacer(modifier = Modifier.height(20.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TermButton(
                                text = "[позже]",
                                isActive = false,
                                textMain = textMain,
                                textMuted = textMuted,
                                borderSubtle = borderSubtle,
                                dialogBg = dialogBg,
                                onClick = onDismiss
                            )

                            Spacer(modifier = Modifier.width(10.dp))

                            TermButton(
                                text = "[настройки]",
                                isActive = true,
                                textMain = textMain,
                                textMuted = textMuted,
                                borderSubtle = borderSubtle,
                                dialogBg = dialogBg,
                                onClick = onOpenSettings
                            )
                        }
                    }

                    UpdateDialogState.READY_TO_INSTALL -> {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(analysisBg)
                                .border(width = 1.dp, color = borderSubtle)
                                .padding(12.dp)
                        ) {
                            Text(
                                text = "обновление скачано. если системный установщик не открылся, нажмите [установить].",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                color = textMuted,
                                lineHeight = 17.sp
                            )
                        }

                        Spacer(modifier = Modifier.height(20.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TermButton(
                                text = "[закрыть]",
                                isActive = false,
                                textMain = textMain,
                                textMuted = textMuted,
                                borderSubtle = borderSubtle,
                                dialogBg = dialogBg,
                                onClick = onDismiss
                            )

                            Spacer(modifier = Modifier.width(10.dp))

                            TermButton(
                                text = "[установить]",
                                isActive = true,
                                textMain = textMain,
                                textMuted = textMuted,
                                borderSubtle = borderSubtle,
                                dialogBg = dialogBg,
                                onClick = onInstall
                            )
                        }
                    }

                    UpdateDialogState.ERROR -> {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(analysisBg)
                                .border(width = 1.dp, color = borderSubtle)
                                .padding(12.dp)
                        ) {
                            Text(
                                text = "не удалось скачать apk. проверьте подключение или откройте страницу релиза в браузере.",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                color = textMuted,
                                lineHeight = 17.sp
                            )
                        }

                        Spacer(modifier = Modifier.height(20.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TermButton(
                                text = "[браузер]",
                                isActive = false,
                                textMain = textMain,
                                textMuted = textMuted,
                                borderSubtle = borderSubtle,
                                dialogBg = dialogBg,
                                onClick = onOpenBrowser
                            )

                            Spacer(modifier = Modifier.width(10.dp))

                            TermButton(
                                text = "[повторить]",
                                isActive = true,
                                textMain = textMain,
                                textMuted = textMuted,
                                borderSubtle = borderSubtle,
                                dialogBg = dialogBg,
                                onClick = onStartDownload
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TermButton(
    text: String,
    isActive: Boolean,
    textMain: Color,
    textMuted: Color,
    borderSubtle: Color,
    dialogBg: Color,
    onClick: () -> Unit
) {
    val buttonBg = if (isActive) textMain else Color.Transparent
    val textColor = if (isActive) dialogBg else textMuted
    val borderColor = if (isActive) textMain else borderSubtle

    Box(
        modifier = Modifier
            .background(buttonBg)
            .border(1.dp, borderColor)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            color = textColor,
            letterSpacing = 0.8.sp,
            fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal
        )
    }
}

private fun formatProgressBar(progress: Float, length: Int = 18): String {
    val clamped = progress.coerceIn(0f, 1f)
    val filled = (clamped * length).toInt()
    val empty = (length - filled).coerceAtLeast(0)
    val body = if (filled > 0) "=".repeat(maxOf(0, filled - 1)) + ">" else ""
    val spaces = " ".repeat(empty)
    return "[$body$spaces]"
}

private fun formatFileSize(bytes: Long): String {
    if (bytes <= 0) return ""
    val mb = bytes.toDouble() / (1024.0 * 1024.0)
    return String.format(Locale.US, "%.1f MB", mb)
}

private fun cleanReleaseNotes(notes: String): String {
    return notes
        .replace(Regex("<[^>]*>"), "")
        .replace(Regex("#+\\s*"), "")
        .trim()
}

@Preview(name = "Dark Theme", showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun UpdateDialogPreviewDark() {
    FainTheme(customBackground = Color.Black) {
        UpdateDialogLayout(
            updateInfo = UpdateInfo(
                latestVersion = "v0.5",
                releaseUrl = "https://github.com/lqsaf/fain-app/releases",
                downloadUrl = "",
                releaseNotes = "- Добавлена проверка обновлений\n- Фирменный темный стиль fain\n- Улучшена производительность"
            ),
            currentVersion = "0.4",
            state = UpdateDialogState.DETAILS,
            downloadProgress = 0.45f,
            downloadedBytes = 5_000_000L,
            totalBytes = 11_000_000L,
            bgColor = Color.Black,
            textColor = Color.White,
            onDismiss = {},
            onStartDownload = {},
            onCancelDownload = {},
            onOpenSettings = {},
            onInstall = {},
            onOpenBrowser = {}
        )
    }
}

@Preview(name = "Dark Theme Downloading", showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun UpdateDialogPreviewDownloading() {
    FainTheme(customBackground = Color.Black) {
        UpdateDialogLayout(
            updateInfo = UpdateInfo(
                latestVersion = "v0.5",
                releaseUrl = "https://github.com/lqsaf/fain-app/releases",
                downloadUrl = "",
                releaseNotes = null
            ),
            currentVersion = "0.4",
            state = UpdateDialogState.DOWNLOADING,
            downloadProgress = 0.65f,
            downloadedBytes = 7_150_000L,
            totalBytes = 11_000_000L,
            bgColor = Color.Black,
            textColor = Color.White,
            onDismiss = {},
            onStartDownload = {},
            onCancelDownload = {},
            onOpenSettings = {},
            onInstall = {},
            onOpenBrowser = {}
        )
    }
}

@Preview(name = "Light Theme", showBackground = true, backgroundColor = 0xFFF4F4F0)
@Composable
private fun UpdateDialogPreviewLight() {
    FainTheme(customBackground = Color(0xFFF4F4F0)) {
        UpdateDialogLayout(
            updateInfo = UpdateInfo(
                latestVersion = "v0.5",
                releaseUrl = "https://github.com/lqsaf/fain-app/releases",
                downloadUrl = "",
                releaseNotes = "- Светлая тема приложения\n- Исправление ошибок"
            ),
            currentVersion = "0.4",
            state = UpdateDialogState.DETAILS,
            downloadProgress = 0f,
            downloadedBytes = 0L,
            totalBytes = 0L,
            bgColor = Color(0xFFF4F4F0),
            textColor = Color(0xFF121212),
            onDismiss = {},
            onStartDownload = {},
            onCancelDownload = {},
            onOpenSettings = {},
            onInstall = {},
            onOpenBrowser = {}
        )
    }
}
