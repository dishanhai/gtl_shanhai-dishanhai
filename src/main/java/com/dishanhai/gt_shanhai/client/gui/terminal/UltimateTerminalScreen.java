package com.dishanhai.gt_shanhai.client.gui.terminal;

import com.dishanhai.gt_shanhai.client.gui.glass.*;
import com.dishanhai.gt_shanhai.client.gui.scaled.*;
import com.dishanhai.gt_shanhai.client.gui.shop.AnimatableEditBox;
import com.dishanhai.gt_shanhai.client.gui.shop.ShopGaussBlur;
import com.dishanhai.gt_shanhai.client.gui.shop.ShopUiSounds;
import com.dishanhai.gt_shanhai.common.item.DShanhaiItems;
import com.dishanhai.gt_shanhai.common.item.ShanhaiUltimateTerminalConfig;
import com.dishanhai.gt_shanhai.common.recipe.editor.ShanhaiRecipeEditorAnimation;
import com.dishanhai.gt_shanhai.network.ShanhaiNetwork;
import com.dishanhai.gt_shanhai.network.UltimateTerminalConfigPacket;
import com.dishanhai.gt_shanhai.network.UltimateTerminalConfigPacket.Field;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.common.util.Lazy;
import org.gtlcore.gtlcore.api.gui.BlockMapSelectorWidget;
import org.gtlcore.gtlcore.common.block.BlockMap;
import java.util.*;
import java.util.function.BooleanSupplier;
import static com.dishanhai.gt_shanhai.client.gui.terminal.TerminalGlassUi.*;

/** 山海：終極終端設定。與樣板生成器共用玻璃底板、彈簧動效、換頁模糊與按鈕回饋；所有文字換行不截斷。 */
public final class UltimateTerminalScreen extends ScaledScreen {
    private enum Page { MAIN, REPLACEMENT }
    private enum Kind { TEXT, BUTTON, TOGGLE, FIELD, TIER }
    private static final String PREFIX = "gui.gt_shanhai.ultimate_terminal.";
    private static final int PANEL_W = 420, PANEL_H = 300, CONTENT_W = 396, GAP = 6;
    private static final int TARGET_W = 640, TARGET_H = 420;
    private static final int REPEAT_MAX = 648;
    private static final int PILL_W = 30, PILL_H = 12, TRACK_ON = 0xFF1F7A92;
    private static final List<String> FAMILY_ORDER = List.of("sc", "sepm", "cal", "coil");
    /** 開關排列順序與語系鍵；此處即設定頁的欄位清單。 */
    private static final Field[] TOGGLES = {Field.MIRROR, Field.REPLACE, Field.ABSOLUTE_REPLACE, Field.AE_MODE,
            Field.ORDER_MODE, Field.NO_CHAMBER, Field.DISMANTLE, Field.MODULE_CHECK};
    private static final String[] TOGGLE_KEYS = {"mirror", "replace", "absolute_replace", "ae_mode",
            "order_mode", "no_chamber", "dismantle", "module_check"};

    private final InteractionHand hand;
    private final EnumMap<Field, Boolean> flags = new EnumMap<>(Field.class);
    private final Component bound;
    private int repeat, tier;
    private String family, browse;
    private Page page = Page.MAIN;

    private final List<Element> elements = new ArrayList<>();
    private List<Element> leaving = List.of();
    private int leavingScroll;
    private AnimatableEditBox repeatBox;
    private boolean syncing;
    private final Spring opening = new Spring(Spring.PANEL);
    private final Spring scroll = new Spring(Spring.SCROLL);
    private final SpringTable<String> selection = new SpringTable<>(Spring.HOVER);
    private final SpringTable<String> stagger = new SpringTable<>(Spring.STAGGER);
    private TerminalGlassUi ui;
    private final GlassPanelTexture root = GlassPanelTexture.root(), display = GlassPanelTexture.display();
    private final ShanhaiRecipeEditorAnimation transition = new ShanhaiRecipeEditorAnimation();
    private int direction = 1, stage;
    private int left, top, cursor, contentHeight, scrollTarget;
    private boolean layoutRequested, warmed;

    private static final class Element {
        Kind kind; String key; int x, y, w, h; Component text = Component.empty(), desc = Component.empty(); boolean enabled = true, dim; Runnable click;
        EditBox field; Field flag; ItemStack icon = ItemStack.EMPTY; BooleanSupplier selected = () -> false;
        Element(Kind kind, String key) { this.kind = kind; this.key = key; }
    }

    public UltimateTerminalScreen(InteractionHand hand) {
        super(Component.translatable(PREFIX + "title"));
        this.hand = hand;
        targetWidth = TARGET_W; targetHeight = TARGET_H; renderRenderablesOnly = true;
        // 讀取用副本：Config.get 會補寫 Version / UUID，不能動客戶端真實物品。
        ItemStack copy = Minecraft.getInstance().player.getItemInHand(hand).copy();
        repeat = ShanhaiUltimateTerminalConfig.getRepeatCount(copy);
        flags.put(Field.MIRROR, ShanhaiUltimateTerminalConfig.isMirrored(copy));
        flags.put(Field.REPLACE, ShanhaiUltimateTerminalConfig.isReplaceMode(copy));
        flags.put(Field.ABSOLUTE_REPLACE, ShanhaiUltimateTerminalConfig.isAbsoluteReplaceMode(copy));
        flags.put(Field.AE_MODE, ShanhaiUltimateTerminalConfig.isAeMode(copy));
        flags.put(Field.ORDER_MODE, ShanhaiUltimateTerminalConfig.isOrderMode(copy));
        flags.put(Field.NO_CHAMBER, ShanhaiUltimateTerminalConfig.isNoChamberMode(copy));
        flags.put(Field.DISMANTLE, ShanhaiUltimateTerminalConfig.isDismantleMode(copy));
        flags.put(Field.MODULE_CHECK, ShanhaiUltimateTerminalConfig.isModuleCheckMode(copy));
        family = ShanhaiUltimateTerminalConfig.getReplacementFamily(copy);
        tier = ShanhaiUltimateTerminalConfig.getReplacementTier(copy, family);
        browse = family.isEmpty() || !BlockMap.tierBlockMap.containsKey(family) ? firstFamily() : family;
        var pos = ShanhaiUltimateTerminalConfig.getBoundAe(copy);
        bound = pos == null ? Component.translatable("tooltip.gt_shanhai.ultimate_terminal.unbound")
                : Component.translatable("tooltip.gt_shanhai.ultimate_terminal.bound",
                        pos.dimension().location(), pos.pos().toShortString());
        opening.setTarget(1, now()); transition.reset(0, now());
    }

    private static Component tr(String key, Object... args) { return Component.translatable(PREFIX + key, args); }
    private int contentX() { return left + 12; }
    private int contentY() { return top + 42; }
    private int viewportHeight() { return PANEL_H - 90; }

    // ---------- 資料 ----------
    private static List<String> families() {
        var keys = new ArrayList<>(BlockMap.tierBlockMap.keySet());
        keys.sort(Comparator.comparingInt((String key) -> {
            int index = FAMILY_ORDER.indexOf(key);
            return index < 0 ? FAMILY_ORDER.size() : index;
        }).thenComparing(Comparator.naturalOrder()));
        return keys;
    }
    private static String firstFamily() { var keys = families(); return keys.isEmpty() ? "" : keys.get(0); }
    private static Block[] tiersOf(String key) {
        Lazy<Block[]> lazy = BlockMap.tierBlockMap.get(key);
        return lazy == null ? new Block[0] : lazy.get();
    }
    private static Component familyName(String key) {
        try { return BlockMapSelectorWidget.getBlock(key); } catch (IllegalStateException unknown) { return Component.literal(key); }
    }
    private Component replacementLabel() {
        Block[] blocks = tiersOf(family);
        if (family.isEmpty() || blocks.length == 0) return tr("replacement_none");
        return Component.literal("(").append(familyName(family)).append(Component.literal(" : "))
                .append(blocks[Math.min(tier, blocks.length - 1)].getName()).append(Component.literal(")"));
    }
    private void send(Field field, int value, String text) {
        ShanhaiNetwork.CHANNEL.sendToServer(new UltimateTerminalConfigPacket(hand, field, value, text));
    }
    private void setRepeat(int value) {
        int clamped = Math.max(0, Math.min(REPEAT_MAX, value));
        if (repeatBox != null && !repeatBox.getValue().equals(String.valueOf(clamped))) {            syncing = true;
            try { repeatBox.setValue(String.valueOf(clamped)); } finally { syncing = false; }
        }
        if (clamped == repeat) return;
        repeat = clamped; send(Field.REPEAT, clamped, "");
    }
    private void toggle(Field field) {
        boolean next = !flags.get(field); flags.put(field, next); send(field, next ? 1 : 0, "");
    }
    private void selectTier(int index) {
        family = browse; tier = index; send(Field.REPLACEMENT, index, family);
    }

    // ---------- 佈局 ----------
    @Override protected void initScaled() {
        left = (vWidth - PANEL_W) / 2; top = (vHeight - PANEL_H) / 2;
        ui = new TerminalGlassUi(font);
        if (repeatBox == null) {
            repeatBox = new AnimatableEditBox(font, 0, 0, 20, 20, tr("repeat"));
            repeatBox.setMaxLength(3);
            repeatBox.setFilter(value -> value.chars().allMatch(Character::isDigit));
            repeatBox.setTextColor(GlassPalette.TEXT);
            repeatBox.setValue(String.valueOf(repeat));
            repeatBox.setResponder(value -> {
                if (syncing) return;
                // 輸入中不回寫輸入框（清空或補零時才不會被改掉），只有超上限才夾回。
                int parsed = value.isEmpty() ? 0 : Integer.parseInt(value);
                if (parsed > REPEAT_MAX) setRepeat(parsed);
                else if (parsed != repeat) { repeat = parsed; send(Field.REPEAT, parsed, ""); }
            });
        }
        rebuildLayout();
    }
    private void rebuildLayout() {
        boolean focused = repeatBox != null && repeatBox.isFocused();
        clearWidgets(); elements.clear(); cursor = 0;
        if (page == Page.MAIN) layoutMain(); else layoutReplacement();
        contentHeight = cursor;
        scrollTarget = Math.min(scrollTarget, Math.max(0, contentHeight - viewportHeight()));
        if (scroll.value() > Math.max(0, contentHeight - viewportHeight())) scroll.snap(scrollTarget);
        if (page == Page.MAIN) {
            addWidget(repeatBox);
            if (focused) { setFocused(repeatBox); repeatBox.setFocused(true); }
        }
        layoutRequested = false;
    }
    private void layoutMain() {
        text(tr("repeat")); description(tr("repeat.desc"));
        Element minus10 = button("repeat.m10", Component.literal("-10"), () -> setRepeat(repeat - 10));
        Element minus1 = button("repeat.m1", Component.literal("-1"), () -> setRepeat(repeat - 1));
        Element field = new Element(Kind.FIELD, "repeat.field"); field.field = repeatBox;
        Element plus1 = button("repeat.p1", Component.literal("+1"), () -> setRepeat(repeat + 1));
        Element plus10 = button("repeat.p10", Component.literal("+10"), () -> setRepeat(repeat + 10));
        row(minus10, minus1, field, plus1, plus10);
        // 每個開關獨佔一行：標籤下方常駐功能說明（換行不截斷），右側才是開關。
        for (int i = 0; i < TOGGLES.length; i++) row(toggleCell(i));
        Element replacement = button("replacement", tr("tier_blocks").copy().append(Component.literal(" ")).append(replacementLabel()),
                () -> showPage(Page.REPLACEMENT));
        row(replacement);
        description(tr("tier_blocks.desc"));
    }
    private Element toggleCell(int index) {
        Field field = TOGGLES[index];
        Element cell = new Element(Kind.TOGGLE, "toggle." + TOGGLE_KEYS[index]);
        cell.text = tr(TOGGLE_KEYS[index]); cell.desc = tr(TOGGLE_KEYS[index] + ".desc");
        cell.flag = field; cell.click = () -> toggle(field);
        return cell;
    }
    private int toggleTextWidth(int cellWidth) { return Math.max(1, cellWidth - 12 - PILL_W - GAP); }
    /** 說明文字（比標題暗一階），同樣整段換行。 */
    private void description(Component value) {
        int h = Math.max(font.lineHeight, font.split(value, CONTENT_W).size() * font.lineHeight);
        Element e = new Element(Kind.TEXT, "desc." + elements.size());
        e.x = 0; e.y = cursor; e.w = CONTENT_W; e.h = h; e.text = value; e.dim = true; elements.add(e); cursor += h + GAP;
    }
    private void layoutReplacement() {
        text(tr("replacement"));
        var keys = families();
        Element[] tabs = new Element[keys.size()];
        for (int i = 0; i < tabs.length; i++) {
            String key = keys.get(i);
            tabs[i] = button("family." + key, familyName(key), () -> { browse = key; restartStagger(); layoutRequested = true; });
            tabs[i].selected = () -> key.equals(browse);
        }
        if (tabs.length > 0) row(tabs);
        text(replacementLabel());
        Block[] blocks = tiersOf(browse);
        int columns = 2, w = (CONTENT_W - (columns - 1) * 4) / columns;
        int[] heights = new int[(blocks.length + columns - 1) / columns];
        Arrays.fill(heights, 24);
        for (int i = 0; i < blocks.length; i++)
            heights[i / columns] = Math.max(heights[i / columns], font.split(blocks[i].getName(), tierTextWidth(w)).size() * font.lineHeight + 10);
        int y = cursor;
        for (int r = 0; r < heights.length; r++) {
            for (int c = 0; c < columns && r * columns + c < blocks.length; c++) {
                int index = r * columns + c; String owner = browse;
                Element cell = new Element(Kind.TIER, "tier." + owner + "." + index);
                cell.x = c * (w + 4); cell.y = y; cell.w = w; cell.h = heights[r];
                cell.text = blocks[index].getName(); cell.icon = new ItemStack(blocks[index]);
                cell.click = () -> selectTier(index);
                cell.selected = () -> owner.equals(family) && index == tier;
                elements.add(cell);
            }
            y += heights[r] + 4;
        }
        cursor = y + 2;
    }
    private static int tierTextWidth(int cellWidth) { return Math.max(1, cellWidth - 29); }
    private void restartStagger() {
        stagger.clear();
        int count = tiersOf(browse).length;
        for (int i = 0; i < count; i++)
            stagger.start("tier." + browse + "." + i, reduced() ? 1 : 0, 1, now(), Math.min(240L, i * 12L));
    }
    private void text(Component value) {
        int h = Math.max(font.lineHeight, font.split(value, CONTENT_W).size() * font.lineHeight);
        Element e = new Element(Kind.TEXT, "text." + elements.size());
        e.x = 0; e.y = cursor; e.w = CONTENT_W; e.h = h; e.text = value; elements.add(e); cursor += h + GAP;
    }
    private Element button(String key, Component text, Runnable click) {
        Element e = new Element(Kind.BUTTON, key); e.text = text; e.click = click; return e;
    }
    private void row(Element... cells) {
        int w = (CONTENT_W - (cells.length - 1) * GAP) / cells.length, h = 22;
        for (Element cell : cells) {
            int textWidth = cell.kind == Kind.TOGGLE ? toggleTextWidth(w) : Math.max(1, w - 12);
            if (cell.kind == Kind.BUTTON)
                h = Math.max(h, font.split(cell.text, textWidth).size() * font.lineHeight + 10);
            else if (cell.kind == Kind.TOGGLE)
                h = Math.max(h, (font.split(cell.text, textWidth).size() + font.split(cell.desc, textWidth).size()) * font.lineHeight + 10);
        }
        for (int i = 0; i < cells.length; i++) {
            Element cell = cells[i]; cell.x = i * (w + GAP); cell.y = cursor; cell.w = w; cell.h = h;
            elements.add(cell);
        }
        cursor += h + GAP;
    }
    private void showPage(Page next) {
        if (next == page) return;
        leaving = List.copyOf(elements); leavingScroll = Math.round(scroll.value());
        direction = next == Page.MAIN ? -1 : 1;
        page = next; scrollTarget = 0; scroll.snap(0);
        transition.advance(++stage, now());
        if (reduced()) { transition.reset(stage, now()); leaving = List.of(); }
        if (next == Page.REPLACEMENT) restartStagger();
        rebuildLayout();
    }

    // ---------- 每刻 ----------
    @Override public void tick() {
        super.tick();
        var player = Minecraft.getInstance().player;
        if (player == null || !player.getItemInHand(hand).is(DShanhaiItems.ULTIMATE_TERMINAL.get())) { onClose(); return; }
        if (!warmed) { warmed = true; if (!reduced()) GlassBlur.warmUp(); }
        if (repeatBox != null) repeatBox.tick();
        if (layoutRequested) rebuildLayout();
    }

    // ---------- 繪製 ----------
    @Override protected void renderScaledBackground(GuiGraphics g, int mx, int my, float pt) {
        long at = now(); ShopGaussBlur.clear(); ShopGaussBlur.clearTopLayer();
        float open = spring(opening, 1, at);
        spring(scroll, scrollTarget, at);
        ui.frame(mx, my, at); selection.sweep(at);
        transition.advance(stage, at);
        if (reduced()) { transition.reset(stage, at); leaving = List.of(); }
        GuiRenderUtil.withPanelMotion(g, left + PANEL_W / 2f, top + PANEL_H / 2f, open, () -> {
            root.draw(g, mx, my, left, top, PANEL_W, PANEL_H);
            ui.wrap(g, tr("title"), left + 12, top + 10, PANEL_W - 66, GlassPalette.TEXT_HEAD);
            ui.button(g, "close", left + PANEL_W - 40, top + 8, 28, 22, Component.literal("×"), 16, true, mx, my, null);
            ui.button(g, "back", left + 12, top + PANEL_H - 30, 90, 22, tr("back"), 78, page != Page.MAIN, mx, my, null);
            ui.wrap(g, bound, left + 110, top + PANEL_H - 31, PANEL_W - 122, GlassPalette.TEXT_MUTED);
            display.draw(g, mx, my, contentX() - 3, contentY() - 3, CONTENT_W + 6, viewportHeight() + 6);
            if (contentHeight > viewportHeight()) {
                int thumb = Math.max(14, viewportHeight() * viewportHeight() / contentHeight);
                int thumbY = contentY() + Math.round((viewportHeight() - thumb) * scroll.value() / Math.max(1, contentHeight - viewportHeight()));
                g.fill(contentX() + CONTENT_W + 2, contentY(), contentX() + CONTENT_W + 4, contentY() + viewportHeight(), GlassPalette.DISPLAY_EDGE);
                g.fill(contentX() + CONTENT_W + 2, thumbY, contentX() + CONTENT_W + 4, thumbY + thumb, GlassPalette.ACCENT);
            }
            TerminalGlassUi.scissor(g, contentX(), contentY(), CONTENT_W, viewportHeight());
            try {
                if (!reduced() && transition.moving(at) && !leaving.isEmpty()) {
                    renderPage(g, mx, my, pt, leaving, leavingScroll, -direction * transition.progress(at) * CONTENT_W, false);
                } else leaving = List.of();
                renderPage(g, mx, my, pt, elements, Math.round(scroll.value()), slideOffset(), true);
            } finally { g.disableScissor(); }
            if (!reduced() && transition.moving(at))
                GlassBlur.requestPage(g, new GuiRect(contentX(), contentY(), CONTENT_W, viewportHeight()), GlassMotion.pageSlide(transition.linear(at)));
        });
    }
    private float slideOffset() { return reduced() ? 0 : direction * (1 - transition.progress(now())) * CONTENT_W; }
    private void renderPage(GuiGraphics g, int mx, int my, float pt, List<Element> rows, int scrollY, float slide, boolean active) {
        g.pose().pushPose();
        try { g.pose().translate(slide, 0, 0); drawElements(g, mx, my, pt, rows, scrollY, active); }
        finally { g.pose().popPose(); }
    }
    private void drawElements(GuiGraphics g, int mx, int my, float pt, List<Element> rows, int scrollY, boolean interactive) {
        long at = now();
        for (Element e : rows) {
            int x = contentX() + e.x, y = contentY() + e.y - scrollY;
            if (y + e.h < contentY() || y > contentY() + viewportHeight()) continue;
            boolean over = interactive && hit(mx, my, x, y, e.w, e.h) && hit(mx, my, contentX(), contentY(), CONTENT_W, viewportHeight());
            switch (e.kind) {
                case FIELD -> {
                    e.field.setX(x); e.field.setY(y + 1); e.field.setWidth(e.w); e.field.setHeight(20);
                    e.field.render(g, mx, my, pt);
                }
                case TOGGLE -> {
                    float on = follow(selection, e.key, flags.get(e.flag) ? 1 : 0, at);
                    int textWidth = toggleTextWidth(e.w);
                    ui.button(g, e.key, x, y, e.w, e.h, e.text, textWidth, true, mx, my, () -> {
                        ui.wrap(g, e.desc, x + 6, y + 5 + ui.height(e.text, textWidth), textWidth, GlassPalette.TEXT_DIM);
                        drawPill(g, x + e.w - 6 - PILL_W, y + (e.h - PILL_H) / 2, on);
                    });
                }
                case TIER -> {
                    float appear = reduced() ? 1 : stagger.valueOr(e.key, 1, at);
                    GuiRenderUtil.withPanelMotion(g, x + e.w / 2f, y + e.h / 2f, appear, () -> {
                        g.pose().pushPose(); g.pose().translate(0, (1 - appear) * 8, 0);
                        ui.button(g, e.key, x, y, e.w, e.h, Component.empty(), 1, true, mx, my, null);
                        float chosen = follow(selection, e.key, e.selected.getAsBoolean() ? 1 : 0, at);
                        GlassCell.draw(g, x + 3, y + 3, 20, GlassPalette.ROW_EDGE, chosen, over);
                        if (!e.icon.isEmpty()) g.renderItem(e.icon, x + 5, y + 5);
                        ui.wrap(g, e.text, x + 27, y + 5, tierTextWidth(e.w), GlassPalette.TEXT);
                        g.pose().popPose();
                    });
                }
                case BUTTON -> {
                    ui.button(g, e.key, x, y, e.w, e.h, e.text, Math.max(1, e.w - 12), true, mx, my, null);
                    float chosen = follow(selection, e.key, e.selected.getAsBoolean() ? 1 : 0, at);
                    if (chosen > 0.02f) g.renderOutline(x, y, e.w, e.h, GuiMotion.lerpColor(0x0069E8FF, GlassPalette.ACCENT, chosen));
                }
                case TEXT -> ui.wrap(g, e.text, x, y, e.w, e.dim ? GlassPalette.TEXT_DIM : GlassPalette.TEXT_MUTED);
            }
        }
    }
    private void drawPill(GuiGraphics g, int x, int y, float on) {
        g.fill(x, y, x + PILL_W, y + PILL_H, GuiMotion.lerpColor(GlassPalette.DISPLAY_FILL, TRACK_ON, on));
        g.renderOutline(x, y, PILL_W, PILL_H, GuiMotion.lerpColor(GlassPalette.ROW_EDGE, GlassPalette.ACCENT, on));
        int knob = PILL_H - 4;
        g.fill(x + 2 + Math.round(on * (PILL_W - 4 - knob)), y + 2, x + 2 + Math.round(on * (PILL_W - 4 - knob)) + knob, y + 2 + knob,
                GuiMotion.lerpColor(GlassPalette.TEXT_DIM, GlassPalette.TEXT, on));
    }
    // ---------- 輸入 ----------
    @Override protected boolean universalMouseClicked(double mx, double my, int button) {
        if (hit(mx, my, left + PANEL_W - 40, top + 8, 28, 22)) { onClose(); return true; }
        if (page != Page.MAIN && hit(mx, my, left + 12, top + PANEL_H - 30, 90, 22)) { showPage(Page.MAIN); return true; }
        if (!hit(mx, my, contentX(), contentY(), CONTENT_W, viewportHeight())) return false;
        int scrollY = Math.round(scroll.value());
        for (Element e : elements) {
            int x = contentX() + e.x, y = contentY() + e.y - scrollY;
            if (!hit(mx, my, x, y, e.w, e.h)) continue;
            if (e.kind == Kind.FIELD) {
                setFocused(e.field); e.field.setFocused(true);
                e.field.setX(x); e.field.setY(y + 1);
                return e.field.mouseClicked(mx, my, button);
            }
            if (e.click == null || button != 0) continue;
            ui.feedback.press(e.key.hashCode(), x, y, e.w, e.h, true, now());
            ShopUiSounds.click(); e.click.run(); return true;
        }
        return false;
    }
    @Override protected boolean universalMouseReleased(double mx, double my, int button) {
        ui.feedback.release(now()); return super.universalMouseReleased(mx, my, button);
    }
    @Override protected boolean universalMouseScrolled(double mx, double my, double amount) {
        if (!hit(mx, my, contentX(), contentY(), CONTENT_W, viewportHeight())) return false;
        scrollTarget = Math.max(0, Math.min(Math.max(0, contentHeight - viewportHeight()), scrollTarget - (int) (amount * 36)));
        return true;
    }
    @Override public boolean keyPressed(int key, int scan, int mods) {
        if (key == 256) {
            if (repeatBox != null && repeatBox.isFocused()) { repeatBox.setFocused(false); setFocused(null); return true; }
            if (page != Page.MAIN) { showPage(Page.MAIN); return true; }
        }
        return super.keyPressed(key, scan, mods);
    }
    @Override public boolean isPauseScreen() { return false; }
    @Override public void removed() {
        ui.feedback.cancel(now()); ShopGaussBlur.clear(); ShopGaussBlur.clearTopLayer(); super.removed();
    }
}
