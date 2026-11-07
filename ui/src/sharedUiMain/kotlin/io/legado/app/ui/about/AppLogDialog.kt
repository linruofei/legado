package io.legado.app.ui.about

// I18N KEYS (已注册于 ResourceProvider.jvm.kt):
//   "log" to "日志",
//   "clear" to "清空"

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.legado.app.constant.AppLog
import io.legado.app.constant.AppLogEntry
import io.legado.app.ui.compose.component.AppDialogScaffold
import io.legado.app.ui.compose.component.AppDialogSizes
import io.legado.app.ui.compose.component.AppTextButton
import io.legado.app.ui.compose.linkifyText
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import io.legado.app.ui.widget.dialog.TextDialog
import legado.ui.generated.resources.Res
import legado.ui.generated.resources.clear
import legado.ui.generated.resources.log
import org.jetbrains.compose.resources.stringResource

/**
 * 应用日志对话框内容 (KMP 共享, app + desktop 复用)。
 *
 * 阅读页 / 书源登录 / 书架管理等页面把当前 [AppLog] 快照弹成对话框 (key="app_log" Overlay)。
 * 完整的日志浏览/过滤/导出在「关于 → 调试日志」页 ([DebugLogScreen]), 这里只保留就地看日志的最小形态:
 * - 标题栏: 返回 + "日志" + 清空按钮
 * - 列表: LazyColumn，行=时间+消息，带异常的行可点击查看堆栈
 * - 实时刷新: 订阅 [AppLog.logsFlow]，日志变化时立即重组
 * - URL 自动链接: 消息中的 URL 可点击跳转 (对齐 origin autoLinkMask)
 *
 * @param onDismiss 用户取消 (返回按钮)
 */
@Composable
fun AppLogDialogContent(
    onDismiss: () -> Unit,
) {
    val logs by AppLog.logsFlow.collectAsState()

    // 选中的堆栈日志 (非 null 时弹出堆栈对话框)
    var stackTraceItem by remember { mutableStateOf<AppLogEntry?>(null) }

    AppDialogScaffold(
        onDismissRequest = onDismiss,
        title = stringResource(Res.string.log),
        titleActions = {
            AppTextButton(text = stringResource(Res.string.clear), onClick = AppLog::clear)
        },
        contentPadding = PaddingValues(0.dp),
        properties = AppDialogSizes.properties(),
    ) {
        SelectionContainer(
            Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            LazyColumn(Modifier.fillMaxSize()) {
                itemsIndexed(logs) { _, item ->
                    LogItem(item) { stackTraceItem = item }
                }
            }
        }
    }

    // 堆栈信息弹窗 (点击带异常的日志行触发)
    stackTraceItem?.let { item ->
        TextDialog(
            title = stringResource(Res.string.log),
            content = item.throwable?.stackTraceToString() ?: "",
            onDismiss = { stackTraceItem = null },
        )
    }
}

/**
 * 单条日志行: 时间 + 消息 (URL 可点击), 带 throwable 时可点击查看堆栈。
 */
@Composable
private fun LogItem(
    item: AppLogEntry,
    onClick: () -> Unit,
) {
    val colors = AppTheme.colors
    // 读取平台时区偏移, 将 UTC epoch 转为本地时间
    val tzOffset = remember { AppLog.timeZoneOffsetMillis() }
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = item.throwable != null, onClick = onClick)
            .padding(DesignTokens.spacingDefault),
    ) {
        Text(
            text = formatLogTime(item.time, tzOffset),
            color = colors.primaryText,
        )
        Text(
            text = remember(item.message, colors.accent) { linkifyText(item.message, colors.accent) },
            color = colors.primaryText,
        )
    }
}

/**
 * 应用日志对话框 (带 Dialog 窗口, 供移动端 / 桌面端直接使用)。
 *
 * 四端唯一入口 (app 端原 AppLogDialog Fragment 已删, 统一走 app_log Overlay / 直接组合)。
 *
 * @param onDismiss 用户取消 (返回按钮 / 点击对话框外部)
 */
@Composable
fun AppLogDialog(
    onDismiss: () -> Unit,
) {
    AppLogDialogContent(onDismiss = onDismiss)
}
