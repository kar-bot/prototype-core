package dev.prototype.core.api.minecraft;

import dev.prototype.core.PrototypeCore;
import dev.prototype.core.PrototypeRuntime;
import dev.prototype.core.api.AddonDefinition;
import dev.prototype.core.api.AddonLifecycle;
import dev.prototype.core.api.CoreService;
import dev.prototype.core.api.Id;
import dev.prototype.core.api.Registry;
import dev.prototype.core.conversion.ConversionManager;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;

/** Stable Fabric-facing API. Register during mod initialization; query on the server thread. */
public final class PrototypeApi {
    private PrototypeApi() {}

    public static void registerAddon(AddonDefinition definition,
                                     Map<Id, SwordConversionProvider> providers,
                                     AddonLifecycle lifecycle) {
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(providers, "providers");
        Objects.requireNonNull(lifecycle, "lifecycle");
        Registry registry = PrototypeCore.registry();
        if (registry.isFrozen()) throw new IllegalStateException("Prototype registration has closed");
        Set<Id> classIds = definition.classes().stream().map(c -> c.id()).collect(Collectors.toSet());
        if (!classIds.equals(providers.keySet()) || providers.values().stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("Each registered class requires exactly one conversion provider");
        }
        // Registry validates every duplicate before mutation; providers cannot overlap existing registered classes.
        conversions().validateProviders(providers);
        registry.register(definition, lifecycle);
        conversions().registerProviders(providers);
    }

    public static void registerAddon(AddonDefinition definition, Map<Id, SwordConversionProvider> providers) {
        registerAddon(definition, providers, new AddonLifecycle() {});
    }

    public static void registerConvertible(Item item) { conversions().registerConvertible(item); }
    public static ConversionManager conversions() { return PrototypeCore.conversions(); }
    public static Registry registry() { return PrototypeCore.registry(); }
    public static CoreService service(MinecraftServer server) { return PrototypeRuntime.service(server); }
    public static boolean isAuthorized(ServerPlayer player, Id classId) {
        return service(player.level().getServer()).isAuthorized(player.getUUID(), classId);
    }
}
