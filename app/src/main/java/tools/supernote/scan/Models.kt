package tools.supernote.scan

const val MAX_RECOGNITION_PROMPT_CHARS = 12_000

const val DEFAULT_RECOGNITION_PROMPT = """You are transcribing handwritten notes from one or more Supernote page images. Transcribe every page in the supplied image order. Use the images as the source of truth. Preserve all legible content, original wording, spelling, punctuation, script, diacritics, and reading order. Ignore device controls, status icons, and page navigation. Do not infer text outside the visible note, summarize, translate, correct grammar, or invent missing words.

Reflow ordinary prose: join line wraps that belong to one thought into natural flowing paragraphs. Keep paragraph breaks and use headings, bullets, numbered lists, poetry, or other structure only where the handwriting supports them. Continue a sentence across captured-page boundaries without inserting page headings, page labels, or artificial breaks.

Mark an unintelligible span as [...]. Sanskrit words may appear: preserve the spelling and script visible in the image. Put a Sanskrit word in square brackets when its reading is uncertain, for example [jnana]; add no diacritics or script that are not visible. Use glossary entries only as possible spellings, never as proof that a term appears.

Return only the transcription, without commentary or code fences. If no note or document writing is visible, return an empty string."""

data class CapturedPage(
    val id: String,
    val path: String,
    val width: Int,
    val height: Int,
    val capturedAt: Long,
)

data class MirrorFrame(
    val bytes: ByteArray,
    val mimeType: String,
    val width: Int,
    val height: Int,
    val receivedAt: Long,
    val samplePageNumber: Int? = null,
)

data class ScanSettings(
    val provider: String = "api",
    val model: String = "gpt-6.1-sol",
    val manualIp: String = "",
    val glossary: String = "",
    val instructions: String = "",
    val prompt: String = DEFAULT_RECOGNITION_PROMPT,
)

data class ModelChoice(val id: String, val name: String)
