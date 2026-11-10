package com.epubsplitter.data

import android.content.Context
import android.net.Uri
import androidx.core.text.HtmlCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.URLDecoder
import java.util.ArrayDeque
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

data class ParsedBook(
    val title: String,
    val chunks: List<String>,
    val totalCharacters: Int
)

object EpubParser {

    /**
     * Безпечно парсить EPUB за наданим Uri
     */
    suspend fun parseFromUri(
        context: Context,
        uri: Uri,
        chunkSize: Int = SmartSplitter.DEFAULT_CHUNK_SIZE,
        onProgress: (String) -> Unit = {}
    ): ParsedBook = withContext(Dispatchers.IO) {
        onProgress("Отримання доступу до файлу...")

        val tempFile = File(context.cacheDir, "import_active.epub")
        if (tempFile.exists()) {
            tempFile.delete()
        }

        var copiedBytes = 0L

        // Спосіб 1: Через openInputStream
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        copiedBytes += read
                    }
                    output.flush()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Спосіб 2: Якщо потік дав 0 байт (наприклад, для хмарних провайдерів або спеціальних сховищ)
        if (copiedBytes == 0L || tempFile.length() == 0L) {
            try {
                context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                    FileInputStream(pfd.fileDescriptor).use { fis ->
                        FileOutputStream(tempFile).use { fos ->
                            val buffer = ByteArray(64 * 1024)
                            var read: Int
                            while (fis.read(buffer).also { read = it } != -1) {
                                fos.write(buffer, 0, read)
                                copiedBytes += read
                            }
                            fos.flush()
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        if (!tempFile.exists() || tempFile.length() == 0L) {
            throw IllegalStateException("Файл виявився порожнім (0 байт). Якщо він у хмарі (Google Drive/Telegram), збережіть його у пам'ять телефону перед відкриттям.")
        }

        // Перевірка магічних байтів ZIP ("PK" = 0x50, 0x4B)
        val header = ByteArray(2)
        FileInputStream(tempFile).use { it.read(header) }
        val isZipHeader = header[0] == 0x50.toByte() && header[1] == 0x4B.toByte()
        if (!isZipHeader) {
            throw IllegalStateException("Файл не є архівом EPUB (відсутній сигнатурний заголовок PK). Можливо, файл пошкоджений або має інший формат.")
        }

        parseFromFile(tempFile, chunkSize, onProgress)
    }

    suspend fun parseFromFile(
        file: File,
        chunkSize: Int = SmartSplitter.DEFAULT_CHUNK_SIZE,
        onProgress: (String) -> Unit = {}
    ): ParsedBook = withContext(Dispatchers.IO) {
        onProgress("Розпакування книги...")

        val extractDir = File(file.parentFile, "epub_unpacked_${System.currentTimeMillis()}")
        if (extractDir.exists()) extractDir.deleteRecursively()
        extractDir.mkdirs()

        try {
            // Використовуємо потокове розпакування ZipInputStream:
            // Це повністю запобігає помилці "zip END header not found",
            // яка виникає у ZipFile через нестандартні коментарі чи байти наприкінці файлу.
            var extractedCount = 0
            try {
                ZipInputStream(FileInputStream(file).buffered()).use { zis ->
                    var entry: ZipEntry? = zis.nextEntry
                    while (entry != null) {
                        val cleanName = entry.name.replace('\\', '/').trim().removePrefix("/")
                        if (cleanName.isNotBlank()) {
                            val targetFile = File(extractDir, cleanName)
                            // Захист від Path Traversal
                            if (targetFile.canonicalPath.startsWith(extractDir.canonicalPath)) {
                                if (entry.isDirectory) {
                                    targetFile.mkdirs()
                                } else {
                                    targetFile.parentFile?.mkdirs()
                                    FileOutputStream(targetFile).use { fos ->
                                        zis.copyTo(fos)
                                    }
                                    extractedCount++
                                }
                            }
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
            } catch (e: Exception) {
                // Якщо ZipInputStream зупинився на якомусь елементі, перевіряємо, чи є вже розпаковані файли
                if (extractedCount == 0) {
                    // Резервна спроба через звичайний ZipFile
                    unpackViaZipFile(file, extractDir)
                }
            }

            // 1. Пошук container.xml
            onProgress("Пошук структури книги...")
            val containerFile = findFile(extractDir, "META-INF/container.xml")
                ?: throw IllegalStateException("Не знайдено META-INF/container.xml у книзі")

            val docBuilder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            val containerDoc = containerFile.inputStream().use { docBuilder.parse(it) }

            val rootfiles = containerDoc.getElementsByTagName("rootfile")
            if (rootfiles.length == 0) {
                throw IllegalStateException("Не знайдено rootfile у container.xml")
            }

            val opfRelativePath = rootfiles.item(0).attributes?.getNamedItem("full-path")?.nodeValue
                ?: throw IllegalStateException("Відсутній атрибут full-path для OPF файлу")

            // 2. Читання OPF файлу
            val opfFile = findFile(extractDir, opfRelativePath)
                ?: throw IllegalStateException("Не знайдено OPF файл: $opfRelativePath")

            val opfDoc = opfFile.inputStream().use { docBuilder.parse(it) }

            // Заголовок книги
            val titleNodeList = opfDoc.getElementsByTagName("dc:title")
            val fallbackTitleNodeList = opfDoc.getElementsByTagName("title")
            val bookTitle = when {
                titleNodeList.length > 0 -> titleNodeList.item(0).textContent?.trim() ?: "Без назви"
                fallbackTitleNodeList.length > 0 -> fallbackTitleNodeList.item(0).textContent?.trim() ?: "Без назви"
                else -> file.nameWithoutExtension
            }

            // Маніфест (id -> href)
            val manifestNodes = opfDoc.getElementsByTagName("item")
            val manifest = mutableMapOf<String, String>()
            for (i in 0 until manifestNodes.length) {
                val node = manifestNodes.item(i)
                val id = node.attributes?.getNamedItem("id")?.nodeValue
                val href = node.attributes?.getNamedItem("href")?.nodeValue
                if (id != null && href != null) {
                    manifest[id] = href
                }
            }

            // Spine (порядок читання розділів)
            val spineNodes = opfDoc.getElementsByTagName("itemref")
            val spine = mutableListOf<String>()
            for (i in 0 until spineNodes.length) {
                val node = spineNodes.item(i)
                val idref = node.attributes?.getNamedItem("idref")?.nodeValue
                if (idref != null) {
                    spine.add(idref)
                }
            }

            // 3. Вилучення тексту розділів
            val extractedSections = mutableListOf<String>()
            val totalSections = spine.size

            for ((index, idref) in spine.withIndex()) {
                val href = manifest[idref] ?: continue
                val fullChapterPath = resolvePath(opfRelativePath, href)
                val chapterFile = findFile(extractDir, fullChapterPath) ?: continue

                onProgress("Обробка розділу ${index + 1} з $totalSections...")

                val rawHtml = try {
                    chapterFile.readText(Charsets.UTF_8)
                } catch (e: Exception) {
                    try {
                        chapterFile.readText(Charsets.ISO_8859_1)
                    } catch (e2: Exception) {
                        ""
                    }
                }

                val cleanText = cleanHtmlContent(rawHtml)
                if (cleanText.isNotBlank()) {
                    extractedSections.add(cleanText)
                }
            }

            if (extractedSections.isEmpty()) {
                throw IllegalStateException("У книзі не знайдено тексту для читання")
            }

            onProgress("Розбиття тексту (Smart Split ~15 000 символів)...")
            val fullText = extractedSections.joinToString(separator = "\n\n***\n\n")
            val chunks = SmartSplitter.splitText(fullText, chunkSize)

            ParsedBook(
                title = bookTitle,
                chunks = chunks,
                totalCharacters = fullText.length
            )
        } finally {
            // Очищення тимчасової папки розпакування
            extractDir.deleteRecursively()
        }
    }

    private fun unpackViaZipFile(zipFile: File, targetDir: File) {
        val zip = ZipFile(zipFile)
        try {
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                val cleanName = entry.name.replace('\\', '/').trim().removePrefix("/")
                if (cleanName.isNotBlank()) {
                    val outFile = File(targetDir, cleanName)
                    if (outFile.canonicalPath.startsWith(targetDir.canonicalPath)) {
                        if (entry.isDirectory) {
                            outFile.mkdirs()
                        } else {
                            outFile.parentFile?.mkdirs()
                            zip.getInputStream(entry).use { input ->
                                FileOutputStream(outFile).use { output ->
                                    input.copyTo(output)
                                }
                            }
                        }
                    }
                }
            }
        } finally {
            zip.close()
        }
    }

    private fun findFile(baseDir: File, relativePath: String): File? {
        val cleanPath = relativePath.replace('\\', '/').trim().removePrefix("/")
        val decodedPath = try {
            URLDecoder.decode(cleanPath, "UTF-8")
        } catch (e: Exception) {
            cleanPath
        }

        // 1. Точний збіг
        val f1 = File(baseDir, cleanPath)
        if (f1.exists() && f1.isFile) return f1

        // 2. Декодований URL
        val f2 = File(baseDir, decodedPath)
        if (f2.exists() && f2.isFile) return f2

        // 3. Нечутливий до регістру пошук
        val targetName = File(decodedPath).name
        return baseDir.walkTopDown().firstOrNull { it.isFile && it.name.equals(targetName, ignoreCase = true) }
    }

    private fun resolvePath(basePath: String, relativePath: String): String {
        val baseDir = if (basePath.contains("/")) basePath.substringBeforeLast("/") else ""
        val combined = if (baseDir.isEmpty()) relativePath else "$baseDir/$relativePath"
        val parts = combined.split("/").filter { it.isNotEmpty() }
        val stack = ArrayDeque<String>()

        for (part in parts) {
            when (part) {
                "." -> continue
                ".." -> if (stack.isNotEmpty()) stack.removeLast()
                else -> stack.addLast(part)
            }
        }
        return stack.joinToString("/")
    }

    private fun cleanHtmlContent(htmlString: String): String {
        if (htmlString.isBlank()) return ""

        var text = htmlString

        // Видалення системних блоків
        text = text.replace(Regex("(?is)<style[^>]*>.*?</style>"), "")
        text = text.replace(Regex("(?is)<script[^>]*>.*?</script>"), "")
        text = text.replace(Regex("(?is)<head[^>]*>.*?</head>"), "")

        // Перетворення блокових тегів у переноси рядків
        text = text.replace(Regex("(?i)</(p|div|h[1-6]|li|tr|section|article|blockquote)>"), "\n\n")
        text = text.replace(Regex("(?i)<br\\s*/?>"), "\n")

        // Видалення всіх залишків тегів
        text = text.replace(Regex("<[^>]+>"), "")

        // Декодування HTML сутностей (&quot;, &amp;, &nbsp;, &#160; тощо)
        text = try {
            HtmlCompat.fromHtml(text, HtmlCompat.FROM_HTML_MODE_COMPACT).toString()
        } catch (e: Exception) {
            text.replace("&nbsp;", " ")
                .replace("&quot;", "\"")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
        }

        // Нормалізація повторюваних порожніх рядків
        return text.replace(Regex("\n[ \t]*\n[ \t]*\n+"), "\n\n").trim()
    }
}
