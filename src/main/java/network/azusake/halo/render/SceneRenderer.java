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
    private final MeshGeometryRenderer meshDraw = new MeshGeometryRenderer(message -> LOG.warn("[Halo mesh] {}", message));
    private final Map<UUID, HaloAppearance> appearances = new LinkedHashMap<>();
    public HaloAppearance appearance(UUID wearer) { return appearances.get(wearer); }
    public void clearAppearances() { appearances.clear(); }
    private final Map<UUID,BodyPose> bodyPoses = new LinkedHashMap<>();
    public Map<UUID,BodyPose> bodyPoses() { return Map.copyOf(bodyPoses); }
    public void clearEntity(UUID uuid) {
        appearances.remove(uuid); frameCalculator.clearEntity(uuid); idlePhaseTracker.remove(uuid); bodyPoses.remove(uuid);
        prevSleepHidden.remove(uuid); prevInvisHidden.remove(uuid);
    }

    public static final boolean DEBUG_RENDERING = false;

    /**
     * When true, each ring segment is tinted with a distinct color so
     * the user can visually identify individual segments and diagnose
     * seam / overlap issues.  Set to false for normal rendering.
     */
    public static final boolean RING_DEBUG_SEGMENTS = false;

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

    /** Timestamp (nanoTime) of the previous render frame, for delta-time. */
    private long prevFrameNanos;
    /** Whether we have seen at least one frame. */
    private boolean firstFrame = true;
    /** EMA-smoothed frame delta-time, to suppress nanoTime jitter. */
    private double smoothedDt = -1;

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
        appearances.clear(); bodyPoses.clear(); clearIdlePhases(); frameCalculator.retainOnly(Set.of());
        prevSleepHidden.clear(); prevInvisHidden.clear(); firstFrame=true; smoothedDt=-1;
        lastMissingDefWarningTime=null;
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
        this.draw = new GeometryCollector(scene.textures());
        meshDraw.begin(scene.visuals());
        FrameScene client = scene;
        MatrixStack matrices = new MatrixStack(scene.rootTransform());
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
            .filter(inst -> scene.entities().get(inst.getEntityUuid()).position().squaredDistanceTo(camera.getPos()) <= 65536)
            .map(HaloInstance::getEntityUuid)
            .collect(Collectors.toSet());
        frameCalculator.retainOnly(activeUuids);

        // Compute frame delta with EMA smoothing to suppress nanoTime jitter
        long frameNanos = scene.frameNanos();
        double rawDt;
        if (firstFrame) {
            rawDt = 0.0;
            firstFrame = false;
        } else {
            rawDt = (frameNanos - prevFrameNanos) / 1_000_000_000.0;
            rawDt = Math.max(0.001, Math.min(rawDt, 0.1));
        }
        prevFrameNanos = frameNanos;

        // EMA smoothing: blend raw frame-time into a rolling average
        if (smoothedDt < 0) {
            smoothedDt = rawDt;
        } else {
            // Weight new frame at 20% — smooth but responsive
            smoothedDt = smoothedDt * 0.8 + rawDt * 0.2;
        }
        final double dt = smoothedDt;

        for (HaloInstance instance : visible) {
            try {
                HaloAppearance appearance = prepareAppearance(instance, client);
                if (appearance != null) {
                    appearances.put(instance.getEntityUuid(), appearance);
                    EntitySample entity = scene.entities().get(instance.getEntityUuid());
                    if (entity.position().squaredDistanceTo(camera.getPos()) <= 65536) {
                        AnchorFrame frame = frameCalculator.calculate(instance, entity.anchor(), entity.fallbackAnchor(),
                            appearance.definition(), camera.getPos(), dt, runtime.getConfig());
                        var rotation = frame.worldOrientation();
                        bodyPoses.put(instance.getEntityUuid(), new BodyPose(frame.worldPosition(),
                            new network.azusake.halo.api.v2.AnchorRotation(rotation.x, rotation.y, rotation.z, rotation.w), frame.scale()));
                        Vec3d crp = frame.cameraRelativePos();
                        if (Math.abs(crp.x) <= 1000 && Math.abs(crp.y) <= 1000 && Math.abs(crp.z) <= 1000) {
                            LightSample light = Objects.requireNonNull(scene.lightmaps().sample(frame.worldPosition()));
                            float brightness = light.available() ? 1f : Math.max(scene.lights().brightness(frame.worldPosition()), .04f);
                            renderAppearance(appearance, frame, matrices, camera, light, brightness);
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
        return meshDraw.finish(draw.batches());
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
            && instance.getTransitionStartTime() > 0;
        double transitionElapsed = instance.getTransitionElapsed();
        boolean isStartup = state == HaloTransitionState.STARTING;

        // ---- elapsed time since halo creation ----
        final double rawAnimTime = (runtime.nowMillis() - instance.getCreatedAtTime()) / 1000.0;

        // ---- adjust animTime so the periodic animation freezes during a
        // transition and resumes at its actual phase afterwards ----
        final double animTime;
        long transitionStart = instance.getTransitionStartTime();
        if (transitionStart > 0
                && (state == HaloTransitionState.STARTING || state == HaloTransitionState.ENDING)) {
            // Transition in progress — freeze the periodic animation at the
            // phase captured when the transition started.
            animTime = instance.getTransitionFreezeAnimTime();
        } else if (transitionStart > 0) {
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
        parallelFacing = input.projection() == PreviewFrame.Projection.ORTHOGRAPHIC;
        draw = new GeometryCollector(input.textures());
        meshDraw.begin(input.visuals());
        AnchorFrame frame = AnchorFrameCalculator.rigid(input.head(), appearance.definition(),
            input.camera().position(), runtime.getConfig());
        renderAppearance(appearance, frame, new MatrixStack(input.rootTransform()), input.camera(), input.light(), 1f);
        return meshDraw.finish(draw.batches());
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
            matrices.translate(crp.x, crp.y, crp.z);
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
            for (HaloGroup group : model.groups()) {
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
            for (HaloPrimitive primitive : group.primitives()) {
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
            for (HaloGroup child : group.children()) {
                renderGroup(child, matrices, camera, appearance, brightness, ambientLight, childAlpha, childGlow);
            }
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
        float hw = billboard.size().x / 2.0f;  // half-width (X)
        float hd = billboard.size().y / 2.0f;  // half-depth (Z) — size.y maps to Z axis

        if (DEBUG_RENDERING) {
            hw *= 5.0f;
            hd *= 5.0f;
        }

        // Camera-facing quads are rebuilt in camera-relative world space from
        // the matrix's position + scale (rotation discarded), so no animation
        // rotation can override the facing.  Other quads keep using the
        // accumulated matrix-stack transform.
        Matrix4f positionMatrix;
        Vector3f surfaceNormal;
        Vector3f c0, c1, c2, c3; // quad corners in the position-matrix space
        if (billboard.faceCamera()) {
            CameraFacing facing = computeCameraFacing(
                matrices.peek().getPositionMatrix(), hw, hd,
                vector(camera.up()), vector(camera.right()), parallelFacing);
            // Identity matrix — the corners are already camera-relative world
            // coordinates, so every accumulated rotation is fully discarded.
            positionMatrix = new Matrix4f();
            Vector3f rightHalf = new Vector3f(facing.right()).mul(facing.halfWidth());
            Vector3f upHalf = new Vector3f(facing.up()).mul(facing.halfDepth());
            c0 = new Vector3f(facing.center()).sub(rightHalf).sub(upHalf);
            c1 = new Vector3f(facing.center()).add(rightHalf).sub(upHalf);
            c2 = new Vector3f(facing.center()).add(rightHalf).add(upHalf);
            c3 = new Vector3f(facing.center()).sub(rightHalf).add(upHalf);
            surfaceNormal = new Vector3f(facing.right()).cross(facing.up()).normalize();
        } else {
            positionMatrix = matrices.peek().getPositionMatrix();
            c0 = new Vector3f(-hw, 0.0f, -hd);
            c1 = new Vector3f( hw, 0.0f, -hd);
            c2 = new Vector3f( hw, 0.0f,  hd);
            c3 = new Vector3f(-hw, 0.0f,  hd);
            surfaceNormal = transformNormal(normalMatrix(positionMatrix), 0, -1, 0);
        }

        GeometryCollector tessellator = draw;
        GeometryCollector.Builder builder = tessellator.getBuffer();
        builder.normal(surfaceNormal.x, surfaceNormal.y, surfaceNormal.z);

        boolean hasTexture = bindTextureSafe(billboard.texture());

        // Self-illuminating primitives are lit by the animation.glow channel;
        // otherwise brightness follows the ambient light at the halo position.
        float brightnessFactor = glowing ? animatedGlow : brightness;

        // Billboard quads are translucent — disable face culling
        draw.disableCull();

        if (DEBUG_RENDERING) {
            draw.disableDepthTest();
            draw.depthMask(false);
        } else {
            draw.enableDepthTest();
            draw.depthMask(true);
        }

        if (hasTexture) {
            draw.enableBlend();
            draw.defaultBlendFunc();
            // Default: XZ plane at Y=0, normal = -Y (faces downward toward the
            // entity head).
            // Vertex winding from BELOW (-Y) is CCW → front face faces -Y:
            // (-hw, 0, -hd)  →  (+hw, 0, -hd)  →  (+hw, 0, +hd)  →  (-hw, 0, +hd)
            // face_camera keeps the same UV layout upright (V=0 at the +up side).
            // Tint texture by the effective brightness factor (fullbright at 1.0)
            draw.textured(true);
            builder.begin(DrawBatch.Topology.QUADS, true);
            builder.vertex(positionMatrix, c0.x, c0.y, c0.z).texture(0.0f, 1.0f).color(brightnessFactor, brightnessFactor, brightnessFactor, 1f).next();
            builder.vertex(positionMatrix, c1.x, c1.y, c1.z).texture(1.0f, 1.0f).color(brightnessFactor, brightnessFactor, brightnessFactor, 1f).next();
            builder.vertex(positionMatrix, c2.x, c2.y, c2.z).texture(1.0f, 0.0f).color(brightnessFactor, brightnessFactor, brightnessFactor, 1f).next();
            builder.vertex(positionMatrix, c3.x, c3.y, c3.z).texture(0.0f, 0.0f).color(brightnessFactor, brightnessFactor, brightnessFactor, 1f).next();
        } else {
            draw.textured(false);
            if (DEBUG_RENDERING) {
                draw.disableBlend();
            } else {
                draw.enableBlend();
                draw.defaultBlendFunc();
            }
            builder.begin(DrawBatch.Topology.QUADS, false);
            builder.vertex(positionMatrix, c0.x, c0.y, c0.z).color(brightnessFactor, brightnessFactor, brightnessFactor, 1.0f).next();
            builder.vertex(positionMatrix, c1.x, c1.y, c1.z).color(brightnessFactor, brightnessFactor, brightnessFactor, 1.0f).next();
            builder.vertex(positionMatrix, c2.x, c2.y, c2.z).color(brightnessFactor, brightnessFactor, brightnessFactor, 1.0f).next();
            builder.vertex(positionMatrix, c3.x, c3.y, c3.z).color(brightnessFactor, brightnessFactor, brightnessFactor, 1.0f).next();
        }

        tessellator.draw();

        draw.enableCull();

        if (DEBUG_RENDERING) {
            draw.depthMask(true);
            draw.enableDepthTest();
        }
        draw.disableBlend();
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
        float radius = ring.size().x;
        float width  = ring.size().y;
        int segments = Math.max(3, ring.segments()); // minimum 3 for a visible shape

        if (DEBUG_RENDERING) {
            radius *= 5.0f;
            width  *= 5.0f;
        }

        float halfW = width / 2.0f;

        // Self-illuminating primitives are lit by the animation.glow channel;
        // otherwise brightness follows the ambient light at the halo position.
        float brightnessFactor = glowing ? animatedGlow : brightness;

        Matrix4f positionMatrix = matrices.peek().getPositionMatrix();
        Matrix3f normalMatrix = normalMatrix(positionMatrix);

        GeometryCollector tessellator = draw;
        GeometryCollector.Builder builder = tessellator.getBuffer();

        boolean hasOuterTexture = bindTextureSafe(ring.outerTexture());
        boolean twoTextures = hasOuterTexture && ring.innerTexture() != null;

        if (DEBUG_RENDERING) {
            draw.disableDepthTest();
            draw.depthMask(false);
        } else {
            draw.enableDepthTest();
            draw.depthMask(true);
        }

        if (hasOuterTexture) {
            draw.enableBlend();
            draw.defaultBlendFunc();

            // ---- Outer surface ----
            // Face culling: two textures → cull back faces (outer visible
            // only from outside); single texture → no culling (visible
            // from both sides).
            if (twoTextures) {
                draw.enableCull();
            } else {
                draw.disableCull();
            }

            // Outer surface: CCW winding → front faces point outward.
            // Each segment emits two triangles (6 vertices):
            //   tri A: top₀, top₁, bottom₀
            //   tri B: bottom₀, top₁, bottom₁
            if (glowing) {
                draw.textured(true);
                builder.begin(DrawBatch.Topology.TRIANGLES, true);
                for (int i = 0; i < segments; i++) {
                    int next = (i + 1) % segments;
                    float u0 = (float) i / segments;
                    // Seam fix: last segment uses U=1.0 instead of 0.0 so
                    // GPU interpolation doesn't stretch the entire texture.
                    float u1 = (i == segments - 1) ? 1.0f : (float) next / segments;
                    float cos0 = (float) Math.cos(2.0 * Math.PI * i / segments);
                    float sin0 = (float) Math.sin(2.0 * Math.PI * i / segments);
                    float cos1 = (float) Math.cos(2.0 * Math.PI * next / segments);
                    float sin1 = (float) Math.sin(2.0 * Math.PI * next / segments);
                    // Triangle A
                    builder.vertex(positionMatrix, radius * cos0, halfW, radius * sin0).texture(u0, 0.0f).color(animatedGlow, animatedGlow, animatedGlow, 1f).next();
                    builder.vertex(positionMatrix, radius * cos1, halfW, radius * sin1).texture(u1, 0.0f).color(animatedGlow, animatedGlow, animatedGlow, 1f).next();
                    builder.vertex(positionMatrix, radius * cos0, -halfW, radius * sin0).texture(u0, 1.0f).color(animatedGlow, animatedGlow, animatedGlow, 1f).next();
                    // Triangle B
                    builder.vertex(positionMatrix, radius * cos0, -halfW, radius * sin0).texture(u0, 1.0f).color(animatedGlow, animatedGlow, animatedGlow, 1f).next();
                    builder.vertex(positionMatrix, radius * cos1, halfW, radius * sin1).texture(u1, 0.0f).color(animatedGlow, animatedGlow, animatedGlow, 1f).next();
                    builder.vertex(positionMatrix, radius * cos1, -halfW, radius * sin1).texture(u1, 1.0f).color(animatedGlow, animatedGlow, animatedGlow, 1f).next();
                }
                tessellator.draw();
            } else {
                draw.textured(true);
                builder.begin(DrawBatch.Topology.TRIANGLES, true);
                for (int i = 0; i < segments; i++) {
                    int next = (i + 1) % segments;
                    float u0 = (float) i / segments;
                    // Seam fix: last segment uses U=1.0 instead of 0.0 so
                    // GPU interpolation doesn't stretch the entire texture.
                    float u1 = (i == segments - 1) ? 1.0f : (float) next / segments;
                    float cos0 = (float) Math.cos(2.0 * Math.PI * i / segments);
                    float sin0 = (float) Math.sin(2.0 * Math.PI * i / segments);
                    float cos1 = (float) Math.cos(2.0 * Math.PI * next / segments);
                    float sin1 = (float) Math.sin(2.0 * Math.PI * next / segments);
                    // Per-segment debug colour: each segment gets a
                    // distinct hue so the user can identify individual
                    // segments and diagnose seam issues.
                    float cr, cg, cb;
                    if (RING_DEBUG_SEGMENTS) {
                        int rgb = java.awt.Color.HSBtoRGB((float) i / segments, 0.8f, 1.0f);
                        cr = ((rgb >> 16) & 0xFF) / 255f;
                        cg = ((rgb >>  8) & 0xFF) / 255f;
                        cb = ( rgb        & 0xFF) / 255f;
                    } else {
                        cr = cg = cb = brightness;
                    }
                    // Triangle A
                    ringNormal(builder, normalMatrix, cos0, sin0).vertex(positionMatrix, radius * cos0, halfW, radius * sin0).texture(u0, 0.0f).color(cr, cg, cb, 1f).next();
                    ringNormal(builder, normalMatrix, cos1, sin1).vertex(positionMatrix, radius * cos1, halfW, radius * sin1).texture(u1, 0.0f).color(cr, cg, cb, 1f).next();
                    ringNormal(builder, normalMatrix, cos0, sin0).vertex(positionMatrix, radius * cos0, -halfW, radius * sin0).texture(u0, 1.0f).color(cr, cg, cb, 1f).next();
                    // Triangle B
                    ringNormal(builder, normalMatrix, cos0, sin0).vertex(positionMatrix, radius * cos0, -halfW, radius * sin0).texture(u0, 1.0f).color(cr, cg, cb, 1f).next();
                    ringNormal(builder, normalMatrix, cos1, sin1).vertex(positionMatrix, radius * cos1, halfW, radius * sin1).texture(u1, 0.0f).color(cr, cg, cb, 1f).next();
                    ringNormal(builder, normalMatrix, cos1, sin1).vertex(positionMatrix, radius * cos1, -halfW, radius * sin1).texture(u1, 1.0f).color(cr, cg, cb, 1f).next();
                }
                tessellator.draw();
            }

            // ---- Inner surface ----
            Identifier innerTex = ring.innerTexture() != null ? ring.innerTexture() : ring.outerTexture();
            boolean hasInnerTexture = bindTextureSafe(innerTex);
            if (!hasInnerTexture) {
                bindTextureSafe(ring.outerTexture()); // fallback
            }

            if (twoTextures) {
                draw.enableCull();
            }
            // (single-texture path already has culling disabled above)

            // Inner surface: CW winding → front faces point inward.
            // Same radius as outer — face culling separates visibility,
            // no artificial offset needed.
            //   tri A: bottom₀, bottom₁, top₀
            //   tri B: top₀, bottom₁, top₁
            if (glowing) {
                draw.textured(true);
                builder.begin(DrawBatch.Topology.TRIANGLES, true);
                for (int i = 0; i < segments; i++) {
                    int next = (i + 1) % segments;
                    float u0 = (float) i / segments;
                    // Seam fix: last segment uses U=1.0 instead of 0.0 so
                    // GPU interpolation doesn't stretch the entire texture.
                    float u1 = (i == segments - 1) ? 1.0f : (float) next / segments;
                    float cos0 = (float) Math.cos(2.0 * Math.PI * i / segments);
                    float sin0 = (float) Math.sin(2.0 * Math.PI * i / segments);
                    float cos1 = (float) Math.cos(2.0 * Math.PI * next / segments);
                    float sin1 = (float) Math.sin(2.0 * Math.PI * next / segments);
                    // Triangle A
                    builder.vertex(positionMatrix, radius * cos0, -halfW, radius * sin0).texture(u0, 1.0f).color(animatedGlow, animatedGlow, animatedGlow, 1f).next();
                    builder.vertex(positionMatrix, radius * cos1, -halfW, radius * sin1).texture(u1, 1.0f).color(animatedGlow, animatedGlow, animatedGlow, 1f).next();
                    builder.vertex(positionMatrix, radius * cos0, halfW, radius * sin0).texture(u0, 0.0f).color(animatedGlow, animatedGlow, animatedGlow, 1f).next();
                    // Triangle B
                    builder.vertex(positionMatrix, radius * cos0, halfW, radius * sin0).texture(u0, 0.0f).color(animatedGlow, animatedGlow, animatedGlow, 1f).next();
                    builder.vertex(positionMatrix, radius * cos1, -halfW, radius * sin1).texture(u1, 1.0f).color(animatedGlow, animatedGlow, animatedGlow, 1f).next();
                    builder.vertex(positionMatrix, radius * cos1, halfW, radius * sin1).texture(u1, 0.0f).color(animatedGlow, animatedGlow, animatedGlow, 1f).next();
                }
                tessellator.draw();
            } else {
                draw.textured(true);
                builder.begin(DrawBatch.Topology.TRIANGLES, true);
                for (int i = 0; i < segments; i++) {
                    int next = (i + 1) % segments;
                    float u0 = (float) i / segments;
                    // Seam fix: last segment uses U=1.0 instead of 0.0 so
                    // GPU interpolation doesn't stretch the entire texture.
                    float u1 = (i == segments - 1) ? 1.0f : (float) next / segments;
                    float cos0 = (float) Math.cos(2.0 * Math.PI * i / segments);
                    float sin0 = (float) Math.sin(2.0 * Math.PI * i / segments);
                    float cos1 = (float) Math.cos(2.0 * Math.PI * next / segments);
                    float sin1 = (float) Math.sin(2.0 * Math.PI * next / segments);
                    float cr, cg, cb;
                    if (RING_DEBUG_SEGMENTS) {
                        int rgb = java.awt.Color.HSBtoRGB((float) i / segments, 0.5f, 0.6f);
                        cr = ((rgb >> 16) & 0xFF) / 255f;
                        cg = ((rgb >>  8) & 0xFF) / 255f;
                        cb = ( rgb        & 0xFF) / 255f;
                    } else {
                        cr = cg = cb = brightness;
                    }
                    // Triangle A
                    ringNormal(builder, normalMatrix, -cos0, -sin0).vertex(positionMatrix, radius * cos0, -halfW, radius * sin0).texture(u0, 1.0f).color(cr, cg, cb, 1f).next();
                    ringNormal(builder, normalMatrix, -cos1, -sin1).vertex(positionMatrix, radius * cos1, -halfW, radius * sin1).texture(u1, 1.0f).color(cr, cg, cb, 1f).next();
                    ringNormal(builder, normalMatrix, -cos0, -sin0).vertex(positionMatrix, radius * cos0, halfW, radius * sin0).texture(u0, 0.0f).color(cr, cg, cb, 1f).next();
                    // Triangle B
                    ringNormal(builder, normalMatrix, -cos0, -sin0).vertex(positionMatrix, radius * cos0, halfW, radius * sin0).texture(u0, 0.0f).color(cr, cg, cb, 1f).next();
                    ringNormal(builder, normalMatrix, -cos1, -sin1).vertex(positionMatrix, radius * cos1, -halfW, radius * sin1).texture(u1, 1.0f).color(cr, cg, cb, 1f).next();
                    ringNormal(builder, normalMatrix, -cos1, -sin1).vertex(positionMatrix, radius * cos1, halfW, radius * sin1).texture(u1, 0.0f).color(cr, cg, cb, 1f).next();
                }
                tessellator.draw();
            }
        } else {
            // No texture fallback — solid color ring, both sides visible
            draw.disableCull();
            draw.textured(false);
            if (DEBUG_RENDERING) {
                draw.disableBlend();
            } else {
                draw.enableBlend();
                draw.defaultBlendFunc();
            }

            float r, g, b;
            r = g = b = brightnessFactor;

            // Outer surface (CCW)
            builder.begin(DrawBatch.Topology.TRIANGLES, false);
            for (int i = 0; i < segments; i++) {
                int next = (i + 1) % segments;
                float cos0 = (float) Math.cos(2.0 * Math.PI * i / segments);
                float sin0 = (float) Math.sin(2.0 * Math.PI * i / segments);
                float cos1 = (float) Math.cos(2.0 * Math.PI * next / segments);
                float sin1 = (float) Math.sin(2.0 * Math.PI * next / segments);
                ringNormal(builder, normalMatrix, cos0, sin0).vertex(positionMatrix, radius * cos0, halfW, radius * sin0).color(r, g, b, 1.0f).next();
                ringNormal(builder, normalMatrix, cos1, sin1).vertex(positionMatrix, radius * cos1, halfW, radius * sin1).color(r, g, b, 1.0f).next();
                ringNormal(builder, normalMatrix, cos0, sin0).vertex(positionMatrix, radius * cos0, -halfW, radius * sin0).color(r, g, b, 1.0f).next();
                ringNormal(builder, normalMatrix, cos0, sin0).vertex(positionMatrix, radius * cos0, -halfW, radius * sin0).color(r, g, b, 1.0f).next();
                ringNormal(builder, normalMatrix, cos1, sin1).vertex(positionMatrix, radius * cos1, halfW, radius * sin1).color(r, g, b, 1.0f).next();
                ringNormal(builder, normalMatrix, cos1, sin1).vertex(positionMatrix, radius * cos1, -halfW, radius * sin1).color(r, g, b, 1.0f).next();
            }
            tessellator.draw();

            // Inner surface (CW)
            builder.begin(DrawBatch.Topology.TRIANGLES, false);
            for (int i = 0; i < segments; i++) {
                int next = (i + 1) % segments;
                float cos0 = (float) Math.cos(2.0 * Math.PI * i / segments);
                float sin0 = (float) Math.sin(2.0 * Math.PI * i / segments);
                float cos1 = (float) Math.cos(2.0 * Math.PI * next / segments);
                float sin1 = (float) Math.sin(2.0 * Math.PI * next / segments);
                ringNormal(builder, normalMatrix, -cos0, -sin0).vertex(positionMatrix, radius * cos0, -halfW, radius * sin0).color(r, g, b, 1.0f).next();
                ringNormal(builder, normalMatrix, -cos1, -sin1).vertex(positionMatrix, radius * cos1, -halfW, radius * sin1).color(r, g, b, 1.0f).next();
                ringNormal(builder, normalMatrix, -cos0, -sin0).vertex(positionMatrix, radius * cos0, halfW, radius * sin0).color(r, g, b, 1.0f).next();
                ringNormal(builder, normalMatrix, -cos0, -sin0).vertex(positionMatrix, radius * cos0, halfW, radius * sin0).color(r, g, b, 1.0f).next();
                ringNormal(builder, normalMatrix, -cos1, -sin1).vertex(positionMatrix, radius * cos1, -halfW, radius * sin1).color(r, g, b, 1.0f).next();
                ringNormal(builder, normalMatrix, -cos1, -sin1).vertex(positionMatrix, radius * cos1, halfW, radius * sin1).color(r, g, b, 1.0f).next();
            }
            tessellator.draw();
        }

        draw.enableCull();

        if (DEBUG_RENDERING) {
            draw.depthMask(true);
            draw.enableDepthTest();
        }
        draw.disableBlend();
    }

    private static GeometryCollector.Builder ringNormal(GeometryCollector.Builder builder,
                                                         Matrix3f matrix, float x, float z) {
        Vector3f normal = transformNormal(matrix, x, 0, z);
        return builder.normal(normal.x, normal.y, normal.z);
    }

    private static Matrix3f normalMatrix(Matrix4f transform) {
        Matrix3f matrix = new Matrix3f(transform);
        float determinant = matrix.determinant();
        return Float.isFinite(determinant) && Math.abs(determinant) > 1.0e-8f
            ? matrix.invert().transpose() : new Matrix3f();
    }

    private static Vector3f transformNormal(Matrix3f matrix, float x, float y, float z) {
        Vector3f normal = matrix.transform(new Vector3f(x, y, z));
        return normal.lengthSquared() > 1.0e-12f && Float.isFinite(normal.lengthSquared())
            ? normal.normalize() : new Vector3f(0, -1, 0);
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
