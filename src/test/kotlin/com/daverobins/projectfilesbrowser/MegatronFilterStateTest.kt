package com.daverobins.projectfilesbrowser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MegatronFilterStateTest {

    @Test
    fun groupsAreEnabledByDefault() {
        val state = MegatronFilterState()
        assertTrue(state.isEnabled("NeverSeenBefore"))
    }

    @Test
    fun disableThenReenableRoundTrips() {
        val state = MegatronFilterState()
        state.setEnabled("Docs", false)
        assertFalse(state.isEnabled("Docs"))
        state.setEnabled("Docs", true)
        assertTrue(state.isEnabled("Docs"))
    }

    @Test
    fun persistedStateHoldsOnlyDisabledNames() {
        val state = MegatronFilterState()
        state.setEnabled("A", false)
        state.setEnabled("B", true)
        assertEquals(setOf("A"), state.state.disabledGroups)
    }

    @Test
    fun loadStateReplacesCurrent() {
        val state = MegatronFilterState()
        val incoming = MegatronFilterState.State().apply { disabledGroups = mutableSetOf("X") }
        state.loadState(incoming)
        assertFalse(state.isEnabled("X"))
        assertTrue(state.isEnabled("Y"))
    }
}
