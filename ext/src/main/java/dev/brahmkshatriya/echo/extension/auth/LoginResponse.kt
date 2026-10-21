package dev.brahmkshatriya.echo.extension.auth

//TODO DELETE
data class LoginResponse(
    val apiKey: String,
    val publicKey: String,
    val encryptedPrivateKey: String,
    val masterKeysEncrypted: String
)