package dev.prototype.core.control;

import dev.prototype.core.api.ClassControlSlot;
import dev.prototype.core.api.CoreService;
import dev.prototype.core.api.Id;
import dev.prototype.core.api.minecraft.ClassControlHandler;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.minecraft.server.level.ServerPlayer;

/** Internal startup-frozen bindings. No client class selection or handler metadata crosses the wire. */
public final class ClassControlDispatcher {
    private static final System.Logger LOGGER = System.getLogger(ClassControlDispatcher.class.getName());
    private final Map<Id, Map<ClassControlSlot, ClassControlHandler>> handlers = new HashMap<>();
    private boolean frozen;

    public Map<Id, Map<ClassControlSlot, ClassControlHandler>> prepare(
            Set<Id> classes, Map<Id, Map<ClassControlSlot, ClassControlHandler>> additions) {
        if (frozen) throw new IllegalStateException("Prototype control registration has closed");
        Objects.requireNonNull(additions, "controls");
        var copy = new HashMap<Id, Map<ClassControlSlot, ClassControlHandler>>();
        additions.forEach((id, slots) -> {
            if (id == null || !classes.contains(id) || handlers.containsKey(id)) {
                throw new IllegalArgumentException("Invalid or conflicting control registration");
            }
            copy.put(id, Map.copyOf(Objects.requireNonNull(slots, "slots")));
        });
        return Map.copyOf(copy);
    }

    public void register(Set<Id> classes, Map<Id, Map<ClassControlSlot, ClassControlHandler>> additions) {
        handlers.putAll(prepare(classes, additions));
    }

    public void freeze() { frozen = true; }

    /** Called only after transport validates the current authenticated connection and rate limit. */
    @SuppressWarnings("removal")
    public void dispatch(ServerPlayer player, CoreService service, ClassControlSlot slot) {
        if (!player.level().getServer().isSameThread()) {
            throw new IllegalStateException("Class controls must run on the server thread");
        }
        if (slot == null || player.hasDisconnected()) return;
        var active = service.operationalActive(player.getUUID());
        if (active.isEmpty() || !service.isAuthorized(player.getUUID(), active.get())) return;
        var handler = handlers.getOrDefault(active.get(), Map.of()).get(slot);
        if (handler == null) return;
        try { handler.handle(player); }
        catch (Throwable error) {
            if (error instanceof VirtualMachineError fatal) throw fatal;
            if (error instanceof ThreadDeath fatal) throw fatal;
            LOGGER.log(System.Logger.Level.WARNING, "Prototype class control handler failed", error);
        }
    }
}
