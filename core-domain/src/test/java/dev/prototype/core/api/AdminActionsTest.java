package dev.prototype.core.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class AdminActionsTest {
    private static final UUID PLAYER = UUID.randomUUID();
    private static final Id CLASS = Id.parse("demo:class");
    private static final Id ADDON = Id.parse("demo:addon");
    private CoreService service;
    private AdminActions actions;
    @BeforeEach void setup() {
        var registry = new Registry(); registry.register(RegistryTest.addon(ADDON.toString(), CLASS));
        service = new CoreService(registry); actions = new AdminActions(service);
    }
    static Stream<AdminRequest> privilegedRequests() {
        return Stream.of(new AdminRequest.SetClass(PLAYER, CLASS), new AdminRequest.AddClass(PLAYER, CLASS),
                new AdminRequest.RemoveClass(PLAYER, CLASS), new AdminRequest.ClearClasses(PLAYER),
                new AdminRequest.SetHybrid(PLAYER, true), new AdminRequest.SetClassBanned(PLAYER, false),
                new AdminRequest.SetAddonEnabled(ADDON, true));
    }
    @ParameterizedTest @MethodSource("privilegedRequests")
    void ordinaryClientsCannotPerformAnyAdministrativeMutation(AdminRequest request) {
        service.setClassBanned(PLAYER, true); service.setAddonEnabled(ADDON, false);
        var before = service.exportState();
        assertEquals(ActionResult.FORBIDDEN, actions.apply(false, service.revision(), request));
        assertEquals(before, service.exportState());
    }
    @ParameterizedTest @MethodSource("privilegedRequests")
    void staleRequestsCannotOverwriteNewerState(AdminRequest request) {
        long stale = service.revision(); service.setClass(PLAYER, CLASS); var before = service.exportState();
        assertEquals(ActionResult.CONFLICT, actions.apply(true, stale, request));
        assertEquals(before, service.exportState());
    }
    @Test void freshAdministrativeRequestCommitsAndOldRevisionThenConflicts() {
        assertTrue(actions.apply(true, 0, new AdminRequest.SetClass(PLAYER, CLASS)).success());
        assertEquals(ActionResult.CONFLICT, actions.apply(true, 0, new AdminRequest.ClearClasses(PLAYER)));
        assertEquals(CLASS, service.activeClass(PLAYER).orElseThrow());
    }
    @Test void permissionCheckPrecedesRevisionAndRequestParsing() {
        assertEquals(ActionResult.FORBIDDEN, actions.apply(false, -20, null));
    }
    @Test void malformedTypedRequestsFailWithoutStateMutation() {
        assertEquals(ActionResult.Status.INVALID_REQUEST, actions.apply(true, 0, null).status());
        assertEquals(ActionResult.Status.INVALID_REQUEST, actions.apply(true, 0, new AdminRequest.SetHybrid(null, true)).status());
        assertEquals(ActionResult.Status.INVALID_REQUEST, actions.apply(true, 0, new AdminRequest.SetClass(PLAYER, null)).status());
        assertEquals(ActionResult.Status.INVALID_REQUEST, actions.apply(true, 0, new AdminRequest.SetAddonEnabled(null, true)).status());
        assertEquals(0, service.revision());
    }
}
