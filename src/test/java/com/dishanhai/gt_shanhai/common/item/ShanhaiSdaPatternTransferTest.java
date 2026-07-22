package com.dishanhai.gt_shanhai.common.item;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import appeng.api.stacks.AEKey;
import appeng.api.stacks.AEKeyType;
import appeng.api.storage.cells.ISaveProvider;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import org.junit.jupiter.api.Test;

import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.math.BigInteger;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

class ShanhaiSdaPatternTransferTest {

    private static final Unsafe UNSAFE = findUnsafe();

    @Test
    void transfersOnlyAcceptedKeysAndLeavesOverflowInSda() throws Exception {
        TestKey patternKey = new TestKey("pattern", "gtceu:chaos_alchemy");
        TestKey ordinaryKey = new TestKey("ordinary", "");
        Map<AEKey, BigInteger> amounts = new HashMap<>();
        amounts.put(patternKey, BigInteger.valueOf(2));
        amounts.put(ordinaryKey, BigInteger.valueOf(5));
        SuperDiskArrayInventory source = newInventory(amounts, BigInteger.valueOf(7));
        CapturingTarget target = new CapturingTarget(patternKey, 1, true);

        ShanhaiSdaPatternTransfer.TransferResult result =
                ShanhaiSdaPatternTransfer.transfer(source, target);

        assertEquals(1, result.transferred());
        assertEquals(BigInteger.ONE, amounts.get(patternKey));
        assertEquals(BigInteger.valueOf(5), amounts.get(ordinaryKey));
        assertEquals(BigInteger.valueOf(6), readTotal(source));
        assertSame(patternKey, target.lastInserted,
                "转移必须交付原始 AEKey 身份，不能重建并丢失样板元数据");
        assertEquals("gtceu:chaos_alchemy",
                target.lastInserted.toTag().getString(PatternRecipeTypeHelper.TAG_RECIPE_TYPE));
    }

    @Test
    void restoresSdaWhenTargetRejectsAfterSuccessfulSimulation() throws Exception {
        TestKey patternKey = new TestKey("pattern", "gtceu:chaos_alchemy");
        Map<AEKey, BigInteger> amounts = new HashMap<>();
        amounts.put(patternKey, BigInteger.ONE);
        SuperDiskArrayInventory source = newInventory(amounts, BigInteger.ONE);
        CapturingTarget target = new CapturingTarget(patternKey, 1, false);

        ShanhaiSdaPatternTransfer.TransferResult result =
                ShanhaiSdaPatternTransfer.transfer(source, target);

        assertEquals(0, result.transferred());
        assertEquals(1, result.failed());
        assertEquals(BigInteger.ONE, amounts.get(patternKey));
        assertEquals(BigInteger.ONE, readTotal(source));
    }

    @Test
    void flushesSdaSaveProviderOnceForBatchTransfer() throws Exception {
        TestKey patternKey = new TestKey("pattern", "gtceu:chaos_alchemy");
        Map<AEKey, BigInteger> amounts = new HashMap<>();
        amounts.put(patternKey, BigInteger.valueOf(2));
        CountingSaveProvider saveProvider = new CountingSaveProvider();
        SuperDiskArrayInventory source = newInventory(amounts, BigInteger.valueOf(2), saveProvider);
        CapturingTarget target = new CapturingTarget(patternKey, 2, true);

        ShanhaiSdaPatternTransfer.TransferResult result =
                ShanhaiSdaPatternTransfer.transfer(source, target);

        assertEquals(2, result.transferred());
        assertEquals(1, saveProvider.saveCalls);
    }

    private static SuperDiskArrayInventory newInventory(
            Map<AEKey, BigInteger> amounts, BigInteger total) throws ReflectiveOperationException {
        return newInventory(amounts, total, (ISaveProvider) () -> {});
    }

    private static SuperDiskArrayInventory newInventory(
            Map<AEKey, BigInteger> amounts, BigInteger total, ISaveProvider saveProvider)
            throws ReflectiveOperationException {
        SuperDiskArrayInventory inventory =
                (SuperDiskArrayInventory) UNSAFE.allocateInstance(SuperDiskArrayInventory.class);
        writeObjectField(inventory, "amounts", amounts);
        writeObjectField(inventory, "total", total);
        writeObjectField(inventory, "saveProvider", saveProvider);
        writeBooleanField(inventory, "persisted", true);
        return inventory;
    }

    private static BigInteger readTotal(SuperDiskArrayInventory inventory)
            throws ReflectiveOperationException {
        Field field = SuperDiskArrayInventory.class.getDeclaredField("total");
        field.setAccessible(true);
        return (BigInteger) field.get(inventory);
    }

    private static void writeObjectField(Object target, String name, Object value)
            throws ReflectiveOperationException {
        Field field = SuperDiskArrayInventory.class.getDeclaredField(name);
        UNSAFE.putObject(target, UNSAFE.objectFieldOffset(field), value);
    }

    private static void writeBooleanField(Object target, String name, boolean value)
            throws ReflectiveOperationException {
        Field field = SuperDiskArrayInventory.class.getDeclaredField(name);
        UNSAFE.putBoolean(target, UNSAFE.objectFieldOffset(field), value);
    }

    private static Unsafe findUnsafe() {
        try {
            Field field = Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            return (Unsafe) field.get(null);
        } catch (ReflectiveOperationException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static final class CountingSaveProvider implements ISaveProvider {
        private int saveCalls;

        @Override
        public void saveChanges() {
            saveCalls++;
        }
    }

    private static final class CapturingTarget
            implements ShanhaiSdaPatternTransfer.PatternTransferTarget {
        private final AEKey acceptedKey;
        private final int capacity;
        private final boolean commitSucceeds;
        private int inserted;
        private AEKey lastInserted;

        private CapturingTarget(AEKey acceptedKey, int capacity, boolean commitSucceeds) {
            this.acceptedKey = acceptedKey;
            this.capacity = capacity;
            this.commitSucceeds = commitSucceeds;
        }

        @Override
        public ShanhaiSdaPatternTransfer.TransferDecision simulate(AEKey key) {
            if (key != acceptedKey) return ShanhaiSdaPatternTransfer.TransferDecision.IGNORE;
            return inserted < capacity
                    ? ShanhaiSdaPatternTransfer.TransferDecision.ACCEPT
                    : ShanhaiSdaPatternTransfer.TransferDecision.REJECT;
        }

        @Override
        public boolean insert(AEKey key) {
            if (!commitSucceeds) return false;
            lastInserted = key;
            inserted++;
            return true;
        }
    }

    private static final class TestKey extends AEKey {
        private final String id;
        private final CompoundTag metadata;

        private TestKey(String id, String recipeType) {
            this.id = id;
            this.metadata = new CompoundTag();
            if (!recipeType.isEmpty()) {
                metadata.putString(PatternRecipeTypeHelper.TAG_RECIPE_TYPE, recipeType);
            }
        }

        @Override
        public AEKeyType getType() {
            return null;
        }

        @Override
        public AEKey dropSecondary() {
            return this;
        }

        @Override
        public CompoundTag toTag() {
            return metadata.copy();
        }

        @Override
        public Object getPrimaryKey() {
            return this;
        }

        @Override
        public ResourceLocation getId() {
            return new ResourceLocation("gt_shanhai", id);
        }

        @Override
        public void writeToPacket(FriendlyByteBuf output) {}

        @Override
        protected Component computeDisplayName() {
            return Component.literal(id);
        }

        @Override
        public void addDrops(long amount, List<ItemStack> drops, Level level, BlockPos pos) {}
    }
}
