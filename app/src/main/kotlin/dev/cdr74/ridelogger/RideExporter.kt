package dev.cdr74.ridelogger

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File

/**
 * Gets closed ride files off the device (design.md §2): share sheet via FileProvider
 * or a copy into Downloads/RideLogger via MediaStore. Only ever reads ride files.
 */
object RideExporter {

    const val MIME = "application/vnd.sqlite3"

    fun share(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND)
            .setType(MIME)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(intent, "Export ${file.name}"))
    }

    /** Returns the Downloads URI, or null on failure. Keeps the ride file name unchanged. */
    fun exportToDownloads(context: Context, file: File): Uri? {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, file.name)
            put(MediaStore.Downloads.MIME_TYPE, MIME)
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/RideLogger")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        return try {
            resolver.openOutputStream(uri)?.use { out ->
                file.inputStream().use { it.copyTo(out) }
            } ?: return null
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            null
        }
    }

    /** Closed ride files: *.db in the rides dir, excluding the active ride and WAL sidecars. */
    fun closedRides(context: Context, activeFileName: String?): List<File> =
        RideStore.ridesDir(context).listFiles()
            .orEmpty()
            .filter { it.name.endsWith(".db") && it.name != activeFileName }
            .sortedByDescending { it.name }

    fun delete(file: File) {
        File(file.parentFile, file.name + "-wal").delete()
        File(file.parentFile, file.name + "-shm").delete()
        File(file.parentFile, file.name + ".analysis.json").delete() // cached post-ride analysis
        file.delete()
    }
}
