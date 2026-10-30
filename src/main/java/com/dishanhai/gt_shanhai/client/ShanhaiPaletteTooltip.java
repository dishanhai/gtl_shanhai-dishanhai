package com.dishanhai.gt_shanhai.client;

import com.dishanhai.gt_shanhai.GTDishanhaiMod;
import com.dishanhai.gt_shanhai.api.TextFormatParser;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.entity.player.ItemTooltipEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.List;

/** 山海動態彩虹測試物品的色板預覽。 */
@Mod.EventBusSubscriber(modid = GTDishanhaiMod.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ShanhaiPaletteTooltip {
    private static final ResourceLocation TEST_ITEM = new ResourceLocation("dishanhai", "test_dynamic_text");

    private ShanhaiPaletteTooltip() {}

    // KubeJS 經 Architectury 的 ItemTooltipEvent HIGH 處理後插入，保留原測試內容。
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onTooltip(ItemTooltipEvent event) {
        if (!TEST_ITEM.equals(ForgeRegistries.ITEMS.getKey(event.getItemStack().getItem()))) return;
        appendPreview(event.getToolTip());
    }

    static void appendPreview(List<Component> tooltip) {
        int index = Math.min(1, tooltip.size());
        tooltip.add(index++, Component.literal("—— 山海新增色板 ——").withStyle(ChatFormatting.DARK_GRAY));
        tooltip.add(index++, TextFormatParser.createFcsComponent("", "山海朝霞 / shanhai_dawn", "shanhai_dawn"));
        tooltip.add(index++, TextFormatParser.createFcsComponent("", "山海青岚 / shanhai_mist", "shanhai_mist"));
        tooltip.add(index++, TextFormatParser.createFcsComponent("", "山海星河 / shanhai_galaxy", "shanhai_galaxy"));
        tooltip.add(index, TextFormatParser.createFcsComponent("", "山海琉彩 / shanhai_prism", "shanhai_prism"));
    }
}
