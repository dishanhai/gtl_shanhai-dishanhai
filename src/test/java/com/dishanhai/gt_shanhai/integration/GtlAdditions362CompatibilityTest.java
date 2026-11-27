package com.dishanhai.gt_shanhai.integration;

// Author: dishanhai
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Checks actual compiled references without initializing Minecraft registries or machines. */
class GtlAdditions362CompatibilityTest {
    private static final String PREFIX = "com/gtladd/gtladditions/";

    @Test
    void requiredMixinTargetClassesExistInTheTargetJar() throws Exception {
        Set<String> missing = new TreeSet<>();
        try (ZipFile jar = new ZipFile("libs/gtladditions-3.6.2Custom.jar");
             var files = Files.walk(Path.of("build/classes/java/main/com/dishanhai/gt_shanhai/mixin"))) {
            for (Path file : files.filter(p -> p.toString().endsWith(".class")).toList()) {
                ClassNode node = new ClassNode();
                new ClassReader(Files.readAllBytes(file)).accept(node, ClassReader.SKIP_CODE);
                List<AnnotationNode> annotations = new ArrayList<>();
                if (node.visibleAnnotations != null) annotations.addAll(node.visibleAnnotations);
                if (node.invisibleAnnotations != null) annotations.addAll(node.invisibleAnnotations);
                if (annotations.stream().anyMatch(a -> a.desc.equals("Lorg/spongepowered/asm/mixin/Pseudo;"))) continue;
                for (AnnotationNode annotation : annotations) {
                    if (!annotation.desc.equals("Lorg/spongepowered/asm/mixin/Mixin;")) continue;
                    for (int i = 0; i < annotation.values.size(); i += 2) {
                        if (!List.of("value", "targets").contains(annotation.values.get(i))) continue;
                        for (Object target : (List<?>) annotation.values.get(i + 1)) {
                            String owner = target instanceof Type type ? type.getInternalName()
                                    : target.toString().replace('.', '/');
                            if (owner.startsWith(PREFIX) && jar.getEntry(owner + ".class") == null) {
                                missing.add(node.name + " -> " + owner);
                            }
                        }
                    }
                }
            }
        }
        assertTrue(missing.isEmpty(), "Missing required Mixin targets:\n" + String.join("\n", missing));
    }

    @Test
    void compiledCallsAndFieldsLinkAgainstTheTargetJar() throws Exception {
        Set<String> failures = new TreeSet<>();
        try (ZipFile jar = new ZipFile("libs/gtladditions-3.6.2Custom.jar")) {
            Map<String, ApiClass> classes = new HashMap<>();
            for (Path root : new Path[]{Path.of("build/classes/java/main"), Path.of("build/classes/kotlin/main")}) {
                if (!Files.isDirectory(root)) continue;
                try (var files = Files.walk(root)) {
                    for (Path file : files.filter(p -> p.toString().endsWith(".class")).toList()) {
                        String caller = root.relativize(file).toString();
                        new ClassReader(Files.readAllBytes(file)).accept(new ClassVisitor(Opcodes.ASM9) {
                            @Override
                            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                             String signature, String[] exceptions) {
                                return new MethodVisitor(Opcodes.ASM9) {
                                    @Override
                                    public void visitMethodInsn(int opcode, String owner, String name,
                                                                String descriptor, boolean isInterface) {
                                        check(owner, name + descriptor, true);
                                    }

                                    @Override
                                    public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
                                        check(owner, name + ":" + descriptor, false);
                                    }

                                    private void check(String owner, String member, boolean method) {
                                        if (!owner.startsWith(PREFIX)) return;
                                        try {
                                            if (!hasMember(jar, classes, owner, member, method, new HashSet<>())) {
                                                failures.add(caller + " -> " + owner + "." + member);
                                            }
                                        } catch (IOException error) {
                                            throw new java.io.UncheckedIOException(error);
                                        }
                                    }
                                };
                            }
                        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                    }
                }
            }
        }
        assertTrue(failures.isEmpty(), "Unresolved GTL Additions 3.6.2 API references:\n" + String.join("\n", failures));
    }

    private static boolean hasMember(ZipFile jar, Map<String, ApiClass> classes, String owner,
                                     String member, boolean method, Set<String> visited) throws IOException {
        if (owner == null || !visited.add(owner)) return false;
        ApiClass api = readClass(jar, classes, owner);
        if (api == null) return false;
        if ((method ? api.methods : api.fields).contains(member)) return true;
        if (member.startsWith("<init>")) return false;
        if (hasMember(jar, classes, api.parent, member, method, visited)) return true;
        for (String contract : api.interfaces) {
            if (hasMember(jar, classes, contract, member, method, visited)) return true;
        }
        return false;
    }

    private static ApiClass readClass(ZipFile jar, Map<String, ApiClass> classes, String owner) throws IOException {
        if (classes.containsKey(owner)) return classes.get(owner);
        var entry = jar.getEntry(owner + ".class");
        try (var stream = entry != null ? jar.getInputStream(entry)
                : owner.startsWith(PREFIX) ? null
                : GtlAdditions362CompatibilityTest.class.getClassLoader().getResourceAsStream(owner + ".class")) {
            if (stream == null) {
                classes.put(owner, null);
                return null;
            }
            ApiClass definition = new ApiClass();
            new ClassReader(stream).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public void visit(int version, int access, String name, String signature,
                                  String superName, String[] interfaces) {
                    definition.parent = superName;
                    definition.interfaces = interfaces;
                }

                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                 String signature, String[] exceptions) {
                    definition.methods.add(name + descriptor);
                    return null;
                }

                @Override
                public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
                    definition.fields.add(name + ":" + descriptor);
                    return null;
                }
            }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            classes.put(owner, definition);
            return definition;
        }
    }

    private static final class ApiClass {
        String parent;
        String[] interfaces;
        final Set<String> methods = new HashSet<>();
        final Set<String> fields = new HashSet<>();
    }
}
