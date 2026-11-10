package com.epubsplitter.data

enum class AppTheme {
    LIGHT,
    SEPIA,
    DARK
}

data class BookMetadata(
    val title: String,
    val totalChunks: Int,
    val totalCharacters: Int
)

data class ReaderState(
    val isBookLoaded: Boolean = false,
    val bookTitle: String = "",
    val chunks: List<String> = emptyList(),
    val currentPage: Int = 0,
    val fontSizeSp: Float = 16f,
    val theme: AppTheme = AppTheme.LIGHT,
    val keepScreenOn: Boolean = true,
    val isLoading: Boolean = false,
    val loadingMessage: String = "",
    val errorMessage: String? = null
)
