# Filter Groups (Phase 2) — Design

Date: 2026-07-15
Status: Approved
Parent spec: 2026-07-15-clion-project-files-browser-design.md (replaces roadmap item 2,
which had planned an IDE settings page; the user chose a project file + toolbar
dropdown instead)

## Purpose

User-defined filtering via a hand-edited file in the project root. The Megatron
toolbar gains a dropdown of named filter groups that can be toggled on/off; the tree
shows files matching any enabled group. The file is plain text, diffs cleanly, and
can be committed so a team shares filter groups.

## The File: `megatron.filters`

Location: project root (same directory the tree is rooted at). Example:

```
# comments and blank lines ignored
Sources: *.cpp, *.c, *.cc, *.cxx
Headers: *.h, *.hpp
Docs: *.md, docs/**
```

Format rules:
- One group per line: `Name: pattern, pattern, ...` — name is everything before the
  first colon, trimmed; patterns are comma-separated, trimmed; empty patterns ignored.
- Lines starting with `#` (after trimming) and blank lines are ignored.
- Malformed lines (no colon, empty name, or no patterns) are skipped silently.
- Duplicate group names: last occurrence wins.
- Encoding UTF-8.

## Wildcard Semantics

- `*` matches any run of characters within one path segment (never `/`).
- `?` matches exactly one character (never `/`).
- `**` matches any run of characters including `/` (crosses directories).
- A pattern containing `/` matches against the PROJECT-RELATIVE path of the file
  (forward slashes). A pattern without `/` matches against the FILE NAME, anywhere
  in the project.
- Matching is case-insensitive.
- Patterns match FILES only; directory visibility is derived (a directory shows only
  if it transitively contains a visible file), exactly as today.

## Filtering Semantics

- A file is visible iff it matches at least one pattern of at least one ENABLED
  group (union).
- Built-in noise-directory exclusion (`FileFilter.includeDirectory`: `.git`, `.idea`,
  `build`, `out`, `.vs`, `cmake-build-*`) always applies and wins — patterns cannot
  reach into excluded directories. `megatron.filters` itself is a visible candidate
  like any file (it only shows if a group matches it, e.g. `*.filters`).
- Fallback to the built-in defaults (current `FileFilter.includeFile` behavior:
  C/C++/CMake extensions) when ANY of: no `megatron.filters` file; the file defines
  zero valid groups; all defined groups are toggled off.

## Toolbar Dropdown

- New popup action on the tool window toolbar (funnel icon,
  `AllIcons.General.Filter`), listing each group as a checkbox toggle in file order.
- Toggling a group rebuilds the tree immediately (no debounce needed — it's a direct
  user action).
- When the fallback is active because no file/groups exist, the dropdown shows one
  disabled entry: `No megatron.filters — using defaults`.
- Toggle state persists per-project in the IDE workspace file (never written to
  `megatron.filters`). Persisted as the set of DISABLED group names, keyed by name —
  so new/unknown groups default to enabled, and renaming a group re-enables it.

## Live Reload

Edits to `megatron.filters` take effect via the existing auto-refresh pipeline:
`VfsChangeWatcher` treats any event whose path (old or new) equals
`<root>/megatron.filters` as always relevant (bypassing the extension relevance
rules), so the tree rebuilds within the 500 ms debounce. The engine re-parses only
when the file's VFS modification stamp changed since the last parse.

## Components

- **`FilterConfig.kt`** (new): pure logic, no IDE types.
  - `class GlobPattern` — compiles one wildcard to a `Regex` at construction;
    `matches(relativePath: String, fileName: String): Boolean` applies the
    path-vs-name rule.
  - `data class FilterGroup(name: String, patterns: List<GlobPattern>)`.
  - `fun parseFilterFile(text: String): List<FilterGroup>` per the format rules.
- **`FilterEngine.kt`** (new): project-scoped service object owned by the panel (not
  a global). Holds the parsed groups cached by the file's modification stamp,
  re-reading lazily on access. Combines groups with `MegatronFilterState` and
  answers `isFileVisible(relativePath: String, fileName: String): Boolean`
  (applying the fallback rule) plus `groupsForUi(): List<Pair<String, Boolean>>`
  for the dropdown.
- **`MegatronFilterState.kt`** (new): `PersistentStateComponent` project service
  (workspace-file storage) holding the mutable set of disabled group names.
- **`FilterDropdownAction.kt`** (new): toolbar popup `ActionGroup` whose children are
  computed per-show from `FilterEngine.groupsForUi()`; each child is a checkbox
  toggle that flips `MegatronFilterState` and triggers a tree rebuild.
- **`FilteredTreeStructure`** (modified): file visibility delegates to the engine
  (`isFileVisible`) instead of static `FileFilter.includeFile`; directory exclusion
  and empty-dir pruning unchanged.
- **`VfsChangeWatcher`** (modified): always-relevant rule for `megatron.filters`;
  in the same touch, the rename branch switches to the platform's
  `event.oldPath`/`event.newPath` (deferred cleanup from the phase-1 review).
- **`ProjectFilesPanel`** (modified): constructs the engine and state, passes the
  engine to the structure, adds the dropdown to the toolbar.

## Error Handling

- Missing/unreadable `megatron.filters` → fallback defaults, no errors surfaced.
- Malformed lines skipped silently; a file of only malformed lines = zero groups =
  fallback.
- Wildcards cannot be syntactically invalid (`*`, `?`, `**` only; everything else is
  regex-escaped literally).

## Testing

- Pure JUnit: parser (comments, blanks, malformed lines, duplicate names, trimming);
  GlobPattern (`*` vs `/`, `?`, `**` crossing dirs, name-vs-path rule,
  case-insensitivity); engine composition (union across groups, disabled groups,
  all three fallback triggers).
- Platform test: tree rendered from a fixture project containing `megatron.filters`,
  asserting group-driven visibility end-to-end.
- Sandbox checklist: dropdown lists groups; toggling updates tree; editing the file
  live-updates groups and tree; fallback entry shows when file deleted; toggle state
  survives IDE restart.
