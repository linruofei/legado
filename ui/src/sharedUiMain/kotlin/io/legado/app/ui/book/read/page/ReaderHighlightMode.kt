package io.legado.app.ui.book.read.page

import io.legado.app.constant.PreferKey
import io.legado.app.help.config.PreferenceProviders
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 朗读高亮的绘制方式（用户需求 2026-11-07）。
 *
 * - [TEXT]：只把命中行文字换成主题强调色，不画矩形（历史行为，默认）
 * - [BACKGROUND]：命中行垫一层半透明强调色底，文字保持正文色
 *
 * 设置页从阅读页菜单打开时阅读页仍然存活，而 [ReaderDrawStyle] 是 remember 出来的、
 * 偏好变化不会自己重建。所以这里把方式缓存成 [StateFlow]，由
 * [rememberReaderDrawStyle] 收集后作为 remember 键：改完返回阅读页立刻重绘，
 * 不需要翻页。
 */
object ReaderHighlightMode {

    const val TEXT = "text"
    const val BACKGROUND = "background"

    /** 朗读命中行底色透明度：0.18 接近纸质荧光笔，不压文字色。 */
    const val BACKGROUND_ALPHA = 0.18f

    private val stored: MutableStateFlow<String> by lazy { MutableStateFlow(readStoredMode()) }

    val mode: StateFlow<String> get() = stored.asStateFlow()

    val current: String get() = stored.value

    val isBackground: Boolean get() = stored.value == BACKGROUND

    fun update(value: String) {
        val normalized = if (value == BACKGROUND) BACKGROUND else TEXT
        stored.value = normalized
        PreferenceProviders.get().putString(PreferKey.readAloudHighlightMode, normalized)
    }

    private fun readStoredMode(): String =
        if (PreferenceProviders.get().getStringOrNull(PreferKey.readAloudHighlightMode) == BACKGROUND) {
            BACKGROUND
        } else {
            TEXT
        }
}
