package dev.prototype.core.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class CoreServiceTest {
    private static final UUID PLAYER = UUID.fromString("12345678-1234-5678-1234-567812345678");
    private static final Id A = Id.parse("first:a");
    private static final Id B = Id.parse("second:b");
    private static final Id SECRET = Id.parse("secret:hidden");
    private static final Id FIRST = Id.parse("first:addon");
    private static final Id SECOND = Id.parse("second:addon");
    private Registry registry;
    private CoreService service;
    private List<String> events;

    @BeforeEach void setup() {
        registry = new Registry(); events = new ArrayList<>();
        registry.register(RegistryTest.addon(FIRST.toString(), A), lifecycle());
        registry.register(RegistryTest.addon(SECOND.toString(), B), lifecycle());
        registry.register(RegistryTest.addon("secret:addon", SECRET), lifecycle());
        service = new CoreService(registry);
    }
    private AddonLifecycle lifecycle() {
        return new AddonLifecycle() {
            public void onAddonEnabled(Id addon) { events.add("enabled:" + addon); }
            public void onAddonDisabled(Id addon) { events.add("disabled:" + addon); }
            public void onClassActivated(PlayerClassContext context) { events.add("activate:" + context.classId() + ":" + context.reason()); }
            public void onClassDeactivated(PlayerClassContext context) { events.add("deactivate:" + context.classId() + ":" + context.reason()); }
        };
    }
    @Test void defaultIsNonHybridEmptyUnbannedAndNoActive() {
        assertEquals(PlayerState.empty(), service.state(PLAYER));
        assertTrue(service.operationalActive(PLAYER).isEmpty());
        assertFalse(service.isAuthorized(PLAYER, A));
        assertTrue(service.playerSnapshot(PLAYER).classes().isEmpty());
    }
    @Test void nonHybridSetReplacesAndActivatesClass() {
        assertTrue(service.setClass(PLAYER, A).success());
        assertTrue(service.setClass(PLAYER, B).success());
        assertEquals(Set.of(B), service.allowedClasses(PLAYER));
        assertEquals(B, service.activeClass(PLAYER).orElseThrow());
        assertTrue(service.isAuthorized(PLAYER, B));
        assertFalse(service.isAuthorized(PLAYER, A));
    }
    @Test void addingSecondClassRequiresHybrid() {
        service.addClass(PLAYER, A); long revision = service.revision();
        assertEquals(ActionResult.Status.INVALID_STATE, service.addClass(PLAYER, B).status());
        assertEquals(revision, service.revision());
        assertEquals(Set.of(A), service.allowedClasses(PLAYER));
    }
    @Test void hybridAddAllowsMultipleButOnlyOneIsAuthorizedActive() {
        service.setClass(PLAYER, A); service.setHybrid(PLAYER, true); service.addClass(PLAYER, B);
        assertEquals(Set.of(A, B), service.allowedClasses(PLAYER));
        assertTrue(service.isAuthorized(PLAYER, A)); assertFalse(service.isAuthorized(PLAYER, B));
        assertTrue(service.switchClass(PLAYER, B).success());
        assertFalse(service.isAuthorized(PLAYER, A)); assertTrue(service.isAuthorized(PLAYER, B));
    }
    @Test void hybridSetPreservesOtherAssignmentsAndActivatesSelected() {
        service.setClass(PLAYER, A); service.setHybrid(PLAYER, true); service.setClass(PLAYER, B);
        assertEquals(Set.of(A, B), service.allowedClasses(PLAYER));
        assertEquals(B, service.activeClass(PLAYER).orElseThrow());
    }
    @Test void unregisteredAssignmentCannotMutateState() {
        assertFalse(service.setClass(PLAYER, Id.parse("missing:class")).success());
        assertFalse(service.addClass(PLAYER, null).success());
        assertEquals(0, service.revision());
    }
    @Test void ordinaryUnauthorizedAndNonexistentFailuresAreIndistinguishable() {
        service.setClass(PLAYER, A);
        assertEquals(service.switchClass(PLAYER, SECRET), service.switchClass(PLAYER, Id.parse("missing:class")));
        assertEquals(ActionResult.ACCESS_DENIED, service.switchClass(PLAYER, null));
        assertEquals(A, service.activeClass(PLAYER).orElseThrow());
    }
    @Test void classBanSuppressesActiveAndPreservesHistory() {
        service.setClass(PLAYER, A); var before = service.allowedClasses(PLAYER);
        service.setClassBanned(PLAYER, true);
        assertFalse(service.isAuthorized(PLAYER, A));
        assertTrue(service.operationalActive(PLAYER).isEmpty());
        assertEquals(A, service.activeClass(PLAYER).orElseThrow());
        assertEquals(before, service.allowedClasses(PLAYER));
        assertEquals(ActionResult.ACCESS_DENIED, service.switchClass(PLAYER, A));
        service.setClassBanned(PLAYER, false);
        assertTrue(service.isAuthorized(PLAYER, A));
    }
    @Test void classBannedSuggestionsEmptyButKnownMetadataRetained() {
        service.setClass(PLAYER, A); service.setClassBanned(PLAYER, true);
        assertTrue(service.switchSuggestions(PLAYER).isEmpty());
        assertEquals(List.of(A), service.playerSnapshot(PLAYER).classes().stream().map(PlayerSnapshot.KnownClass::id).toList());
    }
    @Test void disabledHybridRetainsActiveClassAndOneAssignment() {
        service.setClass(PLAYER, A); service.setHybrid(PLAYER, true); service.setClass(PLAYER, B);
        service.setHybrid(PLAYER, false);
        assertFalse(service.state(PLAYER).hybridEnabled());
        assertEquals(Set.of(B), service.allowedClasses(PLAYER));
        assertEquals(B, service.activeClass(PLAYER).orElseThrow());
    }
    @Test void hybridReductionWithNoActiveIsDeterministic() {
        var historical = new CoreState(Map.of(PLAYER, new PlayerState(false, true, Set.of(B, A), null)), Map.of(), 7);
        var restored = new CoreService(registry, historical); restored.setHybrid(PLAYER, false);
        assertEquals(Set.of(A), restored.allowedClasses(PLAYER));
        assertEquals(A, restored.activeClass(PLAYER).orElseThrow());
    }
    @Test void removeActiveChoosesDeterministicRemainingClass() {
        service.setHybrid(PLAYER, true); service.setClass(PLAYER, A); service.setClass(PLAYER, B);
        service.removeClass(PLAYER, B);
        assertEquals(A, service.activeClass(PLAYER).orElseThrow());
        service.removeClass(PLAYER, A);
        assertTrue(service.activeClass(PLAYER).isEmpty());
    }
    @Test void clearRetainsBanAndHybridAuthorization() {
        service.setHybrid(PLAYER, true); service.setClass(PLAYER, A); service.setClassBanned(PLAYER, true);
        service.clearClasses(PLAYER);
        assertTrue(service.state(PLAYER).classBanned()); assertTrue(service.state(PLAYER).hybridEnabled());
        assertTrue(service.allowedClasses(PLAYER).isEmpty()); assertTrue(service.activeClass(PLAYER).isEmpty());
    }
    @Test void disableAddonImmediatelySuppressesAuthorizationAndRetainsState() {
        service.setClass(PLAYER, A); PlayerState before = service.state(PLAYER);
        service.setAddonEnabled(FIRST, false);
        assertFalse(service.isAuthorized(PLAYER, A)); assertFalse(service.isAddonEnabled(FIRST));
        assertEquals(before, service.state(PLAYER));
        assertEquals(ActionResult.ACCESS_DENIED, service.switchClass(PLAYER, A));
        assertFalse(service.playerSnapshot(PLAYER).classes().getFirst().enabled());
        service.setAddonEnabled(FIRST, true); assertTrue(service.isAuthorized(PLAYER, A));
    }
    @Test void runtimeAddonFlagsSurviveRestoration() {
        service.setClass(PLAYER, A); service.setAddonEnabled(FIRST, false);
        var restored = new CoreService(registry, service.exportState());
        assertFalse(restored.isAddonEnabled(FIRST)); assertEquals(Set.of(A), restored.allowedClasses(PLAYER));
        assertFalse(restored.isAuthorized(PLAYER, A));
        restored.setAddonEnabled(FIRST, true); assertTrue(restored.isAuthorized(PLAYER, A));
    }
    @Test void privacySnapshotAndSuggestionsNeverExposeSecretMetadata() {
        service.setClass(PLAYER, A);
        assertEquals(List.of(A), service.switchSuggestions(PLAYER));
        var snapshot = service.playerSnapshot(PLAYER);
        assertEquals(List.of(A), snapshot.classes().stream().map(PlayerSnapshot.KnownClass::id).toList());
        assertFalse(snapshot.toString().contains("secret"));
        assertEquals(3, registry.classes().size());
    }
    @Test void unknownHistoryIsRetainedAndInert() {
        Id unknown = Id.parse("removed:lost"); Id unknownAddon = Id.parse("removed:addon");
        var historical = new CoreState(Map.of(PLAYER, new PlayerState(false, true, Set.of(unknown, A), unknown)),
                Map.of(unknownAddon, false), 15);
        var restored = new CoreService(registry, historical);
        assertEquals(historical, restored.exportState()); assertTrue(restored.operationalActive(PLAYER).isEmpty());
        assertFalse(restored.isAuthorized(PLAYER, unknown)); assertFalse(restored.isAddonEnabled(unknownAddon));
        assertNull(restored.playerSnapshot(PLAYER).activeClass());
        assertEquals(List.of(A), restored.switchSuggestions(PLAYER));
    }
    @Test void reinstallMakesHistoricalAuthorizationOperational() {
        Id unknown = Id.parse("removed:lost");
        var historical = new CoreState(Map.of(PLAYER, new PlayerState(false, false, Set.of(unknown), unknown)), Map.of(), 15);
        registry.register(RegistryTest.addon("removed:addon", unknown));
        var restored = new CoreService(registry, historical);
        assertTrue(restored.isAuthorized(PLAYER, unknown));
    }
    @Test void offlineChangesHaveNoPlayerHooksAndJoinDetachAreIdempotent() {
        service.setClass(PLAYER, A); assertTrue(events.isEmpty());
        service.attachPlayer(PLAYER); service.attachPlayer(PLAYER);
        service.detachPlayer(PLAYER); service.detachPlayer(PLAYER);
        assertEquals(List.of("activate:first:a:PLAYER_JOIN", "deactivate:first:a:PLAYER_LEAVE"), events);
    }
    @Test void switchDeactivatesOldBeforeActivatingNew() {
        service.setHybrid(PLAYER, true); service.setClass(PLAYER, A); service.addClass(PLAYER, B); service.attachPlayer(PLAYER);
        events.clear(); service.switchClass(PLAYER, B);
        assertEquals(List.of("deactivate:first:a:CLASS_SWITCHED", "activate:second:b:CLASS_SWITCHED"), events);
    }
    @Test void banAndUnbanHooksUsePreservedActive() {
        service.setClass(PLAYER, A); service.attachPlayer(PLAYER); events.clear();
        service.setClassBanned(PLAYER, true); service.setClassBanned(PLAYER, false);
        assertEquals(List.of("deactivate:first:a:CLASS_BANNED", "activate:first:a:CLASS_UNBANNED"), events);
    }
    @Test void globalDisableCleansPlayerBeforeAddonThenEnableActivatesAfterAddon() {
        service.setClass(PLAYER, A); service.attachPlayer(PLAYER); events.clear();
        service.setAddonEnabled(FIRST, false); service.setAddonEnabled(FIRST, true);
        assertEquals(List.of("deactivate:first:a:ADDON_DISABLED", "disabled:first:addon", "enabled:first:addon", "activate:first:a:ADDON_ENABLED"), events);
    }
    @Test void startupCallsOnlyEnabledAddonsAndFreezesRegistration() {
        service.setAddonEnabled(SECOND, false); events.clear(); service.start(); service.start();
        assertEquals(List.of("enabled:first:addon", "enabled:secret:addon"), events);
        assertTrue(registry.isFrozen());
    }
    @Test void closeCleansUpWithoutPersistentlyDisablingAddons() {
        service.setClass(PLAYER, A); service.start(); service.attachPlayer(PLAYER); events.clear();
        var state = service.exportState(); service.close(); service.close();
        assertEquals(state, service.exportState()); assertFalse(service.isAddonEnabled(FIRST));
        assertFalse(service.isAuthorized(PLAYER, A));
        assertEquals("deactivate:first:a:SERVER_STOP", events.getFirst());
        assertEquals(4, events.size());
        assertThrows(IllegalStateException.class, () -> service.clearClasses(PLAYER));
    }
    @Test void failingCleanupCannotPreventLaterActivationOrCorruptState() {
        var failures = new ArrayList<Throwable>(); var broken = new Registry();
        broken.register(RegistryTest.addon(FIRST.toString(), A), new AddonLifecycle() {
            public void onClassDeactivated(PlayerClassContext context) { throw new IllegalStateException("Broken addon"); }
        });
        broken.register(RegistryTest.addon(SECOND.toString(), B), lifecycle());
        var isolated = new CoreService(broken, CoreState.empty(), (message, error) -> failures.add(error));
        isolated.setClass(PLAYER, A); isolated.attachPlayer(PLAYER); isolated.setClass(PLAYER, B);
        assertEquals(1, failures.size()); assertTrue(isolated.isAuthorized(PLAYER, B));
        assertEquals(List.of("activate:second:b:CLASS_ASSIGNED"), events);
    }
    @Test void reentrantLifecycleMutationIsRejectedAndErrorHandlerFailuresAreIsolated() {
        var broken = new Registry(); final CoreService[] reference = new CoreService[1];
        broken.register(RegistryTest.addon(FIRST.toString(), A), new AddonLifecycle() {
            public void onClassActivated(PlayerClassContext context) { reference[0].clearClasses(context.playerId()); }
        });
        reference[0] = new CoreService(broken, CoreState.empty(), (message, error) -> { throw new IllegalArgumentException("Logger broken"); });
        reference[0].setClass(PLAYER, A); assertDoesNotThrow(() -> reference[0].attachPlayer(PLAYER));
        assertTrue(reference[0].isAuthorized(PLAYER, A));
    }
    @Test void noOpOperationsDoNotAdvanceRevision() {
        service.setClass(PLAYER, A); long revision = service.revision();
        assertEquals(ActionResult.NO_CHANGE, service.setClass(PLAYER, A));
        assertEquals(ActionResult.NO_CHANGE, service.addClass(PLAYER, A));
        assertEquals(ActionResult.NO_CHANGE, service.setHybrid(PLAYER, false));
        assertEquals(ActionResult.NO_CHANGE, service.setClassBanned(PLAYER, false));
        assertEquals(ActionResult.NO_CHANGE, service.setAddonEnabled(FIRST, true));
        assertEquals(revision, service.revision());
    }
    @Test void immutableSnapshotsProtectHistoricalState() {
        service.setClass(PLAYER, A); var old = service.exportState(); service.setClass(PLAYER, B);
        assertEquals(Set.of(A), old.players().get(PLAYER).allowedClasses());
        assertThrows(UnsupportedOperationException.class, () -> old.players().clear());
        assertThrows(UnsupportedOperationException.class, () -> service.allowedClasses(PLAYER).clear());
    }
    @Test void playerStateRejectsOrNormalizesInvalidInvariants() {
        assertThrows(IllegalArgumentException.class, () -> new PlayerState(false, false, Set.of(A), B));
        assertEquals(Set.of(B), new PlayerState(false, false, Set.of(A, B), B).allowedClasses());
        assertEquals(Set.of(A), new PlayerState(false, false, Set.of(A, B), null).allowedClasses());
    }
    @Test void persistenceFailureLeavesAuthorizationRevisionAndLifecycleUnchanged() {
        service.setClass(PLAYER, A);
        var original = service.exportState();
        var transactional = new CoreService(registry, original, (message, error) -> {}, replacement -> {
            throw new IllegalStateException("Disk write failed");
        });
        transactional.attachPlayer(PLAYER); events.clear();
        assertThrows(IllegalStateException.class, () -> transactional.setClass(PLAYER, B));
        assertEquals(original, transactional.exportState());
        assertTrue(transactional.isAuthorized(PLAYER, A));
        assertFalse(transactional.isAuthorized(PLAYER, B));
        assertTrue(events.isEmpty());
        assertThrows(IllegalStateException.class, () -> transactional.setAddonEnabled(FIRST, false));
        assertTrue(transactional.isAddonEnabled(FIRST));
        assertEquals(original, transactional.exportState());
    }
    @Test void persistenceObserverRunsBeforeCommitAndCannotReenterMutations() {
        final CoreService[] transactional = new CoreService[1];
        var observations = new ArrayList<CoreState>();
        transactional[0] = new CoreService(registry, CoreState.empty(), (message, error) -> {}, replacement -> {
            assertEquals(0, transactional[0].revision());
            assertFalse(transactional[0].isAuthorized(PLAYER, A));
            assertThrows(IllegalStateException.class, () -> transactional[0].setClassBanned(PLAYER, true));
            observations.add(replacement);
        });
        assertTrue(transactional[0].setClass(PLAYER, A).success());
        assertEquals(transactional[0].exportState(), observations.getFirst());
        assertTrue(transactional[0].isAuthorized(PLAYER, A));
    }
    @Test void lifecycleObserversSeeCommittedAuthorizationDuringOldCleanup() {
        var callbacks = new Registry(); final CoreService[] reference = new CoreService[1];
        callbacks.register(RegistryTest.addon(FIRST.toString(), A), new AddonLifecycle() {
            public void onClassDeactivated(PlayerClassContext context) {
                assertFalse(reference[0].isAuthorized(PLAYER, A));
                assertTrue(reference[0].isAuthorized(PLAYER, B));
            }
        });
        callbacks.register(RegistryTest.addon(SECOND.toString(), B), new AddonLifecycle() {
            public void onClassActivated(PlayerClassContext context) { assertTrue(reference[0].isAuthorized(PLAYER, B)); }
        });
        var errors = new ArrayList<Throwable>();
        reference[0] = new CoreService(callbacks, CoreState.empty(), (message, error) -> errors.add(error));
        reference[0].setClass(PLAYER, A); reference[0].attachPlayer(PLAYER); reference[0].setClass(PLAYER, B);
        assertTrue(errors.isEmpty());
    }
    @Test void hybridReductionRetainsStoredActiveEvenWhenAddonDisabledOrPlayerBanned() {
        service.setClass(PLAYER, A); service.setHybrid(PLAYER, true); service.setClass(PLAYER, B);
        service.setAddonEnabled(SECOND, false); service.setClassBanned(PLAYER, true); service.setHybrid(PLAYER, false);
        assertEquals(Set.of(B), service.allowedClasses(PLAYER));
        assertEquals(B, service.activeClass(PLAYER).orElseThrow());
        assertTrue(service.operationalActive(PLAYER).isEmpty());
        service.setClassBanned(PLAYER, false); service.setAddonEnabled(SECOND, true);
        assertTrue(service.isAuthorized(PLAYER, B));
    }
}
