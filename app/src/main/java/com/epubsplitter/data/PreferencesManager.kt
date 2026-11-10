package com.epubsplitter.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

class PreferencesManager(private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("epub_splitter_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_LAST_PAGE = "last_page"
        private const val KEY_FONT_SIZE = "font_size"
        private const val KEY_THEME = "app_theme"
        private const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
        private const val KEY_BOOK_TITLE = "book_title"
        private const val CHUNKS_FILE_NAME = "saved_chunks.bin"
    }

    var lastPage: Int
        get() = prefs.getInt(KEY_LAST_PAGE, 0)
        set(value) = prefs.edit().putInt(KEY_LAST_PAGE, value).apply()

    var fontSize: Float
        get() = prefs.getFloat(KEY_FONT_SIZE, 16f)
        set(value) = prefs.edit().putFloat(KEY_FONT_SIZE, value).apply()

    var theme: AppTheme
        get() = try {
            AppTheme.valueOf(prefs.getString(KEY_THEME, AppTheme.LIGHT.name) ?: AppTheme.LIGHT.name)
        } catch (e: Exception) {
            AppTheme.LIGHT
        }
        set(value) = prefs.edit().putString(KEY_THEME, value.name).apply()

    var keepScreenOn: Boolean
        get() = prefs.getBoolean(KEY_KEEP_SCREEN_ON, true)
        set(value) = prefs.edit().putBoolean(KEY_KEEP_SCREEN_ON, value).apply()

    var bookTitle: String
        get() = prefs.getString(KEY_BOOK_TITLE, "") ?: ""
        set(value) = prefs.edit().putString(KEY_BOOK_TITLE, value).apply()

    fun hasSavedBook(): Boolean {
        val file = File(context.filesDir, CHUNKS_FILE_NAME)
        return file.exists() && file.length() > 0 && bookTitle.isNotEmpty()
    }

    suspend fun saveBook(title: String, chunks: List<String>) = withContext(Dispatchers.IO) {
        bookTitle = title
        val file = File(context.filesDir, CHUNKS_FILE_NAME)
        FileOutputStream(file).use { fos ->
            DataOutputStream(fos).use { dos ->
                dos.writeInt(chunks.size)
                for (chunk in chunks) {
                    val bytes = chunk.toByteArray(Charsets.UTF_8)
                    dos.writeInt(bytes.size)
                    dos.write(bytes)
                }
            }
        }
    }

    suspend fun loadSavedChunks(): List<String> = withContext(Dispatchers.IO) {
        val file = File(context.filesDir, CHUNKS_FILE_NAME)
        if (!file.exists()) return@withContext emptyList()

        val list = mutableListOf<String>()
        try {
            FileInputStream(file).use { fis ->
                DataInputStream(fis).use { dis ->
                    val count = dis.readInt()
                    for (i in 0 until count) {
                        val len = dis.readInt()
                        val bytes = ByteArray(len)
                        dis.readFully(bytes)
                        list.add(String(bytes, Charsets.UTF_8))
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext emptyList()
        }
        list
    }

    fun clearSavedBook() {
        val file = File(context.filesDir, CHUNKS_FILE_NAME)
        if (file.exists()) file.delete()
        prefs.edit()
            .remove(KEY_BOOK_TITLE)
            .remove(KEY_LAST_PAGE)
            .apply()
    }
}
