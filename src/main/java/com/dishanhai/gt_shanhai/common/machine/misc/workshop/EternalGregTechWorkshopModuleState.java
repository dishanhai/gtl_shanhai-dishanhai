package com.dishanhai.gt_shanhai.common.machine.misc.workshop;

import net.minecraft.nbt.CompoundTag;

import java.util.UUID;

/** 单个永恒格雷工坊模块的持久化状态。 */
public final class EternalGregTechWorkshopModuleState {

    private static final String KEY_CONNECTED = "connected";
    private static final String KEY_FORMED = "formed";
    private static final String KEY_LEVEL = "level";
    private static final String KEY_OWNER = "owner";
    private static final String KEY_MAX_USE_EUT = "maxUseEUt";
    private static final String KEY_EUT_DISCOUNT = "eutDiscount";
    private static final String KEY_DURATION_MODIFIER = "durationModifier";
    private static final String KEY_MAX_PARALLEL = "maxParallel";
    private static final String KEY_HEAT = "heat";
    private static final String KEY_WORKING = "working";
    private static final String KEY_LAST_SEEN_GAME_TIME = "lastSeenGameTime";
    private static final int MAX_SAFE_PARALLEL = Integer.MAX_VALUE - 1;

    private boolean connected;
    private boolean formed;
    private int level;
    private UUID ownerUUID;
    private long maxUseEUt;
    private double eutDiscount = 1.0D;
    private double durationModifier = 1.0D;
    private int maxParallel = 1;
    private int heat;
    private boolean working;
    private long lastSeenGameTime;

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putBoolean(KEY_CONNECTED, connected);
        tag.putBoolean(KEY_FORMED, formed);
        tag.putInt(KEY_LEVEL, level);
        if (ownerUUID != null) {
            tag.putUUID(KEY_OWNER, ownerUUID);
        }
        tag.putLong(KEY_MAX_USE_EUT, maxUseEUt);
        tag.putDouble(KEY_EUT_DISCOUNT, eutDiscount);
        tag.putDouble(KEY_DURATION_MODIFIER, durationModifier);
        tag.putInt(KEY_MAX_PARALLEL, maxParallel);
        tag.putInt(KEY_HEAT, heat);
        tag.putBoolean(KEY_WORKING, working);
        tag.putLong(KEY_LAST_SEEN_GAME_TIME, lastSeenGameTime);
        return tag;
    }

    public void load(CompoundTag tag) {
        if (tag.contains(KEY_CONNECTED)) connected = tag.getBoolean(KEY_CONNECTED);
        if (tag.contains(KEY_FORMED)) formed = tag.getBoolean(KEY_FORMED);
        if (tag.contains(KEY_LEVEL)) level = Math.max(0, tag.getInt(KEY_LEVEL));
        if (tag.hasUUID(KEY_OWNER)) ownerUUID = tag.getUUID(KEY_OWNER);
        if (tag.contains(KEY_MAX_USE_EUT)) maxUseEUt = tag.getLong(KEY_MAX_USE_EUT);
        if (tag.contains(KEY_EUT_DISCOUNT)) eutDiscount = tag.getDouble(KEY_EUT_DISCOUNT);
        if (tag.contains(KEY_DURATION_MODIFIER)) durationModifier = tag.getDouble(KEY_DURATION_MODIFIER);
        if (tag.contains(KEY_MAX_PARALLEL)) maxParallel = sanitizeParallel(tag.getInt(KEY_MAX_PARALLEL));
        if (tag.contains(KEY_HEAT)) heat = Math.max(0, tag.getInt(KEY_HEAT));
        if (tag.contains(KEY_WORKING)) working = tag.getBoolean(KEY_WORKING);
        if (tag.contains(KEY_LAST_SEEN_GAME_TIME)) lastSeenGameTime = Math.max(0L, tag.getLong(KEY_LAST_SEEN_GAME_TIME));
    }

    public void copyFrom(EternalGregTechWorkshopModuleState other) {
        if (other == null) {
            clear();
            return;
        }
        connected = other.connected;
        formed = other.formed;
        level = other.level;
        ownerUUID = other.ownerUUID;
        maxUseEUt = other.maxUseEUt;
        eutDiscount = other.eutDiscount;
        durationModifier = other.durationModifier;
        maxParallel = sanitizeParallel(other.maxParallel);
        heat = other.heat;
        working = other.working;
        lastSeenGameTime = other.lastSeenGameTime;
    }

    public void clear() {
        connected = false;
        formed = false;
        level = 0;
        ownerUUID = null;
        maxUseEUt = 0L;
        eutDiscount = 1.0D;
        durationModifier = 1.0D;
        maxParallel = 1;
        heat = 0;
        working = false;
        lastSeenGameTime = 0L;
    }

    public boolean isConnected() {
        return connected;
    }

    public void setConnected(boolean connected) {
        this.connected = connected;
    }

    public boolean isFormed() {
        return formed;
    }

    public void setFormed(boolean formed) {
        this.formed = formed;
    }

    public int getLevel() {
        return level;
    }

    public void setLevel(int level) {
        this.level = Math.max(0, level);
    }

    public UUID getOwnerUUID() {
        return ownerUUID;
    }

    public void setOwnerUUID(UUID ownerUUID) {
        this.ownerUUID = ownerUUID;
    }

    public long getMaxUseEUt() {
        return maxUseEUt;
    }

    public void setMaxUseEUt(long maxUseEUt) {
        this.maxUseEUt = Math.max(0L, maxUseEUt);
    }

    public double getEUtDiscount() {
        return eutDiscount;
    }

    public void setEUtDiscount(double eutDiscount) {
        this.eutDiscount = Math.max(0.0D, eutDiscount);
    }

    public double getDurationModifier() {
        return durationModifier;
    }

    public void setDurationModifier(double durationModifier) {
        this.durationModifier = Math.max(0.0D, durationModifier);
    }

    public int getMaxParallel() {
        return maxParallel;
    }

    public void setMaxParallel(int maxParallel) {
        this.maxParallel = sanitizeParallel(maxParallel);
    }

    public int getHeat() {
        return heat;
    }

    public void setHeat(int heat) {
        this.heat = Math.max(0, heat);
    }

    public boolean isWorking() {
        return working;
    }

    public void setWorking(boolean working) {
        this.working = working;
    }

    public long getLastSeenGameTime() {
        return lastSeenGameTime;
    }

    public void setLastSeenGameTime(long lastSeenGameTime) {
        this.lastSeenGameTime = Math.max(0L, lastSeenGameTime);
    }

    private static int sanitizeParallel(int value) {
        if (value <= 0) {
            return 1;
        }
        if (value >= Integer.MAX_VALUE) {
            return 1;
        }
        return Math.min(value, MAX_SAFE_PARALLEL);
    }
}
