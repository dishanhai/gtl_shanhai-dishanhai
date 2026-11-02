package com.dishanhai.gt_shanhai.client.loading;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarFile;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ShanhaiLoadingJeiWaitPatchTest {
    private static final String SOURCE = "com.piglinmine.jeioptimizer.mixin.ElementSearchMixin";
    private static ClassNode fixture() {
        var node = new ClassNode(); node.name = "mezz/jei/gui/search/ElementSearch";
        var method = new MethodNode(Opcodes.ACC_PRIVATE, "handler$test$jeiopt$parallelBuild",
                "(Lmezz/jei/gui/search/ElementPrefixParser;Ljava/util/Collection;Lmezz/jei/api/runtime/IIngredientManager;Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V", null, null);
        merged(method, SOURCE);
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/util/concurrent/ForkJoinTask", "get", "()Ljava/lang/Object;", false));
        node.methods.add(method); return node;
    }
    private static void merged(MethodNode method, String source) {
        if (method.visibleAnnotations == null) method.visibleAnnotations = new ArrayList<>();
        var annotation = new AnnotationNode("Lorg/spongepowered/asm/mixin/transformer/meta/MixinMerged;");
        annotation.values = new ArrayList<>(List.of("mixin", source)); method.visibleAnnotations.add(annotation);
    }

    @Test void changesOnlyExactKnownMergedHandlerAndKeepsStackShape() {
        var node = fixture(); assertEquals(1, ShanhaiLoadingJeiWaitPatch.apply(node));
        var call = (MethodInsnNode) node.methods.getFirst().instructions.getFirst();
        assertEquals(Opcodes.INVOKESTATIC, call.getOpcode()); assertEquals("awaitJei", call.name);
        assertEquals("(Ljava/util/concurrent/ForkJoinTask;)Ljava/lang/Object;", call.desc);
        assertEquals(0, ShanhaiLoadingJeiWaitPatch.apply(node), "must not patch twice");
    }

    @Test void refusesWrongProvenanceSignatureAndAmbiguousGetWithoutPartialChanges() {
        var wrongSource = fixture(); wrongSource.methods.getFirst().visibleAnnotations.clear();
        assertEquals(0, ShanhaiLoadingJeiWaitPatch.apply(wrongSource));
        var wrongDescriptor = fixture(); wrongDescriptor.methods.getFirst().desc = "()V";
        assertEquals(0, ShanhaiLoadingJeiWaitPatch.apply(wrongDescriptor));
        var ambiguous = fixture();
        ambiguous.methods.getFirst().instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                "java/util/concurrent/ForkJoinTask", "get", "()Ljava/lang/Object;", false));
        assertEquals(0, ShanhaiLoadingJeiWaitPatch.apply(ambiguous));
        assertEquals("get", ((MethodInsnNode) ambiguous.methods.getFirst().instructions.getFirst()).name);
    }

    @Test void actualInstalledOptimizerHandlerBytesKeepOriginalCatchRegions() throws Exception {
        String jar = System.getProperty("shanhai.loading.optimizerJar"); assumeTrue(jar != null);
        try (var zip = new JarFile(Path.of(jar).toFile())) {
            for (String name : List.of("ElementSearch", "PluginCaller")) {
                var entry = zip.getJarEntry("com/piglinmine/jeioptimizer/mixin/" + name + "Mixin.class"); assertNotNull(entry);
                ClassNode node = new ClassNode();
                try (var bytes = zip.getInputStream(entry)) { new ClassReader(bytes).accept(node, 0); }
                var handler = node.methods.stream().filter(method -> method.name.equals(name.equals("ElementSearch")
                        ? "jeiopt$parallelBuild" : "jeiopt$parallelize")).findFirst().orElseThrow();
                int catches = handler.tryCatchBlocks.size();
                handler.name = "handler$test$" + handler.name;
                merged(handler, node.name.replace('/', '.'));
                node.name = name.equals("ElementSearch") ? "mezz/jei/gui/search/ElementSearch" : "mezz/jei/library/load/PluginCaller";
                assertEquals(1, ShanhaiLoadingJeiWaitPatch.apply(node), name);
                assertEquals(catches, handler.tryCatchBlocks.size());
                var writer = new ClassWriter(0); node.accept(writer); assertNotNull(new ClassReader(writer.toByteArray()));
            }
        }
    }
}
