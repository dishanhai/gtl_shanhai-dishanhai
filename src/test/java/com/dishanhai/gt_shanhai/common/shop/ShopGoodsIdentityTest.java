package com.dishanhai.gt_shanhai.common.shop;

import com.dishanhai.gt_shanhai.common.item.SuperDiskArrayInventory;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class ShopGoodsIdentityTest {

    @Test
    void sameItemIdWithDifferentNbtDoesNotCollapse() {
        CompoundTag logs = disk("原木磁盘阵列", "minecraft:oak_log");
        CompoundTag fish = disk("渔场磁盘阵列", "minecraft:cod");

        assertNotEquals(ShopGoodsIdentity.key(SDA, logs), ShopGoodsIdentity.key(SDA, fish));
        assertNotEquals(ShopGoodsIdentity.key("minecraft:stone", tag("Configuration", 1)),
                ShopGoodsIdentity.key("minecraft:stone", tag("Configuration", 2)));
    }

    @Test
    void diskRuntimeTagsDoNotChangeTheShopIdentity() {
        CompoundTag template = disk("编程电路存储阵列", "gtceu:programmed_circuit");
        CompoundTag used = template.copy();
        used.putDouble("internalCurrentPower", 0.0D);
        used.putUUID(SuperDiskArrayInventory.TAG_UUID, UUID.randomUUID());
        used.putBoolean(SuperDiskArrayInventory.TAG_RUNTIME_UUID, true);
        used.putInt(SuperDiskArrayInventory.TAG_TYPES, 4);
        used.getCompound("display").put("Lore", new ListTag());

        assertEquals(ShopGoodsIdentity.key(SDA, template), ShopGoodsIdentity.key(SDA, used));
    }

    @Test
    void emptyNbtStaysOnTheItemId() {
        assertEquals(ShopGoodsIdentity.key("minecraft:stone", null),
                ShopGoodsIdentity.key(" Minecraft:Stone ", new CompoundTag()));
    }

    private static final String SDA = "gt_shanhai:super_disk_array";

    private static CompoundTag disk(String name, String contentId) {
        CompoundTag root = new CompoundTag();
        CompoundTag display = new CompoundTag();
        display.putString("Name", name);
        root.put("display", display);
        CompoundTag key = new CompoundTag();
        key.putString("id", "expatternprovider:infinity_cell");
        CompoundTag record = new CompoundTag();
        record.putString("id", contentId);
        CompoundTag inner = new CompoundTag();
        inner.put("record", record);
        key.put("tag", inner);
        ListTag keys = new ListTag();
        keys.add(key);
        root.put("keys", keys);
        root.putDouble("internalCurrentPower", 20000.0D);
        root.putUUID(SuperDiskArrayInventory.TAG_UUID, UUID.nameUUIDFromBytes(name.getBytes()));
        return root;
    }

    private static CompoundTag tag(String key, int value) {
        CompoundTag tag = new CompoundTag();
        tag.putInt(key, value);
        return tag;
    }
}
