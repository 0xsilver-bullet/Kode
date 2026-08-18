package com.silverbullet.kode.feature.threads.domain

import com.silverbullet.kode.core.model.OrchestrationProjectShell
import com.silverbullet.kode.core.model.OrchestrationThread
import com.silverbullet.kode.core.model.OrchestrationThreadShell
import com.silverbullet.kode.core.model.ProjectId

/**
 * Derives a thread title from the first prompt, mirroring
 * `deriveThreadTitleFromPrompt` in T3 Code's mobile client: whitespace is
 * collapsed and the result capped at 72 characters. It seeds the server's
 * title generation (`titleSeed`) and doubles as the created thread's title
 * until the server regenerates a better one.
 */
fun deriveThreadTitleFromPrompt(value: String): String {
    val trimmed = value.trim()
    if (trimmed.isEmpty()) {
        return "New thread"
    }

    val compact = trimmed.replace(Regex("\\s+"), " ")
    return if (compact.length <= 72) compact else "${compact.take(69).trimEnd()}..."
}

/**
 * What the thread screen's app bar shows, mirroring T3 Code's mobile header:
 * the thread's own title on top, the project (and, when it disambiguates,
 * the environment) beneath it.
 */
data class ThreadHeader(
    val title: String,
    val subtitle: String?,
)

/**
 * Builds the header from whichever view of the thread has arrived.
 *
 * [shellThread] is the list projection, already in memory from the shell
 * subscription when the screen opens; [thread] is the detail snapshot, which
 * lands a moment later. Preferring the snapshot but falling back to the shell
 * is what puts the real title in the bar on the first frame instead of a
 * placeholder that swaps out — T3 Code gets this for free because its header
 * reads the same shell store the list does.
 */
fun buildThreadHeader(
    thread: OrchestrationThread?,
    shellThread: OrchestrationThreadShell?,
    projects: Map<ProjectId, OrchestrationProjectShell>,
    environmentLabel: String?,
    multiEnvironment: Boolean,
): ThreadHeader {
    val title = thread?.title?.takeIf { it.isNotBlank() }
        ?: shellThread?.title?.takeIf { it.isNotBlank() }
    val projectId = thread?.projectId ?: shellThread?.projectId
    val branch = thread?.branch ?: shellThread?.branch
    val subtitle = listOfNotNull(
        projectId?.let { projects[it]?.title },
        branch?.takeIf { it.isNotBlank() },
        environmentLabel?.takeIf { multiEnvironment && it.isNotBlank() },
    ).joinToString(" · ").ifEmpty { null }

    return ThreadHeader(title = title ?: FALLBACK_THREAD_TITLE, subtitle = subtitle)
}

/** Shown only in the gap before either projection has loaded. */
private const val FALLBACK_THREAD_TITLE = "Thread"
