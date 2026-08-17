package com.dishanhai.gt_shanhai.client.shop;

import com.dishanhai.gt_shanhai.client.ShanhaiKeyMappings;
import com.dishanhai.gt_shanhai.client.gui.shop.ShopScreenOpener;

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
import java.util.Objects;

/** GuideME 风格的商店物品快捷跳转：悬停可购买物品并按住配置键 10 tick 后打开对应商品。 */
public final class ShopItemHotkey {

    private static final int TICKS_TO_OPEN = 10;
    private static final HoldState HOLD_STATE = new HoldState(TICKS_TO_OPEN);
    private static boolean newTick = true;

    private ShopItemHotkey() {}

    public static void onItemTooltip(ItemTooltipEvent event) {
        KeyMapping mapping = ShanhaiKeyMappings.OPEN_HOVERED_SHOP_ITEM;
        Minecraft minecraft = Minecraft.getInstance();
        if (mapping == null || mapping.isUnbound() || minecraft.player == null || minecraft.screen == null
                || event.getItemStack().isEmpty()) {
            HOLD_STATE.reset();
            return;
        }

        ResourceLocation itemId = ForgeRegistries.ITEMS.getKey(event.getItemStack().getItem());
        if (itemId == null) {
            HOLD_STATE.reset();
            return;
        }
        List<Long> entryKeys = ClientShopCatalog.keysOfGoodsId(itemId.toString());
        if (entryKeys.isEmpty()) {
            HOLD_STATE.reset();
            return;
        }

        boolean held = isKeyHeld(mapping);
        if (newTick) {
            newTick = false;
            long entryKey = HOLD_STATE.update(itemId.toString(), entryKeys.get(0), held);
            if (entryKey >= 0L) {
                ShopScreenOpener.requestOpenAt(entryKey);
            }
        }
        addTooltip(event, mapping, held);
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

    /**
     * GuideME 同款實體輸入查詢。JEI tooltip 在自己的輸入路徑內渲染，KeyMapping 的 pressed 狀態不一定更新，
     * 但 GLFW 視窗狀態會持續反映玩家是否按住目前綁定的鍵。
     */
    private static boolean isKeyHeld(KeyMapping mapping) {
        if (mapping == null || mapping.isUnbound()) return false;
        long window = Minecraft.getInstance().getWindow().getWindow();
        return InputConstants.isKeyDown(window, mapping.getKey().getValue());
    }

    private static void addTooltip(ItemTooltipEvent event, KeyMapping mapping, boolean held) {
        MutableComponent prompt = Component.literal("按住 [").withStyle(ChatFormatting.DARK_GRAY)
                .append(mapping.getTranslatedKeyMessage().copy().withStyle(ChatFormatting.GRAY))
                .append(Component.literal("] 前往山海商店").withStyle(ChatFormatting.DARK_GRAY));
        int insertAt = Math.min(1, event.getToolTip().size());
        event.getToolTip().add(insertAt, HOLD_STATE.progressBar(prompt, held));
    }

    static final class HoldState {
        private final int threshold;
        private String hoveredItemId;
        private int heldTicks;
        private boolean opened;

        HoldState(int threshold) {
            this.threshold = Math.max(1, threshold);
        }

        long update(String itemId, long entryKey, boolean keyHeld) {
            if (itemId == null || itemId.isBlank() || entryKey < 0L) {
                reset();
                return -1L;
            }
            if (!Objects.equals(hoveredItemId, itemId)) {
                hoveredItemId = itemId;
                heldTicks = 0;
                opened = false;
            }
            if (!keyHeld) {
                release();
                return -1L;
            }
            if (opened) return -1L;
            heldTicks = Math.min(threshold, heldTicks + 1);
            if (heldTicks < threshold) return -1L;
            opened = true;
            return entryKey;
        }

        Component progressBar(Component prompt, boolean keyHeld) {
            if (!keyHeld || heldTicks <= 0) return prompt;
            int total = 20;
            int filled = Math.min(total, Math.max(1, heldTicks * total / threshold));
            return Component.literal("|".repeat(filled)).withStyle(ChatFormatting.GRAY)
                    .append(Component.literal("|".repeat(total - filled)).withStyle(ChatFormatting.DARK_GRAY));
        }

        void release() {
            heldTicks = 0;
            opened = false;
        }

        void reset() {
            hoveredItemId = null;
            release();
        }
    }
}
