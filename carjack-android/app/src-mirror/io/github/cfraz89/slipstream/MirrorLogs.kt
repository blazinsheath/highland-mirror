package io.github.cfraz89.slipstream

import android.app.Activity
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.widget.Toast
import java.io.File
import java.io.FileNotFoundException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object MirrorLogs {
    fun share(activity: Activity) {
        Thread({
            try {
                val dir = File(activity.cacheDir, "logs").apply { mkdirs() }
                val file = File(dir, "mirror-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date()) + ".zip")
                ZipOutputStream(file.outputStream()).use { zip ->
                    zip.putNextEntry(ZipEntry("info.txt"))
                    @Suppress("DEPRECATION")
                    val version = activity.packageManager.getPackageInfo(activity.packageName, 0).versionName
                    zip.write(("Highland Mirror $version\n" +
                        "Android ${android.os.Build.VERSION.RELEASE}; ${android.os.Build.MODEL}\n" +
                        "Status: ${Variant.setting(activity, "status", "")}\n" +
                        "Last session: ${Variant.setting(activity, "last_connection_status", "")}\n" +
                        "Audio: ${AudioOutputSettings.read(activity)}\n" +
                        "Projection: ${MirrorProjection.active}; touch: ${MirrorControlService.available()}; video started: ${Variant.prefs(activity).getBoolean("video_started", false)}\n" +
                        "No saved video, audio or screen-content inspection.\n").toByteArray())
                    zip.closeEntry()
                    activity.filesDir.listFiles { _, name -> name == Variant.LOG_FILE || name.startsWith(Variant.LOG_FILE + ".") }
                        ?.forEach { log ->
                            zip.putNextEntry(ZipEntry(log.name)); log.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
                        }
                }
                // Bound private cached bundles, without deleting a file being shared now.
                dir.listFiles()?.filter { it != file && it.name.endsWith(".zip") }
                    ?.sortedByDescending { it.lastModified() }?.drop(2)?.forEach { it.delete() }
                val uri = Uri.Builder().scheme("content").authority(Variant.APP_ID + ".logs").appendPath(file.name).build()
                activity.runOnUiThread {
                    activity.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                        type = "application/zip"; putExtra(Intent.EXTRA_STREAM, uri)
                        clipData = android.content.ClipData.newRawUri("Diagnostic log", uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }, "Share Highland Mirror log"))
                }
            } catch (e: Exception) {
                activity.runOnUiThread { Toast.makeText(activity, "Could not share log: ${e.message}", Toast.LENGTH_LONG).show() }
            }
        }, "mirror-log-export").start()
    }
}

class MirrorLogsProvider : ContentProvider() {
    override fun onCreate() = true
    private fun file(uri: Uri): File {
        val name = uri.lastPathSegment ?: throw FileNotFoundException()
        if (uri.pathSegments.size != 1 || !Regex("mirror-\\d{8}-\\d{6}\\.zip").matches(name)) throw FileNotFoundException()
        return File(File(context!!.cacheDir, "logs"), name).takeIf { it.isFile } ?: throw FileNotFoundException()
    }
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("Read only")
        return ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.MODE_READ_ONLY)
    }
    override fun getType(uri: Uri) = "application/zip"
    override fun query(uri: Uri, projection: Array<String>?, selection: String?, args: Array<String>?, sort: String?): Cursor =
        MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).apply {
            val source = file(uri); addRow(arrayOf<Any>(source.name, source.length()))
        }
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, args: Array<String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<String>?) = 0
}
