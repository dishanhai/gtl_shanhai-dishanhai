package com.dishanhai.gt_shanhai.common.machine.part.cloud;

import net.minecraft.nbt.CompoundTag;
import java.util.Objects;
import java.util.UUID;

/** 山海：拆除時交接未解碼資料及待釋租身份，不解讀未知版本內容。 */
public record StellarCloudRecoveryState(UUID executor, CompoundTag pending) {
    public static final String ITEM_KEY = "gtShanhaiCloudRecovery";
    public StellarCloudRecoveryState {
        Objects.requireNonNull(executor);
        if (pending == null || pending.isEmpty()) throw new IllegalArgumentException("Missing cloud recovery state");
        pending = pending.copy();
    }
    @Override public CompoundTag pending() { return pending.copy(); }
    public CompoundTag save() {
        CompoundTag tag = new CompoundTag(); tag.putUUID("executor",executor); tag.put("pending",pending.copy()); return tag;
    }
    public static StellarCloudRecoveryState load(CompoundTag tag) {
        if (!tag.hasUUID("executor")) throw new IllegalArgumentException("Missing cloud recovery executor");
        return new StellarCloudRecoveryState(tag.getUUID("executor"),tag.getCompound("pending"));
    }
}
