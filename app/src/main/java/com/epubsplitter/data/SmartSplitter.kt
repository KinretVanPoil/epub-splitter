package com.epubsplitter.data

object SmartSplitter {

    const val DEFAULT_CHUNK_SIZE = 15000
    private const val LOOKBACK_WINDOW = 3000

    /**
     * Розбиває суцільний текст книги на порції розміром ~15 000 символів,
     * зберігаючи цілісність абзаців, речень та слів.
     */
    fun splitText(text: String, chunkSize: Int = DEFAULT_CHUNK_SIZE): List<String> {
        if (text.isBlank()) return emptyList()

        val chunks = mutableListOf<String>()
        var startIndex = 0
        val textLength = text.length

        while (startIndex < textLength) {
            var targetEnd = startIndex + chunkSize

            if (targetEnd >= textLength) {
                val finalChunk = text.substring(startIndex).trim()
                if (finalChunk.isNotEmpty()) {
                    chunks.add(finalChunk)
                }
                break
            }

            var bestEndIndex = -1
            val searchLimit = maxOf(startIndex, targetEnd - LOOKBACK_WINDOW)
            val chunkSub = text.substring(searchLimit, targetEnd)

            // 1. Пошук розриву абзацу (\n\n або \n)
            val lastDoubleNewline = chunkSub.lastIndexOf("\n\n")
            if (lastDoubleNewline != -1) {
                bestEndIndex = searchLimit + lastDoubleNewline + 2
            } else {
                val lastSingleNewline = chunkSub.lastIndexOf('\n')
                if (lastSingleNewline != -1) {
                    bestEndIndex = searchLimit + lastSingleNewline + 1
                }
            }

            // 2. Якщо нового рядка немає, шукаємо кінець речення
            if (bestEndIndex == -1) {
                val punctuation = charArrayOf('.', '!', '?', '…', '"', '»', '”', ')')
                val whitespace = charArrayOf(' ', '\n', '\r', '\t')

                for (i in targetEnd - 1 downTo searchLimit + 1) {
                    val char = text[i]
                    val prevChar = text[i - 1]
                    if (char in whitespace && prevChar in punctuation) {
                        bestEndIndex = i
                        break
                    }
                }
            }

            // 3. Якщо розділових знаків немає, шукаємо пробіл (межу слова)
            if (bestEndIndex == -1) {
                val lastSpace = text.lastIndexOf(' ', targetEnd)
                if (lastSpace > searchLimit) {
                    bestEndIndex = lastSpace + 1
                }
            }

            if (bestEndIndex != -1) {
                targetEnd = bestEndIndex
            }

            val chunk = text.substring(startIndex, targetEnd).trim()
            if (chunk.isNotEmpty()) {
                chunks.add(chunk)
            }
            startIndex = targetEnd
        }

        return chunks
    }
}
