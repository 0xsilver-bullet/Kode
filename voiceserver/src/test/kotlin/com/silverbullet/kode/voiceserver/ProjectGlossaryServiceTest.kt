package com.silverbullet.kode.voiceserver

import com.silverbullet.kode.voiceserver.glossary.ProjectGlossaryService
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProjectGlossaryServiceTest {

    private fun fixtureProject(): Path {
        val root = Files.createTempDirectory("kode-voice-glossary")
        val project = root.resolve("AwesomeApp").createDirectories()
        project.resolve("gradle").createDirectories()
        project.resolve("gradle/libs.versions.toml").writeText(
            """
            [libraries]
            ktor-client-core = { module = "io.ktor:ktor-client-core", version = "3.5.2" }
            koin-core = { module = "io.insert-koin:koin-core", version = "4.2.2" }
            """.trimIndent(),
        )
        project.resolve("README.md").writeText("# AwesomeApp\n## EnvironmentSupervisor design\n")
        project.resolve("src/commonMain/kotlin").createDirectories()
        project.resolve("src/commonMain/kotlin/EnvironmentSupervisor.kt").writeText("class EnvironmentSupervisor")
        project.resolve("src/commonMain/kotlin/ThreadFeed.kt").writeText("class ThreadFeed")
        return project
    }

    @Test
    fun `builds keyterms from tree, build files and readme`() = runTest {
        val project = fixtureProject()
        val service = ProjectGlossaryService(allowedRoots = listOf(project.parent))

        val glossary = service.glossaryFor(project.toString())

        assertNotNull(glossary)
        assertEquals("AwesomeApp", glossary.projectName)
        assertTrue(glossary.keyterms.contains("EnvironmentSupervisor"), glossary.keyterms.toString())
        assertTrue(glossary.keyterms.any { it.startsWith("ktor") }, glossary.keyterms.toString())
        assertTrue(glossary.contextSummary.contains("AwesomeApp"))
        // Deepgram budget respected.
        assertTrue(glossary.keyterms.size <= 80)
    }

    @Test
    fun `refuses directories outside allowed roots`() = runTest {
        val project = fixtureProject()
        val service = ProjectGlossaryService(allowedRoots = listOf(Files.createTempDirectory("elsewhere")))
        assertNull(service.glossaryFor(project.toString()))
    }

    @Test
    fun `null and missing directories yield no glossary`() = runTest {
        val service = ProjectGlossaryService(allowedRoots = listOf(Path.of("/")))
        assertNull(service.glossaryFor(null))
        assertNull(service.glossaryFor("/definitely/not/here"))
    }

    @Test
    fun `caches per directory`() = runTest {
        val project = fixtureProject()
        val service = ProjectGlossaryService(allowedRoots = listOf(project.parent))
        val first = service.glossaryFor(project.toString())
        val second = service.glossaryFor(project.toString())
        // Not a git repo → TTL-cached instance identity.
        assertTrue(first === second)
    }
}
