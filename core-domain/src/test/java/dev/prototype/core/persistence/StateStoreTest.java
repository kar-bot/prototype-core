package dev.prototype.core.persistence;

import dev.prototype.core.api.CoreState;
import dev.prototype.core.api.Id;
import dev.prototype.core.api.PlayerState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class StateStoreTest {
    @TempDir Path directory;
    private static final UUID PLAYER = UUID.fromString("c5740000-34dc-4c10-a5db-ffa467654a71");
    private static final UUID OTHER = UUID.fromString("bd9931b2-bbc1-49f7-a9f8-c4b6dc50ec43");
    private static final Id FIRST = Id.parse("historic:first");
    private static final Id SECOND = Id.parse("missing:second");
    private static final Id ADDON = Id.parse("missing:addon");

    @Test void absentFileStartsEmptyAndFirstSaveCreatesWorldDirectory() throws IOException {
        StateStore store = new StateStore(directory.resolve("new-world/prototype"));
        assertEquals(CoreState.empty(), store.load());
        assertFalse(store.isReadOnly());
        store.save(CoreState.empty());
        assertTrue(Files.isRegularFile(store.path()));
        assertEquals(CoreState.empty(), new StateStore(store.path().getParent()).load());
    }

    @Test void uuidHybridBanAllowedActiveAndAddonFlagsRoundTrip() throws IOException {
        CoreState original = new CoreState(Map.of(
                PLAYER, new PlayerState(true, true, Set.of(FIRST, SECOND), SECOND),
                OTHER, new PlayerState(false, false, Set.of(FIRST), FIRST)
        ), Map.of(ADDON, false, Id.parse("enabled:future"), true), 812);
        StateStore store = new StateStore(directory);
        store.save(original);
        assertEquals(original, new StateStore(directory).load());
        assertTrue(Files.readString(store.path()).contains("\"schemaVersion\": 1"));
    }

    @Test void removedAddonAndUnknownHistoricalClassesSurviveResave() throws IOException {
        CoreState original = state(7, true, true, Set.of(FIRST, SECOND), SECOND);
        new StateStore(directory).save(original);
        StateStore next = new StateStore(directory);
        next.save(next.load());
        assertEquals(original, new StateStore(directory).load());
    }

    @Test void malformedPlayerUuidsAndRecordsDoNotDiscardUnrelatedRecords() throws IOException {
        write("""
                {"schemaVersion":1,"revision":2,"players":{
                  "not-a-uuid":{"classBanned":false},
                  "1-1-1-1-1":{"classBanned":false},
                  "%s":false,
                  "%s":{"classBanned":true,"hybridEnabled":false,
                    "allowedClasses":["historic:first"],"activeClass":"historic:first"}
                },"addonEnabled":{"missing:addon":false}}
                """.formatted(OTHER, PLAYER));
        StateStore store = new StateStore(directory);
        CoreState recovered = store.load();
        assertEquals(Set.of(PLAYER), recovered.players().keySet());
        assertTrue(recovered.players().get(PLAYER).classBanned());
        assertEquals(false, recovered.addonEnabled().get(ADDON));
        assertEquals(3, store.diagnostics().size());
    }

    @Test void malformedClassIdsAreSkippedButUnknownValidIdsRemain() throws IOException {
        write("""
                {"schemaVersion":1,"players":{"%s":{"hybridEnabled":true,
                  "allowedClasses":["historic:first","missing:second","bare", "Upper:invalid",42,null,"bad:colon:twice"],
                  "activeClass":"missing:second"}},"addonEnabled":{"missing:addon":false,"bare":true}}
                """.formatted(PLAYER));
        StateStore store = new StateStore(directory);
        CoreState recovered = store.load();
        assertEquals(Set.of(FIRST, SECOND), recovered.players().get(PLAYER).allowedClasses());
        assertEquals(SECOND, recovered.players().get(PLAYER).activeClass());
        assertEquals(Map.of(ADDON, false), recovered.addonEnabled());
        assertFalse(store.diagnostics().isEmpty());
    }

    @Test void unauthorizedActiveIdIsClearedWithoutErasingAllowedHistory() throws IOException {
        write("""
                {"schemaVersion":1,"players":{"%s":{"hybridEnabled":true,
                  "allowedClasses":["historic:first","missing:second"],"activeClass":"unrelated:secret"}},
                  "addonEnabled":{"missing:addon":false}}
                """.formatted(PLAYER));
        CoreState recovered = new StateStore(directory).load();
        assertNull(recovered.players().get(PLAYER).activeClass());
        assertEquals(Set.of(FIRST, SECOND), recovered.players().get(PLAYER).allowedClasses());
        assertEquals(Map.of(ADDON, false), recovered.addonEnabled());
    }

    @Test void invalidActiveTypeDoesNotDestroyOtherPlayerFields() throws IOException {
        write("""
                {"schemaVersion":1,"players":{"%s":{"classBanned":true,"hybridEnabled":true,
                  "allowedClasses":["missing:second"],"activeClass":4}},"addonEnabled":{}}
                """.formatted(PLAYER));
        PlayerState player = new StateStore(directory).load().players().get(PLAYER);
        assertNull(player.activeClass());
        assertTrue(player.classBanned());
        assertEquals(Set.of(SECOND), player.allowedClasses());
    }

    @Test void malformedNonhybridMultipleClassesRetainPriorActive() throws IOException {
        write("""
                {"schemaVersion":1,"players":{"%s":{"hybridEnabled":false,
                  "allowedClasses":["historic:first","missing:second"],"activeClass":"missing:second"}},"addonEnabled":{}}
                """.formatted(PLAYER));
        PlayerState player = new StateStore(directory).load().players().get(PLAYER);
        assertEquals(Set.of(SECOND), player.allowedClasses());
        assertEquals(SECOND, player.activeClass());
    }

    @Test void malformedNonhybridMultipleClassesResolveLexicographicallyWhenNoActive() throws IOException {
        write("""
                {"schemaVersion":1,"players":{"%s":{"hybridEnabled":false,
                  "allowedClasses":["missing:second","historic:first"],"activeClass":null}},"addonEnabled":{}}
                """.formatted(PLAYER));
        PlayerState player = new StateStore(directory).load().players().get(PLAYER);
        assertEquals(Set.of(FIRST), player.allowedClasses());
        assertNull(player.activeClass());
    }

    @Test void invalidBooleanValuesFailClosedWithoutStringCoercion() throws IOException {
        write("""
                {"schemaVersion":1,"players":{"%s":{"classBanned":"false","hybridEnabled":"true",
                  "allowedClasses":["historic:first"],"activeClass":"historic:first"}},
                  "addonEnabled":{"missing:addon":"true"}}
                """.formatted(PLAYER));
        CoreState recovered = new StateStore(directory).load();
        assertTrue(recovered.players().get(PLAYER).classBanned());
        assertFalse(recovered.players().get(PLAYER).hybridEnabled());
        assertFalse(recovered.addonEnabled().get(ADDON));
    }

    @Test void damagedRecordMissingBanFlagDoesNotReviveAuthorization() throws IOException {
        write("""
                {"schemaVersion":1,"players":{"%s":{"hybridEnabled":false,
                  "allowedClasses":["historic:first"],"activeClass":"historic:first"}},"addonEnabled":{}}
                """.formatted(PLAYER));
        StateStore store = new StateStore(directory);
        PlayerState player = store.load().players().get(PLAYER);
        assertTrue(player.classBanned());
        assertEquals(FIRST, player.activeClass());
        assertTrue(store.diagnostics().stream().anyMatch(value -> value.contains("Missing classBanned")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{", "[]", "null", "{}", "{\"schemaVersion\":\"1\"}",
            "{\"schemaVersion\":1,\"schemaVersion\":1}", "{\"schemaVersion\":1} trailing",
            "{schemaVersion:1}", "{\"schemaVersion\":1,/*comment*/\"players\":{}}"})
    void corruptRootRemainsUnchangedAndCannotBeOverwritten(String invalid) throws IOException {
        write(invalid);
        StateStore store = new StateStore(directory);
        assertThrows(IOException.class, store::load);
        assertTrue(store.isReadOnly());
        assertThrows(IOException.class, () -> store.save(CoreState.empty()));
        assertEquals(invalid, Files.readString(store.path()));
    }

    @Test void saveWithoutLoadCannotOverwriteCorruptHistoricalFile() throws IOException {
        write("broken historical state");
        StateStore store = new StateStore(directory);
        assertThrows(IOException.class, () -> store.save(CoreState.empty()));
        assertEquals("broken historical state", Files.readString(store.path()));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"schemaVersion\":1,\"players\":{}}",
            "{\"schemaVersion\":1,\"players\":{},\"addonEnabled\":null}",
            "{\"schemaVersion\":1,\"players\":{},\"addonEnabled\":[]}",
            "{\"schemaVersion\":1,\"players\":[],\"addonEnabled\":{}}",
            "{\"schemaVersion\":1,\"addonEnabled\":{}}"
    })
    void missingOrMalformedStateCollectionsCannotEraseAuthorizationOrEnableAddons(String malformed) throws IOException {
        write(malformed);
        StateStore store = new StateStore(directory);
        assertThrows(IOException.class, store::load);
        assertTrue(store.isReadOnly());
        assertThrows(IOException.class, () -> store.save(CoreState.empty()));
        assertEquals(malformed, Files.readString(store.path()));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"schemaVersion\":1,\"players\":{}}",
            "{\"schemaVersion\":1,\"players\":{},\"addonEnabled\":null}",
            "{\"schemaVersion\":1,\"players\":[],\"addonEnabled\":{}}"
    })
    void malformedStateCollectionsRecoverValidBackupReadOnlyWithoutDeletingHistory(String malformed) throws IOException {
        StateStore original = new StateStore(directory);
        CoreState previous = state(9, true, true, Set.of(FIRST, SECOND), SECOND);
        original.save(previous);
        original.save(CoreState.empty());
        String backup = Files.readString(directory.resolve(StateStore.FILE_NAME + ".bak"));
        write(malformed);
        StateStore recovering = new StateStore(directory);
        assertEquals(previous, recovering.load());
        assertTrue(recovering.isReadOnly());
        assertThrows(IOException.class, () -> recovering.save(CoreState.empty()));
        assertEquals(malformed, Files.readString(recovering.path()));
        assertEquals(backup, Files.readString(directory.resolve(StateStore.FILE_NAME + ".bak")));
    }

    @Test void successfulSecondSaveRetainsExactPriorPrimaryAsBackup() throws IOException {
        StateStore store = new StateStore(directory);
        CoreState first = state(3, false, false, Set.of(FIRST), FIRST);
        CoreState second = state(4, true, true, Set.of(FIRST, SECOND), SECOND);
        store.save(first);
        String previous = Files.readString(store.path());
        store.save(second);
        assertEquals(previous, Files.readString(directory.resolve(StateStore.FILE_NAME + ".bak")));
        assertEquals(second, new StateStore(directory).load());
        try (var paths = Files.list(directory)) {
            assertFalse(paths.anyMatch(path -> path.getFileName().toString().endsWith(".tmp")));
        }
    }

    @Test void normalizationPreservesUneditedSourceInBackup() throws IOException {
        String original = """
                {"schemaVersion":1,"players":{"bad-uuid":{},"%s":{"hybridEnabled":true,
                  "allowedClasses":["historic:first","bad"],"activeClass":"historic:first"}},"addonEnabled":{}}
                """.formatted(PLAYER);
        write(original);
        StateStore store = new StateStore(directory);
        store.save(store.load());
        assertEquals(original, Files.readString(directory.resolve(StateStore.FILE_NAME + ".bak")));
        assertEquals(Set.of(FIRST), new StateStore(directory).load().players().get(PLAYER).allowedClasses());
    }

    @Test void corruptPrimaryRecoversBackupReadOnlyAndPreservesBothFiles() throws IOException {
        StateStore store = new StateStore(directory);
        CoreState first = state(2, true, true, Set.of(FIRST, SECOND), SECOND);
        store.save(first);
        store.save(state(3, false, false, Set.of(FIRST), FIRST));
        String backup = Files.readString(directory.resolve(StateStore.FILE_NAME + ".bak"));
        write("{ damaged primary");
        StateStore recovering = new StateStore(directory);
        assertEquals(first, recovering.load());
        assertTrue(recovering.isReadOnly());
        assertThrows(IOException.class, () -> recovering.save(CoreState.empty()));
        assertEquals("{ damaged primary", Files.readString(recovering.path()));
        assertEquals(backup, Files.readString(directory.resolve(StateStore.FILE_NAME + ".bak")));
    }

    @Test void corruptPrimaryAndCorruptBackupDoNotProduceEmptyWritableState() throws IOException {
        write("damaged primary");
        Files.writeString(directory.resolve(StateStore.FILE_NAME + ".bak"), "damaged backup");
        StateStore store = new StateStore(directory);
        IOException failure = assertThrows(IOException.class, store::load);
        assertEquals(1, failure.getSuppressed().length);
        assertTrue(store.isReadOnly());
        assertThrows(IOException.class, () -> store.save(CoreState.empty()));
    }

    @Test void unsupportedNewerSchemaCannotBeDowngradedThroughValidBackup() throws IOException {
        StateStore store = new StateStore(directory);
        store.save(CoreState.empty());
        store.save(CoreState.empty());
        String newer = "{\"schemaVersion\":2,\"futureData\":\"must survive\"}";
        write(newer);
        StateStore oldVersion = new StateStore(directory);
        assertThrows(IOException.class, oldVersion::load);
        assertTrue(oldVersion.isReadOnly());
        assertThrows(IOException.class, () -> oldVersion.save(CoreState.empty()));
        assertEquals(newer, Files.readString(oldVersion.path()));
    }

    @Test void missingPrimaryWithBackupRecoversReadOnly() throws IOException {
        StateStore store = new StateStore(directory);
        store.save(state(1, true, false, Set.of(FIRST), FIRST));
        store.save(CoreState.empty());
        Files.delete(store.path());
        StateStore recovering = new StateStore(directory);
        assertEquals(1, recovering.load().revision());
        assertTrue(recovering.isReadOnly());
        assertThrows(IOException.class, () -> recovering.save(CoreState.empty()));
        assertFalse(Files.exists(recovering.path()));
    }

    @Test void externalModificationCannotBeOverwrittenByLoadedStore() throws IOException {
        StateStore store = new StateStore(directory);
        store.save(CoreState.empty());
        String external = "{\"schemaVersion\":1,\"revision\":17,\"players\":{},\"addonEnabled\":{}}";
        write(external);
        assertThrows(IOException.class, () -> store.save(CoreState.empty()));
        assertTrue(store.isReadOnly());
        assertEquals(external, Files.readString(store.path()));
    }

    @Test void worldDirectoriesDoNotShareState() throws IOException {
        StateStore first = new StateStore(directory.resolve("world-one/prototype"));
        StateStore second = new StateStore(directory.resolve("world-two/prototype"));
        first.save(state(3, true, false, Set.of(FIRST), FIRST));
        assertEquals(CoreState.empty(), second.load());
        second.save(CoreState.empty());
        assertEquals(3, new StateStore(first.path().getParent()).load().revision());
    }

    @Test void storageFailureBlocksSubsequentWritesInsteadOfLeavingMutableUnsavedState() throws IOException {
        Path blockedDirectory = directory.resolve("file-not-directory");
        Files.writeString(blockedDirectory, "operator-owned file");
        StateStore store = new StateStore(blockedDirectory);
        assertEquals(CoreState.empty(), store.load());
        assertThrows(IOException.class, () -> store.save(CoreState.empty()));
        assertTrue(store.isReadOnly());
        assertThrows(IOException.class, () -> store.save(CoreState.empty()));
        assertEquals("operator-owned file", Files.readString(blockedDirectory));
    }

    @Test
    @ResourceLock("system-properties")
    void windowsSkipsOnlyDirectoryFlushButStillSavesAtomicallyAndWarnsOnce() throws IOException {
        String originalOs = System.getProperty("os.name");
        StateStore windows;
        try {
            System.setProperty("os.name", "Windows 11");
            windows = new StateStore(directory);
        } finally {
            if (originalOs == null) System.clearProperty("os.name");
            else System.setProperty("os.name", originalOs);
        }
        CoreState original = state(5, true, true, Set.of(FIRST, SECOND), SECOND);
        windows.save(original);
        windows.save(CoreState.empty());
        assertFalse(windows.isReadOnly());
        assertEquals(1, windows.diagnostics().stream().filter(message -> message.contains("Windows Java NIO")).count());
        assertEquals(CoreState.empty(), new StateStore(directory).load());
        Files.copy(directory.resolve(StateStore.FILE_NAME + ".bak"), windows.path(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        assertEquals(original, new StateStore(directory).load());
    }

    @Test void invalidUtf8IsRejectedWithoutChangingHistoricalBytes() throws IOException {
        byte[] invalid = {(byte) 0xc3, (byte) 0x28};
        Files.write(directory.resolve(StateStore.FILE_NAME), invalid);
        StateStore store = new StateStore(directory);
        assertThrows(IOException.class, store::load);
        assertTrue(store.isReadOnly());
        assertArrayEquals(invalid, Files.readAllBytes(store.path()));
    }

    @Test void revisionRequiresExactNonnegativeIntegerButKeepsValidPlayerState() throws IOException {
        write("""
                {"schemaVersion":1,"revision":2.5,"players":{"%s":{"allowedClasses":["historic:first"],
                  "activeClass":"historic:first"}},"addonEnabled":{}}
                """.formatted(PLAYER));
        CoreState state = new StateStore(directory).load();
        assertEquals(0, state.revision());
        assertEquals(FIRST, state.players().get(PLAYER).activeClass());
    }

    private CoreState state(long revision, boolean banned, boolean hybrid, Set<Id> allowed, Id active) {
        return new CoreState(Map.of(PLAYER, new PlayerState(banned, hybrid, allowed, active)), Map.of(ADDON, false), revision);
    }

    private void write(String json) throws IOException {
        Files.writeString(directory.resolve(StateStore.FILE_NAME), json, StandardCharsets.UTF_8);
    }
}
