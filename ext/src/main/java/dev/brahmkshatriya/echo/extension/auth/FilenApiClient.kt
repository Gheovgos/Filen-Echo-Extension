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
    private val baseUrl: String = "https://gateway.filen.io",
    val client: OkHttpClient = OkHttpClient()
) {
    private val json = Json { ignoreUnknownKeys = true }

    private fun parse(text: String): JsonObject {
        val root = json.parseToJsonElement(text).jsonObject
        if (root["status"]?.jsonPrimitive?.booleanOrNull != true) {
            error("Filen API: ${root["message"]?.jsonPrimitive?.contentOrNull} (${root["code"]?.jsonPrimitive?.contentOrNull})")
        }
        return root["data"]?.jsonObject ?: JsonObject(emptyMap())
    }

    suspend fun post(path: String, body: JsonObject, apiKey: String? = null): JsonObject =
        withContext(Dispatchers.IO) {
            val req = Request.Builder()
                .url(baseUrl + path)
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .apply { apiKey?.let { header("Authorization", "Bearer $it") } }
                .build()
            client.newCall(req).execute().use { parse(it.body.string()) }
        }

    suspend fun get(path: String, apiKey: String): JsonObject =
        withContext(Dispatchers.IO) {
            val req = Request.Builder()
                .url(baseUrl + path)
                .get()
                .header("Authorization", "Bearer $apiKey")
                .build()
            client.newCall(req).execute().use { parse(it.body.string()) }
        }
}