package com.dishanhai.gt_shanhai.api;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 星云渲染（Cosmic Effect）物品注册表。
 * 标记为 cosmic 的物品在 GUI/世界中会渲染黑色星云边框动画。
 * 线程安全，可在任意侧（客户端/服务端）调用。
 *
 * ⚠ ForgeRegistries.ITEMS.getValue() 在 key 不存在时返回 Items.AIR（非 null），
 *   必须用 containsKey() 检查是否存在。
 */
public class CosmicItemRegistry {

    private static final Set<Item> COSMIC_ITEMS = ConcurrentHashMap.newKeySet();
    private static final Set<String> PENDING_IDS = ConcurrentHashMap.newKeySet();
    /** 所有需要缝合到 block atlas 的 mask 贴图路径 */
    private static final Set<ResourceLocation> MASK_TEXTURES = ConcurrentHashMap.newKeySet();

    private CosmicItemRegistry() {}

    /**
     * 通过 Item 对象标记为 cosmic 物品
     */
    public static void markCosmic(Item item) {
        if (item != null && item != Items.AIR) {
            COSMIC_ITEMS.add(item);
        }
    }

    /**
     * 通过物品 ID 字符串标记为 cosmic 物品
     * 格式: "modid:item_name" （如 "kubejs:cosmic_ingot"）
     * 如果注册表尚未就绪，则暂存 ID，注册表可用后会自动解析
     */
    public static void markCosmic(String itemId) {
        if (itemId == null || itemId.isEmpty()) return;

        ResourceLocation id;
        try {
            id = new ResourceLocation(itemId);
        } catch (Exception e) {
            return;
        }

        // ⚠ ForgeRegistries.getValue() 在 key 不存在时不返回 null，而是返回 Items.AIR
        if (ForgeRegistries.ITEMS.containsKey(id)) {
            Item item = ForgeRegistries.ITEMS.getValue(id);
            if (item != null && item != Items.AIR) {
                COSMIC_ITEMS.add(item);
            }
        } else {
            PENDING_IDS.add(itemId);
        }
        // 记录 mask 贴图路径（约定: namespace:mask/path）
        MASK_TEXTURES.add(new ResourceLocation(id.getNamespace(), "mask/" + id.getPath()));
    }

    /**
     * 检查物品是否为 cosmic
     */
    public static boolean isCosmic(Item item) {
        return item != null && COSMIC_ITEMS.contains(item);
    }

    /**
     * 获取所有已注册的 cosmic 物品（只读视图）
     */
    public static Set<Item> getCosmicItems() {
        return Collections.unmodifiableSet(COSMIC_ITEMS);
    }

    /**
     * 获取暂存 ID 数量（调试用）
     */
    public static int pendingCount() {
        return PENDING_IDS.size();
    }

    /**
     * 获取所有需要注册到 block atlas 的 mask 贴图路径
     */
    public static Set<ResourceLocation> getMaskTextures() {
        return Collections.unmodifiableSet(MASK_TEXTURES);
    }

    /**
     * 解析所有暂存的 ID（应在 Registry 就绪后调用，如 ModelEvent.ModifyBakingResult）
     */
    public static void resolvePending() {
        if (PENDING_IDS.isEmpty()) return;
        for (String idStr : PENDING_IDS) {
            ResourceLocation id;
            try {
                id = new ResourceLocation(idStr);
            } catch (Exception e) {
                continue;
            }
            if (ForgeRegistries.ITEMS.containsKey(id)) {
                Item item = ForgeRegistries.ITEMS.getValue(id);
                if (item != null && item != Items.AIR) {
                    COSMIC_ITEMS.add(item);
                }
            }
        }
        PENDING_IDS.clear();
    }
}
