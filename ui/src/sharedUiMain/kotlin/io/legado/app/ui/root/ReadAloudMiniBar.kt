package io.legado.app.ui.root

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.Book
import io.legado.app.help.tts.ReadAloudMiniBarProviders
import io.legado.app.help.tts.ReadAloudMiniBarState
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import kotlinx.coroutines.launch
import legado.ui.generated.resources.Res
import legado.ui.generated.resources.ic_pause_24dp
import legado.ui.generated.resources.ic_play_24dp
import legado.ui.generated.resources.ic_skip_next
import legado.ui.generated.resources.ic_skip_previous
import legado.ui.generated.resources.ic_stop_black_24dp
import org.jetbrains.compose.resources.painterResource

/**
 * 朗读迷你播放条（用户需求 2026-11-07）。
 *
 * - 形态 A：单行悬浮条，贴底居中，左右 12dp、底部 12dp + 导航栏避让，高 52dp
 * - 左侧「书名（次要色） · 章节名（主色）」，单行省略；右侧 ⏮ ⏸/▶ ⏭ ✕
 * - ✕ = 停止朗读（不是"关闭条"）：朗读停了条就该消失，所以没有单独的关闭态
 * - 只在"有正在进行的朗读"且"不在阅读页"时显示：阅读页自己有完整控制栏，重复显示没意义
 * - 点条身回阅读页
 *
 * 状态来自 [ReadAloudMiniBarProviders.state]（由朗读服务折叠 `ReadAloudControllerShared.state`），
 * 动作走 [io.legado.app.help.tts.ReadAloudMiniBarPort]；没注册端口时整条不显示。
 */
@Composable
fun ReadAloudMiniBar(
    navigator: AppNavigator,
    modifier: Modifier = Modifier,
) {
    val state by ReadAloudMiniBarProviders.state.collectAsState()
    // StateFlow 的 .value 不会被 Compose 观察到, 必须 collectAsState 才会随导航重组
    val backStack by navigator.backStack.collectAsState()
    val route = backStack.lastOrNull()?.route
    // 阅读页自己有一整套朗读控制；其余路由才需要这条悬浮条
    val visible = state != null && route !is AppRoute.Reader
    val scope = rememberCoroutineScope()
    // 退出动画期间 state 已被置空, 记住最后一帧内容, 避免文字闪成空串
    var lastShown by remember { mutableStateOf<ReadAloudMiniBarState?>(null) }
    SideEffect { state?.let { lastShown = it } }

    AnimatedVisibility(
        visible = visible,
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 12.dp),
        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
    ) {
        val shown = state ?: lastShown
        if (shown != null) {
            MiniBarContent(
                state = shown,
                onOpenBook = {
                    scope.launch {
                        // 书可能已被删除: 拿不到就地忽略, 不弹错误
                        val book: Book? = AppDbProviders.get().bookDao.getBook(shown.bookUrl)
                        book?.let { navigator.push(it.toReadRoute()) }
                    }
                },
            )
        }
    }
}

@Composable
private fun MiniBarContent(
    state: ReadAloudMiniBarState,
    onOpenBook: () -> Unit,
) {
    val colors = AppTheme.colors
    val port = ReadAloudMiniBarProviders.getOrNull()
    Row(
        Modifier
            .fillMaxWidth()
            .height(52.dp)
            .shadow(4.dp, RoundedCornerShape(12.dp))
            .clip(RoundedCornerShape(12.dp))
            .background(colors.bottomBackground)
            .clickable(onClick = onOpenBook)
            .padding(start = DesignTokens.spacingDefault, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Column(
            Modifier.weight(1f),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = state.bookName,
                color = colors.secondaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = state.chapterTitle,
                color = colors.primaryText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        MiniBarButton(Res.drawable.ic_skip_previous, onClick = { port?.prevChapter() })
        MiniBarButton(
            if (state.isPlaying) Res.drawable.ic_pause_24dp else Res.drawable.ic_play_24dp,
            onClick = { if (state.isPlaying) port?.pause() else port?.resume() },
        )
        MiniBarButton(Res.drawable.ic_skip_next, onClick = { port?.nextChapter() })
        MiniBarButton(Res.drawable.ic_stop_black_24dp, onClick = { port?.stop() })
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.MiniBarButton(
    iconRes: org.jetbrains.compose.resources.DrawableResource,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(40.dp),
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = AppTheme.colors.primaryText,
            modifier = Modifier.size(22.dp),
        )
    }
}
