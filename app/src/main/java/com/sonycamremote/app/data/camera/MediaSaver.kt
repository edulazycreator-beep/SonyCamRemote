package com.sonycamremote.app.data.camera

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.sonycamremote.engine.model.MediaItem
import com.sonycamremote.engine.model.MediaKind
import com.sonycamremote.engine.protocol.CameraProtocol
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** Saves camera files into the shared gallery (Pictures/SonyCamRemote, Movies/SonyCamRemote). */
@Singleton
class MediaSaver @Inject constructor(@ApplicationContext private val context: Context) {

    suspend fun save(protocol: CameraProtocol, item: MediaItem, onProgress: (Long, Long?) -> Unit): Uri =
        withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            val isMovie = item.kind == MediaKind.MOVIE
            val collection = if (isMovie) {
                if (Build.VERSION.SDK_INT >= 29) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            } else {
                if (Build.VERSION.SDK_INT >= 29) MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            }
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, item.fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeFor(item.fileName, isMovie))
                if (Build.VERSION.SDK_INT >= 29) {
                    val dir = if (isMovie) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "$dir/SonyCamRemote")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
            }
            val uri = resolver.insert(collection, values) ?: error("Could not create gallery entry")
            try {
                resolver.openOutputStream(uri)!!.use { out -> protocol.download(item, out, onProgress) }
                if (Build.VERSION.SDK_INT >= 29) {
                    resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                }
                uri
            } catch (t: Throwable) {
                resolver.delete(uri, null, null)
                throw t
            }
        }

    private fun mimeFor(name: String, movie: Boolean): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "jpg", "jpeg" -> "image/jpeg"
            "arw" -> "image/x-sony-arw"
            "heif", "hif", "heic" -> "image/heif"
            "mp4" -> "video/mp4"
            "mts" -> "video/mp2t"
            else -> if (movie) "video/mp4" else "image/jpeg"
        }
    }
}
