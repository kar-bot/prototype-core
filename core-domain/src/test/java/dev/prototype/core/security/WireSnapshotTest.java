package dev.prototype.core.security;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.Gson;
import dev.prototype.core.api.AddonDefinition;
import dev.prototype.core.api.ClassDefinition;
import dev.prototype.core.api.CoreService;
import dev.prototype.core.api.Id;
import dev.prototype.core.api.Registry;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WireSnapshotTest {
    private static final UUID VIEWER = UUID.fromString("12345678-1234-1234-1234-123456789abc");
    private static final UUID OTHER = UUID.fromString("12345678-1234-1234-1234-123456789def");
    private static final Id KNOWN = Id.parse("demo:scholar");
    private static final Id HIDDEN_SIBLING = Id.parse("demo:secret_sibling");
    private static final Id SECRET = Id.parse("hidden:very_rare");
    private CoreService service() {
        var registry = new Registry();
        registry.register(new AddonDefinition(Id.parse("demo:demo"), "Demo", "1", "demo:textures/gui/thumbnail.png",
                List.of(new ClassDefinition(KNOWN, "Scholar"), new ClassDefinition(HIDDEN_SIBLING, "Hidden Sibling"))));
        registry.register(new AddonDefinition(Id.parse("hidden:hidden"), "Forbidden Metadata", "secret_version", "hidden:textures/secret.png",
                List.of(new ClassDefinition(SECRET, "Very Rare Secret"))));
        return new CoreService(registry);
    }
    private WireSnapshot view(CoreService service, boolean admin) {
        return WireSnapshot.create(service, VIEWER, admin, Map.of(VIEWER, new WireSnapshot.PlayerIdentity("Viewer", true),
                OTHER, new WireSnapshot.PlayerIdentity("Other", true)), "", false);
    }
    @Test void unassignedPlayerReceivesNoRegistryMetadata() {
        var snapshot = view(service(), false);
        assertEquals("PLAYER", snapshot.mode()); assertTrue(snapshot.addons().isEmpty());
        assertEquals(1, snapshot.players().size()); assertEquals(VIEWER.toString(), snapshot.players().getFirst().uuid());
    }
    @Test void serializedOrdinaryViewExcludesSecretAndOtherPlayers() {
        var service = service(); service.setClass(VIEWER, KNOWN); service.setClass(OTHER, SECRET);
        String json = new Gson().toJson(view(service, false));
        assertTrue(json.contains("demo:scholar")); assertTrue(json.contains("demo:textures/gui/thumbnail.png"));
        for (String forbidden : List.of("hidden:", "Forbidden Metadata", "secret_version", "Very Rare Secret", "secret_sibling", "Hidden Sibling", OTHER.toString(), "Other")) {
            assertFalse(json.contains(forbidden), forbidden);
        }
    }
    @Test void sameAddonClassListIsIndividuallyFiltered() {
        var service = service(); service.setClass(VIEWER, KNOWN);
        var snapshot = view(service, false);
        assertEquals(1, snapshot.addons().size());
        assertEquals(List.of(new WireSnapshot.ClassView("demo:scholar", "Scholar")), snapshot.addons().getFirst().classes());
    }
    @Test void knownDisabledAddonRemainsVisibleAsDisabled() {
        var service = service(); service.setClass(VIEWER, KNOWN); service.setAddonEnabled(Id.parse("demo:demo"), false);
        var snapshot = view(service, false);
        assertFalse(snapshot.addons().getFirst().enabled());
        assertEquals(List.of("demo:scholar"), snapshot.players().getFirst().allowedClasses());
        assertTrue(service.switchSuggestions(VIEWER).isEmpty());
    }
    @Test void adminViewContainsFullRegistryAndOnlinePlayers() {
        var snapshot = view(service(), true);
        assertEquals("ADMIN", snapshot.mode()); assertEquals(2, snapshot.addons().size()); assertEquals(2, snapshot.players().size());
        assertTrue(new Gson().toJson(snapshot).contains("hidden:very_rare"));
    }
    @Test void classBannedPlayerRetainsOwnKnowledgeAndNoSwitchSuggestions() {
        var service = service(); service.setClass(VIEWER, KNOWN); service.setClassBanned(VIEWER, true);
        var snapshot = view(service, false);
        assertTrue(snapshot.players().getFirst().classBanned());
        assertEquals("demo:scholar", snapshot.players().getFirst().activeClass());
        assertEquals(1, snapshot.addons().size()); assertTrue(service.switchSuggestions(VIEWER).isEmpty());
    }
    @Test void revocationResponseContainsNoCachedRegistryOrPlayers() {
        var snapshot = WireSnapshot.closed("Administrative permission required.");
        assertEquals("CLOSE", snapshot.mode()); assertFalse(snapshot.open()); assertEquals(0, snapshot.revision());
        assertTrue(snapshot.addons().isEmpty()); assertTrue(snapshot.players().isEmpty());
    }
    @Test void broadcastsNeverOpenScreensAndExplicitOpenDoes() {
        assertFalse(view(service(), true).open());
        assertTrue(WireSnapshot.create(service(), VIEWER, true, Map.of(), "", true).open());
    }
}
