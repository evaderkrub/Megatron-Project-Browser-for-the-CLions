# CMake Project-Model Gate (Phase 4) — Design

Date: 2026-07-16
Status: Approved
Parent spec: 2026-07-15-clion-project-files-browser-design.md (roadmap item 4)

## Purpose

An optional extra filter: show only files that belong to the CMake project model
(what CLion actually builds). Surfaced as a pinned toggle at the top of the existing
filter dropdown; when ON it NARROWS the current group filtering (AND). Also fixes a
known watcher gap: auto-refresh now uses the filter engine's real visibility answer,
so files visible only via custom groups trigger refreshes too.

## API Foundation (researched against the CLion 2026.1.1 distribution)

- Membership check: `OCWorkspace.getInstance(project).getConfigurationsForFile(vf)`
  non-empty ⇒ file is part of the project model. Engine-agnostic (works under both
  the classic and Nova/Radler engines — verified: the Radler plugin references none
  of these classes and both engines sit on this shared project-model layer). Module
  `intellij.cidr.projectModel` is `visibility="public"`, `loading="required"` in
  plugin `com.intellij.clion`.
- Plugin dependency: add `<depends>com.intellij.modules.clion</depends>` to
  plugin.xml (the plugin is CLion-only already; this formalizes it). Build script
  gains the corresponding bundled-plugin compile dependency.
- Model-load signal: `OCWorkspaceListener` (`TOPIC` on the project message bus) —
  `workspaceChanged(...)` / `workspaceInitializationFinished(...)` fire when the
  model (re)loads.
- Caveats accepted by design: the model is empty until the first CMake sync
  finishes; header membership is heuristic (headers associate with the nearest
  target and thus generally pass). The gate technically covers whatever build
  system populates the model (CMake, Meson, compilation DB) — labeled "CMake" in
  the UI because that is CLion's default and the user's mental model.

## Behavior

- **Dropdown entry**: checkbox row `Only CMake Project Files` pinned FIRST in the
  filter dropdown, followed by a separator, then the group toggles (or the
  no-file info entry). Present regardless of whether a model exists.
- **Gate semantics** (only when the toggle is ON):
  - Model loaded (workspace has ≥1 resolve configuration): a FILE is visible iff
    it passes the existing group/default filtering AND
    `getConfigurationsForFile(file)` is non-empty. `megatron.filters` and
    `CMakeLists.txt`/`*.cmake` files follow the same rule as any file (build
    scripts are typically NOT in the model, so the gate hides them; toggling the
    gate off brings them back — acceptable and predictable).
  - Model unavailable (no configurations yet — project still syncing, or not a
    CMake project): the gate is a NO-OP; the tree shows group-filtered files as
    if the toggle were off. When the model finishes loading, the tree refreshes
    automatically (workspace listener → same debounced invalidate as the VFS
    watcher).
- **Persistence**: `cmakeGateEnabled: Boolean = false` in `MegatronFilterState`
  (workspace file), same pattern as `flatMode`.
- **Both view modes**: the gate applies identically in tree and flat mode
  (it sits inside the engine's file-visibility answer).
- **Directory visibility** remains derived (a dir shows only if it transitively
  contains a visible file) — no change.

## Watcher Gap Fix (rides along, independent of the gate)

`VfsChangeWatcher`'s file-relevance check currently uses the built-in
`FileFilter.includeFile`, so files visible only via custom groups (e.g. `*.md`
under a `Docs` group) don't trigger auto-refresh on create/delete/rename. Fix: the
relevance check consults the engine's group-level visibility
(`FilterEngine.isFileVisible` semantics WITHOUT the CMake gate). The gate never
affects relevance: a newly created file isn't in the model yet, and gate toggling
doesn't generate VFS events — the tree must still refresh on such changes.
The pure relevance function keeps its testability by taking the file-visibility
predicate as a parameter.

## Components

- **`ProjectModelGate`** (new, one file): tiny interface —
  `fun isActive(): Boolean` (model has ≥1 configuration) and
  `fun isInModel(file: VirtualFile): Boolean`. Two implementations:
  - `OcWorkspaceGate(project)` — wraps `OCWorkspace`; also offers
    `fun subscribe(parentDisposable, onModelChanged: () -> Unit)` wiring
    `OCWorkspaceListener` to the refresh callback.
  - test fake (in test sources) with settable active/membership state.
- **`FilterEngine`** (modified): takes an optional `gate: ProjectModelGate?` plus a
  gate-enabled supplier (reads `MegatronFilterState`). `isFileVisible` becomes:
  group/default visibility AND (gate disabled OR !gate.isActive() OR
  gate.isInModel(file)). Exposes the un-gated group visibility for the watcher.
- **`MegatronFilterState`** (modified): `cmakeGateEnabled` field + synchronized
  accessors + defensive-copy update.
- **`FilterDropdownAction`** (modified): pinned gate toggle + separator before the
  group list.
- **`VfsChangeWatcher`** (modified): file-relevance uses the engine's un-gated
  visibility via an injected predicate; pure functions keep predicate parameters.
- **`ProjectFilesPanel`** (modified): constructs `OcWorkspaceGate`, passes it to the
  engine, subscribes the gate's model-change signal to `invalidateAsync()`.
- **`plugin.xml` / `build.gradle.kts`** (modified): CLion module dependency;
  version 0.4.0.

## Error Handling

- No model / not a CMake project → gate inactive by definition (no configurations);
  UI unchanged, no errors.
- `getConfigurationsForFile` is only called for files already passing group
  filters, on the same background tree walk as today.

## Testing

- Pure/platform tests with the FAKE gate: gate ON + active → intersection applied;
  gate ON + inactive → no-op; gate OFF → unchanged; both view modes.
- State round-trip tests for `cmakeGateEnabled`.
- Watcher: relevance-with-predicate tests — a `*.md` file is relevant when the
  predicate says visible (group case) and irrelevant under defaults; existing
  tests unchanged (the predicate parameter is defaulted).
- The real `OcWorkspaceGate` is compile-verified; behavior verified in the sandbox
  checklist: on a real CMake project, toggle ON hides out-of-model files (e.g. a
  stray `.cpp` not added to any target, build scripts); open the project fresh and
  observe the tree refresh when CMake finishes loading; Meson/compdb note ignored.
- Sandbox regression: the `*.md`-group auto-refresh gap is verified fixed.
