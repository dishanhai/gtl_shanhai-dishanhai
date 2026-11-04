package com.dishanhai.gt_shanhai.common.machine.primordial.module.forge;

import java.util.List;
import java.util.Locale;

import com.dishanhai.gt_shanhai.common.machine.part.StellarPatternWarningText;

import net.minecraft.network.chat.Component;

import org.jetbrains.annotations.Nullable;

/**
 * 铸币工厂的货币权限与并行折算。阶段顺序与商店 {@code currency_stages.json} 的电压阶一致，
 * 第 1 阶对应入门物质模块。没有写阶段的产出不锁。
 */
public final class CoinForgeMintGate {

    static final List<String> TIERS = List.of(
            "ulv", "lv", "mv", "hv", "ev", "iv", "luv", "zpm",
            "uhv", "uev", "uiv", "uxv", "opv", "max");

    private static final int TIER_COUNT = 14;

    private CoinForgeMintGate() {}

    /** 商店阶段名对应的物质模块等级。空阶段不锁；表外阶段要创始现实修改模块。 */
    public static int requiredModuleLevel(String tier) {
        if (tier == null || tier.isBlank()) return 0;
        int index = TIERS.indexOf(tier.trim().toLowerCase(Locale.ROOT));
        return index < 0 ? 17 : index + 1;
    }

    public static boolean allows(int moduleLevel, String tier) {
        return allowsRequiredLevel(moduleLevel, requiredModuleLevel(tier));
    }

    public static boolean allowsRequiredLevel(int moduleLevel, int required) {
        return required == 0 || (moduleLevel > 0 && moduleLevel >= required);
    }

    /** 告警與真正的鑄幣權限使用同一等級判斷；未分階段的產物不誤報。 */
    public static @Nullable Component restrictionComponent(int moduleLevel, int required) {
        if (allowsRequiredLevel(moduleLevel, required)) return null;
        return StellarPatternWarningText.line("mint_locked_detail", Math.max(0, moduleLevel),
                permissionComponent(moduleLevel), required);
    }

    public static String restrictionText(int moduleLevel, int required) {
        Component component = restrictionComponent(moduleLevel, required);
        return component == null ? "" : component.getString();
    }

    /** 正常并行的 1/4，向下取整；正数并行至少保留 1。 */
    public static long quarterParallel(long normalParallel) {
        if (normalParallel <= 0L) return 0L;
        long quarter = normalParallel / 4L;
        return quarter <= 0L ? 1L : quarter;
    }

    public static Component permissionComponent(int moduleLevel) {
        if (moduleLevel <= 0) return StellarPatternWarningText.line("mint_locked");
        if (moduleLevel >= TIERS.size()) return StellarPatternWarningText.line("mint_all");
        int tier = Math.min(Math.max(moduleLevel, 1), TIER_COUNT) - 1;
        return StellarPatternWarningText.line("mint_upto", StellarPatternWarningText.line("mint_tier_" + tier));
    }

    public static String permissionText(int moduleLevel) {
        return permissionComponent(moduleLevel).getString();
    }
}
