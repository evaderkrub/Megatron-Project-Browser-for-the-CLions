# Config Sets (Phase 7) — Design

Date: 2026-07-16
Status: Approved
Parent specs: 2026-07-15-filter-groups-design.md, 2026-07-16-virtual-folders-design.md

## Purpose

Multiple named Megatron configurations ("sets") per project, switchable from the
toolbar. A set bundles a filter config and a folder layout. Config files move
into a dedicated `megatron/` directory. When no sets exist, a one-click action
creates a documented default set.

## Layout

- All config lives in `megatron/` at the project root:
  `megatron/<set>.filters` and `megatron/<set>.folders`.
- A set is a base name; the set list is the union of `.filters`/`.folders`
  base names of the direct children of `megatron/`, sorted case-insensitively.
- Either file may be absent: no `.filters` → built-in default filtering
  (exactly today's no-file behavior); no `.folders` → folder view shows only
  `<Unassigned>` (today's no-file behavior).
- Root-level `megatron.filters` / `megatron.folders` are NO LONGER READ at
  all (breaking change, accepted). Users migrate by moving the files into
  `megatron/` and renaming them `<set>.filters` / `<set>.folders`.

## Switching

- New toolbar dropdown, placed LEFT of the filter (funnel) dropdown. Its
  button text is the effective set name. Children are computed per-show
  (same pattern as the filter dropdown): one radio-style toggle per scanned
  set, alphabetical, check mark on the effective set. Selecting one persists
  it and rebuilds the tree.
- Effective set = the persisted name if that set currently exists, else the
  alphabetically first existing set, else `"default"` (relevant only for
  where a mutation would write).
- Persistence: `activeSet: String = "default"` joins `MegatronFilterState`
  (workspace file, per-user), synchronized accessors, defensive-copy
  `getState()`.
- Group enable/disable toggles remain keyed by group NAME only (not
  per-set) — a group named "Sources" shares its toggle across sets.
  Accepted simplification.

## Empty state — Create Default Set

- When the scan finds NO sets, a banner appears at the top of the tool
  window content: "No Megatron config sets" with a clickable
  "Create default set" link. It hides once at least one set exists
  (re-evaluated on every tree refresh).
- Clicking creates `megatron/default.filters` and `megatron/default.folders`
  (creating the directory as needed) in one write command, then opens both
  files in editor tabs and refreshes the tree.
- `default.filters` content: a comment block documenting the format (one
  `Name: pattern, pattern` group per line; `*` `?` `**` glob rules; path vs
  name matching; case-insensitivity; groups toggled from the funnel
  dropdown; no groups → built-in defaults), followed by a starter group:
  `Sources: *.c, *.cc, *.cpp, *.cxx, *.h, *.hh, *.hpp, *.hxx, *.inl, CMakeLists.txt, *.cmake`
  (mirrors the built-in defaults).
- `default.folders` content: a comment block documenting the format (folder
  lines ending `/` with `/` nesting; explicit file paths; glob pattern
  lines; `!` exclusions; precedence explicit > exclusion > last pattern;
  one folder per file; UI edits rewrite the file), followed by no folders —
  an empty documented file.

## Header comments survive UI rewrites

So the default set's documentation isn't destroyed by the first drag-and-drop:
`FolderLayout` now captures the file's HEADER — the leading run of comment
(`#`) and blank lines before the first folder/rule line (right-trimmed,
trailing blank lines dropped) — and the serializer re-emits it (followed by
one blank line when folders follow). Mutations carry the header through.
Comments elsewhere in the file are still not preserved (unchanged).
`.filters` files are never rewritten by the UI, so they need nothing.

## Plumbing

- **`ConfigSetManager`** (new): owns set scanning and resolution for one
  panel. `fun setNames(): List<String>` (scan, sorted),
  `fun effectiveSet(): String` (fallback chain above),
  `fun filtersFile(): VirtualFile?` / `fun foldersFile(): VirtualFile?`
  (children of `megatron/` for the effective set),
  `fun foldersFilePathForWrite(): pair of (megatron dir create-if-needed,
  file name)` used by the store's mutate, and
  `fun createDefaultSet()` (write command; both files + editor open).
- **`FilterEngine`** (modified): reads its config through the manager
  instead of `rootDir.findChild("megatron.filters")`. The parse cache key
  becomes (file path, modification stamp) so switching sets invalidates
  correctly even when stamps collide.
- **`FolderLayoutStore`** (modified): same resolution change; `mutate`
  creates `megatron/` and `<set>.folders` as needed.
- **`VfsChangeWatcher`** (modified): the always-relevant config check
  becomes: any path directly under `<root>/megatron/` ending in `.filters`
  or `.folders` (case-insensitive), or the `megatron` directory itself —
  covering set creation/deletion/rename and content edits. The old
  root-file check is removed.
- **`ProjectFilesPanel`** (modified): constructs the manager, adds the set
  dropdown, hosts the empty-state banner above the tree.
- Version 0.7.0.

## Error handling

- `megatron/` missing or empty → empty scan → banner; engine/store behave
  as "no file" (defaults / empty layout).
- Persisted set deleted on disk → silent fallback per effective-set chain;
  the dropdown simply shows the fallback as checked.
- Default-set creation IO failure → same ERROR notification path as store
  mutations ("Megatron" group).

## Testing

- ConfigSetManager: scanning (union of extensions, sorting, ignoring
  subdirectories and other extensions), effective-set fallback chain, unit
  tests with fixture dirs.
- Engine/store resolution: set switch changes which file is read (platform
  test: two sets with different groups/layouts, flip activeSet, assert
  visibility changes); cache invalidation across same-stamp switches.
- Watcher: megatron/-relative relevance cases (both extensions, nested
  paths rejected, dir itself, case-insensitivity); root-level megatron.*
  files are now IRRELEVANT.
- Default-set content: created files parse (parseFilterFile yields the
  starter group; parseFoldersFile yields empty layout), directory created,
  banner-condition flips.
- State: activeSet round-trip.
- Sandbox checklist: dropdown switching, banner + creation flow, editing a
  non-active set does not disturb the view, legacy root files ignored.
