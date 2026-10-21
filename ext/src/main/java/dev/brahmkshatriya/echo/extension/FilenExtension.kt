package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.clients.ExtensionClient
import dev.brahmkshatriya.echo.common.clients.HomeFeedClient
import dev.brahmkshatriya.echo.common.clients.LibraryFeedClient
import dev.brahmkshatriya.echo.common.clients.LoginClient
import dev.brahmkshatriya.echo.common.clients.TrackClient
import dev.brahmkshatriya.echo.common.helpers.PagedData
import dev.brahmkshatriya.echo.common.models.Feed
import dev.brahmkshatriya.echo.common.models.Feed.Companion.toFeed
import dev.brahmkshatriya.echo.common.models.Shelf
import dev.brahmkshatriya.echo.common.models.Streamable
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.common.models.User
import dev.brahmkshatriya.echo.common.settings.Setting
import dev.brahmkshatriya.echo.common.settings.SettingTextInput
import dev.brahmkshatriya.echo.common.settings.Settings
import dev.brahmkshatriya.echo.extension.auth.FilenApiClient
import dev.brahmkshatriya.echo.extension.auth.FilenAuthManager
import dev.brahmkshatriya.echo.extension.auth.FilenChunkInputStream
import dev.brahmkshatriya.echo.extension.auth.FilenDriveClient
import dev.brahmkshatriya.echo.extension.auth.FilenSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class FilenExtension : ExtensionClient, LoginClient.CustomInput, HomeFeedClient, LibraryFeedClient,
    TrackClient {

    private lateinit var settings: Settings

    private val TAG = "FilenExt"
    private val apiClient = FilenApiClient()
    private val authManager = FilenAuthManager(apiClient)

    private var drive: FilenDriveClient? = null

    companion object {
        const val KEY_MUSIC_PATH = "music_path"
        const val DEFAULT_MUSIC_PATH = "/echomusic"     // DEFAULT PATH
    }

    private var currentUser: User? = null
    private var session: FilenSession? = null

    // --- ExtensionClient / SettingsProvider ---

    override suspend fun getSettingItems(): List<Setting> {
        flog("getSettingItems")
        return listOf(SettingTextInput(
                "Music Path",
                KEY_MUSIC_PATH,
                "Path of folder to use, ex. /Music/Echo",
                DEFAULT_MUSIC_PATH
            )
        )
    }

    override suspend fun onInitialize() { flog("onInitialize") }

    override suspend fun onExtensionSelected() { flog("onExtensionSelected") }

    override fun setSettings(settings: Settings) {
        this.settings = settings
        flog("setSettings, settings: $settings")
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
        flog("setLoginUser: user=${user != null}, session=${session != null}")
        drive = session?.let { FilenDriveClient(apiClient, it) }
    }

    override suspend fun getCurrentUser(): User? {
        flog("getCurrentUser")
        return currentUser?.copy(extras = emptyMap())
    }

    override suspend fun loadLibraryFeed(): Feed<Shelf> {
        flog("loadLibraryFeed: ENTRATA")
        return emptyList<Shelf>().toFeed()
    }

    override suspend fun loadHomeFeed(): Feed<Shelf> {
        flog("Load home feed")
        val drive = drive ?: error("Do Login first")
        return PagedData.Single<Shelf> {
            val root = drive.resolvePath(musicPath())
            val tracks = flatten(drive.scan(root))
            if (tracks.isEmpty()) error("No tracks found ${musicPath()} (uuid: $root)")
            tracks.map { Shelf.Item(it) }
        }.toFeed(Feed.Buttons(showPlayAndShuffle = true))
    }

    private fun flatten(node: FilenDriveClient.Node, parent: String? = null): List<Track> {
        val label = listOfNotNull(parent, node.folder?.name).joinToString(" / ").ifEmpty { null }
        return node.tracks.map { it.toTrack(label) } + node.children.flatMap { flatten(it, label) }
    }

    private fun FilenDriveClient.File.extras() = mapOf(
        "uuid" to uuid, "name" to name, "region" to region, "bucket" to bucket,
        "key" to key, "chunks" to chunks.toString(), "size" to size.toString()
    )

    private fun FilenDriveClient.File.toTrack(folder: String?) = Track(
        id = uuid,
        title = name.substringBeforeLast('.'),
        subtitle = folder,
        streamables = listOf(Streamable.server(uuid, 0, name, extras())),
        extras = extras()
    )

    // --- TrackClient ---

    override suspend fun loadTrack(track: Track, isDownload: Boolean): Track = track

    override suspend fun loadFeed(track: Track): Feed<Shelf>? = null

    override suspend fun loadStreamableMedia(streamable: Streamable, isDownload: Boolean): Streamable.Media {
        val s = session ?: error("Do Login first")
        val e = streamable.extras
        val file = FilenDriveClient.File(
            uuid = e.getValue("uuid"), name = e.getValue("name"), mime = "",
            size = e.getValue("size").toLong(), key = e.getValue("key"),
            region = e.getValue("region"), bucket = e.getValue("bucket"),
            chunks = e.getValue("chunks").toInt()
        )
        val provider = Streamable.InputProvider { position, length ->
            val stream = FilenChunkInputStream(apiClient.client, s.apiKey, file, position)
            stream to (if (file.size > 0) file.size - position else -1L)
        }
        return Streamable.Media.Server(
            listOf(Streamable.Source.Raw(streamProvider = provider, id = file.uuid)),
            merged = false
        )
    }

}