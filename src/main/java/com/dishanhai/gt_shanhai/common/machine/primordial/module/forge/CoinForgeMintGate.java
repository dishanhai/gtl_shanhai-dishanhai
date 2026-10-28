package com.dishanhai.gt_shanhai.common.machine.primordial.module.forge;

import java.util.List;
import java.util.Locale;

/**
 * 铸币工厂的货币权限与并行折算。阶段顺序与商店 {@code currency_stages.json} 的电压阶一致，
 * 第 1 阶对应入门物质模块。没有写阶段的产出不锁。
 */
public final class CoinForgeMintGate {

    static final List<String> TIERS = List.of(
            "ulv", "lv", "mv", "hv", "ev", "iv", "luv", "zpm",
            "uhv", "uev", "uiv", "uxv", "opv", "max");

    private static final List<String> LABELS = List.of(
            "铜币",
            "白铜币",
            "银币",
            "金币",
            "铂币",
            "锇币",
            "硅岩币",
            "中子合金币",
            "衰变铼币",
            "振动、时空、中子币",
            "二级币",
            "无尽、超维币",
            "混沌至永恒币",
            "星体、星门币");

    private CoinForgeMintGate() {}

    /** 商店阶段名对应的物质模块等级。空阶段不锁；表外阶段要创始现实修改模块。 */
    public static int requiredModuleLevel(String tier) {
        if (tier == null || tier.isBlank()) return 0;
        int index = TIERS.indexOf(tier.trim().toLowerCase(Locale.ROOT));
        return index < 0 ? 17 : index + 1;
    }

    public static boolean allows(int moduleLevel, String tier) {
        int required = requiredModuleLevel(tier);
        return required == 0 || (moduleLevel > 0 && moduleLevel >= required);
    }

    /** 正常并行的 1/4，向下取整；正数并行至少保留 1。 */
    public static long quarterParallel(long normalParallel) {
        if (normalParallel <= 0L) return 0L;
        long quarter = normalParallel / 4L;
        return quarter <= 0L ? 1L : quarter;
    }

    public static String permissionText(int moduleLevel) {
        if (moduleLevel <= 0) return "未解锁，需搭载入门物质模块";
        if (moduleLevel >= TIERS.size()) return "全部货币";
        return LABELS.get(moduleLevel - 1) + "及以下";
    }
}
