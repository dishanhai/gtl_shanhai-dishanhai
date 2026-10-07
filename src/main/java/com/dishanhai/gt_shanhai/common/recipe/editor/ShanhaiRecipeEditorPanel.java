package com.dishanhai.gt_shanhai.common.recipe.editor;

import com.gregtechceu.gtceu.api.gui.GuiTextures;
import com.mojang.blaze3d.systems.RenderSystem;
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
import net.minecraft.resources.ResourceLocation;
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
    private static final int GRID_STRIDE = 22;
    /** Short slide for one IO page. The full-screen stage slide stays on its own clock. */
    private static final long IO_PAGE_MS = 320L;

    private final ShanhaiRecipeEditorWidget host;
    private final StagePage selectPage;
    private final StagePage editPage;
    private final StagePage reviewPage;
    private final DraggableScrollableWidgetGroup resultList;
    private DraggableScrollableWidgetGroup conditionList;
    private DraggableScrollableWidgetGroup parameterList;
    private final List<ShanhaiIOWidget> inputItemCells = new ArrayList<>();
    private final List<ShanhaiIOWidget> inputFluidCells = new ArrayList<>();
    private final List<ShanhaiIOWidget> outputItemCells = new ArrayList<>();
    private final List<ShanhaiIOWidget> outputFluidCells = new ArrayList<>();
    private final int[] shownItemIn = new int[ITEM_PAGE];
    private final int[] shownFluidIn = new int[FLUID_PAGE];
    private final int[] shownItemOut = new int[ITEM_PAGE];
    private final int[] shownFluidOut = new int[FLUID_PAGE];
    private ShanhaiQuerySlotWidget querySlot;
    private IoGrid inputItemGrid;
    private IoGrid inputFluidGrid;
    private IoGrid outputItemGrid;
    private IoGrid outputFluidGrid;

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
        resultList = new DraggableScrollableWidgetGroup(4, 116, getSizeWidth() - 8, 110)
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

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (claimPopupClick(this, mouseX, mouseY, button)) return true;
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseWheelMove(double mouseX, double mouseY, double wheelDelta) {
        if (claimPopupWheel(this, mouseX, mouseY, wheelDelta)) return true;
        return super.mouseWheelMove(mouseX, mouseY, wheelDelta);
    }

    private static boolean claimPopupClick(Widget widget, double mouseX, double mouseY, int button) {
        if (widget == null || !widget.isVisible()) return false;
        if (widget instanceof ShanhaiRecipeTypeSelector selector
                && selector.claimPopupClick(mouseX, mouseY, button)) {
            return true;
        }
        if (widget instanceof WidgetGroup group) {
            for (int i = group.widgets.size() - 1; i >= 0; i--) {
                if (claimPopupClick(group.widgets.get(i), mouseX, mouseY, button)) return true;
            }
        }
        return false;
    }

    private static boolean claimPopupWheel(Widget widget, double mouseX, double mouseY, double wheelDelta) {
        if (widget == null || !widget.isVisible()) return false;
        if (widget instanceof ShanhaiRecipeTypeSelector selector
                && selector.claimPopupWheel(mouseX, mouseY, wheelDelta)) {
            return true;
        }
        if (widget instanceof WidgetGroup group) {
            for (int i = group.widgets.size() - 1; i >= 0; i--) {
                if (claimPopupWheel(group.widgets.get(i), mouseX, mouseY, wheelDelta)) return true;
            }
        }
        return false;
    }

    public void refreshConditions() {
        refreshConditions(true);
    }

    public void refreshConditions(boolean animate) {
        if (conditionList == null) return;
        conditionList.clearAllWidgets();
        int width = Math.max(80, conditionList.getSizeWidth() - 48);
        int rowWidth = Math.max(80, conditionList.getSizeWidth() - 8);
        for (int i = 0; i < host.conditionEdits.size(); i++) {
            int index = i;
            EnterRow row = new EnterRow(0, 2 + i * 16, rowWidth, 16, i, animate);
            row.addWidget(new LabelWidget(4, 2, () -> host.compact(host.conditionLabel(index), 16)));
            row.addWidget(button(width - 40, 0, 36, 14, "改", () -> host.editCondition(index)));
            row.addWidget(button(width, 0, 36, 14, "移除", () -> host.removeCondition(index)));
            conditionList.addWidget(row);
        }
    }

    public void refreshParameters() {
        if (parameterList == null) return;
        parameterList.clearAllWidgets();
        int rowWidth = Math.max(120, parameterList.getSizeWidth() - 8);
        List<ShanhaiRecipeConditions.Parameter> parameters = host.draftParameters();
        if (parameters.isEmpty()) {
            EnterRow row = new EnterRow(0, 2, rowWidth, 16, 0, true);
            row.addWidget(new LabelWidget(4, 2, "§7选出条件后编辑参数"));
            parameterList.addWidget(row);
            return;
        }
        int y = 2;
        int order = 0;
        for (ShanhaiRecipeConditions.Parameter parameter : parameters) {
            String path = parameter.path;
            EnterRow row = new EnterRow(0, y, rowWidth, 16, order, true);
            row.addWidget(new LabelWidget(2, 2, host.compact(path, 12)));
            List<String> choices = host.parameterChoices(path);
            if (choices.size() > 1) {
                SelectorWidget selector = new ShanhaiRecipeTypeSelector(62, 0, 160, 14, choices, 0xFF1A1A1A)
                        .setButtonBackground(GuiTextures.VANILLA_BUTTON)
                        .setBackground(GuiTextures.BACKGROUND)
                        .setFontColor(0xFF1A1A1A)
                        .setMaxCount(8)
                        .setCandidatesSupplier(() -> host.parameterChoices(path))
                        .setSupplier(() -> host.parameterText(path))
                        .setOnChanged(value -> host.setParameterText(path, value));
                selector.setClientSideWidget();
                row.addWidget(selector);
            } else {
                row.addWidget(textField(62, 0, 160, 14,
                        () -> host.parameterText(path), value -> host.setParameterText(path, value)));
            }
            parameterList.addWidget(row);
            y += 16;
            order++;
        }
    }

    public void mountSelector(SelectorWidget selector) {
        selectPage.addWidget(selector);
    }

    public void refreshCards(List<ShanhaiRecipeQuery.Card> cards, List<ShanhaiRecipeQuery.Group> groups) {
        resultList.clearAllWidgets();
        List<ShanhaiRecipeQuery.Card> source = cards == null ? List.of() : cards;
        int width = Math.max(120, resultList.getSizeWidth() - 16);
        boolean[] used = new boolean[source.size()];
        int y = 0;
        int order = 0;
        List<ShanhaiRecipeQuery.Group> sections = groups == null ? List.of() : groups;
        for (ShanhaiRecipeQuery.Group group : sections) {
            List<Integer> indexes = unused(host.sortIndexes(source, group.cardIndexes()), used);
            if (indexes.isEmpty()) continue;
            resultList.addWidget(new ShanhaiRecipeGroupHeaderWidget(
                    2, y, width, groupTitle(group.recipeTypeId(), indexes.size()), order));
            y += ShanhaiRecipeGroupHeaderWidget.CH + 2;
            y = appendCards(source, indexes, width, y, order);
            order += indexes.size();
        }
        List<Integer> rest = new ArrayList<>();
        for (int index = 0; index < source.size(); index++) {
            if (!used[index]) rest.add(index);
        }
        rest = unused(host.sortIndexes(source, rest), used);
        if (!rest.isEmpty()) {
            if (!sections.isEmpty()) {
                resultList.addWidget(new ShanhaiRecipeGroupHeaderWidget(
                        2, y, width, "未分组 · 本页 " + rest.size(), order));
                y += ShanhaiRecipeGroupHeaderWidget.CH + 2;
            }
            appendCards(source, rest, width, y, order);
        }
    }

    private int appendCards(List<ShanhaiRecipeQuery.Card> source, List<Integer> indexes,
                            int width, int y, int order) {
        int next = y;
        int shown = order;
        for (int index : indexes) {
            ShanhaiRecipeQuery.Card card = source.get(index);
            int row = shown;
            resultList.addWidget(new ShanhaiRecipeCardWidget(
                    2, next, width, card, row, () -> host.requestDetail(card)));
            next += ShanhaiRecipeCardWidget.CH + 2;
            shown++;
        }
        return next;
    }

    private static List<Integer> unused(List<Integer> indexes, boolean[] used) {
        List<Integer> fresh = new ArrayList<>();
        for (int index : indexes) {
            if (index < 0 || index >= used.length || used[index]) continue;
            used[index] = true;
            fresh.add(index);
        }
        return fresh;
    }

    private static String groupTitle(String typeId, int count) {
        ResourceLocation id = typeId == null || typeId.isEmpty() ? null : ResourceLocation.tryParse(typeId);
        String name = id == null
                ? (typeId == null || typeId.isEmpty() ? "未分组" : typeId)
                : ShanhaiRecipeTypeNames.display(id);
        return name + " · 本页 " + count;
    }

    public void bindTable(ShanhaiIoTable table) {
        inputItemCells.forEach(cell -> cell.setTable(table));
        inputFluidCells.forEach(cell -> cell.setTable(table));
        outputItemCells.forEach(cell -> cell.setTable(table));
        outputFluidCells.forEach(cell -> cell.setTable(table));
        if (inputItemGrid != null) inputItemGrid.snap();
        if (inputFluidGrid != null) inputFluidGrid.snap();
        if (outputItemGrid != null) outputItemGrid.snap();
        if (outputFluidGrid != null) outputFluidGrid.snap();
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
        showSettled(selectPage, ShanhaiRecipeEditorWidget.STAGE_SELECT);
        showSettled(editPage, ShanhaiRecipeEditorWidget.STAGE_EDIT);
        showSettled(reviewPage, ShanhaiRecipeEditorWidget.STAGE_REVIEW);
    }

    private void refreshIo() {
        if (inputItemGrid != null) inputItemGrid.sync(host.inputItemPage, 0, host.ioTable.itemIn(), ITEM_PAGE, shownItemIn);
        if (inputFluidGrid != null) {
            inputFluidGrid.sync(host.inputFluidPage, host.ioTable.itemIn(), host.ioTable.fluidIn(), FLUID_PAGE, shownFluidIn);
        }
        if (outputItemGrid != null) {
            outputItemGrid.sync(host.outputItemPage, host.ioTable.inSection(), host.ioTable.itemOut(), ITEM_PAGE, shownItemOut);
        }
        if (outputFluidGrid != null) {
            outputFluidGrid.sync(host.outputFluidPage, host.ioTable.inSection() + host.ioTable.itemOut(),
                    host.ioTable.fluidOut(), FLUID_PAGE, shownFluidOut);
        }
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
                ? "§8未放入 GT 机器，也可按配方 ID、原料或输出搜索"
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
        querySlot = new ShanhaiQuerySlotWidget(416, 52, host::acceptQueryDrop);
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
        selectPage.addWidget(sortButton(160, 76, 36, "id", "A-Z"));
        selectPage.addWidget(sortButton(198, 76, 36, "eut", "EU"));
        selectPage.addWidget(sortButton(236, 76, 40, "duration", "耗时"));
        selectPage.addWidget(sortButton(278, 76, 52, "inputs", "输入数"));
        selectPage.addWidget(sortButton(332, 76, 52, "outputs", "输出数"));
        selectPage.addWidget(new ShanhaiRecipeSortMarkerWidget(host, 0, 91, getSizeWidth()));
        selectPage.addWidget(modeButton(4, 96, 52, ShanhaiRecipeQuery.SearchMode.RECIPE_ID, "配方"));
        selectPage.addWidget(modeButton(58, 96, 44, ShanhaiRecipeQuery.SearchMode.INGREDIENT, "原料"));
        selectPage.addWidget(modeButton(104, 96, 44, ShanhaiRecipeQuery.SearchMode.OUTPUT, "输出"));
        selectPage.addWidget(kindButton(152, 96, 40, ShanhaiRecipeQuery.IngredientKind.ITEM, "物品"));
        selectPage.addWidget(kindButton(194, 96, 40, ShanhaiRecipeQuery.IngredientKind.FLUID, "流体"));
        selectPage.addWidget(new ShanhaiRecipeChoiceMarkerWidget(host, 0, 110, getSizeWidth()));
        selectPage.addWidget(new LabelWidget(240, 100, host::searchHint));
        selectPage.addWidget(resultList);
        selectPage.addWidget(button(4, 232, 36, 16, "<", () -> host.shiftQueryPage(-1)));
        selectPage.addWidget(new LabelWidget(44, 236, () -> "第 " + (host.queryPage + 1)
                + "/" + host.pageCount(host.queryTotal, ShanhaiRecipeEditorWidget.QUERY_PAGE_SIZE) + " 页"));
        selectPage.addWidget(button(130, 232, 36, 16, ">", () -> host.shiftQueryPage(1)));
        selectPage.addWidget(new LabelWidget(172, 236, () -> host.queryTotal <= 0
                ? "§8等待配方卡片" : "§a共 " + host.queryTotal + " 条"));
        selectPage.addWidget(button(4, 256, 150, 18, "刷新配方表", host::query));
        selectPage.addWidget(button(340, 256, 156, 18, "进入编辑",
                () -> host.setStage(ShanhaiRecipeEditorWidget.STAGE_EDIT, true)));
    }

    private void buildEditPage() {
        editPage.addWidget(textField(4, 2, 488, 14, () -> host.recipeIdText, host::setRecipeIdText));
        editPage.addWidget(new LabelWidget(4, 18, "§7耗时 / tick"));
        editPage.addWidget(new LabelWidget(150, 18, "§7EU/t"));
        editPage.addWidget(textField(4, 30, 130, 18, () -> host.durationText, host::setDurationText));
        editPage.addWidget(textField(150, 30, 130, 18, () -> host.eutText,
                value -> host.setEutText(value)));
        editPage.addWidget(button(292, 30, 100, 18, "撤销修改", host::undoEdit));
        editPage.addWidget(button(396, 30, 100, 18, "还原", host::restoreOriginal));
        editPage.addWidget(new LabelWidget(4, 50, "§a输入物品"));
        editPage.addWidget(new LabelWidget(252, 50, "§d输出物品"));
        inputItemGrid = addGrid(inputItemCells, shownItemIn, ITEM_PAGE, 4, 62, ITEM_COLS);
        outputItemGrid = addGrid(outputItemCells, shownItemOut, ITEM_PAGE, 252, 62, ITEM_COLS);
        editPage.addWidget(new LabelWidget(4, 108, "§b输入流体"));
        editPage.addWidget(new LabelWidget(252, 108, "§b输出流体"));
        inputFluidGrid = addGrid(inputFluidCells, shownFluidIn, FLUID_PAGE, 4, 120, FLUID_COLS);
        outputFluidGrid = addGrid(outputFluidCells, shownFluidOut, FLUID_PAGE, 252, 120, FLUID_COLS);
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
        editPage.addWidget(textField(128, 186, 36, 16, () -> host.chanceText, host::setChanceText));
        editPage.addWidget(new LabelWidget(166, 190, "§7%%"));
        editPage.addWidget(button(180, 186, 40, 16, "必出", host::markCertain));
        editPage.addWidget(new LabelWidget(224, 190, "§7数量"));
        editPage.addWidget(textField(250, 186, 48, 16, () -> host.countText, host::setCountText));
        editPage.addWidget(new LabelWidget(4, 206, host::traitLine));
        editPage.addWidget(new LabelWidget(4, 220, () -> "§7" + host.ioTable.itemIn()
                + " 物品入 / " + host.ioTable.fluidIn() + " 流体入 → "
                + host.ioTable.itemOut() + " 物品出 / " + host.ioTable.fluidOut() + " 流体出"));
        editPage.addWidget(new LabelWidget(4, 234, "§8输入 0%% = 不消耗；输出百分比 = 概率产物。加页会扩出空格子"));
        editPage.addWidget(button(4, 256, 150, 18, "返回配方卡",
                () -> host.setStage(ShanhaiRecipeEditorWidget.STAGE_SELECT, true)));
        editPage.addWidget(button(340, 256, 156, 18, "去差异审核",
                () -> host.setStage(ShanhaiRecipeEditorWidget.STAGE_REVIEW, true)));
    }

    private void buildReviewPage() {
        reviewPage.addWidget(new LabelWidget(4, 4, "§e提交前核对。服务端会重算指纹并重建配方表"));
        reviewPage.addWidget(new LabelWidget(4, 28, () -> host.selectedBase == null
                ? "§8没有待审核草稿" : "§7配方 §f" + host.compact(host.selectedBase.recipeId(), 42)));
        reviewPage.addWidget(new LabelWidget(4, 118, () -> host.recipeIdText.isEmpty()
                ? "" : "§7配方 id §f" + host.compact(host.recipeIdText, 32)));
        conditionList = new DraggableScrollableWidgetGroup(4, 134, 246, 108)
                .setYScrollBarWidth(4)
                .setYBarStyle(new ColorRectTexture(0xff79c9dd), GuiTextures.VANILLA_BUTTON)
                .setBackground(GuiTextures.DISPLAY)
                .setUseScissor(true);
        reviewPage.addWidget(conditionList);
        parameterList = new DraggableScrollableWidgetGroup(258, 152, 236, 64)
                .setYScrollBarWidth(4)
                .setYBarStyle(new ColorRectTexture(0xff79c9dd), GuiTextures.VANILLA_BUTTON)
                .setBackground(GuiTextures.DISPLAY)
                .setUseScissor(true);
        reviewPage.addWidget(parameterList);
        reviewPage.addWidget(new LabelWidget(258, 138, "§7条件参数"));
        SelectorWidget conditionSelector = new ShanhaiRecipeTypeSelector(
                258, 118, 188, 16, List.of(), 0xFF1A1A1A)
                .setButtonBackground(GuiTextures.VANILLA_BUTTON)
                .setBackground(GuiTextures.BACKGROUND)
                .setFontColor(0xFF1A1A1A)
                .setMaxCount(12)
                .setCandidatesSupplier(ShanhaiRecipeConditions::choices)
                .setSupplier(() -> host.conditionPick.isEmpty() ? "添加条件" : host.conditionPick)
                .setOnChanged(host::setConditionPick);
        conditionSelector.setClientSideWidget();
        reviewPage.addWidget(conditionSelector);
        reviewPage.addWidget(button(450, 118, 44, 16, "添加", host::addCondition));
        reviewPage.addWidget(new LabelWidget(4, 46, () -> host.selectedBase == null
                ? "" : "§7类型 §f" + host.compact(host.selectedBase.recipeTypeId(), 42)));
        reviewPage.addWidget(new LabelWidget(4, 64, host::fingerprintLine));
        reviewPage.addWidget(new LabelWidget(4, 82, host::ioLine));
        reviewPage.addWidget(new LabelWidget(4, 100, host::conditionLine));
        reviewPage.addWidget(new LabelWidget(260, 28, host::durationDiffLine));
        reviewPage.addWidget(new LabelWidget(260, 52, host::eutDiffLine));
        reviewPage.addWidget(new LabelWidget(260, 82, "§7条件与未改动的格子保持原 codec"));
        reviewPage.addWidget(new ButtonWidget(260, 96, 88, 16,
                new GuiTextureGroup(GuiTextures.BUTTON, new TextTexture(host::keepOriginalFace)),
                click -> host.toggleKeepOriginal()));
        reviewPage.addWidget(new LabelWidget(352, 100, host::keepOriginalHint));
        reviewPage.addWidget(button(340, 220, 156, 18, "导出配方为json", host::exportJson));
        reviewPage.addWidget(button(4, 256, 150, 18, "返回图形编辑",
                () -> host.setStage(ShanhaiRecipeEditorWidget.STAGE_EDIT, true)));
        reviewPage.addWidget(button(340, 256, 156, 18, "提交并刷新 JEI", host::commit));
    }

    private IoGrid addGrid(List<ShanhaiIOWidget> into, int[] shown, int count,
                           int x0, int y0, int cols) {
        int rows = Math.max(1, (count + cols - 1) / cols);
        IoGrid grid = new IoGrid(x0, y0, cols * GRID_STRIDE, rows * GRID_STRIDE);
        for (int i = 0; i < count; i++) {
            int slot = i;
            ShanhaiIOWidget cell = new ShanhaiIOWidget(host.ioTable, () -> shown[slot],
                    (i % cols) * GRID_STRIDE, (i / cols) * GRID_STRIDE, host::syncIoDraft);
            cell.setSelection(
                    () -> shown[slot] >= 0 && shown[slot] == host.selectedIo,
                    () -> host.selectIo(shown[slot]));
            cell.setAccent(host::selectionAccent);
            cell.setBeforeChange(host::beforeIoEdit);
            into.add(cell);
            grid.addWidget(cell);
        }
        editPage.addWidget(grid);
        return grid;
    }

    /**
     * One item or fluid page. Adding a page changes the index immediately, and this
     * viewport slides the old cells out before drawing the new ones. Positions stay
     * put; only the pose moves, same as the stage pages.
     */
    private static final class IoGrid extends WidgetGroup {
        private int fromPage;
        private int visualPage = Integer.MIN_VALUE;
        private int targetPage = Integer.MIN_VALUE;
        private long started;
        private boolean primed;
        private int want;
        private int base;
        private int count;
        private int pageSize;
        private int[] shown = new int[0];

        private IoGrid(int x, int y, int width, int height) {
            super(x, y, width, height);
        }

        private void snap() {
            visualPage = Integer.MIN_VALUE;
            targetPage = Integer.MIN_VALUE;
            fromPage = 0;
            primed = false;
        }

        private void sync(int want, int base, int count, int pageSize, int[] shown) {
            this.want = want;
            this.base = base;
            this.count = count;
            this.pageSize = pageSize;
            this.shown = shown;
            this.primed = true;
            apply();
        }

        private void apply() {
            if (!primed || shown == null) return;
            int page = visual(want);
            fillPage(shown, page, base, count, pageSize);
            setActive(!moving());
            for (int i = 0; i < widgets.size() && i < shown.length; i++) {
                widgets.get(i).setVisible(shown[i] >= 0);
            }
        }

        private int visual(int next) {
            if (visualPage == Integer.MIN_VALUE) {
                visualPage = next;
                targetPage = next;
                fromPage = next;
                return next;
            }
            if (next != targetPage) {
                fromPage = visualPage;
                targetPage = next;
                started = ShanhaiRecipeEditorAnimation.nowMs();
            }
            if (progress() >= 0.5f) visualPage = targetPage;
            return visualPage;
        }

        private boolean moving() {
            return fromPage != targetPage && progress() < 1f;
        }

        private float progress() {
            if (fromPage == targetPage) return 1f;
            float linear = Math.min(1f, Math.max(0L,
                    ShanhaiRecipeEditorAnimation.nowMs() - started) / (float) IO_PAGE_MS);
            return linear * linear * (3f - 2f * linear);
        }

        private float offset() {
            float amount = progress();
            if (amount >= 1f || fromPage == targetPage) return 0f;
            int direction = fromPage < targetPage ? -1 : 1;
            float width = getSizeWidth();
            if (amount < 0.5f) return direction * (amount / 0.5f) * width;
            float tail = (amount - 0.5f) / 0.5f;
            return -direction * (1f - tail) * width;
        }

        @Override
        public List<Target> getPhantomTargets(Object ingredient) {
            apply();
            if (moving()) return List.of();
            return super.getPhantomTargets(ingredient);
        }

        @Override
        public boolean isMouseOverElement(double mouseX, double mouseY) {
            if (moving()) return false;
            return super.isMouseOverElement(mouseX, mouseY);
        }

        @Override
        public void drawInBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
            apply();
            int x = getPositionX();
            int y = getPositionY();
            Matrix4f pose = graphics.pose().last().pose();
            Vector4f min = pose.transform(new Vector4f(x, y, 0f, 1f));
            Vector4f max = pose.transform(new Vector4f(x + getSizeWidth(), y + getSizeHeight(), 0f, 1f));
            graphics.enableScissor((int) min.x, (int) min.y, (int) max.x, (int) max.y);
            graphics.pose().pushPose();
            graphics.pose().translate(offset(), 0f, 0f);
            super.drawInBackground(graphics, mouseX, mouseY, partialTicks);
            graphics.pose().popPose();
            graphics.disableScissor();
        }
    }

    /** One rebuilt row. Pose slides in; the hit box stays put so a click still lands. */
    private static final class EnterRow extends WidgetGroup {
        private static final long ENTER_MS = 200L;
        private static final long STAGGER_MS = 28L;

        private final int order;
        private final long born;

        private EnterRow(int x, int y, int width, int height, int order, boolean animate) {
            super(x, y, width, height);
            this.order = Math.max(0, order);
            long delay = this.order * STAGGER_MS;
            this.born = ShanhaiRecipeEditorAnimation.nowMs() - (animate ? 0L : ENTER_MS + delay);
        }

        @Override
        public void drawInBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
            float enter = enter();
            graphics.pose().pushPose();
            graphics.pose().translate(0f, (1f - enter) * 8f, 0f);
            RenderSystem.setShaderColor(1f, 1f, 1f, 0.2f + 0.8f * enter);
            super.drawInBackground(graphics, mouseX, mouseY, partialTicks);
            graphics.pose().popPose();
            RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        }

        private float enter() {
            long elapsed = ShanhaiRecipeEditorAnimation.nowMs() - born - order * STAGGER_MS;
            if (elapsed <= 0L) return 0f;
            float linear = Math.min(1f, elapsed / (float) ENTER_MS);
            return linear * linear * (3f - 2f * linear);
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

    private void showSettled(StagePage page, int stage) {
        boolean visible = host.stage == stage;
        show(page, 0f, visible, visible);
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

    private ButtonWidget modeButton(int x, int y, int width,
                                    ShanhaiRecipeQuery.SearchMode mode, String plain) {
        return new ButtonWidget(x, y, width, 16,
                new GuiTextureGroup(GuiTextures.BUTTON, new TextTexture(() -> host.modeFace(mode, plain))),
                click -> host.setSearchMode(mode));
    }

    private ButtonWidget kindButton(int x, int y, int width,
                                    ShanhaiRecipeQuery.IngredientKind kind, String plain) {
        return new ButtonWidget(x, y, width, 16,
                new GuiTextureGroup(GuiTextures.BUTTON, new TextTexture(() -> host.kindFace(kind, plain))),
                click -> host.setIngredientKind(kind));
    }

    private ButtonWidget sortButton(int x, int y, int width, String key, String plain) {
        return new ButtonWidget(x, y, width, 16,
                new GuiTextureGroup(GuiTextures.BUTTON, new TextTexture(() -> host.sortFace(key, plain))),
                click -> host.setSort(key));
    }

    private ButtonWidget button(int x, int y, int width, int height, String text, Runnable action) {
        return new ButtonWidget(x, y, width, height,
                new GuiTextureGroup(GuiTextures.BUTTON, new TextTexture(text)),
                click -> {
                    if (action != null) action.run();
                });
    }
}
