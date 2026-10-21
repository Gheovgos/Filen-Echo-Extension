package dev.brahmkshatriya.echo.extension.auth

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.io.InputStream
import kotlin.random.Random

class FilenChunkInputStream(
    private val http: OkHttpClient,
    private val apiKey: String,
    private val file: FilenDriveClient.File,
    startPosition: Long
) : InputStream() {

    companion object {
        const val CHUNK_SIZE = 1024 * 1024
        private val HOSTS = listOf("egest.filen.io", "egest.filen.net") + (1..6).map { "egest.filen-$it.net" }
    }

    private var chunkIndex = (startPosition / CHUNK_SIZE).toInt()
    private var skipInFirst = (startPosition % CHUNK_SIZE).toInt()
    private var first = true
    private var buffer = ByteArray(0)
    private var bufPos = 0

    private fun download(index: Int): ByteArray {
        var last: Exception? = null
        repeat(3) {
            try {
                val host = HOSTS[Random.nextInt(HOSTS.size)]
                val req = Request.Builder()
                    .url("https://$host/${file.region}/${file.bucket}/${file.uuid}/$index")
                    .header("Authorization", "Bearer $apiKey")
                    .build()
                http.newCall(req).execute().use { res ->
                    if (!res.isSuccessful) error("HTTP ${res.code}")
                    return FilenCrypto.decryptChunk(res.body.bytes(), file.key)
                }
            } catch (e: Exception) {
                last = e
            }
        }
        throw IOException("Download chunk $index failed", last)
    }

    private fun fill(): Boolean {
        while (bufPos >= buffer.size) {
            if (chunkIndex >= file.chunks) return false
            buffer = download(chunkIndex++)
            bufPos = if (first) { first = false; minOf(skipInFirst, buffer.size) } else 0
        }
        return true
    }

    override fun read(): Int = if (fill()) buffer[bufPos++].toInt() and 0xFF else -1

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (!fill()) return -1
        val n = minOf(len, buffer.size - bufPos)
        System.arraycopy(buffer, bufPos, b, off, n)
        bufPos += n
        return n
    }
}