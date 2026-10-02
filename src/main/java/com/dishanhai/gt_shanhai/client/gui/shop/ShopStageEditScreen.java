package com.dishanhai.gt_shanhai.client.gui.shop;

import com.dishanhai.gt_shanhai.client.gui.scaled.GuiRenderUtil;
import com.dishanhai.gt_shanhai.client.gui.scaled.ScaledScreen;
import com.dishanhai.gt_shanhai.common.shop.ExchangeEntry;
import com.dishanhai.gt_shanhai.network.ShanhaiNetwork;
import com.dishanhai.gt_shanhai.network.ShopStageEditPacket;
import dev.architectury.fluid.FluidStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.List;

/** 分類頁籤階段提交限制編輯器；由編輯權玩家右鍵階段頁籤打開。 */
public final class ShopStageEditScreen extends ScaledScreen {
    private static final int W = 360, H = 210, SLOT = 20, PITCH = 24;
    private final ShopScreen parent;
    private final String path;
    private final List<ItemStack> items = new ArrayList<>();
    private final List<Long> counts = new ArrayList<>();
    private int left, top, scroll;

    public ShopStageEditScreen(ShopScreen parent, String path) {
        super(Component.literal("阶段提交限制"));
        this.parent = parent;
        this.path = path == null ? "" : path;
        for (ExchangeEntry.Ingredient requirement
                : com.dishanhai.gt_shanhai.client.shop.ClientShopUnlockState.stageRequirements(this.path)) {
            ItemStack stack = requirement.makeUnitStack();
            if (stack.isEmpty()) continue;
            stack.setCount(1);
            items.add(stack);
            counts.add(requirement.count);
        }
        targetWidth = W;
        targetHeight = H;
        minScale = 0.1f;
        useOffset = false;
    }

    @Override
    protected void initScaled() {
        left = Math.max(8, (vWidth - W) / 2);
        top = Math.max(8, (vHeight - H) / 2);
    }

    private int rowY() { return top + 54; }

    @Override
    protected void renderScaledBackground(GuiGraphics g, int mx, int my, float pt) {
        g.fill(left, top, left + W, top + H, -7710208);
        g.fill(left + 1, top + 1, left + W - 1, top + H - 1, -22016);
        g.fill(left + 2, top + 2, left + W - 2, top + H - 2, -267382768);
        g.drawString(font, "§6阶段提交限制", left + 10, top + 7, -22016, true);
        g.drawString(font, "§7阶段: §f" + path, left + 10, top + 23, -1, true);
        g.drawString(font, "§8右键删除，留空后保存=取消限制；提交一次后永久解锁该阶段", left + 10, top + 37, -5592406, true);
        int sx = left + 16;
        int y = rowY();
        int visible = Math.max(1, (W - 32) / PITCH);
        int start = Math.max(0, Math.min(scroll, Math.max(0, items.size() + 1 - visible)));
        int end = Math.min(items.size() + 1, start + visible);
        g.enableScissor((int) (sx * guiScale) + offsetX, (int) ((y - 1) * guiScale) + offsetY,
                (int) ((left + W - 12) * guiScale) + offsetX, (int) ((y + SLOT + 10) * guiScale) + offsetY);
        for (int i = start; i < end; i++) {
            int x = sx + (i - start) * PITCH;
            if (i < items.size()) {
                EditorWidgets.itemSlot(g, font, x, y, items.get(i), GuiRenderUtil.isHovering(mx, my, x, y, SLOT, SLOT));
                g.drawCenteredString(font, compact(counts.get(i)), x + 10, y + 21, -11141121);
            } else EditorWidgets.plusSlot(g, font, x, y, GuiRenderUtil.isHovering(mx, my, x, y, SLOT, SLOT));
        }
        g.disableScissor();
        if (items.size() + 1 > visible) {
            g.drawString(font, "§8滚轮切换物品", left + 16, y + 34, -5592406, false);
        }
        drawButton(g, left + 12, top + H - 25, 58, 14, "§c取消", mx, my);
        drawButton(g, left + W - 86, top + H - 25, 74, 14, "§a保存", mx, my);
    }

    private static String compact(long count) {
        if (count >= 1_000_000_000L) return (count / 1_000_000_000L) + "B+";
        if (count >= 1_000_000L) return (count / 1_000_000L) + "M+";
        if (count >= 1_000L) return (count / 1_000L) + "K+";
        return Long.toString(count);
    }

    private void drawButton(GuiGraphics g, int x, int y, int w, int h, String text, int mx, int my) {
        boolean hover = GuiRenderUtil.isHovering(mx, my, x, y, w, h);
        g.fill(x, y, x + w, y + h, -7710208);
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, hover ? -12303292 : -14935012);
        g.drawCenteredString(font, text, x + w / 2, y + 3, -1);
    }

    @Override
    protected boolean universalMouseClicked(double mx, double my, int btn) {
        if (GuiRenderUtil.isHovering(mx, my, left + 12, top + H - 25, 58, 14)) {
            Minecraft.getInstance().setScreen(parent);
            return true;
        }
        if (GuiRenderUtil.isHovering(mx, my, left + W - 86, top + H - 25, 74, 14)) {
            List<ExchangeEntry.Ingredient> result = new ArrayList<>();
            for (int i = 0; i < items.size(); i++) {
                ItemStack stack = items.get(i);
                ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
                if (id != null) result.add(new ExchangeEntry.Ingredient(id, false, counts.get(i), stack.getTag()));
            }
            ShanhaiNetwork.CHANNEL.sendToServer(new ShopStageEditPacket(path, result));
            Minecraft.getInstance().setScreen(parent);
            return true;
        }
        int sx = left + 16, y = rowY(), visible = Math.max(1, (W - 32) / PITCH);
        int start = Math.max(0, Math.min(scroll, Math.max(0, items.size() + 1 - visible)));
        for (int i = start; i < Math.min(items.size() + 1, start + visible); i++) {
            int x = sx + (i - start) * PITCH;
            if (!GuiRenderUtil.isHovering(mx, my, x, y, SLOT, SLOT)) continue;
            if (i == items.size()) {
                Minecraft.getInstance().setScreen(new MultiPickerScreen(this, false,
                        (stack, count) -> { if (stack != null && !stack.isEmpty()) { ItemStack copy = stack.copy(); copy.setCount(1); items.add(copy); counts.add(Math.max(1L, count)); } },
                        fluid -> {}));
            } else if (btn == 1) {
                items.remove(i);
                counts.remove(i);
            } else {
                final int index = i;
                EditorWidgets.openItemPicker(items.get(i), stack -> { if (stack == null || stack.isEmpty()) { items.remove(index); counts.remove(index); } else { stack.setCount(1); items.set(index, stack); } });
            }
            return true;
        }
        return true;
    }

    @Override
    protected boolean universalMouseScrolled(double mx, double my, double d) {
        int visible = Math.max(1, (W - 32) / PITCH);
        scroll = Math.max(0, Math.min(Math.max(0, items.size() + 1 - visible), scroll - (int) d));
        return true;
    }

    @Override public boolean isPauseScreen() { return false; }
}
