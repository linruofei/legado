package io.legado.app.ui.about

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.Switch
import androidx.compose.material.SwitchDefaults
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.legado.app.constant.AppLog
import io.legado.app.constant.AppLogEntry
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.PreferenceProviders
import io.legado.app.help.toast.Toasters
import io.legado.app.ui.compose.component.AppTitleBar
import io.legado.app.ui.compose.component.AppUnderlineTextField
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import io.legado.app.ui.root.AppNavigator
import io.legado.app.ui.root.CrashLogProvider
import io.legado.app.ui.root.PlatformCapabilityProviders
import io.legado.app.ui.root.PlatformServiceProviders
import io.legado.app.ui.widget.dialog.TextDialog
import io.legado.app.utils.format
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import legado.ui.generated.resources.Res
import legado.ui.generated.resources.all
import legado.ui.generated.resources.clear
import legado.ui.generated.resources.copied_to_clipboard
import legado.ui.generated.resources.copy
import legado.ui.generated.resources.crash_log
import legado.ui.generated.resources.debug_log
import legado.ui.generated.resources.log
import legado.ui.generated.resources.memory_log
import legado.ui.generated.resources.record_log
import legado.ui.generated.resources.search
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import legado.ui.generated.resources.ic_clear_all
import legado.ui.generated.resources.ic_copy

/** 复制到剪贴板的最大条数：再长会超过 Binder 事务上限，直接抛 TransactionTooLargeException。 */
private const val MAX_COPY_COUNT = 300

/** 日志流刷新节流间隔：朗读时一秒数条，逐条重组会掉帧。 */
private const val LOG_FLUSH_INTERVAL_MS = 200L

/**
 * 调试日志页（用户需求 2026-11-07）。
 *
 * 两个分页：
 * - 内存日志：[AppLog] 的环形列表（上限 [AppLog.MAX_LOG_COUNT]），可开关记录、按 tag/关键字过滤、
 *   复制（最多 [MAX_COPY_COUNT] 条）与清空
 * - 崩溃日志：平台提供的崩溃文本文件列表，可查看/复制/分享/清空
 *
 * 取代了原「关于页 → 崩溃日志」的对话框：崩溃文件从同一个页面进，内存日志是新增能力。
 */
@Composable
fun DebugLogScreen(navigator: AppNavigator) {
    val colors = AppTheme.colors
    val strDebugLog = stringResource(Res.string.debug_log)
    val strCopy = stringResource(Res.string.copy)
    val strClear = stringResource(Res.string.clear)
    val strCopied = stringResource(Res.string.copied_to_clipboard)

    var tab by remember { mutableIntStateOf(0) }
    var keyword by remember { mutableStateOf("") }
    var tagFilter by remember { mutableStateOf<String?>(null) }

    // 节流刷新：每条日志到达后至少等 LOG_FLUSH_INTERVAL_MS，期间的多次变更被 conflate 掉
    val logs by produceState(initialValue = AppLog.logs) {
        AppLog.logsFlow.collect { latest ->
            value = latest
            delay(LOG_FLUSH_INTERVAL_MS)
        }
    }
    val tags = remember(logs) { logs.map { it.tag }.distinct() }
    val filtered = remember(logs, keyword, tagFilter) {
        logs.filter { entry ->
            (tagFilter == null || entry.tag == tagFilter) &&
                (keyword.isBlank() || entry.message.contains(keyword, ignoreCase = true))
        }
    }
    val crashProvider = PlatformServiceProviders.get().crashLogs
    var crashLogs by remember { mutableStateOf<List<CrashLogProvider.CrashLogEntry>>(emptyList()) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(tab) {
        if (tab == 1) crashLogs = crashProvider.loadCrashLogs()
    }

    Column(Modifier.fillMaxSize().background(colors.background)) {
        AppTitleBar(
            title = strDebugLog,
            onBack = { navigator.pop() },
            actions = {
                if (tab == 0) {
                    IconButton(onClick = {
                        // 只复制当前过滤结果的前 MAX_COPY_COUNT 条，超长的剪贴板事务 Android 会抛异常
                        val picked = filtered.take(MAX_COPY_COUNT)
                        if (picked.isEmpty()) {
                            Toasters.get().toast(strCopied)
                        } else {
                            val tz = AppLog.timeZoneOffsetMillis()
                            PlatformCapabilityProviders.get().copyToClipboard(
                                picked.joinToString("\n") { formatEntry(it, tz) }
                            )
                            Toasters.get().toast(strCopied)
                        }
                    }) {
                        Icon(
                            painter = painterResource(Res.drawable.ic_copy),
                            contentDescription = strCopy,
                            tint = colors.primaryText,
                        )
                    }
                }
                IconButton(onClick = {
                    if (tab == 0) {
                        AppLog.clear()
                    } else {
                        scope.launch {
                            crashProvider.clearCrashLogs()
                            crashLogs = crashProvider.loadCrashLogs()
                        }
                    }
                }) {
                    Icon(
                        painter = painterResource(Res.drawable.ic_clear_all),
                        contentDescription = strClear,
                        tint = colors.primaryText,
                    )
                }
            },
        )
        // 分页选择：内存日志 / 崩溃日志
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = DesignTokens.spacingDefault),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TabChip(stringResource(Res.string.memory_log), tab == 0) { tab = 0 }
            TabChip(stringResource(Res.string.crash_log), tab == 1) { tab = 1 }
        }
        if (tab == 0) {
            MemoryLogBody(
                logs = filtered,
                tags = tags,
                keyword = keyword,
                tagFilter = tagFilter,
                onKeywordChange = { keyword = it },
                onTagFilterChange = { tagFilter = it },
                onCopy = { entry ->
                    PlatformCapabilityProviders.get()
                        .copyToClipboard(formatEntry(entry, AppLog.timeZoneOffsetMillis()))
                    Toasters.get().toast(strCopied)
                },
            )
        } else {
            CrashLogBody(crashLogs)
        }
    }
}

@Composable
private fun TabChip(text: String, selected: Boolean, onClick: () -> Unit) {
    val colors = AppTheme.colors
    Text(
        text = text,
        color = if (selected) colors.accent else colors.secondaryText,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

@Composable
private fun MemoryLogBody(
    logs: List<AppLogEntry>,
    tags: List<String>,
    keyword: String,
    tagFilter: String?,
    onKeywordChange: (String) -> Unit,
    onTagFilterChange: (String?) -> Unit,
    onCopy: (AppLogEntry) -> Unit,
) {
    val colors = AppTheme.colors
    val pref = PreferenceProviders.get()
    val strRecordLog = stringResource(Res.string.record_log)
    val strSearch = stringResource(Res.string.search)
    val strAll = stringResource(Res.string.all)
    var recordLog by remember { mutableStateOf(pref.getBoolean(PreferKey.recordLog, false)) }
    var stackTrace by remember { mutableStateOf<AppLogEntry?>(null) }
    val tzOffset = remember { AppLog.timeZoneOffsetMillis() }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = DesignTokens.spacingDefault, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = strRecordLog, color = colors.primaryText)
            Spacer(Modifier.weight(1f))
            Switch(
                checked = recordLog,
                onCheckedChange = {
                    recordLog = it
                    pref.putBoolean(PreferKey.recordLog, it)
                },
                colors = SwitchDefaults.colors(checkedThumbColor = colors.accent),
            )
        }
        AppUnderlineTextField(
            value = keyword,
            onValueChange = onKeywordChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = DesignTokens.spacingDefault),
            placeholder = strSearch,
            singleLine = true,
        )
        // tag 过滤：全部 + 当前日志里出现过的组件标签
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = DesignTokens.spacingDefault, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TabChip(strAll, tagFilter == null) { onTagFilterChange(null) }
            tags.forEach { tag ->
                TabChip(tag, tagFilter == tag) { onTagFilterChange(tag) }
            }
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(logs) { entry ->
                LogRow(
                    entry = entry,
                    tzOffset = tzOffset,
                    onClick = { onCopy(entry) },
                    onShowStack = if (entry.throwable != null) {
                        { stackTrace = entry }
                    } else {
                        null
                    },
                )
            }
        }
    }

    stackTrace?.let { entry ->
        TextDialog(
            title = stringResource(Res.string.log),
            content = entry.throwable?.stackTraceToString() ?: "",
            onDismiss = { stackTrace = null },
        )
    }
}

@Composable
private fun LogRow(
    entry: AppLogEntry,
    tzOffset: Long,
    onClick: () -> Unit,
    onShowStack: (() -> Unit)?,
) {
    val colors = AppTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = DesignTokens.spacingDefault, vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = formatLogTime(entry.time, tzOffset),
                color = colors.secondaryText,
            )
            if (entry.tag.isNotEmpty()) {
                Spacer(Modifier.padding(horizontal = 4.dp))
                Text(
                    text = entry.tag,
                    color = colors.accent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .clip(RoundedCornerShape(4.dp))
                        .background(colors.fillet)
                        .padding(horizontal = 4.dp),
                )
            }
        }
        Text(
            text = entry.message,
            color = colors.primaryText,
        )
        if (onShowStack != null) {
            Text(
                text = "…",
                color = colors.accent,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 24.dp)
                    .clickable(onClick = onShowStack)
                    .padding(vertical = 2.dp),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CrashLogBody(logs: List<CrashLogProvider.CrashLogEntry>) {
    val colors = AppTheme.colors
    val provider = PlatformServiceProviders.get().crashLogs
    val scope = rememberCoroutineScope()
    var content by remember { mutableStateOf<Pair<String, String>?>(null) }

    LazyColumn(Modifier.fillMaxSize()) {
        items(logs, key = { it.name }) { entry ->
            Text(
                text = entry.name,
                color = colors.primaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        onClick = {
                            scope.launch {
                                val text = provider.readCrashLog(entry.name)
                                if (text != null) content = entry.name to text
                            }
                        },
                        onLongClick = { provider.shareCrashLog(entry.name) },
                    )
                    .padding(DesignTokens.spacingDefault),
            )
        }
    }

    content?.let { (name, text) ->
        TextDialog(
            title = name,
            content = text,
            onDismiss = { content = null },
        )
    }
}

/** 单条日志的纯文本形式：`时间 [tag] 消息`。 */
internal fun formatEntry(entry: AppLogEntry, tzOffset: Long): String {
    val head = formatLogTime(entry.time, tzOffset)
    val tag = if (entry.tag.isEmpty()) "" else "[${entry.tag}] "
    return "$head $tag${entry.message}"
}

/**
 * 将 epoch 毫秒格式化为 "yy-MM-dd HH:mm:ss.SSS" (本地时间)。
 *
 * 使用 Howard Hinnant 的 civil_from_days 算法 + [tzOffsetMs] 时区偏移，
 * 纯 Kotlin 实现，不依赖 java.util.Date / SimpleDateFormat (KMP 安全)。
 */
internal fun formatLogTime(epochMillis: Long, tzOffsetMs: Long): String {
    val localMillis = epochMillis + tzOffsetMs
    val totalSeconds = localMillis / 1000
    val millis = (localMillis % 1000).toInt().let { if (it < 0) it + 1000 else it }
    val secs = (totalSeconds % 60).toInt().let { if (it < 0) it + 60 else it }
    val totalMinutes = totalSeconds / 60
    val mins = (totalMinutes % 60).toInt().let { if (it < 0) it + 60 else it }
    val totalHours = totalMinutes / 60
    val hrs = (totalHours % 24).toInt().let { if (it < 0) it + 24 else it }
    val totalDays = totalHours / 24

    val z = totalDays.toInt() + 719468
    val era = if (z >= 0) z / 146097 else (z - 146096) / 146097
    val doe = z - era * 146097
    val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
    val y = yoe + era * 400
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val d = doy - (153 * mp + 2) / 5 + 1
    val m = if (mp < 10) mp + 3 else mp - 9
    val year = if (m <= 2) y + 1 else y
    val yy = year % 100

    return "%02d-%02d %02d:%02d:%02d.%03d".format(yy, m, d, hrs, mins, secs, millis)
}
