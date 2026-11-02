package com.dishanhai.gt_shanhai.client.loading;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import java.util.ArrayList;
import java.util.List;

/** 山海：只處理已核對的 Optimizer merged handler，不改 JDK／一般 Future 等待。 */
public final class ShanhaiLoadingJeiWaitPatch {
    private ShanhaiLoadingJeiWaitPatch() {}

    public static int apply(ClassNode target) {
        String source, suffix, descriptor;
        if (target.name.equals("mezz/jei/gui/search/ElementSearch")) {
            source = "com.piglinmine.jeioptimizer.mixin.ElementSearchMixin";
            suffix = "jeiopt$parallelBuild";
            descriptor = "(Lmezz/jei/gui/search/ElementPrefixParser;Ljava/util/Collection;Lmezz/jei/api/runtime/IIngredientManager;Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V";
        } else if (target.name.equals("mezz/jei/library/load/PluginCaller")) {
            source = "com.piglinmine.jeioptimizer.mixin.PluginCallerMixin";
            suffix = "jeiopt$parallelize";
            descriptor = "(Ljava/lang/String;Ljava/util/List;Ljava/util/function/Consumer;Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;)V";
        } else return 0;
        List<MethodInsnNode> calls = new ArrayList<>();
        MethodNode owner = null;
        for (var method : target.methods) {
            if (!method.desc.equals(descriptor) || !(method.name.equals(suffix) || method.name.endsWith("$" + suffix))
                    || !fromOptimizer(method, source)) continue;
            if (owner != null) return 0;
            owner = method;
            for (var instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKEVIRTUAL
                        && call.owner.equals("java/util/concurrent/ForkJoinTask")
                        && call.name.equals("get") && call.desc.equals("()Ljava/lang/Object;")) calls.add(call);
            }
        }
        if (owner == null || calls.size() != 1) return 0;
        owner.instructions.set(calls.getFirst(), new MethodInsnNode(Opcodes.INVOKESTATIC,
                "com/dishanhai/gt_shanhai/client/loading/ShanhaiLoadingClient", "awaitJei",
                "(Ljava/util/concurrent/ForkJoinTask;)Ljava/lang/Object;", false));
        return 1;
    }

    private static boolean fromOptimizer(MethodNode method, String source) {
        var annotations = new ArrayList<AnnotationNode>();
        if (method.visibleAnnotations != null) annotations.addAll(method.visibleAnnotations);
        if (method.invisibleAnnotations != null) annotations.addAll(method.invisibleAnnotations);
        for (var annotation : annotations) {
            if (!annotation.desc.equals("Lorg/spongepowered/asm/mixin/transformer/meta/MixinMerged;") || annotation.values == null) continue;
            for (int i = 0; i < annotation.values.size(); i += 2) {
                if (annotation.values.get(i).equals("mixin") && annotation.values.get(i + 1).equals(source)) return true;
            }
        }
        return false;
    }
}
