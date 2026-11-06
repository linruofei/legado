package io.legado.app.service

import android.annotation.SuppressLint
import android.app.PendingIntent
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.offline.DefaultDownloaderFactory
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.Downloader
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import io.legado.app.App
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.constant.AppPattern
import io.legado.app.constant.PreferKey
import io.legado.app.data.entities.HttpTTS
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.AppConfigConstants
import io.legado.app.help.config.AppConfigRanges
import io.legado.app.help.config.PreferenceProviders
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.exoplayer.InputStreamDataSource
import io.legado.app.help.exoplayer.TtsAudioDataSourceFactory
import io.legado.app.help.http.okHttpClient
import io.legado.app.help.tts.HttpTtsAnalyzeUrlFactory
import io.legado.app.help.tts.HttpTtsCacheDirProvider
import io.legado.app.help.tts.HttpTtsDownloadScheduler
import io.legado.app.help.tts.HttpTtsFileInfo
import io.legado.app.help.tts.ReadAloudQueue
import io.legado.app.help.tts.TtsAudioMemoryCache
import io.legado.app.model.ReadAloud
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.ui.book.read.page.entities.getNeedReadAloud
import io.legado.app.ui.book.read.page.entities.title
import io.legado.app.utils.FileUtils
import io.legado.app.utils.servicePendingIntent
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Collections
import kotlin.time.Duration.Companion.seconds

/**
 * 在线朗读
 */
@SuppressLint("UnsafeOptInUsageError")
class HttpReadAloudService : BaseReadAloudService(),
    Player.Listener {
    private val exoPlayer: ExoPlayer by lazy {
        ExoPlayer.Builder(this).setLoadControl(
            DefaultLoadControl.Builder().setBufferDurationsMs(
                1800_000_000,
                1800_000_000,
                DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS,
                DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS
            ).build()
        ).setMediaSourceFactory(
            // 非流式路径的音频来自内存 (TtsAudioDataSourceFactory.SCHEME), 其余协议原样放行
            DefaultMediaSourceFactory(TtsAudioDataSourceFactory(this) { memoryCache.get(it) })
        ).build()
    }
    private val ttsFolderPath: String by lazy {
        cacheDir.absolutePath + File.separator + "httpTTS" + File.separator
    }
    private val cache by lazy {
        SimpleCache(
            File(cacheDir, "httpTTS_cache"),
            LeastRecentlyUsedCacheEvictor(128 * 1024 * 1024),
            StandaloneDatabaseProvider(App.instance)
        )
    }
    private val cacheDataSinkFactory by lazy {
        CacheDataSink.Factory()
            .setCache(cache)
    }
    private val loadErrorHandlingPolicy by lazy {
        CustomLoadErrorHandlingPolicy()
    }

    // 下载调度核心（commonMain 下沉），注入缓存目录 Provider + AnalyzeUrl 工厂
    private val ttsCacheDirProvider by lazy { HttpTtsCacheDirProviderImpl() }
    private val analyzeUrlFactory by lazy {
        HttpTtsAnalyzeUrlFactory { url, source, readTimeout, coroutineContext, variables ->
            AnalyzeUrl(
                url,
                source = source,
                readTimeout = readTimeout,
                coroutineContext = coroutineContext,
                variables = variables,
            )
        }
    }
    private val downloadScheduler by lazy {
        HttpTtsDownloadScheduler(ttsCacheDirProvider, analyzeUrlFactory).also {
            it.speechRate = speechRate
        }
    }

    private var speechRate: Int = AppConfig.speechRatePlay + 5
    private var downloadTask: Coroutine<*>? = null
    private var prefetchTask: Coroutine<*>? = null

    /** 当前预取窗口的标识(章标题|源|语速|段数), 用于判断是否需要重开窗口。 */
    private var prefetchKey: String? = null
    private var playIndexJob: Job? = null
    private var playErrorNo = 0
    private val downloadTaskActiveLock = Mutex()
    private val loadingState = MutableStateFlow(false)

    // region TtsDiag: 定位"换段时朗读高亮闪到别的段落" (定位完成后整块删除)
    /** 本段 play() 发起时刻, 用于统计"段末 → 新媒体项就绪"的窗口长度 (ns)。 */
    private var diagPlayAtNanos = 0L
    /** 本段字节是否命中内存缓存。 */
    private var diagCacheHit = false
    /** 上一段 ramp 在新令牌生效后仍继续上报的次数 (跨段拼位置的直接证据)。 */
    private var diagStaleEmits = 0
    /** 上述残留上报里最大的段内偏移。 */
    private var diagStaleMaxOffset = 0
    // endregion

    /** 在线 TTS 音频内存缓存: 播放与预取共用一份。 */
    private val memoryCache by lazy { TtsAudioMemoryCache() }

    /** 正在请求中的段落, 防止"预取"和"播放"对同一段各发一次网络请求。 */
    private val inFlightSegments: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())

    override fun onCreate() {
        super.onCreate()
        exoPlayer.addListener(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        downloadTask?.cancel()
        prefetchTask?.cancel()
        memoryCache.clear()
        exoPlayer.release()
        cache.release()
        Coroutine.async {
            downloadScheduler.removeCacheFile(textChapter?.title)
        }
    }

    override fun play() {
        pageChanged = false
        diagPlayAtNanos = System.nanoTime()
        // 换段/换章时先关掉上一段的字级上报: 否则它会带着旧段的偏移量继续上报,
        // 而控制器此时已经推进到新段起点, 合成出来的位置就落到别的段落 (高亮闪动)
        playIndexJob?.cancel()
        exoPlayer.stop()
        if (!requestFocus()) return
        if (currentPlaybackText.isEmpty()) {
            AppLog.putDebug("朗读列表为空")
            readBook?.readAloud()
        } else {
            super.play()
            if (AppConfig.streamReadAloudAudio) {
                downloadAndPlayAudiosStream()
            } else {
                downloadAndPlayAudios()
            }
        }
    }

    override fun playStop() {
        exoPlayer.stop()
        playIndexJob?.cancel()
    }

    private fun updateNextPos(token: Long) {
        readAloudController.paragraphCompleted(token)
    }

    /**
     * 非流式播放当前段: 字节来自内存缓存, 未命中才发网络请求。
     *
     * 旧实现把"整章剩余段落"交给调度器一次下载完, 重构后改成每次只喂当前一段, 于是
     * 每念完一段都要重新等一次网络请求。现在由 [startPrefetch] 在本段之后提前把后续
     * 段落拉进内存, 换段时命中缓存即可直接出音。
     */
    private fun downloadAndPlayAudios() {
        downloadTask?.cancel()
        // 一次只留一个媒体项: 残留的旧项会让播放器自动播下去, 控制器按 token 收到
        // 意料之外的段完成通知 (旧实现由调度器的 onClearMediaItems 清, 内存方案自己清)
        exoPlayer.clearMediaItems()
        val playbackToken = currentPlaybackToken
        val text = currentPlaybackText
        downloadTask = execute {
            downloadTaskActiveLock.withLock {
                ensureActive()
                val httpTts = ReadAloud.httpTTS ?: throw NoStackTraceException("tts is null")
                val fileName = downloadScheduler.md5SpeakFileName(text, textChapter?.title, httpTts.url)
                val bytes = obtainSegmentBytes(httpTts, text, fileName)
                withContext(Main) {
                    // 令牌校验: 下载期间用户可能已经换段/换章, 迟到的音频不能插进新队列
                    if (playbackToken == currentPlaybackToken) {
                        addSpeakMediaItem(fileName, playbackToken)
                    }
                }
            }
        }.onError {
            when (it) {
                is CancellationException -> Unit
                else -> {
                    AppLog.put("朗读下载出错\n${it.localizedMessage}", it, true)
                    pauseReadAloud()
                }
            }
        }
        startPrefetch()
    }

    /**
     * 取一段音频字节: 先查内存缓存, 未命中再走网络; 空文本或下载出错用无声音频占位。
     *
     * 与预取共用同一条取字节路径, 因此 [inFlightSegments] 里已有的段不会再发一次请求,
     * 只是这里的调用方永远需要结果, 不会跳过 (跳过的是预取侧)。
     */
    private suspend fun obtainSegmentBytes(
        httpTts: HttpTTS,
        text: String,
        fileName: String,
    ): ByteArray {
        memoryCache.get(fileName)?.let {
            diagCacheHit = true
            return it
        }
        diagCacheHit = false
        inFlightSegments.add(fileName)
        val bytes = try {
            downloadScheduler.fetchSpeakBytesOrSilent(httpTts, text, currentCoroutineContext())
        } finally {
            inFlightSegments.remove(fileName)
        }
        memoryCache.put(fileName, bytes)
        return bytes
    }

    private fun addSpeakMediaItem(fileName: String, playbackToken: Long) {
        val mediaItem = MediaItem.Builder()
            .setUri(TtsAudioDataSourceFactory.uriOf(fileName))
            .setMimeType(MimeTypes.AUDIO_MPEG)
            .setMediaId(playbackToken.toString())
            .build()
        exoPlayer.addMediaItem(mediaItem)
    }

    /**
     * 启动预取: 当前段之后的 N 段 + 下一章前 N 段, 段数取设置项 `httpTtsPrefetchCount`。
     *
     * 只在切章/换语速/换源/服务销毁时取消重启, **换段不打断** —— 这正是旧实现失效的原因:
     * 它尾随在下载任务里, 每次换段都被 `downloadTask?.cancel()` 杀掉, 每次都从第 0 段重来。
     */
    private fun startPrefetch() {
        val count = configuredHttpTtsPrefetchCount()
        if (count <= 0) {
            prefetchTask?.cancel()
            prefetchKey = null
            return
        }
        val chapter = textChapter ?: return
        val httpTts = ReadAloud.httpTTS ?: return
        val ttsUrl = httpTts.url
        // 章/源/语速/段数任一变化都要重开窗口; 同一章内则让正在跑的那次跑完,
        // 否则每换一段就取消一次在途请求, 服务端慢的时候等于白下载
        val key = "${chapter.title}|$ttsUrl|$speechRate|$count"
        if (key != prefetchKey) {
            prefetchTask?.cancel()
            prefetchKey = key
            downloadScheduler.resetPrefetchErrorBreaker()
        } else if (prefetchTask?.isActive == true) {
            return
        }
        val snapshot = readAloudController.playbackQueue.snapshot
        val currentParagraphs = snapshot.contentList
        val fromIndex = snapshot.nowSpeak + 1
        val nextChapter = readBook?.nextTextChapter?.value
        val pageSplit = readAloudByPage
        prefetchTask = execute {
            // 本章后续段落: 标题用本章的
            currentParagraphs.asSequence()
                .drop(fromIndex)
                .take(count)
                .forEach { text ->
                    ensureActive()
                    prefetchSegment(httpTts, text, chapter.title, ttsUrl)
                }
            // 下一章开头段落: 标题必须用下一章自己的。用当前章标题算 hash, 翻章后文件名对不上,
            // 预取的文件一个都命中不到 (旧实现的功能性 bug)
            nextChapter?.let { next ->
                ReadAloudQueue.splitParagraphsSequence(
                    next.getNeedReadAloud(0, pageSplit, 0, 1)
                ).take(count).forEach { text ->
                    ensureActive()
                    prefetchSegment(httpTts, text, next.title, ttsUrl)
                }
            }
        }
    }

    private suspend fun prefetchSegment(
        httpTts: HttpTTS,
        text: String,
        chapterTitle: String?,
        ttsUrl: String?,
    ) {
        val fileName = downloadScheduler.md5SpeakFileName(text, chapterTitle, ttsUrl)
        if (memoryCache.contains(fileName)) return
        // 播放侧正在下载同一段时不重复发请求
        if (!inFlightSegments.add(fileName)) return
        try {
            val bytes = downloadScheduler.fetchSpeakBytesOrSilent(
                httpTts = httpTts,
                text = text,
                coroutineContext = currentCoroutineContext(),
                isPrefetch = true,
            )
            memoryCache.put(fileName, bytes)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 预取失败不影响正在播放的段落, 只降级为"这一段没提前准备好"
            AppLog.putDebug("TTS 预取失败: ${e.localizedMessage}")
        } finally {
            inFlightSegments.remove(fileName)
        }
    }

    private fun downloadAndPlayAudiosStream() {
        exoPlayer.clearMediaItems()
        downloadTask?.cancel()
        downloadTask = execute {
            val scope = this
            downloadTaskActiveLock.withLock {
                ensureActive()
                val httpTts = ReadAloud.httpTTS ?: throw NoStackTraceException("tts is null")
                val downloaderChannel = Channel<Downloader>()
                launch {
                    for (downloader in downloaderChannel) {
                        downloader.download(null)
                    }
                }
                listOf(currentPlaybackText).forEach { text ->
                    ensureActive()
                    val speakText = text.replace(AppPattern.notReadAloudRegex, "")
                    if (speakText.isEmpty()) {
                        AppLog.put("阅读段落内容为空，使用无声音频代替。\n朗读文本：$text")
                    }
                    val fileName = downloadScheduler.md5SpeakFileName(
                        text, textChapter?.title, ReadAloud.httpTTS?.url
                    )
                    val dataSourceFactory = createDataSourceFactory(httpTts, speakText)
                    val mediaSource =
                        createMediaSource(dataSourceFactory, fileName, currentPlaybackToken)
                    scope.launch(Main) {
                        if (mediaSource.mediaItem.mediaId == currentPlaybackToken.toString()) {
                            exoPlayer.addMediaSource(mediaSource)
                        }
                    }
                }
                preDownloadAudiosStream(httpTts, downloaderChannel)
            }
        }.onError {
            AppLog.put("朗读下载出错\n${it.localizedMessage}", it, true)
        }
    }

    @OptIn(FlowPreview::class)
    private suspend fun preDownloadAudiosStream(
        httpTts: HttpTTS,
        downloaderChannel: Channel<Downloader>
    ) {
        val textChapter = readBook?.nextTextChapter?.value ?: return
        val contentList = ReadAloudQueue
            .splitParagraphsSequence(textChapter.getNeedReadAloud(0, readAloudByPage, 0, 1))
            .toList()
        val flow = loadingState.debounce(1.seconds)
        contentList.forEach { content ->
            currentCoroutineContext().ensureActive()
            val fileName = downloadScheduler.md5SpeakFileName(
                content, this.textChapter?.title, ReadAloud.httpTTS?.url
            )
            val speakText = content.replace(AppPattern.notReadAloudRegex, "")
            val dataSourceFactory = createDataSourceFactory(httpTts, speakText)
            val downloader = createDownloader(dataSourceFactory, fileName)
            downloaderChannel.send(downloader)
            flow.first { !it }
        }
    }

    private fun createDataSourceFactory(
        httpTts: HttpTTS,
        speakText: String
    ): CacheDataSource.Factory {
        val upstreamFactory = DataSource.Factory {
            InputStreamDataSource {
                if (speakText.isEmpty()) {
                    null
                } else {
                    kotlin.runCatching {
                        runBlocking(lifecycleScope.coroutineContext[Job]!!) {
                            downloadScheduler.getSpeakStream(httpTts, speakText, currentCoroutineContext())
                        }
                    }.onFailure {
                        when (it) {
                            is InterruptedException,
                            is CancellationException -> Unit

                            else -> pauseReadAloud()
                        }
                    }.getOrThrow()
                } ?: resources.openRawResource(R.raw.silent_sound)
            }
        }
        val factory = CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(upstreamFactory)
            .setCacheWriteDataSinkFactory(cacheDataSinkFactory)
        return factory
    }

    private fun createDownloader(factory: CacheDataSource.Factory, fileName: String): Downloader {
        val uri = fileName.toUri()
        val request = DownloadRequest.Builder(fileName, uri).build()
        return DefaultDownloaderFactory(factory, okHttpClient.dispatcher.executorService)
            .createDownloader(request)
    }

    private fun createMediaSource(
        factory: DataSource.Factory,
        fileName: String,
        playbackToken: Long,
    ): MediaSource {
        val item = MediaItem.Builder()
            .setUri(fileName)
            .setMediaId(playbackToken.toString())
            .build()
        return DefaultMediaSourceFactory(this)
            .setDataSourceFactory(factory)
            .setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
            .createMediaSource(item)
    }


    private fun upPlayPos() {
        playIndexJob?.cancel()
        textChapter ?: return
        playIndexJob = lifecycleScope.launch {
            // 一次取快照: 本协程按当前段时长逐字上报, 逐次重读队列会跨段/跨章取到混合值
            val playback = readAloudController.playbackQueue.snapshot
            val rampToken = currentPlaybackToken
            if (exoPlayer.duration <= 0) {
                return@launch
            }
            val speakTextLength = playback.currentParagraph()?.length ?: 0
            if (speakTextLength <= 0) {
                return@launch
            }
            val sleep = exoPlayer.duration / speakTextLength
            val start = speakTextLength * exoPlayer.currentPosition / exoPlayer.duration
            // TtsDiag: 每段一行, 换段窗口 + 上一轮残留上报
            AppLog.put(
                "TtsDiag 段就绪 token=$rampToken idx=${playback.nowSpeak} base=${playback.readAloudNumber} " +
                    "段长=$speakTextLength 起点=$start 时长=${exoPlayer.duration} 命中缓存=$diagCacheHit " +
                    "换段耗时=${(System.nanoTime() - diagPlayAtNanos) / 1_000_000}ms " +
                    "上轮拦截数=$diagStaleEmits(最大偏移=$diagStaleMaxOffset)",
                tag = "TtsDiag"
            )
            diagStaleEmits = 0
            diagStaleMaxOffset = 0
            for (i in start..speakTextLength.toLong()) {
                // 令牌已被换段/换章替换: i 属于上一段, 不能再用 (哪怕用一个字也算新段的位置)
                if (rampToken != currentPlaybackToken) {
                    diagStaleEmits++
                    if (i > diagStaleMaxOffset) diagStaleMaxOffset = i.toInt()
                    // TtsDiag: 修好后这里应当永不出现 (定位完成后删除)
                    AppLog.put(
                        "TtsDiag 拦截过期上报 发射token=$rampToken 当前token=$currentPlaybackToken i=$i",
                        tag = "TtsDiag"
                    )
                    return@launch
                }
                // 播放器无字级回调, 按已播时长折算段内偏移; 位置合成与发布只在共享层做一份。
                // 必须传本轮自己的令牌: 传 currentPlaybackToken 会在"校验通过后又换轮"时
                // 让旧段的偏移命中新轮次的令牌校验, 把位置拼错
                readAloudController.reportEngineRangeStart(rampToken, i.toInt())
                delay(sleep)
            }
        }
    }

    /**
     * 更新朗读速度
     */
    override fun upSpeechRate(reset: Boolean) {
        downloadTask?.cancel()
        exoPlayer.stop()
        speechRate = AppConfig.speechRatePlay + 5
        downloadScheduler.speechRate = speechRate
        if (AppConfig.streamReadAloudAudio) {
            downloadAndPlayAudiosStream()
        } else {
            downloadAndPlayAudios()
        }
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        super.onPlaybackStateChanged(playbackState)
        when (playbackState) {
            Player.STATE_IDLE -> {
                // 空闲
            }

            Player.STATE_BUFFERING -> {
                // 缓冲中
            }

            Player.STATE_READY -> {
                // 准备好
                if (pause) return
                exoPlayer.play()
                upPlayPos()
            }

            Player.STATE_ENDED -> {
                // 只接受媒体项携带的 controller token；旧 playlist 的迟到 ENDED 无权推进新队列。
                val token = exoPlayer.currentMediaItem?.mediaId?.toLongOrNull() ?: return
                playErrorNo = 0
                updateNextPos(token)
                exoPlayer.stop()
                exoPlayer.clearMediaItems()
            }
        }
    }

    override fun onTimelineChanged(timeline: Timeline, reason: Int) {
        when (reason) {
            Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED -> {
                if (!timeline.isEmpty && exoPlayer.playbackState == Player.STATE_IDLE) {
                    exoPlayer.prepare()
                }
            }

            else -> {}
        }
    }

    override fun onIsLoadingChanged(isLoading: Boolean) {
        loadingState.value = isLoading
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) return
        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
            playErrorNo = 0
        }
        updateNextPos(mediaItem?.mediaId?.toLongOrNull() ?: return)
        upPlayPos()
    }

    override fun onPlayerError(error: PlaybackException) {
        super.onPlayerError(error)
        AppLog.put(
            "朗读错误\n${readAloudController.playbackQueue.snapshot.currentParagraph().orEmpty()}",
            error
        )
        deleteCurrentSpeakFile()
        playErrorNo++
        if (playErrorNo >= 5) {
            toastOnUi("朗读连续5次错误, 最后一次错误代码(${error.localizedMessage})")
            AppLog.put("朗读连续5次错误, 最后一次错误代码(${error.localizedMessage})", error)
            pauseReadAloud()
        } else {
            if (exoPlayer.hasNextMediaItem()) {
                exoPlayer.seekToNextMediaItem()
                exoPlayer.prepare()
            } else {
                val token = exoPlayer.currentMediaItem?.mediaId?.toLongOrNull()
                    ?: currentPlaybackToken
                exoPlayer.clearMediaItems()
                updateNextPos(token)
            }
        }
    }

    /**
     * 丢弃当前段的内存音频, 让下次重试重新下载。
     *
     * 内存方案下没有文件可删: 出错后如果留着这段坏字节, `onPlayerError` 的重试会反复读到
     * 同一份内容, 一直失败到 5 次上限。移出缓存即可让下一次取字节走网络。
     */
    private fun deleteCurrentSpeakFile() {
        if (AppConfig.streamReadAloudAudio) {
            return
        }
        val uri = exoPlayer.currentMediaItem?.localConfiguration?.uri ?: return
        if (uri.scheme != TtsAudioDataSourceFactory.SCHEME) return
        uri.lastPathSegment?.let { memoryCache.remove(it) }
    }

    override fun aloudServicePendingIntent(actionStr: String): PendingIntent? {
        return servicePendingIntent<HttpReadAloudService>(actionStr)
    }

    /** 在线 TTS 预取段数 (0 = 关闭): 存的是字符串, 解析失败/超范围一律回落到默认值。 */
    private fun configuredHttpTtsPrefetchCount(): Int {
        val raw = PreferenceProviders.get().getStringOrNull(PreferKey.httpTtsPrefetchCount)
        val value = raw?.toIntOrNull() ?: AppConfigConstants.httpTtsPrefetchCountDefault
        return value.coerceIn(AppConfigRanges.httpTtsPrefetchCount)
    }

    // HttpTtsCacheDirProvider 实现: 桥接 Android cacheDir + java.io.File 操作
    private inner class HttpTtsCacheDirProviderImpl : HttpTtsCacheDirProvider {
        override fun ttsCacheDir(): String = ttsFolderPath

        override fun currentTimeMillis(): Long = System.currentTimeMillis()

        override fun exist(path: String): Boolean = FileUtils.exist(path)

        override fun listDirsAndFiles(dirPath: String): List<HttpTtsFileInfo>? =
            FileUtils.listDirsAndFiles(dirPath)?.map {
                HttpTtsFileInfo(it.name, it.length(), it.lastModified())
            }

        override fun delete(path: String): Boolean = FileUtils.delete(path)

        override fun silentSoundBytes(): ByteArray =
            resources.openRawResource(R.raw.silent_sound).readBytes()
    }

    class CustomLoadErrorHandlingPolicy : DefaultLoadErrorHandlingPolicy(0) {
        override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
            return C.TIME_UNSET
        }
    }

}
