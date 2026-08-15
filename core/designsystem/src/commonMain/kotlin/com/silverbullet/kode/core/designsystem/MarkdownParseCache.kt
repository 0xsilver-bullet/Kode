package com.silverbullet.kode.core.designsystem

import androidx.compose.runtime.staticCompositionLocalOf
import com.mikepenz.markdown.model.State
import com.mikepenz.markdown.model.parseMarkdown
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser

/**
 * Process-lifetime cache of parsed markdown, keyed by message identity.
 *
 * This exists because of how `rememberMarkdownState` behaves in a lazy list.
 * Its parsed state is a plain `remember`, so it dies when a row scrolls out of
 * view, and a fresh state starts in `State.Loading` — which the renderer draws
 * as an **empty, zero-height box** until an off-thread parse completes a frame
 * or two later. Scrolling back over previous messages therefore collapses each
 * row to 0dp and then snaps it to full height, which is the jitter you feel.
 * `retainState = true` does not help: it only applies when the input changes on
 * an *existing* state, never to a newly created one.
 *
 * Caching the parsed result outside composition means a row that has been seen
 * once renders at its real height immediately, with no loading frame at all.
 *
 * `State.Success` is `@Immutable` and holds an immutable AST plus a
 * fully-populated link handler, so it is safe to retain and re-render.
 */
class MarkdownParseCache(
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
) {
    private val lock = Mutex()
    private val entries = LinkedHashMap<String, State>()

    /**
     * Returns an already-parsed result, or null.
     *
     * Deliberately non-suspending so composition can render a cache hit in the
     * same frame instead of scheduling an effect.
     */
    fun peek(key: String): State? = entries[key]

    /** Parses if needed, then caches. Parsing happens on a background thread. */
    suspend fun parse(key: String, markdown: String): State {
        peek(key)?.let { return it }

        // Not held across the parse: two rows parsing different messages
        // concurrently is fine and preferable to serialising them. A single
        // shared parser avoids rebuilding the GFM flavour per message.
        val parsed = withContext(Dispatchers.Default) {
            parseMarkdown(content = markdown, flavour = Flavour, parser = Parser)
        }

        lock.withLock {
            // Re-insert to move the entry to the most-recent position.
            entries.remove(key)
            entries[key] = parsed
            while (entries.size > maxEntries) {
                val oldest = entries.keys.firstOrNull() ?: break
                entries.remove(oldest)
            }
        }
        return parsed
    }

    fun clear() {
        entries.clear()
    }

    private companion object {
        /**
         * Enough for several screens of scrollback in both directions. Entries
         * are ASTs, not bitmaps, so this is modest in memory terms.
         */
        const val DEFAULT_MAX_ENTRIES = 96

        // Shared: constructing a flavour and parser per message is not free,
        // and both are stateless with respect to a single parse call.
        val Flavour = GFMFlavourDescriptor()
        val Parser = MarkdownParser(Flavour)
    }
}

/**
 * The cache must outlive any one screen, so it is provided from the app root
 * rather than remembered per composable.
 */
val LocalMarkdownParseCache = staticCompositionLocalOf<MarkdownParseCache> {
    error("MarkdownParseCache requested outside of KodeTheme.")
}
