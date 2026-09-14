# HaloCore 2.1.2

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
| Frame output | `FrameOutput` with legacy `DrawBatch` values and lightweight `MeshDraw` commands; `BodyPose` observations before visual animation |
| External anchors | unchanged `network.azusake.halo.api.v2` |
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

Feature version is in `gradle.properties`; the current source version is **2.1.2** and the latest tagged
release is **2.1.0**, schema **1.1.0**.
The first mesh release was **2.0.0**.
The earlier refactor baseline is **1.3.1**, schema **1.0.10**; old definitions remain supported.
The first extraction was released as **1.3.0**; published version tags remain immutable.
Halo embeds this repository with a Git submodule and resolves `network.azusake:halo-core` using a Gradle
composite build. Its gitlink, not a moving branch or this version string, pins exact source.

Develop on a core branch with the adapter. Run both test suites, commit core, tag/release the completed
feature version, then update and commit the adapter's gitlink. Describe interface changes here and in
`CHANGELOG.md`. A dormant adapter can later move directly to a selected core commit and implement the
contract changes; it does not need copies of the business algorithms from intermediate Halo branches.
