package com.dishanhai.gt_shanhai.common.recipe.editor;

import com.dishanhai.gt_shanhai.GTDishanhaiMod;
import net.minecraft.server.level.ServerPlayer;

import java.lang.reflect.Method;

/**
 * Stable bridge used by the holographic menu.
 *
 * <p>The bridge keeps the holo module independent from the optional LDLib UI
 * implementation. When the full editor factory is present it is delegated to;
 * when it is not, the caller gets a clean {@code false} result instead of a
 * class-loading failure on a dedicated server.</p>
 */
public final class DShanhaiRecipeEditorFactory {

    private static final String[] DELEGATES = {
            "com.dishanhai.gt_shanhai.common.recipe.editor.ShanhaiRecipeEditorFactory",
            "com.shanhai.common.recipe.editor.ShanhaiRecipeEditorFactory"
    };

    private DShanhaiRecipeEditorFactory() {}

    public static boolean open(Object ignoredHolder, ServerPlayer player) {
        if (player == null) return false;
        try {
            return ShanhaiRecipeEditorFactory.open(ignoredHolder, player);
        } catch (Throwable localFailure) {
            GTDishanhaiMod.LOGGER.warn("[配方编辑器] local layered factory failed", localFailure);
        }
        ShanhaiRecipeEditorWorkspace workspace =
                new ShanhaiRecipeEditorWorkspace(player.getServer(), player);
        for (String name : DELEGATES) {
            try {
                Class<?> factory = Class.forName(name);
                Method open = findOpen(factory);
                if (open == null) continue;
                Object holder = adaptHolder(open.getParameterTypes()[0], workspace);
                Object result = open.invoke(null, holder, player);
                return !(result instanceof Boolean) || Boolean.TRUE.equals(result);
            } catch (ClassNotFoundException ignored) {
                // Optional delegate is not installed in this source set.
            } catch (Throwable error) {
                GTDishanhaiMod.LOGGER.warn("[配方编辑器] holo bridge delegate failed: {}", name, error);
            }
        }
        return false;
    }

    private static Method findOpen(Class<?> factory) {
        for (Method method : factory.getMethods()) {
            if (!"open".equals(method.getName())
                    || method.getParameterCount() != 2
                    || method.getParameterTypes()[1] != ServerPlayer.class) {
                continue;
            }
            method.setAccessible(true);
            return method;
        }
        return null;
    }

    private static Object adaptHolder(Class<?> holderType, ShanhaiRecipeEditorWorkspace workspace)
            throws ReflectiveOperationException {
        if (holderType.isInstance(workspace) || holderType == Object.class) return workspace;
        try {
            java.lang.reflect.Constructor<?> constructor = holderType.getDeclaredConstructor(
                    net.minecraft.resources.ResourceLocation.class,
                    int.class,
                    net.minecraft.resources.ResourceLocation.class,
                    net.minecraft.resources.ResourceLocation.class,
                    boolean.class);
            constructor.setAccessible(true);
            return constructor.newInstance(null, 0, null, null, false);
        } catch (NoSuchMethodException ignored) {
            java.lang.reflect.Constructor<?> constructor = holderType.getDeclaredConstructor(
                    net.minecraft.resources.ResourceLocation.class, boolean.class);
            constructor.setAccessible(true);
            return constructor.newInstance(null, false);
        }
    }
}
