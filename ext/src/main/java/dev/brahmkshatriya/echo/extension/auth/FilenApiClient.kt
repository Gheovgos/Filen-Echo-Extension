package dev.brahmkshatriya.echo.extension.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class FilenApiClient(
    private val baseUrl: String = "https://gateway.filen.io", // to check
    private val client: OkHttpClient = OkHttpClient()
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** generic POST, TODO*/
    suspend fun post(path: String, body: JsonObject, apiKey: String? = null): JsonObject =
        withContext(Dispatchers.IO) {
            val req = Request.Builder()
                .url(baseUrl + path)
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .apply { apiKey?.let { header("Authorization", "Bearer $it") } }
                .build()
            client.newCall(req).execute().use { res ->
                val root = json.parseToJsonElement(res.body.string()).jsonObject
                if (root["status"]?.jsonPrimitive?.booleanOrNull != true) {
                    error("Filen API: ${root["message"]?.jsonPrimitive?.contentOrNull} (${root["code"]?.jsonPrimitive?.contentOrNull})")
                }
                root["data"]?.jsonObject ?: JsonObject(emptyMap())
            }
        }
}