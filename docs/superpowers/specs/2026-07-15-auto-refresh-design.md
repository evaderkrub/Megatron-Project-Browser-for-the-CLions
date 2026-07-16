# Auto-Refresh (Phase 1) — Design

Date: 2026-07-15
Status: Approved
Parent spec: 2026-07-15-clion-project-files-browser-design.md (roadmap item 1)

## Purpose

The Project Files tree currently updates only via the manual refresh button. Phase 1
makes it update itself: a VFS change listener rebuilds the tree shortly after files
change, debounced so bursts (git checkout, builds) cause one rebuild, and filtered so
churn inside excluded directories (`cmake-build-*`, `.git`, ...) causes none.

## Behavior

- Tree updates ~0.5 s after relevant file-system changes settle (500 ms debounce,
  coalescing: each relevant event restarts the timer).
- Changes are RELEVANT only when all hold:
  1. The event path is under the panel's root directory (or is the root itself).
  2. No path segment strictly between the root and the leaf fails
     `FileFilter.includeDirectory` — so events inside excluded dirs are ignored,
     including build churn in `cmake-build-*`.
  3. The leaf is a directory (creation/deletion/move of dirs can change visibility),
     OR the leaf name passes `FileFilter.includeFile`.
  - Rename events (VFS property-change of `name`) are relevant if the OLD or NEW
    name qualifies under rule 3. Move events are relevant if the old or new path
    qualifies under rules 1-3.
- The manual refresh button stays unchanged (it additionally forces a recursive VFS
  rescan, which catches changes the OS file watcher missed — network drives etc.).
- No settings/toggle for auto-refresh (YAGNI; revisit if it ever misbehaves).

## Approach

`BulkFileListener` subscribed to the `VirtualFileManager.VFS_CHANGES` topic on the
project message bus, connection scoped to the tool window disposable. Events are
already applied when received; we only string-inspect paths/names — no I/O, nothing
to fail, deleted files are never dereferenced. Debounce via `SingleAlarm` (500 ms,
same parent disposable), whose task invokes a callback on the EDT.

Alternatives rejected: `AsyncFileListener` (designed for expensive pre-apply
processing we don't do), `AsyncVfsEventsPostProcessor` (less stable API surface, no
advantage for a cheap check).

## Components

- **`VfsChangeWatcher.kt`** (new, one file): owns the message-bus subscription, the
  relevance decision, and the debounce. Constructor:
  `VfsChangeWatcher(project, rootDir, parentDisposable, onChange: Runnable)`.
  Pure relevance logic exposed as a companion function
  `isRelevant(rootPath: String, event info): Boolean` operating on strings/booleans
  only — unit-testable without an IDE.
- **`ProjectFilesPanel`** (modified): after model construction, instantiate
  `VfsChangeWatcher(project, rootDir, parentDisposable) { structureModel.invalidateAsync() }`.
  No other panel changes.

## Testing

- Pure JUnit tests for the relevance function: path under/outside root; root itself;
  excluded segment at various depths (`cmake-build-debug`, `.git`); leaf dir vs
  matching file vs non-matching file; rename where only old name matches, only new
  name matches, neither matches.
- Debounce and end-to-end wiring verified manually in the runIde sandbox: create /
  delete / rename files both in-IDE and externally; run a CMake build and confirm
  the tree does not churn; confirm one rebuild after a git branch switch.
