package dev.brahmkshatriya.echo.extension.auth

import dev.brahmkshatriya.echo.extension.flog
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import java.io.ByteArrayOutputStream

class FilenTagService(private val http: OkHttpClient, private val apiKey: String) {

    data class Meta(val title: String?, val artist: String?, val album: String?, val coverPath: String?, val durationMs: Long?) {
    }

    private val dir = java.io.File(System.getProperty("java.io.tmpdir") ?: ".", "filen_ext").also { it.mkdirs() }
    private val maxBytes = 4 * 1024 * 1024
    private val chunk = FilenChunkInputStream.CHUNK_SIZE

    fun meta(file: FilenDriveClient.File): Meta {
        val cacheFile = java.io.File(dir, "${file.uuid}.json")
        if (cacheFile.exists()) {
            try { return decode(cacheFile.readText()) } catch (e: Exception) { flog("cache not readable", e) }
        }
        val tags = readTags(file)
        var coverPath: String? = null
        tags.cover?.let { bytes ->
            val ext = if (bytes.size > 3 && u8(bytes[0]) == 0x89) "png" else "jpg"
            val f = java.io.File(dir, "${file.uuid}.$ext")
            f.writeBytes(bytes)
            coverPath = f.absolutePath
        }
        val meta = fromTags(file.name, tags, coverPath)
        cacheFile.writeText(encode(meta))
        return meta
    }

    private fun u8(b: Byte) = b.toInt() and 0xFF

    private fun readTags(file: FilenDriveClient.File): AudioTags {
        FilenChunkInputStream(http, apiKey, file, 0).use { s ->
            val out = ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            var target = chunk
            while (true) {
                while (out.size() < target) {
                    val n = s.read(buf, 0, minOf(buf.size, target - out.size()))
                    if (n < 0) return AudioTagParser.parse(out.toByteArray())
                    out.write(buf, 0, n)
                }
                val need = AudioTagParser.requiredBytes(out.toByteArray())
                if (need <= out.size() || out.size() >= maxBytes) break
                target = minOf(maxBytes, maxOf(need, out.size() + chunk))
            }
            return AudioTagParser.parse(out.toByteArray())
        }
    }

    private fun fromTags(fileName: String, tags: AudioTags, coverPath: String?): Meta {
        val base = fileName.substringBeforeLast('.')
        var title = tags.title
        var artist = tags.artist
        if (artist == null && " - " in base) {
            val left = base.substringBefore(" - ").trim()
            if (left.isNotEmpty() && left.any { !it.isDigit() }) {
                artist = left
                if (title == null) title = base.substringAfter(" - ").trim().ifEmpty { null }
            }
        }
        return Meta(title, artist, tags.album, coverPath, null)
    }

    private fun encode(m: Meta) = buildJsonObject {
        put("title", m.title); put("artist", m.artist); put("album", m.album); put("cover", m.coverPath)
    }.toString()

    private fun decode(s: String): Meta {
        val o = Json.parseToJsonElement(s).jsonObject
        fun str(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
        return Meta(str("title"), str("artist"), str("album"), str("cover"), null)
    }
}