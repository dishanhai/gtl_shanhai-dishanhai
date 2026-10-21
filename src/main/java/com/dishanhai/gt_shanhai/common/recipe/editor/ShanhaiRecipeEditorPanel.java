package com.dishanhai.gt_shanhai.common.recipe.editor;

import static com.dishanhai.gt_shanhai.common.recipe.editor.RecipeEditorLayout.BODY_BOTTOM;
import static com.dishanhai.gt_shanhai.common.recipe.editor.RecipeEditorLayout.BODY_H;
import static com.dishanhai.gt_shanhai.common.recipe.editor.RecipeEditorLayout.BODY_Y;
import static com.dishanhai.gt_shanhai.common.recipe.editor.RecipeEditorLayout.BTN_H;
import static com.dishanhai.gt_shanhai.common.recipe.editor.RecipeEditorLayout.CELL_STRIDE;
import static com.dishanhai.gt_shanhai.common.recipe.editor.RecipeEditorLayout.CTRL_H;
import static com.dishanhai.gt_shanhai.common.recipe.editor.RecipeEditorLayout.FOOT_BTN_W;
import static com.dishanhai.gt_shanhai.common.recipe.editor.RecipeEditorLayout.FOOT_RIGHT_X;
import static com.dishanhai.gt_shanhai.common.recipe.editor.RecipeEditorLayout.FOOT_Y;
import static com.dishanhai.gt_shanhai.common.recipe.editor.RecipeEditorLayout.GAP;
import static com.dishanhai.gt_shanhai.common.recipe.editor.RecipeEditorLayout.LINE_H;
import static com.dishanhai.gt_shanhai.common.recipe.editor.RecipeEditorLayout.MAIN_RIGHT;
import static com.dishanhai.gt_shanhai.common.recipe.editor.RecipeEditorLayout.MAIN_W;
import static com.dishanhai.gt_shanhai.common.recipe.editor.RecipeEditorLayout.MAIN_X;
import static com.dishanhai.gt_shanhai.common.recipe.editor.RecipeEditorLayout.SIDE_W;
import static com.dishanhai.gt_shanhai.common.recipe.editor.RecipeEditorLayout.SIDE_X;
import static com.dishanhai.gt_shanhai.common.recipe.editor.RecipeEditorLayout.TAB_Y;
import static com.dishanhai.gt_shanhai.common.recipe.editor.RecipeEditorLayout.share;
import static com.dishanhai.gt_shanhai.common.recipe.editor.RecipeEditorLayout.shareX;

import com.dishanhai.gt_shanhai.client.gui.glass.GlassBlur;
import com.dishanhai.gt_shanhai.client.gui.glass.GlassMotion;
import com.dishanhai.gt_shanhai.client.gui.glass.GlassPalette;
import com.dishanhai.gt_shanhai.client.gui.glass.GlassPanelTexture;
import com.dishanhai.gt_shanhai.client.gui.glass.GuiRect;
import com.gregtechceu.gtceu.api.gui.GuiTextures;
import com.lowdragmc.lowdraglib.gui.ingredient.IGhostIngredientTarget;
import com.lowdragmc.lowdraglib.gui.ingredient.Target;
import com.lowdragmc.lowdraglib.gui.texture.ColorRectTexture;
import com.lowdragmc.lowdraglib.gui.texture.GuiTextureGroup;
import com.lowdragmc.lowdraglib.gui.texture.TextTexture;
import com.lowdragmc.lowdraglib.gui.widget.ButtonWidget;
import com.lowdragmc.lowdraglib.gui.widget.DraggableScrollableWidgetGroup;
import com.lowdragmc.lowdraglib.gui.widget.ImageWidget;
import com.lowdragmc.lowdraglib.gui.widget.LabelWidget;
import com.lowdragmc.lowdraglib.gui.widget.SelectorWidget;
import com.lowdragmc.lowdraglib.gui.widget.TextFieldWidget;
import com.lowdragmc.lowdraglib.gui.widget.Widget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Two columns per stage, laid on the lines in {@link RecipeEditorLayout}.
 * The content page slides and clips to the content viewport; the sidebar and
 * footer page stays put and is veiled while the stages swap.
 * The panel spans the whole editor, so every child uses root coordinates.
 * WidgetGroup ghost lookup is one level deep, so each page stays a direct child.
 */
public final class ShanhaiRecipeEditorPanel extends WidgetGroup implements IGhostIngredientTarget {

    public static final int ITEM_PAGE = ShanhaiIoTable.ITEM_IN;
    public static final int FLUID_PAGE = ShanhaiIoTable.FLUID_IN;
    /** One IO page is one row of cells across the content column. */
    private static final int GRID_COLS = 16;
    /** Pitch of one IO block: title row with pager, then the framed grid. */
    private static final int IO_BLOCK = 44;
    private static final int IO_TITLE_H = 14;
    private static final int PAGER_Y = BODY_BOTTOM - CTRL_H;
    /** The sidebar settles this far upward while it is unveiled. */
    private static final float SIDE_LIFT = 6f;
    private static final int GUESS_W = 32;

    private final ShanhaiRecipeEditorWidget host;
    private final GlassStagePage selectPage;
    private final GlassStagePage editPage;
    private final GlassStagePage reviewPage;
    private final GlassFadePage selectSide;
    private final GlassFadePage editSide;
    private final GlassFadePage reviewSide;
    private final GlassPanelTexture sideBack = GlassPanelTexture.page();
    private final GlassPanelTexture mainBack = GlassPanelTexture.page();
    private final GlassRecipeCardList resultList;
    /** Query page whose cards should open already scrolled to the bottom. -1 means top. */
    private int landAtEndPage = -1;
    private boolean blurWarmed;
    private DraggableScrollableWidgetGroup conditionList;
    private int conditionContent;
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
    private ShanhaiRecipeHistoryWidget queryHistory;
    private ButtonWidget keepToggle;
    private TextFieldWidget recipeIdField;
    private SelectorWidget createTypeSelector;
    private ButtonWidget guessId;
    private WidgetGroup dataRow;
    private SelectorWidget dataKeySelector;
    private SelectorWidget coilSelector;
    private TextFieldWidget dataValueField;
    private GlassWrapLabel dataLine;
    private final Map<String, TextFieldWidget> parameterFields = new LinkedHashMap<>();
    private boolean createChrome;
    private GlassIoGrid inputItemGrid;
    private GlassIoGrid inputFluidGrid;
    private GlassIoGrid outputItemGrid;
    private GlassIoGrid outputFluidGrid;

    public ShanhaiRecipeEditorPanel(ShanhaiRecipeEditorWidget host) {
        super(0, 0, ShanhaiRecipeEditorWidget.WIDTH, ShanhaiRecipeEditorWidget.HEIGHT);
        this.host = host;
        Arrays.fill(shownItemIn, -1);
        Arrays.fill(shownFluidIn, -1);
        Arrays.fill(shownItemOut, -1);
        Arrays.fill(shownFluidOut, -1);
        selectPage = mainPage();
        editPage = mainPage();
        reviewPage = mainPage();
        selectSide = sidePage();
        editSide = sidePage();
        reviewSide = sidePage();
        int listY = BODY_Y + BTN_H + GAP;
        resultList = new GlassRecipeCardList(host, MAIN_X, listY, MAIN_W, PAGER_Y - GAP - listY);
        resultList.setYScrollBarWidth(5)
                .setYBarStyle(new ColorRectTexture(0xff79c9dd), GuiTextures.VANILLA_BUTTON)
                .setBackground(GlassPanelTexture.display())
                .setUseScissor(true);

        addWidget(new ShanhaiRecipeTabBarWidget(host, MAIN_X, TAB_Y, MAIN_W));
        buildSelectPage();
        buildEditPage();
        buildReviewPage();
        addWidget(selectPage);
        addWidget(editPage);
        addWidget(reviewPage);
        // Sidebars after the content pages: their popups hang over the content column.
        addWidget(selectSide);
        addWidget(editSide);
        addWidget(reviewSide);
        markClientLabels(this);
        applyStage(false);
    }

    @Override
    public List<Target> getPhantomTargets(Object ingredient) {
        return super.getPhantomTargets(ingredient);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (queryHistory != null && queryHistory.isOpen() && !queryHistory.hit(mouseX, mouseY)) {
            queryHistory.setOpen(false);
        }
        if (claimPopupClick(this, mouseX, mouseY, button)) return true;
        return super.mouseClicked(mouseX, mouseY, button);
    }

    void closeQueryHistory() {
        if (queryHistory != null) queryHistory.setOpen(false);
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

    public void revealLastCondition() {
        if (conditionList == null) return;
        int hidden = conditionContent - conditionList.getSizeHeight();
        if (hidden > 0) conditionList.setScrollYOffset(hidden);
    }

    public void refreshConditions() {
        refreshConditions(true);
    }

    /** Rows grow with their wrapped label; the two buttons stay on the first line. */
    public void refreshConditions(boolean animate) {
        if (conditionList == null) return;
        conditionList.clearAllWidgets();
        int rowWidth = Math.max(80, conditionList.getSizeWidth() - 8);
        int removeX = rowWidth - 4 - 36;
        int editX = removeX - GAP - 36;
        int labelWidth = Math.max(40, editX - GAP - 4);
        int y = 2;
        for (int i = 0; i < host.conditionEdits.size(); i++) {
            int index = i;
            Supplier<String> label = () -> host.conditionLabel(index).replace("%", "%%");
            int height = Math.max(16, GlassWrapLabel.lineCount(host.conditionLabel(index), labelWidth) * LINE_H + 6);
            GlassEnterRow row = new GlassEnterRow(0, y, rowWidth, height, i, animate);
            row.addWidget(new GlassWrapLabel(4, 3, labelWidth, label));
            row.addWidget(button(editX, 1, 36, 14, "改", () -> host.editCondition(index)));
            row.addWidget(button(removeX, 1, 36, 14, "移除", () -> host.removeCondition(index)));
            conditionList.addWidget(row);
            y += height;
        }
        conditionContent = y;
    }

    /** Push the characters still sitting in the parameter boxes into the draft before add. */
    public void flushParameterFields() {
        for (Map.Entry<String, TextFieldWidget> entry : parameterFields.entrySet()) {
            TextFieldWidget field = entry.getValue();
            if (field == null) continue;
            host.setParameterText(entry.getKey(), field.getRawCurrentString());
        }
    }

    /** The full parameter path wraps on the left; the editor keeps the rest of the row. */
    public void refreshParameters() {
        if (parameterList == null) return;
        parameterFields.clear();
        parameterList.clearAllWidgets();
        int rowWidth = Math.max(120, parameterList.getSizeWidth() - 8);
        int labelWidth = 116;
        int fieldX = labelWidth + GAP + 2;
        int fieldWidth = rowWidth - fieldX - 2;
        List<ShanhaiRecipeConditions.Parameter> parameters = host.draftParameters();
        if (parameters.isEmpty()) {
            GlassEnterRow row = new GlassEnterRow(0, 2, rowWidth, 16, 0, true);
            row.addWidget(new GlassWrapLabel(4, 3, rowWidth - 8, "§7选出条件后编辑参数"));
            parameterList.addWidget(row);
            return;
        }
        int y = 2;
        int order = 0;
        for (ShanhaiRecipeConditions.Parameter parameter : parameters) {
            String path = parameter.path;
            int height = Math.max(16, GlassWrapLabel.lineCount(path, labelWidth) * LINE_H + 6);
            GlassEnterRow row = new GlassEnterRow(0, y, rowWidth, height, order, true);
            row.addWidget(new GlassWrapLabel(2, 3, labelWidth, path));
            List<String> choices = host.parameterChoices(path);
            if (choices.size() > 1) {
                SelectorWidget selector = selector(fieldX, 0, fieldWidth, 14, choices)
                        .setMaxCount(8)
                        .setCandidatesSupplier(() -> host.parameterChoices(path))
                        .setSupplier(() -> host.parameterText(path))
                        .setOnChanged(value -> host.setParameterText(path, value));
                selector.setClientSideWidget();
                row.addWidget(selector);
            } else {
                TextFieldWidget field = textField(fieldX, 0, fieldWidth, 14,
                        () -> host.parameterText(path), value -> host.setParameterText(path, value));
                parameterFields.put(path, field);
                row.addWidget(field);
            }
            parameterList.addWidget(row);
            y += height;
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
            y = appendHeader(groupTitle(group.recipeTypeId(), indexes.size()), width, y, order);
            y = appendCards(source, indexes, width, y, order);
            order += indexes.size();
        }
        List<Integer> rest = new ArrayList<>();
        for (int index = 0; index < source.size(); index++) {
            if (!used[index]) rest.add(index);
        }
        rest = unused(host.sortIndexes(source, rest), used);
        if (!rest.isEmpty()) {
            if (!sections.isEmpty()) y = appendHeader("未分组 · 本页 " + rest.size(), width, y, order);
            appendCards(source, rest, width, y, order);
        }
        if (landAtEndPage >= 0 && landAtEndPage == host.queryPage) resultList.scrollToEnd();
        landAtEndPage = -1;
    }

    /** Previous-page overscroll lands on the last row once that page's cards arrive. */
    void landQueryAtEnd(int page) {
        landAtEndPage = page;
    }

    private int appendHeader(String title, int width, int y, int order) {
        ShanhaiRecipeGroupHeaderWidget header = new ShanhaiRecipeGroupHeaderWidget(2, y, width, title, order);
        resultList.addWidget(header);
        return y + header.getSizeHeight() + 2;
    }

    private int appendCards(List<ShanhaiRecipeQuery.Card> source, List<Integer> indexes,
                            int width, int y, int order) {
        int next = y;
        int shown = order;
        for (int index : indexes) {
            ShanhaiRecipeQuery.Card card = source.get(index);
            int row = shown;
            ShanhaiRecipeCardWidget widget = new ShanhaiRecipeCardWidget(
                    2, next, width, card, row, () -> host.requestDetail(card));
            resultList.addWidget(widget);
            next += widget.getSizeHeight() + 2;
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
        warmBlurOnce();
        refreshIo();
        if (keepToggle != null) keepToggle.setVisible(!host.creating);
        syncCreateChrome();
    }

    /** Recipe data controls show only for types that carry them. The flow closes the gap otherwise. */
    void syncBlastChrome(boolean show) {
        boolean many = show && host.dataKeyCount() > 1;
        boolean temperature = show && host.temperatureKeyActive();
        if (dataRow != null) dataRow.setVisible(show);
        if (dataKeySelector != null) dataKeySelector.setVisible(many);
        int valueX = many ? 100 + GAP : 0;
        int valueWidth = MAIN_W - valueX;
        if (coilSelector != null) {
            coilSelector.setVisible(temperature);
            coilSelector.setSelfPosition(valueX, 0);
            coilSelector.setSize(valueWidth, 14);
        }
        if (dataValueField != null) {
            dataValueField.setVisible(show && !temperature);
            dataValueField.setSelfPosition(valueX, 0);
            dataValueField.setSize(valueWidth, 14);
        }
        if (dataLine != null) dataLine.setVisible(show);
    }

    /** Type picker and id guess stay on the add-recipe editor. The id field keeps the full column otherwise. */
    void syncCreateChrome() {
        boolean creating = host.creating;
        if (createTypeSelector != null) createTypeSelector.setVisible(creating);
        if (guessId != null) guessId.setVisible(creating);
        if (recipeIdField == null || createChrome == creating) return;
        createChrome = creating;
        recipeIdField.setSize(creating ? SIDE_W - GUESS_W - GAP : SIDE_W, CTRL_H);
    }

    @Override
    public void drawInBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        long now = ShanhaiRecipeEditorAnimation.nowMs();
        layoutMotion(now);
        requestSlideBlur(graphics, now);
        // Both column plates stay put; only what sits on them slides or fades.
        int x = getPositionX();
        int y = getPositionY();
        sideBack.draw(graphics, mouseX, mouseY, x + SIDE_X, y + BODY_Y, SIDE_W, BODY_H);
        mainBack.draw(graphics, mouseX, mouseY, x + MAIN_X, y + BODY_Y, MAIN_W, BODY_H);
        super.drawInBackground(graphics, mouseX, mouseY, partialTicks);
    }

    /**
     * Once, from the client tick: compile the blur shader now instead of on the first page slide.
     * Not from draw: building the post chain mid-render leaves the default framebuffer bound for the rest of that frame.
     */
    private void warmBlurOnce() {
        if (blurWarmed) return;
        blurWarmed = true;
        GlassBlur.warmUp();
    }

    /**
     * Horizontal motion blur while a stage slides. The viewport is the stationary content column,
     * so the blur does not travel with the pose offset. A missing post chain simply skips it.
     */
    private void requestSlideBlur(GuiGraphics graphics, long now) {
        if (!host.animation.moving(now)) return;
        GuiRect viewport = new GuiRect(getPositionX() + MAIN_X,
                getPositionY() + BODY_Y,
                MAIN_W, BODY_H);
        GlassBlur.requestPage(graphics, viewport, GlassMotion.pageSlide(host.animation.linear(now)));
    }

    /**
     * The offset is a float drawn with a pose translate. Moving widget positions
     * every frame rebuilds the whole tree and drops the slide back to tick rate.
     * Content slides across the content column; the sidebar of the old stage is
     * veiled over the first half and the new one unveiled over the second.
     */
    private void layoutMotion(long now) {
        host.animation.advance(host.stage, now);
        GlassStagePage outgoing = page(host.fromStage);
        GlassStagePage incoming = page(host.stage);
        if (host.animation.moving(now) && outgoing != incoming) {
            float progress = host.animation.progress(now);
            float travel = MAIN_W;
            int direction = host.fromStage < host.stage ? -1 : 1;
            show(outgoing, direction * travel * progress, false);
            show(incoming, -direction * travel * (1f - progress), false);
            int other = 3 - host.fromStage - host.stage;
            if (other != host.fromStage && other != host.stage) {
                show(page(other), 0f, false, false);
                hideSide(side(other));
            }
            swapSides(side(host.fromStage), side(host.stage), progress);
            return;
        }
        showSettled(selectPage, ShanhaiRecipeEditorWidget.STAGE_SELECT);
        showSettled(editPage, ShanhaiRecipeEditorWidget.STAGE_EDIT);
        showSettled(reviewPage, ShanhaiRecipeEditorWidget.STAGE_REVIEW);
        settleSide(selectSide, ShanhaiRecipeEditorWidget.STAGE_SELECT);
        settleSide(editSide, ShanhaiRecipeEditorWidget.STAGE_EDIT);
        settleSide(reviewSide, ShanhaiRecipeEditorWidget.STAGE_REVIEW);
    }

    private static void swapSides(GlassFadePage outgoing, GlassFadePage incoming, float progress) {
        boolean first = progress < 0.5f;
        float veil = first ? progress / 0.5f : 1f - (progress - 0.5f) / 0.5f;
        outgoing.setVisible(first);
        outgoing.setActive(false);
        outgoing.veil = veil;
        outgoing.lift = 0f;
        incoming.setVisible(!first);
        incoming.setActive(false);
        incoming.veil = veil;
        incoming.lift = first ? SIDE_LIFT : SIDE_LIFT * veil;
    }

    private static void hideSide(GlassFadePage side) {
        side.setVisible(false);
        side.setActive(false);
    }

    private void settleSide(GlassFadePage side, int stage) {
        boolean visible = host.stage == stage;
        side.veil = 0f;
        side.lift = 0f;
        side.setVisible(visible);
        side.setActive(visible);
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

    static void fillPage(int[] dest, int page, int base, int count, int pageSize) {
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
        int half = share(SIDE_W, 2);
        int third = share(SIDE_W, 3);
        GlassFlow side = new GlassFlow(SIDE_X, BODY_Y, SIDE_W, BODY_H);

        GlassRow machine = new GlassRow(0, 0, SIDE_W, ShanhaiQuerySlotWidget.CELL);
        machine.add(new ShanhaiRecipeMachineSlotWidget(0, 0, () -> host.machineStack, host::setMachine));
        int machineTextX = ShanhaiQuerySlotWidget.CELL + GAP;
        machine.add(new GlassWrapLabel(machineTextX, 7, SIDE_W - machineTextX, () -> {
            if (host.machineStack.isEmpty()) {
                return host.ownedTypeCount > 0
                        ? "§8未放入 GT 机器 · 山海类型 " + host.ownedTypeCount + " 种"
                        : "§8未放入 GT 机器，也可按配方 ID、原料或输出搜索";
            }
            String line = "§f" + host.machineStack.getHoverName().getString()
                    + " §7· 已映射 " + host.mappedTypeCount + " 种";
            if (host.ownedTypeCount > 0) line += " · 山海类型 " + host.ownedTypeCount + " 种";
            return line;
        }));
        side.add(machine);

        ButtonWidget addRecipe = button(0, 0, SIDE_W, BTN_H, "配方添加", host::beginCreate);
        addRecipe.setHoverTooltips("新建一条配方。类型在编辑页点选，id 可按类型、输入和产物猜测");
        side.add(addRecipe);
        side.add(textField(0, 0, SIDE_W, BTN_H, () -> host.typeFilter,
                value -> {
                    host.typeFilter = ShanhaiRecipeEditorWidget.limit(value);
                    host.scheduleDraftSave();
                }));

        int cell = ShanhaiQuerySlotWidget.CELL;
        GlassRow search = new GlassRow(0, 0, SIDE_W, cell);
        int slotX = SIDE_W - cell;
        int historyX = slotX - cell;
        search.add(textField(0, (cell - BTN_H) / 2, historyX - GAP, BTN_H, () -> host.searchText,
                value -> {
                    host.searchText = ShanhaiRecipeEditorWidget.limit(value);
                    host.scheduleDraftSave();
                }));
        queryHistory = new ShanhaiRecipeHistoryWidget(host, historyX, 0);
        queryHistory.setClientSideWidget();
        search.add(queryHistory);
        querySlot = new ShanhaiQuerySlotWidget(slotX, 0, host::acceptQueryDrop);
        search.add(querySlot);
        side.add(search);

        WidgetGroup actions = new WidgetGroup(0, 0, SIDE_W, CTRL_H);
        actions.addWidget(button(shareX(0, SIDE_W, 2, 0), 0, half, CTRL_H, "搜索", () -> {
            host.queryPage = 0;
            host.flushDraftSave();
            host.query();
        }));
        actions.addWidget(button(shareX(0, SIDE_W, 2, 1), 0, half, CTRL_H, "清空", () -> {
            host.typeFilter = "";
            host.searchText = "";
            host.queryPage = 0;
            clearQuerySlot();
            host.flushDraftSave();
            host.query();
        }));
        side.add(actions);

        // Mode row, kind row, and the bars under both: one block so the bars move with the rows.
        int kindY = CTRL_H + GAP;
        WidgetGroup choices = new WidgetGroup(0, 0, SIDE_W, kindY + CTRL_H + 2);
        int[] modeXs = {shareX(0, SIDE_W, 3, 0), shareX(0, SIDE_W, 3, 1), shareX(0, SIDE_W, 3, 2)};
        int[] kindXs = {shareX(0, SIDE_W, 2, 0), shareX(0, SIDE_W, 2, 1)};
        choices.addWidget(modeButton(modeXs[0], 0, third, ShanhaiRecipeQuery.SearchMode.RECIPE_ID, "配方"));
        choices.addWidget(modeButton(modeXs[1], 0, third, ShanhaiRecipeQuery.SearchMode.INGREDIENT, "原料"));
        choices.addWidget(modeButton(modeXs[2], 0, third, ShanhaiRecipeQuery.SearchMode.OUTPUT, "输出"));
        choices.addWidget(kindButton(kindXs[0], kindY, half, ShanhaiRecipeQuery.IngredientKind.ITEM, "物品"));
        choices.addWidget(kindButton(kindXs[1], kindY, half, ShanhaiRecipeQuery.IngredientKind.FLUID, "流体"));
        choices.addWidget(new ShanhaiRecipeChoiceMarkerWidget(host,
                modeXs, new int[] {third, third, third}, CTRL_H,
                kindXs, new int[] {half, half}, kindY + CTRL_H));
        side.add(choices);
        side.add(new GlassWrapLabel(0, 0, SIDE_W, host::searchHint));

        // Five sort keys on two rows; the bar crosses rows when the key changes.
        int[] sortXs = {modeXs[0], modeXs[1], modeXs[2], kindXs[0], kindXs[1]};
        int[] sortYs = {0, 0, 0, kindY, kindY};
        int[] sortWs = {third, third, third, half, half};
        String[] sortFaces = {"A-Z", "EU", "耗时", "输入数", "输出数"};
        WidgetGroup sorts = new WidgetGroup(0, 0, SIDE_W, kindY + CTRL_H + 2);
        for (int i = 0; i < sortFaces.length; i++) {
            sorts.addWidget(sortButton(sortXs[i], sortYs[i], sortWs[i],
                    ShanhaiRecipeSortMarkerWidget.KEYS[i], sortFaces[i]));
        }
        int[] barYs = {CTRL_H, CTRL_H, CTRL_H, kindY + CTRL_H, kindY + CTRL_H};
        sorts.addWidget(new ShanhaiRecipeSortMarkerWidget(host, sortXs, barYs, sortWs));
        side.add(sorts);
        selectSide.addWidget(side);

        selectPage.addWidget(resultList);
        selectPage.addWidget(button(MAIN_X, PAGER_Y, 24, CTRL_H, "<", () -> host.shiftQueryPage(-1)));
        selectPage.addWidget(new GlassWrapLabel(MAIN_X + 24 + GAP, PAGER_Y + 4, 80, () -> "第 " + (host.queryPage + 1)
                + "/" + host.pageCount(host.queryTotal, ShanhaiRecipeEditorWidget.QUERY_PAGE_SIZE) + " 页")
                .alignCenter());
        selectPage.addWidget(button(MAIN_X + 24 + GAP + 80 + GAP, PAGER_Y, 24, CTRL_H, ">",
                () -> host.shiftQueryPage(1)));
        int totalX = MAIN_X + 24 + GAP + 80 + GAP + 24 + GAP;
        selectPage.addWidget(new GlassWrapLabel(totalX, PAGER_Y + 4, MAIN_RIGHT - totalX, () -> host.queryTotal <= 0
                ? "§8等待配方卡片" : "§a共 " + host.queryTotal + " 条").alignRight());

        selectSide.addWidget(button(SIDE_X, FOOT_Y, FOOT_BTN_W, BTN_H, "刷新配方表", host::query));
        selectSide.addWidget(button(FOOT_RIGHT_X, FOOT_Y, FOOT_BTN_W, BTN_H, "进入编辑",
                () -> host.setStage(ShanhaiRecipeEditorWidget.STAGE_EDIT, true)));
    }

    private void buildEditPage() {
        int half = share(SIDE_W, 2);
        GlassFlow side = new GlassFlow(SIDE_X, BODY_Y, SIDE_W, BODY_H);

        GlassRow id = new GlassRow(0, 0, SIDE_W, CTRL_H);
        recipeIdField = textField(0, 0, SIDE_W, CTRL_H, () -> host.recipeIdText, host::setRecipeIdText);
        id.add(recipeIdField);
        guessId = button(SIDE_W - GUESS_W, 0, GUESS_W, CTRL_H, "猜测", host::guessCreateId);
        guessId.setHoverTooltips("填入 gt_shanhai:类型/输入1_产物1。类型是文件夹，只取第一个输入和第一个产物");
        guessId.setVisible(false);
        id.add(guessId);
        side.add(id);

        createTypeSelector = selector(0, 0, SIDE_W, 24, List.of())
                .setMaxCount(8)
                .setCandidatesSupplier(() -> List.copyOf(host.machineTypeNames))
                .setSupplier(host::createTypeLabel)
                .setOnChanged(host::pickCreateType);
        ((ShanhaiRecipeTypeSelector) createTypeSelector).setPopupWidth(MAIN_RIGHT - SIDE_X);
        createTypeSelector.setClientSideWidget();
        createTypeSelector.setVisible(false);
        side.add(createTypeSelector);

        side.add(new LabelWidget(0, 0, "§7耗时 / tick"));
        side.add(textField(0, 0, SIDE_W, BTN_H, () -> host.durationText, host::setDurationText));
        side.add(new LabelWidget(0, 0, "§7EU/t"));
        int voltageWidth = share(SIDE_W, 3);
        WidgetGroup eut = new WidgetGroup(0, 0, SIDE_W, BTN_H);
        eut.addWidget(textField(0, 0, SIDE_W - voltageWidth - GAP, BTN_H, () -> host.eutText,
                value -> host.setEutText(value)));
        SelectorWidget voltage = selector(SIDE_W - voltageWidth, 0, voltageWidth, BTN_H, ShanhaiVoltageTiers.names())
                .setMaxCount(10)
                .setCandidatesSupplier(ShanhaiVoltageTiers::names)
                .setSupplier(host::voltageFace)
                .setOnChanged(host::pickVoltage);
        voltage.setClientSideWidget();
        voltage.setHoverTooltips("点选电压档，数字写入下方 EU/t。MAX = 2147483647，MAX+16 = Long.MAX_VALUE");
        eut.addWidget(voltage);
        side.add(eut);

        WidgetGroup undo = new WidgetGroup(0, 0, SIDE_W, BTN_H);
        undo.addWidget(button(shareX(0, SIDE_W, 2, 0), 0, half, BTN_H, "撤销修改", host::undoEdit));
        undo.addWidget(button(shareX(0, SIDE_W, 2, 1), 0, half, BTN_H, "还原", host::restoreOriginal));
        side.add(undo);
        editSide.addWidget(side);

        inputItemGrid = addIoBlock(0, "§a输入物品", inputItemCells, shownItemIn, ITEM_PAGE,
                () -> host.pageLabel("物品", host.inputItemPage, host.ioTable.itemIn(), ITEM_PAGE),
                () -> host.shiftInputItemPage(-1), () -> host.shiftInputItemPage(1), host::growInput);
        inputFluidGrid = addIoBlock(1, "§b输入流体", inputFluidCells, shownFluidIn, FLUID_PAGE,
                () -> host.pageLabel("流体", host.inputFluidPage, host.ioTable.fluidIn(), FLUID_PAGE),
                () -> host.shiftInputFluidPage(-1), () -> host.shiftInputFluidPage(1), null);
        outputItemGrid = addIoBlock(2, "§d输出物品", outputItemCells, shownItemOut, ITEM_PAGE,
                () -> host.pageLabel("物品", host.outputItemPage, host.ioTable.itemOut(), ITEM_PAGE),
                () -> host.shiftOutputItemPage(-1), () -> host.shiftOutputItemPage(1), host::growOutput);
        outputFluidGrid = addIoBlock(3, "§b输出流体", outputFluidCells, shownFluidOut, FLUID_PAGE,
                () -> host.pageLabel("流体", host.outputFluidPage, host.ioTable.fluidOut(), FLUID_PAGE),
                () -> host.shiftOutputFluidPage(-1), () -> host.shiftOutputFluidPage(1), null);

        // Selected-cell inspector under the four grids.
        int inspectorY = BODY_Y + 4 * IO_BLOCK;
        GlassFlow inspector = new GlassFlow(MAIN_X, inspectorY, MAIN_W, BODY_BOTTOM - inspectorY);
        inspector.add(new GlassWrapLabel(0, 0, MAIN_W, host::traitLine));
        WidgetGroup traits = new WidgetGroup(0, 0, MAIN_W, CTRL_H);
        traits.addWidget(button(0, 0, 52, CTRL_H, "不消耗", host::markNotConsumed));
        traits.addWidget(button(56, 0, 64, CTRL_H, "概率产物", host::markChanceOutput));
        traits.addWidget(textField(124, 0, 36, CTRL_H, () -> host.chanceText, host::setChanceText));
        traits.addWidget(new LabelWidget(163, 4, "§7%%"));
        traits.addWidget(button(176, 0, 40, CTRL_H, "必出", host::markCertain));
        traits.addWidget(new LabelWidget(222, 4, "§7数量"));
        traits.addWidget(textField(250, 0, 48, CTRL_H, () -> host.countText, host::setCountText));
        inspector.add(traits);
        SelectorWidget match = selector(0, 0, MAIN_W, CTRL_H, List.of("精确"))
                .setMaxCount(14)
                .setCandidatesSupplier(host::matchChoices)
                .setSupplier(host::matchFace)
                .setOnChanged(host::pickMatch);
        match.setClientSideWidget();
        match.setHoverTooltips("点中一格后，这里列出该物品或流体自己的标签。选中后这一格按标签匹配，精确则只用注册 id");
        inspector.add(match);
        inspector.add(new GlassWrapLabel(0, 0, MAIN_W, () -> "§7" + host.ioTable.itemIn()
                + " 物品入 / " + host.ioTable.fluidIn() + " 流体入 → "
                + host.ioTable.itemOut() + " 物品出 / " + host.ioTable.fluidOut() + " 流体出"));
        inspector.add(new GlassWrapLabel(0, 0, MAIN_W, "§8输入 0%% = 不消耗；输出百分比 = 概率产物。加页会扩出空格子"));
        editPage.addWidget(inspector);

        editSide.addWidget(button(SIDE_X, FOOT_Y, FOOT_BTN_W, BTN_H, "返回配方卡",
                () -> host.setStage(ShanhaiRecipeEditorWidget.STAGE_SELECT, true)));
        ButtonWidget jsExport = button(MAIN_X, FOOT_Y, FOOT_BTN_W, BTN_H, "导出JS数组", host::copyJsArray);
        jsExport.setClientSideWidget();
        editSide.addWidget(jsExport);
        editSide.addWidget(new ButtonWidget(FOOT_RIGHT_X, FOOT_Y, FOOT_BTN_W, BTN_H,
                new GuiTextureGroup(GlassPanelTexture.button(), new TextTexture(host::reviewButtonFace)),
                click -> host.setStage(ShanhaiRecipeEditorWidget.STAGE_REVIEW, true)));
    }

    /**
     * One IO block: title and pager on the first row, then a single row of cells
     * framed across the whole content column. All four blocks share the cell columns.
     */
    private GlassIoGrid addIoBlock(int index, String title, List<ShanhaiIOWidget> into, int[] shown, int count,
                                   Supplier<String> pageLabel, Runnable previous, Runnable next, Runnable grow) {
        int top = BODY_Y + index * IO_BLOCK;
        int growX = MAIN_RIGHT - 44;
        int nextX = growX - GAP - 18;
        int labelX = nextX - GAP - 70;
        int previousX = labelX - GAP - 18;
        editPage.addWidget(new LabelWidget(MAIN_X, top + 3, title));
        editPage.addWidget(button(previousX, top, 18, 14, "<", previous));
        editPage.addWidget(new GlassWrapLabel(labelX, top + 3, 70, pageLabel).alignCenter());
        editPage.addWidget(button(nextX, top, 18, 14, ">", next));
        if (grow != null) editPage.addWidget(button(growX, top, 44, 14, "加页", grow));
        return addGrid(into, shown, count, MAIN_X + 3, top + IO_TITLE_H + 3, GRID_COLS);
    }

    private void buildReviewPage() {
        DraggableScrollableWidgetGroup summaryBox = new DraggableScrollableWidgetGroup(SIDE_X, BODY_Y, SIDE_W, BODY_H)
                .setYScrollBarWidth(4)
                .setYBarStyle(new ColorRectTexture(0xff79c9dd), GuiTextures.VANILLA_BUTTON)
                .setUseScissor(true);
        int summaryWidth = SIDE_W - 4 - 2 * 2;
        GlassFlow summary = new GlassFlow(2, 2, summaryWidth, 0, true);
        summary.add(new GlassWrapLabel(0, 0, summaryWidth, host::reviewBanner));
        summary.add(new GlassWrapLabel(0, 0, summaryWidth, host::reviewIdLine));
        summary.add(new GlassWrapLabel(0, 0, summaryWidth, () -> host.recipeIdText.isEmpty()
                ? "" : "§7配方 id §f" + host.recipeIdText));
        summary.add(new GlassWrapLabel(0, 0, summaryWidth, () -> host.selectedBase == null
                ? "" : "§7类型 §f" + host.selectedBase.recipeTypeId()));
        summary.add(new GlassWrapLabel(0, 0, summaryWidth, host::fingerprintLine));
        summary.add(new GlassWrapLabel(0, 0, summaryWidth, host::ioLine));
        summary.add(new GlassWrapLabel(0, 0, summaryWidth, host::conditionLine));
        summary.add(new GlassWrapLabel(0, 0, summaryWidth, host::durationDiffLine));
        summary.add(new GlassWrapLabel(0, 0, summaryWidth, host::eutDiffLine));
        summary.add(new GlassWrapLabel(0, 0, summaryWidth, host::codecHint));
        summaryBox.addWidget(summary);
        reviewSide.addWidget(summaryBox);

        int toggleWidth = 88;
        int hintX = toggleWidth + GAP;
        GlassFlow main = new GlassFlow(MAIN_X, BODY_Y, MAIN_W, BODY_H);
        GlassRow keep = new GlassRow(0, 0, MAIN_W, CTRL_H);
        keepToggle = new ButtonWidget(0, 0, toggleWidth, CTRL_H,
                new GuiTextureGroup(GlassPanelTexture.button(), new TextTexture(host::keepOriginalFace)),
                click -> host.toggleKeepOriginal());
        keep.add(keepToggle);
        keep.add(new GlassWrapLabel(hintX, 4, MAIN_W - hintX, host::keepOriginalHint));
        main.add(keep);
        GlassRow enabled = new GlassRow(0, 0, MAIN_W, CTRL_H);
        ButtonWidget enabledToggle = new ButtonWidget(0, 0, toggleWidth, CTRL_H,
                new GuiTextureGroup(GlassPanelTexture.button(), new TextTexture(host::defaultEnabledFace)),
                click -> host.toggleDefaultEnabled());
        enabledToggle.setClientSideWidget();
        enabledToggle.setHoverTooltips("导出 json 写入 defaultEnabled。关掉后提交会禁用当前配方");
        enabled.add(enabledToggle);
        enabled.add(new GlassWrapLabel(hintX, 4, MAIN_W - hintX, host::defaultEnabledHint));
        main.add(enabled);

        int addWidth = 44;
        WidgetGroup pick = new WidgetGroup(0, 0, MAIN_W, CTRL_H);
        SelectorWidget conditionSelector = selector(0, 0, MAIN_W - addWidth - GAP, CTRL_H, List.of())
                .setMaxCount(12)
                .setCandidatesSupplier(ShanhaiRecipeConditions::choices)
                .setSupplier(() -> host.conditionPick.isEmpty() ? "添加条件" : host.conditionPick)
                .setOnChanged(host::setConditionPick);
        ((ShanhaiRecipeTypeSelector) conditionSelector).setPopupWidth(MAIN_W);
        conditionSelector.setClientSideWidget();
        pick.addWidget(conditionSelector);
        ButtonWidget addCondition = button(MAIN_W - addWidth, 0, addWidth, CTRL_H, "添加", host::addCondition);
        addCondition.setClientSideWidget();
        pick.addWidget(addCondition);
        main.add(pick);

        conditionList = new DraggableScrollableWidgetGroup(0, 0, MAIN_W, 88)
                .setYScrollBarWidth(4)
                .setYBarStyle(new ColorRectTexture(0xff79c9dd), GuiTextures.VANILLA_BUTTON)
                .setBackground(GlassPanelTexture.display())
                .setUseScissor(true);
        main.add(conditionList);

        dataRow = new WidgetGroup(0, 0, MAIN_W, 14);
        dataKeySelector = selector(0, 0, 100, 14, List.of())
                .setMaxCount(12)
                .setCandidatesSupplier(host::dataKeyChoices)
                .setSupplier(host::dataKeyFace)
                .setOnChanged(host::pickDataKey);
        ((ShanhaiRecipeTypeSelector) dataKeySelector).setPopupWidth(MAIN_W);
        dataKeySelector.setClientSideWidget();
        dataKeySelector.setVisible(false);
        dataKeySelector.setHoverTooltips("温度、恒星热力容器、组件产线、纳米锻炉、中子活化、裂变热、研磨球、聚变启动 EU");
        dataRow.addWidget(dataKeySelector);
        coilSelector = selector(100 + GAP, 0, MAIN_W - 100 - GAP, 14, List.of())
                .setMaxCount(14)
                .setCandidatesSupplier(host::coilChoices)
                .setSupplier(host::coilSelectFace)
                .setOnChanged(host::pickCoil);
        coilSelector.setClientSideWidget();
        coilSelector.setVisible(false);
        coilSelector.setHoverTooltips("按线圈温度选择。选中后把配方温度设成这颗线圈的温度");
        dataRow.addWidget(coilSelector);
        dataValueField = textField(100 + GAP, 0, MAIN_W - 100 - GAP, 14, () -> host.blastTempText, host::setBlastTempText);
        dataValueField.setVisible(false);
        dataValueField.setHoverTooltips("这项配方数据的数字");
        dataRow.addWidget(dataValueField);
        dataRow.setVisible(false);
        main.add(dataRow);
        dataLine = new GlassWrapLabel(0, 0, MAIN_W, host::dataRequirementLine);
        dataLine.setVisible(false);
        main.add(dataLine);

        main.add(new LabelWidget(0, 0, "§7条件参数"));
        parameterList = new DraggableScrollableWidgetGroup(0, 0, MAIN_W, 64)
                .setYScrollBarWidth(4)
                .setYBarStyle(new ColorRectTexture(0xff79c9dd), GuiTextures.VANILLA_BUTTON)
                .setBackground(GlassPanelTexture.display())
                .setUseScissor(true);
        main.addFill(parameterList);
        reviewPage.addWidget(main);

        int middle = MAIN_X;
        int encodeWidth = 100;
        int exportWidth = FOOT_RIGHT_X - GAP - (middle + encodeWidth + GAP);
        reviewSide.addWidget(button(SIDE_X, FOOT_Y, FOOT_BTN_W, BTN_H, "返回图形编辑",
                () -> host.setStage(ShanhaiRecipeEditorWidget.STAGE_EDIT, true)));
        ButtonWidget encodePattern = button(middle, FOOT_Y, encodeWidth, BTN_H, "快速编写为样板", host::encodePattern);
        encodePattern.setHoverTooltips("按当前审核稿编码样板并上传。需要身上的无线终端，以及网络中的 1 张空白样板");
        reviewSide.addWidget(encodePattern);
        reviewSide.addWidget(button(middle + encodeWidth + GAP, FOOT_Y, exportWidth, BTN_H,
                "导出配方为json", host::exportJson));
        reviewSide.addWidget(new ButtonWidget(FOOT_RIGHT_X, FOOT_Y, FOOT_BTN_W, BTN_H,
                new GuiTextureGroup(GlassPanelTexture.button(), new TextTexture(host::commitFace)),
                click -> host.commit()));
    }

    private GlassIoGrid addGrid(List<ShanhaiIOWidget> into, int[] shown, int count,
                                int x0, int y0, int cols) {
        int rows = Math.max(1, (count + cols - 1) / cols);
        editPage.addWidget(new ImageWidget(x0 - 3, y0 - 3, cols * CELL_STRIDE + 4, rows * CELL_STRIDE + 4,
                GlassPanelTexture.display()));
        GlassIoGrid grid = new GlassIoGrid(x0, y0, cols * CELL_STRIDE, rows * CELL_STRIDE);
        for (int i = 0; i < count; i++) {
            int slot = i;
            ShanhaiIOWidget cell = new ShanhaiIOWidget(host.ioTable, () -> shown[slot],
                    (i % cols) * CELL_STRIDE, (i / cols) * CELL_STRIDE, host::syncIoDraft);
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

    private GlassStagePage mainPage() {
        return new GlassStagePage(MAIN_X, BODY_Y, MAIN_W, BODY_H,
                ShanhaiRecipeEditorWidget.WIDTH, ShanhaiRecipeEditorWidget.HEIGHT);
    }

    /** Sidebar column plus the footer strip; the veil covers both. */
    private static GlassFadePage sidePage() {
        return new GlassFadePage(ShanhaiRecipeEditorWidget.WIDTH, ShanhaiRecipeEditorWidget.HEIGHT)
                .addVeil(SIDE_X + 1, BODY_Y + 1, SIDE_W - 2, BODY_H - 2, GlassPalette.VEIL_PAGE)
                .addVeil(SIDE_X, FOOT_Y, MAIN_RIGHT - SIDE_X, BTN_H, GlassPalette.VEIL_ROOT);
    }

    private GlassStagePage page(int stage) {
        if (stage == ShanhaiRecipeEditorWidget.STAGE_EDIT) return editPage;
        if (stage == ShanhaiRecipeEditorWidget.STAGE_REVIEW) return reviewPage;
        return selectPage;
    }

    private GlassFadePage side(int stage) {
        if (stage == ShanhaiRecipeEditorWidget.STAGE_EDIT) return editSide;
        if (stage == ShanhaiRecipeEditorWidget.STAGE_REVIEW) return reviewSide;
        return selectSide;
    }

    private static void show(GlassStagePage page, float slide, boolean active) {
        show(page, slide, true, active);
    }

    private static void show(GlassStagePage page, float slide, boolean visible, boolean active) {
        page.slide = slide;
        page.setVisible(visible);
        page.setActive(active && visible);
    }

    private void showSettled(GlassStagePage page, int stage) {
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

    private SelectorWidget selector(int x, int y, int width, int height, List<String> candidates) {
        return new ShanhaiRecipeTypeSelector(x, y, width, height, candidates, GlassPalette.TEXT)
                .setButtonBackground(GlassPanelTexture.button())
                .setBackground(GlassPanelTexture.panel())
                .setFontColor(GlassPalette.TEXT);
    }

    private TextFieldWidget textField(int x, int y, int width, int height,
                                      java.util.function.Supplier<String> getter,
                                      java.util.function.Consumer<String> setter) {
        TextFieldWidget field = new TextFieldWidget(x, y, width, height, getter, setter)
                .setMaxStringLength(256)
                .setBackground(GlassPanelTexture.display());
        field.setClientSideWidget();
        return field;
    }

    private ButtonWidget modeButton(int x, int y, int width,
                                    ShanhaiRecipeQuery.SearchMode mode, String plain) {
        return new ButtonWidget(x, y, width, CTRL_H,
                new GuiTextureGroup(GlassPanelTexture.button(), new TextTexture(() -> host.modeFace(mode, plain))),
                click -> host.setSearchMode(mode));
    }

    private ButtonWidget kindButton(int x, int y, int width,
                                    ShanhaiRecipeQuery.IngredientKind kind, String plain) {
        return new ButtonWidget(x, y, width, CTRL_H,
                new GuiTextureGroup(GlassPanelTexture.button(), new TextTexture(() -> host.kindFace(kind, plain))),
                click -> host.setIngredientKind(kind));
    }

    private ButtonWidget sortButton(int x, int y, int width, String key, String plain) {
        return new ButtonWidget(x, y, width, CTRL_H,
                new GuiTextureGroup(GlassPanelTexture.button(), new TextTexture(() -> host.sortFace(key, plain))),
                click -> host.setSort(key));
    }

    private ButtonWidget button(int x, int y, int width, int height, String text, Runnable action) {
        return new ButtonWidget(x, y, width, height,
                new GuiTextureGroup(GlassPanelTexture.button(), new TextTexture(text)),
                click -> {
                    if (action != null) action.run();
                });
    }
}
