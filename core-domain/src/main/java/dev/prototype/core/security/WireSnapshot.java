package dev.prototype.core.security;

import dev.prototype.core.api.AddonDefinition;
import dev.prototype.core.api.CoreService;
import dev.prototype.core.api.PlayerState;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/** Internal immutable server-filtered view. Do not serialize the registry directly to ordinary clients. */
public record WireSnapshot(String mode, long revision, String message, boolean open,
                           List<PlayerView> players, List<AddonView> addons) {
    public WireSnapshot { players = List.copyOf(players); addons = List.copyOf(addons); }
    public record PlayerIdentity(String name, boolean online) {}
    public record PlayerView(String uuid, String name, boolean online, boolean classBanned,
                             boolean hybridEnabled, List<String> allowedClasses, String activeClass) {
        public PlayerView { allowedClasses = List.copyOf(allowedClasses); }
    }
    public record ClassView(String id, String displayName) {}
    public record AddonView(String id, String displayName, String version, String thumbnail,
                            boolean enabled, List<ClassView> classes) {
        public AddonView { classes = List.copyOf(classes); }
    }

    /** Admin permission is a current SERVER fact supplied by the dispatcher, never a client field. */
    public static WireSnapshot create(CoreService service, UUID viewer, boolean serverValidatedAdmin,
                                      Map<UUID, PlayerIdentity> identities, String message, boolean open) {
        var state = service.exportState();
        var own = state.players().getOrDefault(viewer, PlayerState.empty());
        Set<UUID> visiblePlayers = serverValidatedAdmin ? new TreeSet<>(state.players().keySet()) : Set.of(viewer);
        if (serverValidatedAdmin) visiblePlayers.addAll(identities.keySet());
        List<PlayerView> players = new ArrayList<>();
        for (UUID uuid : visiblePlayers) {
            PlayerState player = state.players().getOrDefault(uuid, PlayerState.empty());
            PlayerIdentity identity = identities.getOrDefault(uuid, new PlayerIdentity(uuid.toString().substring(0, 8), false));
            players.add(new PlayerView(uuid.toString(), identity.name(), identity.online(), player.classBanned(),
                    player.hybridEnabled(), player.allowedClasses().stream().map(Object::toString).toList(),
                    player.activeClass() == null ? "" : player.activeClass().toString()));
        }
        players.sort(Comparator.comparing(PlayerView::online).reversed().thenComparing(PlayerView::name).thenComparing(PlayerView::uuid));
        List<AddonView> addons = new ArrayList<>();
        for (AddonDefinition addon : service.registry().addons()) {
            List<ClassView> classes = addon.classes().stream()
                    .filter(clazz -> serverValidatedAdmin || own.allowedClasses().contains(clazz.id()))
                    .map(clazz -> new ClassView(clazz.id().toString(), clazz.displayName())).toList();
            if (classes.isEmpty()) continue;
            addons.add(new AddonView(addon.id().toString(), addon.displayName(), addon.version(), addon.thumbnail(),
                    state.addonEnabled().getOrDefault(addon.id(), true), classes));
        }
        return new WireSnapshot(serverValidatedAdmin ? "ADMIN" : "PLAYER", state.revision(), message, open, players, addons);
    }

    /** Revocation response deliberately carries no player, class, addon, or revision metadata. */
    public static WireSnapshot closed(String message) { return new WireSnapshot("CLOSE", 0, message, false, List.of(), List.of()); }
}
