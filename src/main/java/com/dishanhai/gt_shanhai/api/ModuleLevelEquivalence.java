package com.dishanhai.gt_shanhai.api;

/** 物质模块配方条件的等级差与等效数量规则。 */
public final class ModuleLevelEquivalence {

    private ModuleLevelEquivalence() {}

    /** 等级差 0~3 时逐级翻倍；高出要求 4 级或以上时直接视为无限等效量。 */
    public static long calculateEquivalentCount(int installedLevel, int installedCount, int requiredModuleLevel) {
        if (installedLevel <= 0 || installedCount <= 0 || requiredModuleLevel <= 0
                || installedLevel < requiredModuleLevel) {
            return 0L;
        }
        int levelDifference = installedLevel - requiredModuleLevel;
        if (levelDifference >= 4) {
            return Long.MAX_VALUE;
        }
        long multiplier = 1L << levelDifference;
        return installedCount > Long.MAX_VALUE / multiplier
                ? Long.MAX_VALUE : installedCount * multiplier;
    }

    public static boolean isRequirementSatisfied(int installedLevel, int installedCount,
                                                  int requiredModuleLevel, int requiredCount) {
        if (requiredCount <= 0) return true;
        return calculateEquivalentCount(installedLevel, installedCount, requiredModuleLevel) >= requiredCount;
    }
}
