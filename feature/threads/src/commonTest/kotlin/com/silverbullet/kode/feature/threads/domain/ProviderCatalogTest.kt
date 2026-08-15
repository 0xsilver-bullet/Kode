package com.silverbullet.kode.feature.threads.domain

import com.silverbullet.kode.core.model.ModelSelection
import com.silverbullet.kode.core.model.ProviderAvailability
import com.silverbullet.kode.core.model.ServerProvider
import com.silverbullet.kode.core.model.ServerProviderContinuation
import com.silverbullet.kode.core.model.ServerProviderModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Behaviour ported from `isProviderAvailable` and
 * `getStartedThreadModelChangeBlockReason`.
 */
class ProviderCatalogTest {

    @Test
    fun `only enabled and available providers are offered`() {
        val catalog = listOf(
            provider("ok", enabled = true),
            provider("disabled", enabled = false),
            provider("gone", enabled = true, availability = ProviderAvailability.UNAVAILABLE),
        ).toCatalog()

        assertEquals(listOf("ok"), catalog.options.map { it.instanceId }.distinct())
    }

    @Test
    fun `absent availability counts as available`() {
        // Legacy servers omit the field entirely; treating that as unavailable
        // would empty the picker against every older environment.
        val catalog = listOf(provider("ok", enabled = true, availability = null)).toCatalog()
        assertTrue(catalog.options.isNotEmpty())
    }

    @Test
    fun `legacy models are kept but hidden unless asked for`() {
        // The sheet offers a "Show legacy models" toggle, so they must survive
        // into the catalog rather than being filtered out at build time.
        val catalog = listOf(
            provider(
                "p",
                enabled = true,
                models = listOf(model("current"), model("old", isLegacy = true)),
            ),
        ).toCatalog()

        assertEquals(listOf("current", "old"), catalog.options.map { it.model.slug })
        assertEquals(
            listOf("current"),
            catalog.offered(lockedDriver = null, includeLegacy = false).map { it.model.slug },
        )
        assertEquals(
            listOf("current", "old"),
            catalog.offered(lockedDriver = null, includeLegacy = true).map { it.model.slug },
        )
        assertTrue(catalog.hasLegacyModels)
    }

    @Test
    fun `a legacy model is never the default`() {
        val catalog = listOf(
            provider(
                "p",
                enabled = true,
                models = listOf(model("old", isDefault = true, isLegacy = true), model("current")),
            ),
        ).toCatalog()

        assertEquals(ModelSelection("p", "current"), catalog.defaultSelection())
    }

    @Test
    fun `offered models respect the driver lock`() {
        assertEquals(
            listOf("claude-1"),
            mixed.offered(lockedDriver = "claude", includeLegacy = false).map { it.instanceId },
        )
    }

    @Test
    fun `the default selection prefers the provider's default model`() {
        val catalog = listOf(
            provider(
                "p",
                enabled = true,
                models = listOf(model("first"), model("preferred", isDefault = true)),
            ),
        ).toCatalog()

        assertEquals(ModelSelection("p", "preferred"), catalog.defaultSelection())
    }

    @Test
    fun `the default selection falls back to the first model`() {
        val catalog = listOf(
            provider("p", enabled = true, models = listOf(model("only"))),
        ).toCatalog()

        assertEquals(ModelSelection("p", "only"), catalog.defaultSelection())
    }

    @Test
    fun `an empty catalog has no default`() {
        assertNull(emptyList<ServerProvider>().toCatalog().defaultSelection())
    }

    @Test
    fun `options group by provider preserving order`() {
        val catalog = listOf(
            provider("a", enabled = true, models = listOf(model("a1"), model("a2"))),
            provider("b", enabled = true, models = listOf(model("b1"))),
        ).toCatalog()

        val groups = catalog.byProvider()
        assertEquals(listOf("a", "b"), groups.map { it.instanceId })
        assertEquals(listOf("a1", "a2"), groups.first().options.map { it.model.slug })
    }

    // ----------------------------------------------------- change constraints

    private val catalog = listOf(
        provider("free", enabled = true, models = listOf(model("m1"), model("m2"))),
        provider(
            "strict",
            enabled = true,
            models = listOf(model("s1"), model("s2")),
            requiresNewThreadForModelChange = true,
        ),
    ).toCatalog()

    @Test
    fun `a change before the session starts is always allowed`() {
        assertNull(
            modelChangeBlockedReason(
                current = ModelSelection("strict", "s1"),
                next = ModelSelection("free", "m1"),
                catalog = catalog,
                hasStartedSession = false,
            ),
        )
    }

    @Test
    fun `selecting the model already in use is never blocked`() {
        assertNull(
            modelChangeBlockedReason(
                current = ModelSelection("strict", "s1"),
                next = ModelSelection("strict", "s1"),
                catalog = catalog,
                hasStartedSession = true,
            ),
        )
    }

    @Test
    fun `a permissive provider allows switching mid-conversation`() {
        // The rule is not "no changes after the first message" — only some
        // providers refuse.
        assertNull(
            modelChangeBlockedReason(
                current = ModelSelection("free", "m1"),
                next = ModelSelection("free", "m2"),
                catalog = catalog,
                hasStartedSession = true,
            ),
        )
    }

    @Test
    fun `switching away from a strict provider is blocked`() {
        assertNotNull(
            modelChangeBlockedReason(
                current = ModelSelection("strict", "s1"),
                next = ModelSelection("free", "m1"),
                catalog = catalog,
                hasStartedSession = true,
            ),
        )
    }

    @Test
    fun `switching into a strict provider is blocked`() {
        assertNotNull(
            modelChangeBlockedReason(
                current = ModelSelection("free", "m1"),
                next = ModelSelection("strict", "s1"),
                catalog = catalog,
                hasStartedSession = true,
            ),
        )
    }

    @Test
    fun `changing model within a strict provider is still blocked`() {
        assertNotNull(
            modelChangeBlockedReason(
                current = ModelSelection("strict", "s1"),
                next = ModelSelection("strict", "s2"),
                catalog = catalog,
                hasStartedSession = true,
            ),
        )
    }

    @Test
    fun `a thread with no current selection is not blocked`() {
        assertNull(
            modelChangeBlockedReason(
                current = null,
                next = ModelSelection("strict", "s1"),
                catalog = catalog,
                hasStartedSession = true,
            ),
        )
    }

    // -------------------------------------------------------- the driver lock

    private val mixed = listOf(
        provider("claude-1", enabled = true, driver = "claude", models = listOf(model("sonnet"))),
        provider("oc-1", enabled = true, driver = "opencode", models = listOf(model("gpt"))),
    ).toCatalog()

    @Test
    fun `an unstarted thread is not locked to a driver`() {
        assertNull(lockedDriver(hasStarted = false, sessionProviderName = "claude", currentDriver = "claude"))
    }

    @Test
    fun `a started thread locks to its session provider`() {
        assertEquals(
            "claude",
            lockedDriver(hasStarted = true, sessionProviderName = "claude", currentDriver = "opencode"),
        )
    }

    @Test
    fun `without a session provider the lock falls back to the current driver`() {
        assertEquals(
            "claude",
            lockedDriver(hasStarted = true, sessionProviderName = null, currentDriver = "claude"),
        )
    }

    @Test
    fun `a locked thread refuses a model from another driver`() {
        // This is the bug: a Claude thread could be re-pointed at OpenCode.
        val reason = modelChangeBlockedReason(
            current = ModelSelection("claude-1", "sonnet"),
            next = ModelSelection("oc-1", "gpt"),
            catalog = mixed,
            hasStartedSession = true,
            lockedDriver = "claude",
        )
        assertNotNull(reason)
        assertTrue(reason.contains("claude"))
    }

    @Test
    fun `a locked thread still allows models from the same driver`() {
        val catalog = listOf(
            provider(
                "claude-1",
                enabled = true,
                driver = "claude",
                models = listOf(model("sonnet"), model("opus")),
            ),
        ).toCatalog()

        assertNull(
            modelChangeBlockedReason(
                current = ModelSelection("claude-1", "sonnet"),
                next = ModelSelection("claude-1", "opus"),
                catalog = catalog,
                hasStartedSession = true,
                lockedDriver = "claude",
            ),
        )
    }

    @Test
    fun `an unlocked thread may switch drivers freely`() {
        assertNull(
            modelChangeBlockedReason(
                current = ModelSelection("claude-1", "sonnet"),
                next = ModelSelection("oc-1", "gpt"),
                catalog = mixed,
                hasStartedSession = false,
                lockedDriver = null,
            ),
        )
    }

    @Test
    fun `instances in different continuation groups cannot take over`() {
        val catalog = listOf(
            provider("a", enabled = true, continuationGroupKey = "one", models = listOf(model("m"))),
            provider("b", enabled = true, continuationGroupKey = "two", models = listOf(model("m"))),
        ).toCatalog()

        assertNotNull(
            modelChangeBlockedReason(
                current = ModelSelection("a", "m"),
                next = ModelSelection("b", "m"),
                catalog = catalog,
                hasStartedSession = true,
                lockedDriver = "claude",
            ),
        )
    }

    @Test
    fun `instances sharing a continuation group may take over`() {
        val catalog = listOf(
            provider("a", enabled = true, continuationGroupKey = "one", models = listOf(model("m"))),
            provider("b", enabled = true, continuationGroupKey = "one", models = listOf(model("n"))),
        ).toCatalog()

        assertNull(
            modelChangeBlockedReason(
                current = ModelSelection("a", "m"),
                next = ModelSelection("b", "n"),
                catalog = catalog,
                hasStartedSession = true,
                lockedDriver = "claude",
            ),
        )
    }

    // ----------------------------------------------------------------- builders

    private fun provider(
        instanceId: String,
        enabled: Boolean,
        availability: String? = ProviderAvailability.AVAILABLE,
        models: List<ServerProviderModel> = listOf(model("m1")),
        requiresNewThreadForModelChange: Boolean = false,
        driver: String = "claude",
        continuationGroupKey: String? = null,
    ) = ServerProvider(
        instanceId = instanceId,
        driver = driver,
        continuation = continuationGroupKey?.let { ServerProviderContinuation(it) },
        displayName = instanceId.uppercase(),
        enabled = enabled,
        availability = availability,
        models = models,
        requiresNewThreadForModelChange = requiresNewThreadForModelChange,
    )

    private fun model(
        slug: String,
        isDefault: Boolean = false,
        isLegacy: Boolean = false,
    ) = ServerProviderModel(
        slug = slug,
        name = "Model $slug",
        isDefault = isDefault,
        isLegacy = isLegacy,
    )
}
