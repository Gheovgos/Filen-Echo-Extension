package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.clients.ExtensionClient
import dev.brahmkshatriya.echo.common.clients.HomeFeedClient
import dev.brahmkshatriya.echo.common.clients.LibraryFeedClient
import dev.brahmkshatriya.echo.common.clients.LoginClient
import dev.brahmkshatriya.echo.common.models.Feed
import dev.brahmkshatriya.echo.common.models.Feed.Companion.toFeed
import dev.brahmkshatriya.echo.common.models.Shelf
import dev.brahmkshatriya.echo.common.models.User
import dev.brahmkshatriya.echo.common.settings.Setting
import dev.brahmkshatriya.echo.common.settings.SettingTextInput
import dev.brahmkshatriya.echo.common.settings.Settings
import dev.brahmkshatriya.echo.extension.auth.FilenApiClient
import dev.brahmkshatriya.echo.extension.auth.FilenAuthManager
import dev.brahmkshatriya.echo.extension.auth.FilenSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class FilenExtension : ExtensionClient, LoginClient.CustomInput, HomeFeedClient, LibraryFeedClient {

    private lateinit var settings: Settings
    private val apiClient = FilenApiClient()
    private val authManager = FilenAuthManager(apiClient)

    companion object {
        const val KEY_MUSIC_PATH = "music_path"
        const val DEFAULT_MUSIC_PATH = "/echomusic"     // DEFAULT PATH
    }

    private var currentUser: User? = null
    private var session: FilenSession? = null

    // --- ExtensionClient / SettingsProvider ---

    override suspend fun getSettingItems(): List<Setting> = listOf(
        SettingTextInput(
            "Music Path",
            KEY_MUSIC_PATH,
            "Path of folder to use, ex. /Music/Echo",
            DEFAULT_MUSIC_PATH
        )
    )

    override fun setSettings(settings: Settings) {
        this.settings = settings
    }

    private fun musicPath(): String {
        val raw = settings.getString(KEY_MUSIC_PATH)?.ifBlank { null } ?: DEFAULT_MUSIC_PATH
        return "/" + raw.trim().split('/').filter { it.isNotBlank() }.joinToString("/")
    }

    // --- LoginClient.CustomInput ---

    override val forms = listOf(
        LoginClient.Form(
            key = "filen_login",
            label = "Filen",
            icon = LoginClient.InputField.Type.Email,
            inputFields = listOf(
                LoginClient.InputField(LoginClient.InputField.Type.Email, "email", "Email", true),
                LoginClient.InputField(LoginClient.InputField.Type.Password, "password", "Password", true),
                LoginClient.InputField(LoginClient.InputField.Type.Misc, "twofa", "2FA (not required)", false)
            )
        )
    )

    override suspend fun onLogin(key: String, data: Map<String, String?>): List<User> {
        val email = data["email"]?.trim().orEmpty()
        val password = data["password"].orEmpty()
        val twoFa = data["twofa"]?.trim()?.ifBlank { null }
        require(email.isNotEmpty() && password.isNotEmpty()) { "Email & password required" }

        // PBKDF2
        val s = withContext(Dispatchers.Default) { authManager.login(email, password, twoFa) }

        return listOf(
            User(
                id = email,
                name = email,
                subtitle = "Filen",
                extras = mapOf(
                    "apiKey" to s.apiKey,
                    "masterKeys" to s.masterKeys.joinToString("|")
                )
            )
        )
    }

    override fun setLoginUser(user: User?) {
        currentUser = user
        session = user?.extras?.let { ex ->
            val apiKey = ex["apiKey"]
            val keys = ex["masterKeys"]
            if (apiKey != null && keys != null) FilenSession(apiKey, keys.split("|")) else null
        }
    }

    override suspend fun getCurrentUser(): User? = currentUser?.copy(extras = emptyMap())

    override suspend fun loadHomeFeed(): Feed<Shelf> = emptyList<Shelf>().toFeed()

    override suspend fun loadLibraryFeed(): Feed<Shelf> = emptyList<Shelf>().toFeed()

}