package com.dishanhai.gt_shanhai.client.renderer.item;

import com.dishanhai.gt_shanhai.GTDishanhaiMod;
import com.dishanhai.gt_shanhai.api.CosmicItemRegistry;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraftforge.client.event.ModelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * 模型烘焙完成后，将 cosmic 物品的模型替换为 CosmicBakedModel。
 * 仅客户端执行。
 *
 * ⚠ models map 的 key 在运行时可能是 ResourceLocation 或 ModelResourceLocation，
 *   两者 hashCode() 实现不同，用 get() 查不到。必须遍历匹配 namespace+path。
 */
public class CosmicModelEventHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(GTDishanhaiMod.MOD_ID + "/cosmic");

    @SubscribeEvent
    public static void onModelBake(ModelEvent.ModifyBakingResult event) {
        // KubeJS 脚本中 makeCosmic() 调用时物品尚未注册进入 Forge Registry，
        // ID 暂存在 PENDING_IDS 中。模型烘焙时物品肯定已注册，先解析暂存 ID。
        int pendingBefore = CosmicItemRegistry.pendingCount();
        CosmicItemRegistry.resolvePending();
        LOGGER.info("[Cosmic] ModifyBakingResult 触发, pending={}, cosmic物品={}",
                pendingBefore, CosmicItemRegistry.getCosmicItems().size());

        var models = event.getModels();
        int found = 0;

        for (Item item : CosmicItemRegistry.getCosmicItems()) {
            ResourceLocation itemId = ForgeRegistries.ITEMS.getKey(item);
            if (itemId == null) {
                LOGGER.warn("[Cosmic] 物品的 registry key 为 null");
                continue;
            }

            // ⚠ 模型注册表中物品 key 格式为 {ns}:{item_name}#inventory
            //    path 就是 item_name（不带 item/ 前缀），和物品注册名一致
            String targetPath = itemId.getPath();
            String targetNs = itemId.getNamespace();
            LOGGER.info("[Cosmic] 查找目标: {}/{}  物品ID={}", targetNs, targetPath, itemId);

            boolean matched = false;
            for (Map.Entry<ResourceLocation, BakedModel> entry : models.entrySet()) {
                ResourceLocation key = entry.getKey();
                if (key.getNamespace().equals(targetNs) && key.getPath().equals(targetPath)) {
                    BakedModel original = entry.getValue();
                    if (original != null && !(original instanceof CosmicBakedModel)) {
                        long seed = itemId.hashCode();
                        entry.setValue(new CosmicBakedModel(original, seed));
                        found++;
                        matched = true;
                        LOGGER.info("[Cosmic] 已包装模型: {}", key);
                    }
                    break;
                }
            }

            if (!matched) {
                LOGGER.warn("[Cosmic] 未找到模型! 搜索包含关键词的 key:");
                String search = itemId.getPath().substring(0, Math.min(6, itemId.getPath().length()));
                for (ResourceLocation k : models.keySet()) {
                    if (k.getPath().contains(search) || k.toString().contains(search)) {
                        LOGGER.warn("  >>> 匹配key: {} | ns={} path={}", k, k.getNamespace(), k.getPath());
                    }
                }
                LOGGER.warn("[Cosmic] 采样部分 models key:");
                int n = 0;
                for (ResourceLocation k : models.keySet()) {
                    if (++n > 10) break;
                    LOGGER.warn("  key[{}]: {} | ns={} path={}", n, k, k.getNamespace(), k.getPath());
                }
            }
        }

        if (found > 0) {
            LOGGER.info("[Cosmic] 本次共包装 {} 个物品", found);
        } else {
            int total = CosmicItemRegistry.getCosmicItems().size();
            LOGGER.warn("[Cosmic] 事件已触发，但 {} 个 cosmic 物品的模型均未找到", total);
        }
    }
}
