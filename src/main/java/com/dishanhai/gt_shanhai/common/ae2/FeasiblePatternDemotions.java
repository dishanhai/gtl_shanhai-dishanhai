package com.dishanhai.gt_shanhai.common.ae2;

import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.stacks.AEKey;

import java.util.HashSet;
import java.util.Set;

/**
 * 把「这次计算里哪些产物发生过回环让位」留在计划对象上，确认界面直接读。
 *
 * <p>计算线程和打开界面不是同一刻。标记写在返回的 {@code CraftingPlan} 上，
 * 不另做弱键表，避免计划和标记对不上。
 */
public final class FeasiblePatternDemotions {

    private static final ThreadLocal<Set<AEKey>> CURRENT = new ThreadLocal<>();

    private FeasiblePatternDemotions() {}

    public static void beginAttempt() {
        CURRENT.remove();
    }

    public static void note(AEKey key) {
        if (key == null) return;
        Set<AEKey> noted = CURRENT.get();
        if (noted == null) {
            noted = new HashSet<>();
            CURRENT.set(noted);
        }
        noted.add(key);
    }

    public static void attach(ICraftingPlan plan) {
        Set<AEKey> noted = CURRENT.get();
        CURRENT.remove();
        if (!(plan instanceof CraftingPlanCycleDemotionAccess access)) return;
        if (noted == null || noted.isEmpty()) {
            access.gtShanhai$setDemotedOutputs(Set.of());
            return;
        }
        access.gtShanhai$setDemotedOutputs(Set.copyOf(noted));
    }

    public static boolean wasDemoted(ICraftingPlan plan, AEKey key) {
        if (!(plan instanceof CraftingPlanCycleDemotionAccess access) || key == null) return false;
        return access.gtShanhai$getDemotedOutputs().contains(key);
    }
}
