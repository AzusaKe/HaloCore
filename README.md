# HaloCore 2.3.1

Java 17 core of Halo. This repository builds without Minecraft, Fabric, Loom or a graphics context.
The host owns file/resource I/O, game objects, byte codecs, thread dispatch and GPU submission.

```sh
./gradlew build
```

Runtime dependencies are explicitly Gson 2.10 and JOML 1.10.5. They are implementation dependencies,
not shaded into the core jar. Tests use versioned fixtures under `src/test/resources/definitions`.
`check` also verifies the resolved dependencies and scans production bytecode for platform references.

## Adapter entry points

| Stage | Contract |
| --- | --- |
| Resources | `ResourceInput`, `DefinitionResources.reload(priority, resources)`, opaque `DefinitionSnapshot` |
| Server ownership | `ServerRuntime`, `OwnershipStore`, `Updates` |
| Client | `ClientPort` implemented by one `ClientRuntime` per logical client |
| Local ownership | `LocalOwnership.TextStore`, `restoreInto(serverKey, client)` |
| Frame input | `FrameScene`, entity/camera samples, scalar fallback plus optional block/sky light callbacks, and texture lookup |
| Frame output | `FrameOutput` with legacy `DrawBatch` or ordered `PrimitiveDraw` values plus lightweight `MeshDraw` commands; `BodyPose` observations before visual animation |
| Player/UI previews | Optional `PreviewPort.openPreview([PreviewOptions])` → `PreviewSession.render(PreviewFrame)` → existing `FrameOutput` |
| Head anchors | unified `network.azusake.halo.api.v2.HaloAnchorApi` for world and preview |
| Preview anchor providers | `api.v2.AnchorSource.submitPreview` and the neutral `PreviewAnchorHost` bridge |
| Config/diagnostics | `RuntimeConfigSnapshot`, `ClientStatus`, `Diagnostics.Sink` |

These contracts use core/JDK value types. `data`, `animation`, `physics`, `shape`, `render`,
and the geometry writer are implementation packages retained from Halo to preserve numerical behavior.
Their Java-public math helpers are **not stable platform APIs**. In particular, an adapter should not
build JOML-backed definition graphs or mutate `HaloInstance`. Legacy definition inspection is available
through `DefinitionResources.legacyDefinitions()` for the original Halo commands.

Create separate server and client runtimes, even in one JVM. Server events invoke `show`, `hide`,
`restore`, `unload`, and `died`. `Updates` carries existing attach/remove semantics; the adapter decides
how to encode/send them. A full synchronization uses `ClientPort.replace`. Duplicate attach/remove
messages are idempotent. Only the client initiates shutdown animation when receiving `hide`.

Publish each source as a complete reload (higher numeric priority wins); never mutate a published
snapshot. Minecraft uses priority 0 for server data packs and 1 for client resource packs. An empty
reload removes only that source. Invalid resources produce diagnostics, while valid resources publish
together. Definitions missing on the client do not revoke ownership.

Hosts can construct `ClientRuntime(clock, missingDefinitionWarning)` to receive a missing definition's
`Identifier` on the client thread while rendering a loaded entity. Core throttles these callbacks to
one warning per 30 seconds across all entities; the first warning is immediate. The host supplies
localized chat or other feedback. World/session resets clear the throttle, and reloading definitions
allows rendering to resume without reattaching the halo. The existing constructors remain available
for hosts that only need diagnostic logging. Callbacks must not mutate the runtime during rendering.

## Primitive backends (since 2.3.1)

`FrameScene` and `PreviewFrame` accept `PrimitiveRenderMode.COMPATIBILITY` (default for every old
constructor) or `CACHED`. The host samples this setting once at its frame boundary for both views.
Switching output mode is not a new simulation session and must not reset appearance or physics.

`FrameOutput` contains either `legacyBatches()` or `primitiveDraws()`, never both. Preserve the
source order and existing submission stage of these legacy primitives, followed by the established
OBJ mesh submission policy. Blending on a `PrimitiveDraw` does not request mesh-style sorting.
The command owns copied column-major local-to-view and normal matrices, immutable geometry,
legacy batch state, and vertex brightness. The explicit normal transform includes singular/facing
fallbacks and cannot always be derived from the position matrix. Use the exact legacy material,
light, byte color conversion, alpha cutoff and depth/cull behavior of the host.

`PrimitiveGeometry` carries a static indexed triangle mesh and its compatibility topology. CPU
billboards still use four corners; indexed billboards use `(0,1,2), (2,3,0)`. Rings preserve each
inner/outer source triangle and distinct seam UVs. Reflection does not reverse legacy winding.
`expandedBatches(resources)` or `PrimitiveDraw.expand()` expands the completed frame without
advancing simulation again. Never submit a cached command and its fallback together.

Prepare `DefinitionSnapshot.primitiveGeometries()` and `legacyTextures()` during client loading
or definition refresh, before rendering. The read-only mesh asset dependency API retains its old
meaning. Geometry is shared by shape within this snapshot, while dedicated servers need not
prepare it. Invalid geometry is diagnosed and isolated. Hosts own GPU upload, generation/format
invalidation, upload-failure memoization, resource removal and disposal. Compatibility mode need
not allocate any procedural GPU buffers. Resource generations do not imply animation resets.

`check` runs frozen 2.3.0 world/preview/output constructor bytecode against the current core jar,
in addition to the existing API/provider and 2.1.2 adapter checks.

## Preview anchor providers (since 2.3.0)

The unified loader-neutral head-anchor API is `network.azusake.halo.api.v2`:
`HaloAnchorApi.register(id)` returns a closeable `AnchorSource`. In the model's render hook,
read `currentPreviewContext()` and call `source.submitPreview(context, pose)` with a `PreviewAnchorPose` after the real head transform is final.
Providers never own a physics session, resources or GPU submission. Compile against Halo's API;
do not bundle duplicate API/core classes into a compatibility mod.

Providers require Halo/core 2.3.0 or newer and should declare that minimum in their loader metadata.
The v2 package keeps its existing class names, method descriptors, constructors and coordinate/lifetime
semantics across future platform adapters. Extend it additively; incompatible contracts need a new
API namespace while the published v2 contract remains available. Game-specific model hooks may still need separate builds.

The context identifies both the real wearer and the rendered entity (a UI proxy may differ), and
one lexical draw invocation. It supplies a copied column-major `sceneToView` matrix without projection.
Pose positions are in block-sized preview scene space before that matrix, without GUI scale/mirror,
halo offsets or physics; `AnchorRotation` retains +Y head-up / +Z head-forward. Do not submit world
coordinates or pixel coordinates. Contexts cannot be forged or retained for later frames.

The first valid model submission wins over vanilla fallbacks. Later submissions return false;
closing the winner invalidates its current contribution, allowing fallback or a new submission.
Rendered fallback takes precedence over posed-only fallback, each accepting its first valid sample.
Wrong-thread, unrelated-entity, closed, stale and suspended outer-view submissions are rejected.
`isPreviewRendering()` also covers unrelated entity draws and invalidated scopes still unwinding;
`currentPreviewContext()` is null there. Source registration survives view/world changes until closed.

Hosts use one `core.runtime.PreviewAnchorHost` per logical client and a try-with-resources
`PreviewAnchorScope` per draw. Bracket the entity dispatcher with the host's begin/end hooks,
submit vanilla fallbacks, then consume `resolved()` in the existing preview rendering pipeline.
The host has an overload mapping wearer UUID/runtime ID to a separate rendered UUID/runtime ID.
For a nonzero preview camera, supply the full `sceneToView = rootTransform * Translate(-camera.position)`
using column vectors: `PreviewFrame` subtracts camera position before applying its root. Vanilla GUI
uses a zero camera, so the matrices coincide. Projection is excluded from both matrices.
`clear()` invalidates contributions immediately; lexical scopes must still close in reverse order
on the owning thread so interrupted GUI renders cannot fall through into a world scope.

No public contract contains Minecraft, loader, Mixin or JOML types. The former Minecraft-specific
`HaloPreviewApi` facade is removed in 2.3.0; platform drawing helpers are adapter internals.
An independent provider fixture compiles with only public API classes and runs with the core jar
and JDK alone. These interfaces do not require frozen or older adapters to implement previews.

One `HaloAnchorApi.register(id)` returns one `AnchorSource` for either or both render spaces.
World-only providers call `submit(uuid, worldPose)`; preview-only providers call `submitPreview`.
The untouched space is unaffected: no automatic pose copy, fallback suppression or cross-cache writes.
The first preview model submission wins; world samples retain their last-accepted-sample rule and
current/previous-frame entity cache. GUI world submissions are rejected, even inside a world scope.
All public types live in `api.v2`; the separate preview API package and entry point are removed.

Source IDs are unique across both spaces. `close()` invalidates only this source's contributions in
both spaces. Use different IDs when world and preview need independent disposal. Closing an old
handle after re-registration does not invalidate the replacement. Built-in YSM/EMF providers share
one handle each between their world and preview hooks. Core's PreviewFrame reuses AnchorPose as
a value with preview-space semantics; both paths share appearance, physics, animation and geometry
implementations while motion and capture state remain isolated.

## Preview contract (since 2.2.0)

`ClientRuntime` additionally implements `PreviewPort`. The original `ClientPort`, `FrameScene`,
draw-command signatures and constructors remain available. An older adapter need not implement or
call the preview API. `check` compiles a caller against the frozen 2.1.2 `ClientPort` and runs that
unchanged bytecode against the new core jar; the frozen interface is absent from the runtime classpath.

1. Advance the owning client once per logical render frame using its normal `renderFrame` (or legacy
   `render`) call. Include loaded wearers in `FrameScene.entities` even when their world models are
   outside the camera or the local player is in first person. Appearance sampling precedes world
   distance culling. Calling both world entry points for one frame would advance the simulation twice.
2. Each UI view owns one `PreviewSession`, acquired with `openPreview()` and closed when that view
   ends. The session itself is the view identity; multiple sessions may render the same UUID. Calls
   must use the owning client's thread. Nested views use separate sessions.
3. Supply the wearer's UUID **and current entity runtime ID**, actual preview head `AnchorPose`,
   camera, root matrix, full-bright/native `LightSample`, texture availability and `VisualResources`.
   The pose is in a host-defined scene measured in blocks, with the existing local +Y head-up / +Z
   head-forward quaternion contract. GUI pixels, projection and mirror/scale belong to the adapter.
   Core subtracts `camera.position` in scene coordinates before applying the column-major 4×4
   `rootTransform`. The resulting coordinates are view space, with more negative Z farther away;
   camera up/right are in that resulting space. A GUI typically uses camera position zero.
4. `Projection.ORTHOGRAPHIC` is the default: `face_camera` uses a parallel camera basis, independent
   of the preview's screen position. Use `PERSPECTIVE` for a perspective host camera. Do not include
   the projection matrix in the root transform; apply projection when submitting `FrameOutput`.
5. Rendering consumes the **latest completed owning-client appearance snapshot**. Preview clock
   fields describe the view sample; they do not
   advance or restart ownership, startup/shutdown, idle animation, sleep or invisibility state.
   The default rigid base follows the current head with no physics or damping; authored offsets, scales,
   static rotations and visual animation continue through the shared primitive/material pipeline.
   FREE/LOCKED/SYNC do not select different rigid-preview behavior. World physics, snap flags and
   `bodyPoses()` remain untouched.
6. No matching appearance, unloaded/dead wearer, mismatched runtime ID or stale visual-resource
   generation produces empty output. Definition reload invalidates the previous snapshot until
   the next world frame. Missing definitions preserve ownership so it can recover after reload.
   `clear()`, `replace()` and a world-token change invalidate existing sessions; create new sessions
   for the new client/world scope. `close()` is idempotent; closed or invalidated sessions stay empty.

### Optional preview physics

Use `openPreview(PreviewOptions.PHYSICS)` to opt in per view. The original `openPreview()` remains
rigid. Older `PreviewPort` providers inherit a default overload supporting `RIGID` and rejecting
unsupported physics explicitly; they need not implement new methods to keep their existing behavior.

- Keep the session across frames. It owns its calculator, frame-time smoothing and orientation state;
  it never reads or consumes the world instance's teleport/snap flag. Its head motion is measured in
  preview scene blocks, before the GUI root matrix, so moving/rescaling a GUI does not create forces.
- Physical views reuse the world calculator, parameter merge, linear/angular damping, angular
  momentum, displacement limits, FREE/LOCKED/SYNC semantics and EMA time clamp. They share exactly
  the same visual/geometry path as rigid views. This adds no independent physics configuration model.
- Supply a monotonically increasing `frameNanos` per view sample. Repeated submissions of the same
  sample retain the computed body; visual/GUI transforms may still change. A backward clock resets
  motion. First use snaps to the current target as in the world. After a long interval the same world
  delta clamp applies; hosts should call `resetMotion()` for discontinuous scene/model changes.
- Switching wearer/runtime identity or definition, an unavailable appearance/resource generation,
  and closing a session clear its motion. `resetMotion()` resets only the view, without replaying the
  shared startup animation. `isValid()` reports closed/world-invalidated sessions so hosts can recreate
  them. Both methods have defaults for old providers.

The persisted flag defaults to `playerPreviewHaloPhysicsEnabled=true`; a platform may use it to select
automatic preview sessions. Missing fields are backfilled; an explicit `false` selects rigid motion.
Explicit API callers select their own `PreviewOptions`. No platform type
or GUI identity enters core, and definition schema, storage, protocol and API v2 remain unchanged.

The implementation snapshot (`render.HaloAppearance`) is internal, not an adapter API. Hosts never
construct definition graphs or mutate `HaloInstance`. Submit scene-space preview heads through
`AnchorSource.submitPreview`, never through the world-space `submit` method. Preview physics and third-party model capture live behind
this independent capability without adding methods to older adapters' `ClientPort` implementations.

## Frame and coordinate contract

- Run all client mutations/rendering on one owning thread. The anchor API accepts submissions inside
  the host's main-camera entity scope and retains at most the previous frame. Scope world identities
  are opaque tokens; source registrations intentionally survive disconnects.
- Supply an explicit world token, entity UUID/runtime ID, interpolated world position, alive/sleeping/
  invisible facts, resolved/fallback anchors, camera basis, and both millisecond and monotonic nanosecond
  times. Teleport events call `teleport`; changing world tokens clears transient physics/visual caches.
- World positions use double precision in blocks: +X east, +Y up, +Z south for the Minecraft adapter.
  Anchors use normalized quaternions `(x,y,z,w)`, local +Y head-up and +Z head-forward. Definition-local
  -Y points toward the head. Existing JSON Euler values are degrees, with Y-X-Z rotation order.
- `BodyPose` is definition-local → world rotation and world origin; `visualScale` is separate.
  Physics never consumes the visual animation result.
- The host supplies a column-major 4×4 root view transform. Core subtracts the camera position using
  doubles before float matrix transforms. It then applies body translation/rotation/scale, definition
  animation T/R/S, and each group's local T/R/S followed by group animation or transition T/R/S.
- Legacy `DrawBatch` vertices are already in the supplied view space. `MeshDraw` instead retains authored
  indexed geometry and supplies a column-major local-to-view transform. Consume both lists in order. Bind the given texture, tint, blend, culling, depth-test
  and depth-write state; `blend=true` uses the existing standard alpha blend. Light is sampled in world
  space and emission is already included in the output color. Billboard/ring UV conventions are unchanged.
- Light/texture callbacks are read-only facts for the frame. New adapters provide a `LightSample` with separate
  block/sky levels at the halo root; non-glowing commands retain that sample for the host lightmap, while glowing
  commands request full-bright and keep `animation.glow` as their color multiplier. The original scalar callback
  and constructors remain a compatibility fallback for adapters that predate native lightmaps. Non-glowing commands
  also request directional lighting and carry normalized surface normals; glowing commands explicitly retain flat
  lighting. Older `DrawBatch`, `DrawBatch.Vertex`, `MeshDraw`, and `TriangleMesh` constructors remain source-compatible
  and default to the earlier flat contract. Millisecond time is shared across animation
  stages; nanosecond deltas drive the retained EMA and damping clamp. Fake clocks enable replay tests.

## Mesh adapter contract (2.1.0)

`DefinitionSnapshot.assets()` exposes the deduplicated model and texture IDs required by mesh primitives.
`VisualAssetLoader` accepts a host `Source` for OBJ text and decoded texture metadata; create a new loader
for each resource reload. It caches successes and failures by resource ID, reports each failed read once,
and produces immutable `VisualResources` snapshots. Definitions may change within a generation without
reparsing shared assets. `TriangleMesh` owns indexed positions, UVs and normals; its bounds use referenced vertices.

Supply one `VisualResources` snapshot in `FrameScene.visuals()`. The old constructor supplies an empty
snapshot, retaining old primitive behavior. Missing mesh assets skip that primitive without revoking
ownership. Publish definitions and their visual resources together on the client owner thread; perform
resource reading, OBJ parsing, image inspection and texture uploads in the loading stage, never in draw.

`DrawBatch.material()` is `MaterialState.LEGACY` for old primitives, including batches constructed with
the old constructor. `MaterialState.Mesh` requests base-texture rendering with an optional evaluated
`AlphaMask`. Its offsets are already wrapped into [0,1); sample mask R at `fract(baseUV + offset)` with
nearest repeating level-zero sampling, ignoring mask alpha and applying no sRGB conversion. LINEAR uses
the gray value; STEP uses `gray >= threshold`. Multiply the result by base texture alpha and batch alpha
exactly once, discarding only final alpha <= 0. Never substitute the legacy 0.1 cutoff.
Base/mask dimensions and aspect ratios are independent. Each texture keeps its native resolution in the
same normalized UV domain; do not downsample or create enlarged texture copies. The retained
`TextureInfo.hasIntegralScaleWith` method is informational compatibility API, not a render-validity rule.

Core submits old primitives in their original order, then depth-writing meshes, then transparent meshes
sorted by instance center. Hosts use one reusable `MeshIndexWriter` per cached mesh to preserve stable
back-to-front triangle-center ordering and mirrored winding. The regular writer methods target the mesh's
unique-vertex stream; `writeExpandedSourceOrder` and `writeExpanded` target a triangle-corner-expanded stream for
host formats that derive tangents or other attributes from consecutive triangle submissions. Honor each command's depth/blend/cull flags.
`ClientPort.renderFrame` exposes this path; the original `render` expands meshes for compatibility.
Transparency sorting is not global with the host world's translucent surfaces. Geometry, emission selection,
light samples, animation and index ordering are platform independent; interpreting block/sky samples through a
version's lightmap, shader programs and GPU resource lifetimes belong exclusively to the host.

Mesh `size:[x,y,z]` fits the authored bounds in blocks by axis, without moving the exported origin or
changing axes. Group transforms apply afterwards. Zero source extent requires target size=0 and uses
scale=1 on that axis. Optional primitive `preserve_proportions` defaults to false. When true, `size` may
be omitted and is ignored; primitive `scale` (default 1, finite nonnegative scalar) uniformly multiplies
authored coordinates about their origin before group transforms, with no bounds fitting or zero-extent
restriction. When false, primitive `scale` has no effect. Supplied size/scale fields are still validated.
The old four-argument `MeshPrimitive` constructor retains the size-fitting behavior.
UV V is flipped only by OBJ import. The parser supports textured triangles and
planar convex quads, positive/negative independent indices and optional normals. Authored `vn` values are normalized
and remain part of the corner-deduplication key so hard edges survive. Missing normals are generated per face; indexed
zero normals from older exporters retain their OBJ index slot but use the same fallback. MTL and names are
ignored; unsupported polygons require export-time triangulation. Limits are 16 MiB text, 1,000,000
declared position/UV/normal elements combined, and 250,000 triangles; these are load guards, not frame
rate promises. Adapters should cache indexed vertex geometry by visual-resource generation; a host that
uses the compatibility expansion path still pays per-frame transformation and upload costs.

## Versioning

Feature version is in `gradle.properties`; the current release is **2.3.0**;
the release tag is **v2.3.0**,
schema **1.1.0**.
The first mesh release was **2.0.0**.
The earlier refactor baseline is **1.3.1**, schema **1.0.10**; old definitions remain supported.
The first extraction was released as **1.3.0**; published version tags remain immutable.
Halo embeds this repository with a Git submodule and resolves `network.azusake:halo-core` using a Gradle
composite build. Its gitlink, not a moving branch or this version string, pins exact source.

Develop on a core branch with the adapter. Run both test suites, commit core, tag/release the completed
feature version, then update and commit the adapter's gitlink. Describe interface changes here and in
`CHANGELOG.md`. A dormant adapter can later move directly to a selected core commit and implement the
contract changes; it does not need copies of the business algorithms from intermediate Halo branches.
