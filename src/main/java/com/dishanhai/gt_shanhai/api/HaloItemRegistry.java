package com.dishanhai.gt_shanhai.api;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 光环渲染（Halo Effect）物品注册表。
 * 注册的物品在 GUI/地面/手持渲染时叠加 Avaritia 风格光环（底暈/冕环/星芒）。
 * 线程安全，可在任意侧调用；渲染仅客户端生效。
 * <p>
 * ⚠ ForgeRegistries.ITEMS.getValue() 在 key 不存在时返回 Items.AIR（非 null），
 *   必须用 containsKey() 检查是否存在。
 */
public class HaloItemRegistry {

    private static final Logger LOGGER = LoggerFactory.getLogger("gt_shanhai/halo");

    private static final Map<Item, HaloSettings> HALO_ITEMS = new ConcurrentHashMap<>();
    /** KubeJS startup 阶段物品尚未进 Forge Registry，先暂存 ID → 设置，模型烘焙时解析 */
    private static final Map<String, HaloSettings> PENDING = new ConcurrentHashMap<>();

    private HaloItemRegistry() {}

    /**
     * 注册光环物品。物品未注册时自动暂存，注册表可用后由 {@link #resolvePending()} 解析。
     *
     * @param itemId   物品 ID，格式 "modid:item_name"
     * @param settings 光环参数
     */
    public static void register(String itemId, HaloSettings settings) {
        if (itemId == null || itemId.isEmpty() || settings == null) return;

        ResourceLocation id;
        try {
            id = new ResourceLocation(itemId);
        } catch (Exception e) {
            LOGGER.warn("[Halo] 非法物品 ID '{}'（须为小写 modid:item_name），已忽略", itemId);
            return;
        }

        if (ForgeRegistries.ITEMS.containsKey(id)) {
            Item item = ForgeRegistries.ITEMS.getValue(id);
            if (item != null && item != Items.AIR) {
                // merge：同一物品可分次注册光环与抖动，通道级合并（见 HaloSettings.merge）
                HALO_ITEMS.merge(item, settings, HaloSettings::merge);
                return;
            }
        }
        PENDING.merge(itemId, settings, HaloSettings::merge);
    }

    /** 获取物品的光环参数；未注册返回 null */
    public static HaloSettings get(Item item) {
        return item == null ? null : HALO_ITEMS.get(item);
    }

    /** 所有已解析的光环物品（只读视图） */
    public static Map<Item, HaloSettings> getAll() {
        return Collections.unmodifiableMap(HALO_ITEMS);
    }

    /** 暂存数量（调试用） */
    public static int pendingCount() {
        return PENDING.size();
    }

    /** 解析所有暂存 ID（应在 Registry 就绪后调用，如 ModelEvent.ModifyBakingResult） */
    public static void resolvePending() {
        if (PENDING.isEmpty()) return;
        for (Map.Entry<String, HaloSettings> entry : PENDING.entrySet()) {
            ResourceLocation id;
            try {
                id = new ResourceLocation(entry.getKey());
            } catch (Exception e) {
                LOGGER.warn("[Halo] 暂存 ID '{}' 非法，已丢弃", entry.getKey());
                continue;
            }
            if (ForgeRegistries.ITEMS.containsKey(id)) {
                Item item = ForgeRegistries.ITEMS.getValue(id);
                if (item != null && item != Items.AIR) {
                    HALO_ITEMS.merge(item, entry.getValue(), HaloSettings::merge);
                }
            } else {
                // 拼错的 ID 走到这里；不打日志的话玩家排查不到光环为何没出现
                LOGGER.warn("[Halo] 物品 '{}' 不存在于注册表（检查拼写），光环注册被丢弃", entry.getKey());
            }
        }
        PENDING.clear();
    }
}
