package com.dishanhai.gt_shanhai.common.machine.part.ui;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;

import static org.junit.jupiter.api.Assertions.*;

/** 山海：執行實際側欄高度計算，覆蓋超出樣板畫布的最後一個頁籤。 */
class StellarSidebarHitBoundsTest {
    private static int height(int canvasHeight, int subTabs) throws Exception {
        var source = new ClassNode();
        new ClassReader("com.dishanhai.gt_shanhai.common.machine.part.ui.RecipeThreadConfigurationPage")
                .accept(source, 0);
        var method = source.methods.stream().filter(it -> it.name.equals("stellarSidebarHeight"))
                .findFirst().orElse(null);
        assertNotNull(method, "星律側欄必須依實際頁籤數量決定命中高度");
        // 僅複製純計算方法，避免單元測試載入 Minecraft 的 UI 靜態資源。
        var writer = new ClassWriter(0);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "StellarSidebarHeightProbe", null, "java/lang/Object", null);
        method.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC;
        method.accept(writer);
        writer.visitEnd();
        class ProbeLoader extends ClassLoader {
            Class<?> define(byte[] bytes) {
                return defineClass("StellarSidebarHeightProbe", bytes, 0, bytes.length);
            }
        }
        return (int) new ProbeLoader().define(writer.toByteArray())
                .getMethod("stellarSidebarHeight", int.class, int.class).invoke(null, canvasHeight, subTabs);
    }

    @Test
    void bottomWirelessTabIsFullyClickableBelowThePatternCanvas() throws Exception {
        int height = height(216, 9);
        assertEquals(248, height);
        // 原生側欄最後一格位於 y=224..247，包含整個圖示及下邊緣。
        assertTrue(height > 224);
        assertTrue(height > 247);
    }

    @Test
    void tallerCanvasKeepsItsExistingHitArea() throws Exception {
        assertEquals(300, height(300, 9));
        assertEquals(216, height(216, 0));
    }

    @Test
    void additionalTabsAreIncludedWithoutAHardcodedTabCount() throws Exception {
        assertEquals(272, height(216, 10));
        assertEquals(320, height(216, 12));
    }
}
