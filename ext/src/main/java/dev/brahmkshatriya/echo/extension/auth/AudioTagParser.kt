package dev.brahmkshatriya.echo.extension.auth

class AudioTags(val title: String?, val artist: String?, val album: String?, val cover: ByteArray?)

object AudioTagParser {
    private val EMPTY = AudioTags(null, null, null, null)

    private fun u8(d: ByteArray, o: Int) = d[o].toInt() and 0xFF
    private fun be32(d: ByteArray, o: Int) = (u8(d, o) shl 24) or (u8(d, o + 1) shl 16) or (u8(d, o + 2) shl 8) or u8(d, o + 3)
    private fun le32(d: ByteArray, o: Int) = u8(d, o) or (u8(d, o + 1) shl 8) or (u8(d, o + 2) shl 16) or (u8(d, o + 3) shl 24)
    private fun synchsafe(d: ByteArray, o: Int) =
        ((u8(d, o) and 0x7F) shl 21) or ((u8(d, o + 1) and 0x7F) shl 14) or ((u8(d, o + 2) and 0x7F) shl 7) or (u8(d, o + 3) and 0x7F)

    private fun isId3(d: ByteArray) = d.size >= 10 && String(d, 0, 3, Charsets.ISO_8859_1) == "ID3"
    private fun isFlac(d: ByteArray) = d.size >= 4 && String(d, 0, 4, Charsets.ISO_8859_1) == "fLaC"

    /** Byte necessari per avere tutti i metadati all'inizio del file (0 = formato non supportato). */
    fun requiredBytes(d: ByteArray): Int = when {
        isId3(d) -> 10 + synchsafe(d, 6)
        isFlac(d) -> flacMetadataEnd(d)
        else -> 0
    }

    private fun flacMetadataEnd(d: ByteArray): Int {
        var pos = 4
        while (true) {
            if (pos + 4 > d.size) return d.size + 1 // header oltre i dati scaricati: ne servono altri
            val last = (u8(d, pos) and 0x80) != 0
            pos += 4 + ((u8(d, pos + 1) shl 16) or (u8(d, pos + 2) shl 8) or u8(d, pos + 3))
            if (last) return pos
        }
    }

    fun parse(d: ByteArray): AudioTags = try {
        when {
            isId3(d) -> parseId3(d)
            isFlac(d) -> parseFlac(d)
            else -> EMPTY
        }
    } catch (e: Exception) {
        EMPTY
    }

    // ---------- ID3v2 ----------

    private fun text(b: ByteArray): String? {
        if (b.isEmpty()) return null
        val cs = when (b[0].toInt()) {
            1 -> Charsets.UTF_16
            2 -> Charsets.UTF_16BE
            3 -> Charsets.UTF_8
            else -> Charsets.ISO_8859_1
        }
        return String(b, 1, b.size - 1, cs).split('\u0000').firstOrNull { it.isNotBlank() }?.trim()
    }

    private fun apic(b: ByteArray, v22: Boolean): ByteArray? {
        if (b.size < 4) return null
        val enc = b[0].toInt()
        var p = if (v22) 4 else {
            var e = 1
            while (e < b.size && b[e].toInt() != 0) e++
            e + 1
        }
        p += 1 // tipo immagine
        val wide = enc == 1 || enc == 2
        while (p < b.size) {
            if (!wide && b[p].toInt() == 0) { p += 1; break }
            if (wide && p + 1 < b.size && b[p].toInt() == 0 && b[p + 1].toInt() == 0) { p += 2; break }
            p += if (wide) 2 else 1
        }
        return if (p < b.size) b.copyOfRange(p, b.size) else null
    }

    private fun parseId3(d: ByteArray): AudioTags {
        val version = u8(d, 3)
        val end = minOf(d.size, 10 + synchsafe(d, 6))
        var pos = 10
        if (version >= 3 && (u8(d, 5) and 0x40) != 0 && pos + 4 <= end) {
            pos += if (version == 4) synchsafe(d, pos) else be32(d, pos) + 4
        }
        val idLen = if (version == 2) 3 else 4
        val hdr = if (version == 2) 6 else 10
        var title: String? = null
        var artist: String? = null
        var album: String? = null
        var cover: ByteArray? = null

        while (pos + hdr <= end && u8(d, pos) != 0) {
            val id = String(d, pos, idLen, Charsets.ISO_8859_1)
            val size = when (version) {
                2 -> (u8(d, pos + 3) shl 16) or (u8(d, pos + 4) shl 8) or u8(d, pos + 5)
                4 -> synchsafe(d, pos + 4)
                else -> be32(d, pos + 4)
            }
            val start = pos + hdr
            if (size <= 0 || start + size > end) break
            val body = d.copyOfRange(start, start + size)
            when (id) {
                "TIT2", "TT2" -> title = text(body)
                "TPE1", "TP1" -> artist = text(body)
                "TALB", "TAL" -> album = text(body)
                "APIC", "PIC" -> if (cover == null) cover = apic(body, id == "PIC")
            }
            pos = start + size
        }
        return AudioTags(title, artist, album, cover)
    }

    // ---------- FLAC ----------

    private fun parseFlac(d: ByteArray): AudioTags {
        var pos = 4
        var title: String? = null
        var artist: String? = null
        var album: String? = null
        var cover: ByteArray? = null

        while (pos + 4 <= d.size) {
            val header = u8(d, pos)
            val last = (header and 0x80) != 0
            val type = header and 0x7F
            val len = (u8(d, pos + 1) shl 16) or (u8(d, pos + 2) shl 8) or u8(d, pos + 3)
            val start = pos + 4
            if (start + len > d.size) break

            when (type) {
                4 -> { // VORBIS_COMMENT (little endian)
                    var p = start
                    p += 4 + le32(d, p) // vendor
                    val count = le32(d, p); p += 4
                    for (i in 0 until count) {
                        if (p + 4 > start + len) break
                        val l = le32(d, p); p += 4
                        if (l < 0 || p + l > start + len) break
                        val s = String(d, p, l, Charsets.UTF_8); p += l
                        val eq = s.indexOf('=')
                        if (eq > 0) {
                            val v = s.substring(eq + 1).trim().ifEmpty { null }
                            when (s.substring(0, eq).uppercase()) {
                                "TITLE" -> title = v
                                "ARTIST" -> artist = v
                                "ALBUM" -> album = v
                            }
                        }
                    }
                }
                6 -> { // PICTURE (big endian)
                    var p = start + 4
                    p += 4 + be32(d, p) // mime
                    p += 4 + be32(d, p) // descrizione
                    p += 16 // larghezza, altezza, profondità, colori
                    val dataLen = be32(d, p); p += 4
                    if (cover == null && dataLen > 0 && p + dataLen <= start + len) {
                        cover = d.copyOfRange(p, p + dataLen)
                    }
                }
            }
            if (last) break
            pos = start + len
        }
        return AudioTags(title, artist, album, cover)
    }
}