package com.dishanhai.gt_shanhai.client.renderer.item;

import com.dishanhai.gt_shanhai.GTDishanhaiMod;
import com.dishanhai.gt_shanhai.api.HaloItemRegistry;
import com.dishanhai.gt_shanhai.api.HaloSettings;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ModelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * 模型烘焙完成后，将注册到 {@link HaloItemRegistry} 的物品模型替换为 {@link HaloBakedModel}。
 * 仅客户端执行。
 * <p>
 * ⚠ models map 的 key 在运行时可能是 ResourceLocation 或 ModelResourceLocation，
 *   两者 hashCode() 实现不同，用 get() 查不到，必须遍历匹配 namespace+path。
 *   这里单趟遍历 models map，
 *   用 "ns:path" 索引反查目标物品，避免 物品数×模型数 的双重循环。
 */
@Mod.EventBusSubscriber(modid = GTDishanhaiMod.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public class HaloModelEventHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(GTDishanhaiMod.MOD_ID + "/halo");

    @SubscribeEvent
    public static void onModelBake(ModelEvent.ModifyBakingResult event) {
        // KubeJS startup 脚本调用 makeHalo 时物品尚未进 Forge Registry，此时才解析暂存 ID
        HaloItemRegistry.resolvePending();
        Map<Item, HaloSettings> registered = HaloItemRegistry.getAll();
        if (registered.isEmpty()) return;

        Map<String, Map.Entry<Item, HaloSettings>> targets = new HashMap<>();
        for (Map.Entry<Item, HaloSettings> entry : registered.entrySet()) {
            ResourceLocation id = ForgeRegistries.ITEMS.getKey(entry.getKey());
            if (id != null) targets.put(id.getNamespace() + ":" + id.getPath(), entry);
        }

        int found = 0;
        for (Map.Entry<ResourceLocation, BakedModel> entry : event.getModels().entrySet()) {
            ResourceLocation key = entry.getKey();
            // 物品模型 key 形如 {ns}:{item_name}#inventory；跳过方块状态变体
            if (key instanceof ModelResourceLocation mrl && !"inventory".equals(mrl.getVariant())) continue;

            var target = targets.get(key.getNamespace() + ":" + key.getPath());
            if (target == null) continue;

            BakedModel original = entry.getValue();
            if (original == null || original instanceof HaloBakedModel) continue;

            // BEWLR 物品（GTCEu/LDLib 机器等）不走 getQuads，包了也不会有光环——直接跳过并提示
            if (original.isCustomRenderer()) {
                LOGGER.warn("[Halo] {} 使用自定义渲染器（BEWLR），光环仅支持平面物品模型，已跳过", key);
                continue;
            }

            // 整方块物品的 quad 全在方向裁剪列表里，null 方向列表为空 → 渲染期光环静默失效，这里提前提示
            try {
                if (original.getQuads(null, null, net.minecraft.util.RandomSource.create(42L)).isEmpty()) {
                    LOGGER.warn("[Halo] {} 的无方向 quad 列表为空（方块类模型？），光环可能不显示", key);
                }
            } catch (Exception ignored) {
                // 个别动态模型在烘焙期探测可能异常，不影响包装
            }

            long seed = key.getNamespace().hashCode() * 31L + key.getPath().hashCode();
            entry.setValue(new HaloBakedModel(original, target.getValue(), seed));
            found++;
        }

        if (found < targets.size()) {
            LOGGER.warn("[Halo] 注册 {} 个光环物品，仅找到 {} 个模型", targets.size(), found);
        } else {
            LOGGER.info("[Halo] 已包装 {} 个光环物品模型", found);
        }
    }
}
