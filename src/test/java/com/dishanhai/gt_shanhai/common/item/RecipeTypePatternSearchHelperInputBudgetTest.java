package com.dishanhai.gt_shanhai.common.item;

import appeng.api.stacks.AEKey;
import appeng.api.stacks.AEKeyType;
import appeng.api.stacks.GenericStack;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.ToLongFunction;

import static org.junit.jupiter.api.Assertions.*;

/** 山海：P2-07，同 key 消耗需求共用庫存及訂單預算。 */
class RecipeTypePatternSearchHelperInputBudgetTest {

    @Test
    void duplicateEqualKeysShareOneAvailabilityCheck() throws Exception {
        AEKey key = new TestKey("item", "copper", "");
        Object2LongMap<AEKey> demand = merge(new GenericStack[]{
                new GenericStack(key, 6), new GenericStack(new TestKey("item", "copper", ""), 4)
        }, new GenericStack[2]);
        AtomicInteger simulations = new AtomicInteger();

        long parallel = parallel(demand, 8, candidate -> {
            assertEquals(key, candidate);
            simulations.incrementAndGet();
            return 19;
        });

        assertEquals(1, demand.size());
        assertEquals(10, demand.getLong(key));
        assertEquals(1, parallel, "19 份庫存只能完成一次 6+4，不能各自重用庫存");
        assertEquals(1, simulations.get());
        assertEquals(7, 8 - parallel, "預算只扣可完整供料的並行");
    }

    @Test
    void remainingStockCannotSpendAnotherBatchOfBudget() throws Exception {
        AEKey key = new TestKey("fluid", "water", "");
        Object2LongMap<AEKey> demand = merge(new GenericStack[]{
                new GenericStack(key, 2000), new GenericStack(key, 3000)
        }, new GenericStack[2]);

        long budget = 4;
        long stock = 12000;
        long first = parallel(demand, budget, ignored -> 12000);
        budget -= first;
        stock -= demand.getLong(key) * first;
        long remainingStock = stock;
        long second = parallel(demand, budget, ignored -> remainingStock);

        assertEquals(2, first);
        assertEquals(2000, stock);
        assertEquals(0, second);
        assertEquals(2, budget - second);
    }

    @Test
    void presenceAndInvalidAmountsDoNotConsumeStock() throws Exception {
        AEKey key = new TestKey("item", "copper", "");
        AEKey catalyst = new TestKey("item", "circuit", "1");
        GenericStack[] inputs = {new GenericStack(key, 3), new GenericStack(key, 100),
                new GenericStack(catalyst, 1), null, new GenericStack(key, 0), new GenericStack(key, -1)};
        GenericStack[] presence = new GenericStack[inputs.length];
        presence[1] = new GenericStack(key, 100);
        presence[2] = new GenericStack(catalyst, 1);

        Object2LongMap<AEKey> demand = merge(inputs, presence);

        assertEquals(1, demand.size());
        assertEquals(3, demand.getLong(key));
        assertEquals(4, parallel(demand, 10, ignored -> 12));
    }

    @Test
    void distinctSecondaryDataAndKeyTypesKeepIndependentBottlenecks() throws Exception {
        AEKey first = new TestKey("item", "container", "water");
        AEKey second = new TestKey("item", "container", "lava");
        AEKey fluid = new TestKey("fluid", "container", "water");
        Object2LongMap<AEKey> demand = merge(new GenericStack[]{new GenericStack(first, 2),
                new GenericStack(second, 3), new GenericStack(fluid, 1000)}, new GenericStack[3]);

        assertEquals(3, demand.size());
        assertEquals(2, parallel(demand, 10,
                key -> key.equals(first) ? 100 : key.equals(second) ? 6 : 9000));
    }

    @Test
    void budgetAndHostLimitStillCapTheBatch() throws Exception {
        AEKey key = new TestKey("item", "copper", "");
        Object2LongMap<AEKey> demand = merge(new GenericStack[]{new GenericStack(key, 2)},
                new GenericStack[1]);

        assertEquals(3, parallel(demand, Math.min(3, 20), ignored -> 100));
        assertEquals(2, parallel(demand, Math.min(30, 2), ignored -> 100));
        assertEquals(0, parallel(demand, 30, ignored -> 1));
        assertEquals(0, parallel(demand, 0, ignored -> fail("耗盡預算不應讀庫存")));
    }

    @Test
    void presenceOnlyInputsNeverSpendBudget() throws Exception {
        AEKey key = new TestKey("item", "circuit", "1");
        GenericStack catalyst = new GenericStack(key, 1);
        Object2LongMap<AEKey> demand = merge(new GenericStack[]{catalyst},
                new GenericStack[]{catalyst});

        assertTrue(demand.isEmpty());
        assertEquals(0, parallel(demand, 10, ignored -> fail("Presence 不應模擬抽取")));
    }

    @Test
    void representableLongDemandDoesNotOverflowWhenMultiplied() throws Exception {
        AEKey key = new TestKey("fluid", "water", "");
        Object2LongMap<AEKey> demand = merge(new GenericStack[]{new GenericStack(key, Long.MAX_VALUE - 3),
                new GenericStack(key, 3)}, new GenericStack[2]);

        assertEquals(Long.MAX_VALUE, demand.getLong(key));
        assertEquals(1, parallel(demand, 100, ignored -> Long.MAX_VALUE));
    }

    @Test
    void unrepresentableDemandCannotUnderfillAndSpendBudget() throws Exception {
        AEKey key = new TestKey("fluid", "water", "");
        Object2LongMap<AEKey> demand = merge(new GenericStack[]{new GenericStack(key, Long.MAX_VALUE),
                new GenericStack(key, 1)}, new GenericStack[2]);

        assertNull(demand, "合併量超過 long 時拒絕本輪，不能飽和後當成可完成一份");
        assertEquals(0, parallel(demand, 10, ignored -> fail("超界需求不能抽取")));
    }

    @Test
    void simulationAndExtractionUseTheSameMergedDemand() throws Exception {
        String source = Files.readString(Path.of("src/main/java/com/dishanhai/gt_shanhai/common/item/"
                + "RecipeTypePatternSearchHelper.java"));
        int start = source.indexOf("private static void topUpVirtualSupply");
        int end = source.indexOf("\n    static ", start);
        String method = source.substring(start, end);

        assertTrue(method.contains("mergeConsumableInputs(inputs, presenceTargets)"),
                "P2-07：在模擬與抽取前合併相同 AEKey");
        assertTrue(method.contains("resolveVirtualSupplyParallel(consumableInputs,"));
        assertTrue(method.contains("Math.min(remainingBudget, hostParallelLimit)"));
        assertTrue(method.contains("consumableInputs.object2LongEntrySet()"));
        assertTrue(method.contains("saturatedMultiply(input.getLongValue(), achievable)"));
        assertTrue(method.contains("storage.extract(input.getKey(), want, Actionable.MODULATE"));
        assertTrue(method.contains("decrementBudget(buffer, slot, achievable)"));
        assertFalse(method.contains("available / in.amount()"));
    }

    private static Object2LongMap<AEKey> merge(GenericStack[] inputs, GenericStack[] presence) {
        return RecipeTypePatternSearchHelper.mergeConsumableInputs(inputs, presence);
    }

    private static long parallel(Object2LongMap<AEKey> demand, long limit, ToLongFunction<AEKey> available) {
        return RecipeTypePatternSearchHelper.resolveVirtualSupplyParallel(demand, limit, available);
    }

    private static final class TestKey extends AEKey {
        private final String type;
        private final String id;
        private final String secondary;

        private TestKey(String type, String id, String secondary) {
            this.type = type;
            this.id = id;
            this.secondary = secondary;
        }

        @Override public boolean equals(Object other) {
            return other instanceof TestKey key && type.equals(key.type) && id.equals(key.id)
                    && secondary.equals(key.secondary);
        }
        @Override public int hashCode() { return java.util.Objects.hash(type, id, secondary); }
        @Override public AEKeyType getType() { return null; }
        @Override public AEKey dropSecondary() { return new TestKey(type, id, ""); }
        @Override public CompoundTag toTag() { return new CompoundTag(); }
        @Override public Object getPrimaryKey() { return id; }
        @Override public ResourceLocation getId() { return new ResourceLocation("gt_shanhai", id); }
        @Override public void writeToPacket(FriendlyByteBuf output) {}
        @Override protected Component computeDisplayName() { return Component.literal(id); }
        @Override public void addDrops(long amount, List<ItemStack> drops, Level level, BlockPos pos) {}
    }
}
