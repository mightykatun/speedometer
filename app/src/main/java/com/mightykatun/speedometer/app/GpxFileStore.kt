package com.mightykatun.speedometer.app

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.annotation.RequiresApi

internal object GpxFileStore {
    const val MIME_TYPE = "application/gpx+xml"

    @RequiresApi(Build.VERSION_CODES.Q)
    fun saveToDownloads(context: Context, fileName: String, contents: String): String {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, MIME_TYPE)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = checkNotNull(
            resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        ) { "Unable to create Downloads entry" }
        try {
            write(context, uri, contents)
            val published = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }
            check(resolver.update(uri, published, null, null) == 1) {
                "Unable to publish Downloads entry"
            }
            return displayName(context, uri) ?: fileName
        } catch (failure: Throwable) {
            resolver.delete(uri, null, null)
            throw failure
        }
    }

    private fun write(context: Context, uri: Uri, contents: String) {
        val stream = checkNotNull(context.contentResolver.openOutputStream(uri, "w")) {
            "Unable to open GPX destination"
        }
        stream.bufferedWriter(Charsets.UTF_8).use { writer ->
            writer.write(contents)
        }
    }

    private fun displayName(context: Context, uri: Uri): String? =
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
}
