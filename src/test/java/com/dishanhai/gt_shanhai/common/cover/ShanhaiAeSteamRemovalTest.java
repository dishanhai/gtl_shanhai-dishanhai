package com.dishanhai.gt_shanhai.common.cover;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;

import static org.junit.jupiter.api.Assertions.*;

/** 山海：左键拆卸必须由服务端调用原生覆盖板回收，客户端仅取消挖掘。 */
class ShanhaiAeSteamRemovalTest {
    @Test
    void binderSubscribesToLeftClickAndGuardsRemovalAgainstWrongGestureAndClientExecution() throws Exception {
        ClassNode binder = readClass(ShanhaiAeSteamBinderBehavior.class);
        assertTrue(binder.visibleAnnotations != null && binder.visibleAnnotations.stream()
                .anyMatch(annotation -> annotation.desc.endsWith("Mod$EventBusSubscriber;")),
                "绑定器必须实际注册 Forge 左键事件");
        MethodNode handler = binder.methods.stream().filter(method -> method.name.equals("onLeftClickBlock"))
                .findFirst().orElseThrow(() -> new AssertionError("缺少左键拆卸事件入口"));
        assertTrue(handler.visibleAnnotations != null && handler.visibleAnnotations.stream()
                .anyMatch(annotation -> annotation.desc.endsWith("SubscribeEvent;")));
        assertTrue(calls(handler, "isShiftKeyDown"));
        assertTrue(calls(handler, "isSpectator"));
        assertTrue(calls(handler, "mayBuild"));
        assertTrue(calls(handler, "is"), "必须核对手中物品");
        assertTrue(field(handler, "AE_STEAM_BINDER"));
        assertTrue(calls(handler, "rayTraceCoverableSide"), "必须选中玩家点击的覆盖板面");
        assertTrue(typeCheck(handler, "ShanhaiAeSteamCover"), "只拆蒸汽覆盖板");
        assertTrue(typeCheck(handler, "ServerPlayer"), "服务端才能执行拆卸");
        assertTrue(field(handler, "START"), "仅起始事件可触发回收");
        assertTrue(calls(handler, "mayInteract"));
        assertTrue(calls(handler, "mayUseItemAt"));
        int cancel = callIndex(handler, "setCanceled");
        int remove = callIndex(handler, "removeCover");
        assertTrue(cancel >= 0 && remove > cancel, "取消挖掘后才能回收，避免误拆机器");
        assertTrue(handler.instructions.get(remove) instanceof MethodInsnNode call
                && call.owner.equals("com/gregtechceu/gtceu/api/capability/ICoverable")
                && call.desc.equals("(Lnet/minecraft/core/Direction;Lnet/minecraft/world/entity/player/Player;)Z"));
        assertFalse(calls(handler, "bind"), "拆卸前不能清除绑定资料");
        assertFalse(calls(handler, "destroyBlock"));
        assertFalse(calls(handler, "setBlock"));
    }

    private static ClassNode readClass(Class<?> type) throws Exception {
        ClassNode result = new ClassNode();
        try (var input = type.getResourceAsStream("/" + type.getName().replace('.', '/') + ".class")) {
            assertNotNull(input);
            new ClassReader(input).accept(result, 0);
        }
        return result;
    }

    private static boolean calls(MethodNode method, String name) {
        return callIndex(method, name) >= 0;
    }

    private static int callIndex(MethodNode method, String name) {
        for (int index = 0; index < method.instructions.size(); index++) {
            if (method.instructions.get(index) instanceof MethodInsnNode call && call.name.equals(name)) return index;
        }
        return -1;
    }

    private static boolean field(MethodNode method, String name) {
        for (var instruction : method.instructions) {
            if (instruction instanceof FieldInsnNode field && field.name.equals(name)) return true;
        }
        return false;
    }

    private static boolean typeCheck(MethodNode method, String suffix) {
        for (var instruction : method.instructions) {
            if (instruction instanceof TypeInsnNode type && type.desc.endsWith("/" + suffix)) return true;
        }
        return false;
    }
}
