package com.epubsplitter.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.epubsplitter.data.AppTheme
import com.epubsplitter.data.EpubParser
import com.epubsplitter.data.PreferencesManager
import com.epubsplitter.data.ReaderState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = PreferencesManager(application.applicationContext)

    private val _uiState = MutableStateFlow(
        ReaderState(
            fontSizeSp = prefs.fontSize,
            theme = prefs.theme,
            keepScreenOn = prefs.keepScreenOn
        )
    )
    val uiState: StateFlow<ReaderState> = _uiState.asStateFlow()

    init {
        restoreSavedBookIfAvailable()
    }

    private fun restoreSavedBookIfAvailable() {
        if (prefs.hasSavedBook()) {
            viewModelScope.launch {
                _uiState.update { it.copy(isLoading = true, loadingMessage = "Відновлення книги...") }
                val chunks = prefs.loadSavedChunks()
                if (chunks.isNotEmpty()) {
                    val savedPage = prefs.lastPage.coerceIn(0, chunks.size - 1)
                    _uiState.update {
                        it.copy(
                            isBookLoaded = true,
                            bookTitle = prefs.bookTitle,
                            chunks = chunks,
                            currentPage = savedPage,
                            isLoading = false,
                            loadingMessage = ""
                        )
                    }
                } else {
                    _uiState.update { it.copy(isLoading = false) }
                }
            }
        }
    }

    fun openEpubUri(uri: Uri) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isLoading = true,
                    loadingMessage = "Відкриття EPUB файлу...",
                    errorMessage = null
                )
            }

            try {
                val parsed = EpubParser.parseFromUri(
                    context = getApplication(),
                    uri = uri,
                    onProgress = { msg ->
                        _uiState.update { it.copy(loadingMessage = msg) }
                    }
                )

                // Зберігаємо в постійну пам'ять
                prefs.saveBook(parsed.title, parsed.chunks)
                prefs.lastPage = 0

                _uiState.update {
                    it.copy(
                        isBookLoaded = true,
                        bookTitle = parsed.title,
                        chunks = parsed.chunks,
                        currentPage = 0,
                        isLoading = false,
                        loadingMessage = ""
                    )
                }
            } catch (e: Exception) {
                e.printStackTrace()
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        loadingMessage = "",
                        errorMessage = "Помилка відкриття файлу: ${e.localizedMessage ?: e.message}"
                    )
                }
            }
        }
    }

    fun changePage(delta: Int) {
        val total = _uiState.value.chunks.size
        if (total == 0) return
        val newPage = (_uiState.value.currentPage + delta).coerceIn(0, total - 1)
        goToPage(newPage)
    }

    fun goToPage(pageIndex: Int) {
        val total = _uiState.value.chunks.size
        if (total == 0) return
        val validPage = pageIndex.coerceIn(0, total - 1)
        prefs.lastPage = validPage
        _uiState.update { it.copy(currentPage = validPage) }
    }

    fun setFontSize(sizeSp: Float) {
        val clamped = sizeSp.coerceIn(12f, 32f)
        prefs.fontSize = clamped
        _uiState.update { it.copy(fontSizeSp = clamped) }
    }

    fun setTheme(theme: AppTheme) {
        prefs.theme = theme
        _uiState.update { it.copy(theme = theme) }
    }

    fun setKeepScreenOn(keep: Boolean) {
        prefs.keepScreenOn = keep
        _uiState.update { it.copy(keepScreenOn = keep) }
    }

    fun closeBook() {
        prefs.clearSavedBook()
        _uiState.update {
            it.copy(
                isBookLoaded = false,
                bookTitle = "",
                chunks = emptyList(),
                currentPage = 0
            )
        }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }
}
