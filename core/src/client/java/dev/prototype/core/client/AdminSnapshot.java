package dev.prototype.core.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Client-only presentation data. Server decisions are never derived from this model. */
record AdminSnapshot(String mode, boolean open, long revision, String message,
                     List<Player> players, List<Addon> addons) {
    record Player(UUID uuid, String name, boolean online, boolean classBanned,
                  boolean hybridEnabled, List<String> allowedClasses, String activeClass) {}
    record ClassInfo(String id, String displayName, boolean enabled) {}
    record Addon(String id, String displayName, String version, String thumbnail,
                 boolean enabled, List<ClassInfo> classes) {}

    static AdminSnapshot parse(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        String mode = string(root, "mode", "CLOSE");
        long revision = root.has("revision") ? root.get("revision").getAsLong() : 0;
        List<Player> players = new ArrayList<>();
        for (JsonElement entry : array(root, "players")) {
            JsonObject player = entry.getAsJsonObject();
            List<String> allowed = new ArrayList<>();
            for (JsonElement id : array(player, "allowedClasses")) allowed.add(id.getAsString());
            players.add(new Player(UUID.fromString(string(player, "uuid", "")),
                string(player, "name", "Unknown player"), bool(player, "online"),
                bool(player, "classBanned"), bool(player, "hybridEnabled"),
                List.copyOf(allowed), string(player, "activeClass", "")));
        }
        List<Addon> addons = new ArrayList<>();
        for (JsonElement entry : array(root, "addons")) {
            JsonObject addon = entry.getAsJsonObject();
            boolean enabled = bool(addon, "enabled");
            List<ClassInfo> classes = new ArrayList<>();
            for (JsonElement item : array(addon, "classes")) {
                JsonObject type = item.getAsJsonObject();
                String id = string(type, "id", "");
                classes.add(new ClassInfo(id, string(type, "displayName", id), enabled));
            }
            String id = string(addon, "id", "");
            addons.add(new Addon(id, string(addon, "displayName", id),
                string(addon, "version", ""), string(addon, "thumbnail", ""),
                enabled, List.copyOf(classes)));
        }
        return new AdminSnapshot(mode, bool(root, "open"), revision, string(root, "message", ""),
            List.copyOf(players), List.copyOf(addons));
    }

    Map<String, ClassInfo> classes() {
        Map<String, ClassInfo> classes = new LinkedHashMap<>();
        for (Addon addon : addons) for (ClassInfo type : addon.classes) classes.put(type.id, type);
        return classes;
    }

    String className(String id) {
        if (id == null || id.isBlank()) return "None";
        ClassInfo type = classes().get(id);
        return type == null ? id : type.displayName;
    }

    private static String string(JsonObject object, String name, String fallback) {
        JsonElement value = object.get(name);
        return value == null || value.isJsonNull() ? fallback : value.getAsString();
    }

    private static boolean bool(JsonObject object, String name) {
        JsonElement value = object.get(name);
        return value != null && !value.isJsonNull() && value.getAsBoolean();
    }

    private static JsonArray array(JsonObject object, String name) {
        JsonElement value = object.get(name);
        return value == null || value.isJsonNull() ? new JsonArray() : value.getAsJsonArray();
    }
}
