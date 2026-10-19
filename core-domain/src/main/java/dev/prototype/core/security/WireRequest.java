package dev.prototype.core.security;

import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.IOException;
import java.io.StringReader;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** Internal, strictly bounded client request. No client assertion grants authorization. */
public record WireRequest(Action action, long revision, UUID player, String id, Boolean enabled, Mode mode) {
    public static final int MAX_LENGTH = 4096;
    private static final Pattern IDENTIFIER = Pattern.compile("[a-z0-9_.-]+:[a-z0-9/._-]+");
    private static final Pattern REVISION = Pattern.compile("0|[1-9][0-9]{0,18}");
    private static final Set<String> FIELDS = Set.of("action", "revision", "player", "id", "enabled", "mode");

    public enum Mode { ADMIN, PLAYER }

    public enum Action {
        REFRESH(false), CLOSE(false), SET_CLASS(true), ADD_CLASS(true), REMOVE_CLASS(true),
        CLEAR_CLASSES(true), SET_HYBRID(true), SET_BAN(true), SET_ADDON(true), SWITCH(false), CONVERT(false);
        private final boolean admin;
        Action(boolean admin) { this.admin = admin; }
        public boolean adminMutation() { return admin; }
    }

    public static WireRequest parse(String json) {
        if (json == null || json.isBlank() || json.length() > MAX_LENGTH) throw invalid();
        Map<String, String> values = new HashMap<>();
        Boolean enabled = null;
        Set<String> encountered = new HashSet<>();
        try (JsonReader reader = new JsonReader(new StringReader(json))) {
            reader.setStrictness(Strictness.STRICT);
            reader.beginObject();
            while (reader.hasNext()) {
                String name = reader.nextName();
                if (!FIELDS.contains(name) || !encountered.add(name)) throw invalid();
                if (name.equals("enabled")) {
                    if (reader.peek() != JsonToken.BOOLEAN) throw invalid();
                    enabled = reader.nextBoolean();
                } else {
                    JsonToken expected = name.equals("revision") ? JsonToken.NUMBER : JsonToken.STRING;
                    if (reader.peek() != expected) throw invalid();
                    String value = reader.nextString();
                    if (value.length() > 256) throw invalid();
                    values.put(name, value);
                }
            }
            reader.endObject();
            if (reader.peek() != JsonToken.END_DOCUMENT) throw invalid();
        } catch (IOException | IllegalStateException e) {
            throw invalid();
        }
        final Action action;
        try { action = Action.valueOf(values.getOrDefault("action", "").toUpperCase(java.util.Locale.ROOT)); }
        catch (IllegalArgumentException e) { throw invalid(); }
        // Only the documented lowercase spellings are accepted.
        if (!action.name().toLowerCase(java.util.Locale.ROOT).equals(values.get("action"))) throw invalid();
        long revision = -1;
        if (values.containsKey("revision")) {
            String raw = values.get("revision");
            if (!REVISION.matcher(raw).matches()) throw invalid();
            try { revision = Long.parseLong(raw); } catch (NumberFormatException e) { throw invalid(); }
        }
        UUID player = null;
        if (values.containsKey("player")) {
            String raw = values.get("player");
            try { player = UUID.fromString(raw); } catch (IllegalArgumentException e) { throw invalid(); }
            if (!player.toString().equals(raw)) throw invalid();
        }
        String id = values.get("id");
        if (id != null && !IDENTIFIER.matcher(id).matches()) throw invalid();
        Mode mode = null;
        if (values.containsKey("mode")) {
            try { mode = Mode.valueOf(values.get("mode")); } catch (IllegalArgumentException e) { throw invalid(); }
        }
        boolean target = switch (action) {
            case SET_CLASS, ADD_CLASS, REMOVE_CLASS, CLEAR_CLASSES, SET_HYBRID, SET_BAN -> true;
            default -> false;
        };
        boolean identifier = switch (action) {
            case SET_CLASS, ADD_CLASS, REMOVE_CLASS, SET_ADDON, SWITCH -> true;
            default -> false;
        };
        boolean flag = switch (action) { case SET_HYBRID, SET_BAN, SET_ADDON -> true; default -> false; };
        if (target != (player != null) || identifier != (id != null) || flag != (enabled != null)) throw invalid();
        if (action.adminMutation() && revision < 0) throw invalid();
        if (mode != null && action != Action.REFRESH && action != Action.CLOSE) throw invalid();
        return new WireRequest(action, revision, player, id, enabled, mode);
    }

    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid Prototype request."); }
}
