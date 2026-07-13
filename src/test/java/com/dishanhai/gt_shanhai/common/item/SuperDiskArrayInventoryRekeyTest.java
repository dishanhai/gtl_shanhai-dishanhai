package com.dishanhai.gt_shanhai.common.item;

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
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigInteger;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class SuperDiskArrayInventoryRekeyTest {

    private static final Unsafe UNSAFE = findUnsafe();

    @Test
    void successfulReplacementMovesExactlyOneStoredItemAndNotifiesOwnerOnce() throws Exception {
        TestKey oldKey = new TestKey("old");
        TestKey newKey = new TestKey("new");
        Map<AEKey, BigInteger> amounts = new HashMap<>();
        amounts.put(oldKey, BigInteger.valueOf(3));
        amounts.put(newKey, BigInteger.valueOf(4));
        AtomicInteger ownerNotifications = new AtomicInteger();
        SuperDiskArrayInventory inventory = newInventory(
                amounts, BigInteger.valueOf(7), ownerNotifications::incrementAndGet);

        boolean replaced = invokeReplaceOneStoredKey(inventory, oldKey, newKey);

        assertTrue(replaced);
        assertEquals(BigInteger.valueOf(2), amounts.get(oldKey));
        assertEquals(BigInteger.valueOf(5), amounts.get(newKey));
        assertEquals(2, amounts.size());
        assertEquals(BigInteger.valueOf(7), readTotal(inventory),
                "替换 key 只能迁移一个计数，不能改变磁盘总量");
        assertEquals(1, ownerNotifications.get(),
                "一次成功替换只能通知宿主一次");
    }

    @Test
    void missingOldKeyLeavesStorageAndOwnerUnchanged() throws Exception {
        TestKey storedKey = new TestKey("stored");
        TestKey missingKey = new TestKey("missing");
        TestKey newKey = new TestKey("new");
        Map<AEKey, BigInteger> amounts = new HashMap<>();
        amounts.put(storedKey, BigInteger.valueOf(6));
        Map<AEKey, BigInteger> before = new HashMap<>(amounts);
        AtomicInteger ownerNotifications = new AtomicInteger();
        SuperDiskArrayInventory inventory = newInventory(
                amounts, BigInteger.valueOf(6), ownerNotifications::incrementAndGet);

        boolean replaced = invokeReplaceOneStoredKey(inventory, missingKey, newKey);

        assertFalse(replaced);
        assertEquals(before, amounts);
        assertEquals(BigInteger.valueOf(6), readTotal(inventory));
        assertEquals(0, ownerNotifications.get());
    }

    private static SuperDiskArrayInventory newInventory(
            Map<AEKey, BigInteger> amounts, BigInteger total, ISaveProvider saveProvider)
            throws InstantiationException, ReflectiveOperationException {
        SuperDiskArrayInventory inventory =
                (SuperDiskArrayInventory) UNSAFE.allocateInstance(SuperDiskArrayInventory.class);
        writeObjectField(inventory, "amounts", amounts);
        writeObjectField(inventory, "total", total);
        writeObjectField(inventory, "saveProvider", saveProvider);
        writeBooleanField(inventory, "persisted", true);
        return inventory;
    }

    private static boolean invokeReplaceOneStoredKey(
            SuperDiskArrayInventory inventory, AEKey oldKey, AEKey newKey) {
        try {
            Method method = SuperDiskArrayInventory.class.getMethod(
                    "replaceOneStoredKey", AEKey.class, AEKey.class);
            return (boolean) method.invoke(inventory, oldKey, newKey);
        } catch (NoSuchMethodException e) {
            fail("缺少 public boolean replaceOneStoredKey(AEKey oldKey, AEKey newKey)");
            return false;
        } catch (InvocationTargetException e) {
            throw new AssertionError("replaceOneStoredKey 执行失败", e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("无法调用 replaceOneStoredKey", e);
        }
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
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static final class TestKey extends AEKey {
        private final String id;

        private TestKey(String id) {
            this.id = id;
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
            return new CompoundTag();
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
