# Quick Filter Box (Phase 9) — Design

Date: 2026-07-16
Status: Approved
Parent specs: 2026-07-15-filter-groups-design.md (glob semantics), 2026-07-16-config-sets-design.md (toolbar)

## Purpose

A text box in the tool window header that applies a FINAL wildcard filter to
the displayed files — ANDed after the active set's groups and the CMake gate,
live as you type. A quick narrowing tool, not configuration.

## UI

- A platform `SearchTextField` placed in the header row to the RIGHT of the
  action toolbar (toolbar WEST, search field CENTER of a wrapper panel).
- Ghost/empty text: `Filter results…`. The component's built-in clear (✕)
  empties it instantly.
- Transient: never persisted; empty on every IDE start.

## Semantics

- Empty (or whitespace-only) text → no-op.
- Plain text containing NO `*`, `?`, or `/` → name-contains match,
  equivalent to `*text*`.
- Text containing any of `*`, `?`, or `/` → a single glob with exactly the
  megatron.filters rules: `/` present → matches the project-relative path,
  otherwise the file name; `*` within a segment, `?` one char, `**` crosses
  directories; case-insensitive.
- Applied to FILES as the last AND term of `FilterEngine.isFileVisible`
  (after group visibility and the CMake gate), so it affects all three view
  modes identically. Directory visibility stays derived (a directory shows
  only if something inside survives). Folder-view assignments are untouched;
  folders and `<Unassigned>` simply show their surviving files. Pattern
  resolution in megatron.folders is NOT affected (a quick filter narrows
  display; it never changes which folder would claim a file).
- The quick filter has NO effect on VFS watcher relevance or on the
  `isGroupVisible` path the watcher uses.

## Behavior

- Live: document changes debounce ~300 ms (own SingleAlarm, same pattern as
  the VFS watcher), then `structureModel.invalidateAsync()`.
- Open-in-tabs recursive collection (walks tree children) naturally honors
  the quick filter; Open Pair's counterpart search (deliberately unfiltered
  since v0.8.3) remains unfiltered.

## Components

- **`QuickFilter`** (new, pure): `QuickFilter(text: String)` compiling the
  rule above; `fun matches(relativePath: String, fileName: String): Boolean`;
  companion `fun parse(text: String): QuickFilter?` returning null for
  blank input. Reuses `GlobPattern`. Fully unit-testable.
- **`FilterEngine`** (modified): `@Volatile private var quickFilter:
  QuickFilter? = null`; `fun setQuickFilter(text: String)` (parses, stores);
  `isFileVisible` gains a final `quickFilter?.matches(relativePath, name) != false`
  term. `isGroupVisible` unchanged.
- **`ProjectFilesPanel`** (modified): SearchTextField in the header wrapper,
  document listener → debounce alarm → `engine.setQuickFilter(text)` +
  `invalidateAsync()`.
- Version 0.9.0.

## Error handling

- Globs cannot be malformed; blank input clears. Nothing else can fail.

## Testing

- Pure `QuickFilter` tests: blank → null; bare text wraps as contains
  (name-based, case-insensitive); `*`/`?` text stays a name glob; text with
  `/` matches relative path; `**` crosses directories.
- Engine test: quick filter ANDs after group visibility (file passing groups
  but not quick filter is hidden; clearing restores); gate interaction
  unchanged.
- Structure test: directories prune when the quick filter empties them;
  folder view shows only surviving files with assignments intact.
- Sandbox: typing narrows live in all three view modes; ✕ clears; debounce
  feels instant-ish; restart starts empty.
