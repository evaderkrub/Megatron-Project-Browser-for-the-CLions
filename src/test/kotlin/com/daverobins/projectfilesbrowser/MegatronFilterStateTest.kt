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

    @Test
    fun flatModeDefaultsToFalse() {
        assertFalse(MegatronFilterState().isFlatMode())
    }

    @Test
    fun flatModeRoundTrips() {
        val state = MegatronFilterState()
        state.setFlatMode(true)
        assertTrue(state.isFlatMode())
        state.setFlatMode(false)
        assertFalse(state.isFlatMode())
    }

    @Test
    fun getStateCopiesFlatMode() {
        val state = MegatronFilterState()
        state.setFlatMode(true)
        val snapshot = state.state
        assertTrue(snapshot.flatMode)
        state.setFlatMode(false)
        assertTrue(snapshot.flatMode) // snapshot is a copy, unaffected by later changes
    }

    @Test
    fun cmakeGateDefaultsToFalse() {
        assertFalse(MegatronFilterState().isCmakeGateEnabled())
    }

    @Test
    fun cmakeGateRoundTrips() {
        val state = MegatronFilterState()
        state.setCmakeGateEnabled(true)
        assertTrue(state.isCmakeGateEnabled())
        state.setCmakeGateEnabled(false)
        assertFalse(state.isCmakeGateEnabled())
    }

    @Test
    fun getStateCopiesCmakeGate() {
        val state = MegatronFilterState()
        state.setCmakeGateEnabled(true)
        val snapshot = state.state
        assertTrue(snapshot.cmakeGateEnabled)
        state.setCmakeGateEnabled(false)
        assertTrue(snapshot.cmakeGateEnabled)
    }
}
