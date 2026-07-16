package com.daverobins.projectfilesbrowser

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class FilterEngineGateTest : BasePlatformTestCase() {

    private class FakeGate(
        var active: Boolean,
        val modelPaths: MutableSet<String> = mutableSetOf(),
    ) : ProjectModelGate {
        override fun isActive(): Boolean = active
        override fun isInModel(file: VirtualFile): Boolean = file.path in modelPaths
    }

    fun testGateOnAndActiveNarrowsVisibility() {
        val inModel = myFixture.addFileToProject("g1/src/in_model.cpp", "").virtualFile
        val outOfModel = myFixture.addFileToProject("g1/src/generated.cpp", "").virtualFile
        val rootDir = requireNotNull(myFixture.findFileInTempDir("g1"))
        val gate = FakeGate(active = true, modelPaths = mutableSetOf(inModel.path))
        val engine = FilterEngine(project, rootDir, gate)

        val state = MegatronFilterState.getInstance(project)
        state.setCmakeGateEnabled(true)
        try {
            assertTrue(engine.isFileVisible(inModel))
            assertFalse(engine.isFileVisible(outOfModel))
        } finally {
            state.setCmakeGateEnabled(false)
        }
    }

    fun testGateOnButInactiveIsNoOp() {
        val anyFile = myFixture.addFileToProject("g2/src/main.cpp", "").virtualFile
        val rootDir = requireNotNull(myFixture.findFileInTempDir("g2"))
        val engine = FilterEngine(project, rootDir, FakeGate(active = false))

        val state = MegatronFilterState.getInstance(project)
        state.setCmakeGateEnabled(true)
        try {
            assertTrue(engine.isFileVisible(anyFile))
        } finally {
            state.setCmakeGateEnabled(false)
        }
    }

    fun testGateOffIgnoresModel() {
        val anyFile = myFixture.addFileToProject("g3/src/main.cpp", "").virtualFile
        val rootDir = requireNotNull(myFixture.findFileInTempDir("g3"))
        // active gate, file NOT in model — but the toggle is off
        val engine = FilterEngine(project, rootDir, FakeGate(active = true))

        assertFalse(MegatronFilterState.getInstance(project).isCmakeGateEnabled())
        assertTrue(engine.isFileVisible(anyFile))
    }

    fun testGroupVisibilityStillGatesFirst() {
        val mdFile = myFixture.addFileToProject("g4/readme.md", "").virtualFile
        val rootDir = requireNotNull(myFixture.findFileInTempDir("g4"))
        // even in-model files must pass group/default filtering
        val engine = FilterEngine(project, rootDir, FakeGate(active = true, modelPaths = mutableSetOf(mdFile.path)))

        val state = MegatronFilterState.getInstance(project)
        state.setCmakeGateEnabled(true)
        try {
            assertFalse("md fails built-in defaults regardless of model membership", engine.isFileVisible(mdFile))
        } finally {
            state.setCmakeGateEnabled(false)
        }
    }
}
