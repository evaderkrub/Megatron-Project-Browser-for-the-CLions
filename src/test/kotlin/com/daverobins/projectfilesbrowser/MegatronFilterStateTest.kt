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
    fun viewModeDefaultsToTree() {
        assertEquals(ViewMode.TREE, MegatronFilterState().getViewMode())
    }

    @Test
    fun viewModeRoundTrips() {
        val state = MegatronFilterState()
        state.setViewMode(ViewMode.FOLDERS)
        val restored = MegatronFilterState()
        restored.loadState(state.state)
        assertEquals(ViewMode.FOLDERS, restored.getViewMode())
    }

    @Test
    fun getStateReturnsDefensiveCopyOfViewMode() {
        val state = MegatronFilterState()
        val snapshot = state.state
        state.setViewMode(ViewMode.FLAT)
        assertEquals(ViewMode.TREE, snapshot.viewMode)
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

    @Test
    fun testActiveSetDefaultsToDefault() {
        assertEquals("default", MegatronFilterState().getActiveSet())
    }

    @Test
    fun testActiveSetRoundTrips() {
        val state = MegatronFilterState()
        state.setActiveSet("gui-work")
        val restored = MegatronFilterState()
        restored.loadState(state.state)
        assertEquals("gui-work", restored.getActiveSet())
    }

    @Test
    fun testGetStateReturnsDefensiveCopyOfActiveSet() {
        val state = MegatronFilterState()
        val snapshot = state.state
        state.setActiveSet("other")
        assertEquals("default", snapshot.activeSet)
    }
}
