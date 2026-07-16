package com.daverobins.projectfilesbrowser

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.ReadonlyStatusHandler
import com.intellij.openapi.wm.IdeFocusManager

/**
 * Inserts a bookmark comment above the caret line of the active editor, stamps
 * the active set, and leaves the caret between the quotes so the title is
 * typed in place. Disabled when no text editor is open.
 */
class BookmarkAction(
    private val project: Project,
    private val sets: ConfigSetManager,
) : AnAction("Add Bookmark", "Insert a bookmark comment at the caret in the active editor", AllIcons.Nodes.Bookmark) {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = FileEditorManager.getInstance(project).selectedTextEditor != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return
        val document = editor.document
        val fileName = FileDocumentManager.getInstance().getFile(document)?.name ?: ""
        val line = editor.caretModel.logicalPosition.line
        val lineStart: Int
        val lineText: String
        if (line < document.lineCount) {
            lineStart = document.getLineStartOffset(line)
            lineText = document.getText(TextRange(lineStart, document.getLineEndOffset(line)))
        } else {
            // Caret on the virtual line past the end (empty document or trailing caret).
            lineStart = document.textLength
            lineText = ""
        }
        if (!ReadonlyStatusHandler.ensureDocumentWritable(project, document)) return
        val insertion = bookmarkInsertion(lineText, fileName, sets.effectiveSet())
        WriteCommandAction.runWriteCommandAction(project) {
            document.insertString(lineStart, insertion.lineText + "\n")
            editor.caretModel.moveToOffset(lineStart + insertion.caretColumn)
        }
        IdeFocusManager.getInstance(project).requestFocus(editor.contentComponent, true)
    }
}
