package com.dishanhai.gt_shanhai.api;

/**
 * 温度绕过 ThreadLocal 标志。
 * 由 RecipeTemperatureBypassMixin 设置，CompoundTagBypassMixin 读取。
 *
 * 独立于 mixin 类之外，避免 Mixin 对非私有静态方法的限制。
 */
public class DShanhaiTempBypass {

    private static final ThreadLocal<Boolean> ACTIVE = ThreadLocal.withInitial(() -> false);
    private static final ThreadLocal<Integer> RECIPE_TEMP = ThreadLocal.withInitial(() -> 0);

    public static boolean isActive() {
        return ACTIVE.get();
    }

    public static void set(boolean value) {
        ACTIVE.set(value);
    }

    /** 当前配方要求的温度（供虚拟线圈使用） */
    public static int getRecipeTemp() {
        return RECIPE_TEMP.get();
    }

    public static void setRecipeTemp(int temp) {
        RECIPE_TEMP.set(temp);
    }
}
