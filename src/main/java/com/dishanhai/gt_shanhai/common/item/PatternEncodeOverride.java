package com.dishanhai.gt_shanhai.common.item;

import java.util.Collections;
import java.util.Set;

/**
 * 样板终端"包裹模式"手动覆盖——由 PatternEncodingTermMenu 在服务端调用
 * encodeProcessingPattern() 前后 push/pop，VirtualPatternEncodingHelper 读取。
 * ThreadLocal 是因为 PatternDetailsHelper.encodeProcessingPattern 是无上下文的静态方法，
 * 唯一能把"这次编码来自哪个 Menu、玩家选了什么模式"带过去的办法。
 */
public final class PatternEncodeOverride {

    public enum WrapMode { AUTO, FORCE_WRAP, FORCE_NO_WRAP }

    private static final ThreadLocal<PatternEncodeOverride> CURRENT = new ThreadLocal<>();
    private static final ThreadLocal<String> LAST_DIAGNOSTIC = new ThreadLocal<>();

    public final WrapMode mode;
    public final Set<Integer> forcedSlots;

    public PatternEncodeOverride(WrapMode mode, Set<Integer> forcedSlots) {
        this.mode = mode;
        this.forcedSlots = forcedSlots == null ? Collections.emptySet() : forcedSlots;
    }

    public static void push(PatternEncodeOverride override) {
        CURRENT.set(override);
    }

    public static void pop() {
        CURRENT.remove();
    }

    public static PatternEncodeOverride current() {
        return CURRENT.get();
    }

    public static void setLastDiagnostic(String message) {
        LAST_DIAGNOSTIC.set(message);
    }

    public static String consumeLastDiagnostic() {
        String value = LAST_DIAGNOSTIC.get();
        LAST_DIAGNOSTIC.remove();
        return value;
    }
}
