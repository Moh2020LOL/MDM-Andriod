package dev.mdm.android

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File

/**
 * Where finished files live and how the person opens them.
 *  - Android 10+: downloads run in the app's own folder (no permission), then the finished file is
 *    copied into the public Download/MDM folder through MediaStore (no permission either) and the
 *    private copy is deleted.
 *  - Android 8 and 9: with the storage permission, downloads go straight to Download/MDM.
 */
object Files {
    fun mimeOf(name: String): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
            ?: "application/octet-stream"

    /** Android 8-9 with the storage permission: the public folder itself. Otherwise null. */
    fun legacyPublicDir(context: Context): File? {
        if (Build.VERSION.SDK_INT >= 29) return null
        if (context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            return null
        }
        @Suppress("DEPRECATION")
        return File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "MDM")
            .also { it.mkdirs() }
    }

    /**
     * Copies a finished [file] into the public Download/MDM folder (Android 10+). Returns the
     * content address, or null when it could not be done (the file then stays where it is).
     */
    fun exportToPublicDownloads(context: Context, file: File): Uri? {
        if (Build.VERSION.SDK_INT < 29 || !file.exists()) return null
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, file.name)
            put(MediaStore.Downloads.MIME_TYPE, mimeOf(file.name))
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/MDM")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        return try {
            val out = resolver.openOutputStream(uri) ?: error("no output stream")
            out.use { o -> file.inputStream().use { it.copyTo(o) } }
            val done = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)
            uri
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            null
        }
    }

    fun deletePublic(context: Context, uri: String) {
        runCatching { context.contentResolver.delete(Uri.parse(uri), null, null) }
    }

    /** The address a viewer can read: the public one, or the app folder's file through FileProvider. */
    fun viewUri(context: Context, item: Item): Uri? {
        item.uri?.let { return Uri.parse(it) }
        val path = item.file ?: return null
        val f = File(path)
        if (!f.exists()) return null
        return FileProvider.getUriForFile(context, "${context.packageName}.files", f)
    }

    fun open(context: Context, item: Item): Boolean {
        val uri = viewUri(context, item) ?: return false
        val name = item.file?.substringAfterLast('/') ?: item.title
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, mimeOf(name))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(intent) }.isSuccess
    }

    fun share(context: Context, item: Item): Boolean {
        val uri = viewUri(context, item) ?: return false
        val name = item.file?.substringAfterLast('/') ?: item.title
        val send = Intent(Intent.ACTION_SEND)
            .setType(mimeOf(name))
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val chooser = Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(chooser) }.isSuccess
    }
}
