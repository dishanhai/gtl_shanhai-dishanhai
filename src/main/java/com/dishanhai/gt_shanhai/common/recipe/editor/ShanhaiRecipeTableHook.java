package com.dishanhai.gt_shanhai.common.recipe.editor;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.crafting.RecipeManager;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Invalidates third-party recipe caches after the two RecipeManager tables are
 * replaced. This is deliberately reflection-only: Polymorph/FastSuite are
 * optional runtime dependencies and must not become compile-time dependencies.
 */
public final class ShanhaiRecipeTableHook {

    private static final AtomicInteger TABLE_VERSION = new AtomicInteger();
    private static volatile boolean polymorphAbsentLogged;

    private ShanhaiRecipeTableHook() {}

    public static int invalidateCaches(MinecraftServer server) {
        if (server == null || server.getRecipeManager() == null) return 0;
        int cleared = 0;
        RecipeManager manager = server.getRecipeManager();
        Class<?> type = manager.getClass();
        while (type != null && type != Object.class) {
            for (Field field : type.getDeclaredFields()) {
                if (!Map.class.isAssignableFrom(field.getType())
                        || !field.getName().toLowerCase(java.util.Locale.ROOT).contains("cache")) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    Object value = field.get(manager);
                    if (value instanceof Map<?, ?> cache && !cache.isEmpty()) {
                        cache.clear();
                        cleared++;
                    }
                } catch (Throwable ignored) {
                    // Optional cache; failure must not stop recipe rebuild.
                }
            }
            type = type.getSuperclass();
        }
        invalidatePolymorphPlayerCaches(server);
        TABLE_VERSION.incrementAndGet();
        return cleared;
    }

    /**
     * Clears Polymorph's lastRecipe/input/cachedSelection values without
     * touching the user's selected recipe.
     */
    public static int invalidatePolymorphPlayerCaches(MinecraftServer server) {
        if (server == null) return -1;
        final Class<?> capabilities;
        try {
            capabilities = Class.forName(
                    "com.illusivesoulworks.polymorph.common.capability.PolymorphCapabilities");
        } catch (Throwable absent) {
            if (!polymorphAbsentLogged) polymorphAbsentLogged = true;
            return -1;
        }
        try {
            Method getRecipeData = capabilities.getMethod("getRecipeData", Player.class);
            int players = 0;
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                Object data = getRecipeData.invoke(null, player);
                if (data instanceof java.util.Optional<?> optional) data = optional.orElse(null);
                if (data == null) continue;
                players++;
                clearField(data, "lastRecipe");
                clearField(data, "input");
                clearField(data, "cachedSelection");
            }
            return players;
        } catch (Throwable ignored) {
            return -1;
        }
    }

    public static int tableVersion() {
        return TABLE_VERSION.get();
    }

    private static boolean clearField(Object target, String name) {
        Class<?> type = target.getClass();
        while (type != null && type != Object.class) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                field.set(target, null);
                return true;
            } catch (NoSuchFieldException missing) {
                type = type.getSuperclass();
            } catch (Throwable denied) {
                return false;
            }
        }
        return false;
    }
}
