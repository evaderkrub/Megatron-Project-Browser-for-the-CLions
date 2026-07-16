package com.daverobins.projectfilesbrowser

import com.intellij.icons.AllIcons
import com.intellij.ide.projectView.PresentationData
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.treeStructure.SimpleNode

/** The pinned "Bookmarks" group shown after all other root children. */
class BookmarksRootNode(
    private val project: Project,
    parent: SimpleNode,
    private val bookmarks: List<Pair<VirtualFile, Bookmark>>,
    private val rootPath: String,
) : SimpleNode(project, parent) {

    override fun getChildren(): Array<SimpleNode> =
        bookmarks
            .sortedWith(
                compareBy(
                    { it.second.title.lowercase() },
                    { it.first.path.lowercase() },
                    { it.second.line },
                ),
            )
            .map { (file, bookmark) -> BookmarkNode(project, this, file, bookmark, rootPath) }
            .toTypedArray()

    override fun update(presentation: PresentationData) {
        presentation.presentableText = "Bookmarks"
        presentation.setIcon(AllIcons.Nodes.BookmarkGroup)
    }

    override fun getEqualityObjects(): Array<Any> = arrayOf(EQUALITY_KEY)

    companion object {
        private const val EQUALITY_KEY = "megatron.bookmarksRoot"
    }
}

/** One bookmark leaf: title, grey `path:line` location, navigates on activation. */
class BookmarkNode(
    project: Project,
    parent: SimpleNode,
    val file: VirtualFile,
    val bookmark: Bookmark,
    private val rootPath: String,
) : SimpleNode(project, parent) {

    override fun getChildren(): Array<SimpleNode> = NO_CHILDREN

    override fun update(presentation: PresentationData) {
        presentation.presentableText = bookmark.title.ifBlank { UNTITLED_LABEL }
        presentation.locationString =
            "${file.path.removePrefix("$rootPath/")}:${bookmark.line + 1}"
        presentation.setIcon(AllIcons.Nodes.Bookmark)
    }

    override fun getEqualityObjects(): Array<Any> =
        arrayOf(EQUALITY_KEY, file, bookmark.line, bookmark.title)

    companion object {
        const val UNTITLED_LABEL = "(untitled)"
        private const val EQUALITY_KEY = "megatron.bookmark"
    }
}
