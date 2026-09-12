package network.azusake.halo.core.runtime;

import java.util.*;
import java.util.function.LongSupplier;
import java.util.function.Consumer;
import network.azusake.halo.core.Identifier;
import network.azusake.halo.core.render.*;
import network.azusake.halo.config.HaloConfig;
import network.azusake.halo.data.*;
import network.azusake.halo.render.IdlePhaseTracker;
import network.azusake.halo.render.SceneRenderer;

/** One logical client. Authority replicas outlive entity/render instances. Never shared with a server. */
public final class ClientRuntime implements ClientPort {
    private final Map<UUID,Identifier> assignments = new LinkedHashMap<>();
    private final Map<UUID,HaloInstance> visuals = new LinkedHashMap<>();
    private final Map<UUID,Integer> entityIds = new HashMap<>();
    private final LongSupplier clock;
    private Long frameTime;
    private long worldToken=Long.MIN_VALUE;
    private Map<Identifier,HaloDefinition> definitions=Map.of();
    private Map<Identifier,HaloDefinition> definitionInput;
    private HaloConfig config=new HaloConfig();
    private final SceneRenderer renderer;
    public ClientRuntime() { this(System::currentTimeMillis); }
    public ClientRuntime(LongSupplier clock) { this(clock, id -> {}); }
    /** The warning callback runs on the owning client thread; the host handles localized feedback. */
    public ClientRuntime(LongSupplier clock, Consumer<Identifier> missingDefinitionWarning) {
        this.clock=Objects.requireNonNull(clock);
        this.renderer=new SceneRenderer(this, Objects.requireNonNull(missingDefinitionWarning));
    }
    public long nowMillis() { return frameTime == null ? clock.getAsLong() : frameTime; }
    public HaloConfig getConfig() { return config; }
    public void setConfig(HaloConfig value) { config=value.copy(); }
    public void definitions(DefinitionSnapshot snapshot) { definitions(snapshot.definitions()); }
    public Map<UUID,BodyPose> bodyPoses() { return renderer.bodyPoses(); }
    public void definitions(Map<Identifier,HaloDefinition> value) {
        if (definitionInput == value) return;
        definitionInput=value;
        definitions=Map.copyOf(value); visuals.values().forEach(HaloInstance::invalidateDefinition);
    }
    public Optional<HaloDefinition> definition(Identifier id) { return Optional.ofNullable(definitions.get(id)); }
    public void attach(UUID uuid,Identifier definition,boolean startup) {
        if (definition.equals(assignments.get(uuid)) && visuals.containsKey(uuid)) return;
        renderer.clearEntity(uuid);
        assignments.put(uuid,definition);
        HaloInstance inst=new HaloInstance(uuid,definition,this::nowMillis);
        if (startup) { inst.setTransitionState(HaloTransitionState.STARTING); inst.startTransition(0); }
        visuals.put(uuid,inst);
    }
    public void replace(Map<UUID,Identifier> snapshot) {
        clear(); snapshot.forEach((uuid,id)->attach(uuid,id,false));
    }
    public void hide(UUID uuid,Identifier definition) {
        assignments.remove(uuid);
        HaloInstance inst=visuals.get(uuid);
        if (inst==null || inst.getTransitionState()==HaloTransitionState.ENDING) return;
        IdlePhaseTracker.RenderState state=renderer.readLastRenderState(uuid);
        HaloDefinition def=definitions.get(inst.getDefinitionId());
        double freeze=inst.currentAnimTime(def==null?null:def.startupAnimation().orElse(null));
        inst.setHiddenByState(false); inst.reactivate();
        inst.setTransitionState(HaloTransitionState.ENDING); inst.startTransition(freeze);
        if (state!=null && state.transitionActive() && !state.groups().isEmpty()) inst.setHideVisuals(state.groups());
    }
    public void removeClientHalo(UUID uuid) { visuals.remove(uuid); renderer.clearEntity(uuid); }
    public void unload(UUID uuid) { removeClientHalo(uuid); entityIds.remove(uuid); }
    public void died(UUID uuid, boolean player) { unload(uuid); if (!player) assignments.remove(uuid); }
    public void teleport(UUID uuid) { HaloInstance v=visuals.get(uuid); if(v!=null)v.markTeleported(); }
    public void clear() { assignments.clear(); visuals.clear(); entityIds.clear(); renderer.clearWorld(); worldToken=Long.MIN_VALUE; }
    public HaloInstance getInstance(UUID uuid) { return visuals.get(uuid); }
    public Collection<HaloInstance> getAllInstances() { return visuals.values(); }
    public Map<UUID,HaloInstance> getActiveHalos() { return Map.copyOf(visuals); }
    public Map<UUID,Identifier> assignments() { return Map.copyOf(assignments); }
    public Map<UUID,ClientStatus> diagnostics() {
        var result = new LinkedHashMap<UUID,ClientStatus>();
        visuals.forEach((uuid, value) -> result.put(uuid, new ClientStatus(value.getDefinitionId(),
            value.getCreatedAtTime(), value.isActive(), value.isNeedsSnap(), value.getTransitionState())));
        return Map.copyOf(result);
    }
    public SceneRenderer renderer() { return renderer; }
    public List<DrawBatch> render(FrameScene scene) {
        frameTime=scene.timeMillis();
        try {
            if(worldToken!=scene.worldToken()) {
                if(worldToken!=Long.MIN_VALUE) { visuals.clear(); entityIds.clear(); renderer.clearWorld(); }
                worldToken=scene.worldToken();
            }
            var gone = visuals.keySet().stream().filter(uuid -> !scene.entities().containsKey(uuid)
                || !scene.entities().get(uuid).alive()).toList();
            gone.forEach(this::unload);
            entityIds.keySet().retainAll(scene.entities().keySet());
            scene.entities().forEach((uuid, entity) -> {
                Integer previous = entityIds.put(uuid, entity.runtimeId());
                if (previous != null && previous != entity.runtimeId()) removeClientHalo(uuid);
            });
            assignments.forEach((uuid,id)-> {
                if(scene.entities().containsKey(uuid) && scene.entities().get(uuid).alive()) visuals.computeIfAbsent(uuid,k->new HaloInstance(k,id,this::nowMillis));
            });
            return renderer.renderHalos(scene);
        } finally { frameTime=null; }
    }
    public HaloInstance getHaloInstance(UUID uuid) { return getInstance(uuid); }
    public void putClientHalo(UUID uuid,Identifier id) { attach(uuid,id,false); }
    public void putClientHalo(UUID uuid,Identifier id,HaloTransitionState state) { attach(uuid,id,state==HaloTransitionState.STARTING); }
    public void replaceAllClientHalos(Map<UUID,Identifier> values) { replace(values); }
    public void clearAllClientHalos() { clear(); }
    public int getActiveCount() { return visuals.size(); }
    public void forceRemoveHalo(UUID uuid) { removeClientHalo(uuid); }
}
