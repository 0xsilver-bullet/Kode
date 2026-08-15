package com.silverbullet.kode.feature.threads.domain

import com.silverbullet.kode.core.model.ModelCapabilities
import com.silverbullet.kode.core.model.ProviderOptionChoice
import com.silverbullet.kode.core.model.ProviderOptionDescriptor
import com.silverbullet.kode.core.model.ProviderOptionSelection
import com.silverbullet.kode.core.model.ServerProviderModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonPrimitive

/**
 * Behaviour ported from `getProviderOptionDescriptors`, `selectableChoices` and
 * `applyProviderOptionSelection`.
 */
class ProviderOptionsTest {

    private val effort = ProviderOptionDescriptor(
        id = "effort",
        label = "Reasoning",
        type = ProviderOptionDescriptor.SELECT,
        options = listOf(
            ProviderOptionChoice("low", "Low"),
            ProviderOptionChoice("medium", "Medium", isDefault = true),
            ProviderOptionChoice("high", "High"),
            ProviderOptionChoice("ultracode", "Ultracode"),
            ProviderOptionChoice("ultrathink", "Ultrathink"),
        ),
        promptInjectedValues = listOf("ultrathink"),
    )

    private val fastMode = ProviderOptionDescriptor(
        id = "fastMode",
        label = "Fast mode",
        type = ProviderOptionDescriptor.BOOLEAN,
    )

    private val model = ServerProviderModel(
        slug = "m",
        name = "Model",
        capabilities = ModelCapabilities(optionDescriptors = listOf(effort, fastMode)),
    )

    @Test
    fun `a model with no capabilities advertises no options`() {
        assertTrue(ServerProviderModel("m", "Model").resolveOptionDescriptors(null).isEmpty())
    }

    @Test
    fun `the thread's stored selection overrides the descriptor default`() {
        val resolved = model.resolveOptionDescriptors(
            listOf(ProviderOptionSelection("effort", JsonPrimitive("high"))),
        )

        assertEquals(JsonPrimitive("high"), resolved.first { it.id == "effort" }.currentValue)
    }

    @Test
    fun `an unset select falls back to the choice marked default`() {
        val resolved = model.resolveOptionDescriptors(null)
        assertEquals(
            JsonPrimitive("medium"),
            resolved.first { it.id == "effort" }.currentValueOrDefault(),
        )
    }

    @Test
    fun `prompt-injected and hidden choices are not offered`() {
        // `ultrathink` is injected by the provider; `ultracode` is a workflow
        // trigger rather than a reasoning level.
        val offered = effort.selectableChoices().map { it.id }
        assertEquals(listOf("low", "medium", "high"), offered)
    }

    @Test
    fun `the current label reflects the chosen value`() {
        val resolved = model.resolveOptionDescriptors(
            listOf(ProviderOptionSelection("effort", JsonPrimitive("high"))),
        )
        assertEquals("High", resolved.first { it.id == "effort" }.currentLabel())
    }

    @Test
    fun `a boolean only contributes a label when switched on`() {
        assertNull(fastMode.currentLabel())
        assertEquals("Fast mode", fastMode.copy(currentValue = JsonPrimitive(true)).currentLabel())
    }

    @Test
    fun `active labels summarise what is switched on`() {
        val resolved = model.resolveOptionDescriptors(
            listOf(
                ProviderOptionSelection("effort", JsonPrimitive("low")),
                ProviderOptionSelection("fastMode", JsonPrimitive(true)),
            ),
        )
        assertEquals(listOf("Low", "Fast mode"), resolved.activeOptionLabels())
    }

    @Test
    fun `applying a select writes the whole selection list`() {
        val resolved = model.resolveOptionDescriptors(null)
        val next = resolved.applyOptionSelection("effort", JsonPrimitive("high"))

        // Every descriptor with a resolved value is carried, not just the one
        // that changed — the contract stores options wholesale.
        assertEquals(JsonPrimitive("high"), next?.first { it.id == "effort" }?.value)
    }

    @Test
    fun `applying a boolean writes it through`() {
        val resolved = model.resolveOptionDescriptors(null)
        val next = resolved.applyOptionSelection("fastMode", JsonPrimitive(true))
        assertEquals(JsonPrimitive(true), next?.first { it.id == "fastMode" }?.value)
    }

    @Test
    fun `a value the model does not advertise is rejected`() {
        val resolved = model.resolveOptionDescriptors(null)
        // A stale picker must not be able to write a value the model refuses.
        assertNull(resolved.applyOptionSelection("effort", JsonPrimitive("nonsense")))
        assertNull(resolved.applyOptionSelection("unknown", JsonPrimitive("low")))
        assertNull(resolved.applyOptionSelection("effort", JsonPrimitive(true)))
    }

    @Test
    fun `an unknown descriptor type offers nothing but does not break`() {
        // A newer server may add a type this build has never seen.
        val exotic = ProviderOptionDescriptor(id = "x", label = "X", type = "slider")
        assertTrue(exotic.selectableChoices().isEmpty())
        assertNull(listOf(exotic).applyOptionSelection("x", JsonPrimitive("1")))
    }
}
