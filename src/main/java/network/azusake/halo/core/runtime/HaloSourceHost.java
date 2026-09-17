package network.azusake.halo.core.runtime;

import java.util.*;
import network.azusake.halo.core.Identifier;
import network.azusake.halo.core.runtime.HaloSourceCoordinator.Registration;

/** One logical server's source candidates, effective priorities and arbitration state. */
public final class HaloSourceHost implements AutoCloseable {
    public enum PriorityStatus { ACTIVE, AUTO_DEMOTED, DISABLED_CONFLICT }
    public record PriorityEntry(String sourceId, int defaultPriority, int configuredPriority,
                                Integer effectivePriority, long registrationOrder, PriorityStatus status,
                                String conflictWith, String fallbackConflictWith) {}
    public record PrioritySnapshot(Map<String, PriorityEntry> entries, Map<String, Integer> persistedPriorities) {}
    public record Candidate(String sourceId, Integer priority, Identifier definition, PriorityStatus status) {}

    private final ServerRuntime runtime;
    private final Thread owner;
    private final Map<String, Integer> configured = new LinkedHashMap<>();
    private final Map<String, PriorityEntry> priorities = new LinkedHashMap<>();
    private final Map<UUID, Map<String, Identifier>> candidates = new HashMap<>();
    private final Set<UUID> active = new HashSet<>();
    private boolean closed;

    public HaloSourceHost(ServerRuntime runtime, Map<String, Integer> configuredPriorities) {
        this.runtime = Objects.requireNonNull(runtime);
        this.owner = Thread.currentThread();
        if (configuredPriorities != null) configured.putAll(configuredPriorities);
        HaloSourceCoordinator.bind(this);
        recompute();
    }

    public PrioritySnapshot priorities() {
        checkThread();
        return snapshot();
    }

    public PrioritySnapshot reconfigure(Map<String, Integer> values) {
        checkThread();
        configured.clear();
        if (values != null) configured.putAll(values);
        recompute();
        reconcileAll();
        return snapshot();
    }

    public List<Candidate> candidates(UUID entity) {
        checkThread();
        Map<String, Identifier> values = candidates.getOrDefault(entity, Map.of());
        return values.entrySet().stream().map(entry -> {
            PriorityEntry priority = priorities.get(entry.getKey());
            return priority == null ? null : new Candidate(entry.getKey(), priority.effectivePriority(),
                entry.getValue(), priority.status());
        }).filter(Objects::nonNull).sorted(Comparator.comparing(Candidate::priority,
                Comparator.nullsLast(Comparator.reverseOrder()))
            .thenComparing(Candidate::sourceId)).toList();
    }

    public void activate(UUID entity) { checkThread(); active.add(entity); reconcile(entity); }
    public void deactivate(UUID entity) { checkThread(); active.remove(entity); runtime.unload(entity); }
    public void forget(UUID entity) {
        checkThread(); active.remove(entity); candidates.remove(entity); runtime.unload(entity);
    }

    boolean set(Registration source, UUID entity, String definition) {
        checkThread();
        Identifier id = new Identifier(definition);
        Identifier old = candidates.computeIfAbsent(entity, ignored -> new LinkedHashMap<>())
            .put(source.sourceId(), id);
        active.add(entity);
        if (!id.equals(old)) reconcile(entity);
        return !id.equals(old);
    }

    boolean clear(Registration source, UUID entity) {
        checkThread();
        Map<String, Identifier> values = candidates.get(entity);
        if (values == null || values.remove(source.sourceId()) == null) return false;
        if (values.isEmpty()) candidates.remove(entity);
        reconcile(entity);
        return true;
    }

    int clearAll(Registration source) {
        checkThread();
        int removed = 0;
        for (UUID entity : new ArrayList<>(candidates.keySet())) {
            Map<String, Identifier> values = candidates.get(entity);
            if (values.remove(source.sourceId()) != null) { removed++; reconcile(entity); }
            if (values.isEmpty()) candidates.remove(entity);
        }
        return removed;
    }

    void registered(Registration source) {
        checkThread();
        assign(source, occupied());
    }

    void closed(Registration source) {
        checkThread();
        priorities.remove(source.sourceId());
        clearAll(source);
        // Deliberately do not re-run disabled sources until explicit reconfigure/restart.
    }

    private void recompute() {
        priorities.clear();
        Map<Integer, String> occupied = new HashMap<>();
        for (Registration source : HaloSourceCoordinator.registrations().stream()
                .sorted(Comparator.comparingLong(Registration::order)).toList()) assign(source, occupied);
    }

    private void assign(Registration source, Map<Integer, String> occupied) {
        int desired = configured.getOrDefault(source.sourceId(), source.defaultPriority());
        configured.putIfAbsent(source.sourceId(), desired);
        Integer effective = desired;
        PriorityStatus status = PriorityStatus.ACTIVE;
        String conflict = occupied.get(desired);
        String fallbackConflict = null;
        if (conflict != null) {
            if (desired != Integer.MIN_VALUE && !occupied.containsKey(desired - 1)) {
                effective = desired - 1;
                configured.put(source.sourceId(), effective);
                status = PriorityStatus.AUTO_DEMOTED;
            } else {
                if (desired != Integer.MIN_VALUE) fallbackConflict = occupied.get(desired - 1);
                effective = null;
                status = PriorityStatus.DISABLED_CONFLICT;
            }
        }
        priorities.put(source.sourceId(), new PriorityEntry(source.sourceId(), source.defaultPriority(),
            desired, effective, source.order(), status, conflict, fallbackConflict));
        if (effective != null) occupied.put(effective, source.sourceId());
    }

    private Map<Integer, String> occupied() {
        Map<Integer, String> result = new HashMap<>();
        priorities.values().forEach(value -> {
            if (value.effectivePriority() != null) result.put(value.effectivePriority(), value.sourceId());
        });
        return result;
    }

    private PrioritySnapshot snapshot() {
        return new PrioritySnapshot(Map.copyOf(priorities), Map.copyOf(configured));
    }

    private void reconcileAll() { new ArrayList<>(active).forEach(this::reconcile); }

    private void reconcile(UUID entity) {
        if (!active.contains(entity)) return;
        Candidate winner = candidates(entity).stream().filter(candidate -> candidate.priority() != null)
            .findFirst().orElse(null);
        if (winner == null) runtime.clearSourceSelection(entity);
        else runtime.applySourceSelection(entity, winner.sourceId(), winner.priority(), winner.definition());
    }

    private void checkThread() {
        if (closed) throw new IllegalStateException("halo source host is closed");
        if (Thread.currentThread() != owner) throw new IllegalStateException("halo source mutation must run on the logical server thread");
    }

    @Override public void close() {
        checkThread();
        closed = true;
        candidates.clear(); active.clear(); priorities.clear();
        HaloSourceCoordinator.unbind(this);
    }
}
