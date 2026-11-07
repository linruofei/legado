package io.legado.app.ui.root

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.Book
import io.legado.app.help.config.AppConfigProviders
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
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource

/**
 * 朗读迷你播放条（用户需求 2026-11-07）。
 *
 * - 形态 A：单行悬浮条，贴底居中，高 56dp、圆角 16dp、半透明阴影；
 *   宽窗下最宽 560dp 居中（否则平板上一条横贯全屏很难看）
 * - 左侧强调色圆形播放/暂停，中间「书名（次要色） / 章节名（主色）」两行、单行省略，
 *   右侧 ⏮ ⏭ ✕
 * - ✕ = 停止朗读（不是"关闭条"）：朗读停了条就该消失，所以没有单独的关闭态
 * - 只在"有正在进行的朗读"且"不在阅读页"时显示：阅读页自己有完整控制栏，重复显示没意义
 * - 点条身回阅读页
 *
 * **避让主界面底栏**：主界面（[AppRoute.Main]）在窄窗口下底部有 36~80dp 可配置的 Tab 栏，
 * 悬浮条坐在它上沿而不是盖住它；宽窗口下导航改到左侧 NavRail，底部无遮挡就不加这段偏移。
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

    // 与 MainScreen 同一套宽屏判定: 最短边 ≥ 600dp 走左侧 NavRail, 底部没有 Tab 栏
    val windowInfo = LocalWindowInfo.current
    val density = LocalDensity.current
    val useNavRail by remember(windowInfo, density) {
        derivedStateOf {
            val size = windowInfo.containerSize
            val wDp = with(density) { size.width.toDp() }
            val hDp = with(density) { size.height.toDp() }
            minOf(wDp, hDp) >= DesignTokens.wideScreenMinWidth
        }
    }
    // 底栏高度是用户可配的 (36~80dp), 直接取当前配置而不是写死
    val tabBarHeight = remember { AppConfigProviders.get().bottomBarHeight }
    val tabBarOffset = if (route is AppRoute.Main && !useNavRail) tabBarHeight.dp else 0.dp

    AnimatedVisibility(
        visible = visible,
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(
                start = DesignTokens.spacingMd,
                end = DesignTokens.spacingMd,
                top = DesignTokens.spacingDefault,
                bottom = DesignTokens.spacingDefault + tabBarOffset,
            ),
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
    val shape = RoundedCornerShape(DesignTokens.radiusLg)
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Row(
            Modifier
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .height(56.dp)
                .shadow(6.dp, shape)
                .clip(shape)
                .background(colors.bottomBackground)
                .border(DesignTokens.strokeHairline, colors.fillet, shape)
                .clickable(onClick = onOpenBook)
                .padding(start = DesignTokens.spacingXs, end = DesignTokens.spacingXs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DesignTokens.spacingXs),
        ) {
            // 圆形播放/暂停: 强调色底 + 条面色图标, 任何主题下都与强调色形成对比
            Box(
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(colors.accent)
                    .clickable {
                        if (state.isPlaying) port?.pause() else port?.resume()
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(
                        if (state.isPlaying) Res.drawable.ic_pause_24dp
                        else Res.drawable.ic_play_24dp
                    ),
                    contentDescription = null,
                    tint = colors.bottomBackground,
                    modifier = Modifier.size(22.dp),
                )
            }
            Column(
                Modifier
                    .weight(1f)
                    .padding(start = DesignTokens.spacingXs),
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
            MiniBarButton(Res.drawable.ic_skip_previous) { port?.prevChapter() }
            MiniBarButton(Res.drawable.ic_skip_next) { port?.nextChapter() }
            MiniBarButton(Res.drawable.ic_stop_black_24dp) { port?.stop() }
        }
    }
}

@Composable
private fun MiniBarButton(
    iconRes: DrawableResource,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(40.dp),
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = AppTheme.colors.secondaryText,
            modifier = Modifier.size(20.dp),
        )
    }
}
