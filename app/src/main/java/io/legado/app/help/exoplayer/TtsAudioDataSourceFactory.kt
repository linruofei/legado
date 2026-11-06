package io.legado.app.help.exoplayer

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.TransferListener

/**
 * 在线 TTS 内存音频的数据源工厂。
 *
 * 只接管 [SCHEME] 协议, 其余协议 (file/asset/content/http) 原样交给 [DefaultDataSource],
 * 因此这个工厂可以安全地当作播放器的默认数据源工厂 —— 播放器上其他媒体项的行为不变。
 *
 * @param lookup 按段标识取内存字节, 未命中返回 null
 */
@SuppressLint("UnsafeOptInUsageError")
class TtsAudioDataSourceFactory(
    appContext: Context,
    private val lookup: (String) -> ByteArray?,
) : DataSource.Factory {

    private val fallbackFactory = DefaultDataSource.Factory(appContext)

    override fun createDataSource(): DataSource =
        RoutingDataSource(fallbackFactory.createDataSource(), lookup)

    /**
     * 按 URI 协议在内存数据源与默认数据源之间路由。
     *
     * 具体走哪一个只有 open 时才知道, 所以 [TransferListener] 先攒着, 选定后一次性挂到
     * 实际使用的那个数据源上 —— 提前挂会漏掉"打开前"就已注册的监听器, 也拿不到正确的统计。
     */
    private class RoutingDataSource(
        private val fallback: DataSource,
        private val lookup: (String) -> ByteArray?,
    ) : DataSource {

        private val listeners = mutableListOf<TransferListener>()
        private var memory: ByteArrayDataSource? = null
        private var delegate: DataSource? = null

        override fun addTransferListener(transferListener: TransferListener) {
            listeners += transferListener
            delegate?.addTransferListener(transferListener)
        }

        override fun open(dataSpec: DataSpec): Long {
            val target = if (dataSpec.uri.scheme == SCHEME) {
                memory ?: ByteArrayDataSource(lookup).also { memory = it }
            } else {
                fallback
            }
            if (target !== delegate) {
                delegate = target
                listeners.forEach { target.addTransferListener(it) }
            }
            return target.open(dataSpec)
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            (delegate ?: fallback).read(buffer, offset, length)

        override fun getUri(): Uri? = (delegate ?: fallback).uri

        override fun getResponseHeaders(): Map<String, List<String>> =
            (delegate ?: fallback).responseHeaders

        override fun close() {
            (delegate ?: fallback).close()
        }
    }

    companion object {
        /** 内存音频协议前缀。 */
        const val SCHEME = "tts-memory"

        /** 构造一个指向某段内存音频的 URI。 */
        fun uriOf(segmentKey: String): Uri = Uri.parse("$SCHEME:///$segmentKey")
    }
}
