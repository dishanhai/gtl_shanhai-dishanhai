package com.dishanhai.gt_shanhai.client.gui.shop;

import com.dishanhai.gt_shanhai.client.gui.glass.GlassPalette;
import com.dishanhai.gt_shanhai.client.gui.scaled.GuiRenderUtil;
import com.dishanhai.gt_shanhai.client.gui.scaled.ScaledScreen;
import com.dishanhai.gt_shanhai.client.shop.ClientShopCatalog;
import com.dishanhai.gt_shanhai.common.shop.ShopBranch;
import com.dishanhai.gt_shanhai.common.shop.ShopEntry;
import com.dishanhai.gt_shanhai.network.ShanhaiNetwork;
import com.dishanhai.gt_shanhai.network.ShopBranchCopyPacket;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 复制分支到阶段（山海署名，客户端）：从阶段编辑页的「复制分支」进来，列出所有能放下这棵分支的分类节点，
 * 多选后一次发给服务端。复制出的商品是副本，内容跟随主支同步；成本、限购等阶段自己的字段可在副本单独改。
 * 默认勾上与分支同级的兄弟阶段（同一个父节点的其他子分类）。路径放不下就换行，不截断。
 */
public final class ShopBranchCopyScreen extends ScaledScreen {

    private static final int W = 380, H = 300;
    private static final int PAD = 12;
    private static final int ROW_PAD = 4;
    private static final int BOX = 9;

    private final net.minecraft.client.gui.screens.Screen back;
    private final ShopScreen shop;
    private final String sourcePath;
    /** 商品模式：把这些商品各复制到选中的分组；为空 = 分支模式（复制 sourcePath 整棵子树到阶段）。 */
    private final List<ShopEntry> entries;
    private final List<String> candidates = new ArrayList<>();
    private final Set<String> picked = new LinkedHashSet<>();
    private final ShopDialogUi ui = new ShopDialogUi(-7710208, -22016, -14935012, -12303292);
    private int left, top, scroll;

    /** {@code back}：取消时回到的界面；{@code shop}：复制成功后回到的商店。 */
    public ShopBranchCopyScreen(net.minecraft.client.gui.screens.Screen back, ShopScreen shop, String sourcePath) {
        super(Component.literal("复制分支到阶段"));
        this.back = back;
        this.shop = shop;
        this.sourcePath = sourcePath == null ? "" : sourcePath;
        this.entries = List.of();
        collectCandidates();
        targetWidth = W;
        targetHeight = H;
        minScale = 0.1f;
        useOffset = false;
    }

    // ===== 候选节点 =====

    /** 客户端目录里所有分类节点的完整路径（最多四层），保持目录里的顺序。 */
    static List<String> allPaths() {
        List<String> out = new ArrayList<>();
        for (String top : ClientShopCatalog.topCategories()) {
            out.add(top);
            for (String sub : ClientShopCatalog.subCategories(top)) {
                String p2 = top + "/" + sub;
                out.add(p2);
                for (String sub2 : ClientShopCatalog.subCategories2(top, sub)) {
                    String p3 = p2 + "/" + sub2;
                    out.add(p3);
                    for (String sub3 : ClientShopCatalog.subCategories3(top, sub, sub2)) out.add(p3 + "/" + sub3);
                }
            }
        }
        return out;
    }

    /** 商品模式：单个或批量商品复制到不同分组，副本落在所选分类本身，内容跟随原商品同步。 */
    public ShopBranchCopyScreen(net.minecraft.client.gui.screens.Screen back, ShopScreen shop, List<ShopEntry> entries) {
        super(Component.literal("分支到分组"));
        this.back = back;
        this.shop = shop;
        this.sourcePath = "";
        this.entries = List.copyOf(entries);
        collectCandidates();
        targetWidth = W;
        targetHeight = H;
        minScale = 0.1f;
        useOffset = false;
    }

    private boolean entryMode() {
        return !entries.isEmpty();
    }

    private void collectCandidates() {
        if (entryMode()) {
            for (String path : allPaths()) {
                if (ShopBranch.depth(path) > ShopBranch.MAX_DEPTH) continue;
                boolean allThere = true; // 所有商品都已经在这个分组里，就没必要再列
                for (ShopEntry e : entries) if (!path.equals(e.getCategory())) { allThere = false; break; }
                if (!allThere) candidates.add(path);
            }
            return;
        }
        String parent = parentOf(sourcePath);
        for (String path : allPaths()) {
            if (ShopBranch.inBranch(path, sourcePath)) continue;     // 不能复制到自己里面
            if (ShopBranch.depth(path) + 1 > ShopBranch.MAX_DEPTH) continue; // 连分支根都放不下
            candidates.add(path);
            if (!parent.isEmpty() && parentOf(path).equals(parent)) picked.add(path); // 同级兄弟阶段默认勾上
        }
    }

    private static String parentOf(String path) {
        int i = path.lastIndexOf('/');
        return i < 0 ? "" : path.substring(0, i);
    }

    // ===== 几何 =====
    private int listX() { return left + PAD; }
    private int listW() { return W - PAD * 2 - ShopDialogUi.BAR_W - 4; }
    private int listTop() { return top + 64; }
    private int listBottom() { return top + H - 34; }
    private int listH() { return listBottom() - listTop(); }
    private int barX() { return left + W - PAD - ShopDialogUi.BAR_W; }
    private int textW() { return listW() - BOX - 12; }

    private List<FormattedCharSequence> linesOf(String path) {
        return this.font.split(Component.literal(path), textW());
    }

    private int rowH(String path) {
        return Math.max(BOX + 2, linesOf(path).size() * 10) + ROW_PAD * 2;
    }

    private int contentH() {
        int h = 0;
        for (String c : candidates) h += rowH(c);
        return h;
    }

    private int maxScroll() { return ShopEntryEditLayout.maxScroll(contentH(), listH()); }

    @Override
    protected void initScaled() {
        ui.cancel();
        left = Math.max(8, (vWidth - W) / 2);
        top = Math.max(8, (vHeight - H) / 2);
        scroll = ShopEntryEditLayout.clampScroll(scroll, contentH(), listH());
    }

    // ===== 渲染 =====
    @Override
    protected void renderScaledBackground(GuiGraphics g, int mx, int my, float pt) {
        ui.frame(mx, my);
        ShopTheme t = ShopTheme.current();
        t.rootPanel(g, left, top, W, H, left + 5, top + 18, W - 10, H - 23);
        int head = t.glass ? GlassPalette.TEXT_HEAD : -22016;
        int body = t.glass ? GlassPalette.TEXT : -1;
        int muted = t.glass ? GlassPalette.TEXT_MUTED : -5592406;
        g.drawString(font, entryMode() ? "分支到分组" : "复制分支到阶段", left + 10, top + 5, head, !t.glass);
        int y = top + 24;
        if (entryMode()) {
            StringBuilder names = new StringBuilder();
            for (ShopEntry e : entries) names.append(names.length() == 0 ? "" : "、").append(e.goodsDisplayName());
            y = wrapLine(g, "商品: " + names, left + PAD, y, W - PAD * 2, body, t);
            wrapLine(g, "在每个选中的分组里放一份副本，内容跟随原商品同步；成本、限购、折扣等可在副本单独改。已有副本的分组会重新同步。",
                    left + PAD, y + 2, W - PAD * 2, muted, t);
        } else {
            y = wrapLine(g, "分支: " + sourcePath, left + PAD, y, W - PAD * 2, body, t);
            wrapLine(g, "副本内容跟随主支同步；成本、限购、折扣等可在副本单独改。已有副本的阶段会重新同步。",
                    left + PAD, y + 2, W - PAD * 2, muted, t);
        }

        t.region(g, left + PAD - 2, listTop() - 2, W - PAD * 2 + 4, listH() + 4);
        scroll = ShopEntryEditLayout.clampScroll(scroll, contentH(), listH());
        g.enableScissor((int) (listX() * guiScale) + offsetX, (int) (listTop() * guiScale) + offsetY,
                (int) ((listX() + listW()) * guiScale) + offsetX, (int) (listBottom() * guiScale) + offsetY);
        int ry = listTop() - scroll;
        for (String path : candidates) {
            int h = rowH(path);
            if (ry + h > listTop() && ry < listBottom()) drawRow(g, t, path, ry, h, mx, my, body);
            ry += h;
        }
        if (candidates.isEmpty()) {
            g.drawString(font, entryMode() ? "没有可复制到的分组" : "没有可复制到的分类节点", listX() + 4, listTop() + 6, muted, !t.glass);
        }
        g.disableScissor();
        ui.drawScrollbar(g, barX(), listTop(), listH(), contentH(), listH(), scroll, maxScroll(), mx, my);

        drawBtn(g, t, left + PAD, top + H - 26, 58, "§c取消", mx, my);
        if (!entryMode()) drawBtn(g, t, left + PAD + 64, top + H - 26, 70, "§e同级全选", mx, my);
        drawBtn(g, t, left + PAD + 140, top + H - 26, 54, "§7清空", mx, my);
        String ok = picked.isEmpty() ? "§8复制" : "§a复制(" + picked.size() + ")";
        drawBtn(g, t, left + W - PAD - 82, top + H - 26, 82, ok, mx, my);
    }

    private int wrapLine(GuiGraphics g, String text, int x, int y, int w, int color, ShopTheme t) {
        for (FormattedCharSequence line : font.split(Component.literal(text), w)) {
            g.drawString(font, line, x, y, color, !t.glass);
            y += 10;
        }
        return y;
    }

    private void drawRow(GuiGraphics g, ShopTheme t, String path, int y, int h, int mx, int my, int body) {
        boolean hover = GuiRenderUtil.isHovering(mx, my, listX(), y, listW(), h) && my >= listTop() && my < listBottom();
        if (hover) g.fill(listX(), y, listX() + listW(), y + h, t.glass ? 0x30FFFFFF : 0x30FFFFFF);
        int bx = listX() + 4;
        int by = y + (h - BOX) / 2;
        boolean on = picked.contains(path);
        t.box(g, bx, by, BOX, BOX, on ? t.frame : t.frameDark, t.boxBg);
        if (on) g.fill(bx + 2, by + 2, bx + BOX - 2, by + BOX - 2, t.glass ? GlassPalette.ACCENT : -22016);
        int ty = y + ROW_PAD;
        for (FormattedCharSequence line : linesOf(path)) {
            g.drawString(font, line, bx + BOX + 6, ty, body, !t.glass);
            ty += 10;
        }
    }

    private void drawBtn(GuiGraphics g, ShopTheme t, int x, int y, int w, String label, int mx, int my) {
        boolean hover = GuiRenderUtil.isHovering(mx, my, x, y, w, 16);
        t.button(g, font, x, y, w, 16, label, hover && t.glass ? t.frame : t.frameDark,
                hover ? t.buttonHover : t.buttonBg, -1);
    }

    @Override
    public void renderBackground(GuiGraphics g) {
        if (!ShopTheme.current().renderBackdropDim(g, this.width, this.height)) super.renderBackground(g);
    }

    // ===== 交互 =====
    @Override
    protected boolean universalMouseClicked(double mx, double my, int btn) {
        if (btn != 0) return true;
        int by = top + H - 26;
        if (GuiRenderUtil.isHovering(mx, my, left + PAD, by, 58, 16)) {
            leave();
            return true;
        }
        if (!entryMode() && GuiRenderUtil.isHovering(mx, my, left + PAD + 64, by, 70, 16)) {
            String parent = parentOf(sourcePath);
            for (String c : candidates) if (!parent.isEmpty() && parentOf(c).equals(parent)) picked.add(c);
            return true;
        }
        if (GuiRenderUtil.isHovering(mx, my, left + PAD + 140, by, 54, 16)) {
            picked.clear();
            return true;
        }
        if (GuiRenderUtil.isHovering(mx, my, left + W - PAD - 82, by, 82, 16)) {
            confirm();
            return true;
        }
        int dragTo = ui.beginDrag(mx, my, barX(), listTop(), listH(), contentH(), listH(), maxScroll());
        if (dragTo >= 0) {
            scroll = dragTo;
            return true;
        }
        if (my >= listTop() && my < listBottom() && mx >= listX() && mx < listX() + listW()) {
            int ry = listTop() - scroll;
            for (String path : candidates) {
                int h = rowH(path);
                if (my >= ry && my < ry + h) {
                    if (!picked.remove(path)) picked.add(path);
                    return true;
                }
                ry += h;
            }
        }
        return true;
    }

    private void confirm() {
        if (picked.isEmpty()) return;
        if (shop != null && !shop.isCatalogEditUnlocked()) {
            ShopScreen.showMessage(Component.literal("§c[山海商店] 复制分支需要先开启编辑模式（/山海 商店 编辑）"));
            leave();
            return;
        }
        if (entryMode()) {
            List<String> ids = new ArrayList<>();
            for (ShopEntry e : entries) ids.add(e.getStableId());
            ShanhaiNetwork.CHANNEL.sendToServer(
                    ShopBranchCopyPacket.forEntries(ClientShopCatalog.revision(), ids, picked));
        } else {
            ShanhaiNetwork.CHANNEL.sendToServer(new ShopBranchCopyPacket(ClientShopCatalog.revision(), sourcePath, picked));
        }
        Minecraft.getInstance().setScreen(shop != null ? shop : back);
    }

    private void leave() {
        Minecraft.getInstance().setScreen(back);
    }

    @Override
    protected boolean universalMouseScrolled(double mx, double my, double d) {
        if (my >= listTop() && my < listBottom()) {
            scroll = ShopEntryEditLayout.clampScroll(scroll - (int) Math.signum(d) * 18, contentH(), listH());
        }
        return true;
    }

    @Override
    protected boolean universalMouseDragged(double mx, double my, int btn, double dx, double dy) {
        int target = ui.drag(my, listTop(), listH(), contentH(), listH(), maxScroll());
        if (target >= 0) {
            scroll = target;
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

    @Override
    public void onClose() {
        leave();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
