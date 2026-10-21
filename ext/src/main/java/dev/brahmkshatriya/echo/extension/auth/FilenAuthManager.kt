package dev.brahmkshatriya.echo.extension.auth

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

data class FilenSession(val apiKey: String, val masterKeys: List<String>)

class FilenAuthManager(private val api: FilenApiClient) {

    suspend fun login(email: String, password: String, twoFactor: String? = null): FilenSession {
        val info = api.post("/v3/auth/info", buildJsonObject { put("email", email) })
        val salt = info["salt"]!!.jsonPrimitive.content
        val authVersion = info["authVersion"]!!.jsonPrimitive.int

        val creds = FilenCrypto.deriveCredentials(password, salt)

        val login = api.post("/v3/login", buildJsonObject {
            put("email", email)
            put("password", creds.loginPassword)
            put("twoFactorCode", twoFactor?.ifBlank { null } ?: "XXXXXX")
            put("authVersion", authVersion)
        })
        val apiKey = login["apiKey"]!!.jsonPrimitive.content

        val mk = api.post("/v3/user/masterKeys", buildJsonObject {
            put("masterKeys", FilenCrypto.encryptMetadata(creds.masterKey, creds.masterKey))
        }, apiKey)
        val decrypted = FilenCrypto.decryptMetadata(mk["keys"]!!.jsonPrimitive.content, listOf(creds.masterKey))

        return FilenSession(apiKey, decrypted.split("|"))
    }
}