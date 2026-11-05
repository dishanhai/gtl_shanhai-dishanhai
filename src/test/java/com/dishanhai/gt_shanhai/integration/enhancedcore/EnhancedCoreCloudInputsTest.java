package com.dishanhai.gt_shanhai.integration.enhancedcore;

import org.junit.jupiter.api.Test;
import com.gtl.enhancedcore.common.recipe.iv.LongAllocation;
import com.gtl.enhancedcore.common.recipe.iv.IvJob;
import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.AEKeyType;
import appeng.api.stacks.GenericStack;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;

class EnhancedCoreCloudInputsTest {

    @Test
    void keepsConsumableAndPresenceOfTheSameIdentitySeparate() {
        var inputs = EnhancedCoreCloudInputs.split(List.of(
                new EnhancedCoreCloudInputs.Input<>("item:nbt-a", 12L, false),
                new EnhancedCoreCloudInputs.Input<>("item:nbt-a", 3L, false),
                new EnhancedCoreCloudInputs.Input<>("item:nbt-a", 7L, true),
                new EnhancedCoreCloudInputs.Input<>("item:nbt-a", 5L, true),
                new EnhancedCoreCloudInputs.Input<>("item:nbt-b", 9L, true)));

        assertEquals(Map.of("item:nbt-a", 15L), inputs.physical());
        assertEquals(Map.of("item:nbt-a", 7L, "item:nbt-b", 9L), inputs.presence());
    }

    @Test
    void finiteLongPresenceIsMirroredWithoutBatchMultiplicationOrNarrowing() {
        long amount = (long) Integer.MAX_VALUE + 100;
        var inputs = EnhancedCoreCloudInputs.split(List.of(
                new EnhancedCoreCloudInputs.Input<>("fluid:nbt-a", amount, true)));
        assertEquals(Map.of("fluid:nbt-a", amount), inputs.mergePresence(Map.of("fluid:nbt-a", 5L)));
        assertEquals(Map.of("fluid:nbt-a", Long.MAX_VALUE),
                inputs.mergePresence(Map.of("fluid:nbt-a", Long.MAX_VALUE)));
        assertTrue(inputs.physical().isEmpty());
    }

    @Test
    void physicalOverflowAndInvalidQuantitiesFailClosed() {
        assertThrows(ArithmeticException.class, () -> EnhancedCoreCloudInputs.split(List.of(
                new EnhancedCoreCloudInputs.Input<>("item", Long.MAX_VALUE, false),
                new EnhancedCoreCloudInputs.Input<>("item", 1, false))));
        assertThrows(IllegalArgumentException.class, () -> EnhancedCoreCloudInputs.split(List.of(
                new EnhancedCoreCloudInputs.Input<>("item", 0, true))));
    }

    @Test
    void callerCannotChangeTheAccountingAfterDecomposition() {
        var inputs = EnhancedCoreCloudInputs.split(List.of(
                new EnhancedCoreCloudInputs.Input<>("item", 2, false)));
        assertThrows(UnsupportedOperationException.class, () -> inputs.physical().put("extra", 1L));
        assertThrows(UnsupportedOperationException.class, () -> inputs.presence().put("extra", 1L));
    }

    @Test
    void enhancedcoreAllocatorCannotSpendVirtualPresenceAsPhysicalMaterial() {
        var inputs = EnhancedCoreCloudInputs.split(List.of(
                new EnhancedCoreCloudInputs.Input<>("same-key", 4, false),
                new EnhancedCoreCloudInputs.Input<>("same-key", 2, true)));
        var supply = List.of(
                new LongAllocation.Supply<>("same-key", inputs.physical().get("same-key"), true),
                new LongAllocation.Supply<>("same-key", inputs.presence().get("same-key"), false));
        var needs = List.of(
                new LongAllocation.Need<String>("same-key"::equals, 4, true),
                new LongAllocation.Need<String>("same-key"::equals, 2, false));
        assertEquals(Map.of("same-key", 4L), LongAllocation.plan(supply, needs));
        assertNull(LongAllocation.plan(supply, List.of(
                new LongAllocation.Need<String>("same-key"::equals, 5, true))));
    }

    @Test
    void duplicatedMirrorsCannotSatisfyLargerFiniteCatalystDemand() {
        var inputs = EnhancedCoreCloudInputs.split(List.of(
                new EnhancedCoreCloudInputs.Input<>("fluid-with-nbt", 3L, true),
                new EnhancedCoreCloudInputs.Input<>("fluid-with-nbt", 3L, true)));
        assertNull(LongAllocation.plan(List.of(new LongAllocation.Supply<>(
                        "fluid-with-nbt", inputs.presence().get("fluid-with-nbt"), false)),
                List.of(new LongAllocation.Need<String>("fluid-with-nbt"::equals, 4L, false))));
    }

    @Test
    void adapterPreservesNbtIdentityAndSeparatesSameKeyPresenceFromConsumedUnits() throws Exception {
        var first = key("item", "first");
        var equalFirst = key("item", "first");
        var otherTag = key("item", "other");
        long finite = (long) Integer.MAX_VALUE + 501L;
        var ordinary = input(first, 4L, 13L);
        var pattern = pattern(new IPatternDetails.IInput[] {
                ordinary, input(equalFirst, 7L, 1L), presence(equalFirst, finite),
                presence(first, 20L), presence(otherTag, 8L)
        }, new GenericStack(otherTag, 3L));

        var split = EnhancedCoreCloudInputsAdapter.split(pattern);
        assertEquals(Map.of(first, 11L), split.physical());
        assertEquals(Map.of(first, finite, otherTag, 8L), split.presence());
        assertEquals(Map.of(first, 11L), EnhancedCoreCloudInputsAdapter.physicalUnits(pattern));
        var probe = EnhancedCoreCloudInputsAdapter.probe(pattern, Map.of(equalFirst, 5L));
        assertNotNull(probe);
        assertEquals(split.physical(), probe.inputs());
        assertEquals(split.presence(), probe.virtual());
        assertEquals(Map.of(otherTag, 3L), probe.outputs());
        assertNotEquals(first, otherTag);
        assertEquals(first, equalFirst);

        IPatternDetails.IInput[] filtered = EnhancedCoreCloudInputsAdapter.withoutPresence(pattern.getInputs());
        assertEquals(2, filtered.length);
        assertSame(ordinary, filtered[0]);
        assertEquals(4L, filtered[0].getMultiplier(),
                "4.1.7 的 probe/CPU/push 均以 multiplier 為單位需求，不能再乘 possible.amount");
        assertEquals(13L, filtered[0].getPossibleInputs()[0].amount());
    }

    @Test
    void adapterPreservesDistinctFluidTagsAndDoesNotMutateConfiguredVirtualStock() throws Exception {
        var hot = key("fluid", "hot");
        var cold = key("fluid", "cold");
        Map<AEKey, Long> configured = Map.of(hot, 9L, cold, 2L);
        var pattern = pattern(new IPatternDetails.IInput[] {
                presence(hot, 5L), presence(cold, 7L), input(cold, 19L, 1L)
        }, new GenericStack(hot, 2L), new GenericStack(hot, 3L));

        var probe = EnhancedCoreCloudInputsAdapter.probe(pattern, configured);
        assertNotNull(probe);
        assertEquals(Map.of(cold, 19L), probe.inputs());
        assertEquals(Map.of(hot, 9L, cold, 7L), probe.virtual());
        assertEquals(Map.of(hot, 5L), probe.outputs());
        assertEquals(Map.of(hot, 9L, cold, 2L), configured);
        assertEquals(Map.of(hot, 9L, cold, 7L),
                EnhancedCoreCloudInputsAdapter.mergePresence(pattern, configured));
    }

    @Test
    void adapterRejectsAmbiguousInputsAndPhysicalOverflowBeforePublishingProbe() {
        var key = key("item", "a");
        var overflow = pattern(new IPatternDetails.IInput[] {
                input(key, Long.MAX_VALUE, 1L), input(key, 1L, 1L)
        });
        assertNull(EnhancedCoreCloudInputsAdapter.probe(overflow, Map.of()));
        assertNull(EnhancedCoreCloudInputsAdapter.physicalUnits(overflow));
        var ambiguous = pattern(new IPatternDetails.IInput[] { new IPatternDetails.IInput() {
            public GenericStack[] getPossibleInputs() {
                return new GenericStack[] { new GenericStack(key, 1L), new GenericStack(key("item", "b"), 1L) };
            }
            public long getMultiplier() { return 1L; }
            public boolean isValid(AEKey candidate, Level level) { return true; }
            public AEKey getRemainingKey(AEKey candidate) { return null; }
        } });
        assertNull(EnhancedCoreCloudInputsAdapter.probe(ambiguous, Map.of()));
        assertNull(EnhancedCoreCloudInputsAdapter.physicalUnits(ambiguous));
    }

    @Test
    void ivJobCancellationRefundsOnlyPhysicalInventoryIncludingSameKeyPresence() throws Exception {
        var shared = key("item", "same-nbt");
        var virtualOnly = key("fluid", "virtual-nbt");
        var pattern = pattern(new IPatternDetails.IInput[] {
                input(shared, 10L, 1L), presence(shared, 7L), presence(virtualOnly, 99L)
        });
        var split = EnhancedCoreCloudInputsAdapter.split(pattern);
        var original = new CompoundTag();
        original.putString("identity", "mother-pattern");
        var job = new IvJob(2, null, original, split.physical(), split.presence(), 3L);
        job.refunds.put(shared, 2L);
        original.putString("identity", "changed-after-acceptance");

        job.cancel();

        assertEquals(Map.of(shared, 12L), job.refunds);
        assertFalse(job.refunds.containsKey(virtualOnly));
        assertTrue(job.inventory.isEmpty());
        assertEquals(split.presence(), job.virtual);
        assertEquals(3L, job.cancelledOperations);
        assertEquals("mother-pattern", job.pattern.getString("identity"));
        job.validateAccounting();
    }

    @Test
    void ivJobFinalBatchReturnsPhysicalLeftoversWithoutCreatingVirtualOutput() {
        var physical = key("item", "physical");
        var virtual = key("fluid", "virtual");
        var output = key("item", "output");
        var job = new IvJob(1, null, new CompoundTag(), Map.of(physical, 4L), Map.of(virtual, 50L), 2L);
        job.remaining = 0L;
        job.parallel = 2L;
        job.completion.put(output, 6L);

        job.completeBatch();

        assertEquals(Map.of(physical, 4L, output, 6L), job.pending);
        assertFalse(job.pending.containsKey(virtual));
        assertTrue(job.inventory.isEmpty());
        assertEquals(Map.of(virtual, 50L), job.virtual);
        job.validateAccounting();
    }

    @Test
    void ivJobStockSerializationRetainsLongAmountsAndCompleteNestedKeyNbt() {
        var first = key("fluid", "first");
        var other = key("fluid", "other");
        long amount = (long) Integer.MAX_VALUE + 812L;
        var serialized = IvJob.saveStock(Map.of(first, amount, other, 7L));
        assertEquals(2, serialized.size());
        long total = 0L;
        boolean foundFirst = false;
        for (int i = 0; i < serialized.size(); i++) {
            var entry = serialized.getCompound(i);
            var storedKey = entry.getCompound("key");
            total = Math.addExact(total, entry.getLong("amount"));
            if (storedKey.getCompound("payload").equals(first.tag)) {
                foundFirst = true;
                assertEquals(amount, entry.getLong("amount"));
                assertEquals(first, KEY_TYPE.loadKeyFromTag(storedKey));
            }
        }
        assertTrue(foundFirst);
        assertEquals(amount + 7L, total);
    }

    private static IPatternDetails.IInput presence(AEKey key, long amount) throws Exception {
        var type = Class.forName("com.dishanhai.gt_shanhai.common.item.VirtualPatternEncodingHelper$PresenceInput");
        var constructor = type.getDeclaredConstructor(AEKey.class, long.class);
        constructor.setAccessible(true);
        return (IPatternDetails.IInput) constructor.newInstance(key, amount);
    }

    private static IPatternDetails.IInput input(AEKey key, long multiplier, long templateAmount) {
        return new IPatternDetails.IInput() {
            public GenericStack[] getPossibleInputs() { return new GenericStack[] {new GenericStack(key, templateAmount)}; }
            public long getMultiplier() { return multiplier; }
            public boolean isValid(AEKey candidate, Level level) { return key.equals(candidate); }
            public AEKey getRemainingKey(AEKey candidate) { return null; }
        };
    }

    private static IPatternDetails pattern(IPatternDetails.IInput[] inputs, GenericStack... outputs) {
        return new IPatternDetails() {
            public AEItemKey getDefinition() { throw new AssertionError("分帳不得讀取或替換樣板身份"); }
            public IInput[] getInputs() { return inputs; }
            public GenericStack[] getOutputs() { return outputs; }
        };
    }

    private static TaggedKey key(String kind, String variant) {
        CompoundTag payload = new CompoundTag();
        CompoundTag nested = new CompoundTag();
        nested.putString("variant", variant);
        nested.putLong("large", (long) Integer.MAX_VALUE + 1L);
        payload.put("nested", nested);
        return new TaggedKey(new ResourceLocation("gt_shanhai", kind), payload);
    }

    /** 真正 AEKey 子類；只替換需遊戲註冊的 item/fluid primary，NBT 身份與序列化保持完整。 */
    private static final class TaggedKey extends AEKey {
        private final ResourceLocation id;
        private final CompoundTag tag;
        private TaggedKey(ResourceLocation id, CompoundTag tag) { this.id = id; this.tag = tag.copy(); }
        public AEKeyType getType() { return KEY_TYPE; }
        public AEKey dropSecondary() { return new TaggedKey(id, new CompoundTag()); }
        public CompoundTag toTag() {
            var encoded = new CompoundTag();
            encoded.putString("id", id.toString());
            encoded.put("payload", tag.copy());
            return encoded;
        }
        public Object getPrimaryKey() { return id; }
        public ResourceLocation getId() { return id; }
        public void writeToPacket(FriendlyByteBuf buffer) { throw new UnsupportedOperationException(); }
        protected Component computeDisplayName() { throw new UnsupportedOperationException(); }
        public void addDrops(long amount, List<ItemStack> drops, Level level, BlockPos position) {
            throw new UnsupportedOperationException();
        }
        public boolean equals(Object object) {
            return object instanceof TaggedKey other && id.equals(other.id) && tag.equals(other.tag);
        }
        public int hashCode() { return Objects.hash(id, tag); }
    }

    private static final AEKeyType KEY_TYPE = new AEKeyType(
            new ResourceLocation("gt_shanhai", "cloud_input_test"), TaggedKey.class, null) {
        public AEKey readFromPacket(FriendlyByteBuf buffer) { throw new UnsupportedOperationException(); }
        public AEKey loadKeyFromTag(CompoundTag encoded) {
            return new TaggedKey(new ResourceLocation(encoded.getString("id")), encoded.getCompound("payload"));
        }
    };
}
