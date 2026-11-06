package io.legado.app.help.exoplayer

import android.annotation.SuppressLint
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import java.io.EOFException
import java.io.IOException
import kotlin.math.min

/**
 * 内存字节数据源: 直接从内存里的 [ByteArray] 供给 ExoPlayer。
 *
 * 与 [InputStreamDataSource] 的关键差异: `open()` **返回真实字节数**。
 *
 * 这一条决定了自动翻页能否工作: 播放器要靠 `inputLength` 计算 MP3 的时长
 * (`ConstantBitrateSeeker` 用首帧码率 × 长度), 再据 `duration` 与 `currentPosition`
 * 换算出"读到第几个字", 才有字级进度、朗读位置游标与跟随翻页。流式路径用
 * [InputStreamDataSource] 时长度是 `C.LENGTH_UNSET`, 算不出时长, 长段落就不会翻页。
 *
 * @param supplier 按段标识取字节。取值必须发生在 open 时刻而不是构造时刻:
 *   预取可能刚把这段填进缓存。
 */
@SuppressLint("UnsafeOptInUsageError")
class ByteArrayDataSource(
    private val supplier: (String) -> ByteArray?,
) : BaseDataSource(false) {

    private var dataSpec: DataSpec? = null
    private var data: ByteArray = EMPTY
    private var offset = 0
    private var bytesRemaining = 0L
    private var opened = false

    @Throws(IOException::class)
    override fun open(dataSpec: DataSpec): Long {
        this.dataSpec = dataSpec
        transferInitializing(dataSpec)

        val key = dataSpec.uri.lastPathSegment
            ?: throw IOException("内存 TTS 音频地址缺少段标识: ${dataSpec.uri}")
        val bytes = supplier(key)
            ?: throw IOException("内存中没有该段在线 TTS 音频: $key")
        val position = dataSpec.position
        if (position < 0 || position > bytes.size) {
            throw EOFException("读取位置越界: position=$position, size=${bytes.size}")
        }
        data = bytes
        offset = position.toInt()
        val available = (bytes.size - offset).toLong()
        bytesRemaining = if (dataSpec.length == C.LENGTH_UNSET.toLong()) {
            available
        } else {
            min(dataSpec.length, available)
        }
        opened = true
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun getUri(): Uri? = dataSpec?.uri

    @Throws(IOException::class)
    override fun read(buffer: ByteArray, bufferOffset: Int, readLength: Int): Int {
        if (readLength == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT

        val bytesToRead = min(bytesRemaining, readLength.toLong()).toInt()
        System.arraycopy(data, offset, buffer, bufferOffset, bytesToRead)
        offset += bytesToRead
        bytesRemaining -= bytesToRead
        bytesTransferred(bytesToRead)
        return bytesToRead
    }

    @Throws(IOException::class)
    override fun close() {
        if (!opened) return
        opened = false
        data = EMPTY
        offset = 0
        bytesRemaining = 0
        transferEnded()
    }

    private companion object {
        val EMPTY = ByteArray(0)
    }
}
