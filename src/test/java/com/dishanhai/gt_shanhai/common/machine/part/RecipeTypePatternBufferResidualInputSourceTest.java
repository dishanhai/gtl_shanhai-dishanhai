package com.dishanhai.gt_shanhai.common.machine.part;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class RecipeTypePatternBufferResidualInputSourceTest {

    private static final Path MACHINE = Path.of("src", "main", "java", "com", "dishanhai", "gt_shanhai",
            "common", "machine", "part", "RecipeTypePatternBufferPartMachine.java");

    @Test
    void normalAndWildcardSlotsRemainActiveWithBufferedInputs() throws IOException {
        String source = Files.readString(MACHINE);

        assertTrue(source.contains("return new StellarPatternBufferInternalSlot(slotIndex)"),
                "普通星律槽必须使用不会被残留库存禁用的专属 InternalSlot");
        assertTrue(source.contains("new StellarPatternBufferInternalSlot(globalSlot)"),
                "通配符展开槽必须使用相同的残留库存语义");
        assertTrue(source.contains("public boolean isItemActive(boolean simulate)"));
        assertTrue(source.contains("public boolean isFluidActive(boolean simulate)"));
        assertTrue(source.contains("return hasPatternInSlot(getSlotIndex())"),
                "星律活动槽应由样板身份决定是否参与匹配，库存非空不能反向禁用槽位");
    }

    @Test
    void jadeMergedInventoryExcludesVirtualPresenceAndItsCatalystMirror() throws IOException {
        String source = Files.readString(MACHINE);

        assertTrue(source.contains("public Pair<Object2LongOpenHashMap<Item>, Object2LongOpenHashMap<Fluid>> getMergedInternalSlot()"),
                "星律必须覆盖 GTLCore Jade 合并结果");
        assertTrue(source.contains("VirtualPatternBufferSlotState"));
        assertTrue(source.contains("getVirtualTargets(internalSlot.getItemInventory())"));
        assertTrue(source.contains("getVirtualTargets(internalSlot.getFluidInventory())"));
        assertTrue(source.contains("internalSlot.getItemCatalystInventory().getLong(key)"),
                "虚拟物品同时存在于内部库存和催化镜像，两份都必须从 Jade 结果扣除");
        assertTrue(source.contains("internalSlot.getFluidCatalystInventory().getLong(key)"));
        assertTrue(source.contains("gtShanhai$subtractMergedAmount"));
    }

    @Test
    void jadeFallsBackToPersistedVirtualCircuitIdentity() throws IOException {
        String source = Files.readString(MACHINE);

        assertTrue(source.contains("VirtualPatternBufferSlotState.getVirtualCircuit(itemInventory)"),
                "历史槽只有虚拟电路标记时，Jade 仍必须识别该电路");
        assertTrue(source.contains("IntCircuitBehaviour.stack(virtualCircuit)"),
                "必须按持久化配置构造精确的编程电路 key");
        assertTrue(source.contains("!itemTargets.containsKey(circuitKey)"),
                "目标表已有电路时不得重复扣除");
    }
}
