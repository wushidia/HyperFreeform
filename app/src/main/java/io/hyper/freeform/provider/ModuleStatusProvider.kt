package io.hyper.freeform.provider

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri

class ModuleStatusProvider : ContentProvider() {
    companion object {
        /** Hooked by Xposed HookMyself to return true when module is active. */
        @JvmStatic
        fun isModuleActive(): Boolean = false
    }

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val c = MatrixCursor(arrayOf("active"))
        c.addRow(arrayOf(if (isModuleActive()) 1 else 0))
        return c
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
