package dev.prototype.core.client;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Checks compiled render call paths without initializing Minecraft or requiring an OpenGL display.
 * Vanilla's final frame wrapper owns the background. The custom extraction path (including inherited
 * methods, row helpers and lambda helpers) must not ask for another background or blur. Both tab
 * branches are inspected, so empty registries and installed addons have the same protection.
 */
class ScreenBackgroundRegressionTest {
    private static final String PREFIX = "dev/prototype/core/";
    private static final String PANEL = PREFIX + "client/AdminPanelScreen";
    private static final String ADMIN = PREFIX + "client/PrototypeAdminScreen";
    private static final String CLASSES = PREFIX + "client/ClassSelectionScreen";
    private static final String SCREEN = "net/minecraft/client/gui/screens/Screen";
    private static final String GRAPHICS = "net/minecraft/client/gui/GuiGraphicsExtractor";
    private static final String RENDER = "(L" + GRAPHICS + ";IIF)V";
    private static final String ROWS = "(L" + GRAPHICS + ";II)V";
    private static final Set<String> BACKGROUND_CALLS = Set.of(
        "extractBackground", "extractBlurredBackground", "blurBeforeThisStratum",
        "extractRenderStateWithTooltipAndSubtitles");

    @Test
    void pinnedMinecraftFrameWrapperOwnsBackgroundBeforeContentAndDeferredTooltips() throws IOException {
        ClassNode vanilla = read(SCREEN);
        MethodNode wrapper = method(vanilla, "extractRenderStateWithTooltipAndSubtitles", RENDER);
        assertNotEquals(0, wrapper.access & Opcodes.ACC_FINAL);
        List<MethodInsnNode> calls = calls(wrapper);
        assertEquals(1, calls.stream().filter(call -> call.name.equals("extractBackground")).count());
        assertEquals(1, calls.stream().filter(call -> call.name.equals("extractRenderState")).count());
        int background = indexOf(calls, SCREEN, "extractBackground");
        int content = indexOf(calls, SCREEN, "extractRenderState");
        int deferred = indexOf(calls, GRAPHICS, "extractDeferredElements");
        assertTrue(background < content && content < deferred,
            "Minecraft must extract its background, then custom content, then deferred tooltips/subtitles");
        assertTrue(calls(method(vanilla, "extractBackground", RENDER)).stream()
            .anyMatch(call -> call.name.equals("extractBlurredBackground")));
        assertTrue(calls(method(vanilla, "extractBlurredBackground", "(L" + GRAPHICS + ";)V")).stream()
            .anyMatch(call -> call.owner.equals(GRAPHICS) && call.name.equals("blurBeforeThisStratum")));
    }

    @Test
    void sharedAdminContentStillExtractsPanelRowsEmptyStateAndNativeWidgets() throws IOException {
        MethodNode render = method(read(PANEL), "extractRenderState", RENDER);
        List<MethodInsnNode> calls = calls(render);
        for (String operation : List.of("fillGradient", "outline", "fill", "text", "horizontalLine", "centeredText")) {
            assertTrue(calls.stream().anyMatch(call -> call.owner.equals(GRAPHICS) && call.name.equals(operation)),
                "Keep normal GUI drawing: " + operation);
        }
        assertTrue(calls.stream().anyMatch(call -> call.name.equals("drawRows")));
        assertTrue(calls.stream().anyMatch(call -> call.name.equals("emptyMessage")),
            "Standalone Core must still display its empty registry state");
        assertEquals(1, calls.stream().filter(call -> call.owner.equals(SCREEN)
            && call.name.equals("extractRenderState") && call.getOpcode() == Opcodes.INVOKESPECIAL).count(),
            "Keep super.extractRenderState so buttons and the search box still render");
    }

    @Test
    void playersTabRenderPathKeepsPlayerDrawingAndNeverExtractsAnotherBackground() throws Exception {
        assertTabContent("drawPlayers", "text");
    }

    @Test
    void addonsTabRenderPathKeepsThumbnailsAndNeverExtractsAnotherBackground() throws Exception {
        assertTabContent("drawAddons", "blit");
    }

    @Test
    void classSelectionInheritedRenderPathKeepsClassRowsAndNeverExtractsAnotherBackground() throws Exception {
        Map<String, ClassNode> custom = customClasses();
        Trace trace = renderTrace(custom, CLASSES);
        assertSingleBackgroundOwner(trace, CLASSES);
        assertTrue(trace.methods.contains(new MethodId(PANEL, "extractRenderState", RENDER)));
        assertTrue(trace.methods.contains(new MethodId(CLASSES, "drawRows", ROWS)));
        assertTrue(trace.calls.stream().anyMatch(call -> call.owner.equals(GRAPHICS) && call.name.equals("text")));
        assertTrue(trace.calls.stream().anyMatch(call -> call.name.equals("setTooltipForNextFrame")));
    }

    @TestFactory
    Stream<DynamicTest> everyCompiledCustomScreenIncludingFutureScreensHasOneBackgroundOwner() throws Exception {
        Map<String, ClassNode> custom = customClasses();
        List<String> screens = new ArrayList<>();
        for (ClassNode type : custom.values()) {
            if ((type.access & Opcodes.ACC_ABSTRACT) == 0 && isScreen(custom, type.name)) screens.add(type.name);
        }
        assertTrue(screens.containsAll(List.of(ADMIN, CLASSES)), "Discover both concrete GUI screens");
        return screens.stream().sorted().map(screen -> DynamicTest.dynamicTest(screen.replace('/', '.'), () ->
            assertSingleBackgroundOwner(renderTrace(custom, screen), screen)));
    }

    @Test
    void guardDetectsOriginalDuplicateBackgroundThroughBothInheritedScreens() throws Exception {
        Map<String, ClassNode> custom = customClasses();
        method(custom.get(PANEL), "extractRenderState", RENDER).instructions.insert(
            new MethodInsnNode(Opcodes.INVOKEVIRTUAL, PANEL, "extractBackground", RENDER, false));
        for (String screen : List.of(ADMIN, CLASSES)) {
            assertFalse(forbiddenCalls(renderTrace(custom, screen)).isEmpty(),
                "The original AdminPanelScreen regression must be caught through " + screen);
        }
    }

    @Test
    void guardAlsoDetectsBlurHiddenInRowHelperAndLowLevelBlur() throws Exception {
        for (String helper : List.of("drawPlayers", "drawAddons")) {
            for (String operation : List.of("extractBlurredBackground", "blurBeforeThisStratum")) {
                Map<String, ClassNode> custom = customClasses();
                String owner = operation.equals("extractBlurredBackground") ? SCREEN : GRAPHICS;
                String descriptor = operation.equals("extractBlurredBackground") ? "(L" + GRAPHICS + ";)V" : "()V";
                method(custom.get(ADMIN), helper, ROWS).instructions.insert(
                    new MethodInsnNode(Opcodes.INVOKEVIRTUAL, owner, operation, descriptor, false));
                assertFalse(forbiddenCalls(renderTrace(custom, ADMIN)).isEmpty(),
                    "Catch " + operation + " inside the " + helper + " render helper");
            }
        }
    }

    private static void assertTabContent(String helper, String drawing) throws Exception {
        Map<String, ClassNode> custom = customClasses();
        Trace trace = renderTrace(custom, ADMIN);
        assertSingleBackgroundOwner(trace, "PrototypeAdminScreen." + helper);
        assertTrue(trace.methods.contains(new MethodId(PANEL, "extractRenderState", RENDER)));
        assertTrue(trace.methods.contains(new MethodId(ADMIN, "drawRows", ROWS)));
        assertTrue(trace.methods.contains(new MethodId(ADMIN, helper, ROWS)),
            "Shared extraction must still reach this tab's actual renderer");
        assertTrue(calls(method(custom.get(ADMIN), helper, ROWS)).stream()
            .anyMatch(call -> call.owner.equals(GRAPHICS) && call.name.equals(drawing)),
            "Keep tab content drawing");
        assertTrue(calls(method(custom.get(ADMIN), helper, ROWS)).stream()
            .anyMatch(call -> call.name.equals("setTooltipForNextFrame")), "Keep tab tooltips");
    }

    private static void assertSingleBackgroundOwner(Trace trace, String screen) {
        assertEquals(List.of(), forbiddenCalls(trace),
            screen + " requests a second background/blur after the vanilla frame wrapper");
    }

    private static List<String> forbiddenCalls(Trace trace) {
        return trace.calls.stream().filter(call -> BACKGROUND_CALLS.contains(call.name))
            .map(call -> call.owner.replace('/', '.') + "." + call.name + call.desc).toList();
    }

    private static Trace renderTrace(Map<String, ClassNode> custom, String receiver) {
        Trace trace = new Trace();
        visit(custom, receiver, resolve(custom, receiver, "extractRenderState", RENDER), trace);
        return trace;
    }

    private static void visit(Map<String, ClassNode> custom, String receiver, MethodId id, Trace trace) {
        if (id == null || !custom.containsKey(id.owner) || !trace.methods.add(id)) return;
        MethodNode method = method(custom.get(id.owner), id.name, id.descriptor);
        for (var instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call) {
                trace.calls.add(call);
                if (custom.containsKey(call.owner)) {
                    String owner = call.owner;
                    if (call.getOpcode() == Opcodes.INVOKEVIRTUAL && isCustomSuperclass(custom, receiver, owner))
                        owner = receiver;
                    visit(custom, receiver, resolve(custom, owner, call.name, call.desc), trace);
                }
            } else if (instruction instanceof InvokeDynamicInsnNode dynamic) {
                for (Object argument : dynamic.bsmArgs) {
                    if (argument instanceof Handle handle && handle.getTag() >= Opcodes.H_INVOKEVIRTUAL) {
                        // Include method references too: this::extractBackground must not evade the guard.
                        trace.calls.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, handle.getOwner(),
                            handle.getName(), handle.getDesc(), handle.isInterface()));
                        if (custom.containsKey(handle.getOwner())) {
                            String owner = handle.getOwner();
                            if (handle.getTag() == Opcodes.H_INVOKEVIRTUAL && isCustomSuperclass(custom, receiver, owner))
                                owner = receiver;
                            visit(custom, receiver, resolve(custom, owner, handle.getName(), handle.getDesc()), trace);
                        }
                    }
                }
            }
        }
    }

    private static MethodId resolve(Map<String, ClassNode> custom, String owner, String name, String descriptor) {
        while (custom.containsKey(owner)) {
            ClassNode type = custom.get(owner);
            if (type.methods.stream().anyMatch(method -> method.name.equals(name) && method.desc.equals(descriptor)))
                return new MethodId(owner, name, descriptor);
            owner = type.superName;
        }
        return null;
    }

    private static boolean isCustomSuperclass(Map<String, ClassNode> custom, String receiver, String owner) {
        while (custom.containsKey(receiver)) {
            if (receiver.equals(owner)) return true;
            receiver = custom.get(receiver).superName;
        }
        return false;
    }

    private static boolean isScreen(Map<String, ClassNode> custom, String name) throws IOException {
        while (name != null) {
            if (name.equals(SCREEN)) return true;
            ClassNode type = custom.containsKey(name) ? custom.get(name) : read(name);
            name = type.superName;
        }
        return false;
    }

    private static Map<String, ClassNode> customClasses() throws IOException, URISyntaxException {
        Map<String, ClassNode> result = new LinkedHashMap<>();
        var anchor = ScreenBackgroundRegressionTest.class.getClassLoader().getResource(PANEL + ".class");
        assertNotNull(anchor, "Compiled client classes must be on the test classpath");
        if (anchor.getProtocol().equals("file")) {
            // The anchor is client/AdminPanelScreen.class; scan every package under dev/prototype/core.
            Path root = Path.of(anchor.toURI()).getParent().getParent();
            try (var files = Files.walk(root)) {
                for (Path file : files.filter(path -> path.toString().endsWith(".class")).toList()) {
                    try (var input = Files.newInputStream(file)) { add(result, input); }
                }
            }
        } else if (anchor.getProtocol().equals("jar")) {
            var connection = (JarURLConnection) anchor.openConnection();
            connection.setUseCaches(false);
            try (var jar = connection.getJarFile()) {
                for (var entry : jar.stream().filter(entry -> entry.getName().startsWith(PREFIX)
                    && entry.getName().endsWith(".class")).toList()) {
                    try (var input = jar.getInputStream(entry)) { add(result, input); }
                }
            }
        } else {
            fail("Unsupported compiled client class location: " + anchor.getProtocol());
        }
        return result;
    }

    private static void add(Map<String, ClassNode> result, InputStream input) throws IOException {
        ClassNode type = new ClassNode();
        new ClassReader(input).accept(type, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        result.put(type.name, type);
    }

    private static ClassNode read(String name) throws IOException {
        try (var input = ScreenBackgroundRegressionTest.class.getClassLoader().getResourceAsStream(name + ".class")) {
            assertNotNull(input, "Missing compiled class: " + name);
            ClassNode type = new ClassNode();
            new ClassReader(input).accept(type, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return type;
        }
    }

    private static MethodNode method(ClassNode type, String name, String descriptor) {
        return type.methods.stream().filter(method -> method.name.equals(name) && method.desc.equals(descriptor))
            .findFirst().orElseThrow(() -> new AssertionError("Missing method: " + type.name + "." + name + descriptor));
    }

    private static List<MethodInsnNode> calls(MethodNode method) {
        List<MethodInsnNode> calls = new ArrayList<>();
        for (var instruction : method.instructions) if (instruction instanceof MethodInsnNode call) calls.add(call);
        return calls;
    }

    private static int indexOf(List<MethodInsnNode> calls, String owner, String name) {
        for (int index = 0; index < calls.size(); index++) {
            if (calls.get(index).owner.equals(owner) && calls.get(index).name.equals(name)) return index;
        }
        throw new AssertionError("Missing call: " + owner + "." + name);
    }

    private record MethodId(String owner, String name, String descriptor) {}
    private static final class Trace {
        final Set<MethodId> methods = new HashSet<>();
        final List<MethodInsnNode> calls = new ArrayList<>();
    }
}
