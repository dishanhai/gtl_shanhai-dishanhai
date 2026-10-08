package com.dishanhai.gt_shanhai.common.recipe.editor;

import com.dishanhai.gt_shanhai.client.recipe.ShanhaiRecipeClipboard;
import com.dishanhai.gt_shanhai.network.RecipeEditorCommitPacket;
import com.dishanhai.gt_shanhai.network.RecipeEditorDetailPacket;
import com.dishanhai.gt_shanhai.network.RecipeEditorEncodePacket;
import com.dishanhai.gt_shanhai.network.RecipeEditorExportPacket;
import com.dishanhai.gt_shanhai.network.RecipeEditorDraftRequestPacket;
import com.dishanhai.gt_shanhai.network.RecipeEditorMachinePacket;
import com.dishanhai.gt_shanhai.network.RecipeEditorQueryPacket;
import com.dishanhai.gt_shanhai.network.RecipeEditorResultPacket;
import com.google.gson.JsonParser;
import com.dishanhai.gt_shanhai.network.ShanhaiNetwork;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.gregtechceu.gtceu.api.gui.GuiTextures;
import com.lowdragmc.lowdraglib.gui.widget.LabelWidget;
import com.lowdragmc.lowdraglib.gui.widget.SelectorWidget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
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
    /** JEI crops 12px of padding and a 20px config button off the right strip. */
    /** Right-hand column shared by JEI bookmarks (top) and the search list (below). */
    public static final int JEI_SIDE_WIDTH = 140;
    public static final int STAGE_SELECT = 0;
    public static final int STAGE_EDIT = 1;
    public static final int STAGE_REVIEW = 2;
    static final int QUERY_PAGE_SIZE = 8;
    private static final int QUERY_HISTORY_LIMIT = 16;

    private static volatile ShanhaiRecipeEditorWidget ACTIVE_CLIENT;
    private static final long DRAFT_SAVE_DELAY_MS = 300L;

    private final Player player;
    final ShanhaiRecipeEditorAnimation animation = new ShanhaiRecipeEditorAnimation();
    private final ShanhaiRecipeEditorPanel panel;
    final List<ShanhaiRecipeQuery.Card> cards = new ArrayList<>();
    final List<String> machineTypes = new ArrayList<>();
    final List<String> machineTypeNames = new ArrayList<>();
    int mappedTypeCount;
    int ownedTypeCount;

    ItemStack machineStack = ItemStack.EMPTY;
    String typeFilter = "";
    String searchText = "";
    String durationText = "1";
    String eutText = "0";
    String recipeIdText = "";
    boolean keepOriginal;
    /** Blank draft. Commit adds a recipe and does not replace or remove one. */
    boolean creating;
    String conditionPick = "";
    JsonElement conditionDraft;
    int editingCondition = -1;
    JsonArray conditionEdits = new JsonArray();
    String statusText = "§7拖入 GT 机器，或按配方、原料、输出搜索";
    int stage = STAGE_SELECT;
    int fromStage = STAGE_SELECT;
    int inputPage;
    int outputPage;
    int inputItemPage;
    int inputFluidPage;
    int outputItemPage;
    int outputFluidPage;
    int selectedIo = -1;
    long selectedAt = ShanhaiRecipeEditorAnimation.nowMs();
    String chanceText = "100";
    String countText = "1";
    /** Recipe data ebf_temp from the detail snapshot. -1 means absent. */
    int jsBlastTemp = -1;
    int queryPage;
    int queryTotal;
    String sortKey = "id";
    boolean sortDesc;
    ShanhaiRecipeQuery.SearchMode searchMode = ShanhaiRecipeQuery.SearchMode.RECIPE_ID;
    ShanhaiRecipeQuery.IngredientKind ingredientKind = ShanhaiRecipeQuery.IngredientKind.ITEM;
    private long draftSaveAt = -1L;
    /** Set only for the query that an upward overscroll is about to send. */
    private boolean armEndOnNextQuery;
    private final List<QueryMemory> queryHistory = new ArrayList<>();
    private boolean draftLoadPending;
    private boolean draftChangedBeforeLoad;
    private boolean restoringDraft;
    ShanhaiRecipeQuery.Card selectedCard;
    ShanhaiRecipeBase selectedBase;
    ShanhaiIoTable ioTable = new ShanhaiIoTable();
    private final List<EditSnap> editHistory = new ArrayList<>();
    private String lastEditKind = "";

    /**
     * Left edge that keeps a {@link #JEI_SIDE_WIDTH} column on the right.
     * Bookmarks and the search list both use that column.
     */
    public static int jeiLeft(int screenWidth, int guiWidth) {
        if (screenWidth <= 0) return 0;
        int centered = Math.max(0, (screenWidth - guiWidth) / 2);
        if (screenWidth - centered - guiWidth >= JEI_SIDE_WIDTH) return centered;
        int left = screenWidth - guiWidth - JEI_SIDE_WIDTH;
        if (left < 2) left = 2;
        int maxLeft = Math.max(0, screenWidth - guiWidth);
        return Math.min(left, maxLeft);
    }

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
        LabelWidget status = new LabelWidget(12, 336, () -> statusText);
        status.setClientSideWidget();
        addWidget(status);
        setStage(STAGE_SELECT, false);
        if (isClient()) {
            requestDraftLoad();
            ShanhaiNetwork.CHANNEL.sendToServer(new RecipeEditorMachinePacket(ItemStack.EMPTY));
            query();
        }
    }

    private SelectorWidget createTypeSelector() {
        SelectorWidget selector = new ShanhaiRecipeTypeSelector(4, 30, 496, 18, List.of(), 0xFF1A1A1A)
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
            mappedTypeCount = 0;
            ownedTypeCount = 0;
            ShanhaiNetwork.CHANNEL.sendToServer(new RecipeEditorMachinePacket(ItemStack.EMPTY));
            query();
            return;
        }
        ShanhaiNetwork.CHANNEL.sendToServer(new RecipeEditorMachinePacket(machineStack));
        setStatus("§e正在映射机器的配方类型…");
    }

    void query() {
        if (!isClient()) return;
        if (searchMode != ShanhaiRecipeQuery.SearchMode.RECIPE_ID) {
            String id = searchText == null ? "" : searchText.trim();
            if (id.isEmpty()) {
                cards.clear();
                queryTotal = 0;
                panel.refreshCards(cards, List.of());
                setStatus("§7反查请拖入物品或流体，或填写注册 ID");
                return;
            }
            if (net.minecraft.resources.ResourceLocation.tryParse(id) == null) {
                setStatus("§c反查需要完整注册 ID");
                return;
            }
        }
        if (armEndOnNextQuery) panel.landQueryAtEnd(queryPage);
        rememberQuery();
        ShanhaiNetwork.CHANNEL.sendToServer(new RecipeEditorQueryPacket(
                typeFilter, searchText, queryPage, QUERY_PAGE_SIZE, sortWire(),
                searchMode, ingredientKind));
    }

    /** Keeps a non-empty search. The same query moves to the front instead of repeating. */
    private void rememberQuery() {
        if (!isClient() || restoringDraft) return;
        String text = limit(searchText).trim();
        String type = limit(typeFilter).trim();
        if (text.isEmpty() && type.isEmpty()) return;
        QueryMemory next = new QueryMemory(type, text, searchMode, ingredientKind);
        if (!queryHistory.isEmpty() && queryHistory.get(0).same(next)) return;
        queryHistory.removeIf(old -> old.same(next));
        queryHistory.add(0, next);
        while (queryHistory.size() > QUERY_HISTORY_LIMIT) {
            queryHistory.remove(queryHistory.size() - 1);
        }
        scheduleDraftSave();
    }

    int queryHistoryCount() {
        return queryHistory.size();
    }

    String queryHistoryLabel(int index) {
        if (index < 0 || index >= queryHistory.size()) return "";
        QueryMemory memory = queryHistory.get(index);
        String mode = switch (memory.mode) {
            case INGREDIENT -> "原料";
            case OUTPUT -> "输出";
            default -> "配方";
        };
        StringBuilder line = new StringBuilder(mode);
        if (memory.mode != ShanhaiRecipeQuery.SearchMode.RECIPE_ID) {
            line.append(" · ").append(memory.kind == ShanhaiRecipeQuery.IngredientKind.FLUID ? "流体" : "物品");
        }
        if (!memory.type.isEmpty()) line.append(" · ").append(memory.type);
        if (!memory.text.isEmpty()) line.append("  ").append(memory.text);
        return line.toString();
    }

    void applyQueryHistory(int index) {
        if (index < 0 || index >= queryHistory.size()) return;
        QueryMemory memory = queryHistory.get(index);
        typeFilter = memory.type;
        searchText = memory.text;
        searchMode = memory.mode;
        ingredientKind = memory.kind;
        queryPage = 0;
        if (index > 0) {
            queryHistory.remove(index);
            queryHistory.add(0, memory);
        }
        panel.clearQuerySlot();
        panel.closeQueryHistory();
        flushDraftSave();
        query();
    }

    /** Wheel past the card list. Negative delta is the previous query page. */
    void overscrollQueryPage(int delta) {
        if (delta < 0) armEndOnNextQuery = true;
        shiftQueryPage(delta);
        armEndOnNextQuery = false;
    }

    void acceptQueryDrop(String id, boolean fluid) {
        if (id == null || id.isEmpty()) {
            searchText = "";
            queryPage = 0;
            flushDraftSave();
            query();
            return;
        }
        searchText = id;
        ingredientKind = fluid
                ? ShanhaiRecipeQuery.IngredientKind.FLUID
                : ShanhaiRecipeQuery.IngredientKind.ITEM;
        if (searchMode == ShanhaiRecipeQuery.SearchMode.RECIPE_ID) {
            searchMode = ShanhaiRecipeQuery.SearchMode.INGREDIENT;
        }
        queryPage = 0;
        flushDraftSave();
        query();
    }

    void setSearchMode(ShanhaiRecipeQuery.SearchMode mode) {
        ShanhaiRecipeQuery.SearchMode next = mode == null
                ? ShanhaiRecipeQuery.SearchMode.RECIPE_ID : mode;
        if (next == searchMode) return;
        searchMode = next;
        queryPage = 0;
        query();
    }

    void setIngredientKind(ShanhaiRecipeQuery.IngredientKind kind) {
        ShanhaiRecipeQuery.IngredientKind next = kind == null
                ? ShanhaiRecipeQuery.IngredientKind.ITEM : kind;
        if (next == ingredientKind) return;
        ingredientKind = next;
        if (searchMode != ShanhaiRecipeQuery.SearchMode.RECIPE_ID) {
            queryPage = 0;
            query();
        }
    }

    String modeFace(ShanhaiRecipeQuery.SearchMode mode, String plain) {
        return searchMode == mode ? "【" + plain + "】" : plain;
    }

    String kindFace(ShanhaiRecipeQuery.IngredientKind kind, String plain) {
        if (searchMode == ShanhaiRecipeQuery.SearchMode.RECIPE_ID || ingredientKind != kind) return plain;
        return "【" + plain + "】";
    }

    String searchHint() {
        boolean fluid = ingredientKind == ShanhaiRecipeQuery.IngredientKind.FLUID;
        return switch (searchMode) {
            case INGREDIENT -> fluid ? "§7原料反查 · 流体 ID" : "§7原料反查 · 物品 ID";
            case OUTPUT -> fluid ? "§7输出反查 · 流体 ID" : "§7输出反查 · 物品 ID";
            default -> "§7配方 ID 包含搜索";
        };
    }

    void setSort(String key) {
        String next = key == null || key.isEmpty() ? "id" : key;
        if (next.equals(sortKey)) {
            sortDesc = !sortDesc;
        } else {
            sortKey = next;
            sortDesc = !"id".equals(next);
        }
        queryPage = 0;
        query();
    }

    String sortWire() {
        return sortKey + (sortDesc ? ":desc" : ":asc");
    }

    String sortFace(String key, String plain) {
        return key != null && key.equals(sortKey) ? sortLabel() : plain;
    }

    String sortLabel() {
        return switch (sortKey) {
            case "eut" -> sortDesc ? "EU↓" : "EU↑";
            case "duration" -> sortDesc ? "耗时↓" : "耗时↑";
            case "inputs" -> sortDesc ? "输入↓" : "输入↑";
            case "outputs" -> sortDesc ? "输出↓" : "输出↑";
            default -> sortDesc ? "Z-A" : "A-Z";
        };
    }

    void beginCreate() {
        if (!isClient()) return;
        String type = typeFilter == null ? "" : typeFilter.trim();
        if (net.minecraft.resources.ResourceLocation.tryParse(type) == null) {
            setStatus("§c先选择配方类型，再添加配方");
            return;
        }
        creating = true;
        keepOriginal = true;
        selectedCard = null;
        jsBlastTemp = -1;
        selectedBase = new ShanhaiRecipeBase(
                type, type, 100, 0L, new JsonObject(), new JsonObject(), new JsonObject(), new JsonArray());
        durationText = "100";
        eutText = "0";
        recipeIdText = "";
        conditionPick = "";
        conditionDraft = null;
        editingCondition = -1;
        conditionEdits = new JsonArray();
        panel.refreshConditions();
        panel.refreshParameters();
        inputPage = 0;
        outputPage = 0;
        inputItemPage = 0;
        inputFluidPage = 0;
        outputItemPage = 0;
        outputFluidPage = 0;
        selectedIo = -1;
        chanceText = "100";
        countText = "1";
        ioTable = ShanhaiIoTable.fromJson(new JsonObject(), new JsonObject());
        panel.bindTable(ioTable);
        panel.closeQueryHistory();
        seedEditHistory();
        setStage(STAGE_EDIT, true);
        setStatus("§a新建配方：填写 命名空间:路径，再编辑输入输出。不会改动已有配方");
    }

    void requestDetail(ShanhaiRecipeQuery.Card card) {
        if (!isClient() || card == null) return;
        creating = false;
        keepOriginal = false;
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
            if (creating) {
                net.minecraft.resources.ResourceLocation createdId =
                        RecipeEditorExportPacket.exportLocation(recipeIdText, "");
                if (createdId == null) {
                    setStatus("§c填写新配方 id，使用小写的 命名空间:路径");
                    return;
                }
                ShanhaiRecipeBase created = new ShanhaiRecipeBase(
                        selectedBase.recipeTypeId(), createdId.toString(), duration, eut,
                        inputs, outputs, new JsonObject(), new JsonObject(), conditionEdits);
                JsonObject payload = payloadWithConditionNote(created);
                payload.addProperty("createNew", true);
                payload.addProperty("liveRecipeId", createdId.toString());
                ShanhaiNetwork.CHANNEL.sendToServer(new RecipeEditorCommitPacket(
                        created.recipeTypeId(), created.recipeId(),
                        ShanhaiRecipeFingerprint.of(created), payload.toString()));
                flushDraftSave();
                setStatus("§e提交中：正在新增配方并重建索引…");
                return;
            }
            String liveRaw = recipeIdText == null || recipeIdText.isBlank()
                    ? selectedBase.recipeId() : recipeIdText.trim();
            if (RecipeEditorExportPacket.exportLocation(liveRaw, selectedBase.recipeId()) == null) {
                setStatus("§c配方 id 不合法，使用小写的 命名空间:路径");
                return;
            }
            ShanhaiRecipeBase edited = new ShanhaiRecipeBase(
                    selectedBase.recipeTypeId(), selectedBase.recipeId(), duration, eut,
                    inputs, outputs, selectedBase.tickInputs(),
                    selectedBase.tickOutputs(), conditionEdits);
            JsonObject payload = payloadWithConditionNote(edited);
            payload.addProperty("keepOriginal", keepOriginal);
            payload.addProperty("liveRecipeId",
                    RecipeEditorExportPacket.exportLocation(liveRaw, selectedBase.recipeId()).toString());
            ShanhaiNetwork.CHANNEL.sendToServer(new RecipeEditorCommitPacket(
                    edited.recipeTypeId(), edited.recipeId(),
                    ShanhaiRecipeFingerprint.of(selectedBase), payload.toString()));
            flushDraftSave();
            setStatus("§e提交中：服务端正在重建配方索引…");
        } catch (RuntimeException invalid) {
            setStatus("§c耗时或 EU/t 不是整数");
        }
    }

    void copyJsArray() {
        if (!isClient() || selectedBase == null) return;
        try {
            int duration = Math.max(1, Integer.parseInt(durationText.trim()));
            long eut = Long.parseLong(eutText.trim());
            net.minecraft.resources.ResourceLocation exportId = creating
                    ? RecipeEditorExportPacket.exportLocation(recipeIdText, "")
                    : RecipeEditorExportPacket.exportLocation(recipeIdText, selectedBase.recipeId());
            if (exportId == null) {
                setStatus(creating
                        ? "§c填写新配方 id，使用小写的 命名空间:路径"
                        : "§c配方 id 不合法，使用小写的 命名空间:路径");
                return;
            }
            ShanhaiRecipeClipboard.copy(ShanhaiRecipeJsExport.format(
                    ioTable, exportId.toString(), selectedBase.recipeTypeId(),
                    duration, eut, jsBlastTemp));
            setStatus("§a已复制 JS 配方对象");
        } catch (RuntimeException invalid) {
            setStatus("§c耗时或 EU/t 不是整数");
        }
    }

    void exportJson() {
        if (!isClient() || selectedBase == null) return;
        try {
            int duration = Math.max(1, Integer.parseInt(durationText.trim()));
            long eut = Long.parseLong(eutText.trim());
            net.minecraft.resources.ResourceLocation exportId = creating
                    ? RecipeEditorExportPacket.exportLocation(recipeIdText, "")
                    : RecipeEditorExportPacket.exportLocation(recipeIdText, selectedBase.recipeId());
            if (exportId == null) {
                setStatus(creating
                        ? "§c填写新配方 id，使用小写的 命名空间:路径"
                        : "§c配方 id 不合法，使用小写的 命名空间:路径");
                return;
            }
            JsonObject inputs = ioTable.json("inputs");
            JsonObject outputs = ioTable.json("outputs");
            String sourceId = creating ? exportId.toString() : selectedBase.recipeId();
            ShanhaiRecipeBase edited = new ShanhaiRecipeBase(
                    selectedBase.recipeTypeId(), sourceId, duration, eut,
                    inputs, outputs,
                    creating ? new JsonObject() : selectedBase.tickInputs(),
                    creating ? new JsonObject() : selectedBase.tickOutputs(),
                    conditionEdits);
            JsonObject payload = payloadWithConditionNote(edited);
            if (creating) payload.addProperty("createNew", true);
            ShanhaiNetwork.CHANNEL.sendToServer(new RecipeEditorExportPacket(
                    edited.recipeTypeId(), sourceId, exportId.toString(), payload.toString()));
            setStatus("§e正在导出配方 json…");
        } catch (RuntimeException invalid) {
            setStatus("§c耗时或 EU/t 不是整数");
        }
    }

    void encodePattern() {
        if (!isClient() || selectedBase == null) return;
        try {
            int duration = Math.max(1, Integer.parseInt(durationText.trim()));
            long eut = Long.parseLong(eutText.trim());
            net.minecraft.resources.ResourceLocation patternId = creating
                    ? RecipeEditorExportPacket.exportLocation(recipeIdText, "")
                    : RecipeEditorExportPacket.exportLocation(recipeIdText, selectedBase.recipeId());
            if (creating && patternId == null) {
                setStatus("§c填写新配方 id，使用小写的 命名空间:路径");
                return;
            }
            String sourceId = creating ? patternId.toString() : selectedBase.recipeId();
            JsonObject inputs = ioTable.json("inputs");
            JsonObject outputs = ioTable.json("outputs");
            ShanhaiRecipeBase edited = new ShanhaiRecipeBase(
                    selectedBase.recipeTypeId(), sourceId, duration, eut,
                    inputs, outputs,
                    creating ? new JsonObject() : selectedBase.tickInputs(),
                    creating ? new JsonObject() : selectedBase.tickOutputs(),
                    conditionEdits);
            JsonObject payload = payloadWithConditionNote(edited);
            if (creating) payload.addProperty("createNew", true);
            ShanhaiNetwork.CHANNEL.sendToServer(new RecipeEditorEncodePacket(
                    edited.recipeTypeId(), sourceId, payload.toString()));
            setStatus("§e正在按当前审核稿编写样板…");
        } catch (RuntimeException invalid) {
            setStatus("§c耗时或 EU/t 不是整数");
        }
    }

    private JsonObject payloadWithConditionNote(ShanhaiRecipeBase edited) {
        JsonObject payload = edited.payloadJson();
        payload.addProperty("conditionNote",
                ShanhaiRecipeConditions.diffLine(selectedBase.conditions(), conditionEdits));
        return payload;
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

    void setDurationText(String value) {
        String next = limit(value);
        if (!next.equals(durationText)) beforeEdit("text:duration");
        durationText = next;
        scheduleDraftSave();
    }

    void setEutText(String value) {
        String next = limit(value);
        if (!next.equals(eutText)) beforeEdit("text:eut");
        eutText = next;
        scheduleDraftSave();
    }

    void toggleKeepOriginal() {
        if (creating) return;
        keepOriginal = !keepOriginal;
        if (!isClient()) return;
        setStatus(keepOriginal
                ? "§a原配方会保留。把配方 id 改成新的，提交后两者同时存在"
                : "§e提交后原配方会从 JEI 和配方层移除");
        flushDraftSave();
    }

    String keepOriginalFace() {
        return keepOriginal ? "保留原配方" : "移除原配方";
    }

    String keepOriginalHint() {
        if (creating) return "§7不会移除或覆盖已有配方";
        if (keepOriginal) return "§7新 id 与原配方同时留下";
        return "§7JEI 和机器里只留修改后的";
    }

    String reviewBanner() {
        if (creating) return "§e全新配方。提交后只增加，不改动已有配方";
        return "§e提交前核对。服务端会重算指纹并重建配方表";
    }

    String reviewIdLine() {
        if (selectedBase == null) return "§8没有待审核草稿";
        if (!creating) return "§7配方 §f" + compact(selectedBase.recipeId(), 42);
        String id = recipeIdText == null ? "" : recipeIdText.trim();
        return id.isEmpty() ? "§e新建配方，尚未填写 id" : "§7新配方 §f" + compact(id, 42);
    }

    String reviewButtonFace() {
        return creating ? "去新增审核" : "去差异审核";
    }

    String commitFace() {
        return creating ? "提交新配方" : "提交并刷新 JEI";
    }

    String codecHint() {
        if (creating) return "§7格子、耗时、EU/t 和条件都会写入新配方";
        return "§7条件与未改动的格子保持原 codec";
    }

    void setRecipeIdText(String value) {
        String next = limit(value);
        if (!next.equals(recipeIdText)) beforeEdit("text:recipeId");
        recipeIdText = next;
        scheduleDraftSave();
    }

    void setConditionPick(String value) {
        conditionPick = value == null ? "" : value;
        editingCondition = -1;
        conditionDraft = ShanhaiRecipeConditions.defaultJson(conditionPick);
        panel.refreshParameters();
    }

    void editCondition(int index) {
        if (!isClient() || index < 0 || index >= conditionEdits.size()) return;
        editingCondition = index;
        conditionDraft = conditionEdits.get(index).deepCopy();
        panel.refreshParameters();
    }

    List<ShanhaiRecipeConditions.Parameter> draftParameters() {
        return ShanhaiRecipeConditions.parameters(conditionDraft);
    }

    String parameterText(String path) {
        for (ShanhaiRecipeConditions.Parameter parameter : draftParameters()) {
            if (parameter.path.equals(path)) return parameter.value;
        }
        return "";
    }

    void setParameterText(String path, String value) {
        if (!isClient() || conditionDraft == null) return;
        JsonElement next = ShanhaiRecipeConditions.withParameter(conditionDraft, path, value);
        if (next == conditionDraft) return;
        if (editingCondition >= 0) beforeEdit("condition");
        conditionDraft = next;
        if (editingCondition >= 0 && editingCondition < conditionEdits.size()) {
            conditionEdits.set(editingCondition, next.deepCopy());
            panel.refreshConditions(false);
            flushDraftSave();
        }
    }

    List<String> parameterChoices(String path) {
        return ShanhaiRecipeConditions.parameterChoices(path, parameterText(path));
    }

    void addCondition() {
        if (!isClient() || selectedBase == null) return;
        JsonElement created = conditionDraft == null
                ? ShanhaiRecipeConditions.defaultJson(conditionPick) : conditionDraft.deepCopy();
        if (created == null) {
            setStatus("§c没有可添加的配方条件");
            return;
        }
        beforeEdit("condition");
        conditionEdits.add(created);
        panel.refreshConditions();
        setStatus("§a已添加 " + ShanhaiRecipeConditions.label(created));
        flushDraftSave();
    }

    void removeCondition(int index) {
        if (!isClient() || index < 0 || index >= conditionEdits.size()) return;
        beforeEdit("condition");
        String label = ShanhaiRecipeConditions.label(conditionEdits.get(index));
        conditionEdits.remove(index);
        panel.refreshConditions();
        setStatus("§e已移除 " + label);
        flushDraftSave();
    }

    String conditionLabel(int index) {
        if (index < 0 || index >= conditionEdits.size()) return "";
        return ShanhaiRecipeConditions.label(conditionEdits.get(index));
    }

    void setChanceText(String value) {
        String next = limit(value);
        if (!next.equals(chanceText)) beforeEdit("text:chance");
        chanceText = next;
        applyChance(false);
    }

    void setCountText(String value) {
        String next = limit(value);
        if (!next.equals(countText)) beforeEdit("text:count");
        countText = next;
        applyCountText();
    }

    void beforeIoEdit() {
        beforeEdit("io");
    }

    void undoEdit() {
        if (selectedBase == null || editHistory.isEmpty()) {
            setStatus("§7没有可撤销的修改");
            return;
        }
        EditSnap now = captureEdit();
        EditSnap top = editHistory.get(editHistory.size() - 1);
        if (!now.same(top)) {
            applyEdit(top);
            lastEditKind = "";
            setStatus("§a已撤销上一步");
            flushDraftSave();
            return;
        }
        if (editHistory.size() <= 1) {
            setStatus("§7没有可撤销的修改");
            return;
        }
        editHistory.remove(editHistory.size() - 1);
        applyEdit(editHistory.get(editHistory.size() - 1));
        lastEditKind = "";
        setStatus("§a已撤销上一步");
        flushDraftSave();
    }

    void restoreOriginal() {
        if (selectedBase == null || editHistory.isEmpty()) return;
        EditSnap origin = editHistory.get(0);
        EditSnap now = captureEdit();
        if (now.same(origin)) {
            setStatus("§7已经是打开时的配方");
            return;
        }
        if (!now.same(editHistory.get(editHistory.size() - 1))) editHistory.add(now);
        applyEdit(origin);
        lastEditKind = "restore";
        setStatus(creating ? "§a已清空为刚打开的空白配方" : "§a已还原为打开时的配方");
        flushDraftSave();
    }

    private void seedEditHistory() {
        editHistory.clear();
        lastEditKind = "";
        if (selectedBase == null) return;
        editHistory.add(originEdit());
        EditSnap now = captureEdit();
        if (!now.same(editHistory.get(0))) editHistory.add(now);
    }

    private void beforeEdit(String kind) {
        if (restoringDraft || selectedBase == null || editHistory.isEmpty()) return;
        if (kind != null && kind.startsWith("text:") && kind.equals(lastEditKind)) return;
        EditSnap now = captureEdit();
        if (!now.same(editHistory.get(editHistory.size() - 1))) editHistory.add(now);
        while (editHistory.size() > 48) editHistory.remove(1);
        lastEditKind = kind == null ? "" : kind;
    }

    private void applyEdit(EditSnap snap) {
        durationText = snap.duration;
        eutText = snap.eut;
        recipeIdText = snap.recipeId;
        conditionEdits = parseConditions(snap.conditions);
        panel.refreshConditions();
        ioTable.restoreCells(snap.itemIn, snap.fluidIn, snap.itemOut, snap.fluidOut, snap.cells);
        inputItemPage = clampPage(snap.inputItemPage, ioTable.itemIn(), ShanhaiRecipeEditorPanel.ITEM_PAGE);
        inputFluidPage = clampPage(snap.inputFluidPage, ioTable.fluidIn(), ShanhaiRecipeEditorPanel.FLUID_PAGE);
        outputItemPage = clampPage(snap.outputItemPage, ioTable.itemOut(), ShanhaiRecipeEditorPanel.ITEM_PAGE);
        outputFluidPage = clampPage(snap.outputFluidPage, ioTable.fluidOut(), ShanhaiRecipeEditorPanel.FLUID_PAGE);
        inputPage = inputItemPage;
        outputPage = outputItemPage;
        selectedIo = ioTable.cell(snap.selectedIo) == null ? -1 : snap.selectedIo;
        chanceText = snap.chanceText;
        countText = snap.countText;
        panel.bindTable(ioTable);
    }

    private int clampPage(int page, int section, int pageSize) {
        return Math.max(0, Math.min(page, pageCount(section, pageSize) - 1));
    }

    private EditSnap captureEdit() {
        List<ShanhaiIoTable.Cell> cells = new ArrayList<>();
        for (ShanhaiIoTable.Cell cell : ioTable.all()) cells.add(cell.copy());
        return new EditSnap(durationText, eutText, recipeIdText, conditionEdits.toString(),
                ioTable.itemIn(), ioTable.fluidIn(),
                ioTable.itemOut(), ioTable.fluidOut(), cells,
                inputItemPage, inputFluidPage, outputItemPage, outputFluidPage,
                selectedIo, chanceText, countText);
    }

    private EditSnap originEdit() {
        ShanhaiIoTable base = ShanhaiIoTable.fromJson(selectedBase.inputs(), selectedBase.outputs());
        List<ShanhaiIoTable.Cell> cells = new ArrayList<>();
        for (ShanhaiIoTable.Cell cell : base.all()) cells.add(cell.copy());
        return new EditSnap(Integer.toString(selectedBase.duration()), Long.toString(selectedBase.eut()),
                selectedBase.recipeId(), selectedBase.conditions().toString(),
                base.itemIn(), base.fluidIn(), base.itemOut(), base.fluidOut(), cells,
                0, 0, 0, 0, -1, "100", "1");
    }

    private static final class EditSnap {
        private final String duration;
        private final String eut;
        private final String recipeId;
        private final String conditions;
        private final int itemIn;
        private final int fluidIn;
        private final int itemOut;
        private final int fluidOut;
        private final List<ShanhaiIoTable.Cell> cells;
        private final int inputItemPage;
        private final int inputFluidPage;
        private final int outputItemPage;
        private final int outputFluidPage;
        private final int selectedIo;
        private final String chanceText;
        private final String countText;

        private EditSnap(String duration, String eut, String recipeId, String conditions,
                         int itemIn, int fluidIn, int itemOut, int fluidOut,
                         List<ShanhaiIoTable.Cell> cells, int inputItemPage, int inputFluidPage,
                         int outputItemPage, int outputFluidPage, int selectedIo,
                         String chanceText, String countText) {
            this.duration = duration == null ? "" : duration;
            this.eut = eut == null ? "" : eut;
            this.recipeId = recipeId == null ? "" : recipeId;
            this.conditions = conditions == null ? "[]" : conditions;
            this.itemIn = itemIn;
            this.fluidIn = fluidIn;
            this.itemOut = itemOut;
            this.fluidOut = fluidOut;
            this.cells = cells;
            this.inputItemPage = inputItemPage;
            this.inputFluidPage = inputFluidPage;
            this.outputItemPage = outputItemPage;
            this.outputFluidPage = outputFluidPage;
            this.selectedIo = selectedIo;
            this.chanceText = chanceText == null ? "" : chanceText;
            this.countText = countText == null ? "" : countText;
        }

        private boolean same(EditSnap other) {
            if (other == null || itemIn != other.itemIn || fluidIn != other.fluidIn
                    || itemOut != other.itemOut || fluidOut != other.fluidOut) return false;
            if (!duration.equals(other.duration) || !eut.equals(other.eut)
                    || !recipeId.equals(other.recipeId) || !conditions.equals(other.conditions)) return false;
            if (inputItemPage != other.inputItemPage || inputFluidPage != other.inputFluidPage
                    || outputItemPage != other.outputItemPage || outputFluidPage != other.outputFluidPage) return false;
            if (cells.size() != other.cells.size()) return false;
            for (int i = 0; i < cells.size(); i++) {
                if (!sameCell(cells.get(i), other.cells.get(i))) return false;
            }
            return true;
        }

        private static boolean sameCell(ShanhaiIoTable.Cell left, ShanhaiIoTable.Cell right) {
            if (left == right) return true;
            if (left == null || right == null || left.itemKind != right.itemKind) return false;
            if (left.chance != right.chance || left.maxChance != right.maxChance) return false;
            if (left.itemKind) {
                return ItemStack.isSameItemSameTags(left.item, right.item)
                        && left.item.getCount() == right.item.getCount();
            }
            if (left.fluid.isEmpty() && right.fluid.isEmpty()) return true;
            if (left.fluid.isEmpty() || right.fluid.isEmpty()) return false;
            return left.fluid.getFluid() == right.fluid.getFluid()
                    && left.fluid.getAmount() == right.fluid.getAmount();
        }
    }

    void growInput() {
        int nextItems = growTo(ioTable.itemIn(), ShanhaiRecipeEditorPanel.ITEM_PAGE);
        int nextFluids = growTo(ioTable.fluidIn(), ShanhaiRecipeEditorPanel.FLUID_PAGE);
        if (nextItems == ioTable.itemIn() && nextFluids == ioTable.fluidIn()) return;
        beforeEdit("grow");
        ioTable.resize(nextItems, nextFluids, ioTable.itemOut(), ioTable.fluidOut());
        inputItemPage = pageCount(ioTable.itemIn(), ShanhaiRecipeEditorPanel.ITEM_PAGE) - 1;
        inputFluidPage = pageCount(ioTable.fluidIn(), ShanhaiRecipeEditorPanel.FLUID_PAGE) - 1;
        inputPage = inputItemPage;
        selectedIo = -1;
        syncIoDraft();
    }

    void growOutput() {
        int nextItems = growTo(ioTable.itemOut(), ShanhaiRecipeEditorPanel.ITEM_PAGE);
        int nextFluids = growTo(ioTable.fluidOut(), ShanhaiRecipeEditorPanel.FLUID_PAGE);
        if (nextItems == ioTable.itemOut() && nextFluids == ioTable.fluidOut()) return;
        beforeEdit("grow");
        ioTable.resize(ioTable.itemIn(), ioTable.fluidIn(), nextItems, nextFluids);
        outputItemPage = pageCount(ioTable.itemOut(), ShanhaiRecipeEditorPanel.ITEM_PAGE) - 1;
        outputFluidPage = pageCount(ioTable.fluidOut(), ShanhaiRecipeEditorPanel.FLUID_PAGE) - 1;
        outputPage = outputItemPage;
        selectedIo = -1;
        syncIoDraft();
    }

    void selectIo(int index) {
        ShanhaiIoTable.Cell cell = ioTable.cell(index);
        if (cell == null) return;
        if (index != selectedIo) selectedAt = ShanhaiRecipeEditorAnimation.nowMs();
        selectedIo = index;
        int max = Math.max(1, cell.maxChance);
        chanceText = Integer.toString(cell.chance * 100 / max);
        countText = Integer.toString(Math.max(0, cell.shownCount()));
    }

    void markNotConsumed() {
        ShanhaiIoTable.Cell cell = selectedCell();
        if (cell == null || cell.chance == 0) return;
        beforeEdit("chance");
        cell.chance = 0;
        cell.dirty = true;
        chanceText = "0";
        syncIoDraft();
    }

    void markChanceOutput() {
        applyChance(true);
    }

    private void applyChance(boolean checkpoint) {
        ShanhaiIoTable.Cell cell = selectedCell();
        if (cell == null) return;
        int percent = parsePercent(chanceText);
        if (percent < 0) {
            setStatus("§c概率要写成 0 到 100");
            return;
        }
        int max = cell.maxChance <= 0 ? 10000 : cell.maxChance;
        int next = percent * max / 100;
        if (cell.chance == next && cell.maxChance == max) return;
        if (checkpoint) beforeEdit("chance");
        cell.maxChance = max;
        cell.chance = next;
        cell.dirty = true;
        chanceText = Integer.toString(percent);
        syncIoDraft();
    }

    void markCertain() {
        ShanhaiIoTable.Cell cell = selectedCell();
        if (cell == null) return;
        if (cell.maxChance <= 0) cell.maxChance = 10000;
        if (cell.chance == cell.maxChance) return;
        beforeEdit("chance");
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
        if (cell.shownCount() == count) return;
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

    float selectionAccent() {
        float linear = Math.min(1f, Math.max(0L,
                ShanhaiRecipeEditorAnimation.nowMs() - selectedAt) / 160f);
        return linear * linear * (3f - 2f * linear);
    }

    String traitLine() {
        ShanhaiIoTable.Cell cell = ioTable.cell(selectedIo);
        if (cell == null) return "§8点一格，再设不消耗或概率";
        String side = selectedIo < ioTable.inSection() ? "输入" : "输出";
        String kind = cell.itemKind ? "物品" : "流体";
        String mode = cell.chance <= 0 ? "不消耗" : (cell.chance >= cell.maxChance ? "必定" : "概率");
        return "§7" + side + kind + " §f" + mode + " §7" + chanceText + "%%  数量 " + countText;
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
        return ShanhaiRecipeConditions.diffLine(selectedBase.conditions(), conditionEdits);
    }

    String fingerprintLine() {
        if (selectedBase == null) return "§7指纹：—";
        if (creating) return "§7全新配方，不对照旧指纹";
        return "§7打开时指纹 §f" + compact(ShanhaiRecipeFingerprint.of(selectedBase), 28);
    }

    String ioLine() {
        if (selectedBase == null) return "";
        return "§7IO §a" + ioTable.inSection() + " 入 §d" + ioTable.outSection() + " 出";
    }

    String durationDiffLine() {
        if (selectedBase == null) return "§7耗时：—";
        if (creating) return "§7耗时 §f" + (durationText == null ? "" : durationText.trim());
        String before = Integer.toString(selectedBase.duration());
        String after = durationText == null ? "" : durationText.trim();
        if (before.equals(after)) return "§7耗时 §f" + before + " §8未改";
        return "§7耗时 §6" + before + " §f→ §a" + after;
    }

    String eutDiffLine() {
        if (selectedBase == null) return "§7EU/t：—";
        if (creating) return "§7EU/t §f" + (eutText == null ? "" : eutText.trim());
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
            panel.refreshCards(cards, result.groups());
            setStatus(result.total() == 0
                    ? "§c没有命中配方"
                    : "§a" + modeName(result.mode()) + "命中 " + result.total() + " 条");
            return;
        }
        if ("encode".equals(packet.message())) {
            setStatus(packet.status() == RecipeEditorResultPacket.Status.SUCCESS
                    ? "§a已按当前审核稿编写样板"
                    : "§c样板未写入，原因见聊天栏");
            return;
        }
        if ("encode-failed".equals(packet.message())) {
            setStatus("§c样板未写入，原因见聊天栏");
            return;
        }
        if ("export".equals(packet.message())) {
            if (packet.status() == RecipeEditorResultPacket.Status.SUCCESS) {
                String payload = packet.payload() == null ? "" : packet.payload();
                String[] lines = payload.split("\n", 3);
                String where = lines[0] + (lines.length > 1 ? " " + lines[1] : "");
                String diff = lines.length > 2 ? " " + lines[2] : "";
                setStatus("§a已导出 " + compact(where, 40) + diff);
            } else {
                setStatus(statusLine(packet));
            }
            return;
        }
        if ("detail".equals(packet.message())
                && packet.status() == RecipeEditorResultPacket.Status.SUCCESS) {
            selectedBase = parseBase(packet.payload());
            if (selectedBase != null) {
                durationText = Integer.toString(selectedBase.duration());
                eutText = Long.toString(selectedBase.eut());
                recipeIdText = selectedBase.recipeId();
                conditionEdits = selectedBase.conditions().deepCopy();
                panel.refreshConditions();
                inputPage = 0;
                outputPage = 0;
                inputItemPage = 0;
                inputFluidPage = 0;
                outputItemPage = 0;
                outputFluidPage = 0;
                selectedIo = -1;
                ioTable = ShanhaiIoTable.fromJson(selectedBase.inputs(), selectedBase.outputs());
                panel.bindTable(ioTable);
                seedEditHistory();
                setStatus("§a已载入 " + compact(selectedBase.recipeId(), 48));
            }
            return;
        }
        setStatus(packet.payload() != null && packet.payload().startsWith("§7条件")
                ? "§a已提交 " + packet.payload()
                : statusLine(packet));
        if (packet.status() == RecipeEditorResultPacket.Status.SUCCESS
                && packet.message() != null && packet.message().contains("rebuilt")) {
            ShanhaiNetwork.CHANNEL.sendToServer(new RecipeEditorDraftRequestPacket(
                    RecipeEditorDraftRequestPacket.Action.CLEAR, null));
            creating = false;
            selectedBase = null;
            selectedCard = null;
            editHistory.clear();
            setStage(STAGE_SELECT, true);
            query();
        }
    }

    private void parseMachine(String payload) {
        try {
            JsonObject root = JsonParser.parseString(payload).getAsJsonObject();
            machineTypes.clear();
            machineTypeNames.clear();
            mappedTypeCount = 0;
            ownedTypeCount = 0;
            JsonArray values = root.getAsJsonArray("types");
            for (JsonElement element : values) {
                JsonObject value = element.getAsJsonObject();
                boolean owned = value.has("owned") && value.get("owned").getAsBoolean();
                machineTypes.add(value.get("id").getAsString());
                String label = value.get("label").getAsString();
                int count = value.has("count") ? value.get("count").getAsInt() : 0;
                machineTypeNames.add((owned ? "山海 · " : "") + label + " · " + count + " 条");
                if (owned) ownedTypeCount++;
                else mappedTypeCount++;
            }
            if (mappedTypeCount > 0) {
                if (!machineTypes.subList(0, mappedTypeCount).contains(typeFilter)) {
                    typeFilter = machineTypes.get(0);
                }
                queryPage = 0;
                query();
            } else if (machineTypes.isEmpty()) {
                setStatus("§c没有可读的配方类型");
            }
        } catch (RuntimeException invalid) {
            setStatus("§c机器映射数据解析失败");
        }
    }

    private static String modeName(ShanhaiRecipeQuery.SearchMode mode) {
        return switch (mode) {
            case INGREDIENT -> "原料";
            case OUTPUT -> "输出";
            default -> "配方";
        };
    }

    List<Integer> sortIndexes(List<ShanhaiRecipeQuery.Card> source, List<Integer> indexes) {
        List<Integer> ordered = new ArrayList<>();
        if (source == null || indexes == null) return ordered;
        for (Integer index : indexes) {
            if (index == null || index < 0 || index >= source.size() || source.get(index) == null) continue;
            ordered.add(index);
        }
        ordered.sort((left, right) -> compareCards(source.get(left), source.get(right)));
        return ordered;
    }

    private int compareCards(ShanhaiRecipeQuery.Card left, ShanhaiRecipeQuery.Card right) {
        int order = switch (sortKey) {
            case "eut" -> Long.compare(left.eut(), right.eut());
            case "duration" -> Integer.compare(left.duration(), right.duration());
            case "inputs" -> Integer.compare(left.inputCount(), right.inputCount());
            case "outputs" -> Integer.compare(left.outputCount(), right.outputCount());
            default -> left.recipeId().compareToIgnoreCase(right.recipeId());
        };
        if (order == 0) return left.recipeId().compareToIgnoreCase(right.recipeId());
        return sortDesc ? -order : order;
    }

    private static String ioBrief(JsonObject card, String key) {
        if (card == null || !card.has(key) || !card.get(key).isJsonArray()) return "";
        JsonArray values = card.getAsJsonArray(key);
        StringBuilder text = new StringBuilder();
        int limit = Math.min(3, values.size());
        for (int i = 0; i < limit; i++) {
            if (i > 0) text.append(", ");
            text.append(ioBit(values.get(i)));
        }
        if (values.size() > 3) text.append("…");
        return text.toString();
    }

    private static String ioBit(JsonElement element) {
        if (element == null || element.isJsonNull()) return "";
        if (!element.isJsonObject()) return element.getAsString();
        JsonObject value = element.getAsJsonObject();
        String id = value.has("id") ? value.get("id").getAsString() : "";
        int slash = id.lastIndexOf(':');
        String name = slash >= 0 && slash + 1 < id.length() ? id.substring(slash + 1) : id;
        int count = value.has("count") ? value.get("count").getAsInt() : 1;
        return count > 1 ? name + "x" + count : name;
    }

    private static int ioCount(JsonObject card, String countKey, String arrayKey) {
        if (card.has(countKey) && card.get(countKey).isJsonPrimitive()) return card.get(countKey).getAsInt();
        if (card.has(arrayKey) && card.get(arrayKey).isJsonArray()) return card.getAsJsonArray(arrayKey).size();
        return 0;
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
                        card.has("iconId") ? card.get("iconId").getAsString() : "",
                        ioBrief(card, "inputs"),
                        ioBrief(card, "outputs"),
                        ioCount(card, "inputCount", "inputs"),
                        ioCount(card, "outputCount", "outputs")));
            }
            List<ShanhaiRecipeQuery.Card> page = List.copyOf(result);
            return new ShanhaiRecipeQuery.Result(page,
                    root.get("total").getAsInt(), root.get("revision").getAsLong(),
                    parseMode(root.get("mode")), parseGroups(root, page.size()));
        } catch (RuntimeException invalid) {
            setStatus("§c配方列表解析失败");
            return new ShanhaiRecipeQuery.Result(List.of(), 0, 0L);
        }
    }

    private static ShanhaiRecipeQuery.IngredientKind parseKind(String name) {
        if (name == null || name.isEmpty()) return ShanhaiRecipeQuery.IngredientKind.ITEM;
        try {
            return ShanhaiRecipeQuery.IngredientKind.valueOf(name);
        } catch (IllegalArgumentException invalid) {
            return ShanhaiRecipeQuery.IngredientKind.ITEM;
        }
    }

    private static ShanhaiRecipeQuery.SearchMode parseMode(JsonElement element) {
        if (element == null || !element.isJsonPrimitive()) return ShanhaiRecipeQuery.SearchMode.RECIPE_ID;
        try {
            return ShanhaiRecipeQuery.SearchMode.valueOf(element.getAsString());
        } catch (IllegalArgumentException invalid) {
            return ShanhaiRecipeQuery.SearchMode.RECIPE_ID;
        }
    }

    private static List<ShanhaiRecipeQuery.Group> parseGroups(JsonObject root, int cardCount) {
        if (root == null || !root.has("groups") || !root.get("groups").isJsonArray()) return null;
        JsonArray values = root.getAsJsonArray("groups");
        if (values.isEmpty()) return null;
        List<ShanhaiRecipeQuery.Group> groups = new ArrayList<>();
        for (JsonElement element : values) {
            if (!element.isJsonObject()) continue;
            JsonObject group = element.getAsJsonObject();
            String typeId = group.has("recipeTypeId") && group.get("recipeTypeId").isJsonPrimitive()
                    ? group.get("recipeTypeId").getAsString() : "";
            List<Integer> indexes = new ArrayList<>();
            if (group.has("cardIndexes") && group.get("cardIndexes").isJsonArray()) {
                for (JsonElement index : group.getAsJsonArray("cardIndexes")) {
                    if (!index.isJsonPrimitive()) continue;
                    int value = index.getAsInt();
                    if (value >= 0 && value < cardCount) indexes.add(value);
                }
            }
            groups.add(new ShanhaiRecipeQuery.Group(typeId, indexes));
        }
        return groups.isEmpty() ? null : groups;
    }

    private ShanhaiRecipeBase parseBase(String payload) {
        try {
            JsonObject json = JsonParser.parseString(payload).getAsJsonObject();
            jsBlastTemp = json.has("blastTemp") && json.get("blastTemp").isJsonPrimitive()
                    ? json.get("blastTemp").getAsInt() : -1;
            json.remove("blastTemp");
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
        draft.putString("searchMode", searchMode.name());
        draft.putString("ingredientKind", ingredientKind.name());
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
        if (!queryHistory.isEmpty()) {
            ListTag history = new ListTag();
            for (QueryMemory memory : queryHistory) {
                CompoundTag row = new CompoundTag();
                row.putString("type", memory.type);
                row.putString("text", memory.text);
                row.putString("mode", memory.mode.name());
                row.putString("kind", memory.kind.name());
                history.add(row);
            }
            draft.put("queryHistory", history);
        }
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
            draft.putString("recipeIdText", recipeIdText);
            draft.putBoolean("keepOriginal", keepOriginal);
            draft.putBoolean("creating", creating);
            draft.putString("conditionEdits", conditionEdits.toString());
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
        creating = draft.getBoolean("creating");
        try {
            machineStack = draft.contains("machine", CompoundTag.TAG_COMPOUND)
                    ? ItemStack.of(draft.getCompound("machine")) : ItemStack.EMPTY;
            typeFilter = draft.getString("typeFilter");
            searchText = draft.getString("searchText");
            searchMode = parseMode(draft.contains("searchMode")
                    ? new com.google.gson.JsonPrimitive(draft.getString("searchMode")) : null);
            ingredientKind = parseKind(draft.getString("ingredientKind"));
            queryHistory.clear();
            ListTag history = draft.getList("queryHistory", CompoundTag.TAG_COMPOUND);
            for (int i = 0; i < history.size() && queryHistory.size() < QUERY_HISTORY_LIMIT; i++) {
                CompoundTag row = history.getCompound(i);
                String text = limit(row.getString("text")).trim();
                String type = limit(row.getString("type")).trim();
                if (text.isEmpty() && type.isEmpty()) continue;
                queryHistory.add(new QueryMemory(type, text,
                        parseMode(new com.google.gson.JsonPrimitive(row.getString("mode"))),
                        parseKind(row.getString("kind"))));
            }
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
                recipeIdText = draft.contains("recipeIdText")
                        ? draft.getString("recipeIdText") : selectedBase.recipeId();
                conditionEdits = draft.contains("conditionEdits")
                        ? parseConditions(draft.getString("conditionEdits"))
                        : selectedBase.conditions().deepCopy();
                panel.refreshConditions();
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
                seedEditHistory();
                String fingerprint = draft.getString("baseFingerprint");
                if (!creating && !fingerprint.isEmpty()) {
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
            if (draft.contains("keepOriginal")) keepOriginal = draft.getBoolean("keepOriginal");
            setStatus(selectedBase == null && selectedCard == null
                    ? "§7草稿已恢复" : "§a已恢复未提交的配方编辑进度");
        } catch (RuntimeException invalid) {
            creating = false;
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

    private static JsonArray parseConditions(String raw) {
        try {
            JsonElement element = JsonParser.parseString(raw == null ? "[]" : raw);
            if (element.isJsonArray()) return element.getAsJsonArray();
        } catch (RuntimeException ignored) {
            // Keep an empty list rather than a half-parsed condition.
        }
        return new JsonArray();
    }

    private record QueryMemory(String type, String text,
                               ShanhaiRecipeQuery.SearchMode mode,
                               ShanhaiRecipeQuery.IngredientKind kind) {
        private boolean same(QueryMemory other) {
            return other != null && type.equals(other.type) && text.equals(other.text)
                    && mode == other.mode && kind == other.kind;
        }
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
