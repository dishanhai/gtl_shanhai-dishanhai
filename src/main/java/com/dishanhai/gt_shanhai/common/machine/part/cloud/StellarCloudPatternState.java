package com.dishanhai.gt_shanhai.common.machine.part.cloud;

import net.minecraft.nbt.CompoundTag;
import java.util.Objects;
import java.util.UUID;

/** 山海：付款時的有效樣板定義與路由代次，不依重載當下的宿主倍率重算。 */
public record StellarCloudPatternState(UUID generation, long fingerprint, CompoundTag definition) {
    public StellarCloudPatternState {
        Objects.requireNonNull(generation);
        if (definition == null || definition.isEmpty()) throw new IllegalArgumentException("Missing cloud execution definition");
        definition = definition.copy();
    }
    @Override public CompoundTag definition() { return definition.copy(); }
    public CompoundTag save() {
        CompoundTag tag = new CompoundTag(); tag.putUUID("generation", generation);
        tag.putLong("fingerprint", fingerprint); tag.put("definition", definition.copy()); return tag;
    }
    public static StellarCloudPatternState load(CompoundTag tag) {
        if (!tag.hasUUID("generation")) throw new IllegalArgumentException("Missing cloud execution identity");
        return new StellarCloudPatternState(tag.getUUID("generation"), tag.getLong("fingerprint"), tag.getCompound("definition"));
    }
}
