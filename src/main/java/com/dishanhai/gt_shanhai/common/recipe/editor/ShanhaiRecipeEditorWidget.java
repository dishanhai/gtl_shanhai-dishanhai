package com.dishanhai.gt_shanhai.common.recipe.editor;

import com.dishanhai.gt_shanhai.network.RecipeEditorCommitPacket;
import com.dishanhai.gt_shanhai.network.RecipeEditorDetailPacket;
import com.dishanhai.gt_shanhai.network.RecipeEditorQueryPacket;
import com.dishanhai.gt_shanhai.network.RecipeEditorResultPacket;
import com.dishanhai.gt_shanhai.network.ShanhaiNetwork;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.gregtechceu.gtceu.api.gui.GuiTextures;
import com.lowdragmc.lowdraglib.gui.texture.GuiTextureGroup;
import com.lowdragmc.lowdraglib.gui.texture.TextTexture;
import com.lowdragmc.lowdraglib.gui.widget.ButtonWidget;
import com.lowdragmc.lowdraglib.gui.widget.DraggableScrollableWidgetGroup;
import com.lowdragmc.lowdraglib.gui.widget.ImageWidget;
import com.lowdragmc.lowdraglib.gui.widget.LabelWidget;
import com.lowdragmc.lowdraglib.gui.widget.TextFieldWidget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Layered LDLib UI for recipe selection, editing and review.
 *
 * <p>The visual structure follows the EnhancedCore pattern-generator layout:
 * a stable top rail, one focused task page at a time, paged server results and
 * a separate review page before a commit is sent.</p>
 */
public final class ShanhaiRecipeEditorWidget extends WidgetGroup {

    public static final int WIDTH = 420;
    public static final int HEIGHT = 300;
    public static final int STAGE_SELECT = 0;
    public static final int STAGE_EDIT = 1;
    public static final int STAGE_REVIEW = 2;
    private static final int MAX_JSON_TEXT = 32767;

    private static volatile ShanhaiRecipeEditorWidget ACTIVE_CLIENT;

    private final Player player;
    private final ShanhaiRecipeEditorAnimation animation =
            new ShanhaiRecipeEditorAnimation();
    private final WidgetGroup selectPage = new WidgetGroup(10, 54, 400, 226);
    private final WidgetGroup editPage = new WidgetGroup(10, 54, 400, 226);
    private final WidgetGroup reviewPage = new WidgetGroup(10, 54, 400, 226);
    private final DraggableScrollableWidgetGroup resultList =
            new DraggableScrollableWidgetGroup(12, 70, 396, 128)
                    .setYScrollBarWidth(4)
                    .setBackground(GuiTextures.DISPLAY)
                    .setUseScissor(true);
    private final List<ShanhaiRecipeQuery.Card> cards = new ArrayList<>();

    private TextFieldWidget typeField;
    private TextFieldWidget searchField;
    private TextFieldWidget durationField;
    private TextFieldWidget eutField;
    private TextFieldWidget inputsField;
    private TextFieldWidget outputsField;
    private LabelWidget statusLabel;
    private LabelWidget editStatusLabel;
    private LabelWidget reviewLabel;

    private String typeFilter = "";
    private String searchText = "";
    private String durationText = "1";
    private String eutText = "0";
    private String inputsText = "{}";
    private String outputsText = "{}";
    private int stage = STAGE_SELECT;
    private int fromStage = STAGE_SELECT;
    private ShanhaiRecipeQuery.Card selectedCard;
    private ShanhaiRecipeBase selectedBase;

    public ShanhaiRecipeEditorWidget(Player player) {
        super(0, 0, WIDTH, HEIGHT);
        this.player = player;
        setId("shanhai_recipe_editor");
        if (player != null && player.level().isClientSide) {
            ACTIVE_CLIENT = this;
        }

        addWidget(new LabelWidget(10, 6, "配方修改器"));
        addWidget(new LabelWidget(10, 18,
                "选择 → 编辑 → 审核：每一步只保留当前任务"));
        addWidget(new ImageWidget(10, 38, WIDTH - 20, 2, GuiTextures.DISPLAY));
        addWidget(stageButton(10, 42, 126, "1 选择", STAGE_SELECT));
        addWidget(stageButton(147, 42, 126, "2 编辑", STAGE_EDIT));
        addWidget(stageButton(284, 42, 126, "3 审核", STAGE_REVIEW));

        buildSelectPage();
        buildEditPage();
        buildReviewPage();
        addWidget(selectPage);
        addWidget(editPage);
        addWidget(reviewPage);
        setStage(STAGE_SELECT, false);
        if (player != null && player.level().isClientSide) {
            query();
        }
    }

    private ButtonWidget stageButton(int x, int y, int width, String text, int next) {
        return new ButtonWidget(x, y, width, 18,
                new GuiTextureGroup(GuiTextures.BUTTON, new TextTexture(text)),
                click -> {
                    if (next == STAGE_EDIT && selectedBase == null) return;
                    if (next == STAGE_REVIEW && selectedBase == null) return;
                    setStage(next, true);
                });
    }

    private void buildSelectPage() {
        selectPage.setBackground(GuiTextures.BACKGROUND_INVERSE);
        selectPage.addWidget(new LabelWidget(12, 8, "1 选择目标配方"));
        typeField = textField(12, 26, 188, 18, () -> typeFilter,
                value -> typeFilter = limit(value));
        searchField = textField(208, 26, 188, 18, () -> searchText,
                value -> searchText = limit(value));
        selectPage.addWidget(typeField);
        selectPage.addWidget(searchField);
        selectPage.addWidget(button(12, 48, 98, 18, "查询", this::query));
        statusLabel = new LabelWidget(118, 52, () -> cards.isEmpty()
                ? "未载入配方" : "命中 " + cards.size() + " / 当前页");
        selectPage.addWidget(statusLabel);
        selectPage.addWidget(resultList);
        selectPage.addWidget(button(12, 204, 126, 18, "返回全息菜单",
                () -> player.closeContainer()));
        selectPage.addWidget(button(270, 204, 126, 18, "刷新列表", this::query));
    }

    private void buildEditPage() {
        editPage.setBackground(GuiTextures.BACKGROUND_INVERSE);
        editPage.addWidget(new LabelWidget(12, 8, "2 编辑工作台"));
        editPage.addWidget(new LabelWidget(12, 24,
                () -> selectedCard == null ? "尚未选择配方" : selectedCard.recipeId()));
        durationField = textField(12, 48, 188, 18, () -> durationText,
                value -> durationText = limit(value));
        eutField = textField(208, 48, 188, 18, () -> eutText,
                value -> eutText = limit(value));
        editPage.addWidget(durationField);
        editPage.addWidget(eutField);
        editPage.addWidget(new LabelWidget(12, 42, "耗时"));
        editPage.addWidget(new LabelWidget(208, 42, "EU/t"));
        inputsField = textField(12, 92, 384, 18, () -> inputsText,
                value -> inputsText = limitJson(value));
        outputsField = textField(12, 132, 384, 18, () -> outputsText,
                value -> outputsText = limitJson(value));
        editPage.addWidget(new LabelWidget(12, 82, "输入 JSON（保留 GTCEu codec 结构）"));
        editPage.addWidget(inputsField);
        editPage.addWidget(new LabelWidget(12, 122, "输出 JSON（保留 GTCEu codec 结构）"));
        editPage.addWidget(outputsField);
        editStatusLabel = new LabelWidget(12, 164, () -> selectedBase == null
                ? "等待配方快照" : "IO 页签已拆分；当前页编辑基础参数与完整 codec JSON");
        editPage.addWidget(editStatusLabel);
        editPage.addWidget(button(12, 194, 126, 18, "← 返回选择",
                () -> setStage(STAGE_SELECT, true)));
        editPage.addWidget(button(270, 194, 126, 18, "进入审核",
                this::openReview));
    }

    private void buildReviewPage() {
        reviewPage.setBackground(GuiTextures.BACKGROUND_INVERSE);
        reviewPage.addWidget(new LabelWidget(12, 8, "3 差异审核"));
        reviewLabel = new LabelWidget(12, 30, this::reviewText);
        reviewPage.addWidget(reviewLabel);
        reviewPage.addWidget(new ImageWidget(12, 74, 384, 2, GuiTextures.DISPLAY));
        reviewPage.addWidget(new LabelWidget(12, 86,
                "提交后：重建配方表 · 清除反查索引 · 广播 RecipeSyncPacket"));
        reviewPage.addWidget(button(12, 194, 126, 18, "← 回到编辑",
                () -> setStage(STAGE_EDIT, true)));
        reviewPage.addWidget(button(270, 194, 126, 18, "提交并刷新 JEI",
                this::commit));
    }

    private TextFieldWidget textField(int x, int y, int width, int height,
                                      Supplier<String> getter,
                                      java.util.function.Consumer<String> setter) {
        TextFieldWidget field = new TextFieldWidget(x, y, width, height, getter, setter)
                .setMaxStringLength(MAX_JSON_TEXT)
                .setBackground(GuiTextures.DISPLAY);
        field.setClientSideWidget();
        return field;
    }

    private ButtonWidget button(int x, int y, int width, int height,
                                String text, Runnable action) {
        return new ButtonWidget(x, y, width, height,
                new GuiTextureGroup(GuiTextures.BUTTON, new TextTexture(text)),
                click -> {
                    if (action != null) action.run();
                });
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        long now = System.currentTimeMillis();
        animation.advance(stage, now);
        float progress = animation.progress(now);
        WidgetGroup oldPage = page(fromStage);
        WidgetGroup newPage = page(stage);
        if (animation.moving(now) && oldPage != newPage) {
            oldPage.setVisible(true);
            newPage.setVisible(true);
            oldPage.setSelfPositionX(10 + Math.round((fromStage < stage ? -24 : 24)
                    * progress));
            newPage.setSelfPositionX(10 + Math.round((fromStage < stage ? 24 : -24)
                    * (1.0f - progress)));
        } else {
            selectPage.setVisible(stage == STAGE_SELECT);
            editPage.setVisible(stage == STAGE_EDIT);
            reviewPage.setVisible(stage == STAGE_REVIEW);
            oldPage.setSelfPositionX(10);
            newPage.setSelfPositionX(10);
        }
    }

    private void setStage(int next, boolean animate) {
        int safe = Math.max(STAGE_SELECT, Math.min(STAGE_REVIEW, next));
        fromStage = stage;
        stage = safe;
        if (!animate || fromStage == stage) {
            animation.reset(stage, System.currentTimeMillis());
            selectPage.setVisible(stage == STAGE_SELECT);
            editPage.setVisible(stage == STAGE_EDIT);
            reviewPage.setVisible(stage == STAGE_REVIEW);
            return;
        }
        long now = System.currentTimeMillis();
        animation.reset(fromStage, now);
        animation.advance(stage, now);
        page(fromStage).setVisible(true);
        page(stage).setVisible(true);
    }

    private WidgetGroup page(int value) {
        return value == STAGE_EDIT ? editPage
                : value == STAGE_REVIEW ? reviewPage : selectPage;
    }

    private void query() {
        if (!isClient()) return;
        ShanhaiNetwork.CHANNEL.sendToServer(
                new RecipeEditorQueryPacket(typeFilter, searchText, 0, 24));
    }

    private void requestDetail(ShanhaiRecipeQuery.Card card) {
        if (!isClient()) return;
        selectedCard = card;
        ShanhaiNetwork.CHANNEL.sendToServer(
                new RecipeEditorDetailPacket(card.recipeTypeId(), card.recipeId()));
        setStage(STAGE_EDIT, true);
    }

    private void commit() {
        if (!isClient() || selectedBase == null) return;
        try {
            int duration = Math.max(1, Integer.parseInt(durationText.trim()));
            long eut = Long.parseLong(eutText.trim());
            JsonObject inputs = JsonParser.parseString(inputsText).getAsJsonObject();
            JsonObject outputs = JsonParser.parseString(outputsText).getAsJsonObject();
            ShanhaiRecipeBase edited = new ShanhaiRecipeBase(
                    selectedBase.recipeTypeId(),
                    selectedBase.recipeId(),
                    duration,
                    eut,
                    inputs,
                    outputs,
                    selectedBase.tickInputs(),
                    selectedBase.conditions());
            ShanhaiNetwork.CHANNEL.sendToServer(new RecipeEditorCommitPacket(
                    edited.recipeTypeId(),
                    edited.recipeId(),
                    ShanhaiRecipeFingerprint.of(selectedBase),
                    edited.payloadJson().toString()));
            setStatus("提交中…");
        } catch (RuntimeException invalid) {
            setStatus("输入格式无效：耗时/EU 或 IO JSON 无法解析");
        }
    }

    private void openReview() {
        if (selectedBase != null) setStage(STAGE_REVIEW, true);
    }

    private void refreshCards(ShanhaiRecipeQuery.Result result) {
        cards.clear();
        cards.addAll(result.cards());
        resultList.clearAllWidgets();
        for (int index = 0; index < cards.size(); index++) {
            ShanhaiRecipeQuery.Card card = cards.get(index);
            int y = index * 22;
            resultList.addWidget(button(4, y, 370, 20,
                    compact(card.recipeId()) + "  ·  " + card.duration() + "t  ·  " + card.eut() + " EU/t",
                    () -> requestDetail(card)));
        }
    }

    private void receive(RecipeEditorResultPacket packet) {
        if ("query".equals(packet.message())
                && packet.status() == RecipeEditorResultPacket.Status.SUCCESS) {
            ShanhaiRecipeQuery.Result result = parseResult(packet.payload());
            refreshCards(result);
            setStatus(result.total() == 0 ? "没有命中配方" : "命中 " + result.total() + " 条");
            return;
        }
        if ("detail".equals(packet.message())
                && packet.status() == RecipeEditorResultPacket.Status.SUCCESS) {
            selectedBase = parseBase(packet.payload());
            if (selectedBase != null) {
                durationText = Integer.toString(selectedBase.duration());
                eutText = Long.toString(selectedBase.eut());
                inputsText = selectedBase.inputs().toString();
                outputsText = selectedBase.outputs().toString();
                setStage(STAGE_EDIT, true);
            }
            return;
        }
        setStatus(packet.message());
        if (packet.status() == RecipeEditorResultPacket.Status.SUCCESS
                && packet.message().contains("rebuilt")) {
            setStage(STAGE_SELECT, true);
            query();
        }
    }

    private ShanhaiRecipeQuery.Result parseResult(String payload) {
        try {
            JsonObject root = JsonParser.parseString(payload).getAsJsonObject();
            List<ShanhaiRecipeQuery.Card> result = new ArrayList<>();
            JsonArray values = root.getAsJsonArray("cards");
            for (JsonElement value : values) {
                JsonObject card = value.getAsJsonObject();
                result.add(new ShanhaiRecipeQuery.Card(
                        card.get("recipeTypeId").getAsString(),
                        card.get("recipeId").getAsString(),
                        card.get("duration").getAsInt(),
                        card.get("eut").getAsLong(),
                        card.get("fingerprint").getAsString()));
            }
            int total = root.get("total").getAsInt();
            long revision = root.get("revision").getAsLong();
            return new ShanhaiRecipeQuery.Result(List.copyOf(result), total, revision);
        } catch (RuntimeException invalid) {
            setStatus("配方列表解析失败");
            return new ShanhaiRecipeQuery.Result(List.of(), 0, 0L);
        }
    }

    public static void receiveClientResult(RecipeEditorResultPacket packet) {
        ShanhaiRecipeEditorWidget widget = ACTIVE_CLIENT;
        if (widget != null) widget.receive(packet);
    }

    private ShanhaiRecipeBase parseBase(String payload) {
        try {
            JsonObject json = JsonParser.parseString(payload).getAsJsonObject();
            return new ShanhaiRecipeBase(
                    json.get("recipeTypeId").getAsString(),
                    json.get("recipeId").getAsString(),
                    json.get("duration").getAsInt(),
                    json.get("eut").getAsLong(),
                    json.getAsJsonObject("inputs"),
                    json.getAsJsonObject("outputs"),
                    json.getAsJsonObject("tickInputs"),
                    json.has("conditions")
                            ? json.getAsJsonArray("conditions") : new JsonArray());
        } catch (RuntimeException invalid) {
            setStatus("配方快照解析失败");
            return null;
        }
    }

    private String reviewText() {
        if (selectedBase == null) return "没有待审核草稿";
        return "原始指纹： " + compact(ShanhaiRecipeFingerprint.of(selectedBase))
                + "\n配方类型： " + selectedBase.recipeTypeId()
                + "\n配方 ID： " + selectedBase.recipeId()
                + "\n当前耗时 / EUt： " + durationText + " / " + eutText
                + "\n输入与输出将在服务端以 GTCEu codec 再验证一次。";
    }

    private void setStatus(String text) {
        if (statusLabel != null) statusLabel.setText(text);
        if (editStatusLabel != null) editStatusLabel.setText(text);
    }

    private boolean isClient() {
        return player != null && player.level().isClientSide;
    }

    private static String compact(String value) {
        if (value == null) return "";
        return value.length() <= 64 ? value : value.substring(0, 61) + "...";
    }

    private static String limit(String value) {
        if (value == null) return "";
        return value.substring(0, Math.min(256, value.length()));
    }

    private static String limitJson(String value) {
        if (value == null || value.isBlank()) return "{}";
        return value.substring(0, Math.min(MAX_JSON_TEXT, value.length()));
    }
}
