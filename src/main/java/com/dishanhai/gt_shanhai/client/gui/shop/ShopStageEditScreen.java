package com.dishanhai.gt_shanhai.client.gui.shop;

import com.dishanhai.gt_shanhai.client.gui.scaled.GuiRenderUtil;
import com.dishanhai.gt_shanhai.client.gui.scaled.ScaledScreen;
import com.dishanhai.gt_shanhai.client.gui.scaled.Spring;
import com.dishanhai.gt_shanhai.client.shop.ClientShopUiSettings;
import com.dishanhai.gt_shanhai.common.shop.ExchangeEntry;
import com.dishanhai.gt_shanhai.network.ShanhaiNetwork;
import com.dishanhai.gt_shanhai.network.ShopStageEditPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.List;

/** 分类页签阶段提交限制编辑器（山海署名）；格子自动换行，超出视口时按像素纵向滚动。 */
public final class ShopStageEditScreen extends ScaledScreen {
    private static final int W = 360, H = 210, SLOT = 20;
    private static final int SLOT_ROW_H = SLOT + 10;
    private final ShopScreen parent;
    private final String path;
    private final List<ItemStack> items = new ArrayList<>();
    private final List<Long> counts = new ArrayList<>();
    private int left, top, scroll, shownScroll;
    private final Spring scrollMotion = new Spring(Spring.SCROLL);
    private final ShopDialogUi ui = new ShopDialogUi(-7710208, -22016, -14935012, -12303292);

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
        ui.cancel();
        left = Math.max(8, (vWidth - W) / 2);
        top = Math.max(8, (vHeight - H) / 2);
        snapScroll(scroll); // 山海：从选择器返回后保留原来的纵向位置。
    }

    private static final int BRANCH_BTN_W = 96;

    /** 底栏居中的「复制分支到阶段」：夹在「取消」和「保存」之间。 */
    private int branchBtnX() { return left + (W - BRANCH_BTN_W) / 2; }

    private int rowY() { return top + 54; }
    private int slotsX() { return left + 16; }
    private int perLine() { return ShopEntryEditLayout.perLine(W - 32); }
    private int viewBottom() { return top + H - 32; }
    private int viewH() { return viewBottom() - rowY(); }
    private int contentH() { return ShopSlotRows.height(items.size() + 1, perLine(), SLOT_ROW_H, SLOT_ROW_H); }
    private int maxScroll() { return ShopEntryEditLayout.maxScroll(contentH(), viewH()); }
    private int barX() { return left + W - 10; }
    private boolean inViewport(double mx, double my) {
        return GuiRenderUtil.isHovering(mx, my, slotsX(), rowY(), W - 32, viewH());
    }

    private void snapScroll(int target) {
        scroll = ShopEntryEditLayout.clampScroll(target, contentH(), viewH());
        shownScroll = scroll;
        scrollMotion.snap(scroll);
    }

    private void updateScroll() {
        scroll = ShopEntryEditLayout.clampScroll(scroll, contentH(), viewH());
        long now = System.currentTimeMillis();
        if (ClientShopUiSettings.reduceMotion()) scrollMotion.snap(scroll);
        else {
            if (scrollMotion.target() != scroll) scrollMotion.setTarget(scroll, now);
            scrollMotion.update(now);
        }
        shownScroll = ShopEntryEditLayout.clampScroll(Math.round(scrollMotion.value()), contentH(), viewH());
    }

    @Override
    protected void renderScaledBackground(GuiGraphics g, int mx, int my, float pt) {
        ui.frame(mx, my);
        updateScroll();
        g.fill(left, top, left + W, top + H, -7710208);
        g.fill(left + 1, top + 1, left + W - 1, top + H - 1, -22016);
        g.fill(left + 2, top + 2, left + W - 2, top + H - 2, -267382768);
        g.drawString(font, "§6阶段提交限制", left + 10, top + 7, -22016, true);
        g.drawString(font, "§7阶段: §f" + path, left + 10, top + 23, -1, true);
        g.drawString(font, "§8右键删除，留空后保存=取消限制；提交一次后永久解锁该阶段", left + 10, top + 37, -5592406, true);
        g.enableScissor((int) ((slotsX() - 6) * guiScale) + offsetX, (int) (rowY() * guiScale) + offsetY,
                (int) ((left + W - 16) * guiScale) + offsetX, (int) (viewBottom() * guiScale) + offsetY);
        for (int i = 0; i <= items.size(); i++) {
            int x = ShopEntryEditLayout.slotX(slotsX(), i, perLine());
            int y = ShopEntryEditLayout.slotY(rowY() - shownScroll, i, perLine(), SLOT_ROW_H);
            if (y + SLOT_ROW_H <= rowY() || y >= viewBottom()) continue;
            boolean hover = inViewport(mx, my) && GuiRenderUtil.isHovering(mx, my, x, y, SLOT, SLOT);
            if (i < items.size()) {
                EditorWidgets.itemSlot(g, font, x, y, items.get(i), hover);
                g.drawCenteredString(font, compact(i < counts.size() ? counts.get(i) : 1L), x + 10, y + 21, -11141121);
            } else EditorWidgets.plusSlot(g, font, x, y, hover);
        }
        g.disableScissor();
        ui.drawScrollbar(g, barX(), rowY(), viewH(), contentH(), viewH(), shownScroll, maxScroll(), mx, my);
        drawButton(g, left + 12, top + H - 25, 58, 14, "§c取消", mx, my);
        drawButton(g, branchBtnX(), top + H - 25, BRANCH_BTN_W, 14, "§b复制分支到阶段", mx, my);
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
        if (GuiRenderUtil.isHovering(mx, my, branchBtnX(), top + H - 25, BRANCH_BTN_W, 14)) {
            // 阶段页签本身就是一个分支：把它整棵子树（含商品）复制到别的阶段下，内容保持同步
            Minecraft.getInstance().setScreen(new ShopBranchCopyScreen(this, parent, path));
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
        int target = btn == 0 ? ui.beginDrag(mx, my, barX(), rowY(), viewH(), contentH(), viewH(), maxScroll()) : -1;
        if (target >= 0) {
            snapScroll(target);
            return true;
        }
        if (!inViewport(mx, my)) return true;
        int i = ShopEntryEditLayout.slotIndexAt(mx, my + shownScroll, slotsX(), rowY(), items.size() + 1,
                perLine(), SLOT_ROW_H, SLOT);
        if (i >= 0) {
            if (i == items.size()) {
                Minecraft.getInstance().setScreen(new MultiPickerScreen(this, false,
                        (stack, count) -> { if (stack != null && !stack.isEmpty()) { ItemStack copy = stack.copy(); copy.setCount(1); items.add(copy); counts.add(Math.max(1L, count)); } },
                        fluid -> {}));
            } else if (btn == 1) {
                items.remove(i);
                counts.remove(i);
            } else {
                openItemReplace(i);
            }
            return true;
        }
        return true;
    }

    /**
     * 点已有物品格：开自建多选器的替换模式，原物品与精确数量先放进已选清单。确认后整份清单换掉这一格，
     * 清空再确认 = 删掉这一格。与商店编辑器同一套，不再弹出 FTBLib 的选取页。
     */
    private void openItemReplace(int idx) {
        List<ItemStack> picked = new ArrayList<>();
        List<Long> pickedCounts = new ArrayList<>();
        MultiPickerScreen picker = new MultiPickerScreen(this, false,
                (stack, count) -> {
                    if (stack == null || stack.isEmpty()) return;
                    ItemStack copy = stack.copy();
                    copy.setCount(1);
                    picked.add(copy);
                    pickedCounts.add(Math.max(1L, count));
                },
                fluid -> {}).replacing();
        for (int i = 0; i < items.size(); i++) {
            ItemStack st = items.get(i);
            picker.preselect(st, i < counts.size() ? counts.get(i) : Math.max(1L, st.getCount()));
        }
        picker.focusPreselected(idx);
        picker.setAfterConfirm(() -> {
            ShopEntryEditLayout.replaceAll(items, picked);
            ShopEntryEditLayout.replaceAll(counts, pickedCounts);
        });
        Minecraft.getInstance().setScreen(picker);
    }

    @Override
    protected boolean universalMouseScrolled(double mx, double my, double d) {
        if (inViewport(mx, my)) {
            scroll = ShopEntryEditLayout.clampScroll(scroll - (int) Math.signum(d) * SLOT_ROW_H, contentH(), viewH());
        }
        return true;
    }

    @Override
    protected boolean universalMouseDragged(double mx, double my, int btn, double dx, double dy) {
        int target = ui.drag(my, rowY(), viewH(), contentH(), viewH(), maxScroll());
        if (target >= 0) {
            snapScroll(target);
            return true;
        }
        return super.universalMouseDragged(mx, my, btn, dx, dy);
    }

    @Override
    protected boolean universalMouseReleased(double mx, double my, int btn) {
        ui.release();
        return super.universalMouseReleased(mx, my, btn);
    }

    @Override
    public void removed() {
        ui.cancel();
        super.removed();
    }

    @Override public boolean isPauseScreen() { return false; }
}
