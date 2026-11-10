package com.epubsplitter

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.epubsplitter.ui.JumpDialog
import com.epubsplitter.ui.MainViewModel
import com.epubsplitter.ui.ReaderView
import com.epubsplitter.ui.SettingsDialog
import com.epubsplitter.ui.WelcomeView
import com.epubsplitter.ui.theme.EpubSplitterTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Перевіряємо, чи додаток відкрили через файл EPUB (Intent)
        intent?.data?.let { handleIncomingUri(it) }

        setContent {
            val state by viewModel.uiState.collectAsState()
            val snackbarHostState = remember { SnackbarHostState() }
            val coroutineScope = rememberCoroutineScope()
            val clipboardManager = LocalClipboardManager.current

            var showSettingsDialog by remember { mutableStateOf(false) }
            var showJumpDialog by remember { mutableStateOf(false) }

            // Керування прапорцем блокування засинання екрана (Wake Lock)
            DisposableEffect(state.keepScreenOn) {
                if (state.keepScreenOn) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
                onDispose {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }

            // Лаунчер системного вибору файлів
            val filePickerLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.OpenDocument()
            ) { uri: Uri? ->
                uri?.let { viewModel.openEpubUri(it) }
            }

            EpubSplitterTheme(appTheme = state.theme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    if (state.isBookLoaded && state.chunks.isNotEmpty()) {
                        ReaderView(
                            bookTitle = state.bookTitle,
                            chunks = state.chunks,
                            currentPage = state.currentPage,
                            fontSizeSp = state.fontSizeSp,
                            snackbarHostState = snackbarHostState,
                            onPrevPage = { viewModel.changePage(-1) },
                            onNextPage = { viewModel.changePage(1) },
                            onPageClick = { showJumpDialog = true },
                            onCopyClick = { chunkText ->
                                clipboardManager.setText(AnnotatedString(chunkText))
                                coroutineScope.launch {
                                    val count = chunkText.length
                                    snackbarHostState.showSnackbar("Скопійовано $count символів у буфер!")
                                }
                            },
                            onSettingsClick = { showSettingsDialog = true },
                            onCloseBookClick = { viewModel.closeBook() }
                        )
                    } else {
                        WelcomeView(
                            onSelectFileClick = {
                                filePickerLauncher.launch(
                                    arrayOf(
                                        "application/epub+zip",
                                        "application/octet-stream",
                                        "*/*"
                                    )
                                )
                            }
                        )
                    }

                    // Модальне вікно налаштувань
                    if (showSettingsDialog) {
                        SettingsDialog(
                            currentTheme = state.theme,
                            currentFontSize = state.fontSizeSp,
                            keepScreenOn = state.keepScreenOn,
                            onThemeChange = { viewModel.setTheme(it) },
                            onFontSizeChange = { viewModel.setFontSize(it) },
                            onKeepScreenOnChange = { viewModel.setKeepScreenOn(it) },
                            onCloseBook = { viewModel.closeBook() },
                            onDismiss = { showSettingsDialog = false }
                        )
                    }

                    // Модальне вікно переходу на сторінку
                    if (showJumpDialog) {
                        JumpDialog(
                            currentPage = state.currentPage,
                            totalPages = state.chunks.size,
                            onDismiss = { showJumpDialog = false },
                            onJump = { pageIndex -> viewModel.goToPage(pageIndex) }
                        )
                    }

                    // Індикатор завантаження / обробки
                    if (state.isLoading) {
                        LoadingOverlay(message = state.loadingMessage)
                    }

                    // Діалог повідомлення про помилку
                    state.errorMessage?.let { msg ->
                        AlertDialog(
                            onDismissRequest = { viewModel.clearError() },
                            title = { Text("Помилка") },
                            text = { Text(msg) },
                            confirmButton = {
                                TextButton(onClick = { viewModel.clearError() }) {
                                    Text("Зрозуміло")
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.data?.let { handleIncomingUri(it) }
    }

    private fun handleIncomingUri(uri: Uri) {
        viewModel.openEpubUri(uri)
    }
}

@Composable
fun LoadingOverlay(message: String) {
    Dialog(onDismissRequest = {}) {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(48.dp),
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = message.ifEmpty { "Завантаження..." },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}
