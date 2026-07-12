package com.dishanhai.gt_shanhai.client.gui.shop;

import com.dishanhai.gt_shanhai.client.gui.scaled.GuiRenderUtil;
import com.dishanhai.gt_shanhai.client.gui.scaled.ScaledScreen;
import com.dishanhai.gt_shanhai.common.shop.ExchangeEntry;
import com.dishanhai.gt_shanhai.network.ExchangeEditPacket;
import com.dishanhai.gt_shanhai.network.ShanhaiNetwork;

import dev.architectury.fluid.FluidStack;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluid;
import net.minecraftforge.registries.ForgeRegistries;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/**
 * 兑换条目编辑器（FTBQ 式可视化槽位，客户端，山海署名）。替代原 FTBLib 长列表编辑器。
 *
 * <p>「付出 / 得到」两栏，每栏一排物品槽 + 一排流体槽 + 绿色「+物品/+流体」按钮 + 星火输入框。
 * 点已有槽调 {@link EditorWidgets} 的 FTBLib 单项选择器（自带数量/mB）编辑，右键槽位删除；
 * 点「+」改调 {@link MultiPickerScreen}（同商店编辑器，支持搜索/物品栏/多选一次加入）。
 * 确认打成 {@link ExchangeEditPacket} 发服务端持久化，关闭返回 {@link ExchangeScreen}。</p>
 */
public class ExchangeEditScreen extends ScaledScreen {

    private static final int GOLD = -22016;
    private static final int GOLD_DARK = -7710208;
    private static final int PANEL_BG = -267382768;
    private static final int PANEL_INNER = -266724838;
    private static final int CYAN = -11141121;
    private static final int GRAY = -5592406;
    private static final int WHITE = -1;
    private static final int BTN_BG = -14935012;
    private static final int BTN_HOVER = -12303292;

    private static final int TARGET_W = 720;
    private static final int TARGET_H = 430;
    private static final int SLOT = 20;
    private static final int PITCH = 24;
    private static final int ITEM_MAX = 6;
    private static final int FLUID_MAX = 3;

    /** 一侧草稿：星火 + 物品列表 + 流体列表（各自携带数量/mB）。 */
    private static final class SideDraft {
        BigInteger spark = BigInteger.ZERO;
        final List<ItemStack> items = new ArrayList<>();
        final List<FluidStack> fluids = new ArrayList<>();
    }

    private final ExchangeScreen parent;
    private final ExchangeEntry oldEntry;
    private final boolean isNew;

    private String id;
    private String category;
    private String name;
    private final SideDraft cost = new SideDraft();
    private final SideDraft result = new SideDraft();

    private EditBox idBox, catBox, nameBox, costSparkBox, resultSparkBox;
    private int left, top, panelWidth, panelHeight;

    public ExchangeEditScreen(ExchangeScreen parent, ExchangeEntry entry, boolean isNew) {
        super(Component.literal("编辑兑换"));
        this.parent = parent;
        this.oldEntry = isNew ? null : entry;
        this.isNew = isNew;
        this.targetWidth = TARGET_W;
        this.targetHeight = TARGET_H;
        this.useOffset = false;
        this.minScale = 0.1f;
        this.maxScale = Float.MAX_VALUE;
        if (entry != null) {
            this.id = entry.getId();
            this.category = entry.getCategory();
            this.name = entry.getName();
            fillSide(cost, entry.getCost());
            fillSide(result, entry.getResult());
        } else {
            this.id = "";
            this.category = "杂项";
            this.name = "";
        }
    }

    private static void fillSide(SideDraft dst, ExchangeEntry.Side src) {
        dst.spark = src.spark;
        for (ExchangeEntry.Ingredient in : src.ingredients) {
            if (in.isFluid) {
                Fluid fluid = ForgeRegistries.FLUIDS.getValue(in.id);
                if (fluid != null) dst.fluids.add(FluidStack.create(fluid, Math.max(1L, in.count)));
            } else {
                var item = ForgeRegistries.ITEMS.getValue(in.id);
                if (item != null) dst.items.add(new ItemStack(item, (int) Math.max(1L, Math.min(Integer.MAX_VALUE, in.count))));
            }
        }
    }

    // ===== 布局 =====
    private int contentX() { return left + 12; }
    private int fieldsY() { return top + 24; }
    private int costY() { return top + 48; }
    private int resultY() { return costY() + 74; }
    private int confirmX() { return left + panelWidth - 12 - 70; }
    private int cancelX() { return confirmX() - 6 - 60; }

    @Override
    protected void initScaled() {
        left = 6;
        top = 8;
        panelWidth = Math.max(560, Math.min(TARGET_W - 12, vWidth - 12));
        panelHeight = Math.max(300, Math.min(TARGET_H - 12, vHeight - 16));

        int fy = fieldsY();
        int cx = contentX();
        // ID / 分类 / 显示名 三个短输入框
        idBox = mkBox(cx + 26, fy, 120, id, s -> id = s);
        catBox = mkBox(cx + 26 + 120 + 40, fy, 90, category, s -> category = s);
        nameBox = mkBox(cx + 26 + 120 + 40 + 90 + 52, fy, 120, name, s -> name = s);
        // 星火输入框（付出 / 得到）
        costSparkBox = mkSparkBox(cx + 52, costY(), cost);
        resultSparkBox = mkSparkBox(cx + 52, resultY(), result);
        addRenderableWidget(idBox);
        addRenderableWidget(catBox);
        addRenderableWidget(nameBox);
        addRenderableWidget(costSparkBox);
        addRenderableWidget(resultSparkBox);
    }

    private EditBox mkBox(int x, int y, int w, String val, java.util.function.Consumer<String> setter) {
        EditBox b = new EditBox(this.font, x, y, w, 12, Component.literal(""));
        b.setValue(val == null ? "" : val);
        b.setMaxLength(64);
        b.setBordered(true);
        b.setTextColor(0xFFFFFF);
        b.setResponder(s -> setter.accept(s));
        return b;
    }

    private EditBox mkSparkBox(int x, int y, SideDraft side) {
        EditBox b = new EditBox(this.font, x, y, 110, 12, Component.literal("星火"));
        b.setValue(side.spark.signum() > 0 ? side.spark.toString() : "");
        b.setMaxLength(40);
        b.setBordered(true);
        b.setTextColor(0xFFFF55);
        b.setFilter(s -> s.isEmpty() || s.matches("\\d+"));
        b.setResponder(s -> side.spark = (s == null || s.isEmpty()) ? BigInteger.ZERO : new BigInteger(s));
        return b;
    }

    /** 打开选择器前先把输入框文本落回草稿，避免 init 重建控件时丢失。 */
    private void capture() {
        if (idBox != null) id = idBox.getValue();
        if (catBox != null) category = catBox.getValue();
        if (nameBox != null) name = nameBox.getValue();
        if (costSparkBox != null) cost.spark = parseBig(costSparkBox.getValue());
        if (resultSparkBox != null) result.spark = parseBig(resultSparkBox.getValue());
    }

    private static BigInteger parseBig(String s) {
        if (s == null || s.isEmpty()) return BigInteger.ZERO;
        try { return new BigInteger(s); } catch (NumberFormatException e) { return BigInteger.ZERO; }
    }

    // ===== 渲染 =====
    @Override
    protected void renderScaledBackground(GuiGraphics g, int mx, int my, float pt) {
        g.fill(left, top, left + panelWidth, top + panelHeight, GOLD_DARK);
        g.fill(left + 1, top + 1, left + panelWidth - 1, top + panelHeight - 1, GOLD);
        g.fill(left + 2, top + 2, left + panelWidth - 2, top + panelHeight - 2, PANEL_BG);
        g.fill(left + 5, top + 18, left + panelWidth - 5, top + panelHeight - 5, PANEL_INNER);

        g.drawString(this.font, "§6" + (isNew ? "新增兑换条目" : "编辑兑换条目"), left + 10, top + 5, GOLD, true);
        drawBtn(g, cancelX(), top + 3, 60, 14, "§c取消", mx, my);
        drawBtn(g, confirmX(), top + 3, 70, 14, "§a确认保存", mx, my);

        int cx = contentX();
        int fy = fieldsY();
        // 文本字段标签（框由 super.render 绘制）
        g.drawString(this.font, "§7ID", cx, fy + 2, GRAY, true);
        g.drawString(this.font, "§7分类", cx + 26 + 120 + 12, fy + 2, GRAY, true);
        g.drawString(this.font, "§7名", cx + 26 + 120 + 40 + 90 + 30, fy + 2, GRAY, true);

        drawSideEditor(g, "§a付出", cost, costY(), mx, my);
        drawSideEditor(g, "§e得到", result, resultY(), mx, my);

        g.drawString(this.font, "§8点「+」多选加入，点已有槽编辑数量，右键槽位删除；ID/名留空自动", cx, top + panelHeight - 14, GRAY, true);
    }

    private void drawSideEditor(GuiGraphics g, String label, SideDraft side, int baseY, int mx, int my) {
        int cx = contentX();
        g.drawString(this.font, label, cx, baseY + 2, WHITE, true);
        g.drawString(this.font, "§7星火:", cx + 30, baseY + 2, GRAY, true);
        // 物品排
        int iy = baseY + 16;
        g.drawString(this.font, "§7物品", cx, iy + 6, GRAY, true);
        int sx = cx + 30;
        for (int i = 0; i < side.items.size(); i++) {
            int x = sx + i * PITCH;
            EditorWidgets.itemSlot(g, this.font, x, iy, side.items.get(i), hover(mx, my, x, iy));
        }
        if (side.items.size() < ITEM_MAX) {
            int x = sx + side.items.size() * PITCH;
            EditorWidgets.plusSlot(g, this.font, x, iy, hover(mx, my, x, iy));
        }
        // 流体排
        int fy = baseY + 42;
        g.drawString(this.font, "§7流体", cx, fy + 6, GRAY, true);
        for (int j = 0; j < side.fluids.size(); j++) {
            int x = sx + j * PITCH;
            EditorWidgets.fluidSlot(g, this.font, x, fy, side.fluids.get(j), hover(mx, my, x, fy));
            long mb = side.fluids.get(j).getAmount();
            g.drawCenteredString(this.font, mb + "", x + 10, fy + 21, CYAN);
        }
        if (side.fluids.size() < FLUID_MAX) {
            int x = sx + side.fluids.size() * PITCH;
            EditorWidgets.plusSlot(g, this.font, x, fy, hover(mx, my, x, fy));
        }
    }

    private boolean hover(int mx, int my, int x, int y) {
        return GuiRenderUtil.isHovering(mx, my, x, y, SLOT, SLOT);
    }

    // ===== 交互 =====
    @Override
    protected boolean universalMouseClicked(double mx, double my, int btn) {
        if (GuiRenderUtil.isHovering(mx, my, cancelX(), top + 3, 60, 14)) {
            Minecraft.getInstance().setScreen(parent);
            return true;
        }
        if (GuiRenderUtil.isHovering(mx, my, confirmX(), top + 3, 70, 14)) {
            submit();
            return true;
        }
        if (sideClicked(cost, costY(), mx, my, btn)) return true;
        if (sideClicked(result, resultY(), mx, my, btn)) return true;
        return super.universalMouseClicked(mx, my, btn);
    }

    /** 处理一侧的槽位点击（左键=选/编辑，右键=删除）。命中返回 true。 */
    private boolean sideClicked(SideDraft side, int baseY, double mx, double my, int btn) {
        int cx = contentX();
        int sx = cx + 30;
        int iy = baseY + 16;
        // 物品槽
        for (int i = 0; i < side.items.size(); i++) {
            int x = sx + i * PITCH;
            if (GuiRenderUtil.isHovering(mx, my, x, iy, SLOT, SLOT)) {
                if (btn == 1) { side.items.remove(i); rebuild(); }
                else { final int idx = i; capture(); EditorWidgets.openItemPicker(side.items.get(idx),
                        st -> { if (st == null || st.isEmpty()) side.items.remove(idx); else side.items.set(idx, st); }); }
                return true;
            }
        }
        if (side.items.size() < ITEM_MAX) {
            int x = sx + side.items.size() * PITCH;
            if (GuiRenderUtil.isHovering(mx, my, x, iy, SLOT, SLOT)) {
                capture();
                Minecraft.getInstance().setScreen(new MultiPickerScreen(this, false,
                        st -> { if (st != null && !st.isEmpty() && side.items.size() < ITEM_MAX) side.items.add(st); },
                        fs -> {}));
                return true;
            }
        }
        // 流体槽
        int fy = baseY + 42;
        for (int j = 0; j < side.fluids.size(); j++) {
            int x = sx + j * PITCH;
            if (GuiRenderUtil.isHovering(mx, my, x, fy, SLOT, SLOT)) {
                if (btn == 1) { side.fluids.remove(j); rebuild(); }
                else { final int jdx = j; capture(); EditorWidgets.openFluidPicker(side.fluids.get(jdx),
                        fs -> { if (fs == null || fs.isEmpty()) side.fluids.remove(jdx); else side.fluids.set(jdx, fs); }); }
                return true;
            }
        }
        if (side.fluids.size() < FLUID_MAX) {
            int x = sx + side.fluids.size() * PITCH;
            if (GuiRenderUtil.isHovering(mx, my, x, fy, SLOT, SLOT)) {
                capture();
                Minecraft.getInstance().setScreen(new MultiPickerScreen(this, true,
                        st -> {},
                        fs -> { if (fs != null && !fs.isEmpty() && side.fluids.size() < FLUID_MAX) side.fluids.add(fs); }));
                return true;
            }
        }
        return false;
    }

    /** 删除槽位后就地重排（无子屏，手动重跑布局）。 */
    private void rebuild() {
        capture();
        this.init(Minecraft.getInstance(), this.width, this.height);
    }

    private void submit() {
        capture();
        String finalId = (id == null || id.isBlank()) ? genId() : id.trim();
        String finalCat = (category == null || category.isBlank()) ? "杂项" : category.trim();
        String finalName = name == null ? "" : name.trim();
        ExchangeEntry.Side c = buildSide(cost);
        ExchangeEntry.Side r = buildSide(result);
        ExchangeEntry entry = new ExchangeEntry(finalId, finalCat, finalName, c, r);
        if (!entry.isValid()) {
            var p = Minecraft.getInstance().player;
            if (p != null) p.displayClientMessage(
                    Component.literal("§c[兑换] 两侧都要有内容（星火或物品/流体），未保存"), false);
            return;
        }
        ShanhaiNetwork.CHANNEL.sendToServer(new ExchangeEditPacket(
                isNew ? ExchangeEditPacket.Action.ADD : ExchangeEditPacket.Action.EDIT, oldEntry, entry));
        Minecraft.getInstance().setScreen(parent);
    }

    private static ExchangeEntry.Side buildSide(SideDraft s) {
        List<ExchangeEntry.Ingredient> ing = new ArrayList<>();
        for (ItemStack st : s.items) {
            if (st == null || st.isEmpty()) continue;
            ResourceLocation rid = ForgeRegistries.ITEMS.getKey(st.getItem());
            if (rid != null) ing.add(new ExchangeEntry.Ingredient(rid, false, Math.max(1, st.getCount())));
        }
        for (FluidStack fs : s.fluids) {
            if (fs == null || fs.isEmpty()) continue;
            ResourceLocation rid = ForgeRegistries.FLUIDS.getKey(fs.getFluid());
            if (rid != null) ing.add(new ExchangeEntry.Ingredient(rid, true, Math.max(1L, fs.getAmount())));
        }
        return new ExchangeEntry.Side(s.spark == null ? BigInteger.ZERO : s.spark, ing);
    }

    private static String genId() {
        return "ex_" + Long.toHexString(System.nanoTime());
    }

    // ===== 小工具 =====
    private void drawBtn(GuiGraphics g, int x, int y, int w, int h, String label, int mx, int my) {
        boolean hv = GuiRenderUtil.isHovering(mx, my, x, y, w, h);
        g.fill(x, y, x + w, y + h, GOLD_DARK);
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, hv ? BTN_HOVER : BTN_BG);
        g.drawCenteredString(this.font, label, x + w / 2, y + (h - 8) / 2, WHITE);
    }
}
