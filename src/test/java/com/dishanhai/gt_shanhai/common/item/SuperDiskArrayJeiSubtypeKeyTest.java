package com.dishanhai.gt_shanhai.common.item;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.fail;

class SuperDiskArrayJeiSubtypeKeyTest {

    @Test
    void uuidLessTemplatesKeepDefaultEmptyAndChargedVariantsDistinct() {
        CompoundTag emptyTag = new CompoundTag();
        emptyTag.putDouble("internalCurrentPower", 0.0D);
        CompoundTag chargedTag = new CompoundTag();
        chargedTag.putDouble("internalCurrentPower", 20_000.0D);

        String defaultKey = subtypeKey(null);
        String emptyKey = subtypeKey(emptyTag);
        String chargedKey = subtypeKey(chargedTag);

        assertNotEquals(defaultKey, emptyKey);
        assertNotEquals(emptyKey, chargedKey);
        assertNotEquals(defaultKey, chargedKey);
    }

    @Test
    void ownedStacksRemainStableWhenOnlyPowerChanges() {
        UUID uuid = UUID.randomUUID();
        CompoundTag emptyTag = new CompoundTag();
        emptyTag.putUUID(SuperDiskArrayInventory.TAG_UUID, uuid);
        emptyTag.putDouble("internalCurrentPower", 0.0D);
        CompoundTag chargedTag = emptyTag.copy();
        chargedTag.putDouble("internalCurrentPower", 20_000.0D);

        assertEquals(subtypeKey(emptyTag), subtypeKey(chargedTag));
    }

    private static String subtypeKey(CompoundTag tag) {
        try {
            Method method = SuperDiskArrayInventory.class.getMethod("getJeiSubtypeKey", CompoundTag.class);
            return (String) method.invoke(null, tag);
        } catch (NoSuchMethodException e) {
            fail("缺少统一的 public static getJeiSubtypeKey(CompoundTag) 实现");
            return "";
        } catch (InvocationTargetException e) {
            throw new AssertionError("getJeiSubtypeKey 执行失败", e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("无法调用 getJeiSubtypeKey", e);
        }
    }
}
