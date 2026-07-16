package com.dishanhai.gt_shanhai.common.item;

import appeng.api.crafting.IPatternDetails;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraftforge.fml.ModList;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/** 可选调用 GTLCore 的通配符样板兼容实现，避免直接依赖 wildcard_pattern 模组类。 */
public final class WildcardPatternBridge {

    private static final Logger LOG = LoggerFactory.getLogger("gt_shanhai:wildcard_pattern");
    private static final String MOD_ID = "wildcard_pattern";
    private static final String COMPAT_CLASS = "org.gtlcore.gtlcore.integration.wildcard.WildcardPatternCompatImpl";

    private static boolean initialized;
    private static boolean available;
    private static Method isWildcardPatternMethod;
    private static Method expandPatternsMethod;

    private WildcardPatternBridge() {}

    public static boolean isAvailable() {
        initialize();
        return available;
    }

    public static boolean isWildcardPattern(ItemStack stack) {
        if (stack == null || stack.isEmpty() || !isAvailable()) return false;
        try {
            return Boolean.TRUE.equals(isWildcardPatternMethod.invoke(null, stack));
        } catch (ReflectiveOperationException | LinkageError e) {
            disable(e);
            return false;
        }
    }

    public static List<IPatternDetails> expandPatterns(ItemStack stack, Level level) {
        if (!isWildcardPattern(stack) || level == null) return List.of();
        try {
            Object result = expandPatternsMethod.invoke(null, stack, level);
            if (!(result instanceof List<?> list)) return List.of();
            List<IPatternDetails> patterns = new ArrayList<>(list.size());
            for (Object value : list) {
                if (value instanceof IPatternDetails pattern) patterns.add(pattern);
            }
            return patterns;
        } catch (ReflectiveOperationException | LinkageError e) {
            disable(e);
            return List.of();
        }
    }

    private static synchronized void initialize() {
        if (initialized) return;
        initialized = true;
        if (!ModList.get().isLoaded(MOD_ID)) return;
        try {
            Class<?> compat = Class.forName(COMPAT_CLASS);
            isWildcardPatternMethod = compat.getDeclaredMethod("isWildcardPattern", ItemStack.class);
            expandPatternsMethod = compat.getDeclaredMethod("expandPatterns", ItemStack.class, Level.class);
            isWildcardPatternMethod.setAccessible(true);
            expandPatternsMethod.setAccessible(true);
            available = true;
        } catch (ReflectiveOperationException | LinkageError e) {
            disable(e);
        }
    }

    private static synchronized void disable(Throwable error) {
        if (available || isWildcardPatternMethod != null || expandPatternsMethod != null) {
            LOG.warn("GTLCore 通配符样板桥接失效，星律通配符槽将停用", error);
        }
        available = false;
        isWildcardPatternMethod = null;
        expandPatternsMethod = null;
    }
}
