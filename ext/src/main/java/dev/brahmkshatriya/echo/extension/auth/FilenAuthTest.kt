package dev.brahmkshatriya.echo.extension.auth

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class FilenAuthTest {

    // Test offline
    @Test
    fun metadataRoundTrip() {
        val key = "a".repeat(64)
        val enc = FilenCrypto.encryptMetadata("""{"name":"test.mp3"}""", key)
        assertEquals("""{"name":"test.mp3"}""", FilenCrypto.decryptMetadata(enc, listOf("x".repeat(64), key)))
    }

    @Test
    fun login() = runBlocking {
        val email = System.getenv("FILEN_EMAIL") ?: return@runBlocking
        val password = System.getenv("FILEN_PASSWORD") ?: return@runBlocking
        val session = FilenAuthManager(FilenApiClient()).login(email, password, System.getenv("FILEN_2FA"))
        println("Login OK, master keys: ${session.masterKeys.size}")
    }
}