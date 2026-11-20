package com.satnova.alpha

import android.app.Activity
import android.app.AlertDialog
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException

/**
 * Only used when the stock Google TV emulator has no system DocumentsUI.
 * Handles an explicit user request from Secreto Wallpaper Studio's
 * "Choose image from device" action. Does not modify any Secreto method.
 */
class AltaPlayIslamicImagePickerActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AlertDialog.Builder(this)
            .setTitle("AltaPlay · Islamic wallpaper")
            .setMessage("Import the original Islamic mosque wallpaper saved in AltaPlay into Secreto Wallpaper Studio?")
            .setPositiveButton("Use Islamic wallpaper") { _, _ ->
                val uri = Uri.parse("content://com.altaplay.launcher.islamic/background")
                val result = Intent().setDataAndType(uri, "image/webp")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    .addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                setResult(RESULT_OK, result)
                finish()
            }
            .setNegativeButton("Cancel") { _, _ ->
                setResult(RESULT_CANCELED)
                finish()
            }
            .setOnCancelListener {
                setResult(RESULT_CANCELED)
                finish()
            }
            .show()
    }
}

/** Read-only content URI for the ORIGINAL user-approved, bundled wallpaper. */
class AltaPlayIslamicWallpaperProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String = "image/webp"

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (uri.path != "/background" || mode != "r")
            throw FileNotFoundException("Only the bundled Islamic wallpaper is exposed")
        val c = context ?: throw FileNotFoundException("Context unavailable")
        val cached = File(c.cacheDir, "original-altaplay-islamic.webp")
        if (!cached.isFile || cached.length() < 1000) {
            c.assets.open("satnova_wallpaper.webp").use { input ->
                cached.outputStream().use { output -> input.copyTo(output) }
            }
        }
        return ParcelFileDescriptor.open(cached, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                       selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val cols = projection?.filter {
            it == OpenableColumns.DISPLAY_NAME || it == OpenableColumns.SIZE
        }?.toTypedArray() ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val cursor = MatrixCursor(cols)
        cursor.addRow(cols.map {
            if (it == OpenableColumns.DISPLAY_NAME) "AltaPlay-Islamic.webp" else 4012L
        }.toTypedArray())
        return cursor
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(uri: Uri, values: ContentValues?, selection: String?,
                        selectionArgs: Array<out String>?): Int = 0
    override fun delete(uri: Uri, selection: String?,
                        selectionArgs: Array<out String>?): Int = 0
}
