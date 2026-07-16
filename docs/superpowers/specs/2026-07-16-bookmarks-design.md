# Bookmarks (Phase 10) — Design

Date: 2026-07-16
Status: Approved
Parent specs: 2026-07-16-config-sets-design.md (set names, toolbar), 2026-07-16-quick-filter-design.md (visibility pipeline)

## Purpose

Lightweight code bookmarks stored as comments in the source itself. A toolbar
button inserts a marker comment at the caret; every marker in a
filter-visible file appears under a Bookmarks node at the bottom of the tree,
optionally scoped to a config set. No sidecar state: the code IS the store,
so bookmarks travel with the file through git.

## Comment syntax

A bookmark is a single line whose first non-whitespace content is a marker
(case-insensitive):

```
// megatron: "title"
// megatron/setname: "title"
# megatron: "title"              (CMake files)
# megatron/setname: "title"
```

- Comment prefix: `//` or `#`, both accepted by the parser in any file.
- Set name: everything between `/` and `:`, trimmed; compared
  case-insensitively against the active set (ConfigSetManager convention).
  Absent → the bookmark shows in every set.
- Title: the text between the FIRST pair of double quotes on the line.
  A marker line with no parsable quoted title is ignored (it is just a
  comment). An empty title (`""`) — the state right after insertion — is
  displayed as `(untitled)`.
- A marker mid-line (after code) is NOT a bookmark; only lines that start
  (after indentation) with the comment prefix + marker count.

Parsing is a pure function in a new `Bookmark.kt`:
`parseBookmarks(text: String): List<Bookmark>` where
`Bookmark(line: Int /*0-based*/, setName: String?, title: String)`.

## Toolbar action (replaces Refresh)

New `BookmarkAction` (bookmark icon, e.g. `AllIcons.Nodes.Bookmark`) takes
Refresh's slot as the FIRST toolbar action. Refresh moves to the tree
right-click menu (`MegatronTreePopupGroup`), same behavior as before
(`rootDir.refresh` + invalidate).

On click, `BookmarkAction`:

- Uses the active text editor (`FileEditorManager.selectedTextEditor`); the
  action's `update()` disables it when there is no text editor.
- Inserts a new line ABOVE the caret line, copying that line's leading
  whitespace: `<indent><prefix> megatron/<activeSet>: ""` — prefix `#` for
  CMake files (name `CMakeLists.txt` or extension `cmake`), else `//`;
  active set from `ConfigSetManager.effectiveSet()`.
- Places the caret between the quotes and focuses the editor, so the user
  types the title in place. No dialog.
- Runs inside a `WriteCommandAction` (single undo step; read-only files get
  the platform's standard handling).
- The insertion text + caret offset are computed by a pure helper
  (testable): given the caret line's text, file-type flag, and set name,
  return (line text, caret column).

## Discovery: BookmarkScanner

Panel-owned `BookmarkScanner`, following the FilterEngine caching pattern:

- `bookmarksIn(file: VirtualFile): List<Bookmark>` — per-file cache keyed on
  `(path, modificationStamp)`; on miss, load text and run `parseBookmarks`.
- Text source: prefer the in-memory Document
  (`FileDocumentManager.getCachedDocument`) with ITS modification stamp when
  the file is open — unsaved edits included — else disk bytes
  (`contentsToByteArray`, file charset).
- Files larger than 1 MB are skipped (empty result) so a stray huge file
  cannot stall tree builds.
- Read failures: log + empty result (FilterEngine.loadText pattern).
- No explicit eviction: entries for deleted/renamed files simply stop being
  queried. (Bounded by files seen; fine at project scale.)

## Bookmarks tree node

- Root `FileNode.getChildren()` appends a `BookmarksRootNode` AFTER the
  normal children in ALL THREE view modes (Tree, Flat, Folders) — i.e. the
  node is pinned at the bottom.
- Children: walk the visible files (the existing `collectVisibleFiles`
  walk — so group filters, quick filter, and CMake gate are already
  applied), collect `scanner.bookmarksIn(file)`, and keep bookmarks where
  `setName == null || setName.equals(activeSet, ignoreCase = true)`.
- Zero surviving bookmarks → the Bookmarks node is NOT added (hidden when
  empty).
- Each `BookmarkNode` presents: title (or `(untitled)`), bookmark icon,
  location string `relative/path:line` (1-based for display), sorted by
  title (case-insensitive), ties by path then line.
- Activation (double-click / Enter) opens the file at the bookmark's line:
  `OpenFileDescriptor(project, file, line, 0)`. `ProjectFilesPanel`'s
  `openSelection`/double-click handler learns to handle `BookmarkNode`
  alongside `FileNode`. Ctrl+double-click pair-opening does not apply to
  bookmark nodes (plain open).

## Live updating

`VfsChangeWatcher` deliberately ignores content-only changes, so typing or
deleting a marker would not refresh the tree. Add a debounced document
listener (`EditorFactory.eventMulticaster.addDocumentListener`, disposed with
the panel; 300 ms `SingleAlarm`, the quick-filter pattern) that invalidates
the tree model when a changed document:

- contains the marker word `megatron` (cheap contains-scan, case-insensitive), OR
- belongs to a file whose last scan produced bookmarks (so deleting the last
  marker updates the tree too — the scanner exposes `hadBookmarks(path)`).

Normal typing in marker-free files costs one string scan per debounce tick
and triggers no rebuild.

## Error handling

- Scanner read failure → log + no bookmarks for that file.
- Malformed marker lines → ignored.
- Read-only file on insert → platform read-only dialog via
  WriteCommandAction; no custom handling.
- Stale line numbers only possible for files changed outside the IDE without
  a VFS event; `OpenFileDescriptor` tolerates out-of-range lines, and the
  next content change or full refresh rescans.
- No open editor → action disabled, not an error.

## Testing

Plain unit tests (existing style, no IDE fixtures):

- `BookmarkParserTest`: both prefixes, with/without set name,
  case-insensitivity of marker and set, indentation, empty title, missing or
  unterminated quotes ignored, marker mid-line rejected, multiple bookmarks
  per file, 0-based line numbers.
- Visibility predicate: null set matches any active set; named set matches
  case-insensitively; mismatch hidden.
- Insertion helper: prefix by file type, indent copied from caret line,
  returned caret column between the quotes, set name stamped.

Scanner caching and tree wiring stay thin over these pure functions.

## Out of scope

- Editing/renaming bookmarks from the tree (edit the comment instead).
- Deleting bookmarks from the tree.
- Bookmark ordering beyond title sort; drag-and-drop into virtual folders.
- Non-C/C++/CMake comment styles beyond `//` and `#`.
