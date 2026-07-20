package com.dishanhai.gt_shanhai.common.item;

import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

public final class ShanhaiUltimateTerminalConfig {

    public static final String ROOT_KEY = "gt_shanhai_terminal";
    public static final int CONFIG_VERSION = 2;
    private static final String VERSION_KEY = "Version";
    private static final String UUID_KEY = "TerminalUuid";
    private static final String REPEAT_KEY = "RepeatCount";
    private static final String MIRROR_KEY = "MirrorMode";
    private static final String REPLACE_KEY = "ReplaceMode";
    private static final String AE_REQUEST_KEY = "AeRequestMode";
    private static final String NO_CHAMBER_KEY = "NoChamberMode";
    private static final String DISMANTLE_KEY = "DismantleMode";
    private static final String BOUND_AE_KEY = "BoundAE";
    private static final String REPLACEMENT_FAMILY_KEY = "ReplacementFamily";
    private static final String REPLACEMENT_TIER_KEY = "ReplacementTier";

    private ShanhaiUltimateTerminalConfig() {}

    public static CompoundTag get(ItemStack stack) {
        CompoundTag root = stack.getOrCreateTag();
        CompoundTag config = root.contains(ROOT_KEY, CompoundTag.TAG_COMPOUND)
                ? root.getCompound(ROOT_KEY) : new CompoundTag();
        config.putInt(VERSION_KEY, CONFIG_VERSION);
        if (!config.hasUUID(UUID_KEY)) {
            config.putUUID(UUID_KEY, UUID.randomUUID());
        }
        root.put(ROOT_KEY, config);
        return config;
    }

    public static UUID getTerminalUuid(ItemStack stack) {
        return get(stack).getUUID(UUID_KEY);
    }

    public static int getRepeatCount(ItemStack stack) {
        return Math.max(0, Math.min(648, get(stack).getInt(REPEAT_KEY)));
    }

    public static void setRepeatCount(ItemStack stack, int repeatCount) {
        get(stack).putInt(REPEAT_KEY, Math.max(0, Math.min(648, repeatCount)));
    }

    public static boolean isMirrored(ItemStack stack) {
        return get(stack).getBoolean(MIRROR_KEY);
    }

    public static void setMirrored(ItemStack stack, boolean mirrored) {
        get(stack).putBoolean(MIRROR_KEY, mirrored);
    }

    public static boolean isReplaceMode(ItemStack stack) {
        return get(stack).getBoolean(REPLACE_KEY);
    }

    public static void setReplaceMode(ItemStack stack, boolean replaceMode) {
        get(stack).putBoolean(REPLACE_KEY, replaceMode);
    }

    public static boolean isAeRequestMode(ItemStack stack) {
        return get(stack).getBoolean(AE_REQUEST_KEY);
    }

    public static void setAeRequestMode(ItemStack stack, boolean aeRequestMode) {
        get(stack).putBoolean(AE_REQUEST_KEY, aeRequestMode);
    }

    public static boolean isNoChamberMode(ItemStack stack) {
        CompoundTag config = get(stack);
        return !config.contains(NO_CHAMBER_KEY) || config.getBoolean(NO_CHAMBER_KEY);
    }

    public static void setNoChamberMode(ItemStack stack, boolean noChamberMode) {
        get(stack).putBoolean(NO_CHAMBER_KEY, noChamberMode);
    }

    public static boolean isDismantleMode(ItemStack stack) {
        return get(stack).getBoolean(DISMANTLE_KEY);
    }

    public static void setDismantleMode(ItemStack stack, boolean dismantleMode) {
        get(stack).putBoolean(DISMANTLE_KEY, dismantleMode);
    }

    public static String getReplacementFamily(ItemStack stack) {
        return get(stack).getString(REPLACEMENT_FAMILY_KEY);
    }

    public static int getReplacementTier(ItemStack stack) {
        return Math.max(0, get(stack).getInt(REPLACEMENT_TIER_KEY));
    }

    public static void setReplacement(ItemStack stack, String family, int tier) {
        CompoundTag config = get(stack);
        config.putString(REPLACEMENT_FAMILY_KEY, family == null ? "" : family);
        config.putInt(REPLACEMENT_TIER_KEY, Math.max(0, tier));
    }

    public static void setBoundAe(ItemStack stack, GlobalPos pos) {
        CompoundTag config = get(stack);
        if (pos == null) {
            config.remove(BOUND_AE_KEY);
            return;
        }
        CompoundTag bound = new CompoundTag();
        bound.putString("Dimension", pos.dimension().location().toString());
        bound.putLong("Pos", pos.pos().asLong());
        config.put(BOUND_AE_KEY, bound);
    }

    public static GlobalPos getBoundAe(ItemStack stack) {
        CompoundTag config = get(stack);
        if (!config.contains(BOUND_AE_KEY, CompoundTag.TAG_COMPOUND)) return null;
        CompoundTag bound = config.getCompound(BOUND_AE_KEY);
        ResourceLocation dimension = ResourceLocation.tryParse(bound.getString("Dimension"));
        if (dimension == null) return null;
        ResourceKey<Level> levelKey = ResourceKey.create(Registries.DIMENSION, dimension);
        return GlobalPos.of(levelKey, BlockPos.of(bound.getLong("Pos")));
    }
}
