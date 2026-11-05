package com.dishanhai.gt_shanhai.client.loading;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

import javax.imageio.ImageIO;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;

/** 山海：用實際依賴 jar 的 class bytes 核對注入入口，不初始化 Minecraft 註冊表。 */
class ShanhaiLoadingIntegrationTest {
    private static final List<String> MIXINS = List.of("ShanhaiLoadingOverlayMixin", "ShanhaiLoadingWorldMixin",
            "ShanhaiLoadingWaitMixin", "ShanhaiLoadingRecipeMixin", "ShanhaiLoadingSyncMixin", "ShanhaiLoadingConnectMixin", "ShanhaiLoadingReloadProfilerMixin",
            "ShanhaiLoadingJeiRecipesMixin", "ShanhaiLoadingJeiIndexMixin", "ShanhaiLoadingJeiPluginsMixin",
            "ShanhaiLoadingJeiSearchMixin", "ShanhaiLoadingJeiSearchStorageMixin", "ShanhaiLoadingDataPackMixin");

    @Test void everyLoadingInjectionResolvesToExactlyOneActualTargetMethod() throws Exception {
        int checked = 0;
        for (String name : MIXINS) {
            ClassNode mixin = readClass("com/dishanhai/gt_shanhai/mixin/" + name);
            var annotation = mixin.invisibleAnnotations.stream().filter(a -> a.desc.equals("Lorg/spongepowered/asm/mixin/Mixin;")).findFirst();
            if (annotation.isEmpty() && mixin.visibleAnnotations != null) {
                annotation = mixin.visibleAnnotations.stream().filter(a -> a.desc.equals("Lorg/spongepowered/asm/mixin/Mixin;")).findFirst();
            }
            assertTrue(annotation.isPresent(), name);
            ClassNode target = targetClass(annotation.get());
            for (var method : mixin.methods) {
                if (method.visibleAnnotations == null) continue;
                for (var inject : method.visibleAnnotations) {
                    if (!List.of("Lorg/spongepowered/asm/mixin/injection/Inject;",
                            "Lcom/llamalad7/mixinextras/injector/wrapmethod/WrapMethod;",
                            "Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;").contains(inject.desc)) continue;
                    checked++;
                    @SuppressWarnings("unchecked") List<String> selectors = (List<String>) value(inject, "method");
                    long matches = target.methods.stream().filter(m -> selectors.stream().anyMatch(s ->
                            s.equals(m.name) || s.equals(m.name + m.desc))).count();
                    assertEquals(1, matches, name + ":" + method.name + " -> " + selectors);
                    assertEquals(Boolean.FALSE, value(inject, "remap"), name);
                }
            }
        }
        assertEquals(25, checked);
    }

    @Test void loadingMixinsAreRegisteredOnlyForClients() throws Exception {
        var config = JsonParser.parseString(Files.readString(Path.of("src/main/resources/gt_shanhai.minecraft.mixin.json"))).getAsJsonObject();
        String clients = config.getAsJsonArray("client").toString();
        String common = config.getAsJsonArray("mixins").toString();
        var jeiConfig = JsonParser.parseString(Files.readString(Path.of("src/main/resources/gt_shanhai.jei.mixin.json"))).getAsJsonObject();
        var gtceuConfig = JsonParser.parseString(Files.readString(Path.of("src/main/resources/gt_shanhai.gtceu.mixin.json"))).getAsJsonObject();
        for (String name : MIXINS) {
            String expected = name.equals("ShanhaiLoadingDataPackMixin") ? gtceuConfig.getAsJsonArray("client").toString()
                    : name.startsWith("ShanhaiLoadingJei") ? jeiConfig.getAsJsonArray("client").toString() : clients;
            assertTrue(expected.contains('"' + name + '"'), name);
            assertFalse(common.contains('"' + name + '"'), name);
            assertFalse(gtceuConfig.getAsJsonArray("mixins").toString().contains('"' + name + '"'), name);
        }
        assertTrue(clients.contains("ShanhaiLoadingListenerAccessor"));
    }

    @Test void wrappedInvocationsAndAlternativeGroupsMatchRealInstructions() throws Exception {
        var groups = new java.util.HashMap<String, Integer>();
        for (String name : MIXINS) {
            ClassNode mixin = readClass("com/dishanhai/gt_shanhai/mixin/" + name);
            var mixinAnnotation = mixin.invisibleAnnotations.stream()
                    .filter(a -> a.desc.equals("Lorg/spongepowered/asm/mixin/Mixin;")).findFirst().orElseThrow();
            ClassNode target = targetClass(mixinAnnotation);
            for (var method : mixin.methods) {
                if (method.visibleAnnotations == null) continue;
                var operation = method.visibleAnnotations.stream().filter(a -> a.desc.endsWith("/WrapOperation;")).findFirst();
                if (operation.isEmpty()) continue;
                @SuppressWarnings("unchecked") var selectors = (List<String>) value(operation.get(), "method");
                Object locations = value(operation.get(), "at");
                var at = locations instanceof AnnotationNode node ? node : (AnnotationNode) ((List<?>) locations).get(0);
                String invocation = (String) value(at, "target");
                int matches = 0;
                for (var actual : target.methods) {
                    if (selectors.stream().noneMatch(s -> s.equals(actual.name) || s.equals(actual.name + actual.desc))) continue;
                    for (var instruction : actual.instructions) {
                        if (instruction instanceof MethodInsnNode call && invocation.equals("L" + call.owner + ";" + call.name + call.desc)) matches++;
                    }
                }
                var annotations = new ArrayList<AnnotationNode>(method.visibleAnnotations);
                if (method.invisibleAnnotations != null) annotations.addAll(method.invisibleAnnotations);
                var group = annotations.stream().filter(a -> a.desc.endsWith("/Group;")).findFirst();
                if (group.isPresent()) groups.merge(name + value(group.get(), "name"), matches, Integer::sum);
                else assertEquals(1, matches, name + ":" + method.name + " " + invocation);
            }
        }
        assertEquals(3, groups.size());
        groups.forEach((name, matches) -> assertEquals(1, matches, name));
    }

    @Test void forgeListenerAccessorMatchesActualWrappedField() throws Exception {
        var listener = readClass("net/minecraftforge/event/AddReloadListenerEvent$WrappedStateAwareListener");
        assertEquals(1, listener.fields.stream().filter(field -> field.name.equals("wrapped")
                && field.desc.equals("Lnet/minecraft/server/packs/resources/PreparableReloadListener;")).count());
    }

    @Test void startupAtlasHasCompleteMetricsAndRequiredBrandingWithoutGameFonts() throws Exception {
        Path root = Path.of("src/main/resources/assets/gt_shanhai/textures/gui/loading");
        var metadata = JsonParser.parseString(Files.readString(root.resolve("shanhai_loading_font.json"))).getAsJsonObject();
        String characters = metadata.get("characters").getAsString();
        assertEquals(characters.length(), characters.chars().distinct().count());
        assertEquals(characters.length(), metadata.getAsJsonArray("advances").size());
        for (char letter : "山海模組初始化資源重載進入世界0123456789%".toCharArray()) assertTrue(characters.indexOf(letter) >= 0);
        var pixels = ImageIO.read(root.resolve("shanhai_loading_font.png").toFile());
        assertEquals(metadata.get("width").getAsInt(), pixels.getWidth());
        assertEquals(metadata.get("height").getAsInt(), pixels.getHeight());
    }

    private static ClassNode readClass(String name) throws Exception {
        try (var input = ShanhaiLoadingIntegrationTest.class.getClassLoader().getResourceAsStream(name + ".class")) {
            assertNotNull(input, name);
            var node = new ClassNode();
            new ClassReader(input).accept(node, 0);
            return node;
        }
    }

    private static ClassNode targetClass(AnnotationNode mixin) throws Exception {
        @SuppressWarnings("unchecked") List<Type> targets = (List<Type>) value(mixin, "value");
        if (targets != null) return readClass(targets.get(0).getInternalName());
        @SuppressWarnings("unchecked") List<String> names = (List<String>) value(mixin, "targets");
        return readClass(names.get(0).replace('.', '/'));
    }

    private static Object value(AnnotationNode annotation, String key) {
        for (int i = 0; i < annotation.values.size(); i += 2) if (annotation.values.get(i).equals(key)) return annotation.values.get(i + 1);
        return null;
    }
}
