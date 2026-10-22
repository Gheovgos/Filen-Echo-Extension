package dev.brahmkshatriya.echo.extension

import dev.brahmkshatriya.echo.common.clients.AlbumClient
import dev.brahmkshatriya.echo.common.clients.ArtistClient
import dev.brahmkshatriya.echo.common.clients.ExtensionClient
import dev.brahmkshatriya.echo.common.clients.HomeFeedClient
import dev.brahmkshatriya.echo.common.clients.LibraryFeedClient
import dev.brahmkshatriya.echo.common.clients.LoginClient
import dev.brahmkshatriya.echo.common.clients.PlaylistClient
import dev.brahmkshatriya.echo.common.clients.SearchFeedClient
import dev.brahmkshatriya.echo.common.clients.TrackClient
import dev.brahmkshatriya.echo.common.helpers.PagedData
import dev.brahmkshatriya.echo.common.models.Album
import dev.brahmkshatriya.echo.common.models.Artist
import dev.brahmkshatriya.echo.common.models.Date
import dev.brahmkshatriya.echo.common.models.Feed
import dev.brahmkshatriya.echo.common.models.Feed.Companion.toFeed
import dev.brahmkshatriya.echo.common.models.ImageHolder
import dev.brahmkshatriya.echo.common.models.Playlist
import dev.brahmkshatriya.echo.common.models.Shelf
import dev.brahmkshatriya.echo.common.models.Streamable
import dev.brahmkshatriya.echo.common.models.Track
import dev.brahmkshatriya.echo.common.models.User
import dev.brahmkshatriya.echo.common.settings.Setting
import dev.brahmkshatriya.echo.common.settings.SettingSwitch
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
    TrackClient, SearchFeedClient, ArtistClient, AlbumClient, PlaylistClient {

    private lateinit var settings: Settings

    private val TAG = "FilenExt"
    private val apiClient = FilenApiClient()
    private val authManager = FilenAuthManager(apiClient)

    private var drive: FilenDriveClient? = null

    private var tagService: FilenTagService? = null

    companion object {
        const val KEY_MUSIC_PATH = "music_path"
        const val KEY_RECURSIVE = "recursive"
        const val KEY_PLAYLIST_FILES = "playlist_files"
        const val KEY_PLAYLIST_FOLDERS = "playlist_folders"
        const val DEFAULT_MUSIC_PATH = "/echomusic"     // DEFAULT
    }

    private var currentUser: User? = null
    private var session: FilenSession? = null

    // --- ExtensionClient / SettingsProvider ---

    override suspend fun getSettingItems(): List<Setting> = listOf(
        SettingTextInput(
            "Music folders", KEY_MUSIC_PATH,
            "Folders to scan, separated by ; (e.g. /Music;/Downloads/Songs)", DEFAULT_MUSIC_PATH
        ),
        SettingSwitch("Include subfolders", KEY_RECURSIVE, "Search music in subfolders too", true),
        SettingTextInput(
            "Playlist files", KEY_PLAYLIST_FILES,
            "Full paths of .m3u/.m3u8 files, separated by ;", null
        ),
        SettingTextInput(
            "Playlist folders", KEY_PLAYLIST_FOLDERS,
            "Folders whose .m3u/.m3u8 files are all imported, separated by ;", null
        )
    )

    private fun pathList(raw: String?): List<String> =
        raw.orEmpty().split(';', '\n').map { it.trim() }.filter { it.isNotEmpty() }
            .map { "/" + it.replace('\\', '/').split('/').filter(String::isNotBlank).joinToString("/") }
            .distinct()

    override suspend fun onInitialize() { flog("onInitialize") }

    override suspend fun onExtensionSelected() { flog("onExtensionSelected") }

    override fun setSettings(settings: Settings) {
        this.settings = settings
        flog("setSettings, settings: $settings")
    }

    private fun musicPaths(): List<String> =
        pathList(settings.getString(KEY_MUSIC_PATH)).ifEmpty { listOf(DEFAULT_MUSIC_PATH) }

    private fun recursiveEnabled(): Boolean = settings.getBoolean(KEY_RECURSIVE) ?: true

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

    override suspend fun loadLibraryFeed(): Feed<Shelf> =
        PagedData.Single<Shelf> { playlistFiles().map { Shelf.Item(it.toPlaylist()) } }.toFeed()

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
            extras = extras() + ("folder" to (folder ?: ""))
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
        val roots = musicPaths()
        val recursive = recursiveEnabled()
        val key = roots.joinToString(";") + "|" + recursive
        tracksCache?.let { (k, t, list) ->
            if (!force && k == key && System.currentTimeMillis() - t < cacheTtlMs) return list
        }
        val drive = drive ?: error("Do Login first")
        val tags = tagService ?: error("Do Login first")

        val errors = mutableListOf<String>()
        val found = mutableListOf<Pair<FilenDriveClient.File, String?>>()
        for (path in roots) {
            try {
                val root = drive.resolvePath(path)
                found += collect(drive.scan(root, maxDepth = if (recursive) 8 else 0))
            } catch (e: Exception) {
                flog("folder $path", e)
                errors += "$path: ${e.message}"
            }
        }
        val files = found.distinctBy { it.first.uuid }
            .sortedWith(
                compareByDescending<Pair<FilenDriveClient.File, String?>> { it.first.uploadedAt() }
                    .thenBy { it.first.name.lowercase() }
            )
        if (files.isEmpty()) {
            error("No tracks found in ${roots.joinToString()}" + if (errors.isEmpty()) "" else " (${errors.joinToString("; ")})")
        }

        val sem = Semaphore(6)
        val metas: Map<String, FilenTagService.Meta?> = coroutineScope {
            files.map { (f, _) ->
                async(Dispatchers.IO) {
                    sem.withPermit {
                        f.uuid to try { tags.meta(f) } catch (e: Exception) { flog("tag ${f.name}", e); null }
                    }
                }
            }.awaitAll().toMap()
        }
        val list = files.map { (f, label) -> f.toTrack(label, metas[f.uuid]) }
        tracksCache = Triple(key, System.currentTimeMillis(), list)
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

    /* ---------- ARTIST & ALBUM CLIENTS ----------*/

    private val playlistExt = setOf("m3u", "m3u8")

    private fun FilenDriveClient.File.toPlaylist() = Playlist(
        id = uuid, title = name.substringBeforeLast('.'), isEditable = false, extras = extras()
    )

    private fun fileFromExtras(e: Map<String, String>) = FilenDriveClient.File(
        uuid = e.getValue("uuid"), name = e.getValue("name"), mime = "",
        size = e.getValue("size").toLong(), key = e.getValue("key"),
        region = e.getValue("region"), bucket = e.getValue("bucket"),
        chunks = e.getValue("chunks").toInt()
    )

    private suspend fun playlistFiles(): List<FilenDriveClient.File> {
        val drive = drive ?: error("Do Login first")
        val result = LinkedHashMap<String, FilenDriveClient.File>()

        for (path in pathList(settings.getString(KEY_PLAYLIST_FILES))) {
            try {
                val parent = path.substringBeforeLast('/', "").ifEmpty { "/" }
                val fileName = path.substringAfterLast('/')
                val f = drive.list(drive.resolvePath(parent)).files.firstOrNull { it.name.equals(fileName, true) }
                if (f != null) result[f.uuid] = f else flog("playlist not found: $path")
            } catch (e: Exception) { flog("playlist $path", e) }
        }
        for (path in pathList(settings.getString(KEY_PLAYLIST_FOLDERS))) {
            try {
                drive.list(drive.resolvePath(path)).files
                    .filter { it.name.substringAfterLast('.', "").lowercase() in playlistExt }
                    .forEach { result[it.uuid] = it }
            } catch (e: Exception) { flog("playlist $path", e) }
        }
        return result.values.sortedBy { it.name.lowercase() }
    }

    private fun stem(name: String) = normalize(name.substringBeforeLast('.'))

    private fun trackSegments(t: Track): List<String> =
        t.extras["folder"].orEmpty().split(" / ").filter { it.isNotBlank() }.map(::normalize) +
                stem(t.extras["name"].orEmpty())

    private fun resolveEntry(entry: String, index: Map<String, List<Track>>): Track? {
        val parts = entry.replace('\\', '/').split('/')
            .filter { it.isNotBlank() && it != "." && it != ".." && !Regex("[A-Za-z]:").matches(it) }
        if (parts.isEmpty()) return null
        val segs = parts.dropLast(1).map(::normalize) + stem(parts.last())
        val candidates = index[stem(parts.last())] ?: return null
        return candidates.maxByOrNull { t ->
            val ts = trackSegments(t)
            var n = 0
            while (n < minOf(segs.size, ts.size) && segs[segs.size - 1 - n] == ts[ts.size - 1 - n]) n++
            n
        }
    }

    private suspend fun playlistTracks(playlist: Playlist): List<Track> {
        val s = session ?: error("Do Login first")
        val file = fileFromExtras(playlist.extras)
        val bytes = withContext(Dispatchers.IO) {
            FilenChunkInputStream(apiClient.client, s.apiKey, file, 0).use { it.readBytes() }
        }
        var text = bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF")
        if ('\uFFFD' in text) text = bytes.toString(Charsets.ISO_8859_1) // .m3u

        val entries = text.lineSequence().map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("http", ignoreCase = true) }
            .toList()
        val index = allTracks().groupBy { stem(it.extras["name"].orEmpty()) }
        return entries.mapNotNull { resolveEntry(it, index) }
    }

// --- Library: le playlist ---



// --- PlaylistClient ---

    override suspend fun loadPlaylist(playlist: Playlist): Playlist {
        val tracks = playlistTracks(playlist)
        val cover = firstCover(tracks)
        return playlist.copy(trackCount = tracks.size.toLong(), cover = cover, background = cover)
    }

    override suspend fun loadTracks(playlist: Playlist): Feed<Track> =
        PagedData.Single<Track> { playlistTracks(playlist) }
            .toFeed(Feed.Buttons(showPlayAndShuffle = true))

    override suspend fun loadFeed(playlist: Playlist): Feed<Shelf>? = null

}