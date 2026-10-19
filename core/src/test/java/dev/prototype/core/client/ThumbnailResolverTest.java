package dev.prototype.core.client;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.util.Optional;
import javax.imageio.ImageIO;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import org.junit.jupiter.api.Test;

class ThumbnailResolverTest {
    private static final String PATH = "demo:textures/gui/addon.png";

    @Test
    void correct64PixelPngIsAccepted() throws IOException {
        var id = ThumbnailResolver.validateResource(manager(resource(png(64, 64))), PATH);
        assertEquals(Identifier.parse(PATH), id);
    }

    @Test
    void productionCoreFallbackIsValid64PixelArtwork() throws IOException {
        try (var stream = getClass().getResourceAsStream("/assets/prototype/textures/gui/addon_fallback.png")) {
            assertNotNull(stream, "Core must ship its own fallback graphic");
            assertEquals(Identifier.parse(PATH), ThumbnailResolver.validateResource(manager(resource(stream.readAllBytes())), PATH));
        }
    }

    @Test
    void missingResourceUsesFallback() {
        assertEquals(ThumbnailResolver.FALLBACK, ThumbnailResolver.validateResource(manager(null), PATH));
    }

    @Test
    void malformedOrMissingResourceIdentifiersUseFallback() {
        ResourceManager manager = manager(null);
        for (String path : new String[] {null, "", " ", "Invalid Namespace:image.png", "demo:../image.png", "demo:image.jpg"}) {
            assertEquals(ThumbnailResolver.FALLBACK, ThumbnailResolver.validateResource(manager, path));
        }
    }

    @Test
    void wrongDimensionsCannotAllocateUnexpectedImages() throws IOException {
        for (int[] size : new int[][] {{32, 32}, {64, 32}, {32, 64}, {128, 128}}) {
            assertEquals(ThumbnailResolver.FALLBACK,
                ThumbnailResolver.validateResource(manager(resource(png(size[0], size[1]))), PATH));
        }
    }

    @Test
    void badSignatureAndNonImageContentUseFallback() {
        assertEquals(ThumbnailResolver.FALLBACK, ThumbnailResolver.validateResource(manager(resource(new byte[100])), PATH));
        assertEquals(ThumbnailResolver.FALLBACK, ThumbnailResolver.validateResource(manager(resource("not an image".getBytes())), PATH));
    }

    @Test
    void truncatedImageWithValidHeaderUsesFallback() throws IOException {
        byte[] image = png(64, 64);
        assertEquals(ThumbnailResolver.FALLBACK,
            ThumbnailResolver.validateResource(manager(resource(java.util.Arrays.copyOf(image, 33))), PATH));
    }

    @Test
    void oversizedEncodedContentUsesFallback() throws IOException {
        byte[] image = java.util.Arrays.copyOf(png(64, 64), 1_048_577);
        assertEquals(ThumbnailResolver.FALLBACK, ThumbnailResolver.validateResource(manager(resource(image)), PATH));
    }

    @Test
    void resourceReadFailureAndBrokenResourcePackUseFallback() {
        var broken = new Resource(null, () -> { throw new IOException("broken resource"); });
        assertEquals(ThumbnailResolver.FALLBACK, ThumbnailResolver.validateResource(manager(broken), PATH));
        ResourceManager pack = (ResourceManager) Proxy.newProxyInstance(getClass().getClassLoader(),
            new Class<?>[] {ResourceManager.class}, (proxy, method, args) -> { throw new IllegalStateException("broken pack"); });
        assertEquals(ThumbnailResolver.FALLBACK, ThumbnailResolver.validateResource(pack, PATH));
    }

    private static byte[] png(int width, int height) throws IOException {
        var output = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB), "png", output));
        return output.toByteArray();
    }

    private static Resource resource(byte[] bytes) {
        return new Resource(null, () -> new ByteArrayInputStream(bytes));
    }

    private static ResourceManager manager(Resource resource) {
        return (ResourceManager) Proxy.newProxyInstance(ThumbnailResolverTest.class.getClassLoader(),
            new Class<?>[] {ResourceManager.class}, (proxy, method, args) -> {
                if (method.getName().equals("getResource")) return Optional.ofNullable(resource);
                throw new UnsupportedOperationException(method.getName());
            });
    }
}
