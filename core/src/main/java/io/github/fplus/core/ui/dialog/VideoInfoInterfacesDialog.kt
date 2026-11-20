package io.github.fplus.core.ui.dialog

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Divider
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.fplus.core.hook.logic.DEFAULT_VIDEO_INFO_CODE
import io.github.fplus.core.hook.logic.RegionLibrary
import io.github.fplus.core.hook.logic.VideoInfoApi
import io.github.fplus.core.hook.logic.VideoInfoApiResult
import io.github.fplus.core.ui.component.FCard
import io.github.fplus.core.ui.component.FCardBorder
import io.github.fplus.core.ui.component.FDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val KEY_AREA = "area"
private const val KEY_STNAME = "stname"
private const val KEY_BOUND = "bound"
private const val KEY_OFFLINE = "offline"

private val SUCCESS_COLOR = Color(0xFF2E7D32)

/**
 * 视频信息接口管理。
 *
 * 用一个区划代码依次测试视频信息用到的 4 个接口，并展示/更新离线地区库文件，
 * 方便接口失效时快速定位是哪一项出了问题。
 */
@Composable
fun VideoInfoInterfacesDialog(
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var code by remember { mutableStateOf(DEFAULT_VIDEO_INFO_CODE) }
    var status by remember { mutableStateOf<RegionLibrary.Status?>(null) }
    var updating by remember { mutableStateOf(false) }
    var updateMessage by remember { mutableStateOf<String?>(null) }
    var allRunning by remember { mutableStateOf(false) }

    val results = remember { mutableStateMapOf<String, VideoInfoApiResult>() }
    val running = remember { mutableStateMapOf<String, Boolean>() }

    LaunchedEffect(Unit) {
        status = withContext(Dispatchers.IO) { RegionLibrary.status(context) }
    }

    fun startTest(key: String) {
        if (running[key] == true) return
        val value = code.ifBlank { DEFAULT_VIDEO_INFO_CODE }
        running[key] = true
        scope.launch {
            val result = when (key) {
                KEY_AREA -> VideoInfoApi.testArea(value)
                KEY_STNAME -> VideoInfoApi.testStname(value)
                KEY_BOUND -> VideoInfoApi.testBound(value)
                else -> VideoInfoApi.testOffline(context, code = value)
            }
            results[key] = result
            running[key] = false
        }
    }

    fun startAllTests() {
        if (allRunning) return
        allRunning = true
        scope.launch {
            for (key in listOf(KEY_AREA, KEY_STNAME, KEY_BOUND, KEY_OFFLINE)) {
                startTest(key)
            }
            // startTest 各自在独立协程里执行，这里等它们结束再恢复按钮
            while (running.values.any { it }) {
                kotlinx.coroutines.delay(100)
            }
            allRunning = false
        }
    }

    fun updateLibrary() {
        if (updating) return
        updating = true
        updateMessage = null
        scope.launch {
            val result = RegionLibrary.update(context)
            updating = false
            result.onSuccess {
                status = it
                results.remove(KEY_OFFLINE)
                updateMessage = "更新成功：${formatSize(it.bytes)}，${it.provinces} 个省级节点"
            }.onFailure {
                updateMessage = "更新失败：${it.message ?: it.javaClass.simpleName}"
            }
        }
    }

    FDialog(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 12.dp,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 24.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "视频信息接口管理",
                    modifier = Modifier
                        .weight(1f)
                        .padding(vertical = 16.dp),
                    style = MaterialTheme.typography.body1.copy(fontWeight = FontWeight.Bold),
                )
                Text(
                    text = "关闭",
                    modifier = Modifier
                        .clickable { onDismiss() }
                        .padding(horizontal = 12.dp, vertical = 16.dp),
                    style = MaterialTheme.typography.body1.copy(
                        color = MaterialTheme.colors.primary,
                    ),
                )
            }
            Divider()

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 560.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 14.dp),
            ) {
                Text(
                    text = "接口自检",
                    style = MaterialTheme.typography.body1.copy(fontWeight = FontWeight.Bold),
                )
                Text(
                    text = "用一个区划代码测试视频信息用到的 4 个接口，默认 $DEFAULT_VIDEO_INFO_CODE（成都武侯区）。",
                    modifier = Modifier.padding(top = 4.dp),
                    style = MaterialTheme.typography.body2.copy(
                        fontSize = 12.sp,
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.55f),
                    ),
                )

                FCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp),
                    border = FCardBorder(borderWidth = 1.0.dp),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "代码",
                            style = MaterialTheme.typography.body2.copy(
                                color = MaterialTheme.colors.onSurface.copy(alpha = 0.55f),
                            ),
                        )
                        BasicTextField(
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 10.dp, vertical = 10.dp),
                            value = code,
                            maxLines = 1,
                            singleLine = true,
                            textStyle = MaterialTheme.typography.body1,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            decorationBox = { innerTextField ->
                                if (code.isEmpty()) {
                                    Text(
                                        text = DEFAULT_VIDEO_INFO_CODE,
                                        style = MaterialTheme.typography.body1.copy(
                                            color = Color(0xFF999999),
                                        ),
                                    )
                                }
                                innerTextField()
                            },
                            onValueChange = { value ->
                                code = value.filter { it.isDigit() }.take(12)
                            },
                        )
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (allRunning) "全部测试中…" else "全部测试",
                        modifier = Modifier
                            .clickable(enabled = !allRunning) { startAllTests() }
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.body2.copy(
                            fontWeight = FontWeight.Bold,
                            color = if (allRunning) {
                                MaterialTheme.colors.onSurface.copy(alpha = 0.4f)
                            } else {
                                MaterialTheme.colors.primary
                            },
                        ),
                    )
                }

                Divider(modifier = Modifier.padding(top = 4.dp))

                InterfaceRow(
                    title = "区划接口",
                    subtitle = "民政部 · 行政区划",
                    result = results[KEY_AREA],
                    isRunning = running[KEY_AREA] == true,
                    onTest = { startTest(KEY_AREA) },
                )
                InterfaceRow(
                    title = "地名接口",
                    subtitle = "民政部 · 地名搜索",
                    result = results[KEY_STNAME],
                    isRunning = running[KEY_STNAME] == true,
                    onTest = { startTest(KEY_STNAME) },
                )
                InterfaceRow(
                    title = "边界接口",
                    subtitle = "阿里 DataV · 行政边界",
                    result = results[KEY_BOUND],
                    isRunning = running[KEY_BOUND] == true,
                    onTest = { startTest(KEY_BOUND) },
                )
                InterfaceRow(
                    title = "离线地区库",
                    subtitle = "本地 pcas-code.json 查询",
                    result = results[KEY_OFFLINE],
                    isRunning = running[KEY_OFFLINE] == true,
                    onTest = { startTest(KEY_OFFLINE) },
                )

                Divider(modifier = Modifier.padding(top = 4.dp))

                Text(
                    text = "地区库文件",
                    modifier = Modifier.padding(top = 14.dp),
                    style = MaterialTheme.typography.body1.copy(fontWeight = FontWeight.Bold),
                )
                RegionLibraryCard(
                    status = status,
                    updating = updating,
                    message = updateMessage,
                    onUpdate = { updateLibrary() },
                )
            }
        }
    }
}

@Composable
private fun InterfaceRow(
    title: String,
    subtitle: String,
    result: VideoInfoApiResult?,
    isRunning: Boolean,
    onTest: () -> Unit,
) {
    val muted = MaterialTheme.colors.onSurface.copy(alpha = 0.55f)
    val stateColor = when {
        isRunning -> muted
        result == null -> muted
        result.ok -> SUCCESS_COLOR
        else -> MaterialTheme.colors.error
    }
    val stateText = when {
        isRunning -> "测试中…"
        result == null -> "未测试"
        result.ok -> "正常 · ${result.elapsedMs}ms"
        else -> "失败 · ${result.elapsedMs}ms"
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.body1,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.body2.copy(
                    fontSize = 11.sp,
                    color = muted.copy(alpha = 0.4f),
                ),
            )
            Text(
                text = stateText,
                modifier = Modifier.padding(top = 2.dp),
                style = MaterialTheme.typography.body2.copy(
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = stateColor,
                ),
            )
            if (result != null) {
                Text(
                    text = result.detail,
                    modifier = Modifier.padding(top = 2.dp),
                    style = MaterialTheme.typography.body2.copy(
                        fontSize = 12.sp,
                        color = if (result.ok) MaterialTheme.colors.onSurface.copy(alpha = 0.75f)
                        else MaterialTheme.colors.error,
                    ),
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = result.url,
                    modifier = Modifier.padding(top = 2.dp),
                    style = MaterialTheme.typography.body2.copy(
                        fontSize = 10.sp,
                        color = muted.copy(alpha = 0.4f),
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Text(
            text = if (isRunning) "稍候" else "测试",
            modifier = Modifier
                .clickable(enabled = !isRunning) { onTest() }
                .padding(start = 12.dp, top = 10.dp, bottom = 10.dp),
            style = MaterialTheme.typography.body2.copy(
                fontWeight = FontWeight.Bold,
                color = if (isRunning) muted.copy(alpha = 0.4f) else MaterialTheme.colors.primary,
            ),
        )
    }
}

@Composable
private fun RegionLibraryCard(
    status: RegionLibrary.Status?,
    updating: Boolean,
    message: String?,
    onUpdate: () -> Unit,
) {
    val muted = MaterialTheme.colors.onSurface.copy(alpha = 0.55f)

    FCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        border = FCardBorder(borderWidth = 1.0.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            if (status == null) {
                Text(
                    text = "读取中…",
                    style = MaterialTheme.typography.body2.copy(fontSize = 12.sp, color = muted),
                )
            } else {
                Text(
                    text = if (status.exists) {
                        "${formatSize(status.bytes)} · ${status.provinces} 个省级节点 / ${status.totalNodes} 个节点"
                    } else {
                        "尚未下载地区库文件"
                    },
                    style = MaterialTheme.typography.body2.copy(
                        fontSize = 12.sp,
                        color = if (status.exists) MaterialTheme.colors.onSurface
                        else MaterialTheme.colors.error,
                    ),
                )
                Text(
                    text = if (status.exists) {
                        "更新时间 ${formatTime(status.lastModified)}" +
                            if (status.fresh) "（30 天内有效）" else "（已过期，将自动更新）"
                    } else {
                        "获取视频信息时会自动下载，也可以点下方按钮手动更新"
                    },
                    modifier = Modifier.padding(top = 2.dp),
                    style = MaterialTheme.typography.body2.copy(fontSize = 12.sp, color = muted),
                )
                Text(
                    text = "Download/Freedom/${RegionLibrary.RELATIVE_PATH}",
                    modifier = Modifier.padding(top = 4.dp),
                    style = MaterialTheme.typography.body2.copy(
                        fontSize = 10.sp,
                        color = muted.copy(alpha = 0.4f),
                    ),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = message.orEmpty(),
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 8.dp),
                    style = MaterialTheme.typography.body2.copy(
                        fontSize = 11.sp,
                        color = if (message?.startsWith("更新成功") == true) SUCCESS_COLOR
                        else MaterialTheme.colors.error,
                    ),
                )
                Text(
                    text = if (updating) "更新中…" else "更新地区库文件",
                    modifier = Modifier
                        .clickable(enabled = !updating) { onUpdate() }
                        .padding(vertical = 6.dp),
                    style = MaterialTheme.typography.body2.copy(
                        fontWeight = FontWeight.Bold,
                        color = if (updating) muted.copy(alpha = 0.4f) else MaterialTheme.colors.primary,
                    ),
                )
            }
        }
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> String.format(Locale.ROOT, "%.2f MB", bytes / 1024.0 / 1024.0)
    bytes >= 1024 -> String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0)
    else -> "$bytes B"
}

private fun formatTime(timestamp: Long): String {
    if (timestamp <= 0) return "未知"
    return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(timestamp))
}
