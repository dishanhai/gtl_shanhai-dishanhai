package com.dishanhai.gt_shanhai.common.machine.part;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 山海星律：同槽排程覆蓋，跨維度／座標／槽位互不影響。 */
class StellarPatternStuckWatchKeyTest {
    @Test
    void differentAeOrdersAtTheSameSlotCannotReplaceEachOthersWatch() {
        var dim = new ResourceLocation("minecraft", "overworld");
        var first = new StellarPatternStuckWatch.SlotKey(dim, 123L, 3, "job-a");
        var second = new StellarPatternStuckWatch.SlotKey(dim, 123L, 3, "job-b");
        assertEquals(2, java.util.Set.of(first, second).size());
        assertEquals(first, new StellarPatternStuckWatch.SlotKey(dim, 123L, 3, "job-a"));
    }

    @Test
    void repeatedPushReplacesOnlyTheSameDimensionPositionAndSlot() {
        long pos = new BlockPos(32, 64, -32).asLong();
        ResourceLocation overworld = new ResourceLocation("minecraft", "overworld");
        ResourceLocation nether = new ResourceLocation("minecraft", "the_nether");
        Map<StellarPatternStuckWatch.SlotKey, String> pending = new HashMap<>();
        pending.put(new StellarPatternStuckWatch.SlotKey(overworld, pos, 1000), "old");
        pending.put(new StellarPatternStuckWatch.SlotKey(nether, pos, 1000), "dimension");
        pending.put(new StellarPatternStuckWatch.SlotKey(overworld, pos + 1, 1000), "position");
        pending.put(new StellarPatternStuckWatch.SlotKey(overworld, pos, 1001), "slot");
        pending.put(new StellarPatternStuckWatch.SlotKey(overworld, pos, 1000), "new");

        assertEquals(4, pending.size());
        assertEquals("new", pending.get(new StellarPatternStuckWatch.SlotKey(overworld, pos, 1000)));
        assertEquals("dimension", pending.get(new StellarPatternStuckWatch.SlotKey(nether, pos, 1000)));
        assertEquals("position", pending.get(new StellarPatternStuckWatch.SlotKey(overworld, pos + 1, 1000)));
        assertEquals("slot", pending.get(new StellarPatternStuckWatch.SlotKey(overworld, pos, 1001)));
    }
}
