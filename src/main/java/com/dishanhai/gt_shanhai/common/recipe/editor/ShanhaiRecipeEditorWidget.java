package com.dishanhai.gt_shanhai.common.recipe.editor;

import com.dishanhai.gt_shanhai.network.RecipeEditorCommitPacket;
import com.dishanhai.gt_shanhai.network.RecipeEditorDetailPacket;
import com.dishanhai.gt_shanhai.network.RecipeEditorDraftRequestPacket;
import com.dishanhai.gt_shanhai.network.RecipeEditorMachinePacket;
import com.dishanhai.gt_shanhai.network.RecipeEditorQueryPacket;
import com.dishanhai.gt_shanhai.network.RecipeEditorResultPacket;
import com.google.gson.JsonParser;
import com.dishanhai.gt_shanhai.network.ShanhaiNetwork;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.gregtechceu.gtceu.api.gui.GuiTextures;
import com.lowdragmc.lowdraglib.gui.widget.LabelWidget;
import com.lowdragmc.lowdraglib.gui.widget.SelectorWidget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Root of the recipe modifier. Pages, cards and IO cells live in
 * {@link ShanhaiRecipeEditorPanel}; this class owns the server packets.
 */
public final class ShanhaiRecipeEditorWidget extends WidgetGroup {

    public static final int WIDTH = 520;
    public static final int HEIGHT = 360;
    public static final int STAGE_SELECT = 0;
    public static final int STAGE_EDIT = 1;
    public static final int STAGE_REVIEW = 2;
    static final int QUERY_PAGE_SIZE = 8;

    private static volatile ShanhaiRecipeEditorWidget ACTIVE_CLIENT;
    private static final long DRAFT_SAVE_DELAY_MS = 300L;

    private final Player player;
    final ShanhaiRecipeEditorAnimation animation = new ShanhaiRecipeEditorAnimation();
    private final ShanhaiRecipeEditorPanel panel;
    final List<ShanhaiRecipeQuery.Card> cards = new ArrayList<>();
    final List<String> machineTypes = new ArrayList<>();
    final List<String> machineTypeNames = new ArrayList<>();

    ItemStack machineStack = ItemStack.EMPTY;
    String typeFilter = "";
    String searchText = "";
    String durationText = "1";
    String eutText = "0";
    String statusText = "§7拖入 GT 机器，或按配方 ID 搜索";
    int stage = STAGE_SELECT;
    int fromStage = STAGE_SELECT;
    int inputPage;
    int outputPage;
    int inputItemPage;
    int inputFluidPage;
    int outputItemPage;
    int outputFluidPage;
    int selectedIo = -1;
    String chanceText = "100";
    String countText = "1";
    int queryPage;
    int queryTotal;
    private long draftSaveAt = -1L;
    private boolean draftLoadPending;
    private boolean draftChangedBeforeLoad;
    private boolean restoringDraft;
    ShanhaiRecipeQuery.Card selectedCard;
    ShanhaiRecipeBase selectedBase;
    ShanhaiIoTable ioTable = new ShanhaiIoTable();

    public ShanhaiRecipeEditorWidget(Player player) {
        super(0, 0, WIDTH, HEIGHT);
        this.player = player;
        setId("shanhai_recipe_editor");
        if (player != null && player.level().isClientSide) {
            ACTIVE_CLIENT = this;
            draftLoadPending = true;
        }

        panel = new ShanhaiRecipeEditorPanel(this);
        panel.mountSelector(createTypeSelector());
        addWidget(new LabelWidget(12, 6, "§b§l山海配方修改器"));
        addWidget(new LabelWidget(12, 18, "§7机器映射 → 图形化编辑 → 差异审核"));
        addWidget(panel);
        LabelWidget status = new LabelWidget(12, 346, () -> statusText);
        status.setClientSideWidget();
        addWidget(status);
        setStage(STAGE_SELECT, false);
        if (isClient()) {
            requestDraftLoad();
            query();
        }
    }

    private SelectorWidget createTypeSelector() {
        SelectorWidget selector = new SelectorWidget(4, 30, 496, 18, List.of(), 0xFF1A1A1A)
                .setButtonBackground(GuiTextures.VANILLA_BUTTON)
                .setBackground(GuiTextures.BACKGROUND)
                .setFontColor(0xFF1A1A1A)
                .setMaxCount(8)
                .setCandidatesSupplier(() -> List.copyOf(machineTypeNames))
                .setSupplier(() -> {
                    int index = machineTypes.indexOf(typeFilter);
                    return index >= 0 && index < machineTypeNames.size()
                            ? machineTypeNames.get(index) : "展开选择配方类型";
                })
                .setOnChanged(value -> {
                    int index = machineTypeNames.indexOf(value);
                    if (index >= 0 && index < machineTypes.size()) {
                        typeFilter = machineTypes.get(index);
                        queryPage = 0;
                        scheduleDraftSave();
                        query();
                    }
                });
        selector.setId("shanhai_recipe_editor.recipe_type_selector");
        // Names arrive on the client packet, not the server widget copy.
        selector.setClientSideWidget();
        return selector;
    }

    boolean canEnter(int next) {
        if (next == STAGE_REVIEW) return selectedBase != null;
        if (next == STAGE_EDIT) return selectedCard != null || selectedBase != null;
        return true;
    }

    void setStage(int next, boolean animate) {
        int safe = Math.max(STAGE_SELECT, Math.min(STAGE_REVIEW, next));
        if (!canEnter(safe)) return;
        if (safe == stage) {
            fromStage = safe;
            panel.applyStage(false);
            return;
        }
        fromStage = stage;
        stage = safe;
        panel.applyStage(animate);
        flushDraftSave();
    }

    float marker() {
        float progress = animation.progress(ShanhaiRecipeEditorAnimation.nowMs());
        return fromStage + (stage - fromStage) * progress;
    }

    void setMachine(ItemStack stack) {
        machineStack = stack == null ? ItemStack.EMPTY : stack.copyWithCount(1);
        if (!isClient()) return;
        machineTypes.clear();
        machineTypeNames.clear();
        typeFilter = "";
        queryPage = 0;
        flushDraftSave();
        if (machineStack.isEmpty()) {
            query();
            return;
        }
        ShanhaiNetwork.CHANNEL.sendToServer(new RecipeEditorMachinePacket(machineStack));
        setStatus("§e正在映射机器的配方类型…");
    }

    void query() {
        if (!isClient()) return;
        ShanhaiNetwork.CHANNEL.sendToServer(new RecipeEditorQueryPacket(
                typeFilter, searchText, queryPage, QUERY_PAGE_SIZE));
    }

    void requestDetail(ShanhaiRecipeQuery.Card card) {
        if (!isClient() || card == null) return;
        selectedCard = card;
        flushDraftSave();
        setStatus("§e正在读取配方快照…");
        ShanhaiNetwork.CHANNEL.sendToServer(
                new RecipeEditorDetailPacket(card.recipeTypeId(), card.recipeId()));
        setStage(STAGE_EDIT, true);
    }

    void syncIoDraft() {
        if (selectedBase == null) return;
        setStatus("§aIO 草稿已更新，审核页会带上整张表");
        flushDraftSave();
    }

    void commit() {
        if (!isClient() || selectedBase == null) return;
        try {
            int duration = Math.max(1, Integer.parseInt(durationText.trim()));
            long eut = Long.parseLong(eutText.trim());
            JsonObject inputs = ioTable.json("inputs");
            JsonObject outputs = ioTable.json("outputs");
            ShanhaiRecipeBase edited = new ShanhaiRecipeBase(
                    selectedBase.recipeTypeId(), selectedBase.recipeId(), duration, eut,
                    inputs, outputs, selectedBase.tickInputs(),
                    selectedBase.tickOutputs(), selectedBase.conditions());
            ShanhaiNetwork.CHANNEL.sendToServer(new RecipeEditorCommitPacket(
                    edited.recipeTypeId(), edited.recipeId(),
                    ShanhaiRecipeFingerprint.of(selectedBase), edited.payloadJson().toString()));
            flushDraftSave();
            setStatus("§e提交中：服务端正在重建配方索引…");
        } catch (RuntimeException invalid) {
            setStatus("§c耗时或 EU/t 不是整数");
        }
    }

    void shiftQueryPage(int delta) {
        int pages = pageCount(queryTotal, QUERY_PAGE_SIZE);
        int next = Math.max(0, Math.min(pages - 1, queryPage + delta));
        if (next == queryPage) return;
        queryPage = next;
        flushDraftSave();
        query();
    }

    void shiftInputItemPage(int delta) {
        inputItemPage = shift(inputItemPage, delta, ioTable.itemIn(), ShanhaiRecipeEditorPanel.ITEM_PAGE);
        inputPage = inputItemPage;
        flushDraftSave();
    }

    void shiftInputFluidPage(int delta) {
        inputFluidPage = shift(inputFluidPage, delta, ioTable.fluidIn(), ShanhaiRecipeEditorPanel.FLUID_PAGE);
        flushDraftSave();
    }

    void shiftOutputItemPage(int delta) {
        outputItemPage = shift(outputItemPage, delta, ioTable.itemOut(), ShanhaiRecipeEditorPanel.ITEM_PAGE);
        outputPage = outputItemPage;
        flushDraftSave();
    }

    void shiftOutputFluidPage(int delta) {
        outputFluidPage = shift(outputFluidPage, delta, ioTable.fluidOut(), ShanhaiRecipeEditorPanel.FLUID_PAGE);
        flushDraftSave();
    }

    void growInput() {
        ioTable.resize(
                growTo(ioTable.itemIn(), ShanhaiRecipeEditorPanel.ITEM_PAGE),
                growTo(ioTable.fluidIn(), ShanhaiRecipeEditorPanel.FLUID_PAGE),
                ioTable.itemOut(), ioTable.fluidOut());
        inputItemPage = pageCount(ioTable.itemIn(), ShanhaiRecipeEditorPanel.ITEM_PAGE) - 1;
        inputFluidPage = pageCount(ioTable.fluidIn(), ShanhaiRecipeEditorPanel.FLUID_PAGE) - 1;
        inputPage = inputItemPage;
        selectedIo = -1;
        syncIoDraft();
    }

    void growOutput() {
        ioTable.resize(
                ioTable.itemIn(), ioTable.fluidIn(),
                growTo(ioTable.itemOut(), ShanhaiRecipeEditorPanel.ITEM_PAGE),
                growTo(ioTable.fluidOut(), ShanhaiRecipeEditorPanel.FLUID_PAGE));
        outputItemPage = pageCount(ioTable.itemOut(), ShanhaiRecipeEditorPanel.ITEM_PAGE) - 1;
        outputFluidPage = pageCount(ioTable.fluidOut(), ShanhaiRecipeEditorPanel.FLUID_PAGE) - 1;
        outputPage = outputItemPage;
        selectedIo = -1;
        syncIoDraft();
    }

    void selectIo(int index) {
        ShanhaiIoTable.Cell cell = ioTable.cell(index);
        if (cell == null) return;
        selectedIo = index;
        int max = Math.max(1, cell.maxChance);
        chanceText = Integer.toString(cell.chance * 100 / max);
        countText = Integer.toString(Math.max(0, cell.shownCount()));
    }

    void markNotConsumed() {
        ShanhaiIoTable.Cell cell = selectedCell();
        if (cell == null) return;
        cell.chance = 0;
        cell.dirty = true;
        chanceText = "0";
        syncIoDraft();
    }

    void markChanceOutput() {
        ShanhaiIoTable.Cell cell = selectedCell();
        if (cell == null) return;
        int percent = parsePercent(chanceText);
        if (percent < 0) {
            setStatus("§c概率要写成 0 到 100");
            return;
        }
        int max = cell.maxChance <= 0 ? 10000 : cell.maxChance;
        cell.maxChance = max;
        cell.chance = percent * max / 100;
        cell.dirty = true;
        chanceText = Integer.toString(percent);
        syncIoDraft();
    }

    void markCertain() {
        ShanhaiIoTable.Cell cell = selectedCell();
        if (cell == null) return;
        if (cell.maxChance <= 0) cell.maxChance = 10000;
        cell.chance = cell.maxChance;
        cell.dirty = true;
        chanceText = "100";
        syncIoDraft();
    }

    void applyCountText() {
        ShanhaiIoTable.Cell cell = selectedCell();
        if (cell == null) return;
        int count;
        try {
            count = Integer.parseInt(countText == null ? "" : countText.trim());
        } catch (NumberFormatException invalid) {
            setStatus("§c数量要写成正整数");
            return;
        }
        if (count < 1) {
            setStatus("§c数量要写成正整数");
            return;
        }
        if (cell.itemKind) {
            if (cell.item.isEmpty()) {
                setStatus("§c先放入物品");
                return;
            }
            cell.setItem(cell.item, count);
        } else {
            if (cell.fluid.isEmpty()) {
                setStatus("§c先放入流体");
                return;
            }
            cell.setFluid(cell.fluid.copy(count));
        }
        syncIoDraft();
    }

    String traitLine() {
        ShanhaiIoTable.Cell cell = ioTable.cell(selectedIo);
        if (cell == null) return "§8点一格，再设不消耗或概率";
        String side = selectedIo < ioTable.inSection() ? "输入" : "输出";
        String kind = cell.itemKind ? "物品" : "流体";
        String mode = cell.chance <= 0 ? "不消耗" : (cell.chance >= cell.maxChance ? "必定" : "概率");
        return "§7" + side + kind + " §f" + mode + " §7" + chanceText + "%  数量 " + countText;
    }

    private ShanhaiIoTable.Cell selectedCell() {
        ShanhaiIoTable.Cell cell = ioTable.cell(selectedIo);
        if (cell == null || cell.empty()) {
            setStatus("§c先点中一个已经放入东西的格子");
            return null;
        }
        return cell;
    }

    private static int growTo(int current, int page) {
        int size = Math.max(1, page);
        int aligned = Math.max(1, (Math.max(0, current) + size - 1) / size) * size;
        return current < aligned ? aligned : aligned + size;
    }

    private static int parsePercent(String text) {
        try {
            int percent = Integer.parseInt(text == null ? "" : text.trim());
            return percent < 0 || percent > 100 ? -1 : percent;
        } catch (NumberFormatException invalid) {
            return -1;
        }
    }

    String pageLabel(String name, int page, int section, int pageSize) {
        return name + " " + (page + 1) + "/" + pageCount(section, pageSize);
    }

    int pageCount(int total, int pageSize) {
        int size = Math.max(1, pageSize);
        return Math.max(1, (Math.max(0, total) + size - 1) / size);
    }

    String conditionLine() {
        if (selectedBase == null) return "§7条件：—";
        int count = selectedBase.conditions().size();
        return count == 0
                ? "§7条件：无，提交时仍原样保留"
                : "§d条件：" + count + " 条，提交时原样保留";
    }

    String fingerprintLine() {
        if (selectedBase == null) return "§7指纹：—";
        return "§7打开时指纹 §f" + compact(ShanhaiRecipeFingerprint.of(selectedBase), 28);
    }

    String ioLine() {
        if (selectedBase == null) return "";
        return "§7IO §a" + ioTable.inSection() + " 入 §d" + ioTable.outSection() + " 出";
    }

    String durationDiffLine() {
        if (selectedBase == null) return "§7耗时：—";
        String before = Integer.toString(selectedBase.duration());
        String after = durationText == null ? "" : durationText.trim();
        if (before.equals(after)) return "§7耗时 §f" + before + " §8未改";
        return "§7耗时 §6" + before + " §f→ §a" + after;
    }

    String eutDiffLine() {
        if (selectedBase == null) return "§7EU/t：—";
        String before = Long.toString(selectedBase.eut());
        String after = eutText == null ? "" : eutText.trim();
        if (before.equals(after)) return "§7EU/t §f" + before + " §8未改";
        return "§7EU/t §6" + before + " §f→ §a" + after;
    }

    private int shift(int page, int delta, int section, int pageSize) {
        int pages = pageCount(section, pageSize);
        return Math.max(0, Math.min(pages - 1, page + delta));
    }

    private void receive(RecipeEditorResultPacket packet) {
        if ("machine".equals(packet.message())) {
            if (packet.status() == RecipeEditorResultPacket.Status.SUCCESS) parseMachine(packet.payload());
            else setStatus("§c这不是可映射的 GT 机器");
            return;
        }
        if ("query".equals(packet.message())
                && packet.status() == RecipeEditorResultPacket.Status.SUCCESS) {
            ShanhaiRecipeQuery.Result result = parseResult(packet.payload());
            cards.clear();
            cards.addAll(result.cards());
            queryTotal = result.total();
            panel.refreshCards(cards);
            setStatus(result.total() == 0 ? "§c没有命中配方" : "§a命中 " + result.total() + " 条");
            return;
        }
        if ("detail".equals(packet.message())
                && packet.status() == RecipeEditorResultPacket.Status.SUCCESS) {
            selectedBase = parseBase(packet.payload());
            if (selectedBase != null) {
                durationText = Integer.toString(selectedBase.duration());
                eutText = Long.toString(selectedBase.eut());
                inputPage = 0;
                outputPage = 0;
                inputItemPage = 0;
                inputFluidPage = 0;
                outputItemPage = 0;
                outputFluidPage = 0;
                selectedIo = -1;
                ioTable = ShanhaiIoTable.fromJson(selectedBase.inputs(), selectedBase.outputs());
                panel.bindTable(ioTable);
                setStatus("§a已载入 " + compact(selectedBase.recipeId(), 48));
            }
            return;
        }
        setStatus(statusLine(packet));
        if (packet.status() == RecipeEditorResultPacket.Status.SUCCESS
                && packet.message() != null && packet.message().contains("rebuilt")) {
            ShanhaiNetwork.CHANNEL.sendToServer(new RecipeEditorDraftRequestPacket(
                    RecipeEditorDraftRequestPacket.Action.CLEAR, null));
            selectedBase = null;
            selectedCard = null;
            setStage(STAGE_SELECT, true);
            query();
        }
    }

    private void parseMachine(String payload) {
        try {
            JsonObject root = JsonParser.parseString(payload).getAsJsonObject();
            machineTypes.clear();
            machineTypeNames.clear();
            JsonArray values = root.getAsJsonArray("types");
            for (JsonElement element : values) {
                JsonObject value = element.getAsJsonObject();
                machineTypes.add(value.get("id").getAsString());
                String label = value.get("label").getAsString();
                int count = value.has("count") ? value.get("count").getAsInt() : 0;
                machineTypeNames.add(label + " · " + count + " 条");
            }
            if (!machineTypes.isEmpty()) {
                if (!machineTypes.contains(typeFilter)) typeFilter = machineTypes.get(0);
                queryPage = 0;
                query();
            } else {
                setStatus("§c这台机器没有配方类型");
            }
        } catch (RuntimeException invalid) {
            setStatus("§c机器映射数据解析失败");
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
                        card.get("fingerprint").getAsString(),
                        card.has("iconKind") ? card.get("iconKind").getAsString() : "",
                        card.has("iconId") ? card.get("iconId").getAsString() : ""));
            }
            return new ShanhaiRecipeQuery.Result(List.copyOf(result),
                    root.get("total").getAsInt(), root.get("revision").getAsLong());
        } catch (RuntimeException invalid) {
            setStatus("§c配方列表解析失败");
            return new ShanhaiRecipeQuery.Result(List.of(), 0, 0L);
        }
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
                    json.has("tickOutputs")
                            ? json.getAsJsonObject("tickOutputs") : new JsonObject(),
                    json.has("conditions") ? json.getAsJsonArray("conditions") : new JsonArray());
        } catch (RuntimeException invalid) {
            setStatus("§c配方快照解析失败");
            return null;
        }
    }

    private static String statusLine(RecipeEditorResultPacket packet) {
        String message = packet.message() == null ? "" : packet.message();
        return switch (packet.status()) {
            case SUCCESS -> "§a" + message;
            case CONFLICT -> "§c指纹冲突：" + message;
            case PERMISSION_DENIED -> "§c没有权限：" + message;
            case REBUILD_FAILED -> "§c重建失败：" + message;
            case VALIDATION_ERROR -> "§c校验失败：" + message;
        };
    }

    private void setStatus(String text) {
        statusText = text == null ? "" : text;
    }

    private boolean isClient() {
        return player != null && player.level().isClientSide;
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        if (draftSaveAt > 0L && System.currentTimeMillis() >= draftSaveAt) {
            flushDraftSave();
        }
    }

    void scheduleDraftSave() {
        if (!isClient() || restoringDraft) return;
        if (draftLoadPending) {
            draftChangedBeforeLoad = true;
            return;
        }
        draftSaveAt = System.currentTimeMillis() + DRAFT_SAVE_DELAY_MS;
    }

    void flushDraftSave() {
        if (!isClient() || restoringDraft) return;
        if (draftLoadPending) {
            draftChangedBeforeLoad = true;
            return;
        }
        draftSaveAt = -1L;
        ShanhaiNetwork.CHANNEL.sendToServer(new RecipeEditorDraftRequestPacket(
                RecipeEditorDraftRequestPacket.Action.SAVE, createDraftSnapshot()));
    }

    private void requestDraftLoad() {
        ShanhaiNetwork.CHANNEL.sendToServer(new RecipeEditorDraftRequestPacket(
                RecipeEditorDraftRequestPacket.Action.LOAD, null));
    }

    private CompoundTag createDraftSnapshot() {
        CompoundTag draft = new CompoundTag();
        draft.putInt("version", 1);
        draft.putInt("stage", stage);
        draft.putString("typeFilter", typeFilter);
        draft.putString("searchText", searchText);
        draft.putInt("queryPage", queryPage);
        draft.putInt("inputPage", inputItemPage);
        draft.putInt("outputPage", outputItemPage);
        draft.putInt("inputItemPage", inputItemPage);
        draft.putInt("inputFluidPage", inputFluidPage);
        draft.putInt("outputItemPage", outputItemPage);
        draft.putInt("outputFluidPage", outputFluidPage);
        draft.putInt("itemIn", ioTable.itemIn());
        draft.putInt("fluidIn", ioTable.fluidIn());
        draft.putInt("itemOut", ioTable.itemOut());
        draft.putInt("fluidOut", ioTable.fluidOut());
        if (!machineStack.isEmpty()) draft.put("machine", machineStack.save(new CompoundTag()));
        if (selectedCard != null) {
            draft.putString("recipeTypeId", selectedCard.recipeTypeId());
            draft.putString("recipeId", selectedCard.recipeId());
            draft.putInt("cardDuration", selectedCard.duration());
            draft.putLong("cardEut", selectedCard.eut());
            draft.putString("cardFingerprint", selectedCard.fingerprint());
        }
        if (selectedBase != null) {
            draft.putString("basePayload", selectedBase.payloadJson().toString());
            draft.putString("baseFingerprint", ShanhaiRecipeFingerprint.of(selectedBase));
            draft.putString("durationText", durationText);
            draft.putString("eutText", eutText);
            draft.putString("draftInputs", editableTable(selectedBase.inputs(), ioTable.json("inputs")).toString());
            draft.putString("draftOutputs", editableTable(selectedBase.outputs(), ioTable.json("outputs")).toString());
        }
        return draft;
    }

    private static JsonObject editableTable(JsonObject base, JsonObject edited) {
        JsonObject result = base == null ? new JsonObject() : base.deepCopy();
        for (String key : List.of("item", "fluid")) {
            if (edited != null && edited.has(key)) result.add(key, edited.get(key).deepCopy());
            else result.remove(key);
        }
        return result;
    }

    private void restoreDraft(CompoundTag draft) {
        draftLoadPending = false;
        if (draftChangedBeforeLoad) {
            draftChangedBeforeLoad = false;
            flushDraftSave();
            return;
        }
        if (draft == null || draft.isEmpty()) return;

        restoringDraft = true;
        try {
            machineStack = draft.contains("machine", CompoundTag.TAG_COMPOUND)
                    ? ItemStack.of(draft.getCompound("machine")) : ItemStack.EMPTY;
            typeFilter = draft.getString("typeFilter");
            searchText = draft.getString("searchText");
            queryPage = Math.max(0, draft.getInt("queryPage"));
            inputItemPage = Math.max(0, draft.contains("inputItemPage")
                    ? draft.getInt("inputItemPage") : draft.getInt("inputPage"));
            inputFluidPage = Math.max(0, draft.getInt("inputFluidPage"));
            outputItemPage = Math.max(0, draft.contains("outputItemPage")
                    ? draft.getInt("outputItemPage") : draft.getInt("outputPage"));
            outputFluidPage = Math.max(0, draft.getInt("outputFluidPage"));
            inputPage = inputItemPage;
            outputPage = outputItemPage;
            selectedBase = draft.contains("basePayload")
                    ? parseBase(draft.getString("basePayload")) : null;
            if (draft.contains("recipeId") && draft.contains("recipeTypeId")) {
                selectedCard = new ShanhaiRecipeQuery.Card(
                        draft.getString("recipeTypeId"),
                        draft.getString("recipeId"),
                        draft.getInt("cardDuration"),
                        draft.getLong("cardEut"),
                        draft.getString("cardFingerprint"));
            }
            if (selectedBase != null) {
                durationText = draft.contains("durationText")
                        ? draft.getString("durationText") : Integer.toString(selectedBase.duration());
                eutText = draft.contains("eutText")
                        ? draft.getString("eutText") : Long.toString(selectedBase.eut());
                JsonObject inputs = draft.contains("draftInputs")
                        ? JsonParser.parseString(draft.getString("draftInputs")).getAsJsonObject()
                        : selectedBase.inputs();
                JsonObject outputs = draft.contains("draftOutputs")
                        ? JsonParser.parseString(draft.getString("draftOutputs")).getAsJsonObject()
                        : selectedBase.outputs();
                ioTable = ShanhaiIoTable.fromJson(inputs, outputs);
                if (draft.contains("itemIn")) {
                    ioTable.resize(draft.getInt("itemIn"), draft.getInt("fluidIn"),
                            draft.getInt("itemOut"), draft.getInt("fluidOut"));
                }
                panel.bindTable(ioTable);
                String fingerprint = draft.getString("baseFingerprint");
                if (!fingerprint.isEmpty()) {
                    selectedCard = new ShanhaiRecipeQuery.Card(
                            selectedBase.recipeTypeId(), selectedBase.recipeId(),
                            selectedBase.duration(), selectedBase.eut(), fingerprint);
                }
            }
            int restoredStage = Math.max(STAGE_SELECT, Math.min(STAGE_REVIEW, draft.getInt("stage")));
            if (restoredStage != STAGE_SELECT && selectedBase == null && selectedCard == null) {
                restoredStage = STAGE_SELECT;
            }
            stage = restoredStage;
            fromStage = restoredStage;
            panel.applyStage(false);
            if (!machineStack.isEmpty()) {
                ShanhaiNetwork.CHANNEL.sendToServer(new RecipeEditorMachinePacket(machineStack));
            }
            query();
            if (selectedBase == null && selectedCard != null) {
                ShanhaiNetwork.CHANNEL.sendToServer(
                        new RecipeEditorDetailPacket(selectedCard.recipeTypeId(), selectedCard.recipeId()));
            }
            setStatus(selectedBase == null && selectedCard == null
                    ? "§7草稿已恢复" : "§a已恢复未提交的配方编辑进度");
        } catch (RuntimeException invalid) {
            setStatus("§c编辑草稿无法解析，已回到查询页");
            stage = STAGE_SELECT;
            fromStage = STAGE_SELECT;
            panel.applyStage(false);
        } finally {
            restoringDraft = false;
        }
    }

    public static void receiveDraft(CompoundTag draft) {
        ShanhaiRecipeEditorWidget widget = ACTIVE_CLIENT;
        if (widget != null) widget.restoreDraft(draft == null ? new CompoundTag() : draft);
    }

    static String compact(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, Math.max(0, max - 3)) + "...";
    }

    static String limit(String value) {
        if (value == null) return "";
        return value.substring(0, Math.min(256, value.length()));
    }

    public static void receiveClientResult(RecipeEditorResultPacket packet) {
        ShanhaiRecipeEditorWidget widget = ACTIVE_CLIENT;
        if (widget != null) widget.receive(packet);
    }
}
