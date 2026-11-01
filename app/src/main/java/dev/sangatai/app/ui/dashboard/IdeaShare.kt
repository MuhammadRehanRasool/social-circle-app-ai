package dev.braindump.splitter.ui.dashboard

import android.content.Context
import android.content.Intent
import android.net.Uri
import dev.braindump.splitter.R
import dev.braindump.splitter.domain.model.Idea
import dev.braindump.splitter.domain.model.IdeaStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate

/** Opens the system share sheet with the note as Markdown, ready for notes, chats or issues. */
internal fun shareIdea(context: Context, idea: Idea) {
    val send = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_TEXT, ideaShareText(idea))
    if (idea.title.isNotBlank()) send.putExtra(Intent.EXTRA_SUBJECT, idea.title)
    context.startActivity(Intent.createChooser(send, context.getString(R.string.idea_share_title)))
}

internal fun ideaShareText(idea: Idea): String {
    if (idea.status != IdeaStatus.READY) return idea.rawInput
    return buildString {
        appendLine("# ${idea.title}")
        if (idea.body.isNotBlank()) {
            appendLine()
            appendLine(idea.body)
        }
    }.trimEnd()
}

/**
 * Every idea as Markdown, one heading per day, newest first. Headings inside a note are
 * pushed down a few levels so they stay under the note's own title:
 *
 * ```
 * ## 2026-10-18
 *
 * ### Getting My Evenings Back
 * 🏠 Personal
 *
 * My evenings keep disappearing into my phone…
 *
 * #### What I could try
 * - Pick one thing for the evening before I leave work.
 * ```
 */
internal fun exportMarkdown(context: Context, sections: List<DaySection>): String {
    val count = sections.sumOf { it.ideas.size }
    return buildString {
        appendLine("# ${context.getString(R.string.app_name)} · " +
            "${context.resources.getQuantityString(R.plurals.timeline_ideas, count, count)} · ${LocalDate.now()}")
        sections.forEach { section ->
            appendLine()
            appendLine("## ${LocalDate.ofEpochDay(section.epochDay)}")
            section.ideas.forEach { idea ->
                appendLine()
                appendIdea(context, idea)
            }
        }
    }.trimEnd() + "\n"
}

private fun StringBuilder.appendIdea(context: Context, idea: Idea) {
    if (idea.status != IdeaStatus.READY) {
        appendLine("**${context.getString(R.string.export_unsplit)}** ${oneLine(idea.rawInput)}")
        return
    }
    appendLine("### ${oneLine(idea.title)}")
    appendLine("${idea.category.emoji} ${context.getString(idea.category.label)}")
    if (idea.body.isNotBlank()) {
        appendLine()
        appendLine(idea.body.replace(NOTE_HEADING) { "#".repeat(minOf(6, it.groupValues[1].length + 3)) + " " })
    }
}

private fun oneLine(text: String): String = text.replace(LINE_BREAKS, " ").trim()

private val LINE_BREAKS = Regex("""\s*[\r\n]+\s*""")

private val NOTE_HEADING = Regex("""(?m)^(#{1,6})\s+""")

/** Returns false when the text is too big for a share intent, so the caller can offer a file instead. */
internal fun shareExport(context: Context, markdown: String): Boolean {
    if (markdown.length > MAX_SHARE_CHARS) return false
    val send = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_TEXT, markdown)
        .putExtra(Intent.EXTRA_SUBJECT, exportFileName())
    context.startActivity(Intent.createChooser(send, context.getString(R.string.export_share_title)))
    return true
}

internal suspend fun saveExport(context: Context, uri: Uri, markdown: String): Boolean = withContext(Dispatchers.IO) {
    runCatching {
        context.contentResolver.openOutputStream(uri)?.use { it.write(markdown.toByteArray(Charsets.UTF_8)) } != null
    }.getOrDefault(false)
}

internal fun exportFileName(): String = "braindump-${LocalDate.now()}.md"

// Intent extras travel through a ~1 MB binder buffer shared with the rest of the transaction.
private const val MAX_SHARE_CHARS = 100_000
