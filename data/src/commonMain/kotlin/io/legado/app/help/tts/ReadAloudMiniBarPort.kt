package io.legado.app.help.tts

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 朗读迷你播放条的状态快照。
 *
 * 只带 UI 需要的最小信息：条身要显示"书名 + 章节名"，点条身要能回到阅读页（[bookUrl]），
 * 按钮要能区分播放/暂停。
 *
 * 为什么不用 `ReadBookPlatform.isReadAloudRun/isReadAloudPause`：那两个是同步布尔快照，
 * 且 `BaseReadAloudService.pause` 是普通 `var`，Compose 无从订阅。朗读服务把播放状态
 * （由 `ReadAloudControllerShared.state` 驱动）折叠成这里的单一 StateFlow，UI 只订阅它。
 *
 * @param bookUrl 当前朗读书籍的 url，用于点条身回阅读页
 * @param bookName 书名（条目左侧，次要色）
 * @param chapterTitle 当前章节名（条目左侧，主色）
 * @param isPlaying 是否正在播放；false = 暂停（按钮显示播放图标）
 */
data class ReadAloudMiniBarState(
    val bookUrl: String,
    val bookName: String,
    val chapterTitle: String,
    val isPlaying: Boolean,
)

/**
 * 朗读迷你播放条的控制端口。
 *
 * 全应用只有一个实现（Android 端由朗读前台服务提供），因为播放状态天然是单例：
 * 同一时刻只有一个朗读服务在跑。
 *
 * 没有默认实现，四端都挂 [ReadAloudMiniBarProviders] 时按需注册；未注册（桌面/iOS 暂不接）
 * 时 UI 侧 [ReadAloudMiniBarProviders.getOrNull] 返回 null，迷你条不显示。
 */
interface ReadAloudMiniBarPort {

    /** null = 没有正在进行的朗读，迷你条隐藏。 */
    val state: StateFlow<ReadAloudMiniBarState?>

    fun pause()

    fun resume()

    /** 停止朗读（✕ 按钮）：结束服务、通知栏一起收。 */
    fun stop()

    fun prevChapter()

    fun nextChapter()
}

/**
 * 迷你播放条端口的注册表（启动早期注册一次，服务销毁时注销）。
 *
 * 用可空单例而不是"必须有实现"：桌面/iOS 端没接朗读服务时不该崩，UI 拿不到端口就不显示。
 */
object ReadAloudMiniBarProviders {

    @Volatile
    private var port: ReadAloudMiniBarPort? = null

    private val _state = MutableStateFlow<ReadAloudMiniBarState?>(null)

    /** 供 UI 直接订阅的状态：没有端口时恒为 null。 */
    val state: StateFlow<ReadAloudMiniBarState?> = _state.asStateFlow()

    fun register(impl: ReadAloudMiniBarPort) {
        port = impl
    }

    fun unregister(impl: ReadAloudMiniBarPort) {
        if (port === impl) port = null
    }

    fun getOrNull(): ReadAloudMiniBarPort? = port

    /** 由实现方在状态变化时调用，转发给 UI。 */
    fun publish(state: ReadAloudMiniBarState?) {
        _state.value = state
    }
}
