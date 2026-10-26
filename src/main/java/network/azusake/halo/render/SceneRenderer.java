package network.azusake.halo.render;

import network.azusake.halo.data.HaloTransitionState;
import network.azusake.halo.animation.LayerAnimation;
import network.azusake.halo.animation.StartupAnimationConfig;
import network.azusake.halo.animation.TransitionAnimationResult;
import network.azusake.halo.animation.TransitionResolver;
import network.azusake.halo.data.GroupVisualSnapshot;
import network.azusake.halo.data.HaloDefinition;
import network.azusake.halo.data.HaloInstance;
import network.azusake.halo.physics.AnchorFrame;
import network.azusake.halo.physics.AnchorFrameCalculator;
import network.azusake.halo.shape.HaloPrimitive;
import network.azusake.halo.shape.RingPrimitive;
import network.azusake.halo.shape.BillboardPrimitive;
import network.azusake.halo.shape.HaloGroup;
import network.azusake.halo.shape.MeshPrimitive;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import network.azusake.halo.core.Diagnostics.Logger;
import network.azusake.halo.core.Diagnostics;

import java.util.*;
import network.azusake.halo.core.*;
import network.azusake.halo.core.render.*;
import network.azusake.halo.core.runtime.ClientRuntime;
import network.azusake.halo.core.render.FrameScene.EntitySample;
import network.azusake.halo.core.render.FrameScene.CameraSample;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.function.Consumer;

/**
 * Renders halo models at their computed world-space {@link AnchorFrame}.
 *
 * <p>All pose computation is delegated to {@link AnchorFrameCalculator}.
 * This class is a <em>pure rendering consumer</em> — it receives an
 * {@link AnchorFrame} and draws each layer of the {@code HaloModel}
 * with its own local transform relative to the anchor frame origin.</p>
 *
 * <h3>Billboard convention</h3>
 * <p>Billboard primitives are drawn on the <b>XZ plane</b> (horizontal,
 * normal = -Y in definition-local space).  The layer's accumulated
 * transform (anchor frame + layer local) determines the final world-space
 * orientation — no separate billboard-facing rotation is applied.</p>
 */
public final class SceneRenderer {

    private static final Logger LOG = Diagnostics.logger("halo");

    private final ClientRuntime runtime;
    private final Consumer<Identifier> missingDefinitionWarning;
    private GeometryCollector draw;
    private boolean parallelFacing;
    private List<SceneDraw> retained;
    private long retainedRevision;
    private FrameScene retainedInput;
    private UUID retainedWearer;
    private Vec3d retainedOrigin;
    private Identifier retainedDefinition;
    private String retainedPath;
    private int retainedPrimitive, retainedPart;
    private SceneDraw.Kind retainedKind;

    public SceneFrame renderScene(FrameScene input, long definitionRevision) {
        retained = new ArrayList<>(); retainedInput = input; retainedRevision = definitionRevision;
        try {
            renderHalos(input);
            return new SceneFrame(input.worldToken(),input.visuals().generation(),retained);
        } finally {
            retained=null; retainedInput=null; meshDraw.sink(null);
        }
    }
    private SceneDraw.Key retainedKey() {
        return new SceneDraw.Key(retainedWearer,retainedInput.entities().get(retainedWearer).runtimeId(),
            retainedDefinition,retainedRevision,retainedPath,retainedPrimitive,retainedPart++);
    }
    private void retainPrimitive(PrimitiveDraw command) {
        var state=command.state();
        retained.add(new SceneDraw(retainedKey(),retainedKind,command.geometry().mesh(),retainedOrigin,
            command.localToView(),command.normalToView(),state.texture(),state.textured(),state.cull(),
            !state.directionalLighting(),command.brightness(),state.alpha(),state.material()));
    }
    private void retainMesh(MeshDraw command) {
        var transform=new Matrix4f().set(command.localToView());
        var normal=RetainedNormalMatrix.from(transform);
        retained.add(new SceneDraw(retainedKey(),SceneDraw.Kind.MESH,
            retainedInput.visuals().meshes().get(command.model()),retainedOrigin,command.localToView(),
            normal.get(new float[9]),command.texture(),true,command.cull(),!command.directionalLighting(),
            command.red(),command.alpha(),command.material()));
    }
    private final MeshGeometryRenderer meshDraw = new MeshGeometryRenderer(message -> LOG.warn("[Halo mesh] {}", message));
    private final Map<UUID, HaloAppearance> appearances = new LinkedHashMap<>();
    public HaloAppearance appearance(UUID wearer) { return appearances.get(wearer); }
    public void clearAppearances() { appearances.clear(); }
    private final Map<UUID,BodyPose> bodyPoses = new LinkedHashMap<>();
    private final Map<UUID,AnchorFrame> replayBodies = new HashMap<>();
    private final Map<UUID,HaloAppearance> replayAppearances = new HashMap<>();
    private Long replayNanos;
    public Map<UUID,BodyPose> bodyPoses() { return Map.copyOf(bodyPoses); }
    public void clearEntity(UUID uuid) {
        replayBodies.remove(uuid); replayAppearances.remove(uuid);
        appearances.remove(uuid); frameCalculator.clearEntity(uuid); idlePhaseTracker.remove(uuid); bodyPoses.remove(uuid);
        prevSleepHidden.remove(uuid); prevInvisHidden.remove(uuid);
    }

    /**
     * One missing-definition warning per client every 30 seconds, shared by all entities.
     * Null allows an immediate first warning even when replay time starts at zero.
     */
    private Long lastMissingDefWarningTime;

    private final AnchorFrameCalculator frameCalculator = new AnchorFrameCalculator();

    /** Per-entity previous sleep-hidden state, for edge detection. */
    private final Map<UUID, Boolean> prevSleepHidden = new HashMap<>();
    /** Per-entity previous invis-hidden state, for edge detection. */
    private final Map<UUID, Boolean> prevInvisHidden = new HashMap<>();
    /**
     * Last rendered render state per halo (idle phase, transition-active
     * flag, and the per-group offset/scale/alpha values drawn during a
     * transition). Owned by this client runtime; used internally to align shutdown animation.
     */
    private final IdlePhaseTracker idlePhaseTracker = new IdlePhaseTracker();

    private final network.azusake.halo.physics.FrameDelta frameDelta = new network.azusake.halo.physics.FrameDelta();

    public SceneRenderer(ClientRuntime runtime) { this(runtime, id -> {}); }
    public SceneRenderer(ClientRuntime runtime, Consumer<Identifier> missingDefinitionWarning) {
        this.runtime = runtime;
        this.missingDefinitionWarning = missingDefinitionWarning;
    }

    /**
     * The last rendered render state of a halo (idle phase + whether the last
     * frame was inside a transition + per-group visual values), or {@code null}
     * when there is no fresh record. ClientRuntime uses this when handling a hide operation.
     *
     * @param uuid the halo's entity UUID
     * @return the last render state, or null when never rendered recently
     */
    public IdlePhaseTracker.RenderState readLastRenderState(UUID uuid) {
        return idlePhaseTracker.read(uuid, runtime.nowMillis());
    }

    /** Drop all recorded phases (full sync / world change). */
    public void clearIdlePhases() { idlePhaseTracker.clear(); }
    public void clearWorld() {
        resetMotion();
        appearances.clear(); bodyPoses.clear(); clearIdlePhases(); frameCalculator.retainOnly(Set.of());
        prevSleepHidden.clear(); prevInvisHidden.clear(); frameDelta.reset();
        lastMissingDefWarningTime=null;
    }

    public void resetMotion() {
        replayNanos=null; replayBodies.clear(); replayAppearances.clear();
        bodyPoses.clear(); frameCalculator.retainOnly(Set.of()); frameDelta.reset();
    }

    public void restoreVisibility(UUID uuid, boolean sleeping, boolean invisible) {
        HaloInstance instance=runtime.getInstance(uuid);
        HaloDefinition definition=instance==null?null:runtime.definition(instance.getDefinitionId()).orElse(null);
        prevSleepHidden.put(uuid, definition!=null && definition.hideOnSleep() && sleeping);
        prevInvisHidden.put(uuid, definition!=null && !definition.displayInInvisible() && invisible);
    }

    // ------------------------------------------------------------------
    // Public entry point
    // ------------------------------------------------------------------

    /**
     * Render every visible halo for the current frame.
     */
    public FrameOutput renderHalos(FrameScene scene) {
        parallelFacing = false;
        bodyPoses.clear();
        appearances.clear();
        this.draw = new GeometryCollector(scene.textures(), scene.primitiveMode());
        meshDraw.begin(scene.visuals());
        meshDraw.sink(retained == null ? null : this::retainMesh);
        if (retained != null) draw.sink(this::retainPrimitive);
        FrameScene client = scene;
        MatrixStack matrices = new MatrixStack(retained == null ? scene.rootTransform() : new Matrix4f().get(new float[16]));
        CameraSample camera = scene.camera();
        float tickDelta = 0;
        idlePhaseTracker.prune(runtime.nowMillis());

        var visible = runtime.getAllInstances().stream().filter(inst -> {
            EntitySample e = scene.entities().get(inst.getEntityUuid());
            if (e == null || !e.isAlive()) return false;
            inst.setEntitySleeping(e.isSleeping()); inst.setEntityInvisible(e.isInvisible());
            return inst.isActive();
        }).toList();

        // Clean stale entries from the frame calculator's internal maps
        Set<UUID> activeUuids = visible.stream()
            .filter(inst -> runtime.isReplay() || scene.entities().get(inst.getEntityUuid()).position().squaredDistanceTo(camera.getPos()) <= 65536)
            .map(HaloInstance::getEntityUuid)
            .collect(Collectors.toSet());
        frameCalculator.retainOnly(activeUuids);

        // Compute frame delta with EMA smoothing to suppress nanoTime jitter
        boolean newReplaySample = replayNanos == null || replayNanos != scene.frameNanos();
        final double dt = runtime.isReplay()
            ? replayNanos==null?0:Math.max(0,(scene.frameNanos()-replayNanos)/1_000_000_000.0)
            : frameDelta.advance(scene.frameNanos());
        if (runtime.isReplay() && newReplaySample) { replayNanos=scene.frameNanos(); replayAppearances.clear(); }

        for (HaloInstance instance : visible) {
            try {
                HaloAppearance appearance = runtime.isReplay() && replayAppearances.containsKey(instance.getEntityUuid())
                    ? replayAppearances.get(instance.getEntityUuid()) : prepareAppearance(instance, client);
                if (runtime.isReplay()) replayAppearances.put(instance.getEntityUuid(),appearance);
                if (appearance != null) {
                    appearances.put(instance.getEntityUuid(), appearance);
                    EntitySample entity = scene.entities().get(instance.getEntityUuid());
                    if (runtime.isReplay() || entity.position().squaredDistanceTo(camera.getPos()) <= 65536) {
                        AnchorFrame frame;
                        if (runtime.isReplay()) {
                            frame = replayBodies.get(instance.getEntityUuid());
                            if (newReplaySample || frame == null || instance.isNeedsSnap()) {
                                // Consume all source time. Small steps avoid the legacy per-step clamp at high replay speeds.
                                double remaining=dt;
                                do {
                                    double step=Math.min(remaining,.05);
                                    frame=frameCalculator.calculate(instance,entity.anchor(),entity.fallbackAnchor(),
                                        appearance.definition(),Vec3d.ZERO,step,runtime.getConfig());
                                    remaining-=step;
                                } while(remaining>1e-9);
                                replayBodies.put(instance.getEntityUuid(),frame);
                            }
                            frame=new AnchorFrame(frame.worldPosition(),frame.worldPosition().subtract(camera.getPos()),
                                frame.worldOrientation(),frame.worldForward(),frame.toHeadDirection(),frame.scale());
                        } else frame = frameCalculator.calculate(instance, entity.anchor(), entity.fallbackAnchor(),
                            appearance.definition(), camera.getPos(), dt, runtime.getConfig());
                        var rotation = frame.worldOrientation();
                        bodyPoses.put(instance.getEntityUuid(), new BodyPose(frame.worldPosition(),
                            new network.azusake.halo.api.v2.AnchorRotation(rotation.x, rotation.y, rotation.z, rotation.w), frame.scale()));
                        Vec3d crp = frame.cameraRelativePos();
                        if (Math.abs(crp.x) <= 1000 && Math.abs(crp.y) <= 1000 && Math.abs(crp.z) <= 1000) {
                            LightSample light = Objects.requireNonNull(scene.lightmaps().sample(frame.worldPosition()));
                            float brightness = light.available() ? 1f : Math.max(scene.lights().brightness(frame.worldPosition()), .04f);
                            if (retained != null) {
                                retainedWearer=instance.getEntityUuid(); retainedDefinition=instance.getDefinitionId();
                                retainedOrigin=frame.worldPosition();
                            }
                            renderAppearance(appearance, frame, matrices, camera, light, retained == null ? brightness : 1f);
                        }
                    }
                }
            } catch (Exception e) {
                LOG.warn("[SceneRenderer] error rendering halo for entity {}: {}", instance.getEntityUuid(), e.getMessage(), e);
            }
        }

        // Maintain non-active instances (NULL / deactivated):
        //   - NULL + hiddenByState (sleep/invis): keep while still hidden,
        //     reactivate with STARTING (or NORMAL if no startup anim) when visible
        //   - everything else non-active (explicit hide's NULL, ENDING stuck on an
        //     inactive instance, dead entity, missing def) → permanently remove
        List<UUID> removals = new ArrayList<>();
        for (HaloInstance inst : runtime.getAllInstances()) {
            if (inst.isActive()) continue; // active instances handled in renderSingleHalo

            UUID uuid = inst.getEntityUuid();
            EntitySample entity = SceneRenderer.findEntityByUuid(client, uuid);
            if (entity == null || !entity.isAlive()) {
                if (runtime.isReplay()) continue;
                removals.add(uuid);
                continue;
            }

            if (inst.getTransitionState() == HaloTransitionState.NULL && inst.isHiddenByState()) {
                HaloDefinition def = runtime.definition(inst.getDefinitionId()).orElse(null);
                if (def == null) continue; // preserve hidden state and its animation clock until resources return
                boolean sleepHidden = def.hideOnSleep() && entity.isSleeping();
                boolean invisHidden = !def.displayInInvisible() && entity.isInvisible();
                if (sleepHidden || invisHidden) {
                    continue; // entity still hidden — wait for wake-up
                }
                // Entity visible again → reactivate
                inst.reactivate();
                inst.setHiddenByState(false);
                inst.setEntitySleeping(entity.isSleeping());
                inst.setEntityInvisible(entity.isInvisible());
                if (def.startupAnimation().isPresent()) {
                    inst.setTransitionState(HaloTransitionState.STARTING);
                    inst.startTransition((runtime.nowMillis() - inst.getCreatedAtTime()) / 1000.0);
                } else {
                    inst.setTransitionState(HaloTransitionState.NORMAL);
                }
                continue;
            }

            // Permanently useless: explicit hide's NULL, inactive ENDING (e.g. hide
            // targeted an already-hidden halo), dead/missing entity, missing def
            removals.add(uuid);
        }
        for (UUID uuid : removals) {
            runtime.removeClientHalo(uuid);
            prevSleepHidden.remove(uuid);
            prevInvisHidden.remove(uuid);
        }
        FrameOutput meshOutput = meshDraw.finish(draw.batches());
        return new FrameOutput(meshOutput.visualGeneration(), meshOutput.legacyBatches(), meshOutput.meshes(), draw.primitiveDraws());
    }

    // ------------------------------------------------------------------
    // Single halo
    // ------------------------------------------------------------------

    /**
     * When a halo is hidden while still inside a transition (e.g. during its
     * startup), the shutdown must start from the values the renderer was
     * actually drawing — not from the idle animation — or the fade-out would
     * jump.  Reads the per-group visuals recorded on the last transition
     * frame and stashes them on the instance for {@link #resolveAnimation}.
     */
    private void stashMidTransitionVisuals(HaloInstance instance) {
        IdlePhaseTracker.RenderState renderState = idlePhaseTracker.read(
            instance.getEntityUuid(), runtime.nowMillis());
        if (renderState != null && renderState.transitionActive()
                && !renderState.groups().isEmpty()) {
            instance.setHideVisuals(renderState.groups());
        }
    }

    private HaloAppearance prepareAppearance(HaloInstance instance, FrameScene client) {
        // ---- resolve entity ----
        EntitySample entity = findEntityByUuid(client, instance.getEntityUuid());
        if (entity == null || !entity.isAlive()) {
            instance.deactivate();
            return null;
        }

        // ---- resolve definition ----
        HaloDefinition def = runtime.definition(instance.getDefinitionId()).orElse(null);
        if (def == null) {
            long now = runtime.nowMillis();
            if (lastMissingDefWarningTime == null || now - lastMissingDefWarningTime >= 30_000) {
                lastMissingDefWarningTime = now;
                LOG.warn("Missing halo definition: {}", instance.getDefinitionId());
                missingDefinitionWarning.accept(instance.getDefinitionId());
            }
            return null;
        }

        // ---- hide while sleeping (reads per-tick cache) ----
        boolean sleepHidden = def.hideOnSleep() && instance.isEntitySleeping();

        // ---- hide while invisible (reads per-tick cache) ----
        boolean invisHidden = !def.displayInInvisible() && instance.isEntityInvisible();

        boolean shouldRender = !sleepHidden && !invisHidden;

        // ---- Build transition context (used by state machine and rendering) ----
        StartupAnimationConfig startupConfig = def.startupAnimation().orElse(null);
        StartupAnimationConfig shutdownConfig = def.shutdownAnimation().orElse(null);

        // ---- State machine: sleep/invis edge detection (NORMAL → STARTING/ENDING) ----
        HaloTransitionState state = instance.getTransitionState();
        UUID uuid = instance.getEntityUuid();
        boolean prevSleep = prevSleepHidden.getOrDefault(uuid, false);
        boolean prevInvis = prevInvisHidden.getOrDefault(uuid, false);
        boolean wasHidden = prevSleep || prevInvis;
        boolean nowHidden = sleepHidden || invisHidden;

        if (state == HaloTransitionState.NORMAL) {
            if (!wasHidden && nowHidden) {
                // Entity entered sleep/invis → start shutdown animation
                if (shutdownConfig != null || startupConfig != null) {
                    instance.setHiddenByState(true);
                    // Align the shutdown head to the idle animation's actual
                    // phase right now (rawAnimTime - startupDur after a
                    // completed startup), not the raw wall-clock time.
                    double freeze = instance.currentAnimTime(startupConfig);
                    instance.setTransitionState(HaloTransitionState.ENDING);
                    instance.startTransition(freeze);
                    stashMidTransitionVisuals(instance);
                    state = HaloTransitionState.ENDING;
                }
            } else if (wasHidden && !nowHidden) {
                // Entity woke/became visible → start startup animation
                if (startupConfig != null) {
                    instance.setHiddenByState(false);
                    instance.setTransitionState(HaloTransitionState.STARTING);
                    instance.startTransition((runtime.nowMillis() - instance.getCreatedAtTime()) / 1000.0);
                    state = HaloTransitionState.STARTING;
                }
            }
        }
        prevSleepHidden.put(uuid, sleepHidden);
        prevInvisHidden.put(uuid, invisHidden);

        // ---- State machine: terminal state and transition completion ----
        if (state == HaloTransitionState.ENDING && !instance.isTransitioning(startupConfig, shutdownConfig)) {
            // ENDING animation complete → mark as NULL but keep in map for potential reactivation
            instance.setTransitionState(HaloTransitionState.NULL);
            instance.deactivate();
            prevSleepHidden.remove(uuid);
            prevInvisHidden.remove(uuid);
            return null;
        }
        if (state == HaloTransitionState.STARTING && !instance.isTransitioning(startupConfig, shutdownConfig)) {
            // STARTING animation complete
            if (!shouldRender) {
                // Entity still sleeping/invisible → skip NORMAL, mark as NULL for reactivation
                instance.setHiddenByState(true);
                instance.setTransitionState(HaloTransitionState.NULL);
                instance.deactivate();
                prevSleepHidden.remove(uuid);
                prevInvisHidden.remove(uuid);
                return null;
            }
            instance.setTransitionState(HaloTransitionState.NORMAL);
            state = HaloTransitionState.NORMAL;
        }

        // ---- Rendering gate ----
        if (state == HaloTransitionState.NULL) return null;
        if (state == HaloTransitionState.NORMAL && !shouldRender) return null;
        // STARTING and ENDING always render (animation must play regardless of shouldRender)

        // Determine if transition is currently active
        boolean transitionActive = (state == HaloTransitionState.STARTING || state == HaloTransitionState.ENDING)
            && instance.hasTransition();
        double transitionElapsed = instance.getTransitionElapsed();
        boolean isStartup = state == HaloTransitionState.STARTING;

        // ---- elapsed time since halo creation ----
        final double rawAnimTime = (runtime.nowMillis() - instance.getCreatedAtTime()) / 1000.0;

        // ---- adjust animTime so the periodic animation freezes during a
        // transition and resumes at its actual phase afterwards ----
        final double animTime;
        long transitionStart = instance.getTransitionStartTime();
        if (instance.hasTransition()
                && (state == HaloTransitionState.STARTING || state == HaloTransitionState.ENDING)) {
            // Transition in progress — freeze the periodic animation at the
            // phase captured when the transition started.
            animTime = instance.getTransitionFreezeAnimTime();
        } else if (instance.hasTransition()) {
            // NORMAL right after a completed startup — resume the periodic
            // animation from where it would have been, skipping the transition.
            double transitionDur = startupConfig != null ? startupConfig.maxDuration() : 0.0;
            animTime = Math.max(0.0, rawAnimTime - transitionDur);
        } else {
            animTime = rawAnimTime;
        }
        idlePhaseTracker.record(instance.getEntityUuid(), animTime, transitionActive, runtime.nowMillis());

        Map<HaloGroup, HaloAppearance.Group> evaluated = new LinkedHashMap<>();
        for (HaloGroup group : def.model().groups()) {
            sampleGroup(group, instance, animTime, transitionActive, transitionElapsed, isStartup,
                startupConfig, shutdownConfig, evaluated);
        }
        return new HaloAppearance(def, runtime.nowMillis(), animTime, evaluated);
    }

    /** Geometry-only entry point. Sessions have their own collector; shared client state is read-only. */
    public FrameOutput renderPreview(PreviewFrame input, HaloAppearance appearance) {
        return renderPreview(input, appearance, AnchorFrameCalculator.rigid(input.head(), appearance.definition(),
            input.camera().position(), runtime.getConfig()));
    }

    /** Both motion choices feed the exact same visual/geometry path. */
    public FrameOutput renderPreview(PreviewFrame input, HaloAppearance appearance, AnchorFrame frame) {
        parallelFacing = input.projection() == PreviewFrame.Projection.ORTHOGRAPHIC;
        draw = new GeometryCollector(input.textures(), input.primitiveMode());
        meshDraw.begin(input.visuals());
        renderAppearance(appearance, frame, new MatrixStack(input.rootTransform()), input.camera(), input.light(), 1f);
        FrameOutput meshOutput = meshDraw.finish(draw.batches());
        return new FrameOutput(meshOutput.visualGeneration(), meshOutput.legacyBatches(), meshOutput.meshes(), draw.primitiveDraws());
    }

    private void renderAppearance(HaloAppearance appearance, AnchorFrame frame, MatrixStack matrices,
                                  CameraSample camera, LightSample ambientLight, float brightness) {
        HaloDefinition def = appearance.definition();
        double animTime = appearance.animationTime();
        Vec3d crp = frame.cameraRelativePos();
        // ---- render model groups ----
        var model = def.model();
        if (model.groups().isEmpty()) {
            return; // empty model, nothing to draw
        }

        matrices.push();
        try {
            // Step 1: Anchor frame → world
            if (retained == null) matrices.translate(crp.x, crp.y, crp.z);
            applyQuaternionRotation(matrices, frame.worldOrientation());
            matrices.scale(frame.scale(), frame.scale(), frame.scale());

            // Step 2: Definition-level animation (whole-halo-body, visual only).
            // The definition animation acts as an implicit root group: its
            // alpha/glow become the initial inherited values for every top-level
            // group, so animation.alpha at the definition root fades the whole
            // halo (offset/rotation/scale still apply to the whole body).
            float defAlpha = 1.0f;
            float defGlow = 1.0f;
            var defAnimOpt = def.animation();
            if (defAnimOpt.isPresent()) {
                var defAnim = defAnimOpt.get();
                if (!defAnim.isEmpty()) {
                    Vec3d defOffset = defAnim.evaluateOffset(animTime);
                    Quaternionf defRot = defAnim.evaluateRotation(animTime);
                    float[] defScale = defAnim.evaluateScale(animTime);
                    matrices.translate(defOffset.x, defOffset.y, defOffset.z);
                    applyQuaternionRotation(matrices, defRot);
                    matrices.scale(defScale[0], defScale[1], defScale[2]);
                    defAlpha = defAnim.evaluateAlpha(animTime);
                    defGlow = defAnim.evaluateGlow(animTime);
                }
            }

            // Step 3: Recursive group rendering
            for (int root=0;root<model.groups().size();root++) {
                HaloGroup group=model.groups().get(root);
                if (retained != null) retainedPath=Integer.toString(root);
                // Root groups inherit the definition root's alpha/glow
                renderGroup(group, matrices, camera, appearance, brightness, ambientLight, defAlpha, defGlow);
            }
        } finally {
            matrices.pop();
        }

        // Clear any translucent shader tint left by the last transparent group
        draw.setShaderColor(1f, 1f, 1f, 1f);

    }

    // ------------------------------------------------------------------
    // Recursive group rendering (scene-graph traversal)
    // ------------------------------------------------------------------

    /**
     * Recursively render a {@link HaloGroup}: apply group transform,
     * draw all primitives, then recurse into child groups.
     *
     * <p>When a transition is active, the group's pre-built animation
     * is evaluated and applied as additional offset, rotation, scale, and
     * alpha (rotation in YXZ order, matching the idle animation).</p>
     */
    private void renderGroup(HaloGroup group, MatrixStack matrices, CameraSample camera,
                              HaloAppearance appearance, float brightness, LightSample ambientLight,
                              float inheritedAlpha, float inheritedGlow) {
        matrices.push();
        try {
            matrices.translate(group.position().x, group.position().y, group.position().z);
            applyQuaternionRotation(matrices, group.rotation());
            matrices.scale(group.scale(), group.scale(), group.scale());
            HaloAppearance.Group visual = appearance.groups().get(group);
            matrices.translate(visual.offset().x, visual.offset().y, visual.offset().z);
            var q = visual.rotation();
            applyQuaternionRotation(matrices, new Quaternionf((float) q.x(), (float) q.y(), (float) q.z(), (float) q.w()));
            matrices.scale(visual.scaleX(), visual.scaleY(), visual.scaleZ());
            double animTime = appearance.animationTime();
            float finalAlpha = inheritedAlpha * visual.alpha();
            float effectiveGlow = inheritedGlow * visual.glow();
            LightSample groupLight = group.glowing() ? LightSample.FULL_BRIGHT : ambientLight;
            draw.setLight(groupLight);
            draw.setDirectionalLighting(!group.glowing());
            if (finalAlpha < 1f) {
                draw.enableBlend();
                draw.defaultBlendFunc();
            }
            draw.setShaderColor(1f, 1f, 1f, finalAlpha);
            // Draw all primitives in this group
            for (int index=0;index<group.primitives().size();index++) {
                HaloPrimitive primitive=group.primitives().get(index);
                if (retained != null) {
                    retainedPrimitive=index; retainedPart=0;
                    retainedKind=primitive instanceof RingPrimitive ? SceneDraw.Kind.RING : SceneDraw.Kind.BILLBOARD;
                }
                if (primitive instanceof BillboardPrimitive bp) {
                    renderBillboard(bp, matrices, camera, group.glowing(), brightness, effectiveGlow);
                } else if (primitive instanceof RingPrimitive rp) {
                    renderRing(rp, matrices, group.glowing(), brightness, effectiveGlow);
                } else if (primitive instanceof MeshPrimitive mesh) {
                    meshDraw.add(mesh, matrices.peek().getPositionMatrix(), finalAlpha,
                        group.glowing() ? effectiveGlow : brightness, animTime, groupLight,
                        !group.glowing());
                }
            }

            // Recurse into child groups — children inherit this group's effective
            // alpha/glow multiplicatively. A group can opt out per channel via
            // inherit_alpha / inherit_glow (default true); when disabled, the
            // subtree starts fresh from 1.0 instead of inheriting.
            float childAlpha = group.inheritAlpha() ? finalAlpha : 1.0f;
            float childGlow = group.inheritGlow() ? effectiveGlow : 1.0f;
            String parentPath=retainedPath;
            for (int childIndex=0;childIndex<group.children().size();childIndex++) {
                if (retained != null) retainedPath=parentPath+"/"+childIndex;
                renderGroup(group.children().get(childIndex), matrices, camera, appearance, brightness, ambientLight, childAlpha, childGlow);
            }
            retainedPath=parentPath;
        } finally {
            matrices.pop();
        }
    }

    private void sampleGroup(HaloGroup group, HaloInstance instance, double animTime,
                             boolean transitionActive, double transitionElapsed, boolean isStartup,
                             StartupAnimationConfig startupConfig, StartupAnimationConfig shutdownConfig,
                             Map<HaloGroup, HaloAppearance.Group> result) {
        var idle = group.animation().orElse(LayerAnimation.EMPTY);
        Vec3d offset = idle.evaluateOffset(animTime);
        Quaternionf rotation = idle.evaluateRotation(animTime);
        float[] scale = idle.evaluateScale(animTime);
        float alpha = idle.evaluateAlpha(animTime);
        float glow = idle.evaluateGlow(animTime);
        if (transitionActive) {
            TransitionAnimationResult anim = resolveAnimation(group, instance, isStartup, startupConfig, shutdownConfig);
            float[] degrees;
            if (anim != null) {
                var transition = anim.evaluate(transitionElapsed);
                offset = transition.offset();
                scale = transition.scale();
                alpha = transition.alpha();
                degrees = anim.rotationAnimated() ? transition.rotationDegrees()
                    : frozenIdleVisuals(group, animTime).rotationDegrees();
            } else {
                var frozen = frozenIdleVisuals(group, animTime);
                offset = new Vec3d(frozen.offset()[0], frozen.offset()[1], frozen.offset()[2]);
                scale = frozen.scale();
                alpha = frozen.alpha();
                degrees = frozen.rotationDegrees();
            }
            rotation = LayerAnimation.quaternionFromYxzDegrees(degrees[0], degrees[1], degrees[2]);
            idlePhaseTracker.recordGroupVisual(instance.getEntityUuid(), group.id().orElse(""),
                new float[]{(float) offset.x, (float) offset.y, (float) offset.z}, scale, alpha, degrees, runtime.nowMillis());
        }
        result.put(group, new HaloAppearance.Group(offset,
            new network.azusake.halo.api.v2.AnchorRotation(rotation.x, rotation.y, rotation.z, rotation.w),
            scale[0], scale[1], scale[2], alpha, glow));
        for (HaloGroup child : group.children()) sampleGroup(child, instance, animTime, transitionActive,
            transitionElapsed, isStartup, startupConfig, shutdownConfig, result);
    }

    /**
     * The idle visual values a group is drawn with during a transition when
     * its transition does not override a channel: the group's idle animation
     * frozen at {@code frozenAnimTime} (F8).  Identity defaults when the group
     * has no idle animation.  With this, the final transition frame equals the
     * first frame of the resumed idle animation on every channel.
     *
     * @param offset          frozen offset (3 components)
     * @param rotationDegrees frozen rotation (YXZ Euler degrees, 3 components)
     * @param scale           frozen scale (3 components)
     * @param alpha           frozen alpha multiplier
     */
    static FrozenIdleVisuals frozenIdleVisuals(HaloGroup group, double frozenAnimTime) {
        var idle = group.animation().orElse(LayerAnimation.EMPTY);
        if (idle.isEmpty()) {
            return FrozenIdleVisuals.IDENTITY;
        }
        Vec3d off = idle.evaluateOffset(frozenAnimTime);
        return new FrozenIdleVisuals(
            new float[]{(float) off.x, (float) off.y, (float) off.z},
            idle.evaluateRotationDegrees(frozenAnimTime),
            idle.evaluateScale(frozenAnimTime),
            idle.evaluateAlpha(frozenAnimTime));
    }

    /** Immutable bundle of frozen idle visual values (F8). */
    record FrozenIdleVisuals(float[] offset, float[] rotationDegrees, float[] scale, float alpha) {
        static final FrozenIdleVisuals IDENTITY = new FrozenIdleVisuals(
            new float[]{0f, 0f, 0f}, new float[]{0f, 0f, 0f}, new float[]{1f, 1f, 1f}, 1.0f);
    }

    /**
     * Resolve the endpoint-aligned transition animation for a group, caching
     * per-instance so the endpoint patch is computed only once per transition.
     *
     * <p>STARTING plays the startup config forward with derived tail values
     * aligned to the group's idle animation at the resume phase.  ENDING plays
     * an explicit shutdown config forward with derived head values aligned to
     * the idle animation at the hide phase; when no shutdown is defined the
     * startup config is reversed and aligned the same way.  A hide that lands
     * mid-transition instead head-aligns to the exact on-screen values the
     * renderer was drawing (stashed via {@link HaloInstance#getHideVisuals()}).</p>
     */
    private TransitionAnimationResult resolveAnimation(HaloGroup group, HaloInstance instance,
                                                        boolean isStartup,
                                                        StartupAnimationConfig startupConfig,
                                                        StartupAnimationConfig shutdownConfig) {
        TransitionResolver.Resolution resolution =
            TransitionResolver.resolve(isStartup, startupConfig, shutdownConfig);
        if (resolution == null) {
            return null;
        }
        String groupKey = group.id().orElse("");
        TransitionAnimationResult cached = instance.getTransitionAnimation(groupKey);
        if (cached != null) {
            return cached;
        }
        TransitionAnimationResult base = resolution.config().getAnimationForGroup(group.id());
        if (base == null) {
            return null;
        }
        LayerAnimation idle = group.animation().orElse(LayerAnimation.EMPTY);
        double freeze = instance.getTransitionFreezeAnimTime();
        // A hide that happened mid-transition must start the fade-out from
        // the exact on-screen values the renderer was drawing (recorded every
        // transition frame), not from the idle animation.
        GroupVisualSnapshot hide = instance.getHideVisuals().get(groupKey);
        TransitionAnimationResult patched;
        if (isStartup) {
            patched = base.withTail(idle, freeze);
        } else if (resolution.reversed()) {
            // Fallback shutdown = startup timeline played backwards.  The
            // reversed queues are cached at the definition level; only the
            // derived head values are patched per instance.
            TransitionAnimationResult reversedBase =
                resolution.config().getReversedAnimationForGroup(group.id());
            if (reversedBase == null) {
                return null;
            }
            patched = hide != null
                ? reversedBase.withHeadValues(hide.offset(), hide.scale(), hide.alpha(), hide.rotation())
                : reversedBase.withHead(idle, freeze);
        } else {
            patched = hide != null
                ? base.withHeadValues(hide.offset(), hide.scale(), hide.alpha(), hide.rotation())
                : base.withHead(idle, freeze);
        }
        instance.putTransitionAnimation(groupKey, patched);
        return patched;
    }

    // ------------------------------------------------------------------
    // Billboard primitive (XZ plane, normal = -Y)
    // ------------------------------------------------------------------

    /**
     * Draw a billboard quad.  By default the quad lies on the XZ plane
     * (horizontal, normal = -Y) and the layer's accumulated matrix-stack
     * transform provides the world-space placement.  When the primitive's
     * {@code face_camera} is set, the quad is instead drawn fully facing the
     * camera: the plane normal always points toward the camera and that
     * orientation cannot be overridden by any animation rotation.
     */
    private void renderBillboard(BillboardPrimitive billboard, MatrixStack matrices, CameraSample camera,
                                 boolean glowing, float brightness, float animatedGlow) {
        PrimitiveGeometry geometry = runtime.primitiveGeometries().billboard(billboard);
        if (geometry == null) return;
        Matrix4f transform = matrices.peek().getPositionMatrix();
        Matrix3f facingNormal = null;
        if (billboard.faceCamera()) {
            // Unit quad: preserve the existing facing basis, including orthographic GUI views.
            var size = billboard.size();
            Matrix4f facingTransform=transform;
            if (retained != null) {
                facingTransform=new Matrix4f(transform);
                facingTransform.m30((float)(retainedOrigin.x-camera.position().x+transform.m30()));
                facingTransform.m31((float)(retainedOrigin.y-camera.position().y+transform.m31()));
                facingTransform.m32((float)(retainedOrigin.z-camera.position().z+transform.m32()));
            }
            CameraFacing facing = computeCameraFacing(facingTransform, size.x / 2f, size.y / 2f,
                vector(camera.up()), vector(camera.right()), parallelFacing);
            geometry = runtime.primitiveGeometries().facingQuad();
            Vector3f normal = new Vector3f(facing.right()).cross(facing.up()).normalize();
            facingNormal = new Matrix3f().setColumn(1, new Vector3f(normal).negate());
            Vector3f right = new Vector3f(facing.right()).mul(facing.halfWidth());
            Vector3f up = new Vector3f(facing.up()).mul(facing.halfDepth());
            transform = new Matrix4f(right.x,right.y,right.z,0, -normal.x,-normal.y,-normal.z,0,
                up.x,up.y,up.z,0, retained == null ? facing.center().x : transform.m30(),
                retained == null ? facing.center().y : transform.m31(),retained == null ? facing.center().z : transform.m32(),1);
        }
        draw.textured(bindTextureSafe(billboard.texture()));
        draw.disableCull(); draw.enableBlend(); draw.enableDepthTest(); draw.depthMask(true);
        draw.geometry(geometry, transform, glowing ? animatedGlow : brightness, facingNormal);
        draw.enableCull(); draw.disableBlend();
    }

    /**
     * World-space (camera-relative) placement of a camera-facing billboard:
     * the quad centre, an orthonormal right/up basis, and the world-space
     * half extents.
     */
    record CameraFacing(Vector3f center, Vector3f right, Vector3f up, float halfWidth, float halfDepth) {
    }

    /**
     * Compute the placement of a camera-facing billboard from the accumulated
     * position matrix.  The matrix stack is camera-relative (its origin is the
     * camera), so the matrix translation is the quad centre and the matrix
     * column lengths give the world-space scale — group/animation scaling
     * still applies while every accumulated rotation is discarded, making the
     * facing immune to all animation rotations.
     *
     * <p>The quad normal points from the centre toward the camera.  The quad's
     * "up" is the projection of the camera's vertical plane (world up for an
     * unrolled vanilla camera).  When the view direction is (near-)parallel to
     * the camera up — looking straight down/up — the camera's diagonal (right)
     * plane provides a well-defined horizontal axis instead.</p>
     */
    static CameraFacing computeCameraFacing(Matrix4f positionMatrix, float halfWidthLocal, float halfDepthLocal,
                                            Vector3f cameraUp, Vector3f cameraRight) {
        return computeCameraFacing(positionMatrix, halfWidthLocal, halfDepthLocal, cameraUp, cameraRight, false);
    }

    private static CameraFacing computeCameraFacing(Matrix4f positionMatrix, float halfWidthLocal, float halfDepthLocal,
                                                    Vector3f cameraUp, Vector3f cameraRight, boolean parallel) {
        Vector3f center = positionMatrix.getTranslation(new Vector3f());

        // World-space half extents: preserve (possibly non-uniform) scale from
        // the matrix columns; rotation does not affect their lengths.
        float scaleX = new Vector3f(positionMatrix.m00(), positionMatrix.m10(), positionMatrix.m20()).length();
        float scaleZ = new Vector3f(positionMatrix.m02(), positionMatrix.m12(), positionMatrix.m22()).length();
        float halfWidth = halfWidthLocal * scaleX;
        float halfDepth = halfDepthLocal * scaleZ;

        // Normal: quad centre → camera (the matrix origin).  Fall back to a
        // stable direction if the quad is exactly at the camera.
        Vector3f dir;
        float lenSq = center.lengthSquared();
        if (parallel) {
            dir = new Vector3f(cameraRight).cross(cameraUp).normalize();
        } else if (lenSq < 1e-12f) {
            dir = new Vector3f(0.0f, 0.0f, 1.0f);
        } else {
            dir = new Vector3f(center).mul(-1.0f / (float) Math.sqrt(lenSq));
        }

        Vector3f right = new Vector3f(cameraUp).cross(dir, new Vector3f());
        float rightLen = right.length();
        if (rightLen < 1e-6f) {
            // Looking (almost) straight down/up — the camera's right plane is
            // well-defined and horizontal, perpendicular to the view direction.
            right = new Vector3f(cameraRight);
        } else {
            right.mul(1.0f / rightLen);
        }

        Vector3f up = new Vector3f(dir).cross(right, new Vector3f());

        return new CameraFacing(center, right, up, halfWidth, halfDepth);
    }

    // ------------------------------------------------------------------
    // Ring primitive (cylindrical ring, XZ plane at identity rotation)
    // ------------------------------------------------------------------

    /**
     * Draw a cylindrical ring on the XZ plane.
     * At identity rotation the ring lies flat (axis = -Y), matching the
     * billboard convention.  Segment 0 starts at +X (seam on X axis).
     *
     * <p>UV mapping: U = i/segments (circumference, seam at +X),
     * V = 0 at top (+width/2), V = 1 at bottom (-width/2).</p>
     *
     * <p>Uses GL_TRIANGLES (6 vertices per segment) rather than
     * TRIANGLE_STRIP so that every triangle has a consistent,
     * independently controlled winding order.  This is required because
     * TRIANGLE_STRIP automatically flips the winding of alternating
     * triangles, which breaks face culling on a closed cylinder surface.</p>
     *
     * <p>Culling behaviour: when separate inner/outer textures are
     * provided, face culling is enabled so each texture is only visible
     * from its intended side.  When a single texture is used for both
     * sides, culling is disabled so the texture is visible from both
     * sides.</p>
     */
    private void renderRing(RingPrimitive ring, MatrixStack matrices, boolean glowing, float brightness, float animatedGlow) {
        PrimitiveGeometry outerGeometry = runtime.primitiveGeometries().ring(ring, false);
        PrimitiveGeometry innerGeometry = runtime.primitiveGeometries().ring(ring, true);
        if (outerGeometry == null || innerGeometry == null) return;
        boolean outer = bindTextureSafe(ring.outerTexture());
        boolean cull = outer && ring.innerTexture() != null;
        draw.textured(outer); draw.enableBlend(); draw.enableDepthTest(); draw.depthMask(true);
        if (cull) draw.enableCull(); else draw.disableCull();
        Matrix4f transform = matrices.peek().getPositionMatrix();
        float color = glowing ? animatedGlow : brightness;
        draw.geometry(outerGeometry, transform, color);
        if (outer) {
            Identifier inner = ring.innerTexture() != null ? ring.innerTexture() : ring.outerTexture();
            if (!bindTextureSafe(inner)) bindTextureSafe(ring.outerTexture());
        }
        draw.geometry(innerGeometry, transform, color);
        draw.enableCull(); draw.disableBlend();
    }

    // ------------------------------------------------------------------
    // Rotation helpers
    // ------------------------------------------------------------------

    static void applyQuaternionRotation(MatrixStack matrices, Quaternionf quat) {
        Matrix4f rotMatrix = new Matrix4f().rotation(quat);
        matrices.peek().getPositionMatrix().mul(rotMatrix);
    }

    // ------------------------------------------------------------------
    // Entity helpers
    // ------------------------------------------------------------------

    static EntitySample findEntityByUuid(FrameScene scene, UUID uuid) { return scene.entities().get(uuid); }

    // ------------------------------------------------------------------
    // Texture binding
    // ------------------------------------------------------------------

    private boolean bindTextureSafe(Identifier textureId) { return draw.bindTexture(textureId); }
    private static Vector3f vector(Vec3d v) { return new Vector3f((float)v.x,(float)v.y,(float)v.z); }
}
