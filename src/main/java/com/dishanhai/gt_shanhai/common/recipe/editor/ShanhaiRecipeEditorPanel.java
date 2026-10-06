package com.dishanhai.gt_shanhai.common.recipe.editor;

import com.gregtechceu.gtceu.api.gui.GuiTextures;
import com.lowdragmc.lowdraglib.gui.ingredient.IGhostIngredientTarget;
import com.lowdragmc.lowdraglib.gui.ingredient.Target;
import com.lowdragmc.lowdraglib.gui.texture.ColorRectTexture;
import com.lowdragmc.lowdraglib.gui.texture.GuiTextureGroup;
import com.lowdragmc.lowdraglib.gui.texture.TextTexture;
import com.lowdragmc.lowdraglib.gui.widget.ButtonWidget;
import com.lowdragmc.lowdraglib.gui.widget.DraggableScrollableWidgetGroup;
import com.lowdragmc.lowdraglib.gui.widget.LabelWidget;
import com.lowdragmc.lowdraglib.gui.widget.SelectorWidget;
import com.lowdragmc.lowdraglib.gui.widget.TextFieldWidget;
import com.lowdragmc.lowdraglib.gui.widget.Widget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;
import net.minecraft.client.gui.GuiGraphics;
import org.joml.Matrix4f;
import org.joml.Vector4f;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Three stacked pages. Only one is interactive; the others slide off during a stage change.
 * WidgetGroup ghost lookup is one level deep, so each page stays a direct child.
 */
public final class ShanhaiRecipeEditorPanel extends WidgetGroup implements IGhostIngredientTarget {

    public static final int PAGE_CELLS = 12;
    public static final int ITEM_PAGE = 16;
    public static final int FLUID_PAGE = 16;
    private static final int ITEM_COLS = 8;
    private static final int FLUID_COLS = 8;
    private static final int PAGE_Y = 24;

    private final ShanhaiRecipeEditorWidget host;
    private final StagePage selectPage;
    private final StagePage editPage;
    private final StagePage reviewPage;
    private final DraggableScrollableWidgetGroup resultList;
    private final List<ShanhaiIOWidget> inputItemCells = new ArrayList<>();
    private final List<ShanhaiIOWidget> inputFluidCells = new ArrayList<>();
    private final List<ShanhaiIOWidget> outputItemCells = new ArrayList<>();
    private final List<ShanhaiIOWidget> outputFluidCells = new ArrayList<>();
    private final int[] shownItemIn = new int[ITEM_PAGE];
    private final int[] shownFluidIn = new int[FLUID_PAGE];
    private final int[] shownItemOut = new int[ITEM_PAGE];
    private final int[] shownFluidOut = new int[FLUID_PAGE];
    private ShanhaiQuerySlotWidget querySlot;

    public ShanhaiRecipeEditorPanel(ShanhaiRecipeEditorWidget host) {
        super(8, 32, ShanhaiRecipeEditorWidget.WIDTH - 16, 310);
        this.host = host;
        Arrays.fill(shownItemIn, -1);
        Arrays.fill(shownFluidIn, -1);
        Arrays.fill(shownItemOut, -1);
        Arrays.fill(shownFluidOut, -1);
        selectPage = page();
        editPage = page();
        reviewPage = page();
        resultList = new DraggableScrollableWidgetGroup(4, 98, getSizeWidth() - 8, 128)
                .setYScrollBarWidth(5)
                .setYBarStyle(new ColorRectTexture(0xff79c9dd), GuiTextures.VANILLA_BUTTON)
                .setBackground(GuiTextures.DISPLAY)
                .setUseScissor(true);

        addWidget(new ShanhaiRecipeTabBarWidget(host, 4, 2, getSizeWidth() - 8));
        buildSelectPage();
        buildEditPage();
        buildReviewPage();
        addWidget(selectPage);
        addWidget(editPage);
        addWidget(reviewPage);
        markClientLabels(this);
        applyStage(false);
    }

    @Override
    public List<Target> getPhantomTargets(Object ingredient) {
        return super.getPhantomTargets(ingredient);
    }

    public void mountSelector(SelectorWidget selector) {
        selectPage.addWidget(selector);
    }

    public void refreshCards(List<ShanhaiRecipeQuery.Card> cards) {
        resultList.clearAllWidgets();
        int width = Math.max(120, resultList.getSizeWidth() - 16);
        for (int index = 0; index < cards.size(); index++) {
            ShanhaiRecipeQuery.Card card = cards.get(index);
            resultList.addWidget(new ShanhaiRecipeCardWidget(
                    2, index * (ShanhaiRecipeCardWidget.CH + 2), width, card,
                    () -> host.requestDetail(card)));
        }
    }

    public void bindTable(ShanhaiIoTable table) {
        inputItemCells.forEach(cell -> cell.setTable(table));
        inputFluidCells.forEach(cell -> cell.setTable(table));
        outputItemCells.forEach(cell -> cell.setTable(table));
        outputFluidCells.forEach(cell -> cell.setTable(table));
        refreshIo();
    }

    public void clearQuerySlot() {
        if (querySlot != null) querySlot.clear();
    }

    public void applyStage(boolean animate) {
        long now = ShanhaiRecipeEditorAnimation.nowMs();
        if (!animate || host.fromStage == host.stage) {
            host.animation.reset(host.stage, now);
        } else {
            host.animation.reset(host.fromStage, now);
            host.animation.advance(host.stage, now);
        }
        layoutMotion(now);
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        layoutMotion(ShanhaiRecipeEditorAnimation.nowMs());
        refreshIo();
    }

    @Override
    public void drawInBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        layoutMotion(ShanhaiRecipeEditorAnimation.nowMs());
        super.drawInBackground(graphics, mouseX, mouseY, partialTicks);
    }

    /**
     * The offset is a float drawn with a pose translate. Moving widget positions
     * every frame rebuilds the whole tree and drops the slide back to tick rate.
     */
    private void layoutMotion(long now) {
        host.animation.advance(host.stage, now);
        StagePage outgoing = (StagePage) page(host.fromStage);
        StagePage incoming = (StagePage) page(host.stage);
        if (host.animation.moving(now) && outgoing != incoming) {
            float progress = host.animation.progress(now);
            float travel = getSizeWidth();
            int direction = host.fromStage < host.stage ? -1 : 1;
            show(outgoing, direction * travel * progress, false);
            show(incoming, -direction * travel * (1f - progress), false);
            int other = 3 - host.fromStage - host.stage;
            if (other != host.fromStage && other != host.stage) show((StagePage) page(other), 0f, false, false);
            return;
        }
        show(selectPage, 0f, host.stage == ShanhaiRecipeEditorWidget.STAGE_SELECT);
        show(editPage, 0f, host.stage == ShanhaiRecipeEditorWidget.STAGE_EDIT);
        show(reviewPage, 0f, host.stage == ShanhaiRecipeEditorWidget.STAGE_REVIEW);
    }

    private void refreshIo() {
        fillPage(shownItemIn, host.inputItemPage, 0, host.ioTable.itemIn(), ITEM_PAGE);
        fillPage(shownFluidIn, host.inputFluidPage, host.ioTable.itemIn(), host.ioTable.fluidIn(), FLUID_PAGE);
        fillPage(shownItemOut, host.outputItemPage, host.ioTable.inSection(), host.ioTable.itemOut(), ITEM_PAGE);
        fillPage(shownFluidOut, host.outputFluidPage,
                host.ioTable.inSection() + host.ioTable.itemOut(), host.ioTable.fluidOut(), FLUID_PAGE);
        boolean editing = host.selectedBase != null && (host.stage == ShanhaiRecipeEditorWidget.STAGE_EDIT
                || (host.fromStage == ShanhaiRecipeEditorWidget.STAGE_EDIT
                && host.animation.moving(ShanhaiRecipeEditorAnimation.nowMs())));
        showPage(inputItemCells, shownItemIn, editing);
        showPage(inputFluidCells, shownFluidIn, editing);
        showPage(outputItemCells, shownItemOut, editing);
        showPage(outputFluidCells, shownFluidOut, editing);
    }

    private static void fillPage(int[] dest, int page, int base, int count, int pageSize) {
        Arrays.fill(dest, -1);
        for (int i = 0; i < dest.length && i < pageSize; i++) {
            int section = page * pageSize + i;
            if (section >= 0 && section < count) dest[i] = base + section;
        }
    }

    private static void showPage(List<ShanhaiIOWidget> cells, int[] shown, boolean editing) {
        for (int i = 0; i < cells.size() && i < shown.length; i++) {
            cells.get(i).setVisible(editing && shown[i] >= 0);
        }
    }

    private void buildSelectPage() {
        selectPage.addWidget(new ShanhaiRecipeMachineSlotWidget(4, 4,
                () -> host.machineStack, host::setMachine));
        selectPage.addWidget(new LabelWidget(32, 8, () -> host.machineStack.isEmpty()
                ? "§8未放入 GT 机器，也可直接按类型或配方 ID 搜索"
                : "§f" + host.machineStack.getHoverName().getString()
                + " §7· 已映射 " + host.machineTypes.size() + " 种"));
        selectPage.addWidget(textField(4, 54, 200, 18, () -> host.typeFilter,
                value -> {
                    host.typeFilter = ShanhaiRecipeEditorWidget.limit(value);
                    host.scheduleDraftSave();
                }));
        selectPage.addWidget(textField(208, 54, 200, 18, () -> host.searchText,
                value -> {
                    host.searchText = ShanhaiRecipeEditorWidget.limit(value);
                    host.scheduleDraftSave();
                }));
        querySlot = new ShanhaiQuerySlotWidget(416, 52, id -> {
            host.searchText = id == null ? "" : id;
            host.queryPage = 0;
            host.flushDraftSave();
            host.query();
        });
        selectPage.addWidget(querySlot);
        selectPage.addWidget(button(4, 76, 72, 16, "搜索", () -> {
            host.queryPage = 0;
            host.flushDraftSave();
            host.query();
        }));
        selectPage.addWidget(button(80, 76, 72, 16, "清空", () -> {
            host.typeFilter = "";
            host.searchText = "";
            host.queryPage = 0;
            clearQuerySlot();
            host.flushDraftSave();
            host.query();
        }));
        selectPage.addWidget(new LabelWidget(160, 80, () -> host.queryTotal <= 0
                ? "§8等待配方卡片" : "§a共 " + host.queryTotal + " 条"));
        selectPage.addWidget(resultList);
        selectPage.addWidget(button(4, 232, 36, 16, "<", () -> host.shiftQueryPage(-1)));
        selectPage.addWidget(new LabelWidget(44, 236, () -> "第 " + (host.queryPage + 1)
                + "/" + host.pageCount(host.queryTotal, ShanhaiRecipeEditorWidget.QUERY_PAGE_SIZE) + " 页"));
        selectPage.addWidget(button(130, 232, 36, 16, ">", () -> host.shiftQueryPage(1)));
        selectPage.addWidget(button(4, 256, 150, 18, "刷新配方表", host::query));
        selectPage.addWidget(button(340, 256, 156, 18, "进入编辑",
                () -> host.setStage(ShanhaiRecipeEditorWidget.STAGE_EDIT, true)));
    }

    private void buildEditPage() {
        editPage.addWidget(new LabelWidget(4, 4, () -> host.selectedCard == null
                ? "§8从配方卡片进入这一屏" : "§f" + host.compact(host.selectedCard.recipeId(), 64)));
        editPage.addWidget(new LabelWidget(4, 18, "§7耗时 / tick"));
        editPage.addWidget(new LabelWidget(150, 18, "§7EU/t"));
        editPage.addWidget(textField(4, 30, 130, 18, () -> host.durationText,
                value -> {
                    host.durationText = ShanhaiRecipeEditorWidget.limit(value);
                    host.scheduleDraftSave();
                }));
        editPage.addWidget(textField(150, 30, 130, 18, () -> host.eutText,
                value -> {
                    host.eutText = ShanhaiRecipeEditorWidget.limit(value);
                    host.scheduleDraftSave();
                }));
        editPage.addWidget(new LabelWidget(4, 50, "§a输入物品"));
        editPage.addWidget(new LabelWidget(252, 50, "§d输出物品"));
        addGrid(inputItemCells, shownItemIn, ITEM_PAGE, 4, 62, ITEM_COLS);
        addGrid(outputItemCells, shownItemOut, ITEM_PAGE, 252, 62, ITEM_COLS);
        editPage.addWidget(new LabelWidget(4, 108, "§b输入流体"));
        editPage.addWidget(new LabelWidget(252, 108, "§b输出流体"));
        addGrid(inputFluidCells, shownFluidIn, FLUID_PAGE, 4, 120, FLUID_COLS);
        addGrid(outputFluidCells, shownFluidOut, FLUID_PAGE, 252, 120, FLUID_COLS);
        editPage.addWidget(button(4, 166, 18, 14, "<", () -> host.shiftInputItemPage(-1)));
        editPage.addWidget(new LabelWidget(24, 169, () -> host.pageLabel(
                "物品", host.inputItemPage, host.ioTable.itemIn(), ITEM_PAGE)));
        editPage.addWidget(button(78, 166, 18, 14, ">", () -> host.shiftInputItemPage(1)));
        editPage.addWidget(button(100, 166, 36, 14, "加页", host::growInput));
        editPage.addWidget(button(140, 166, 18, 14, "<", () -> host.shiftInputFluidPage(-1)));
        editPage.addWidget(new LabelWidget(160, 169, () -> host.pageLabel(
                "流体", host.inputFluidPage, host.ioTable.fluidIn(), FLUID_PAGE)));
        editPage.addWidget(button(214, 166, 18, 14, ">", () -> host.shiftInputFluidPage(1)));
        editPage.addWidget(button(252, 166, 18, 14, "<", () -> host.shiftOutputItemPage(-1)));
        editPage.addWidget(new LabelWidget(272, 169, () -> host.pageLabel(
                "物品", host.outputItemPage, host.ioTable.itemOut(), ITEM_PAGE)));
        editPage.addWidget(button(326, 166, 18, 14, ">", () -> host.shiftOutputItemPage(1)));
        editPage.addWidget(button(348, 166, 36, 14, "加页", host::growOutput));
        editPage.addWidget(button(388, 166, 18, 14, "<", () -> host.shiftOutputFluidPage(-1)));
        editPage.addWidget(new LabelWidget(408, 169, () -> host.pageLabel(
                "流体", host.outputFluidPage, host.ioTable.fluidOut(), FLUID_PAGE)));
        editPage.addWidget(button(462, 166, 18, 14, ">", () -> host.shiftOutputFluidPage(1)));
        editPage.addWidget(button(4, 186, 52, 16, "不消耗", host::markNotConsumed));
        editPage.addWidget(button(60, 186, 64, 16, "概率产物", host::markChanceOutput));
        editPage.addWidget(textField(128, 186, 36, 16, () -> host.chanceText, value -> {
            host.chanceText = ShanhaiRecipeEditorWidget.limit(value);
            host.markChanceOutput();
        }));
        editPage.addWidget(new LabelWidget(166, 190, "§7%"));
        editPage.addWidget(button(180, 186, 40, 16, "必出", host::markCertain));
        editPage.addWidget(new LabelWidget(224, 190, "§7数量"));
        editPage.addWidget(textField(250, 186, 48, 16, () -> host.countText, value -> {
            host.countText = ShanhaiRecipeEditorWidget.limit(value);
            host.applyCountText();
        }));
        editPage.addWidget(new LabelWidget(4, 206, host::traitLine));
        editPage.addWidget(new LabelWidget(4, 220, () -> "§7" + host.ioTable.itemIn()
                + " 物品入 / " + host.ioTable.fluidIn() + " 流体入 → "
                + host.ioTable.itemOut() + " 物品出 / " + host.ioTable.fluidOut() + " 流体出"));
        editPage.addWidget(new LabelWidget(4, 234, "§8输入 0% = 不消耗；输出百分比 = 概率产物。加页会扩出空格子"));
        editPage.addWidget(button(4, 256, 150, 18, "返回配方卡",
                () -> host.setStage(ShanhaiRecipeEditorWidget.STAGE_SELECT, true)));
        editPage.addWidget(button(340, 256, 156, 18, "去差异审核",
                () -> host.setStage(ShanhaiRecipeEditorWidget.STAGE_REVIEW, true)));
    }

    private void buildReviewPage() {
        reviewPage.addWidget(new LabelWidget(4, 4, "§e提交前核对。服务端会重算指纹并重建配方表"));
        reviewPage.addWidget(new LabelWidget(4, 28, () -> host.selectedBase == null
                ? "§8没有待审核草稿" : "§7配方 §f" + host.compact(host.selectedBase.recipeId(), 42)));
        reviewPage.addWidget(new LabelWidget(4, 46, () -> host.selectedBase == null
                ? "" : "§7类型 §f" + host.compact(host.selectedBase.recipeTypeId(), 42)));
        reviewPage.addWidget(new LabelWidget(4, 64, host::fingerprintLine));
        reviewPage.addWidget(new LabelWidget(4, 82, host::ioLine));
        reviewPage.addWidget(new LabelWidget(4, 100, host::conditionLine));
        reviewPage.addWidget(new LabelWidget(260, 28, host::durationDiffLine));
        reviewPage.addWidget(new LabelWidget(260, 52, host::eutDiffLine));
        reviewPage.addWidget(new LabelWidget(260, 82, "§7条件与未改动的格子保持原 codec"));
        reviewPage.addWidget(new LabelWidget(260, 100, "§7成功后刷新 GT / 原版表和 JEI"));
        reviewPage.addWidget(button(4, 256, 150, 18, "返回图形编辑",
                () -> host.setStage(ShanhaiRecipeEditorWidget.STAGE_EDIT, true)));
        reviewPage.addWidget(button(340, 256, 156, 18, "提交并刷新 JEI", host::commit));
    }

    private void addGrid(List<ShanhaiIOWidget> into, int[] shown, int count,
                         int x0, int y0, int cols) {
        for (int i = 0; i < count; i++) {
            int slot = i;
            ShanhaiIOWidget cell = new ShanhaiIOWidget(host.ioTable, () -> shown[slot],
                    x0 + (i % cols) * 22, y0 + (i / cols) * 22, host::syncIoDraft);
            cell.setSelection(
                    () -> shown[slot] >= 0 && shown[slot] == host.selectedIo,
                    () -> host.selectIo(shown[slot]));
            into.add(cell);
            editPage.addWidget(cell);
        }
    }

    private StagePage page() {
        return new StagePage(this);
    }

    private StagePage page(int stage) {
        if (stage == ShanhaiRecipeEditorWidget.STAGE_EDIT) return editPage;
        if (stage == ShanhaiRecipeEditorWidget.STAGE_REVIEW) return reviewPage;
        return selectPage;
    }

    private static void show(StagePage page, float slide, boolean active) {
        show(page, slide, true, active);
    }

    private static void show(StagePage page, float slide, boolean visible, boolean active) {
        page.slide = slide;
        page.setVisible(visible);
        page.setActive(active && visible);
    }

    /** Labels read the client widget. The server copy never sees query or mapping results. */
    private static void markClientLabels(Widget widget) {
        if (widget instanceof LabelWidget) widget.setClientSideWidget();
        if (widget instanceof WidgetGroup group) {
            for (Widget child : group.widgets) markClientLabels(child);
        }
    }

    /**
     * Clips a sliding page to the stationary viewport. The scissor follows the
     * same pose transform LDLib uses for the recipe list, so the two nest.
     */
    private static final class StagePage extends WidgetGroup {
        private final ShanhaiRecipeEditorPanel viewport;
        private float slide;

        private StagePage(ShanhaiRecipeEditorPanel viewport) {
            super(0, PAGE_Y, viewport.getSizeWidth(), viewport.getSizeHeight() - PAGE_Y);
            this.viewport = viewport;
            setBackground(GuiTextures.BACKGROUND_INVERSE);
        }

        @Override
        public void drawInBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
            int x = viewport.getPositionX();
            int y = viewport.getPositionY() + PAGE_Y;
            int right = x + viewport.getSizeWidth();
            int bottom = viewport.getPositionY() + viewport.getSizeHeight();
            Matrix4f pose = graphics.pose().last().pose();
            Vector4f min = pose.transform(new Vector4f(x, y, 0f, 1f));
            Vector4f max = pose.transform(new Vector4f(right, bottom, 0f, 1f));
            graphics.enableScissor((int) min.x, (int) min.y, (int) max.x, (int) max.y);
            graphics.pose().pushPose();
            graphics.pose().translate(slide, 0f, 0f);
            super.drawInBackground(graphics, mouseX, mouseY, partialTicks);
            graphics.pose().popPose();
            graphics.disableScissor();
        }
    }

    private TextFieldWidget textField(int x, int y, int width, int height,
                                      java.util.function.Supplier<String> getter,
                                      java.util.function.Consumer<String> setter) {
        TextFieldWidget field = new TextFieldWidget(x, y, width, height, getter, setter)
                .setMaxStringLength(256)
                .setBackground(GuiTextures.DISPLAY);
        field.setClientSideWidget();
        return field;
    }

    private ButtonWidget button(int x, int y, int width, int height, String text, Runnable action) {
        return new ButtonWidget(x, y, width, height,
                new GuiTextureGroup(GuiTextures.BUTTON, new TextTexture(text)),
                click -> {
                    if (action != null) action.run();
                });
    }
}
