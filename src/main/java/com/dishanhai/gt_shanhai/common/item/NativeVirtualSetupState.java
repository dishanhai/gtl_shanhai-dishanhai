package com.dishanhai.gt_shanhai.common.item;

import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.GTRecipeType;

/**
 * 原生虚拟配方执行的跨类状态标记。
 * 非 mixin 类，可安全持有 static 字段。
 *
 * <p>用途：仅在作弊破限配置开启时，NativeVirtualFindHandleRecipeMixin 在调用 setupRecipe 之前
 * 通过 beginVirtualExecution() 激活标记；NativeVirtualBeforeWorkingBypassMixin
 * 检测到此标记后对 beforeWorking 直接返回 true。正常模式不设置标记，保留宿主机器检查。
 */
public final class NativeVirtualSetupState {

    private NativeVirtualSetupState() {}

    /** 每线程执行标记，防止并发搜索互相干扰 */
    private static final ThreadLocal<Boolean> VIRTUAL_EXECUTION = ThreadLocal.withInitial(() -> false);

    /**
     * 宿主拥有、但当前没选中的配方类型。只在这一轮 setup 期间让 {@code getRecipeType()}
     * 回报该类型，不改持久化的 activeRecipeType，避免界面来回跳。
     */
    private static final ThreadLocal<GTRecipeType> OWNED_INACTIVE_TYPE = new ThreadLocal<>();

    /**
     * 正在用宿主自有准入逻辑尝试的那条未选中样板。
     * RetryableRecipeLogic 用引用相等拒绝当前模式以外的配方，对照这条样板放行。
     */
    private static final ThreadLocal<GTRecipe> OWNED_INACTIVE_CANDIDATE = new ThreadLocal<>();

    public static void beginVirtualExecution() {
        VIRTUAL_EXECUTION.set(true);
    }

    public static void endVirtualExecution() {
        VIRTUAL_EXECUTION.set(false);
    }

    public static boolean isVirtualExecution() {
        return VIRTUAL_EXECUTION.get();
    }

    public static void beginOwnedInactiveType(GTRecipeType type) {
        if (type == null) {
            OWNED_INACTIVE_TYPE.remove();
            return;
        }
        OWNED_INACTIVE_TYPE.set(type);
    }

    public static void endOwnedInactiveType() {
        OWNED_INACTIVE_TYPE.remove();
    }

    public static GTRecipeType ownedInactiveType() {
        return OWNED_INACTIVE_TYPE.get();
    }

    public static void beginOwnedInactiveCandidate(GTRecipe recipe) {
        if (recipe == null) {
            OWNED_INACTIVE_CANDIDATE.remove();
            return;
        }
        OWNED_INACTIVE_CANDIDATE.set(recipe);
    }

    public static void endOwnedInactiveCandidate() {
        OWNED_INACTIVE_CANDIDATE.remove();
    }

    public static GTRecipe ownedInactiveCandidate() {
        return OWNED_INACTIVE_CANDIDATE.get();
    }
}
