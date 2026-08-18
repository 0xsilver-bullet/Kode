package com.silverbullet.kode.voiceserver.glossary

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.streams.toList

/**
 * Lazily builds a per-project vocabulary from whatever directory a voice session names,
 * the way opencode resolves instances per requested directory: no per-project server
 * config, just an [allowedRoots] fence around what this process will read.
 *
 * The glossary feeds two consumers with the same terms:
 *  - Deepgram `keyterm` parameters, so recognition itself prefers project jargon;
 *  - the refiner prompt, so corrections lean toward real identifiers.
 *
 * Cache entries are invalidated when git HEAD moves (a merge or checkout changes the
 * vocabulary) or after [ttlMillis] for non-git directories.
 */
class ProjectGlossaryService(
    private val allowedRoots: List<Path>,
    private val ttlMillis: Long = 15 * 60 * 1000L,
    private val now: () -> Long = System::currentTimeMillis,
) {
    data class Glossary(
        val projectName: String,
        val keyterms: List<String>,
        /** Compact prose block for the refiner prompt. */
        val contextSummary: String,
        internal val headStamp: String?,
        internal val builtAt: Long,
    )

    /** Deepgram allows 100 keyterms / 500 tokens total; stay comfortably inside both. */
    private companion object {
        const val MAX_KEYTERMS = 80
        const val MAX_KEYTERM_CHARS = 1_400
        const val MAX_TREE_ENTRIES = 4_000
        const val MAX_DEPTH = 5
        val SKIPPED_DIRS = setOf(
            ".git", ".gradle", ".idea", "node_modules", "build", "dist", "out", "target",
            ".venv", "venv", "__pycache__", ".next", ".turbo", "Pods", "DerivedData",
        )
        val STOPWORDS = setOf(
            "the", "and", "for", "with", "from", "into", "that", "this", "not", "are", "was",
            "when", "then", "than", "them", "its", "also", "only", "over", "under", "src",
            "main", "test", "tests", "common", "kotlin", "java", "resources", "assets",
            "readme", "license", "gitignore", "properties", "gradle", "gradlew", "index",
            "fix", "add", "use", "make", "update", "remove", "support", "instead",
        )
    }

    private val mutex = Mutex()
    private val cache = HashMap<Path, Glossary>()

    suspend fun glossaryFor(projectDir: String?): Glossary? {
        if (projectDir.isNullOrBlank()) return null
        val dir = runCatching { Path.of(projectDir).toRealPath() }.getOrNull() ?: return null
        if (!dir.isDirectory()) return null
        if (allowedRoots.none { root -> dir.startsWith(runCatching { root.toRealPath() }.getOrDefault(root)) }) {
            return null
        }
        return mutex.withLock {
            val cached = cache[dir]
            val stamp = gitHeadStamp(dir)
            val fresh = cached != null &&
                cached.headStamp == stamp &&
                (stamp != null || now() - cached.builtAt < ttlMillis)
            if (fresh) {
                cached
            } else {
                withContext(Dispatchers.IO) { build(dir, stamp) }.also { cache[dir] = it }
            }
        }
    }

    private fun build(dir: Path, headStamp: String?): Glossary {
        val scores = HashMap<String, Double>()

        fun credit(term: String, weight: Double) {
            val cleaned = term.trim().trim('.', ',', ':', ';', '"', '\'', '`', '(', ')', '[', ']')
            if (cleaned.length < 3 || cleaned.length > 40) return
            if (cleaned.lowercase() in STOPWORDS) return
            if (cleaned.all { it.isDigit() }) return
            scores.merge(cleaned, weight, Double::plus)
        }

        credit(dir.name, 10.0)
        scanTree(dir) { entry, depth ->
            val base = entry.name.substringBeforeLast('.')
            // Identifier-shaped names (CamelCase, snake_case) are exactly what Deepgram
            // mis-hears; keep them verbatim, capitalization included.
            val weight = if (depth <= 1) 3.0 else 1.5 / depth
            credit(base, weight)
        }
        buildFileTerms(dir).forEach { credit(it, 6.0) }
        readmeTerms(dir).forEach { credit(it, 2.5) }
        gitLogTerms(dir).forEach { credit(it, 1.0) }

        val ranked = scores.entries
            .sortedByDescending { it.value }
            .map { it.key }
        val keyterms = ArrayList<String>(MAX_KEYTERMS)
        var chars = 0
        for (term in ranked) {
            if (keyterms.size == MAX_KEYTERMS) break
            if (chars + term.length > MAX_KEYTERM_CHARS) break
            keyterms += term
            chars += term.length
        }

        val summary = buildString {
            append("Project \"").append(dir.name).append("\".")
            val tech = buildFileTerms(dir).take(12)
            if (tech.isNotEmpty()) append(" Technologies: ").append(tech.joinToString(", ")).append('.')
            val idents = keyterms.take(40)
            if (idents.isNotEmpty()) append(" Vocabulary: ").append(idents.joinToString(", ")).append('.')
        }

        return Glossary(
            projectName = dir.name,
            keyterms = keyterms,
            contextSummary = summary,
            headStamp = headStamp,
            builtAt = now(),
        )
    }

    private inline fun scanTree(root: Path, onEntry: (Path, Int) -> Unit) {
        var seen = 0
        val queue = ArrayDeque<Pair<Path, Int>>()
        queue += root to 0
        while (queue.isNotEmpty() && seen < MAX_TREE_ENTRIES) {
            val (dir, depth) = queue.removeFirst()
            val children = runCatching { Files.list(dir).use { it.toList() } }.getOrDefault(emptyList())
            for (child in children) {
                if (seen++ >= MAX_TREE_ENTRIES) break
                val name = child.name
                if (name.startsWith('.') && name != ".well-known") continue
                if (child.isDirectory()) {
                    if (name !in SKIPPED_DIRS && depth < MAX_DEPTH) queue += child to depth + 1
                    onEntry(child, depth + 1)
                } else {
                    onEntry(child, depth + 1)
                }
            }
        }
    }

    /** Dependency names out of the common build manifests — the spoken tech vocabulary. */
    private fun buildFileTerms(dir: Path): List<String> {
        val terms = LinkedHashSet<String>()

        val versionCatalog = dir.resolve("gradle/libs.versions.toml")
        if (versionCatalog.isRegularFile()) {
            Regex("""module\s*=\s*"([^":]+):([^"]+)"""").findAll(readSafely(versionCatalog))
                .forEach { terms += it.groupValues[2].substringBefore('-') }
        }
        val packageJson = dir.resolve("package.json")
        if (packageJson.isRegularFile()) {
            Regex(""""(@?[A-Za-z0-9_./-]+)"\s*:\s*"[~^]?\d""").findAll(readSafely(packageJson))
                .forEach { terms += it.groupValues[1].substringAfterLast('/') }
        }
        val cargo = dir.resolve("Cargo.toml")
        if (cargo.isRegularFile()) {
            Regex("""(?m)^([A-Za-z0-9_-]+)\s*=""").findAll(readSafely(cargo))
                .forEach { terms += it.groupValues[1] }
        }
        val goMod = dir.resolve("go.mod")
        if (goMod.isRegularFile()) {
            Regex("""(?m)^\t([^\s]+) v""").findAll(readSafely(goMod))
                .forEach { terms += it.groupValues[1].substringAfterLast('/') }
        }
        return terms.filter { it.length >= 3 }.take(40)
    }

    private fun readmeTerms(dir: Path): List<String> {
        val readme = sequenceOf("README.md", "readme.md", "README").map(dir::resolve)
            .firstOrNull { it.isRegularFile() } ?: return emptyList()
        return Regex("""(?m)^#{1,3}\s+(.+)$""").findAll(readSafely(readme))
            .flatMap { it.groupValues[1].split(' ', '/', '·') }
            .map(String::trim)
            .filter { it.length in 3..40 }
            .take(30)
            .toList()
    }

    private fun gitLogTerms(dir: Path): List<String> {
        if (!Files.isDirectory(dir.resolve(".git"))) return emptyList()
        return runCatching {
            val process = ProcessBuilder("git", "-C", dir.toString(), "log", "-50", "--pretty=%s")
                .redirectErrorStream(false)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) process.destroyForcibly()
            output.lineSequence()
                .flatMap { it.split(' ') }
                .map { it.trim() }
                // Only identifier-shaped words (contain an uppercase past index 0, a dot, or
                // an underscore) — plain prose from commit subjects is noise.
                .filter { word ->
                    word.length in 3..40 &&
                        (word.drop(1).any(Char::isUpperCase) || word.contains('.') || word.contains('_'))
                }
                .take(60)
                .toList()
        }.getOrDefault(emptyList())
    }

    private fun gitHeadStamp(dir: Path): String? {
        val head = dir.resolve(".git/HEAD")
        if (!head.isRegularFile()) return null
        val content = readSafely(head).trim()
        val refPath = content.removePrefix("ref: ")
        if (refPath == content) return content // detached HEAD: the hash itself
        val ref = dir.resolve(".git").resolve(refPath)
        return if (ref.isRegularFile()) readSafely(ref).trim() else content
    }

    private fun readSafely(file: Path): String =
        runCatching { file.readText() }.getOrDefault("")
}
