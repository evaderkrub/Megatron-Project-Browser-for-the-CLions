# Context Actions (Phase 8) — Design

Date: 2026-07-16
Status: Approved
Parent spec: 2026-07-16-virtual-folders-design.md (extends the tree context menu)

## Purpose

Four workflow actions on the Megatron tree: open a folder's files as editor
tabs (plain or pinned), open a file's header/source counterpart alongside it,
and reveal a file in the OS file manager.

## Open in Tabs / Open in Pinned Tabs

- Two entries on folder-like nodes: virtual folders, disk directory nodes,
  and the `<Unassigned>` bucket. Multi-select allowed — the union of the
  selected folders' files, deduplicated.
- Recursive: every visible file underneath (virtual folders: the folder's
  resolved files plus all descendant folders'; directories: the same
  filtered walk the tree renders, honoring `<Unassigned>` exclusions).
- Confirm dialog when the total exceeds 20 files ("Open N tabs?" yes/no);
  no dialog at or below 20.
- Files open in order (folders' own sort order); the pinned variant pins
  each opened tab via the platform editor-window pinning API (exact API
  javap-verified during planning).

## Open Pair

- Context menu entry "Open Pair" on a single selected file that HAS a
  counterpart; hidden otherwise. Opens the counterpart first, then the
  selected file, both as normal tabs — focus ends on the selected file.
- Also bound to Ctrl+double-click on a file row: opens the pair when a
  counterpart exists, else behaves like a plain double-click (opens the
  file).
- Counterpart definition: same base name (case-insensitive
  `nameWithoutExtension`), opposite extension family —
  headers `h, hh, hpp, hxx` ↔ sources `c, cc, cpp, cxx`. Files with other
  extensions have no counterpart.
- Search order among the project's VISIBLE files (current filters apply):
  same directory first; otherwise the candidate with the most path
  segments shared with the file's directory, ties broken by
  case-insensitive relative-path order. Pure function, unit-tested.

## Reveal in Explorer

- Entry on any single selected file or directory node (including the tree
  root, excluding virtual folders and `<Unassigned>` which have no disk
  identity). Delegates to the platform reveal action
  (`RevealFileAction` — OS-appropriate label like "Reveal in Explorer" /
  "Reveal in Finder" via the platform's own naming, and its file-manager
  integration; exact API javap-verified during planning).

## Menu layout

The existing `MegatronTreePopupGroup` gains, in order after the current
entries and a separator: Open in Tabs, Open in Pinned Tabs, Open Pair,
Reveal in Explorer — each visible only when its selection rule matches.

## Components

- **`OpenActions.kt`** (new): the four actions plus the pure helpers —
  recursive file collection for each folder-node kind, pair matching
  (`findCounterpart(file, visibleFiles): VirtualFile?` on relative paths),
  and the >20 confirm.
- **`MegatronTreePopupGroup`** (modified): registers the new entries.
- **`ProjectFilesPanel`** (modified): Ctrl+double-click branch in the
  existing DoubleClickListener.
- Version 0.8.0.

## Error handling

- Deleted/invalid files at open time are skipped silently (same guard the
  existing open path uses).
- Reveal on a file that vanished → the platform action's own handling.

## Testing

- Pure unit tests: counterpart matching (extension families, same-dir
  preference, shared-segment tie-break, case-insensitivity, no-counterpart
  cases), recursive collection over a fake folder grouping.
- Platform structure tests where cheap; the tab/pin/reveal side effects and
  Ctrl+double-click are sandbox-verified (no automated editor-tab
  assertions).
- Sandbox checklist: open ~5-file folder in tabs; pinned variant shows pins;
  >20 confirm appears and cancels cleanly; Open Pair from either side of a
  pair; Ctrl+double-click both with and without a counterpart; Reveal opens
  Explorer at the file.
