package dev.brahmkshatriya.echo.extension

import kotlinx.serialization.Serializable

@Serializable
data class DriveFile(
    val id: String,
    val title: String,
    val mimeType: String,
    val fileSize: String? = null
)

@Serializable
data class DriveApiResponse(
    val nextPageToken: String? = null,
    val items: List<DriveFile> = emptyList()
)
