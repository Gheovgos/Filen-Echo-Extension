package dev.brahmkshatriya.echo.extension.auth

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object FilenCrypto {

    private const val B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    private fun base64Encode(data: ByteArray): String {
        val sb = StringBuilder((data.size + 2) / 3 * 4)
        var i = 0
        while (i < data.size) {
            val b0 = data[i].toInt() and 0xFF
            val b1 = if (i + 1 < data.size) data[i + 1].toInt() and 0xFF else 0
            val b2 = if (i + 2 < data.size) data[i + 2].toInt() and 0xFF else 0
            sb.append(B64[b0 shr 2])
            sb.append(B64[((b0 and 3) shl 4) or (b1 shr 4)])
            sb.append(if (i + 1 < data.size) B64[((b1 and 15) shl 2) or (b2 shr 6)] else '=')
            sb.append(if (i + 2 < data.size) B64[b2 and 63] else '=')
            i += 3
        }
        return sb.toString()
    }

    private fun base64Decode(s: String): ByteArray {
        val clean = s.filter { !it.isWhitespace() }.trimEnd('=')
        val out = ByteArray(clean.length * 6 / 8)
        var buffer = 0
        var bits = 0
        var idx = 0
        for (c in clean) {
            val v = B64.indexOf(c)
            require(v >= 0) { "Base64 not valid" }
            buffer = (buffer shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out[idx++] = ((buffer shr bits) and 0xFF).toByte()
            }
        }
        return out
    }

    data class Derived(val masterKey: String, val loginPassword: String)

    private fun pbkdf2(password: String, salt: String, iterations: Int, bits: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA512")
        mac.init(SecretKeySpec(password.toByteArray(Charsets.UTF_8), "HmacSHA512"))
        val saltBytes = salt.toByteArray(Charsets.UTF_8)
        val hLen = mac.macLength
        val blocks = (bits / 8 + hLen - 1) / hLen
        val out = ByteArray(blocks * hLen)

        for (i in 1..blocks) {
            mac.update(saltBytes)
            mac.update(byteArrayOf((i ushr 24).toByte(), (i ushr 16).toByte(), (i ushr 8).toByte(), i.toByte()))
            var u = mac.doFinal()
            val t = u.copyOf()
            for (j in 2..iterations) {
                u = mac.doFinal(u)
                for (k in t.indices) t[k] = (t[k].toInt() xor u[k].toInt()).toByte()
            }
            t.copyInto(out, (i - 1) * hLen)
        }
        return out.copyOf(bits / 8)
    }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }

    fun deriveCredentials(rawPassword: String, salt: String): Derived {
        val hex = pbkdf2(rawPassword, salt, 200_000, 512).toHex()
        val half = hex.length / 2
        val sha = MessageDigest.getInstance("SHA-512")
            .digest(hex.substring(half).toByteArray(Charsets.UTF_8)).toHex()
        return Derived(masterKey = hex.substring(0, half), loginPassword = sha)
    }

    private fun metadataKey(masterKey: String) = pbkdf2(masterKey, masterKey, 1, 256)

    private fun randomString(len: Int): String {
        val chars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        val r = SecureRandom()
        return (1..len).map { chars[r.nextInt(chars.length)] }.joinToString("")
    }

    private fun gcm(mode: Int, key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        return c.doFinal(data) // data = ciphertext || tag
    }

    fun encryptMetadata(plain: String, masterKey: String): String {
        val iv = randomString(12)
        val enc = gcm(Cipher.ENCRYPT_MODE, metadataKey(masterKey), iv.toByteArray(), plain.toByteArray())
        return "002" + iv + base64Encode(enc)
    }

    fun decryptMetadata(metadata: String, masterKeys: List<String>): String {
        require(metadata.startsWith("002")) { "Metadata version not supported" }
        val iv = metadata.substring(3, 15).toByteArray()
        val payload = base64Decode(metadata.substring(15))
        for (key in masterKeys.asReversed()) {
            try {
                return gcm(Cipher.DECRYPT_MODE, metadataKey(key), iv, payload).toString(Charsets.UTF_8)
            } catch (_: Exception) { }
        }
        error("No valid master key")
    }

    fun decryptChunk(chunk: ByteArray, fileKey: String): ByteArray {
        val iv = chunk.copyOfRange(0, 12)
        val data = chunk.copyOfRange(12, chunk.size)
        return gcm(Cipher.DECRYPT_MODE, fileKey.toByteArray(Charsets.UTF_8), iv, data)
    }
}