package dev.prototype.demo;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

class DemoBoundaryTest {
    @Test void statusScreenLeavesBackgroundToVanillaAndKeepsNormalWidgets() throws Exception {
        try (var stream = getClass().getClassLoader().getResourceAsStream("dev/prototype/demo/client/DemoStatusScreen.class")) {
            assertNotNull(stream);
            var node = new ClassNode(); new ClassReader(stream).accept(node, 0);
            boolean superRender = false;
            for (var method : node.methods) for (var instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call) {
                    assertFalse(call.name.equals("extractBackground") || call.name.equals("extractBlurredBackground")
                            || call.name.equals("extractRenderStateWithTooltipAndSubtitles") || call.name.equals("blurBeforeThisStratum"));
                    if (call.name.equals("extractRenderState") && call.getOpcode() == Opcodes.INVOKESPECIAL) superRender = true;
                }
            }
            assertTrue(superRender, "Keep normal native widget rendering");
        }
    }

    @Test void commonAddonClassesAvoidClientReferencesAndOwnNoKeyMappings() throws Exception {
        try (var files = Files.walk(Path.of("build/classes/java"))) {
            for (var file : files.filter(p -> p.toString().endsWith(".class") && !p.toString().contains("/test/")).toList()) {
                String pool = new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1);
                assertFalse(pool.contains("registerKeyMapping"), "Addons consume Core's mappings");
                if (file.toString().contains("/main/")) assertFalse(pool.contains("net/minecraft/client/")
                        || pool.contains("fabric/api/client/"), file.toString());
            }
        }
    }
}
