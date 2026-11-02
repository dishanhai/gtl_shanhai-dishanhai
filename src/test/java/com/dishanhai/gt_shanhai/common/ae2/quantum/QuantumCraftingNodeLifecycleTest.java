package com.dishanhai.gt_shanhai.common.ae2.quantum;

import appeng.me.GridNode;
import appeng.me.ManagedGridNode;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** 山海：實際 AE 節點生命週期重現，以及量子更新入口的原始碼契約。 */
class QuantumCraftingNodeLifecycleTest {
    private static final Path BLOCK_ENTITY = Path.of("src/main/java/com/dishanhai/gt_shanhai/common/ae2/quantum",
            "QuantumCraftingBlockEntity.java");

    @Test
    void destroyedNodeRejectsFurtherConnectionSideChanges() throws Exception {
        // AE destroy() 後 initData 與 node 均為 null；無須啟動 Forge 世界。
        ManagedGridNode destroyed = allocate(ManagedGridNode.class);
        assertFalse(destroyed.isReady());
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> destroyed.setExposedOnSides(Set.of()));
        assertTrue(error.getMessage().contains("already been initialized"));
    }

    @Test
    void readinessDistinguishesUninitializedLiveAndDestroyedNodes() throws Exception {
        ManagedGridNode node = allocate(ManagedGridNode.class);
        Object initData = allocate(Class.forName("appeng.me.ManagedGridNode$InitData"));
        setField(node, "initData", initData);
        assertFalse(node.isReady(), "尚未建立節點");

        setField(node, "initData", null);
        setField(node, "node", allocate(GridNode.class));
        assertTrue(node.isReady(), "已建立節點");

        setField(node, "node", null);
        assertFalse(node.isReady(), "已銷毀節點");
    }

    @Test
    void quantumUpdateChecksNodeReadinessBeforeTouchingWorldOrConnectionSides() throws Exception {
        String source = Files.readString(BLOCK_ENTITY);
        int start = source.indexOf("public void updateSubType(boolean updateFormed)");
        String update = source.substring(start, source.indexOf("@Override", start));
        String entry = update.substring(0, update.indexOf("QuantumDiagnostics.start()"));
        assertTrue(entry.contains("!getMainNode().isReady()"),
                "區塊尚未標記移除時，已銷毀 AE 節點也必須在更新入口返回");
        assertTrue(entry.contains("return;"));
        assertFalse(entry.contains("isOnline()"), "斷電節點仍須同步外觀及連接面");
        assertFalse(entry.contains("isActive()"), "缺頻道不能當作節點已銷毀");
        assertTrue(update.indexOf("QuantumDiagnostics.start()") < update.indexOf("level.getBlockState"));
        assertTrue(update.contains("onGridConnectableSidesChanged();"), "保留有效節點的連接面更新");
    }

    @Test
    void onReadyRecalculatesClusterAfterNodeCreation() throws Exception {
        String source = Files.readString(BLOCK_ENTITY);
        int start = source.indexOf("public void onReady()");
        String onReady = source.substring(start, source.indexOf("public void updateMultiBlock", start));
        int createNode = onReady.indexOf("super.onReady();");
        int recalculate = onReady.indexOf("calculator.calculateMultiblock(");
        assertTrue(createNode >= 0, "保留父類節點建立流程");
        assertTrue(recalculate >= 0, "保留結構重算流程");
        assertTrue(createNode < recalculate,
                "重新載入時先建立 AE 節點，再重算結構並恢復連接面");
    }

    private static <T> T allocate(Class<T> type) throws Exception {
        return type.cast(unsafe().allocateInstance(type));
    }

    private static void setField(ManagedGridNode target, String name, Object value) throws Exception {
        Unsafe unsafe = unsafe();
        unsafe.putObject(target, unsafe.objectFieldOffset(ManagedGridNode.class.getDeclaredField(name)), value);
    }

    private static Unsafe unsafe() throws Exception {
        Field field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (Unsafe) field.get(null);
    }
}
