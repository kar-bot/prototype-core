package dev.prototype.core.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Central authoritative authorization service. Mutation methods are trusted SERVER APIs:
 * never expose them directly to a client. Use a server permission/revision guard for requests.
 * Call from the server thread. Snapshots are immutable and callbacks cannot mutate this service.
 */
public final class CoreService implements AutoCloseable {
    private final Registry registry;
    private final BiConsumer<String, Throwable> errorHandler;
    private final Consumer<CoreState> beforeCommit;
    private final Set<UUID> onlinePlayers = new TreeSet<>();
    private CoreState state;
    private boolean dispatching;
    private boolean committing;
    private boolean started;
    private boolean closed;

    public CoreService(Registry registry) { this(registry, CoreState.empty()); }
    public CoreService(Registry registry, CoreState restored) {
        this(registry, restored, (message, error) -> System.getLogger(CoreService.class.getName())
                .log(System.Logger.Level.WARNING, message, error));
    }
    public CoreService(Registry registry, CoreState restored, BiConsumer<String, Throwable> errorHandler) {
        this(registry, restored, errorHandler, ignored -> {});
    }
    /** The observer must durably save or throw; state and lifecycle events commit only after it succeeds. */
    public CoreService(Registry registry, CoreState restored, BiConsumer<String, Throwable> errorHandler,
                       Consumer<CoreState> beforeCommit) {
        this.registry = Objects.requireNonNull(registry);
        this.state = Objects.requireNonNull(restored);
        this.errorHandler = Objects.requireNonNull(errorHandler);
        this.beforeCommit = Objects.requireNonNull(beforeCommit);
    }

    /** Invoke after installing the service in the server runtime, so addons can query it in hooks. */
    public synchronized void start() {
        ensureMutable();
        if (started) return;
        registry.freeze();
        started = true;
        dispatch(() -> {
            for (var addon : registry.addons()) if (isAddonEnabled(addon.id())) {
                invoke("addon enabled: " + addon.id(), () -> registry.lifecycle(addon.id()).onAddonEnabled(addon.id()));
            }
        });
    }

    public Registry registry() { return registry; }
    public synchronized CoreState state() { return state; }
    public synchronized CoreState exportState() { return state; }
    public synchronized long revision() { return state.revision(); }
    public synchronized PlayerState state(UUID playerId) {
        return state.players().getOrDefault(Objects.requireNonNull(playerId), PlayerState.empty());
    }
    public synchronized Set<UUID> onlinePlayers() { return Set.copyOf(onlinePlayers); }
    public synchronized boolean isClassBanned(UUID playerId) { return state(playerId).classBanned(); }
    public synchronized Set<Id> allowedClasses(UUID playerId) { return state(playerId).allowedClasses(); }
    public synchronized Optional<Id> activeClass(UUID playerId) { return Optional.ofNullable(state(playerId).activeClass()); }
    public synchronized boolean isAddonEnabled(Id addonId) {
        return !closed && addonId != null && registry.addon(addonId).isPresent() && state.addonEnabled().getOrDefault(addonId, true);
    }

    /** The primary kill switch: registered, enabled, allowed, active, and not class banned. */
    public synchronized boolean isAuthorized(UUID playerId, Id classId) {
        if (closed || classId == null || playerId == null) return false;
        var player = state(playerId);
        if (player.classBanned() || !classId.equals(player.activeClass()) || !player.allowedClasses().contains(classId)) return false;
        return registry.addonForClass(classId).map(addon -> isAddonEnabled(addon.id())).orElse(false);
    }
    public synchronized Optional<Id> operationalActive(UUID playerId) {
        Id active = state(playerId).activeClass();
        return isAuthorized(playerId, active) ? Optional.of(active) : Optional.empty();
    }

    public synchronized List<Id> switchSuggestions(UUID playerId) {
        var player = state(playerId);
        if (player.classBanned()) return List.of();
        return player.allowedClasses().stream().filter(id -> registry.addonForClass(id)
                .map(addon -> isAddonEnabled(addon.id())).orElse(false)).sorted().toList();
    }

    /** Unknown history is retained in persistence, but never sent as discoverable client metadata. */
    public synchronized PlayerSnapshot playerSnapshot(UUID playerId) {
        var player = state(playerId);
        var known = new ArrayList<PlayerSnapshot.KnownClass>();
        for (var id : player.allowedClasses()) {
            var definition = registry.classDefinition(id);
            var owner = registry.addonForClass(id);
            if (definition.isPresent() && owner.isPresent()) {
                var clazz = definition.get(); var addon = owner.get();
                known.add(new PlayerSnapshot.KnownClass(id, clazz.displayName(), addon.id(), addon.displayName(),
                        addon.thumbnail(), isAddonEnabled(addon.id()), id.equals(player.activeClass())));
            }
        }
        Id visibleActive = registry.classDefinition(player.activeClass()).isPresent() ? player.activeClass() : null;
        return new PlayerSnapshot(playerId, revision(), player.classBanned(), player.hybridEnabled(), visibleActive, known);
    }

    public synchronized ActionResult setClass(UUID playerId, Id classId) {
        ensureMutable();
        if (!knownClass(classId)) return ActionResult.invalidRequest("Unknown registered class.");
        var old = state(playerId);
        var allowed = new TreeSet<Id>();
        if (old.hybridEnabled()) allowed.addAll(old.allowedClasses());
        allowed.add(classId);
        return updatePlayer(playerId, new PlayerState(old.classBanned(), old.hybridEnabled(), allowed, classId), LifecycleReason.CLASS_ASSIGNED);
    }
    public synchronized ActionResult addClass(UUID playerId, Id classId) {
        ensureMutable();
        if (!knownClass(classId)) return ActionResult.invalidRequest("Unknown registered class.");
        var old = state(playerId);
        if (old.allowedClasses().contains(classId)) return ActionResult.NO_CHANGE;
        if (!old.hybridEnabled() && !old.allowedClasses().isEmpty()) {
            return new ActionResult(ActionResult.Status.INVALID_STATE, "Enable hybrid authorization before adding another class.");
        }
        var allowed = new TreeSet<>(old.allowedClasses()); allowed.add(classId);
        return updatePlayer(playerId, new PlayerState(old.classBanned(), old.hybridEnabled(), allowed,
                old.activeClass() == null ? classId : old.activeClass()), LifecycleReason.CLASS_ASSIGNED);
    }
    public synchronized ActionResult removeClass(UUID playerId, Id classId) {
        ensureMutable();
        var old = state(playerId);
        if (classId == null) return ActionResult.invalidRequest("Invalid class identifier.");
        if (!old.allowedClasses().contains(classId)) return ActionResult.NO_CHANGE;
        var allowed = new TreeSet<>(old.allowedClasses()); allowed.remove(classId);
        Id active = classId.equals(old.activeClass()) ? (allowed.isEmpty() ? null : allowed.first()) : old.activeClass();
        return updatePlayer(playerId, new PlayerState(old.classBanned(), old.hybridEnabled(), allowed, active), LifecycleReason.AUTHORIZATION_REMOVED);
    }
    public synchronized ActionResult clearClasses(UUID playerId) {
        ensureMutable();
        var old = state(playerId);
        return updatePlayer(playerId, new PlayerState(old.classBanned(), old.hybridEnabled(), Set.of(), null), LifecycleReason.AUTHORIZATION_REMOVED);
    }
    public synchronized ActionResult setHybrid(UUID playerId, boolean enabled) {
        ensureMutable();
        var old = state(playerId);
        Id active = old.activeClass();
        if (!enabled && old.allowedClasses().size() > 1 && active == null) active = old.allowedClasses().iterator().next();
        return updatePlayer(playerId, new PlayerState(old.classBanned(), enabled, old.allowedClasses(), active), LifecycleReason.HYBRID_DISABLED);
    }
    public synchronized ActionResult setClassBanned(UUID playerId, boolean banned) {
        ensureMutable();
        var old = state(playerId);
        return updatePlayer(playerId, new PlayerState(banned, old.hybridEnabled(), old.allowedClasses(), old.activeClass()),
                banned ? LifecycleReason.CLASS_BANNED : LifecycleReason.CLASS_UNBANNED);
    }
    public synchronized ActionResult switchClass(UUID playerId, Id classId) {
        ensureMutable();
        var old = state(playerId);
        if (old.classBanned() || classId == null || !old.allowedClasses().contains(classId)
                || registry.addonForClass(classId).filter(addon -> isAddonEnabled(addon.id())).isEmpty()) {
            return ActionResult.ACCESS_DENIED;
        }
        return updatePlayer(playerId, new PlayerState(false, old.hybridEnabled(), old.allowedClasses(), classId), LifecycleReason.CLASS_SWITCHED);
    }
    public synchronized ActionResult setAddonEnabled(Id addonId, boolean enabled) {
        ensureMutable();
        if (addonId == null || registry.addon(addonId).isEmpty()) return ActionResult.invalidRequest("Unknown registered addon.");
        if (isAddonEnabled(addonId) == enabled) return ActionResult.NO_CHANGE;
        var previous = new TreeMap<UUID, Optional<Id>>();
        for (var playerId : onlinePlayers) previous.put(playerId, operationalActive(playerId));
        var flags = new TreeMap<>(state.addonEnabled()); flags.put(addonId, enabled);
        commit(new CoreState(state.players(), flags, nextRevision()));
        dispatch(() -> {
            if (!enabled) {
                for (var entry : previous.entrySet()) {
                    entry.getValue().filter(id -> !operationalActive(entry.getKey()).equals(Optional.of(id)))
                            .ifPresent(id -> deactivate(entry.getKey(), id, LifecycleReason.ADDON_DISABLED));
                }
                invoke("addon disabled: " + addonId, () -> registry.lifecycle(addonId).onAddonDisabled(addonId));
            } else {
                invoke("addon enabled: " + addonId, () -> registry.lifecycle(addonId).onAddonEnabled(addonId));
                for (var entry : previous.entrySet()) operationalActive(entry.getKey())
                        .filter(id -> !entry.getValue().equals(Optional.of(id)))
                        .ifPresent(id -> activate(entry.getKey(), id, LifecycleReason.ADDON_ENABLED));
            }
        });
        return ActionResult.APPLIED;
    }

    public synchronized void attachPlayer(UUID playerId) {
        ensureMutable();
        if (!onlinePlayers.add(Objects.requireNonNull(playerId))) return;
        dispatch(() -> operationalActive(playerId).ifPresent(id -> activate(playerId, id, LifecycleReason.PLAYER_JOIN)));
    }
    public synchronized void detachPlayer(UUID playerId) {
        ensureMutable();
        if (!onlinePlayers.remove(Objects.requireNonNull(playerId))) return;
        dispatch(() -> operationalActive(playerId).ifPresent(id -> deactivate(playerId, id, LifecycleReason.PLAYER_LEAVE)));
    }

    @Override public synchronized void close() {
        if (closed) return;
        ensureMutable();
        var active = new TreeMap<UUID, Id>();
        for (var player : onlinePlayers) operationalActive(player).ifPresent(id -> active.put(player, id));
        var enabled = registry.addons().stream().filter(addon -> isAddonEnabled(addon.id())).toList();
        onlinePlayers.clear(); closed = true;
        dispatch(() -> {
            for (var entry : active.entrySet()) deactivate(entry.getKey(), entry.getValue(), LifecycleReason.SERVER_STOP);
            if (started) for (var addon : enabled) {
                invoke("addon server cleanup: " + addon.id(), () -> registry.lifecycle(addon.id()).onAddonDisabled(addon.id()));
            }
        });
    }

    private boolean knownClass(Id id) { return id != null && registry.classDefinition(id).isPresent(); }
    private long nextRevision() {
        if (state.revision() == Long.MAX_VALUE) throw new IllegalStateException("Core revision exhausted");
        return state.revision() + 1;
    }
    private void commit(CoreState replacement) {
        committing = true;
        try { beforeCommit.accept(replacement); }
        finally { committing = false; }
        state = replacement;
    }
    private ActionResult updatePlayer(UUID playerId, PlayerState replacement, LifecycleReason reason) {
        Objects.requireNonNull(playerId);
        if (state(playerId).equals(replacement)) return ActionResult.NO_CHANGE;
        var before = operationalActive(playerId);
        var players = new TreeMap<>(state.players()); players.put(playerId, replacement);
        commit(new CoreState(players, state.addonEnabled(), nextRevision()));
        var after = operationalActive(playerId);
        if (onlinePlayers.contains(playerId) && !before.equals(after)) dispatch(() -> {
            before.ifPresent(id -> deactivate(playerId, id, reason));
            after.ifPresent(id -> activate(playerId, id, reason));
        });
        return ActionResult.APPLIED;
    }
    private void deactivate(UUID playerId, Id classId, LifecycleReason reason) {
        registry.addonForClass(classId).ifPresent(addon -> invoke("class deactivated: " + classId,
                () -> registry.lifecycle(addon.id()).onClassDeactivated(new PlayerClassContext(playerId, classId, reason))));
    }
    private void activate(UUID playerId, Id classId, LifecycleReason reason) {
        registry.addonForClass(classId).ifPresent(addon -> invoke("class activated: " + classId,
                () -> registry.lifecycle(addon.id()).onClassActivated(new PlayerClassContext(playerId, classId, reason))));
    }
    private void ensureMutable() {
        if (closed) throw new IllegalStateException("Core service is closed");
        if (dispatching) throw new IllegalStateException("Lifecycle hooks may query authorization but must not mutate Core state");
        if (committing) throw new IllegalStateException("Persistence observers must not mutate Core state");
    }
    private void dispatch(Runnable events) {
        dispatching = true;
        try { events.run(); } finally { dispatching = false; }
    }
    @SuppressWarnings("removal") // Do not swallow process/thread termination signals while isolating addon hooks.
    private void invoke(String message, Runnable event) {
        try { event.run(); }
        catch (Throwable error) {
            if (error instanceof VirtualMachineError fatal) throw fatal;
            if (error instanceof ThreadDeath fatal) throw fatal;
            try { errorHandler.accept(message, error); }
            catch (Throwable handlerError) {
                if (handlerError instanceof VirtualMachineError fatal) throw fatal;
                if (handlerError instanceof ThreadDeath fatal) throw fatal;
                // Addon and reporting failures cannot roll back authoritative state or stop later hooks.
            }
        }
    }
}
