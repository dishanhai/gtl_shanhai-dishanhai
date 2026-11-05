package com.dishanhai.gt_shanhai.integration.enhancedcore;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;

/** 山海：直接對實際依賴位元碼核對 gate 與已登記 Mixin，無須載入遊戲註冊表。 */
class EnhancedCoreCloudBytecodeContractTest {
    private final Map<String, ClassNode> classes = new HashMap<>();

    @Test
    void oldApiIsDisabledAndSelectedApiPassesTheSameGate() throws Exception {
        assertFalse(supports(Path.of("libs/GTL-Enhancedcore-3.8.9.jar")));
        assertTrue(supports(Path.of("libs/GTL-Enhancedcore-4.1.7.jar")));
    }

    private static boolean supports(Path jar) throws IOException {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            return EnhancedCoreCloudApi.supports(true, method -> {
                var entry = zip.getEntry(method.owner().replace('.', '/') + ".class");
                if (entry == null) return false;
                try (var input = zip.getInputStream(entry)) {
                    ClassNode owner = new ClassNode();
                    new ClassReader(input).accept(owner, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG);
                    return owner.methods.stream().anyMatch(found -> found.name.equals(method.name())
                            && found.desc.equals(method.descriptor()));
                } catch (IOException failure) { throw new IllegalStateException(failure); }
            });
        }
    }

    @Test
    void registeredOptionalMixinSelectorsExistInActualTargetBytecode() throws Exception {
        var config = JsonParser.parseString(Files.readString(Path.of("src/main/resources/gt_shanhai.enhancedcore.mixin.json")))
                .getAsJsonObject();
        assertTrue(config.get("plugin").getAsString().endsWith("EnhancedCoreCloudMixinPlugin"));
        int checked = 0;
        for (var value : config.getAsJsonArray("mixins")) {
            String simple = value.getAsString();
            if (!simple.startsWith("EnhancedCoreCloud") && !simple.startsWith("EnhancedCorePrimordial")) continue;
            ClassNode mixin = readClass("com/dishanhai/gt_shanhai/mixin/" + simple);
            assertNotNull(mixin, simple);
            var targets = new ArrayList<String>();
            for (AnnotationNode annotation : annotations(mixin.visibleAnnotations, mixin.invisibleAnnotations)) {
                if (!annotation.desc.equals("Lorg/spongepowered/asm/mixin/Mixin;")) continue;
                for (int i = 0; i < annotation.values.size(); i += 2) {
                    String key = (String) annotation.values.get(i);
                    Object raw = annotation.values.get(i + 1);
                    if (key.equals("targets")) for (Object name : (List<?>) raw)
                        targets.add(name.toString().replace('.', '/'));
                    if (key.equals("value")) for (Object type : (List<?>) raw)
                        targets.add(((Type) type).getInternalName());
                }
            }
            assertFalse(targets.isEmpty(), simple + " lacks targets");
            for (var field : mixin.fields) {
                if (annotations(field.visibleAnnotations, field.invisibleAnnotations).stream()
                        .noneMatch(annotation -> annotation.desc.equals("Lorg/spongepowered/asm/mixin/Shadow;"))) continue;
                for (String target : targets) assertTrue(hasField(target, field.name, field.desc),
                        simple + " missing shadow " + target + "#" + field.name + ":" + field.desc);
            }
            for (var handler : mixin.methods) {
                for (var annotation : annotations(handler.visibleAnnotations, handler.invisibleAnnotations)) {
                    if (annotation.values == null) continue;
                    for (int i = 0; i < annotation.values.size(); i += 2) {
                        if (!annotation.values.get(i).equals("method")) continue;
                        Object selectors = annotation.values.get(i + 1);
                        List<?> methods = selectors instanceof List<?> list ? list : List.of(selectors);
                        for (Object raw : methods) {
                            String selector = raw.toString();
                            for (String target : targets) {
                                assertTrue(hasMethod(target, selector), simple + "." + handler.name
                                        + " missing target " + target + "#" + selector);
                                checked++;
                            }
                        }
                    }
                }
            }
        }
        assertTrue(checked >= 20, "必須核對全部新增橋接的注入目標");
    }

    private boolean hasMethod(String owner, String selector) throws IOException {
        int descriptor = selector.indexOf('(');
        String name = descriptor < 0 ? selector : selector.substring(0, descriptor);
        String desc = descriptor < 0 ? null : selector.substring(descriptor);
        ClassNode node = readClass(owner);
        if (node == null) return false;
        if (node.methods.stream().anyMatch(method -> method.name.equals(name)
                && (desc == null || method.desc.equals(desc)))) return true;
        return node.superName != null && !node.superName.equals("java/lang/Object") && hasMethod(node.superName, selector);
    }

    private boolean hasField(String owner, String name, String descriptor) throws IOException {
        ClassNode node = readClass(owner);
        if (node == null) return false;
        if (node.fields.stream().anyMatch(field -> field.name.equals(name) && field.desc.equals(descriptor))) return true;
        return node.superName != null && !node.superName.equals("java/lang/Object")
                && hasField(node.superName, name, descriptor);
    }

    private ClassNode readClass(String owner) throws IOException {
        if (classes.containsKey(owner)) return classes.get(owner);
        String relative = owner + ".class";
        Path compiled = Path.of("build/classes/java/main").resolve(relative);
        byte[] bytes = Files.isRegularFile(compiled) ? Files.readAllBytes(compiled) : null;
        if (bytes == null) {
            try (var jars = Files.list(Path.of("libs"))) {
                for (Path path : jars.filter(p -> p.toString().endsWith(".jar"))
                        .filter(p -> !p.getFileName().toString().equals("GTL-Enhancedcore-3.8.9.jar")).toList()) {
                    try (ZipFile zip = new ZipFile(path.toFile())) {
                        var entry = zip.getEntry(relative);
                        if (entry != null) {
                            try (var input = zip.getInputStream(entry)) { bytes = input.readAllBytes(); }
                            break;
                        }
                    }
                }
            }
        }
        if (bytes == null) return null;
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG);
        classes.put(owner, node);
        return node;
    }

    private static List<AnnotationNode> annotations(List<AnnotationNode> visible, List<AnnotationNode> invisible) {
        var result = new ArrayList<AnnotationNode>();
        if (visible != null) result.addAll(visible);
        if (invisible != null) result.addAll(invisible);
        return result;
    }
}
