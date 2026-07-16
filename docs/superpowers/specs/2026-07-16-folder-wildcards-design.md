# Wildcards in megatron.folders (Phase 6) — Design

Date: 2026-07-16
Status: Approved
Parent spec: 2026-07-16-virtual-folders-design.md (extends the megatron.folders format)

## Purpose

Folder file entries may be glob patterns that auto-assign matching files to
virtual folders, plus `!` exclusion entries that pin files out of pattern
assignment. Patterns evaluate live against the files on disk, so new files
matching `src/**` appear in their folder automatically.

## Syntax (extends the phase-5 format; fully backward compatible)

Within a folder block, each non-blank, non-comment line is one of:

- **Explicit path** — no `*` or `?` anywhere in the line (today's behavior).
- **Pattern** — contains `*` or `?`. Same glob rules as `megatron.filters`:
  `*` matches within a path segment, `?` one character, `**` crosses
  directories; a pattern containing `/` matches the project-relative path,
  one without `/` matches the file name; case-insensitive.
- **Exclusion** — starts with `!`, followed by an explicit path or a glob.
  Excluded files are pinned OUT of all pattern assignment (they land in
  `<Unassigned>` unless explicitly assigned). Exclusions are global in
  effect regardless of which folder block they appear in; the UI writes
  them under the folder whose pattern they override, for readability.

Folder declaration lines (ending `/`), comments, blank lines, indentation,
backslash normalization, and case-insensitivity are unchanged from phase 5.

## Precedence (one folder per file, evaluated per visible file)

1. **Explicit path entry** — always wins; among duplicates, last in file
   (unchanged from phase 5).
2. **Exclusion** — beats all patterns anywhere in the file.
3. **Patterns** — the matching pattern LATEST in the file wins.

`<Unassigned>` = visible files that are not explicitly assigned and not
pattern-assigned (or pattern-matched but excluded).

## Serialization (behavior change from phase 5)

Order is now semantically significant ("last pattern wins"), so the
serializer preserves DECLARATION ORDER of folders instead of sorting them
alphabetically; new folders append at the end. Within a folder block:
pattern and exclusion lines keep their declaration order and are written
first, then explicit file paths sorted by lowercase path. Two-space indent
and `\n` termination unchanged. Hand-written comments are still not
preserved across a UI rewrite (unchanged).

Tree DISPLAY order of folders remains alphabetical per level (unchanged);
only the file's text order changes meaning.

## Live behavior

Pattern matching runs at tree-build time against the files actually on
disk (the same filtered walk flat mode uses: excluded directories are
never entered, engine filters apply). The existing VFS watcher already
refreshes on relevant create/delete/rename events and on megatron.folders
edits, so folder contents track the file system with no new listeners.

## UI interaction (every existing gesture works on every file)

- **Add to Folder / drag onto a folder**: writes an explicit entry (explicit
  beats patterns) and deletes any exclusion entries matching that exact file
  path (stale-pin cleanup). If the file was explicitly assigned elsewhere,
  that line moves (unchanged).
- **Remove from Folder / drag to `<Unassigned>`**: delete the file's
  explicit entry if it has one; then, if a pattern would still claim the
  file, also write a `!<relative path>` exclusion line under the folder
  whose pattern claims it. Either way the file resolves to `<Unassigned>`
  after the rewrite.
- **Folder rename**: renames the folder path everywhere (unchanged); its
  pattern/exclusion lines stay in the renamed block.
- **Folder delete**: removes the block including its pattern and exclusion
  lines; files return to wherever the remaining rules put them (which may be
  another folder's pattern, not necessarily `<Unassigned>`).
- The UI never generates glob patterns or glob exclusions itself — globs are
  a hand-edit power feature; the UI only preserves them faithfully and works
  around them via explicit entries and exact-path exclusions.
- "Remove from Folder" menu visibility now covers pattern-assigned files too
  (any selected file whose resolved folder is non-null).

## Components

- **`FolderLayout`** (modified, still pure): entries become typed —
  explicit assignments stay `FileAssignment(path, folder)`; patterns and
  exclusions are `FolderRule(raw, folder, isExclusion)` — reusing the
  existing `GlobPattern` from
  FilterConfig.kt. `folderFor(relativePath)` implements the precedence
  chain; `patternFolderFor(relativePath)` exposes rules-only resolution.
  Mutations carry the exclusion semantics internally: `withAssignment`
  also drops matching exact-path exclusions; `withUnassigned` removes the
  explicit entry and writes the exclusion itself when a pattern would
  still claim the file. Serializer per the section above.
- **`FilteredTreeStructure` / `VirtualFolderNode`** (modified): folder
  children and the `<Unassigned>` exclusion switch from "listed paths" to
  "resolve rules over the visible file walk": collect visible files once
  per folder-view build (same collector as flat mode), resolve each file's
  folder, group. Missing-file entries naturally drop out (nothing on disk
  to resolve); explicit entries keep case-insensitive resolution behavior.
- **`FolderActions` / `FolderDnD`** (unchanged): menu visibility already
  uses `folderFor` (which now covers pattern assignment), and the mutation
  entry points keep their signatures with the new semantics inside
  `FolderLayout` — no UI-file changes needed.
- **`FolderLayoutStore`, watcher, toggle, plugin.xml**: unchanged.
- **Version**: 0.6.0.

## Performance note

Folder-view builds match each visible file against all patterns — same
cost class as megatron.filters group matching, acceptable at project scale.
The visible-file walk happens once per build (not per folder).

## Error handling

- A `!` line with nothing after it (globs themselves cannot be malformed)
  → skipped silently, consistent with the existing salvage-what-you-can
  parsing.
- Everything else unchanged from phase 5.

## Testing

- Parser/serializer: round-trips with patterns and exclusions; declaration
  order preserved for folders and pattern lines; explicit files still
  sorted; `!` with empty rest skipped; backward compat — a phase-5 file
  parses and serializes to the same content modulo the new folder ordering
  rule.
- Precedence unit tests: explicit vs pattern vs exclusion; last-pattern-
  wins across folder blocks; name-glob vs path-glob matching; exclusion by
  glob; case-insensitivity.
- Mutations: assign clears exact-path exclusions; unassign of a pattern-
  assigned file writes the exclusion under the owning folder; unassign of an
  explicit entry deletes the line; folder delete removes its pattern lines
  and files re-resolve.
- Structure tests: a `src/**` folder picks up files and `<Unassigned>`
  shrinks accordingly; a new file appearing on disk shows up in the pattern
  folder on rebuild; excluded file renders in `<Unassigned>`; engine filters
  still apply inside pattern folders.
- Sandbox checklist: hand-edit patterns and watch live updates; drag a
  pattern-matched file to `<Unassigned>` and verify the `!` line; drag it
  back onto the folder and verify the exclusion is cleaned up.
