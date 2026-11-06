package io.legado.app.help.tts

/**
 * 在线 TTS 音频的纯内存缓存（按字节数封顶的 LRU）。
 *
 * 为什么按字节而不是按条数: 段落长短差异极大（一句话 vs 一大段),按条数封顶时
 * 短段场景浪费、长段场景又撑爆内存。字节数上限对两种情况都给出确定的内存占用。
 *
 * 键沿用 [io.legado.app.help.tts.HttpTtsDownloadScheduler.md5SpeakFileName] 的文件名,
 * 因此换语速、换 TTS 源、正文变更都会自然产生新键,不需要额外的失效逻辑。
 *
 * 线程安全性: 预取协程与播放协程读写同一个实例,全部方法加锁。
 */
class TtsAudioMemoryCache(
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
) {

    private val entries = LinkedHashMap<String, ByteArray>(16, 0.75f, true)
    private var size = 0L

    @Synchronized
    fun get(key: String): ByteArray? = entries[key]

    @Synchronized
    fun contains(key: String): Boolean = entries.containsKey(key)

    @Synchronized
    fun put(key: String, bytes: ByteArray) {
        val previous = entries.put(key, bytes)
        if (previous != null) size -= previous.size
        size += bytes.size
        trim(preserve = key)
    }

    @Synchronized
    fun remove(key: String): ByteArray? {
        val previous = entries.remove(key) ?: return null
        size -= previous.size
        return previous
    }

    @Synchronized
    fun clear() {
        entries.clear()
        size = 0L
    }

    /** 当前占用的字节数（调试/日志用）。 */
    @Synchronized
    fun totalBytes(): Long = size

    /** 当前缓存的段数（调试/日志用）。 */
    @Synchronized
    fun count(): Int = entries.size

    /**
     * 按访问顺序淘汰到上限以内。
     *
     * [preserve] 是刚写入的那一段: 极端情况下单段就超过上限时, 保住它仍然可以正常播放,
     * 删掉反而让刚下载好的音频白扔, 下次播放要重下。
     */
    private fun trim(preserve: String) {
        if (size <= maxBytes) return
        val iterator = entries.entries.iterator()
        while (size > maxBytes && iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key == preserve && entries.size == 1) return
            size -= entry.value.size
            iterator.remove()
        }
    }

    companion object {
        /** 默认上限 16MB: 32kbps 单声道 mp3 约合 30~130 段, 远超实际预取窗口。 */
        const val DEFAULT_MAX_BYTES = 16L * 1024 * 1024
    }
}
