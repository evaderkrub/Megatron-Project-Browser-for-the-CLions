# Virtual Folders (Phase 5) — Design

Date: 2026-07-16
Status: Approved
Parent spec: 2026-07-15-clion-project-files-browser-design.md (roadmap item 5)

## Purpose

Visual-Studio-style virtual folders: manually created named folders in the Megatron
tool window, into which the user assigns files by context menu or drag-and-drop,
independent of the on-disk layout. The layout is a plain-text project file
(`megatron.folders`) — human-readable, VCS-shareable, hand-editable.

## View Shape

- A third view mode, **Folder View**, joins Tree and Flat. The three are mutually
  exclusive: `MegatronFilterState.flatMode: Boolean` is replaced by
  `viewMode: TREE | FLAT | FOLDERS` (enum, default TREE). The old per-user
  `flatMode` setting resets once — accepted.
- Toolbar: a Folder View toggle button next to the Flat View toggle. The two
  toggles behave radio-style: turning one on turns the other off; both off = tree.
- Folder view layout, top to bottom:
  - The user's virtual folders (nested), sorted alphabetically (case-insensitive)
    at each level, folders before files.
  - A pinned **`<Unassigned>`** node containing the normal filtered directory
    tree MINUS files assigned to any virtual folder. Directories whose visible
    files are all assigned disappear (normal derived-visibility rule).
- Files inside virtual folders render like flat mode: file name + grey
  project-relative path (`locationString`), file-type icon.
- Empty virtual folders still render (so the user can create, then fill).
- Double-click / Enter opens files exactly as in the other views.

## The `megatron.folders` File

Project-root sibling of `megatron.filters`:

```
# megatron.folders
Core/
  src/engine.cpp
  src/engine.h
Core/Math/
  src/vec.h
Platform/
  src/win32_main.cpp
```

Format rules:

- A non-blank, non-comment line ending in `/` declares a folder. Nesting uses `/`
  separators (`Core/Math/`); missing parents are auto-created.
- Any other non-blank line is a project-relative file path assigned to the most
  recently declared folder. A file line before any folder line is ignored
  (malformed).
- `#` starts a comment line; blank lines and leading/trailing whitespace ignored;
  indentation is cosmetic only.
- Backslashes are normalized to `/` on parse. Path comparison is
  case-insensitive (consistent with the filter engine).
- One folder per file: duplicate assignments → last one wins.
- Unparseable lines are silently skipped (consistent with `megatron.filters`).

**Single source of truth:** every UI mutation (create/rename/delete folder,
assign/unassign, drag) goes model → serializer → write the file (in a write
action). Hand edits to the file auto-reload through the VFS watcher. The
serializer writes folders and their files sorted (folders alphabetical at each
level, file paths alphabetical within a folder) with two-space indentation for
file lines, so diffs stay clean. Comments in a hand-edited file are NOT preserved
across a UI rewrite — accepted.

- No `megatron.folders` file, or no folders declared → folder view shows only the
  `<Unassigned>` tree (which then equals the normal tree).

## Filtering Interaction

- Group filters and the CMake gate apply everywhere, including inside virtual
  folders: a file hidden by current filters disappears from its folder but KEEPS
  its assignment entry (it returns when visible again).
- Entries for files that don't exist on disk are not rendered and never
  auto-pruned. A file renamed/moved on disk falls back to `<Unassigned>` under
  its new path; the folders file is not auto-rewritten (documented limitation).

## Interactions

Context menu on the Megatron tree (popup menu, all view modes unless noted):

- On one or more selected FILES: **Add to Folder ▸** — submenu listing all
  existing folders (nested paths shown as `Core/Math`) plus **New Folder…**
  (prompts for a name, creates at top level, assigns). Available in every view
  mode. **Remove from Folder** — shown when at least one selected file is
  assigned; removes the assignment(s).
- On a VIRTUAL FOLDER node (folder view only): **New Folder…** (top-level),
  **New Subfolder…**, **Rename…**, **Delete** (children folders deleted too;
  all their files return to `<Unassigned>`; simple confirmation dialog, since
  the file is typically in VCS).
- Folder name validation: non-empty, no `/`, no leading/trailing whitespace,
  must not duplicate a sibling (case-insensitive). Invalid → error in the dialog.

Drag-and-drop (folder view only):

- Drag one or more selected file rows onto a virtual folder node → assign; onto
  the `<Unassigned>` node → unassign. Drop anywhere else → no-op.
- Directory nodes and virtual folder nodes are not draggable in this phase.
- Implemented with IntelliJ platform DnD support; the exact API surface is
  javap-verified against the CLion 2026.1.1 distribution during planning (same
  procedure as the OCWorkspace research in phase 4).

## Components

New:

- **`FolderLayout.kt`** — pure model + parser + serializer. Model: folder tree
  (name, children, ordered), `fileToFolder` map (normalized lowercase relative
  path → folder path). `parseFoldersFile(text): FolderLayout`,
  `FolderLayout.serialize(): String`, and pure mutation helpers (add/rename/
  delete folder, assign/unassign file) returning new layouts. Fully
  unit-testable.
- **`FolderLayoutStore.kt`** — per-panel cache keyed on the folders file's
  `modificationStamp` (same pattern as `FilterEngine.groups()`), plus mutation
  operations that apply a `FolderLayout` change and rewrite `megatron.folders`
  inside a write action (creating the file on first mutation).
- **`VirtualFolderNode` / `UnassignedNode`** — SimpleNodes in the tree
  structure. `UnassignedNode` reuses the existing `FileNode` children logic with
  an assigned-files exclusion predicate.
- **Context-menu actions + DnD handler** — action group registered as the
  tree's popup; DnD wiring in the panel.

Modified:

- **`MegatronFilterState`** — `viewMode` enum replaces `flatMode`; synchronized
  accessors; defensive-copy `getState()`.
- **`FilteredTreeStructure`** — root branches on view mode (TREE / FLAT /
  FOLDERS).
- **`VfsChangeWatcher`** — `megatron.folders` joins `megatron.filters` in the
  always-relevant project-file check.
- **`ProjectFilesPanel`** — second toggle button, popup menu installation, DnD
  wiring, store construction.
- **`build.gradle.kts`** — version 0.5.0.

## Error Handling

- Malformed folders file → parse salvages what it can, skips bad lines; never
  errors at the user.
- Mutations when the file is read-only/locked → notification balloon with the
  IO error message; model unchanged.
- Folder view with a huge unassigned tree behaves exactly like today's tree view
  (same walk, same derived visibility).

## Testing

- `FolderLayout`: parse/serialize round-trips; comments/blank/indent handling;
  backslash + case normalization; duplicate assignment last-wins; file line
  before any folder ignored; auto-created parents; mutation helpers (rename
  cascades to descendants' paths, delete returns files to unassigned).
- Structure tests (platform, fake-gate/fixture patterns from phase 4): folder
  view shows folders alphabetically + `<Unassigned>` exclusion; assigned file
  hidden by a filter disappears from its folder; empty folder renders; missing
  file entry not rendered; folder view with no file = plain tree under
  `<Unassigned>`.
- `FolderLayoutStore`: mutations produce the expected file text on disk.
- Watcher: `megatron.folders` events are relevant (create/edit/delete/rename).
- State: viewMode round-trip persistence.
- Sandbox checklist: context-menu create/assign/rename/delete, drag files onto
  folders and `<Unassigned>`, hand-edit the file and watch the tree update,
  filters + CMake gate applying inside folders, VCS-diff readability.
