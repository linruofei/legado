package io.legado.app.ui.book.read.config

import androidx.compose.runtime.Composable
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.AppConfigConstants
import io.legado.app.ui.book.read.page.ReaderHighlightMode
import io.legado.app.ui.compose.preference.PreferenceScreen
import io.legado.app.ui.compose.preference.listPreference
import io.legado.app.ui.compose.preference.preference
import io.legado.app.ui.compose.preference.switchPreference
import io.legado.app.ui.compose.theme.AppTheme
import legado.ui.generated.resources.Res
import legado.ui.generated.resources.pause_read_aloud_while_phone_calls_summary
import legado.ui.generated.resources.pause_read_aloud_while_phone_calls_title
import legado.ui.generated.resources.pref_media_button_per_next
import legado.ui.generated.resources.pref_media_button_per_next_summary
import legado.ui.generated.resources.read_aloud_by_page
import legado.ui.generated.resources.read_aloud_by_page_summary
import legado.ui.generated.resources.read_aloud_wake_lock
import legado.ui.generated.resources.read_aloud_pause_on_back
import legado.ui.generated.resources.read_aloud_pause_on_back_summary
import legado.ui.generated.resources.read_aloud_wake_lock_summary
import legado.ui.generated.resources.speak_engine
import legado.ui.generated.resources.stream_read_aloud_audio
import legado.ui.generated.resources.stream_read_aloud_audio_summary
import legado.ui.generated.resources.sys_tts_config
import legado.ui.generated.resources.sys_tts_config_summary
import legado.ui.generated.resources.tts_highlight_mode
import legado.ui.generated.resources.tts_highlight_mode_background
import legado.ui.generated.resources.tts_highlight_mode_summary
import legado.ui.generated.resources.tts_highlight_mode_text
import legado.ui.generated.resources.tts_prefetch_count
import legado.ui.generated.resources.tts_prefetch_count_summary
import legado.ui.generated.resources.tts_prefetch_off
import legado.ui.generated.resources.tts_prefetch_segments
import org.jetbrains.compose.resources.stringResource

/**
 * 朗读设置（迁 pref_config_aloud.xml）。逐条对齐原条目顺序/key/默认值，
 * 但"忽略音频焦点"只留其它设置页那一处（原版两页各挂一份同 key 开关）。
 * pauseReadAloudWhilePhoneCalls 仍联动 ignoreAudioFocus 的值（enabled），
 * 朗读引擎 summary 动态（SpeakEngineDialog 回调刷新），事件广播由宿主承接。
 *
 * 下沉 shared/sharedUiMain 后:
 * - `stringResource(R.string.xxx)` → `stringResource(Res.string.xxx)` (key-based, 跨平台)
 * - PreferenceScreen/preference/preferenceCategory/switchPreference 走 shared/sharedUiMain 的
 *   io.legado.app.ui.compose.preference 包, 内部通过 PreferenceProviders 单例读写 prefs
 * - 注意: 原 "mediaButtonPerNext" 为硬编码 key (不在 PreferKey 中), 保留原字面量字符串
 * - "朗读设置"分类标题已提升为对话框标题栏 (2026-10-11 用户拍板), 本 Screen 只留条目
 */
@Composable
fun ReadAloudConfigScreen(
    pausePhoneCallsEnabled: Boolean,
    speakEngineSummary: String,
    onTtsEngine: () -> Unit,
    onSysTtsConfig: () -> Unit,
) {
    val titlePausePhoneCalls = stringResource(Res.string.pause_read_aloud_while_phone_calls_title)
    val summaryPausePhoneCalls =
        stringResource(Res.string.pause_read_aloud_while_phone_calls_summary)
    val titleWakeLock = stringResource(Res.string.read_aloud_wake_lock)
    val summaryWakeLock = stringResource(Res.string.read_aloud_wake_lock_summary)
    val titleMediaButtonPerNext = stringResource(Res.string.pref_media_button_per_next)
    val summaryMediaButtonPerNext = stringResource(Res.string.pref_media_button_per_next_summary)
    val titleReadAloudByPage = stringResource(Res.string.read_aloud_by_page)
    val summaryReadAloudByPage = stringResource(Res.string.read_aloud_by_page_summary)
    val titleStreamReadAloud = stringResource(Res.string.stream_read_aloud_audio)
    val summaryStreamReadAloud = stringResource(Res.string.stream_read_aloud_audio_summary)
    val titlePrefetchCount = stringResource(Res.string.tts_prefetch_count)
    val summaryPrefetchCount = stringResource(Res.string.tts_prefetch_count_summary)
    // 档位与 PreferKey.httpTtsPrefetchCount 的存储值一一对应 (存字符串, 读取端解析后钳制)
    val prefetchEntries = listOf(
        stringResource(Res.string.tts_prefetch_off),
        stringResource(Res.string.tts_prefetch_segments, 1),
        stringResource(Res.string.tts_prefetch_segments, 2),
        stringResource(Res.string.tts_prefetch_segments, 3),
        stringResource(Res.string.tts_prefetch_segments, 5),
        stringResource(Res.string.tts_prefetch_segments, 8),
        stringResource(Res.string.tts_prefetch_segments, 10),
    )
    val prefetchValues = listOf("0", "1", "2", "3", "5", "8", "10")
    val titlePauseOnBack = stringResource(Res.string.read_aloud_pause_on_back)
    val summaryPauseOnBack = stringResource(Res.string.read_aloud_pause_on_back_summary)
    val titleHighlightMode = stringResource(Res.string.tts_highlight_mode)
    val summaryHighlightMode = stringResource(Res.string.tts_highlight_mode_summary)
    // 档位与 PreferKey.readAloudHighlightMode 的存储值一一对应 (存字符串)
    val highlightModeEntries = listOf(
        stringResource(Res.string.tts_highlight_mode_text),
        stringResource(Res.string.tts_highlight_mode_background),
    )
    val highlightModeValues = listOf(ReaderHighlightMode.TEXT, ReaderHighlightMode.BACKGROUND)
    val titleSpeakEngine = stringResource(Res.string.speak_engine)
    val titleSysTtsConfig = stringResource(Res.string.sys_tts_config)
    val summarySysTtsConfig = stringResource(Res.string.sys_tts_config_summary)

    AppTheme {
        PreferenceScreen {
            // "忽略音频焦点"不在此重复: 原版 pref_config_aloud.xml 与 pref_config_other.xml
            // 各挂一份同 key 开关, 2026-09-04 用户拍板只留其它设置那一处。
            // pauseReadAloudWhilePhoneCalls 仍按它的值联动 enabled (值由其它设置页改)
            switchPreference(
                prefKey = PreferKey.pauseReadAloudWhilePhoneCalls,
                title = titlePausePhoneCalls,
                summary = summaryPausePhoneCalls,
                defaultValue = false,
                enabled = pausePhoneCallsEnabled,
            )
            switchPreference(
                prefKey = PreferKey.readAloudWakeLock,
                title = titleWakeLock,
                summary = summaryWakeLock,
                defaultValue = false,
            )
            switchPreference(
                prefKey = "mediaButtonPerNext",
                title = titleMediaButtonPerNext,
                summary = summaryMediaButtonPerNext,
                defaultValue = false,
            )
            switchPreference(
                prefKey = PreferKey.readAloudByPage,
                title = titleReadAloudByPage,
                summary = summaryReadAloudByPage,
                defaultValue = false,
            )
            switchPreference(
                prefKey = PreferKey.streamReadAloudAudio,
                title = titleStreamReadAloud,
                summary = summaryStreamReadAloud,
                defaultValue = false,
            )
            // 非流式在线朗读的预取段数: 0 = 关闭
            listPreference(
                prefKey = PreferKey.httpTtsPrefetchCount,
                title = titlePrefetchCount,
                entries = prefetchEntries,
                values = prefetchValues,
                defaultValue = AppConfigConstants.httpTtsPrefetchCountDefault.toString(),
                summary = summaryPrefetchCount,
            )
            // 返回键是否先暂停朗读: 关闭后返回直接退出阅读页, 朗读在后台继续
            switchPreference(
                prefKey = PreferKey.readAloudPauseOnBack,
                title = titlePauseOnBack,
                summary = summaryPauseOnBack,
                defaultValue = true,
            )
            // 朗读高亮方式: 文字色 (默认) / 背景色; 改完由 ReaderHighlightMode 的状态流驱动画布重绘
            listPreference(
                prefKey = PreferKey.readAloudHighlightMode,
                title = titleHighlightMode,
                entries = highlightModeEntries,
                values = highlightModeValues,
                defaultValue = ReaderHighlightMode.TEXT,
                summary = summaryHighlightMode,
                onValueChange = { ReaderHighlightMode.update(it) },
            )
            preference(
                title = titleSpeakEngine,
                summary = speakEngineSummary,
                onClick = onTtsEngine,
            )
            preference(
                title = titleSysTtsConfig,
                summary = summarySysTtsConfig,
                onClick = onSysTtsConfig,
            )
        }
    }
}
