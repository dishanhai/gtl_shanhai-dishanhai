package com.dishanhai.gt_shanhai.client.shop;

import com.dishanhai.gt_shanhai.client.ShanhaiKeyMappings;
import com.dishanhai.gt_shanhai.client.gui.shop.ShopScreenOpener;
import com.dishanhai.gt_shanhai.common.shop.CurrencyRateConfig;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.ItemTooltipEvent;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.List;

/**
 * 悬停货币中心配置过的 GT 币（宇宙GT币、超维度GT币等），按住与商店相同的快捷键 10 tick 后打开货币中心并选中该币。
 * 按键查询与进度条跟 {@link ShopItemHotkey} 同一套：JEI 提示框不更新 KeyMapping 按下状态，改读窗口实体按键。
 */
public final class CurrencyItemHotkey {

    private static final int TICKS_TO_OPEN = 10;
    private static final ShopItemHotkey.HoldState HOLD_STATE = new ShopItemHotkey.HoldState(TICKS_TO_OPEN);
    private static boolean newTick = true;

    private CurrencyItemHotkey() {}

    public static void onItemTooltip(ItemTooltipEvent event) {
        if (event.getItemStack().isEmpty()) {
            HOLD_STATE.reset();
            return;
        }
        ResourceLocation itemId = ForgeRegistries.ITEMS.getKey(event.getItemStack().getItem());
        if (itemId == null || !CurrencyRateConfig.has(itemId)) {
            HOLD_STATE.reset();
            return;
        }
        offer(event.getToolTip(), itemId);
    }

    public static void offer(List<Component> tooltip, ResourceLocation currencyId) {
        KeyMapping mapping = ShanhaiKeyMappings.OPEN_HOVERED_SHOP_ITEM;
        Minecraft minecraft = Minecraft.getInstance();
        if (mapping == null || mapping.isUnbound() || minecraft.player == null || minecraft.screen == null
                || tooltip == null || currencyId == null || !CurrencyRateConfig.has(currencyId)) {
            HOLD_STATE.reset();
            return;
        }

        boolean held = isKeyHeld(mapping);
        if (newTick) {
            newTick = false;
            long opened = HOLD_STATE.update(currencyId.toString(), 1L, held);
            if (opened >= 0L) {
                ShopScreenOpener.requestOpenCurrency(currencyId);
            }
        }
        addTooltip(tooltip, mapping, held);
    }

    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        newTick = true;
        KeyMapping mapping = ShanhaiKeyMappings.OPEN_HOVERED_SHOP_ITEM;
        if (mapping == null || !isKeyHeld(mapping) || Minecraft.getInstance().screen == null) {
            HOLD_STATE.release();
        }
    }

    public static void reset() {
        HOLD_STATE.reset();
        newTick = true;
    }

    private static boolean isKeyHeld(KeyMapping mapping) {
        if (mapping == null || mapping.isUnbound()) return false;
        long window = Minecraft.getInstance().getWindow().getWindow();
        return InputConstants.isKeyDown(window, mapping.getKey().getValue());
    }

    private static void addTooltip(List<Component> tooltip, KeyMapping mapping, boolean held) {
        MutableComponent prompt = Component.literal("按住 [").withStyle(ChatFormatting.DARK_GRAY)
                .append(mapping.getTranslatedKeyMessage().copy().withStyle(ChatFormatting.GRAY))
                .append(Component.literal("] 进入货币中心").withStyle(ChatFormatting.DARK_GRAY));
        int insertAt = Math.min(1, tooltip.size());
        tooltip.add(insertAt, HOLD_STATE.progressBar(prompt, held));
    }
}
