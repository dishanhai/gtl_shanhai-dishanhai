package com.dishanhai.gt_shanhai.common.machine.part.cloud;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** 山海：庫原件的唯讀視圖與權威租約；執行材料仍由星律保存。 */
public interface StellarCloudSource {
    record Entry(UUID id, ItemStack original, CompoundTag configuration) {}
    String sourceId();
    long revision();
    List<Entry> candidates(Set<String> recipeTypes);
    boolean claim(UUID executorId, UUID entryId);
    boolean owns(UUID executorId, UUID entryId);
    void release(UUID executorId, UUID entryId);
    boolean available();
    default CompoundTag saveBinding() { return new CompoundTag(); }
    default void loadBinding(CompoundTag binding) {}
    default void unload() {}
    /** 呼叫端先完成所有材料的安全收尾，才能刪除執行端。 */
    default void removed() { unload(); }
}
