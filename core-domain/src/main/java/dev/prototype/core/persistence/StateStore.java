package dev.prototype.core.persistence;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import dev.prototype.core.api.CoreState;
import dev.prototype.core.api.Id;
import dev.prototype.core.api.PlayerState;

import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Core-owned, versioned world state. This store never reads addon progression.
 * Calls are serialized; external edits are detected and require a fresh store.
 */
public final class StateStore {
    public static final int SCHEMA_VERSION = 1;
    public static final String FILE_NAME = "core-state.json";
    private static final long MAX_BYTES = 16L * 1024 * 1024;
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final String WINDOWS_FLUSH_DIAGNOSTIC = "Windows Java NIO does not support directory metadata fsync; forced files and atomic replacement remain required.";

    private final Path directory;
    private final Path file;
    private final Path backup;
    private final boolean directoryFlushSupported;
    private final List<String> diagnostics = new ArrayList<>();
    private boolean initialized;
    private boolean readOnly;
    private byte[] expectedFingerprint;

    /** Supply the Core-specific directory beneath the current world directory. */
    public StateStore(Path directory) {
        this.directory = Objects.requireNonNull(directory, "directory").toAbsolutePath().normalize();
        this.file = this.directory.resolve(FILE_NAME);
        this.backup = this.directory.resolve(FILE_NAME + ".bak");
        this.directoryFlushSupported = !System.getProperty("os.name", "").startsWith("Windows");
    }

    public Path path() {
        return file;
    }

    public synchronized boolean isReadOnly() {
        return readOnly;
    }

    public synchronized List<String> diagnostics() {
        return List.copyOf(diagnostics);
    }

    /**
     * Loads valid records independently. Unknown syntactically valid IDs survive.
     * A corrupt primary may be read from backup, but remains read-only so server
     * shutdown cannot replace historical data with an incomplete recovery.
     */
    public synchronized CoreState load() throws IOException {
        diagnostics.clear();
        if (!directoryFlushSupported) diagnostics.add(WINDOWS_FLUSH_DIAGNOSTIC);
        initialized = true;
        readOnly = false;
        expectedFingerprint = null;
        if (!Files.exists(file)) {
            if (!Files.exists(backup)) {
                return CoreState.empty();
            }
            readOnly = true;
            diagnostics.add("Primary state is missing; recovering the backup read-only. Restore a reviewed copy before saving.");
            return parse(readBytes(backup));
        }

        byte[] primary;
        try {
            primary = readBytes(file);
            expectedFingerprint = fingerprint(primary);
        } catch (IOException failure) {
            readOnly = true;
            diagnostics.add("Primary state cannot be read safely; saving is blocked: " + failure.getMessage());
            throw failure;
        }
        try {
            return parse(primary);
        } catch (UnsupportedSchemaException newer) {
            readOnly = true;
            diagnostics.add(newer.getMessage() + "; saving is blocked to protect this file.");
            throw newer;
        } catch (IOException malformed) {
            readOnly = true;
            diagnostics.add("Primary state is corrupt; saving is blocked and the original file is preserved.");
            if (Files.exists(backup)) {
                try {
                    CoreState recovered = parse(readBytes(backup));
                    diagnostics.add("Loaded the last backup read-only. Restore a reviewed copy before saving.");
                    return recovered;
                } catch (IOException backupFailure) {
                    malformed.addSuppressed(backupFailure);
                }
            }
            throw malformed;
        }
    }

    /**
     * Uses forced temporary files and atomic replacement, retaining the previous
     * valid primary verbatim as a backup. Filesystems without atomic rename fail
     * closed, leaving the primary intact. Always call load before normal use.
     */
    public synchronized void save(CoreState state) throws IOException {
        try {
            saveWritable(state);
        } catch (IOException failure) {
            readOnly = true;
            diagnostics.add("Core state save failed; further writes are blocked: " + failure.getMessage());
            throw failure;
        }
    }

    private void saveWritable(CoreState state) throws IOException {
        Objects.requireNonNull(state, "state");
        if (!initialized) {
            load();
        }
        if (readOnly) {
            throw new IOException("Core state store is read-only; historical state must be reviewed/restored before saving");
        }
        Files.createDirectories(directory);
        byte[] current = Files.exists(file) ? readBytes(file) : null;
        byte[] actual = current == null ? null : fingerprint(current);
        if (!Arrays.equals(expectedFingerprint, actual)) {
            readOnly = true;
            diagnostics.add("Primary state changed outside this store; saving is blocked to preserve external changes.");
            throw new IOException("Core state changed externally; reload a reviewed state before saving");
        }

        byte[] bytes = (JSON.toJson(serialize(state)) + "\n").getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES) {
            throw new IOException("Core state exceeds the supported file size");
        }
        Path temporary = null;
        Path backupTemporary = null;
        try {
            temporary = forcedTemporary(bytes);
            if (current != null) {
                backupTemporary = forcedTemporary(current);
                Files.move(backupTemporary, backup, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                backupTemporary = null;
                forceDirectory();
            }
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            temporary = null;
            expectedFingerprint = fingerprint(bytes);
            forceDirectory();
        } finally {
            if (temporary != null) Files.deleteIfExists(temporary);
            if (backupTemporary != null) Files.deleteIfExists(backupTemporary);
        }
    }

    private Path forcedTemporary(byte[] bytes) throws IOException {
        Path temporary = Files.createTempFile(directory, ".core-state-", ".tmp");
        try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) channel.write(buffer);
            channel.force(true);
            return temporary;
        } catch (IOException failure) {
            Files.deleteIfExists(temporary);
            throw failure;
        }
    }

    private void forceDirectory() throws IOException {
        if (!directoryFlushSupported) return;
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        }
    }

    private byte[] readBytes(Path source) throws IOException {
        if (!Files.isRegularFile(source) || Files.size(source) > MAX_BYTES) {
            throw new IOException("State file is not a regular file or exceeds 16 MiB");
        }
        byte[] bytes = Files.readAllBytes(source);
        if (bytes.length > MAX_BYTES) throw new IOException("State file exceeds 16 MiB");
        return bytes;
    }

    private CoreState parse(byte[] bytes) throws IOException {
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException malformed) {
            throw new IOException("Core state is not valid UTF-8", malformed);
        }
        JsonElement element;
        try (JsonReader reader = new JsonReader(new StringReader(text))) {
            reader.setStrictness(Strictness.STRICT);
            element = readElement(reader, 0);
            if (reader.peek() != JsonToken.END_DOCUMENT) throw new IOException("Trailing data after Core state");
        } catch (RuntimeException malformed) {
            throw new IOException("Invalid Core state JSON", malformed);
        }
        if (!element.isJsonObject()) throw new IOException("Core state root must be an object");
        JsonObject root = element.getAsJsonObject();
        long schema = requiredInteger(root.get("schemaVersion"), "schemaVersion");
        if (schema != SCHEMA_VERSION) {
            throw new UnsupportedSchemaException("Unsupported Core state schema " + schema + " (supported: " + SCHEMA_VERSION + ")");
        }
        long revision = optionalRevision(root.get("revision"));
        Map<UUID, PlayerState> players = new LinkedHashMap<>();
        JsonElement playerElement = root.get("players");
        if (playerElement != null && playerElement.isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : playerElement.getAsJsonObject().entrySet()) {
                UUID uuid;
                try {
                    uuid = UUID.fromString(entry.getKey());
                    if (!uuid.toString().equalsIgnoreCase(entry.getKey())) throw new IllegalArgumentException("Noncanonical UUID");
                } catch (IllegalArgumentException malformed) {
                    diagnostics.add("Skipped malformed player UUID: " + entry.getKey());
                    continue;
                }
                if (!entry.getValue().isJsonObject()) {
                    diagnostics.add("Skipped malformed player record for " + uuid);
                    continue;
                }
                if (players.containsKey(uuid)) throw new IOException("Duplicate equivalent player UUID: " + uuid);
                players.put(uuid, parsePlayer(uuid, entry.getValue().getAsJsonObject()));
            }
        } else {
            throw new IOException("Invalid players collection; historical authorization cannot be recovered safely");
        }

        Map<Id, Boolean> addonEnabled = new LinkedHashMap<>();
        JsonElement addonElement = root.get("addonEnabled");
        if (addonElement != null && addonElement.isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : addonElement.getAsJsonObject().entrySet()) {
                Id id = id(entry.getKey(), "addon");
                if (id != null) addonEnabled.put(id, bool(entry.getValue(), false, "addon " + id + " enabled"));
            }
        } else {
            throw new IOException("Invalid addonEnabled collection; runtime kill switches cannot be recovered safely");
        }
        return new CoreState(players, addonEnabled, revision);
    }

    private PlayerState parsePlayer(UUID uuid, JsonObject player) {
        if (!player.has("classBanned")) diagnostics.add("Missing classBanned for " + uuid + "; using safe default true.");
        boolean banned = bool(player.get("classBanned"), true, "player " + uuid + " classBanned");
        boolean hybrid = bool(player.get("hybridEnabled"), false, "player " + uuid + " hybridEnabled");
        Set<Id> allowed = new TreeSet<>();
        JsonElement allowedElement = player.get("allowedClasses");
        if (allowedElement != null && allowedElement.isJsonArray()) {
            for (JsonElement value : allowedElement.getAsJsonArray()) {
                Id id = id(value, "allowed class for " + uuid);
                if (id != null) allowed.add(id);
            }
        } else if (allowedElement != null) {
            diagnostics.add("Invalid allowedClasses for " + uuid + "; authorization is empty.");
        }
        JsonElement activeElement = player.get("activeClass");
        Id active = activeElement == null || activeElement.isJsonNull() ? null : id(activeElement, "active class for " + uuid);
        if (active != null && !allowed.contains(active)) {
            diagnostics.add("Active class for " + uuid + " was not authorized; cleared active class.");
            active = null;
        }
        if (!hybrid && allowed.size() > 1) {
            Id retained = active == null ? allowed.iterator().next() : active;
            diagnostics.add("Non-hybrid player " + uuid + " had multiple classes; retained " + retained + ".");
            allowed = Set.of(retained);
        }
        return new PlayerState(banned, hybrid, allowed, active);
    }

    private long optionalRevision(JsonElement value) {
        if (value == null) return 0;
        try {
            long revision = requiredInteger(value, "revision");
            if (revision < 0) throw new IOException("Negative revision");
            return revision;
        } catch (IOException malformed) {
            diagnostics.add("Invalid revision; reset revision to zero.");
            return 0;
        }
    }

    private long requiredInteger(JsonElement value, String field) throws IOException {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new IOException("Missing or invalid " + field);
        }
        try {
            return value.getAsBigDecimal().longValueExact();
        } catch (ArithmeticException | NumberFormatException malformed) {
            throw new IOException("Invalid integer " + field, malformed);
        }
    }

    private boolean bool(JsonElement value, boolean fallback, String field) {
        if (value == null) return fallback;
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean()) return value.getAsBoolean();
        diagnostics.add("Invalid boolean " + field + "; using safe default " + fallback + ".");
        return fallback;
    }

    private Id id(JsonElement value, String field) {
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            diagnostics.add("Skipped non-string " + field + " identifier.");
            return null;
        }
        return id(value.getAsString(), field);
    }

    private Id id(String value, String field) {
        try {
            return Id.parse(value);
        } catch (IllegalArgumentException malformed) {
            diagnostics.add("Skipped malformed " + field + " identifier: " + value);
            return null;
        }
    }

    private JsonObject serialize(CoreState state) {
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", SCHEMA_VERSION);
        root.addProperty("revision", state.revision());
        JsonObject players = new JsonObject();
        state.players().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            PlayerState player = entry.getValue();
            JsonObject record = new JsonObject();
            record.addProperty("classBanned", player.classBanned());
            record.addProperty("hybridEnabled", player.hybridEnabled());
            JsonArray allowed = new JsonArray();
            player.allowedClasses().stream().sorted().forEach(id -> allowed.add(id.toString()));
            record.add("allowedClasses", allowed);
            record.add("activeClass", player.activeClass() == null ? JsonNull.INSTANCE : new JsonPrimitive(player.activeClass().toString()));
            players.add(entry.getKey().toString(), record);
        });
        root.add("players", players);
        JsonObject addons = new JsonObject();
        state.addonEnabled().entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> addons.addProperty(entry.getKey().toString(), entry.getValue()));
        root.add("addonEnabled", addons);
        return root;
    }

    /** Strict tree reading also rejects duplicate keys, rather than silently keeping the last value. */
    private JsonElement readElement(JsonReader reader, int depth) throws IOException {
        if (depth > 64) throw new IOException("Core state JSON nesting exceeds 64 levels");
        return switch (reader.peek()) {
            case BEGIN_OBJECT -> {
                reader.beginObject();
                JsonObject object = new JsonObject();
                while (reader.hasNext()) {
                    String key = reader.nextName();
                    if (object.has(key)) throw new IOException("Duplicate JSON key: " + key);
                    object.add(key, readElement(reader, depth + 1));
                }
                reader.endObject();
                yield object;
            }
            case BEGIN_ARRAY -> {
                reader.beginArray();
                JsonArray array = new JsonArray();
                while (reader.hasNext()) array.add(readElement(reader, depth + 1));
                reader.endArray();
                yield array;
            }
            case STRING -> new JsonPrimitive(reader.nextString());
            case NUMBER -> new JsonPrimitive(new BigDecimal(reader.nextString()));
            case BOOLEAN -> new JsonPrimitive(reader.nextBoolean());
            case NULL -> {
                reader.nextNull();
                yield JsonNull.INSTANCE;
            }
            default -> throw new IOException("Expected JSON value");
        };
    }

    private byte[] fingerprint(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("Java runtime lacks SHA-256", impossible);
        }
    }

    private static final class UnsupportedSchemaException extends IOException {
        private UnsupportedSchemaException(String message) {
            super(message);
        }
    }
}
