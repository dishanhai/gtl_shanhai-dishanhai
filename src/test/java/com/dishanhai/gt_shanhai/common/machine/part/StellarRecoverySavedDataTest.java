package com.dishanhai.gt_shanhai.common.machine.part;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.assertEquals;

class StellarRecoverySavedDataTest {
    @Test void pendingReceiptSurvivesSaveAndReload() {
        UUID id = UUID.randomUUID();
        CompoundTag receipt = new CompoundTag();
        receipt.putUUID("id", id);
        receipt.putString("dimension", "minecraft:overworld");
        receipt.putLong("position", 42L);
        CompoundTag item = new CompoundTag();
        item.putString("id", "gt_shanhai:super_disk_array");
        receipt.put("item", item);
        ListTag entries = new ListTag();
        entries.add(receipt);
        CompoundTag input = new CompoundTag();
        input.put("pending", entries);

        CompoundTag saved = StellarRecoverySavedData.load(input).save(new CompoundTag());
        assertEquals(receipt, saved.getList("pending", Tag.TAG_COMPOUND).getCompound(0));
    }
}
