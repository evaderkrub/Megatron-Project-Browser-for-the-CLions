# Flat List Mode (Phase 3) — Design

Date: 2026-07-16
Status: Approved
Parent spec: 2026-07-15-clion-project-files-browser-design.md (roadmap item 3)

## Purpose

A toolbar toggle that switches the Megatron view between the directory tree and a
flat list of all visible files. Same filtering in both modes (filter groups,
fallback, noise-dir exclusion); flat mode just changes presentation: every visible
file as one row, name first, dimmed parent path after.

## Behavior

- **Toggle**: a checkbox-style toolbar ToggleAction "Flat View" (icon
  `AllIcons.Actions.ListFiles`), placed after the filter dropdown. Flipping it
  rebuilds the view immediately (`invalidateAsync`, no debounce).
- **Persistence**: the mode is remembered per project in the existing workspace
  state component (`MegatronFilterState`, component name unchanged), field
  `flatMode: Boolean = false`. Default: tree mode.
- **Flat rendering**: the root node stays visible (project directory name); every
  visible FILE in the project hangs directly under it as a leaf. No directory
  nodes. Each row: file name as the main label (file-type icon as in tree mode),
  parent directory's project-relative path as the dimmed `locationString` to the
  right; files directly in the root get no location string.
- **Sorting (flat)**: case-insensitive by file name; ties broken by
  case-insensitive relative path.
- **Filtering**: identical to tree mode — `FilterEngine.isFileVisible` per file,
  `FileFilter.includeDirectory` pruning; excluded directories are not traversed at
  all. The three fallback triggers and live reload behave exactly as in tree mode.
- **Interactions unchanged**: double-click/Enter opens the file (flat rows are the
  same `FileNode` type); refresh button and auto-refresh rebuild the current mode.
- Tree mode behavior is byte-for-byte unchanged.

## Approach

Mode-aware structure: `FilteredTreeStructure` reads the mode at children-computation
time. In TREE mode the root's children are the existing directory hierarchy; in FLAT
mode the root's children are the recursively collected visible files (leaf
`FileNode`s parented to the root). No model/tree rewiring on toggle — the same
`StructureTreeModel`/`AsyncTreeModel` are invalidated and recompute. This keeps the
structure as the single swap point (as the phase-1 review recommended) for the
future virtual-folders mode.

Alternatives rejected: swapping between two structures (requires rebuilding the
tree models and re-wiring disposables on every flip); a separate JBList component
behind a card layout (duplicates keyboard/mouse/icon behavior).

## Components

- **`MegatronFilterState`** (modified): add `var flatMode: Boolean = false` to
  `State`, with synchronized accessors `isFlatMode(): Boolean` /
  `setFlatMode(flat: Boolean)`. Existing disabled-groups behavior untouched.
- **`FilteredTreeStructure` / `FileNode`** (modified): the root node checks
  `MegatronFilterState.isFlatMode()` in `getChildren()`. FLAT: walk the subtree
  (skipping excluded directories entirely), collect visible files, sort by
  name-then-path, return as leaf children of the root; leaf `FileNode`s carry a
  location string (parent's relative dir, empty at root). TREE: existing logic
  unchanged. Non-root directory nodes are never constructed in flat mode.
- **`FlatViewToggleAction`** (new, one file): `ToggleAction` bound to
  `MegatronFilterState.isFlatMode`/`setFlatMode` + `invalidateAsync` callback,
  update thread BGT.
- **`ProjectFilesPanel`** (modified): add the toggle to the toolbar group after the
  filter dropdown.

## Error Handling

Nothing new can fail: the flat walk reuses the same VFS accessors and validity
checks as the tree walk. A project where everything is filtered out shows just the
root node, as in tree mode today.

## Testing

- Platform tests (structure-level, no UI): flat mode renders expected rows in
  expected order from a fixture (names, name-then-path tie-break, location strings
  incl. empty-at-root); filtering parity (a fixture with megatron.filters shows the
  same file SET in both modes); excluded dirs absent in flat mode.
- Pure JUnit: state round-trip for `flatMode` (defaults false, set/get).
- Sandbox checklist: toggle flips instantly both ways; open-on-double-click works
  in flat mode; filter dropdown + live reload + auto-refresh all work while flat;
  mode survives project reopen; tree mode unchanged.
