package com.example.ai

import com.example.model.CaptionCue

object TeluguScriptChunker {

    const val MAX_WORD_COUNT = 10_000
    private const val TARGET_WORDS_PER_CHUNK = 650
    private const val TELUGU_WORDS_PER_MINUTE = 128.0

    fun countWords(text: String): Int {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return 0
        return trimmed.split(Regex("\\s+")).count { it.isNotBlank() }
    }

    fun estimateDurationSeconds(wordCount: Int): Long {
        if (wordCount <= 0) return 0L
        return ((wordCount / TELUGU_WORDS_PER_MINUTE) * 60.0).toLong().coerceAtLeast(4L)
    }

    fun estimateSceneDurationMs(dialogueTelugu: String): Long {
        val words = countWords(dialogueTelugu)
        if (words <= 0) return 4_500L
        val seconds = (words / TELUGU_WORDS_PER_MINUTE) * 60.0
        return ((seconds * 1000.0).toLong() + 800L).coerceIn(3_500L, 120_000L)
    }

    fun formatDuration(totalSeconds: Long): String {
        val safeSec = totalSeconds.coerceAtLeast(0L)
        val hours = safeSec / 3600
        val minutes = (safeSec % 3600) / 60
        val seconds = safeSec % 60
        return if (hours > 0) {
            String.format("%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%02d:%02d", minutes, seconds)
        }
    }

    /**
     * Splits a Telugu script of up to 10,000 words into balanced narrative chunks along paragraph
     * and sentence boundaries so Gemini can produce complete scene breakdowns without truncating.
     */
    fun chunkScript(scriptText: String, targetWordsPerChunk: Int = TARGET_WORDS_PER_CHUNK): List<String> {
        val normalized = scriptText.replace("\r\n", "\n").trim()
        if (normalized.isEmpty()) return emptyList()

        val paragraphs = normalized.split(Regex("\n+")).map { it.trim() }.filter { it.isNotEmpty() }
        val chunks = mutableListOf<String>()
        val currentBuilder = StringBuilder()
        var currentWords = 0

        for (paragraph in paragraphs) {
            val paraWords = countWords(paragraph)
            if (paraWords > targetWordsPerChunk) {
                // Flush accumulated chunk first
                if (currentBuilder.isNotEmpty()) {
                    chunks.add(currentBuilder.toString().trim())
                    currentBuilder.clear()
                    currentWords = 0
                }
                // Split large paragraph along Telugu/Unicode sentence boundaries
                val sentences = paragraph.split(Regex("(?<=[.!?।\\n])\\s+"))
                for (sentence in sentences) {
                    val sentWords = countWords(sentence)
                    if (currentWords + sentWords > targetWordsPerChunk && currentBuilder.isNotEmpty()) {
                        chunks.add(currentBuilder.toString().trim())
                        currentBuilder.clear()
                        currentWords = 0
                    }
                    if (currentBuilder.isNotEmpty()) currentBuilder.append(' ')
                    currentBuilder.append(sentence)
                    currentWords += sentWords
                }
            } else {
                if (currentWords + paraWords > targetWordsPerChunk && currentBuilder.isNotEmpty()) {
                    chunks.add(currentBuilder.toString().trim())
                    currentBuilder.clear()
                    currentWords = 0
                }
                if (currentBuilder.isNotEmpty()) currentBuilder.append("\n\n")
                currentBuilder.append(paragraph)
                currentWords += paraWords
            }
        }

        if (currentBuilder.isNotEmpty()) {
            chunks.add(currentBuilder.toString().trim())
        }
        return chunks
    }

    /**
     * Generates dialogue-synchronized caption cues aligned to the exact narration audio duration,
     * weighted by Telugu character count and natural punctuation pauses.
     */
    fun buildSyncedCaptionCues(
        captionLines: List<String>,
        fallbackDialogue: String,
        totalDurationMs: Long
    ): List<CaptionCue> {
        val safeTotalMs = totalDurationMs.coerceAtLeast(1_500L)
        val rawLines = if (captionLines.isNotEmpty()) {
            captionLines.map { it.trim() }.filter { it.isNotEmpty() }
        } else {
            splitDialogueIntoCaptionLines(fallbackDialogue)
        }
        if (rawLines.isEmpty()) return emptyList()

        val weights = rawLines.map { line ->
            val charWeight = line.replace(" ", "").length.coerceAtLeast(6)
            val pauseBonus = if (line.endsWith(".") || line.endsWith("!") || line.endsWith("?") || line.endsWith("।")) 4 else 1
            (charWeight + pauseBonus).toDouble()
        }
        val totalWeight = weights.sum().coerceAtLeast(1.0)

        val cues = mutableListOf<CaptionCue>()
        var cursorMs = 0L
        rawLines.forEachIndexed { index, line ->
            val duration = if (index == rawLines.lastIndex) {
                (safeTotalMs - cursorMs).coerceAtLeast(400L)
            } else {
                ((weights[index] / totalWeight) * safeTotalMs).toLong().coerceAtLeast(600L)
            }
            val endMs = (cursorMs + duration).coerceAtMost(safeTotalMs)
            cues.add(CaptionCue(text = line, startMs = cursorMs, endMs = endMs))
            cursorMs = endMs
        }
        return cues
    }

    fun splitDialogueIntoCaptionLines(dialogue: String, maxCharsPerLine: Int = 52): List<String> {
        val words = dialogue.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        val lines = mutableListOf<String>()
        val current = StringBuilder()
        for (word in words) {
            if (current.isNotEmpty() && current.length + 1 + word.length > maxCharsPerLine) {
                lines.add(current.toString())
                current.clear()
            }
            if (current.isNotEmpty()) current.append(' ')
            current.append(word)
        }
        if (current.isNotEmpty()) lines.add(current.toString())
        return lines
    }

    val SAMPLE_TELUGU_TITLE = "గోదావరి తీరంలో బంగారు దీపం"

    val SAMPLE_TELUGU_SCRIPT = """
దృశ్యం 1: గోదావరి తీరం — సూర్యోదయం
నరేటర్: పొగమంచు కప్పిన గోదావరి నదిపై బంగారు సూర్యకిరణాలు మెరుస్తున్నాయి. దూరంగా పడవల గంటల శబ్దం ప్రశాంతంగా వినిపిస్తోంది. ఆ చిన్న గ్రామంలో అరుణ్ అనే యువ శిల్పి తన తాతయ్య వారసత్వాన్ని కాపాడుతూ జీవిస్తున్నాడు.

దృశ్యం 2: ప్రాచీన శిల్పశాల
అరుణ్: "తాతయ్య, ఈ రాతిలో దాగిన సంగీతాన్ని నేను మళ్ళీ ప్రపంచానికి వినిపిస్తాను. మన ఊరి కళ ఎప్పటికీ మాయమైపోదు!"
నరేటర్: అరుణ్ చేతిలో ఉలి పట్టుకుని చెక్కుతున్న ప్రతి గీతలోనూ ఒక అద్భుతమైన కథ జీవం పోసుకుంటోంది. బయట వర్షపు చినుకులు మెల్లగా నేలను తాకుతున్నాయి.

దృశ్యం 3: రాత్రి ఉత్సవం మరియు ఆశ
మీరా: "అరుణ్, నువ్వు చెక్కిన ఈ దీపస్తంభం చూస్తుంటే మొత్తం గ్రామానికి కొత్త వెలుగు వచ్చినట్లుంది. ఈ రాత్రి వేడుక చరిత్రలో నిలిచిపోతుంది!"
నరేటర్: వందలాది మట్టి దీపాలు వెలిగిన ఆ రాత్రి, గోదావరి అలలపై ప్రతిబింబించిన వెలుగు ప్రతి హృదయంలోనూ కొత్త ఆశను నింపింది.
    """.trimIndent()
}
