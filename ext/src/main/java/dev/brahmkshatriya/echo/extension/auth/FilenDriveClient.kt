package dev.brahmkshatriya.echo.extension.auth

import dev.brahmkshatriya.echo.extension.flog
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

class FilenDriveClient(private val api: FilenApiClient, private val session: FilenSession) {

    data class Folder(val uuid: String, val name: String)

    data class File(
        val uuid: String, val name: String, val mime: String, val size: Long,
        val key: String, val region: String, val bucket: String, val chunks: Int,
        val lastModified: Long = 0L,  // mtime client (ms)
        val creation: Long = 0L       // upload date Filen (ms)
    )

    data class Listing(val folders: List<Folder>, val files: List<File>)

    data class Node(val folder: Folder?, val tracks: List<File>, val children: List<Node>)

    private val json = Json { ignoreUnknownKeys = true }
    private val pathCache = mutableMapOf<String, String>()

    private val audioExt = setOf("mp3", "flac", "m4a", "aac", "ogg", "opus", "wav", "wma")

    private fun JsonObject.str(k: String) = this[k]?.jsonPrimitive?.contentOrNull
    private fun JsonObject.arr(k: String) = (this[k] as? JsonArray) ?: JsonArray(emptyList())

    private fun JsonObject.tsMs(k: String): Long {
        val p = this[k]?.jsonPrimitive ?: return 0L
        val n = p.longOrNull ?: p.contentOrNull?.toLongOrNull() ?: return 0L
        return if (n in 1 until 100_000_000_000L) n * 1000 else n  // seconds -> ms
    }
    private fun decrypt(meta: String) = FilenCrypto.decryptMetadata(meta, session.masterKeys)

    // GET /v3/user/baseFolder -> data.uuid
    suspend fun baseFolder(): String =
        api.get("/v3/user/baseFolder", session.apiKey).str("uuid") ?: error("baseFolder: uuid missing")

    // POST /v3/dir/content {uuid} -> data.folders[], data.uploads[]
    suspend fun list(uuid: String): Listing {
        val data = api.post("/v3/dir/content", buildJsonObject { put("uuid", uuid) }, session.apiKey)
        flog(data.toString())
        if (data["uploads"] == null && data["folders"] == null) {
            error("Response: ${data.keys}")
        }

        val folders = data.arr("folders").map { el ->
            val o = el.jsonObject
            try {
                val plain = decrypt(o.str("name")!!)
                val name = runCatching { json.parseToJsonElement(plain).jsonObject.str("name") }.getOrNull() ?: plain
                Folder(o.str("uuid")!!, name)
            } catch (e: Exception) {
                throw IllegalStateException("Reading folder was not possible: (${e::class.simpleName}: ${e.message}); fields: ${o.keys}", e)
            }
        }

        val files = data.arr("uploads").map { el ->
            val o = el.jsonObject
            try {
                val meta = json.parseToJsonElement(decrypt(o.str("metadata")!!)).jsonObject
                flog(meta.toString())
                File(
                    uuid = o.str("uuid")!!,
                    name = meta.str("name")!!,
                    mime = meta.str("mime").orEmpty(),
                    size = meta["size"]?.jsonPrimitive?.longOrNull ?: 0L,
                    key = meta.str("key")!!,
                    region = o.str("region")!!,
                    bucket = o.str("bucket")!!,
                    chunks = o["chunks"]?.jsonPrimitive?.intOrNull ?: 1,
                    lastModified = meta.tsMs("lastModified"),
                    creation = meta.tsMs("creation")
                )
            } catch (e: Exception) {
                throw IllegalStateException("File was not readable (${e::class.simpleName}: ${e.message}); fields: ${o.keys}", e)
            }
        }
        return Listing(folders, files)
    }

    suspend fun resolvePath(path: String): String {
        val segments = path.split('/').filter { it.isNotBlank() }
        val norm = "/" + segments.joinToString("/")
        pathCache[norm]?.let { return it }
        var current = baseFolder()
        for (seg in segments) {
            current = list(current).folders.firstOrNull { it.name.equals(seg, ignoreCase = true) }?.uuid
                ?: error("Folder not found: \"$seg\" in $norm")
        }
        pathCache[norm] = current
        return current
    }

    private fun isAudio(f: File) =
        f.mime.startsWith("audio/") || f.name.substringAfterLast('.', "").lowercase() in audioExt

    /** Recursive search, each subfolder is checked. */
    suspend fun scan(uuid: String, folder: Folder? = null, depth: Int = 0, maxDepth: Int = 5): Node {
        val listing = list(uuid)
        val children = if (depth < maxDepth)
            listing.folders.map { scan(it.uuid, it, depth + 1, maxDepth) }
        else emptyList()
        return Node(folder, listing.files.filter(::isAudio).sortedBy { it.name.lowercase() }, children)
    }
}