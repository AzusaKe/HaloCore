# Changelog

## 2.4.0 — 2026-09-17

- Add loader-neutral `api.v2` ownership sources with closeable, session-isolated candidate handles.
- Arbitrate one winning definition by host-configured signed priorities, including negative values,
  deterministic one-step collision demotion and explicit disabling of a second collision.
- Preserve identical-definition presentation state, existing attach/remove events, legacy wire/storage
  shapes and every published `ServerRuntime`, `ClientPort` and anchor API entry point.
- Compile an external ownership provider using only the public API jar and run it against the built core;
  add lifecycle, fallback, collision, minimum-integer and frozen server-runtime caller coverage.

## 2.3.2 — 2026-09-17

- Add optional exact-depth-transform reuse to `MeshIndexWriter`, preserving stable source-order ties,
  floating-point depth arithmetic, mirrored winding and all existing writer entry points. Expose a
  writer-local preparation revision so adapters can track uploaded indices per EBO and vertex layout.
- Compare indexed/expanded writes against the frozen 2.3.1 implementation, including translation-induced
  rounding ties, and run a frozen 2.3.1 writer caller against the current jar.

## 2.3.1 — 2026-09-16

- Share immutable, shape-deduplicated billboard/ring geometry between compatibility expansion and ordered cached primitive commands. Retain quad diagonals, ring seams, winding and facing/normal fallbacks.
- Add explicit `PrimitiveRenderMode` to world/preview input and `PrimitiveDraw` output, preserving every old constructor. `FrameOutput.expandedBatches` remains a same-frame compatibility/failure fallback; OBJ mesh commands and ordering are unchanged.
- Expose legacy texture dependencies and prepared procedural geometry from `DefinitionSnapshot`, independently of existing mesh asset dependencies. Keep preparation lazy on dedicated servers.
- Transform shared corners once in CPU expansion; memoize texture facts only within a render invocation. Isolate invalid legacy geometry during preparation.
- Add repaired/persisted `primitiveRenderBackend` configuration, defaulting to `compatibility`; retain unrelated/unknown configuration data.
- Compare both representations against frozen 2.3.0 geometry at fixed input/time, and run a caller compiled against frozen frame/preview/output constructors against the new jar. No definition schema, physics, API v2, save or wire changes.

## 2.3.0 — 2026-09-15

- Extend the Java 17 / loader-neutral `api.v2` with preview submission and a separate host bridge.
  Share one HaloAnchorApi registration and AnchorSource lifecycle across both render spaces.
  Bind submissions to lexical preview contexts, real wearers and optional proxy render entities.
- Move provider/fallback selection, nested entity/view isolation, source lifetime and thread checks
  into core. Reject expired/suspended contexts; retain render routing until invalidated scopes close.
- Prevent GUI submissions from overwriting an enclosing world API v2 capture. Preserve world API
  signatures, preview rendering contracts, physics, definition schema, storage and protocol.
- Compile an external provider with only the public API classes, then run it against the built core
  jar + JDK; add scope, proxy, arbitration, reset, thread and world-isolation regression coverage.
- Clarify the separate world/preview coordinate, arbitration and lifetime contracts. Minecraft's
  former HaloPreviewApi facade is no longer a supported integration; hosts use the neutral core ports.

- Permit world-only and preview-only use without affecting the other space. Test independent
  sources, shared-handle cleanup and re-registration; remove the standalone preview API package.

## 2.2.0 — 2026-09-15

- Add optional `PreviewPort`, closeable `PreviewSession` and platform-neutral `PreviewFrame` inputs;
  retain `ClientPort`, world frame/output constructors and anchor API v2 compatibility.
- Share wearer appearance, transitions, animation, materials and billboard/ring/mesh dispatch across
  world and preview draws. Preview views consume the latest world appearance without advancing it.
- Add stateless head-relative preview bases and orthographic `face_camera`; isolate view geometry
  from world physics, teleport flags and body-pose observations.
- Backfill `playerPreviewHaloEnabled=true` in existing client configuration.
- Add opt-in `PreviewOptions.PHYSICS`, with independent per-session motion using the world's
  calculator, parameters and frame-time smoothing. Keep the no-argument preview entry rigid.
- Add defaulted session validity/reset methods and `playerPreviewHaloPhysicsEnabled=true`;
  repeated view samples do not advance motion twice, and lifecycle/definition changes reset it.
- Enable automatic preview physics by default, backfill old configurations, and preserve explicit
  `false` for rigid head following. Explicit no-argument API sessions remain rigid for compatibility.
- Verify frozen 2.1.2 client-port consumers against the built jar, alongside preview isolation,
  coordinate, lifecycle, resource recovery and platform-boundary tests.
- Keep definition schema 1.1.0, persistence, network protocols and world anchor semantics unchanged.

## 2.1.2 — 2026-09-14

- Restore case-insensitive namespace-prefix matching for halo identifiers while retaining path-prefix matching.
- Add optional block/sky `LightSample` frame input and draw-command output so non-glowing primitives can use a
  host's native lightmap; glowing primitives request full-bright while retaining animated glow tint.
- Retain the original scalar light callback and `FrameScene` / `DrawBatch` / `MeshDraw` constructors as a fallback
  for adapters that have not adopted native lightmaps.
- Preserve normalized OBJ `vn` data and generate per-face normals when it is missing or zero. Extend mesh and legacy
  draw commands with normals plus an explicit directional-lighting request for non-glowing groups; compatibility
  constructors retain their prior flat-lighting behavior.
- Let hosts write source-order or stably depth-sorted indices for triangle-corner-expanded vertex streams, while
  retaining the existing unique-vertex index contract for adapters that do not need derived per-triangle attributes.
- Keep definition formats, storage, protocol, schema 1.1.0 and anchor API v2 unchanged.

## 2.1.0 — 2026-09-13

- Accept arbitrary positive base/mask dimensions and aspect ratios in the shared normalized UV domain.
- Add lightweight `FrameOutput` / `MeshDraw` commands so adapters can retain indexed mesh geometry instead of expanding it every frame.
- Add reusable, stable back-to-front `MeshIndexWriter` sorting without per-frame workspace allocation.
- Keep `ClientPort.render`, `DrawBatch`, schema 1.1.0, storage, protocol and anchor API v2 compatible.

## 2.0.0 — 2026-09-13

Accepted with Halo Minecraft 1.20.1 Fabric after automated checks and user game validation.

- Add static OBJ mesh primitives with three-axis size, authored origins and existing group animation/lighting.
- Add opt-in `preserve_proportions` with uniform primitive `scale`, optional/ignored size and unchanged authored coordinates.
- Accept uniformly integer-scaled base/mask dimensions in either direction; retain native-resolution mask detail.
- Add mesh-only alpha masks, LINEAR/STEP transfer, thresholds, and summed U/V animation terms on the existing idle clock.
- Add immutable indexed `TriangleMesh`, visual resource dependencies and a per-generation loading cache.
- Extend `FrameScene` with `VisualResources` and `DrawBatch` with typed material state; retain old constructors.
- Sort mesh transparency after legacy draws; preserve legacy billboard/ring behavior and anchor API v2.
- Raise additive definition schema to 1.1.0. Storage and network formats are unchanged.
- Keep orientation finite when halo and head origins coincide (zero positioning offset), using the head-up limiting direction.

## 1.3.1 — 2026-09-12

Accepted HaloCore baseline after Halo 1.20.1 Fabric multiplayer validation.

- Add an optional `ClientRuntime` missing-definition warning callback for host-localized feedback.
  Warnings are immediate, throttled across entities for 30 seconds, and reset with world/session state.
  Missing resources continue to preserve ownership and recover on reload.
- Keep definition schema 1.0.10 and the existing anchor API v2 unchanged.

## 1.3.0

Initial extraction from Halo `1.20.1-fabric` at `b30dad7` (including its preceding local changes).
The functional version and JSON schema 1.0.10 are unchanged.

- Plain Java 17 build, independent fixtures/tests, no Minecraft/Fabric/Loom/GPU dependency.
- Separate server authority, client presentation, local ownership and connection mode services.
- Source-scoped atomic definition reloads; neutral frame, body-pose and ordered geometry contracts.
- Retained damping/orientation, transition curves, hierarchical animation, ring/billboard geometry,
  and render-scoped anchor API v2 behavior.
- Explicit time, storage, message, diagnostics and configuration snapshot boundaries.

New adapters should use the documented contracts in README. Retained implementation packages are
not an additional public compatibility promise. The externally documented anchor API v2 remains stable.
