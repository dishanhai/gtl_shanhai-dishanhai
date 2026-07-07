package com.dishanhai.gt_shanhai.common.item;

/**
 * 原生虚拟配方执行的跨类状态标记。
 * 非 mixin 类，可安全持有 static 字段。
 *
 * <p>用途：NativeVirtualFindHandleRecipeMixin 在调用 setupRecipe 之前
 * 通过 beginVirtualExecution() 激活标记；NativeVirtualBeforeWorkingBypassMixin
 * 检测到此标记后对 beforeWorking 直接返回 true，使跨类型配方不被宿主机器模式拒绝。
 */
public final class NativeVirtualSetupState {

    private NativeVirtualSetupState() {}

    /** 每线程执行标记，防止并发搜索互相干扰 */
    private static final ThreadLocal<Boolean> VIRTUAL_EXECUTION = ThreadLocal.withInitial(() -> false);

    public static void beginVirtualExecution() {
        VIRTUAL_EXECUTION.set(true);
    }

    public static void endVirtualExecution() {
        VIRTUAL_EXECUTION.set(false);
    }

    public static boolean isVirtualExecution() {
        return VIRTUAL_EXECUTION.get();
    }
}
