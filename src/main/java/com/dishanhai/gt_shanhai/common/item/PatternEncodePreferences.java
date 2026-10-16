package com.dishanhai.gt_shanhai.common.item;

import com.dishanhai.gt_shanhai.GTDishanhaiMod;
import com.dishanhai.gt_shanhai.config.DShanhaiConfig;

/**
 * 样板编码的两个全局开关。终端按钮和快速编码上传读同一份配置。
 */
public final class PatternEncodePreferences {

    private PatternEncodePreferences() {
    }

    public static boolean writeRecipeId() {
        return read(DShanhaiConfig.COMMON.encodeWriteRecipeId, true);
    }

    public static void setWriteRecipeId(boolean enabled) {
        write(DShanhaiConfig.COMMON.encodeWriteRecipeId, enabled);
    }

    public static boolean writeVirtualProvider() {
        return read(DShanhaiConfig.COMMON.encodeWriteVirtualProvider, true);
    }

    public static void setWriteVirtualProvider(boolean enabled) {
        write(DShanhaiConfig.COMMON.encodeWriteVirtualProvider, enabled);
    }

    private static boolean read(net.minecraftforge.common.ForgeConfigSpec.BooleanValue value, boolean fallback) {
        if (value == null) return fallback;
        try {
            Boolean current = value.get();
            return current == null ? fallback : current;
        } catch (RuntimeException exception) {
            return fallback;
        }
    }

    private static void write(net.minecraftforge.common.ForgeConfigSpec.BooleanValue value, boolean enabled) {
        if (value == null) return;
        value.set(enabled);
        try {
            DShanhaiConfig.COMMON_SPEC.save();
        } catch (RuntimeException exception) {
            GTDishanhaiMod.LOGGER.warn("[样板编码] 开关已在本次运行生效，但配置文件没有写回", exception);
        }
    }
}
