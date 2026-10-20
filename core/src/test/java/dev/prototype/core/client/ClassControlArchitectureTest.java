package dev.prototype.core.client;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;

class ClassControlArchitectureTest {
    @Test void compiledClientRegistersExactlyThreeNeutralMappingsAndNoClassRegistryCache() throws Exception {
        try (var stream = getClass().getClassLoader().getResourceAsStream("dev/prototype/core/client/ClassControlKeys.class")) {
            assertNotNull(stream);
            byte[] bytes = stream.readAllBytes();
            var node = new ClassNode(); new ClassReader(bytes).accept(node, 0);
            int registrations = 0;
            var keys = new ArrayList<String>();
            for (var method : node.methods) for (var instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call) {
                    if (call.name.equals("registerKeyMapping")) registrations++;
                    assertFalse(call.owner.contains("PrototypeApi") || call.owner.contains("ClassControlDispatcher"));
                }
                if (instruction instanceof LdcInsnNode value && value.cst instanceof String text && text.startsWith("key.prototype.")) keys.add(text);
            }
            assertEquals(3, registrations);
            assertEquals(List.of("key.prototype.ability_1", "key.prototype.ability_2", "key.prototype.status"), keys);
        }
    }

    @Test void everyCompiledCommonClassAvoidsClientOnlyReferences() throws Exception {
        for (var root : List.of(Path.of("build/classes/java/main"), Path.of("../core-domain/build/classes/java/main"))) {
            try (var paths = Files.walk(root)) {
                for (var path : paths.filter(p -> p.toString().endsWith(".class")).toList()) {
                    String pool = new String(Files.readAllBytes(path), StandardCharsets.ISO_8859_1);
                    assertFalse(pool.contains("net/minecraft/client/") || pool.contains("fabric/api/client/"), path.toString());
                }
            }
        }
    }
}
