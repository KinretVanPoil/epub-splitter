package com.epubsplitter.data

import android.content.Context
import android.net.Uri
import androidx.core.text.HtmlCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.URLDecoder
import java.util.ArrayDeque
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

data class ParsedBook(
    val title: String,
    val chunks: List<String>,
    val totalCharacters: Int
)

object EpubParser {

    /**
     * Парсить EPUB файл за наданим Uri або File
     */
    suspend fun parseFromUri(
        context: Context,
        uri: Uri,
        chunkSize: Int = SmartSplitter.DEFAULT_CHUNK_SIZE,
        onProgress: (String) -> Unit = {}
    ): ParsedBook = withContext(Dispatchers.IO) {
        onProgress("Копіювання файлу в захищене сховище...")
        val tempFile = File(context.cacheDir, "import_active.epub")
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(tempFile).use { output ->
                input.copyTo(output)
            }
        } ?: throw IllegalArgumentException("Неможливо відкрити файл")

        parseFromFile(tempFile, chunkSize, onProgress)
    }

    suspend fun parseFromFile(
        file: File,
        chunkSize: Int = SmartSplitter.DEFAULT_CHUNK_SIZE,
        onProgress: (String) -> Unit = {}
    ): ParsedBook = withContext(Dispatchers.IO) {
        onProgress("Розпакування EPUB архіву...")
        val zip = ZipFile(file)

        try {
            // 1. Пошук container.xml
            val containerEntry = findZipEntry(zip, "META-INF/container.xml")
                ?: throw IllegalStateException("Файл META-INF/container.xml не знайдено (некоректний EPUB)")

            val docBuilder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            val containerDoc = zip.getInputStream(containerEntry).use { docBuilder.parse(it) }

            val rootfiles = containerDoc.getElementsByTagName("rootfile")
            if (rootfiles.length == 0) {
                throw IllegalStateException("Не знайдено rootfile у container.xml")
            }

            val opfPath = rootfiles.item(0).attributes?.getNamedItem("full-path")?.nodeValue
                ?: throw IllegalStateException("Відсутній атрибут full-path для OPF файлу")

            // 2. Читання OPF файлу
            onProgress("Читання структури книги...")
            val opfEntry = findZipEntry(zip, opfPath)
                ?: throw IllegalStateException("Не знайдено OPF файл: $opfPath")

            val opfDoc = zip.getInputStream(opfEntry).use { docBuilder.parse(it) }

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

            // Spine (порядок читання)
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
                val fullChapterPath = resolvePath(opfPath, href)
                val chapterEntry = findZipEntry(zip, fullChapterPath) ?: continue

                onProgress("Обробка розділу ${index + 1} з $totalSections...")

                val rawHtml = zip.getInputStream(chapterEntry).bufferedReader(Charsets.UTF_8).use {
                    it.readText()
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
            zip.close()
        }
    }

    private fun findZipEntry(zip: ZipFile, path: String): ZipEntry? {
        val normalized = path.trim().removePrefix("/")
        val decoded = try {
            URLDecoder.decode(normalized, "UTF-8")
        } catch (e: Exception) {
            normalized
        }

        return zip.getEntry(normalized)
            ?: zip.getEntry(decoded)
            ?: zip.getEntry(normalized.replace('/', '\\'))
            ?: zip.entries().asSequence().firstOrNull {
                it.name.equals(normalized, ignoreCase = true) ||
                it.name.equals(decoded, ignoreCase = true)
            }
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
