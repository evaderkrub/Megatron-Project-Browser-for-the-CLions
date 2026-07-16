# CLion Project Files Browser — Design

Date: 2026-07-15
Status: Approved

## Purpose

A CLion plugin that adds a "Project Files" tool window: a filtered, curated view of
the project's files. The built-in Project view shows everything on disk; this plugin
shows only the files that matter (sources, headers, CMake files), with build/VCS
noise pruned away. V1 is a small, working foundation; later phases add flat-list
mode, configurable filtering, CMake-model awareness, and Visual-Studio-style
virtual folders.

## V1 Scope

A tool window (docked left, alongside the built-in Project view) showing a
directory tree of the project, filtered:

- **Extension allow-list** (hardcoded in v1): `.c .cc .cpp .cxx .h .hh .hpp .hxx .inl`,
  plus `CMakeLists.txt` and `*.cmake`.
- **Noise directories pruned entirely** (hardcoded in v1): `.git`, `.idea`,
  `cmake-build-*`, `build`, `out`, `.vs`.
- Directories left empty after filtering are hidden.

Behavior:

- Tree roots at the project base directory, showing its name at the top.
- Files render with platform file-type icons; directories with folder icons.
- Sorting: directories first, then files, both alphabetical case-insensitive.
- Double-click or Enter opens the file in the editor. That is the only action —
  the view is read-only browsing in v1 (no rename/delete context menu).
- Manual refresh via a toolbar button rebuilds the tree. No auto-refresh in v1.

## Approach

Custom tool window using the modern IntelliJ Platform tree stack:
`AbstractTreeStructure` → `StructureTreeModel` → `AsyncTreeModel` → tree component.
Scanning runs on a background thread; the UI never freezes on large projects.

Alternatives considered and rejected:

- **Custom pane inside the built-in Project View** (`AbstractProjectViewPane`):
  free platform actions, but an older, poorly documented API that would constrain
  the virtual-folders mode.
- **Plain Swing JTree**: simplest, but we would hand-roll background loading and
  refresh, then rebuild into the chosen shape anyway.

The chosen approach keeps the tree structure entirely ours, so later view modes
(flat list, virtual folders) are just alternative structure providers behind the
same panel.

## Project Scaffold

- Kotlin, Gradle with the IntelliJ Platform Gradle Plugin 2.x.
- Target: CLion 2026.1 (build 261.x) — matches the installed CLion 2026.1.1.
- JDK 21 via Gradle toolchain. (Machine currently has only JRE 1.8; installing
  Temurin JDK 21 is a setup step, e.g. `winget install EclipseAdoptium.Temurin.21.JDK`.)
- `./gradlew runIde` launches a sandboxed CLion with the plugin loaded — it does
  not touch the real CLion install.
- V1 depends only on platform APIs (no C++/CMake plugin APIs), so it works under
  both CLion engines.

## Components

Each component is one file with one job:

- **`ProjectFilesToolWindowFactory`** — registered in `plugin.xml`; creates the
  tool window content.
- **`FileFilter`** — pure logic: given a file or directory name, answers
  include/exclude. Hardcoded defaults in v1; becomes settings-driven in phase 2.
  Unit-testable with no IDE running.
- **`FilteredTreeStructure`** — `AbstractTreeStructure` walking the project base
  directory through `FileFilter`, pruning empty directories. The swap-point for
  flat/virtual-folder modes later.
- **`ProjectFilesPanel`** — glues structure → `StructureTreeModel` →
  `AsyncTreeModel` → tree component; wires double-click/Enter-to-open and the
  refresh toolbar action.

## Error Handling

- Files or directories that vanish between scan and click are ignored gracefully
  (`VirtualFile.isValid` check before opening).
- Unreadable directories are skipped silently.
- An unreadable/empty project directory yields an empty tree — no error dialogs.

## Testing

- **`FileFilter`**: plain JUnit unit tests (pure logic, no IDE).
- **`FilteredTreeStructure`**: IntelliJ Platform test framework
  (`BasePlatformTestCase`) with a small in-memory project — assert noise dirs are
  pruned, non-matching extensions hidden, empty dirs collapsed away.
- **End-to-end**: `runIde` sandbox — open a real CMake project and verify the
  window visually.

## Phase Roadmap (after v1)

Each phase gets its own plan and session:

1. **Auto-refresh** — VFS change listener rebuilds the tree on file-system changes.
2. **Settings** — configurable extension allow-list and user-defined include/exclude
   glob patterns; settings page under Tools; persisted per-project.
3. **Flat list mode** — toolbar toggle; same filter, flat presentation showing
   relative paths.
4. **CMake-aware filter** — option to show only files that are part of the CMake
   project model (requires CLion CMake APIs).
5. **Virtual folders (VS-style)** — manually created named folders; assign files
   via context menu / drag-and-drop; persisted per-project in the workspace file.
   Largest phase, deliberately last.
