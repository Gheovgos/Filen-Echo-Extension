package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.clients.AlbumClient
import dev.brahmkshatriya.echo.common.clients.ArtistClient
import dev.brahmkshatriya.echo.common.clients.ExtensionClient
import dev.brahmkshatriya.echo.common.clients.HomeFeedClient
import dev.brahmkshatriya.echo.common.clients.LibraryFeedClient
import dev.brahmkshatriya.echo.common.clients.LoginClient
import dev.brahmkshatriya.echo.common.clients.SearchFeedClient
import dev.brahmkshatriya.echo.common.clients.TrackClient
import dev.brahmkshatriya.echo.common.helpers.PagedData
import dev.brahmkshatriya.echo.common.models.Album
import dev.brahmkshatriya.echo.common.models.Artist
import dev.brahmkshatriya.echo.common.models.Date
import dev.brahmkshatriya.echo.common.models.Feed
import dev.brahmkshatriya.echo.common.models.Feed.Companion.toFeed
import dev.brahmkshatriya.echo.common.models.ImageHolder
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
import dev.brahmkshatriya.echo.extension.auth.FilenTagService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

class FilenExtension : ExtensionClient, LoginClient.CustomInput, HomeFeedClient, LibraryFeedClient,
    TrackClient, SearchFeedClient, ArtistClient, AlbumClient {

    private lateinit var settings: Settings

    private val TAG = "FilenExt"
    private val apiClient = FilenApiClient()
    private val authManager = FilenAuthManager(apiClient)

    private var drive: FilenDriveClient? = null

    private var tagService: FilenTagService? = null

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
        tagService = session?.let { FilenTagService(apiClient.client, it.apiKey) }
        tracksCache = null
    }

    override suspend fun getCurrentUser(): User? {
        flog("getCurrentUser")
        return currentUser?.copy(extras = emptyMap())
    }

    override suspend fun loadLibraryFeed(): Feed<Shelf> {
        flog("loadLibraryFeed: ENTRATA")
        return emptyList<Shelf>().toFeed()
    }

    private fun FilenDriveClient.File.uploadedAt() = if (creation > 0) creation else lastModified

    override suspend fun loadHomeFeed(): Feed<Shelf> {
        flog("Home Feed")
        return PagedData.Single<Shelf> {
            allTracks(force = true).map { Shelf.Item(it) }
        }.toFeed(Feed.Buttons(showPlayAndShuffle = true))
    }

    private fun collect(node: FilenDriveClient.Node, parent: String? = null): List<Pair<FilenDriveClient.File, String?>> {
        val label = listOfNotNull(parent, node.folder?.name).joinToString(" / ").ifEmpty { null }
        return node.tracks.map { it to label } + node.children.flatMap { collect(it, label) }
    }

    private fun normalize(s: String): String =
        java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")

    private fun score(t: Track, tokens: List<String>): Int? {
        val title = normalize(t.title)
        val artists = normalize(t.artists.joinToString(" ") { it.name })
        val album = normalize(t.album?.title.orEmpty())
        val file = normalize(t.extras["name"].orEmpty())
        var total = 0
        for (tok in tokens) {
            total += when {
                title.startsWith(tok) -> 5
                title.contains(" $tok") -> 4
                title.contains(tok) -> 3
                artists.contains(tok) -> 2
                album.contains(tok) || file.contains(tok) -> 1
                else -> return null
            }
        }
        return total
    }

    override suspend fun loadSearchFeed(query: String): Feed<Shelf> {
        flog("Search: $query")
        val tokens = normalize(query).split(' ').filter { it.isNotBlank() }
        return PagedData.Single<Shelf> {
            if (tokens.isEmpty()) {
                emptyList()
            } else {
                allTracks().mapNotNull { t -> score(t, tokens)?.let { t to it } }
                    .sortedByDescending { it.second }
                    .map { Shelf.Item(it.first) }
            }
        }.toFeed(Feed.Buttons(showPlayAndShuffle = true))
    }

    private fun FilenDriveClient.File.toTrack(folder: String?, meta: FilenTagService.Meta?): Track {
        val artistName = meta?.artist?.let(::firstArtist)
        val artist = artistName?.let { Artist(id = artistKey(it), name = it) }
        return Track(
            id = uuid,
            title = meta?.title ?: name.substringBeforeLast('.'),
            artists = listOfNotNull(artist),
            cover = meta?.coverPath?.let { ImageHolder.ResourceUriImageHolder("file://$it", false) },
            album = meta?.album?.takeIf { it.isNotBlank() }?.let {
                Album(id = albumKey(artistName, it), title = it, artists = listOfNotNull(artist))
            },
            duration = meta?.durationMs,
            streamables = listOf(Streamable.server(uuid, 0, name, extras())),
            releaseDate = uploadedAt().takeIf { it > 0 }?.let { Date(epochTimeMs = it) },
            extras = extras()
        )
    }

    private fun FilenDriveClient.File.extras() = mapOf(
        "uuid" to uuid, "name" to name, "region" to region, "bucket" to bucket,
        "key" to key, "chunks" to chunks.toString(), "size" to size.toString()
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

    /* ---------- SEARCH FIND CLIENT ----------*/

    private var tracksCache: Triple<String, Long, List<Track>>? = null
    private val tracksMutex = Mutex()
    private val cacheTtlMs = 5 * 60 * 1000L

    private suspend fun allTracks(force: Boolean = false): List<Track> = tracksMutex.withLock {
        val path = musicPath()
        tracksCache?.let { (p, t, list) ->
            if (!force && p == path && System.currentTimeMillis() - t < cacheTtlMs) return list
        }
        val drive = drive ?: error("Do Login first")
        val tags = tagService ?: error("Do Login first")

        val root = drive.resolvePath(path)
        val files = collect(drive.scan(root))
            .sortedWith(
                compareByDescending<Pair<FilenDriveClient.File, String?>> { it.first.uploadedAt() }
                    .thenBy { it.first.name.lowercase() }
            )
        if (files.isEmpty()) error("No tracks found in $path (uuid: $root)")

        val sem = Semaphore(6)
        val metas = coroutineScope {
            files.map { (f, _) ->
                async(Dispatchers.IO) {
                    sem.withPermit {
                        f.uuid to try { tags.meta(f) } catch (e: Exception) { flog("tag ${f.name}", e); null }
                    }
                }
            }.awaitAll().toMap()
        }
        val list = files.map { (f, label) -> f.toTrack(label, metas[f.uuid]) }
        tracksCache = Triple(path, System.currentTimeMillis(), list)
        list
    }

    /* ---------- ARTIST & ALBUM CLIENTS ----------*/

    private val multiArtistRegex = Regex("""\s*;\s*|\s+\(?(?:feat|ft|featuring)\.?\s+""", RegexOption.IGNORE_CASE)

    private fun firstArtist(raw: String): String? =
        raw.split(multiArtistRegex).firstOrNull()?.trim()?.ifEmpty { null }

    private fun artistKey(name: String) = normalize(name)

    private fun albumKey(artist: String?, album: String) =
        "${artist?.let(::normalize).orEmpty()}||${normalize(album)}"

    private suspend fun tracksOfArtist(artistId: String): List<Track> =
        allTracks().filter { it.artists.firstOrNull()?.id == artistId }

    private suspend fun tracksOfAlbum(albumId: String): List<Track> =
        allTracks().filter { it.album?.id == albumId }

    private fun firstCover(tracks: List<Track>): ImageHolder? =
        tracks.firstNotNullOfOrNull { it.cover }

    // --- ArtistClient ---

    override suspend fun loadArtist(artist: Artist): Artist {
        val cover = firstCover(tracksOfArtist(artist.id))
        return artist.copy(cover = cover, background = cover)
    }

    override suspend fun loadFeed(artist: Artist): Feed<Shelf> =
        PagedData.Single<Shelf> {
            tracksOfArtist(artist.id).map { Shelf.Item(it) }
        }.toFeed(Feed.Buttons(showPlayAndShuffle = true))

    // --- AlbumClient ---

    private suspend fun albumTracks(albumId: String): List<Track> =
        tracksOfAlbum(albumId).sortedBy { it.extras["name"]?.lowercase().orEmpty() }

    override suspend fun loadAlbum(album: Album): Album {
        val tracks = albumTracks(album.id)
        if (tracks.isEmpty()) return album
        val cover = firstCover(tracks)
        return album.copy(
            cover = cover,
            background = cover,
            trackCount = tracks.size.toLong(),
            duration = tracks.mapNotNull { it.duration }.takeIf { it.isNotEmpty() }?.sum(),
            artists = album.artists.ifEmpty { tracks.first().artists }
        )
    }

    override suspend fun loadFeed(album: Album): Feed<Shelf>? = null

    override suspend fun loadTracks(album: Album): Feed<Track> =
        PagedData.Single { albumTracks(album.id) }
            .toFeed(Feed.Buttons(showPlayAndShuffle = true))

}