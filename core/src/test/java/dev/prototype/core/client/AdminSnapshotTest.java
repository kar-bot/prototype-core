package dev.prototype.core.client;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class AdminSnapshotTest {
    private static final String PLAYER = "72a8f975-84f3-47e7-bf10-180c9acbd196";

    @Test
    void absentOpenFlagCannotOpenAnUnsolicitedAdminScreen() {
        var snapshot = AdminSnapshot.parse("{\"mode\":\"ADMIN\",\"revision\":42}");
        assertEquals("ADMIN", snapshot.mode());
        assertEquals(42, snapshot.revision());
        assertFalse(snapshot.open());
        assertTrue(snapshot.players().isEmpty());
        assertTrue(snapshot.addons().isEmpty());
    }

    @Test
    void explicitOpenAndServerFeedbackSurviveParsing() {
        var snapshot = AdminSnapshot.parse("{\"mode\":\"ADMIN\",\"open\":true,\"revision\":9001,"
            + "\"message\":\"State changed; refreshed.\"}");
        assertTrue(snapshot.open());
        assertEquals("State changed; refreshed.", snapshot.message());
        assertEquals(9001, snapshot.revision());
    }

    @Test
    void absentModeClosesAndNullArraysAreEmpty() {
        var snapshot = AdminSnapshot.parse("{\"players\":null,\"addons\":null,\"message\":null}");
        assertEquals("CLOSE", snapshot.mode());
        assertTrue(snapshot.players().isEmpty());
        assertTrue(snapshot.addons().isEmpty());
        assertEquals("", snapshot.message());
    }

    @Test
    void bannedHybridShowsAllowedClassesSeparatelyFromStoredActiveClass() {
        var snapshot = AdminSnapshot.parse("""
            {"mode":"ADMIN","players":[{"uuid":"%s","name":"Alex","online":true,
            "classBanned":true,"hybridEnabled":true,"allowedClasses":["demo:blade","other:blade"],
            "activeClass":"other:blade"}]}
            """.formatted(PLAYER));
        var player = snapshot.players().getFirst();
        assertEquals(UUID.fromString(PLAYER), player.uuid());
        assertTrue(player.online());
        assertTrue(player.classBanned());
        assertTrue(player.hybridEnabled());
        assertEquals(2, player.allowedClasses().size());
        assertEquals("other:blade", player.activeClass());
        assertThrows(UnsupportedOperationException.class, () -> player.allowedClasses().clear());
    }

    @Test
    void disabledMulticlassAddonRetainsEachClassNameAndState() {
        var snapshot = AdminSnapshot.parse("""
            {"mode":"ADMIN","addons":[{"id":"demo:demo","displayName":"Demo","version":"1.2",
            "thumbnail":"demo:textures/gui/addon.png","enabled":false,"classes":[
            {"id":"demo:first","displayName":"First"},{"id":"demo:second","displayName":"Second"}]}]}
            """);
        assertEquals(2, snapshot.classes().size());
        assertFalse(snapshot.classes().get("demo:first").enabled());
        assertEquals("Second", snapshot.className("demo:second"));
        assertEquals("unknown:history", snapshot.className("unknown:history"));
        assertEquals("None", snapshot.className(null));
        assertEquals("None", snapshot.className(""));
        assertEquals("1.2", snapshot.addons().getFirst().version());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.addons().clear());
    }

    @Test
    void missingThumbnailGetsAnEmptyFallbackPath() {
        var snapshot = AdminSnapshot.parse("{\"addons\":[{\"id\":\"demo:addon\",\"thumbnail\":null}]}");
        assertEquals("", snapshot.addons().getFirst().thumbnail());
        assertEquals("demo:addon", snapshot.addons().getFirst().displayName());
    }

    @Test
    void missingActiveClassHasNoFabricatedAuthorization() {
        var snapshot = AdminSnapshot.parse("{\"players\":[{\"uuid\":\"" + PLAYER + "\",\"activeClass\":null}]}");
        assertFalse(snapshot.players().getFirst().hybridEnabled());
        assertFalse(snapshot.players().getFirst().classBanned());
        assertTrue(snapshot.players().getFirst().allowedClasses().isEmpty());
        assertEquals("", snapshot.players().getFirst().activeClass());
    }

    @Test
    void malformedSnapshotAndInvalidPlayerUuidAreRejected() {
        assertThrows(RuntimeException.class, () -> AdminSnapshot.parse("{"));
        assertThrows(RuntimeException.class, () -> AdminSnapshot.parse("[]"));
        assertThrows(RuntimeException.class, () -> AdminSnapshot.parse("{\"players\":[{\"uuid\":\"bad\"}]}"));
        assertThrows(RuntimeException.class, () -> AdminSnapshot.parse("{\"addons\":42}"));
    }
}
