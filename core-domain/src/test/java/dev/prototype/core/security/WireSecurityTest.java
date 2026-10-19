package dev.prototype.core.security;

import static org.junit.jupiter.api.Assertions.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WireSecurityTest {
    private static final String PLAYER = "12345678-1234-1234-1234-123456789abc";
    private static String set(String extra) { return "{\"action\":\"set_class\",\"revision\":7,\"player\":\"" + PLAYER + "\",\"id\":\"demo:scholar\"" + extra + "}"; }
    private static void rejects(String json) { assertThrows(IllegalArgumentException.class, () -> WireRequest.parse(json)); }

    @Test void parsesBoundedAdminAssignment() {
        var request = WireRequest.parse(set(""));
        assertEquals(WireRequest.Action.SET_CLASS, request.action());
        assertEquals(UUID.fromString(PLAYER), request.player());
        assertEquals("demo:scholar", request.id());
        assertEquals(7, request.revision());
    }
    @Test void parsesBooleanMutationWithoutCoercion() {
        assertTrue(WireRequest.parse("{\"action\":\"set_hybrid\",\"revision\":0,\"player\":\"" + PLAYER + "\",\"enabled\":true}").enabled());
    }
    @Test void rejectsStringBoolean() { rejects("{\"action\":\"set_addon\",\"revision\":7,\"id\":\"demo:demo\",\"enabled\":\"true\"}"); }
    @Test void rejectsUnknownAndDuplicateFields() { rejects(set(",\"admin\":true")); rejects(set(",\"revision\":8")); }
    @Test void rejectsNullAndNestedValues() { rejects(set(",\"enabled\":null")); rejects("{\"action\":{\"admin\":true}}"); }
    @Test void rejectsMalformedIdentifier() { rejects(set("").replace("demo:scholar", "demo:../Bad ID")); }
    @Test void rejectsUnqualifiedIdentifier() { rejects(set("").replace("demo:scholar", "scholar")); }
    @Test void rejectsNoncanonicalOrMalformedUuid() {
        rejects(set("").replace(PLAYER, "1-2-3-4-5"));
        rejects(set("").replace(PLAYER, "12345678-1234-1234-1234-123456789ABC"));
        rejects(set("").replace(PLAYER, "not-a-uuid"));
    }
    @Test void rejectsMissingTargetOrRevision() {
        rejects("{\"action\":\"set_class\",\"revision\":7,\"id\":\"demo:scholar\"}");
        rejects("{\"action\":\"set_class\",\"player\":\"" + PLAYER + "\",\"id\":\"demo:scholar\"}");
    }
    @Test void rejectsAmbiguousRevisionNumbers() {
        for (String value : new String[] {"-1", "1.0", "1e1", "9223372036854775808", "\"7\""})
            rejects(set("").replace("\"revision\":7", "\"revision\":" + value));
    }
    @Test void acceptsMaxRevision() { assertEquals(Long.MAX_VALUE, WireRequest.parse(set("").replace("\"revision\":7", "\"revision\":9223372036854775807")).revision()); }
    @Test void rejectsExtraTargetOnSelfServiceActions() {
        rejects("{\"action\":\"switch\",\"id\":\"demo:scholar\",\"player\":\"" + PLAYER + "\"}");
        rejects("{\"action\":\"convert\",\"id\":\"demo:scholar\"}");
    }
    @Test void refreshModeIsExplicitAndNoPermissionAssertion() {
        assertEquals(WireRequest.Mode.ADMIN, WireRequest.parse("{\"action\":\"refresh\",\"mode\":\"ADMIN\"}").mode());
        rejects("{\"action\":\"refresh\",\"mode\":\"admin\"}");
        rejects("{\"action\":\"set_addon\",\"revision\":7,\"id\":\"demo:demo\",\"enabled\":true,\"mode\":\"ADMIN\"}");
    }
    @Test void rejectsOversizedRequest() { rejects(" ".repeat(WireRequest.MAX_LENGTH + 1)); }
    @Test void rejectsTrailingJsonAndLenientSyntax() {
        rejects(set("") + "{}"); rejects("{'action':'convert'}"); rejects("{action:'convert'}"); rejects("{\"action\":\"convert\",}");
    }
    @Test void rejectsUnknownAndCaseChangedAction() { rejects("{\"action\":\"give_me_admin\"}"); rejects("{\"action\":\"CONVERT\"}"); }
    @Test void allAdministrativeMutationKindsRequireCurrentPermission() {
        for (var action : WireRequest.Action.values()) if (action.adminMutation()) {
            var request = new WireRequest(action, 7, null, null, null, null);
            assertEquals(WireGuard.Decision.ADMIN_REQUIRED, WireGuard.authorize(request, false, 7), action.name());
            assertEquals(WireGuard.Decision.STALE_REVISION, WireGuard.authorize(request, true, 8), action.name());
            assertEquals(WireGuard.Decision.ALLOWED, WireGuard.authorize(request, true, 7), action.name());
        }
    }
    @Test void selfServiceStillRequiresDomainAuthorization() {
        // Passing the wire guard grants no class or conversion authorization.
        assertEquals(WireGuard.Decision.ALLOWED, WireGuard.authorize(WireRequest.parse("{\"action\":\"switch\",\"id\":\"secret:unknown\"}"), false, 7));
    }
    @Test void rateLimitBoundsBurstsAndRefills() {
        var guard = new WireGuard(); var id = UUID.randomUUID();
        for (int i = 0; i < 12; i++) assertTrue(guard.consume(id, 0));
        assertFalse(guard.consume(id, 0));
        assertTrue(guard.consume(id, 125_000_000));
        assertFalse(guard.consume(id, 125_000_000));
        assertTrue(guard.consume(id, 2_000_000_000));
    }
    @Test void rateLimitSeparatesActorsAndDisconnectClearsBucket() {
        var guard = new WireGuard(); var a = UUID.randomUUID(); var b = UUID.randomUUID();
        for (int i = 0; i < 12; i++) guard.consume(a, 0);
        assertFalse(guard.consume(a, 0)); assertTrue(guard.consume(b, 0));
        guard.disconnect(a); assertTrue(guard.consume(a, 0));
    }
}
