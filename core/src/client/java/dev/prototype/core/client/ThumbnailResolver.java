package dev.prototype.core.client;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import javax.imageio.ImageIO;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;

/** Validates addon resources before rendering so missing, corrupt, or wrong-size images use Core's art. */
final class ThumbnailResolver {
    static final Identifier FALLBACK = Identifier.fromNamespaceAndPath("prototype", "textures/gui/addon_fallback.png");
    private final Map<String, Identifier> resolved = new HashMap<>();
    private ResourceManager resources;
    private Object textureGeneration;

    Identifier resolve(Minecraft client, String path) {
        Object generation = client.getTextureManager().getTexture(FALLBACK).getTexture();
        if (resources != client.getResourceManager() || textureGeneration != generation) {
            resources = client.getResourceManager();
            textureGeneration = generation;
            resolved.clear();
        }
        return resolved.computeIfAbsent(path, candidate -> validateResource(resources, candidate));
    }

    static Identifier validateResource(ResourceManager resources, String path) {
        if (path == null || path.isBlank()) return FALLBACK;
        Identifier id = Identifier.tryParse(path);
        if (id == null || !id.getPath().endsWith(".png")) return FALLBACK;
        try {
            var resource = resources.getResource(id);
            if (resource.isEmpty()) return FALLBACK;
            try (InputStream stream = resource.get().open()) {
                // Fixed 64x64 PNG contract; reject large inputs/dimensions before decoding.
                byte[] bytes = stream.readNBytes(1_048_577);
                if (bytes.length > 1_048_576 || !png64(bytes)) return FALLBACK;
                var image = ImageIO.read(new ByteArrayInputStream(bytes));
                return image != null && image.getWidth() == 64 && image.getHeight() == 64 ? id : FALLBACK;
            }
        } catch (IOException | RuntimeException invalid) {
            return FALLBACK;
        }
    }

    private static boolean png64(byte[] bytes) {
        byte[] signature = {(byte) 137, 80, 78, 71, 13, 10, 26, 10};
        if (bytes.length < 33) return false;
        for (int i = 0; i < signature.length; i++) if (bytes[i] != signature[i]) return false;
        return bytes[12] == 'I' && bytes[13] == 'H' && bytes[14] == 'D' && bytes[15] == 'R'
            && bytes[16] == 0 && bytes[17] == 0 && bytes[18] == 0 && bytes[19] == 64
            && bytes[20] == 0 && bytes[21] == 0 && bytes[22] == 0 && bytes[23] == 64;
    }
}
