package com.dishanhai.gt_shanhai.common.gui.generator;

import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;

import com.dishanhai.gt_shanhai.GTDishanhaiMod;
import com.dishanhai.gt_shanhai.common.item.SelectedRecipePattern;
import com.dishanhai.gt_shanhai.common.item.generator.PatternGeneratorClientHooks;
import com.dishanhai.gt_shanhai.common.item.generator.PatternGeneratorFilter;
import com.dishanhai.gt_shanhai.common.item.generator.PatternGeneratorLayout;
import com.dishanhai.gt_shanhai.common.item.generator.PatternGeneratorPresetFiles;
import com.dishanhai.gt_shanhai.common.item.generator.PatternGeneratorRecipes;
import com.dishanhai.gt_shanhai.common.item.generator.PatternGeneratorScale;
import com.dishanhai.gt_shanhai.common.item.generator.PatternGeneratorSettings;
import com.dishanhai.gt_shanhai.common.item.generator.PatternGeneratorTransfer;
import com.gregtechceu.gtceu.api.GTValues;
import com.gregtechceu.gtceu.api.gui.GuiTextures;
import com.gregtechceu.gtceu.api.item.MetaMachineItem;
import com.gregtechceu.gtceu.api.recipe.GTRecipeType;
import com.gregtechceu.gtceu.api.recipe.RecipeHelper;

import com.lowdragmc.lowdraglib.gui.factory.HeldItemUIFactory;
import com.lowdragmc.lowdraglib.gui.texture.ColorBorderTexture;
import com.lowdragmc.lowdraglib.gui.texture.ColorRectTexture;
import com.lowdragmc.lowdraglib.gui.texture.GuiTextureGroup;
import com.lowdragmc.lowdraglib.gui.texture.ItemStackTexture;
import com.lowdragmc.lowdraglib.gui.texture.TextTexture;
import com.lowdragmc.lowdraglib.gui.widget.ButtonWidget;
import com.lowdragmc.lowdraglib.gui.widget.DraggableScrollableWidgetGroup;
import com.lowdragmc.lowdraglib.gui.widget.ImageWidget;
import com.lowdragmc.lowdraglib.gui.widget.LabelWidget;
import com.lowdragmc.lowdraglib.gui.widget.SelectorWidget;
import com.lowdragmc.lowdraglib.gui.widget.SlotWidget;
import com.lowdragmc.lowdraglib.gui.widget.TextFieldWidget;
import com.lowdragmc.lowdraglib.gui.widget.Widget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** 手持菜单。筛选和生成以服务器配方为准，预设文件只在客户端读写。 */
public final class PatternGeneratorWidget extends WidgetGroup {
    public static final int WIDTH = 310;
    public static final int HEIGHT = 240;
    private static final String PREFIX = "gt_shanhai.gui.pattern_generator.";
    private static final int STATE = 100;
    private static final int TYPE = 101;
    private static final int CIRCUIT = 102;
    private static final int WHITE = 103;
    private static final int CLEAR = 104;
    private static final int PAGE = 105;
    private static final int SELECT = 106;
    private static final int SELECT_ALL = 107;
    private static final int GENERATE = 108;
    private static final int CANCEL = 109;
    private static final int FILL = 110;
    private static final int COLUMNS = 111;
    private static final int INCLUDE_TAG = 112;
    private static final int EXPORT_PRESET = 113;
    private static final int APPLY_PRESET = 114;
    private static final int INSPECT_PRESET = 115;
    private static final int EXT = 116;
    private static final int BUNDLE = 117;
    private static final int PRESET_FILE = 120;
    private static final int PRESET_PREVIEW = 121;
    private static final int BATCH_PER_TICK = 8;

    private final HeldItemUIFactory.HeldItemHolder holder;
    private final PatternGeneratorSettings settings;
    private final WidgetGroup main = new WidgetGroup(0, 0, WIDTH, 158);
    private final WidgetGroup filters = new WidgetGroup(0, 0, WIDTH, HEIGHT);
    private final int[] selectedFilterSlots = new int[4];
    private final WidgetGroup circuits = new WidgetGroup(0, 0, WIDTH, 158);
    private final WidgetGroup extensions = new WidgetGroup(0, 0, WIDTH, HEIGHT);
    private final WidgetGroup previews = new WidgetGroup(0, 0, WIDTH, HEIGHT);
    private final WidgetGroup presets = new WidgetGroup(0, 0, WIDTH, HEIGHT);
    private final WidgetGroup savePreset = new WidgetGroup(0, 0, WIDTH, 158);
    private final WidgetGroup inventory = new WidgetGroup(0, 0, WIDTH, HEIGHT);
    private final DraggableScrollableWidgetGroup grid = new DraggableScrollableWidgetGroup(10, 64, 290, 110)
            .setYScrollBarWidth(4).setYBarStyle(new ColorRectTexture(0xff9495a3), GuiTextures.VANILLA_BUTTON)
            .setUseScissor(true).setDraggable(false);
    private final DraggableScrollableWidgetGroup presetList = new DraggableScrollableWidgetGroup(10, 62, 126, 142)
            .setYScrollBarWidth(4).setYBarStyle(new ColorRectTexture(0xff9495a3), GuiTextures.VANILLA_BUTTON)
            .setUseScissor(true).setDraggable(false);
    private final DraggableScrollableWidgetGroup presetGrid = new DraggableScrollableWidgetGroup(146, 134, 154, 52)
            .setYScrollBarWidth(4).setYBarStyle(new ColorRectTexture(0xff9495a3), GuiTextures.VANILLA_BUTTON)
            .setUseScissor(true).setDraggable(false);
    private final Map<String, Integer> typeCounts = new HashMap<>();
    private List<GTRecipeType> types = List.of();
    private List<PatternGeneratorRecipes.Entry> all = List.of();
    private List<PatternGeneratorRecipes.Entry> matching = List.of();
    private final Set<String> unchecked = new HashSet<>();
    private Set<String> restoredSelection;
    private List<Preview> previewPage = List.of();
    private int revision;
    private int page;
    private int count;
    private int selected;
    private int pendingCount;
    private int generated;
    private int failed;
    private int skipped;
    private int availablePatterns;
    private int queuedTransfers;
    private long lastAvailableCheck = -1;
    private boolean running;
    private boolean ready;
    private boolean whiteDirty;
    private boolean extDirty;
    private boolean saveRequested;
    private long editAt;
    private long extAt;
    private String inputDraft = "";
    private String outputDraft = "";
    private String scaleDraft = "1";
    private String outputScaleDraft = "1";
    private String queryDraft = "";
    private String presetName = "";
    private String presetMessage = "";
    private Component feedback = Component.empty();
    private final SelectorWidget typeSelector;
    private final ButtonWidget generationButton;
    private final ButtonWidget bundleButton;
    private final ButtonWidget cancelButton;
    private final ButtonWidget selectAllButton;
    private final ButtonWidget previousButton;
    private final ButtonWidget nextButton;
    private final ButtonWidget fillButton;
    private final ButtonWidget mainFillButton;
    private final ButtonWidget previewButton;
    private final ButtonWidget applyPresetButton;
    private final ButtonWidget savePresetButton;
    private final PatternGeneratorGhostWidget machineSlot;
    private final List<Widget> configuration = new ArrayList<>();
    private PatternGeneratorPresetFiles.Preset chosenPreset;
    private String inspectedMachine = "";
    private String inspectedMode = "";
    private int inspectedAvailable;
    private int inspectedMissing;
    private int presetRequest;

    private record Preview(String id, int circuit, long eut, int duration, String tier,
                           List<GenericStack> inputs, List<GenericStack> outputs, boolean checked, boolean overflow) {}

    public PatternGeneratorWidget(HeldItemUIFactory.HeldItemHolder holder) {
        super(0, 0, WIDTH, HEIGHT);
        setId("shanhai_pattern_generator");
        this.holder = holder;
        settings = PatternGeneratorSettings.load(holder.getHeld());
        queuedTransfers = settings.transferRecipes.size();
        inputDraft = settings.inputWhite;
        outputDraft = settings.outputWhite;
        scaleDraft = Integer.toString(settings.scale);
        outputScaleDraft = Integer.toString(settings.outputScale);
        queryDraft = settings.recipeQuery;
        ready = inputDraft.isBlank() && outputDraft.isBlank();
        refreshMachine();
        if (holder.getPlayer() instanceof ServerPlayer) rebuild(false);

        addWidget(text(10, 5, 290, 12, () -> tr("title")));
        addWidget(new ImageWidget(10, 19, 290, 1, new ColorRectTexture(0xff8b8c99)).setClientSideWidget());
        main.addWidget(panel(10, 22, 290, 36));
        main.addWidget(label(16, 26, "step_machine"));
        machineSlot = new PatternGeneratorGhostWidget(16, 36,
                () -> settings.machine.isEmpty() ? null : new GenericStack(AEItemKey.of(settings.machine), 1),
                stack -> stack.what() instanceof AEItemKey item && item.getItem() instanceof MetaMachineItem,
                this::setMachine, PREFIX + "machine_hint");
        machineSlot.setId("shanhai_pattern_generator.machine");
        main.addWidget(machineSlot);
        main.addWidget(text(42, 35, 248, 10, () -> settings.machine.isEmpty()
                ? tr("machine_empty") : settings.machine.getHoverName().getString()));
        main.addWidget(text(42, 46, 248, 9, () -> tr("drag_hint")));
        main.addWidget(label(10, 60, "step_recipe"));
        main.addWidget(label(214, 60, "circuit"));
        typeSelector = new SelectorWidget(10, 71, 194, 18, List.of(), 6)
                .setButtonBackground(GuiTextures.VANILLA_BUTTON).setBackground(GuiTextures.BACKGROUND)
                .setFontColor(0xff303030).setCandidatesSupplier(this::typeLabels)
                .setSupplier(this::selectedTypeLabel).setOnChanged(value -> {
                    if (!holder.isRemote()) return;
                    int index = typeLabels().indexOf(value);
                    if (index >= 0 && index < types.size()) {
                        send(TYPE, buffer -> buffer.writeUtf(types.get(index).registryName.toString(), 256));
                    }
                });
        typeSelector.setClientSideWidget();
        typeSelector.setId("shanhai_pattern_generator.type");
        configuration.add(typeSelector);
        var circuitButton = button(214, 71, 86, 18, this::circuitLabel, () -> show(circuits));
        circuitButton.setId("shanhai_pattern_generator.circuit");
        configuration.add(circuitButton);
        main.addWidget(circuitButton);
        var filterButton = button(10, 92, 92, 18, () -> settings.filterCount() == 0
                ? tr("filters_optional") : tr("filters_active", settings.filterCount()), () -> show(filters));
        filterButton.setId("shanhai_pattern_generator.filters");
        main.addWidget(filterButton);
        var extensionButton = button(108, 92, 92, 18, () -> settings.extensionsActive()
                ? tr("extensions_active") : tr("extensions"), () -> show(extensions));
        extensionButton.setId("shanhai_pattern_generator.extensions");
        main.addWidget(extensionButton);
        previewButton = button(206, 92, 94, 18, () -> tr("open_preview", count), () -> {
            flushDrafts();
            show(previews);
        });
        previewButton.setHoverTooltips(List.of(Component.translatable(PREFIX + "main_hint")));
        previewButton.setId("shanhai_pattern_generator.open_preview");
        main.addWidget(previewButton);
        main.addWidget(button(10, 115, 140, 18, () -> tr("presets"), this::openPresets)
                .setId("shanhai_pattern_generator.presets"));
        main.addWidget(button(160, 115, 140, 18, () -> tr("save_preset"), () -> {
            flushDrafts();
            presetMessage = "";
            show(savePreset);
        }).setId("shanhai_pattern_generator.save_preset"));
        mainFillButton = button(10, 138, 290, 18, this::fillLabel, this::requestFill);
        mainFillButton.setId("shanhai_pattern_generator.fill");
        mainFillButton.setHoverTooltips(List.of(Component.translatable(PREFIX + "fill_hint")));
        main.addWidget(mainFillButton);
        main.addWidget(typeSelector);

        previews.addWidget(button(10, 24, 60, 18, () -> tr("back_main"), () -> show(main)));
        previews.addWidget(text(76, 24, 132, 18, () -> tr("step_preview")));
        var columns = selector(214, 24, 86,
                () -> java.util.stream.IntStream.rangeClosed(1, PatternGeneratorLayout.MAX_COLUMNS)
                        .mapToObj(i -> tr("column_count", i)).toList(),
                () -> tr("column_count", settings.previewColumns), value -> {
                    for (int i = 1; i <= PatternGeneratorLayout.MAX_COLUMNS; i++) {
                        if (value.equals(tr("column_count", i))) {
                            int chosen = i;
                            send(COLUMNS, buffer -> buffer.writeVarInt(chosen));
                            break;
                        }
                    }
                });
        columns.setHoverTooltips(List.of(Component.translatable(PREFIX + "columns_hint")));
        columns.setId("shanhai_pattern_generator.columns");
        previousButton = button(10, 45, 18, 16, () -> "<", () -> page(-1));
        nextButton = button(100, 45, 18, 16, () -> ">", () -> page(1));
        previews.addWidget(previousButton);
        previews.addWidget(center(30, 45, 68, 16, () -> count == 0 ? "0 / 0"
                : (page + 1) + " / " + PatternGeneratorLayout.pages(count, settings.previewColumns)));
        previews.addWidget(nextButton);
        selectAllButton = button(128, 45, 80, 16,
                () -> tr(selected == count && count > 0 ? "unselect_all" : "select_all"),
                () -> send(SELECT_ALL, buffer -> {
                    buffer.writeVarInt(revision);
                    buffer.writeBoolean(selected != count);
                }));
        previews.addWidget(selectAllButton);
        previews.addWidget(text(216, 45, 84, 16, () -> tr("page_capacity",
                PatternGeneratorLayout.capacity(settings.previewColumns))));
        grid.setClientSideWidget();
        grid.setBackground(GuiTextures.BACKGROUND_INVERSE);
        previews.addWidget(grid);
        bundleButton = button(10, 176, 290, 16, () -> tr("bundle", selected), () -> {
            if (!flushDrafts()) send(BUNDLE, buffer -> buffer.writeVarInt(revision));
        });
        bundleButton.setId("shanhai_pattern_generator.bundle");
        previews.addWidget(bundleButton);
        previews.addWidget(text(10, 196, 224, 14, () -> feedback.getString().isEmpty()
                ? tr("selected_total", selected, count) : feedback.getString()));
        cancelButton = button(240, 196, 60, 14, () -> tr("cancel_pending"), () -> send(CANCEL, buffer -> {}));
        previews.addWidget(cancelButton);
        generationButton = button(10, 214, 140, 18, this::generationLabel, () -> {
            if (!flushDrafts()) send(GENERATE, buffer -> buffer.writeVarInt(revision));
        });
        generationButton.setId("shanhai_pattern_generator.generate");
        previews.addWidget(generationButton);
        fillButton = button(160, 214, 140, 18, this::fillLabel, this::requestFill);
        fillButton.setHoverTooltips(List.of(Component.translatable(PREFIX + "fill_hint")));
        previews.addWidget(fillButton);
        previews.addWidget(columns);

        buildFilters();
        buildCircuits();
        buildExtensions();
        presets.addWidget(button(10, 24, 60, 18, () -> tr("back_main"), () -> show(main)));
        presets.addWidget(text(76, 24, 132, 18, () -> tr("presets")));
        presets.addWidget(button(214, 24, 86, 18, () -> tr("open_folder"), this::openPresetDirectory));
        presets.addWidget(text(10, 46, 290, 12, () -> tr("preset_share_hint")));
        presetList.setClientSideWidget();
        presets.addWidget(presetList);
        presets.addWidget(text(146, 64, 154, 12, () -> chosenPreset == null ? tr("preset_choose") : chosenPreset.name()));
        presets.addWidget(text(146, 79, 154, 11, () -> chosenPreset == null ? "" : tr("preset_author", chosenPreset.author())));
        presets.addWidget(text(146, 93, 154, 11, () -> inspectedMachine));
        presets.addWidget(text(146, 107, 154, 11, () -> inspectedMode));
        presets.addWidget(text(146, 121, 154, 11, () -> chosenPreset == null ? ""
                : tr("preset_available", inspectedAvailable, inspectedMissing)));
        presetGrid.setClientSideWidget();
        presets.addWidget(presetGrid);
        presets.addWidget(text(146, 190, 154, 15, () -> presetMessage));
        presets.addWidget(button(10, 214, 126, 18, () -> tr("refresh_presets"), this::openPresets));
        applyPresetButton = button(146, 214, 154, 18, () -> tr("apply_preview"), () -> submitPreset(APPLY_PRESET));
        applyPresetButton.setId("shanhai_pattern_generator.apply_preset");
        presets.addWidget(applyPresetButton);

        savePreset.addWidget(label(10, 28, "save_preset"));
        savePreset.addWidget(button(214, 24, 86, 18, () -> tr("back_main"), () -> show(main)));
        savePreset.addWidget(label(10, 48, "preset_name"));
        var nameField = new TextFieldWidget(10, 62, 290, 18, () -> presetName, value -> presetName = value)
                .setMaxStringLength(48).setTextColor(0xffeeeeee).setBackground(GuiTextures.DISPLAY);
        nameField.setClientSideWidget();
        savePreset.addWidget(nameField);
        savePreset.addWidget(text(10, 87, 290, 14, () -> tr("preset_save_hint")));
        savePreset.addWidget(text(10, 105, 290, 14, () -> tr("selected_total", selected, count)));
        savePreset.addWidget(text(10, 123, 290, 12, () -> presetMessage));
        savePresetButton = button(10, 140, 140, 16, () -> tr("save_local"), () -> {
            if (flushDrafts()) return;
            saveRequested = true;
            send(EXPORT_PRESET, buffer -> {
                buffer.writeVarInt(revision);
                buffer.writeUtf(presetName.strip(), 48);
            });
        });
        savePresetButton.setId("shanhai_pattern_generator.save_local");
        savePreset.addWidget(savePresetButton);
        savePreset.addWidget(button(160, 140, 140, 16, () -> tr("open_folder"), this::openPresetDirectory));
        bindInventory();
        addWidget(inventory);
        for (WidgetGroup view : List.of(main, filters, circuits, extensions, previews, presets, savePreset)) addWidget(view);
        show(main);
        updateControls();
    }

    private String fillLabel() {
        return tr(queuedTransfers > 0 ? "fill_resume" : "fill_target",
                availablePatterns + (queuedTransfers > 0 ? queuedTransfers : selected));
    }

    private void requestFill() {
        if (!flushDrafts()) send(FILL, buffer -> buffer.writeVarInt(revision));
    }

    private void buildFilters() {
        var tagSelectors = new ArrayList<SelectorWidget>();
        filters.addWidget(label(10, 28, "filters_optional"));
        filters.addWidget(button(214, 24, 86, 18, () -> tr("back_main"), () -> {
            flushDrafts();
            show(main);
        }));
        for (int side = 0; side < 2; side++) {
            boolean input = side == 0;
            int x = input ? 10 : 160;
            filters.addWidget(label(x, 44, input ? "input_white" : "output_white"));
            var editor = new TextFieldWidget(x, 55, 140, 18, () -> input ? inputDraft : outputDraft, value -> {
                if (input) inputDraft = PatternGeneratorSettings.limit(value);
                else outputDraft = PatternGeneratorSettings.limit(value);
                whiteDirty = true;
                editAt = System.currentTimeMillis();
            }).setMaxStringLength(256).setTextColor(0xffeeeeee).setBackground(GuiTextures.DISPLAY);
            editor.setClientSideWidget();
            editor.setId("shanhai_pattern_generator." + (input ? "input_white" : "output_white"));
            editor.setHoverTooltips(List.of(Component.translatable(PREFIX + "white_hint")));
            configuration.add(editor);
            filters.addWidget(editor);
            filters.addWidget(label(x, 76, input ? "input_include" : "output_include"));
            filters.addWidget(label(x, 130, input ? "input_black" : "output_black"));
            for (int mode = 0; mode < 2; mode++) {
                boolean exclude = mode == 1;
                int group = side * 2 + mode;
                int y = exclude ? 142 : 88;
                String groupId = (input ? "input_" : "output_") + (exclude ? "black" : "include");
                for (int index = 0; index < PatternGeneratorSettings.FILTER_SLOTS; index++) {
                    int slot = index;
                    var ghost = new PatternGeneratorGhostWidget(x + 1 + index * 20, y,
                            () -> settings.filterSlots(input, exclude)[slot], stack -> true,
                            stack -> setFilter(input, exclude, slot, stack), PREFIX + "multi_filter_hint")
                            .selection(() -> selectedFilterSlots[group] = slot,
                                    () -> selectedFilterSlots[group] == slot,
                                    () -> Component.literal(filterLabel(input, exclude, slot)));
                    ghost.setId("shanhai_pattern_generator." + groupId + "." + slot);
                    configuration.add(ghost);
                    filters.addWidget(ghost);
                }
                var tags = selector(x, y + 20, 140,
                        () -> filterLabels(input, exclude, selectedFilterSlots[group]),
                        () -> filterLabel(input, exclude, selectedFilterSlots[group]), value -> {
                            int slot = selectedFilterSlots[group];
                            int index = filterLabels(input, exclude, slot).indexOf(value);
                            if (index < 0) return;
                            List<String> names = PatternGeneratorSettings.tags(settings.filterSlots(input, exclude)[slot]);
                            if (index > names.size()) return;
                            String tag = index == 0 ? "" : names.get(index - 1);
                            send(INCLUDE_TAG, buffer -> {
                                buffer.writeBoolean(input);
                                buffer.writeBoolean(exclude);
                                buffer.writeVarInt(slot);
                                buffer.writeUtf(tag, 256);
                            });
                        });
                tags.setMaxCount(3);
                tags.setId("shanhai_pattern_generator." + groupId + "_tag");
                tags.setHoverTooltips(List.of(Component.translatable(PREFIX + "multi_tag_hint")));
                configuration.add(tags);
                tagSelectors.add(tags);
            }
        }
        filters.addWidget(center(10, 188, 290, 12, () -> tr("multi_filter_rule")));
        var clear = button(10, 214, 140, 18, () -> tr("clear_filters"), this::clearFilters);
        configuration.add(clear);
        filters.addWidget(clear);
        filters.addWidget(button(160, 214, 140, 18, () -> tr("view_matches", count), () -> {
            flushDrafts();
            show(previews);
        }));
        for (var tags : tagSelectors) filters.addWidget(tags);
    }

    private void buildExtensions() {
        extensions.addWidget(label(10, 28, "extensions"));
        extensions.addWidget(button(214, 24, 86, 18, () -> tr("back_main"), () -> {
            flushDrafts();
            show(main);
        }));
        extensions.addWidget(label(10, 48, "scale"));
        var scaleField = new TextFieldWidget(70, 44, 50, 16, () -> scaleDraft, value -> {
            scaleDraft = value;
            extDirty = true;
            extAt = System.currentTimeMillis();
        }).setMaxStringLength(2).setTextColor(0xffeeeeee).setBackground(GuiTextures.DISPLAY);
        scaleField.setClientSideWidget();
        scaleField.setHoverTooltips(List.of(Component.translatable(PREFIX + "scale_hint")));
        configuration.add(scaleField);
        extensions.addWidget(scaleField);
        extensions.addWidget(label(130, 48, "output_scale"));
        var outputField = new TextFieldWidget(210, 44, 50, 16, () -> outputScaleDraft, value -> {
            outputScaleDraft = value;
            extDirty = true;
            extAt = System.currentTimeMillis();
        }).setMaxStringLength(2).setTextColor(0xffeeeeee).setBackground(GuiTextures.DISPLAY);
        outputField.setClientSideWidget();
        outputField.setHoverTooltips(List.of(Component.translatable(PREFIX + "output_scale_hint")));
        configuration.add(outputField);
        extensions.addWidget(outputField);
        extensions.addWidget(label(10, 70, "min_tier"));
        extensions.addWidget(label(160, 70, "max_tier"));
        var minTier = selector(10, 82, 140, this::tierLabels, () -> tierLabel(settings.minTier), value -> {
            int index = tierLabels().indexOf(value);
            sendExt(index <= 0 ? PatternGeneratorFilter.ANY_TIER : index - 1, settings.maxTier, settings.sort, settings.skipOwned);
        });
        var maxTier = selector(160, 82, 140, this::tierLabels, () -> tierLabel(settings.maxTier), value -> {
            int index = tierLabels().indexOf(value);
            sendExt(settings.minTier, index <= 0 ? PatternGeneratorFilter.ANY_TIER : index - 1, settings.sort, settings.skipOwned);
        });
        configuration.add(minTier);
        configuration.add(maxTier);
        extensions.addWidget(label(10, 106, "sort"));
        var sort = selector(70, 102, 220, this::sortLabels, () -> sortLabels().get(PatternGeneratorFilter.sortMode(settings.sort)),
                value -> {
                    int index = sortLabels().indexOf(value);
                    if (index >= 0) sendExt(settings.minTier, settings.maxTier, index, settings.skipOwned);
                });
        configuration.add(sort);
        extensions.addWidget(label(10, 128, "recipe_query"));
        var query = new TextFieldWidget(10, 140, 290, 16, () -> queryDraft, value -> {
            queryDraft = PatternGeneratorSettings.limit(value);
            extDirty = true;
            extAt = System.currentTimeMillis();
        }).setMaxStringLength(256).setTextColor(0xffeeeeee).setBackground(GuiTextures.DISPLAY);
        query.setClientSideWidget();
        query.setHoverTooltips(List.of(Component.translatable(PREFIX + "recipe_query_hint")));
        configuration.add(query);
        extensions.addWidget(query);
        var skip = button(10, 164, 140, 18, () -> tr(settings.skipOwned ? "skip_on" : "skip_off"),
                () -> sendExt(settings.minTier, settings.maxTier, settings.sort, !settings.skipOwned));
        skip.setHoverTooltips(List.of(Component.translatable(PREFIX + "skip_hint")));
        configuration.add(skip);
        extensions.addWidget(skip);
        var reset = button(160, 164, 140, 18, () -> tr("reset_extensions"), () -> {
            scaleDraft = "1";
            outputScaleDraft = "1";
            queryDraft = "";
            extDirty = false;
            sendExt(PatternGeneratorFilter.ANY_TIER, PatternGeneratorFilter.ANY_TIER, PatternGeneratorFilter.SORT_ID, false);
        });
        configuration.add(reset);
        extensions.addWidget(reset);
        extensions.addWidget(center(10, 190, 290, 16, () -> tr("extensions_hint")));
        extensions.addWidget(minTier);
        extensions.addWidget(maxTier);
        extensions.addWidget(sort);
    }

    private List<String> filterLabels(boolean input, boolean exclude, int slot) {
        var result = new ArrayList<String>();
        result.add(tr("include_exact"));
        PatternGeneratorSettings.tags(settings.filterSlots(input, exclude)[slot]).forEach(tag -> result.add("#" + tag));
        return result;
    }

    private String filterLabel(boolean input, boolean exclude, int slot) {
        if (settings.filterSlots(input, exclude)[slot] == null) return tr("filter_slot_empty", slot + 1);
        String tag = settings.filterTags(input, exclude)[slot];
        return tr("filter_slot_label", slot + 1, tag.isEmpty() ? tr("include_exact") : "#" + tag);
    }

    private void buildCircuits() {
        circuits.addWidget(label(10, 28, "choose_circuit"));
        circuits.addWidget(button(214, 24, 86, 18, () -> tr("back_main"), () -> show(main)));
        circuits.addWidget(button(10, 46, 140, 18, () -> tr("circuit_any"), () -> circuit(PatternGeneratorFilter.ANY_CIRCUIT)));
        circuits.addWidget(button(160, 46, 140, 18, () -> tr("circuit_none"), () -> circuit(0)));
        for (int value = 1; value <= 32; value++) {
            int chosen = value;
            var choice = button(10 + ((value - 1) % 8) * 37, 70 + ((value - 1) / 8) * 19, 31, 18,
                    () -> (settings.circuit == chosen ? "[" : "") + chosen + (settings.circuit == chosen ? "]" : ""),
                    () -> circuit(chosen));
            configuration.add(choice);
            circuits.addWidget(choice);
        }
        circuits.addWidget(center(10, 148, 290, 10, () -> tr("circuit_hint")));
    }

    private void bindInventory() {
        int startX = (WIDTH - 162) / 2;
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 9; column++) {
                inventory.addWidget(new SlotWidget(holder.getPlayer().getInventory(), 9 + row * 9 + column,
                        startX + column * 18, 160 + row * 18).setBackgroundTexture(GuiTextures.SLOT));
            }
        }
        for (int column = 0; column < 9; column++) {
            inventory.addWidget(new SlotWidget(holder.getPlayer().getInventory(), column,
                    startX + column * 18, 218).setBackgroundTexture(GuiTextures.SLOT));
        }
    }

    private static LabelWidget label(int x, int y, String key) {
        var label = new LabelWidget(x, y, PREFIX + key).setTextColor(0xff303030).setDropShadow(false);
        label.setClientSideWidget();
        return label;
    }

    private static ImageWidget text(int x, int y, int width, int height, Supplier<String> supplier) {
        var image = new ImageWidget(x, y, width, height, new TextTexture(supplier).setWidth(width)
                .setType(TextTexture.TextType.LEFT_HIDE).setColor(0xff303030).setDropShadow(false));
        image.setClientSideWidget();
        return image;
    }

    private static ImageWidget center(int x, int y, int width, int height, Supplier<String> supplier) {
        var image = new ImageWidget(x, y, width, height, new TextTexture(supplier).setWidth(width)
                .setType(TextTexture.TextType.HIDE).setColor(0xff303030).setDropShadow(false));
        image.setClientSideWidget();
        return image;
    }

    private static ImageWidget panel(int x, int y, int width, int height) {
        var image = new ImageWidget(x, y, width, height, GuiTextures.BACKGROUND_INVERSE);
        image.setClientSideWidget();
        return image;
    }

    private ButtonWidget button(int x, int y, int width, int height, Supplier<String> title, Runnable clicked) {
        var button = new ButtonWidget(x, y, width, height, new GuiTextureGroup(GuiTextures.VANILLA_BUTTON,
                new TextTexture(title).setWidth(width - 6).setType(TextTexture.TextType.HIDE).setColor(0xff303030).setDropShadow(false)),
                click -> {
                    if (click.isRemote) clicked.run();
                });
        button.setClientSideWidget();
        return button;
    }

    private SelectorWidget selector(int x, int y, int width, Supplier<List<String>> candidates,
                                    Supplier<String> current, Consumer<String> changed) {
        var selector = new SelectorWidget(x, y, width, 18, List.of(), 6)
                .setButtonBackground(GuiTextures.VANILLA_BUTTON)
                .setBackground(GuiTextures.BACKGROUND)
                .setFontColor(0xff303030)
                .setCandidatesSupplier(candidates)
                .setSupplier(current)
                .setOnChanged(value -> {
                    if (holder.isRemote()) changed.accept(value);
                });
        selector.setClientSideWidget();
        return selector;
    }

    private static String tr(String suffix, Object... arguments) {
        return Component.translatable(PREFIX + suffix, arguments).getString();
    }

    private void show(WidgetGroup view) {
        for (WidgetGroup group : List.of(main, filters, circuits, extensions, previews, presets, savePreset)) {
            group.setVisible(group == view);
        }
        inventory.setVisible(view != previews && view != presets && view != filters && view != extensions);
    }

    private List<String> typeLabels() {
        return types.stream().map(type -> Component.translatable(PatternGeneratorRecipes.typeTranslation(type)).getString()
                + " (" + typeCounts.getOrDefault(type.registryName.toString(), 0) + ")").toList();
    }

    private String selectedTypeLabel() {
        for (int index = 0; index < types.size(); index++) {
            if (types.get(index).registryName.toString().equals(settings.recipeType)) return typeLabels().get(index);
        }
        return tr(settings.machine.isEmpty() ? "type_wait_machine" : "type_unavailable");
    }

    private String circuitLabel() {
        if (settings.circuit < 0) return tr("circuit_any");
        if (settings.circuit == 0) return tr("circuit_none");
        return tr("circuit_value", settings.circuit);
    }

    private List<String> tierLabels() {
        var labels = new ArrayList<String>();
        labels.add(tr("tier_any"));
        for (String name : GTValues.VN) labels.add(name);
        return labels;
    }

    private String tierLabel(int tier) {
        if (tier < 0 || tier >= GTValues.VN.length) return tr("tier_any");
        return GTValues.VN[tier];
    }

    private List<String> sortLabels() {
        return List.of(tr("sort_id"), tr("sort_eut_asc"), tr("sort_eut_desc"), tr("sort_duration"));
    }

    private String generationLabel() {
        if (running) return tr("generating", generated, pendingCount);
        if (pendingCount > 0) return tr("resume", pendingCount);
        if (extDirty && (tryParse(scaleDraft) == null || tryParse(outputScaleDraft) == null)) return tr("scale_invalid");
        if (!ready || whiteDirty || extDirty) return tr("filtering");
        return tr("generate_selected", selected);
    }

    private void updateControls() {
        boolean editable = pendingCount == 0 && queuedTransfers == 0;
        machineSlot.setActive(editable);
        for (Widget widget : configuration) widget.setActive(editable);
        typeSelector.setActive(editable && !types.isEmpty());
        boolean scaleValid = tryParse(scaleDraft) != null && tryParse(outputScaleDraft) != null;
        generationButton.setActive(!running && queuedTransfers == 0 && ready && !whiteDirty && !extDirty
                && scaleValid && (selected > 0 || pendingCount > 0));
        bundleButton.setActive(!running && pendingCount == 0 && queuedTransfers == 0 && ready
                && !whiteDirty && !extDirty && scaleValid && selected > 0);
        selectAllButton.setActive(editable && ready && count > 0);
        previousButton.setActive(ready && page > 0);
        nextButton.setActive(ready && page + 1 < PatternGeneratorLayout.pages(count, settings.previewColumns));
        cancelButton.setVisible(pendingCount > 0 || queuedTransfers > 0);
        cancelButton.setActive(pendingCount > 0 || queuedTransfers > 0);
        boolean canFill = !running && pendingCount == 0 && ready && !whiteDirty && !extDirty && scaleValid
                && (availablePatterns > 0 || selected > 0 || queuedTransfers > 0);
        fillButton.setActive(canFill);
        mainFillButton.setActive(canFill);
        previewButton.setActive(!types.isEmpty());
        applyPresetButton.setActive(editable && chosenPreset != null && inspectedAvailable > 0);
        savePresetButton.setActive(editable && ready && !whiteDirty && !extDirty && scaleValid
                && !presetName.isBlank() && selected > 0 && !saveRequested);
        generationButton.setHoverTooltips(List.of(Component.translatable(PREFIX + "generate_hint")));
        bundleButton.setHoverTooltips(List.of(Component.translatable(PREFIX + "bundle_hint")));
    }

    private List<Component> previewDetails(Preview value) {
        var lines = new ArrayList<Component>();
        lines.add(Component.translatable(PREFIX + "preview_info", value.tier(), value.eut(),
                value.circuit() == 0 ? tr("circuit_none") : tr("circuit_value", value.circuit()), value.duration()));
        if (settings.scale != 1 || settings.outputScale != 1) {
            lines.add(Component.translatable(PREFIX + "lore_scale", settings.scale,
                    PatternGeneratorScale.outputFactor(settings.scale, settings.outputScale)));
        }
        if (value.overflow()) lines.add(Component.translatable(PREFIX + "scale_overflow"));
        lines.add(Component.translatable(PREFIX + "preview_inputs"));
        for (var stack : value.inputs()) lines.add(stack.what().getDisplayName().copy().append(" × " + stack.amount()));
        lines.add(Component.translatable(PREFIX + "preview_outputs"));
        for (var stack : value.outputs()) lines.add(stack.what().getDisplayName().copy().append(" × " + stack.amount()));
        lines.add(Component.literal(value.id()));
        return lines;
    }

    private void rebuildGrid() {
        int scroll = grid.getScrollYOffset();
        grid.clearAllWidgets();
        int cellWidth = 284 / settings.previewColumns;
        for (int i = 0; i < previewPage.size(); i++) {
            Preview value = previewPage.get(i);
            int x = 2 + PatternGeneratorLayout.column(i) * cellWidth;
            int y = 2 + PatternGeneratorLayout.row(i) * 18;
            var cell = recipeCell(value, x, y, cellWidth - 2, true);
            cell.setId("shanhai_pattern_generator.recipe." + i);
            grid.addWidget(cell);
        }
        if (previewPage.isEmpty()) {
            grid.addWidget(center(8, 40, 270, 34, () -> tr(!ready || whiteDirty || extDirty ? "filtering" : "no_matches")));
        }
        int contentHeight = 1 + Math.min(PatternGeneratorLayout.ROWS, previewPage.size()) * 18;
        grid.setScrollYOffset(Math.min(scroll, Math.max(0, contentHeight - grid.getSize().height)));
    }

    private ButtonWidget recipeCell(Preview value, int x, int y, int width, boolean selectable) {
        ItemStack icon = value.outputs().isEmpty() ? ItemStack.EMPTY : PatternGeneratorGhostWidget.display(value.outputs().get(0));
        var cell = new RecipeCell(x, y, width, value, icon, selectable);
        cell.setHoverTooltips(previewDetails(value));
        cell.setClientSideWidget();
        return cell;
    }

    private final class RecipeCell extends ButtonWidget {
        private final Preview value;
        private final ItemStackTexture iconTexture;
        private final TextTexture nameTexture;
        private final TextTexture checkTexture;
        private final boolean selectable;

        private RecipeCell(int x, int y, int width, Preview value, ItemStack icon, boolean selectable) {
            super(x, y, width, 17, new GuiTextureGroup(
                    new ColorRectTexture(value.checked() && selectable ? 0xffb7c7b7 : 0xffd3d3dc),
                    new ColorBorderTexture(1, value.checked() && selectable ? 0xff477447 : 0xff9696a5)), click -> {});
            this.value = value;
            this.selectable = selectable;
            iconTexture = new ItemStackTexture(icon);
            nameTexture = new TextTexture(value.outputs().isEmpty() ? "" : value.outputs().get(0).what().getDisplayName().getString())
                    .setWidth(Math.max(1, width - 30)).setType(TextTexture.TextType.LEFT_HIDE).setColor(0xff303030).setDropShadow(false);
            checkTexture = new TextTexture("✓").setColor(0xff265226).setDropShadow(false);
            setOnPressCallback(click -> {
                if (click.isRemote && selectable && pendingCount == 0 && ready) {
                    send(SELECT, buffer -> {
                        buffer.writeVarInt(revision);
                        buffer.writeUtf(value.id(), 256);
                    });
                }
            });
        }

        @Override
        public void drawInBackground(net.minecraft.client.gui.GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
            super.drawInBackground(graphics, mouseX, mouseY, partialTicks);
            iconTexture.draw(graphics, mouseX, mouseY, getPosition().x + 1, getPosition().y, 16, 16);
            if (getSize().width >= 64 && !value.outputs().isEmpty()) {
                nameTexture.draw(graphics, mouseX, mouseY, getPosition().x + 19, getPosition().y, getSize().width - 30, 17);
            }
            if (selectable && value.checked()) {
                checkTexture.draw(graphics, mouseX, mouseY, getPosition().x + getSize().width - 9, getPosition().y + 3, 8, 11);
            }
        }
    }

    private void openPresets() {
        if (!holder.isRemote()) return;
        show(presets);
        presetRequest++;
        presetList.clearAllWidgets();
        chosenPreset = null;
        inspectedAvailable = 0;
        inspectedMissing = 0;
        inspectedMachine = "";
        inspectedMode = "";
        presetMessage = "";
        presetGrid.clearAllWidgets();
        try {
            var listing = PatternGeneratorClientHooks.get().list();
            int index = 0;
            for (var preset : listing.presets()) {
                var row = button(0, index++ * 28, 118, 26, () -> preset.name(), () -> {
                    chosenPreset = preset;
                    inspectedAvailable = 0;
                    inspectedMissing = 0;
                    presetMessage = "";
                    inspectedMachine = "";
                    inspectedMode = "";
                    presetGrid.clearAllWidgets();
                    submitPreset(INSPECT_PRESET);
                });
                row.setHoverTooltips(List.of(Component.literal(preset.name()),
                        Component.translatable(PREFIX + "preset_author", preset.author()),
                        Component.literal(preset.savedAt())));
                presetList.addWidget(row);
            }
            if (listing.presets().isEmpty()) presetList.addWidget(center(3, 10, 114, 40, () -> tr("preset_empty")));
            if (listing.unreadable() > 0) presetMessage = tr("preset_invalid", listing.unreadable());
        } catch (Exception error) {
            presetError(error);
        }
    }

    private void openPresetDirectory() {
        if (!holder.isRemote()) return;
        try {
            PatternGeneratorClientHooks.get().openDirectory();
        } catch (Exception error) {
            presetError(error);
        }
    }

    private void presetError(Exception error) {
        presetMessage = tr("preset_error");
        GTDishanhaiMod.LOGGER.warn("Pattern generator preset operation failed", error);
    }

    private void submitPreset(int action) {
        if (chosenPreset == null) return;
        try {
            CompoundTag config = TagParser.parseTag(chosenPreset.configuration());
            int request = ++presetRequest;
            send(action, buffer -> {
                buffer.writeVarInt(request);
                buffer.writeNbt(config);
                buffer.writeVarInt(chosenPreset.recipes().size());
                chosenPreset.recipes().forEach(id -> buffer.writeUtf(id, 256));
            });
            if (action == APPLY_PRESET) {
                whiteDirty = false;
                extDirty = false;
                show(previews);
            }
        } catch (Exception error) {
            presetError(error);
        }
    }

    private void send(int action, Consumer<FriendlyByteBuf> writer) {
        if (holder.isRemote()) writeClientAction(action, writer);
    }

    private void page(int direction) {
        send(PAGE, buffer -> {
            buffer.writeVarInt(revision);
            buffer.writeVarInt(direction);
        });
    }

    private void circuit(int value) {
        send(CIRCUIT, buffer -> buffer.writeVarInt(value));
        show(main);
    }

    private void clearFilters() {
        inputDraft = "";
        outputDraft = "";
        whiteDirty = false;
        send(CLEAR, buffer -> {});
    }

    private boolean flushDrafts() {
        boolean blocked = flushWhite();
        if (flushExt()) blocked = true;
        return blocked;
    }

    private boolean flushWhite() {
        if (!whiteDirty) return false;
        var input = PatternGeneratorClientHooks.get().find(inputDraft);
        var output = PatternGeneratorClientHooks.get().find(outputDraft);
        send(WHITE, buffer -> {
            buffer.writeUtf(inputDraft, 256);
            buffer.writeUtf(outputDraft, 256);
            writeBits(buffer, input.items());
            writeBits(buffer, input.fluids());
            writeBits(buffer, output.items());
            writeBits(buffer, output.fluids());
        });
        whiteDirty = false;
        ready = false;
        updateControls();
        return true;
    }

    private boolean flushExt() {
        if (!extDirty) return false;
        if (tryParse(scaleDraft) == null || tryParse(outputScaleDraft) == null) return true;
        sendExt(settings.minTier, settings.maxTier, settings.sort, settings.skipOwned);
        return true;
    }

    private void sendExt(int minTier, int maxTier, int sort, boolean skip) {
        Integer scale = tryParse(scaleDraft);
        Integer outputScale = tryParse(outputScaleDraft);
        int scaleValue = scale == null ? settings.scale : scale;
        int outputValue = outputScale == null ? settings.outputScale : outputScale;
        extDirty = scale == null || outputScale == null;
        send(EXT, buffer -> {
            buffer.writeVarInt(scaleValue);
            buffer.writeVarInt(outputValue);
            buffer.writeVarInt(minTier);
            buffer.writeVarInt(maxTier);
            buffer.writeVarInt(sort);
            buffer.writeBoolean(skip);
            buffer.writeUtf(PatternGeneratorSettings.limit(queryDraft), 256);
        });
        ready = false;
        updateControls();
    }

    private static Integer tryParse(String text) {
        if (text == null) return null;
        try {
            int value = Integer.parseInt(text.strip());
            if (value < 1 || value > PatternGeneratorScale.MAX_FACTOR) return null;
            return value;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        long now = System.currentTimeMillis();
        if (whiteDirty && now - editAt >= 250) flushWhite();
        if (extDirty && now - extAt >= 250) flushExt();
        updateControls();
    }

    private void refreshMachine() {
        types = PatternGeneratorRecipes.machineTypes(settings.machine);
        if (types.stream().noneMatch(type -> type.registryName.toString().equals(settings.recipeType))) {
            settings.recipeType = types.isEmpty() ? "" : types.get(0).registryName.toString();
        }
        if (holder.getPlayer() instanceof ServerPlayer player) {
            typeCounts.clear();
            for (GTRecipeType type : types) {
                typeCounts.put(type.registryName.toString(), player.server.getRecipeManager().getAllRecipesFor(type).size());
            }
            GTRecipeType type = types.stream().filter(candidate -> candidate.registryName.toString().equals(settings.recipeType))
                    .findFirst().orElse(null);
            all = type == null ? List.of() : PatternGeneratorRecipes.recipes(player, type);
        }
    }

    private void setMachine(GenericStack machine) {
        if (holder.isRemote() || pendingCount > 0 || queuedTransfers > 0) return;
        settings.machine = machine == null ? ItemStack.EMPTY : ((AEItemKey) machine.what()).toStack(1);
        settings.recipeType = "";
        refreshMachine();
        rebuild(true);
        save();
        push();
    }

    private void setFilter(boolean input, boolean exclude, int slot, GenericStack stack) {
        if (holder.isRemote() || pendingCount > 0 || queuedTransfers > 0 || slot < 0 || slot >= PatternGeneratorSettings.FILTER_SLOTS) return;
        settings.filterSlots(input, exclude)[slot] = stack;
        settings.filterTags(input, exclude)[slot] = "";
        page = 0;
        rebuild(true);
        save();
        push();
    }

    private void rebuild(boolean resetSelection) {
        matching = ready ? all.stream().filter(entry -> entry.matches(settings))
                .sorted(PatternGeneratorRecipes.comparator(settings.sort)).toList() : List.of();
        if (resetSelection) unchecked.clear();
        if (!settings.pending.isEmpty()) {
            Set<String> queued = Set.copyOf(settings.pending);
            unchecked.clear();
            for (var entry : matching) {
                if (!queued.contains(entry.id())) unchecked.add(entry.id());
            }
        }
        if (ready && restoredSelection != null) {
            unchecked.clear();
            for (var entry : matching) {
                if (!restoredSelection.contains(entry.id())) unchecked.add(entry.id());
            }
            restoredSelection = null;
        }
        count = matching.size();
        selected = (int) matching.stream().filter(entry -> !unchecked.contains(entry.id())).count();
        pendingCount = settings.pending.size();
        queuedTransfers = settings.transferRecipes.size();
        if (queuedTransfers > 0) {
            matching.forEach(entry -> unchecked.add(entry.id()));
            selected = 0;
        }
        page = PatternGeneratorLayout.page(page, count, settings.previewColumns);
        revision++;
        feedback = Component.empty();
        refreshPreview();
    }

    private void refreshPreview() {
        int start = page * PatternGeneratorLayout.capacity(settings.previewColumns);
        previewPage = matching.stream().skip(start).limit(PatternGeneratorLayout.capacity(settings.previewColumns))
                .map(entry -> preview(entry, !unchecked.contains(entry.id()))).toList();
    }

    private Preview preview(PatternGeneratorRecipes.Entry entry, boolean checked) {
        boolean[] overflow = new boolean[1];
        List<GenericStack> inputs = scaleStacks(entry.encodedInputs(), PatternGeneratorScale.clamp(settings.scale), overflow);
        List<GenericStack> outputs = scaleStacks(entry.encodedOutputs(),
                PatternGeneratorScale.outputFactor(settings.scale, settings.outputScale), overflow);
        return new Preview(entry.id(), entry.circuit(), RecipeHelper.getInputEUt(entry.recipe()), entry.recipe().duration,
                GTValues.VN[entry.tier()], inputs, outputs, checked, overflow[0]);
    }

    private static List<GenericStack> scaleStacks(List<GenericStack> stacks, int factor, boolean[] overflow) {
        try {
            return PatternGeneratorRecipes.scaled(stacks, factor);
        } catch (ArithmeticException ignored) {
            overflow[0] = true;
            return stacks;
        }
    }

    private void save() {
        if (holder.isRemote() || holder.isInvalid()) return;
        holder.getHeld().getOrCreateTag().put(PatternGeneratorSettings.TAG, settings.write());
        holder.markAsDirty();
        holder.getPlayer().getInventory().setChanged();
    }

    private void push() {
        if (!holder.isRemote()) writeUpdateInfo(STATE, this::writeState);
    }

    @Override
    public void writeInitialData(FriendlyByteBuf buffer) {
        super.writeInitialData(buffer);
        writeState(buffer);
    }

    @Override
    public void readInitialData(FriendlyByteBuf buffer) {
        super.readInitialData(buffer);
        readState(buffer);
        inputDraft = settings.inputWhite;
        outputDraft = settings.outputWhite;
        if (!inputDraft.isBlank() || !outputDraft.isBlank()) {
            whiteDirty = true;
            editAt = System.currentTimeMillis() - 250;
        }
    }

    @Override
    public void detectAndSendChanges() {
        super.detectAndSendChanges();
        if (running && !holder.isInvalid()) generateBatch();
        if (!holder.isInvalid() && holder.getPlayer() instanceof ServerPlayer player) {
            long tick = player.serverLevel().getGameTime();
            if (lastAvailableCheck < 0 || tick - lastAvailableCheck >= 20) {
                lastAvailableCheck = tick;
                int available = PatternGeneratorTransfer.available(player, settings.generatorId);
                if (availablePatterns != available) {
                    availablePatterns = available;
                    push();
                }
            }
        }
    }

    @Override
    public void readUpdateInfo(int id, FriendlyByteBuf buffer) {
        if (id == STATE) readState(buffer);
        else if (id == PRESET_FILE) receivePresetFile(buffer);
        else if (id == PRESET_PREVIEW) receivePresetPreview(buffer);
        else super.readUpdateInfo(id, buffer);
    }

    private void writeState(FriendlyByteBuf buffer) {
        var tag = settings.write();
        tag.remove("pending");
        tag.remove("transferRecipes");
        tag.remove("transferConfiguration");
        tag.putInt("transferCount", queuedTransfers);
        buffer.writeNbt(tag);
        buffer.writeVarInt(revision);
        buffer.writeVarInt(page);
        buffer.writeVarInt(count);
        buffer.writeVarInt(selected);
        buffer.writeVarInt(pendingCount);
        buffer.writeVarInt(generated);
        buffer.writeBoolean(running);
        buffer.writeBoolean(ready);
        buffer.writeVarInt(availablePatterns);
        writeText(buffer, feedback);
        buffer.writeVarInt(typeCounts.size());
        for (var type : typeCounts.entrySet()) {
            buffer.writeUtf(type.getKey(), 256);
            buffer.writeVarInt(type.getValue());
        }
        buffer.writeVarInt(previewPage.size());
        for (Preview value : previewPage) writePreview(buffer, value);
    }

    private void readState(FriendlyByteBuf buffer) {
        String oldInput = settings.inputWhite;
        String oldOutput = settings.outputWhite;
        var tag = buffer.readNbt();
        if (tag != null) settings.read(tag);
        queuedTransfers = tag == null ? 0 : tag.getInt("transferCount");
        if (!whiteDirty && (!oldInput.equals(settings.inputWhite) || !oldOutput.equals(settings.outputWhite))) {
            inputDraft = settings.inputWhite;
            outputDraft = settings.outputWhite;
        }
        if (!extDirty) {
            scaleDraft = Integer.toString(settings.scale);
            outputScaleDraft = Integer.toString(settings.outputScale);
            queryDraft = settings.recipeQuery;
        }
        types = PatternGeneratorRecipes.machineTypes(settings.machine);
        revision = buffer.readVarInt();
        page = buffer.readVarInt();
        count = buffer.readVarInt();
        selected = buffer.readVarInt();
        pendingCount = buffer.readVarInt();
        generated = buffer.readVarInt();
        running = buffer.readBoolean();
        ready = buffer.readBoolean();
        availablePatterns = buffer.readVarInt();
        feedback = readText(buffer);
        typeCounts.clear();
        int size = readSize(buffer, 4096);
        for (int i = 0; i < size; i++) typeCounts.put(buffer.readUtf(256), buffer.readVarInt());
        var values = new ArrayList<Preview>();
        size = readSize(buffer, PatternGeneratorLayout.capacity(PatternGeneratorLayout.MAX_COLUMNS));
        for (int i = 0; i < size; i++) values.add(readPreview(buffer));
        previewPage = List.copyOf(values);
        if (!ready && !whiteDirty && !extDirty) {
            whiteDirty = !settings.inputWhite.isBlank() || !settings.outputWhite.isBlank();
            if (whiteDirty) editAt = System.currentTimeMillis() - 250;
        }
        if (holder.isRemote()) rebuildGrid();
        updateControls();
    }

    private static void writePreview(FriendlyByteBuf buffer, Preview value) {
        buffer.writeUtf(value.id(), 256);
        buffer.writeVarInt(value.circuit());
        buffer.writeLong(value.eut());
        buffer.writeVarInt(value.duration());
        buffer.writeUtf(value.tier(), 32);
        buffer.writeBoolean(value.checked());
        buffer.writeBoolean(value.overflow());
        writeStacks(buffer, value.inputs());
        writeStacks(buffer, value.outputs());
    }

    private static Preview readPreview(FriendlyByteBuf buffer) {
        String id = buffer.readUtf(256);
        int circuit = buffer.readVarInt();
        long eut = buffer.readLong();
        int duration = buffer.readVarInt();
        String tier = buffer.readUtf(32);
        boolean checked = buffer.readBoolean();
        boolean overflow = buffer.readBoolean();
        return new Preview(id, circuit, eut, duration, tier, readStacks(buffer), readStacks(buffer), checked, overflow);
    }

    private static int readSize(FriendlyByteBuf buffer, int maximum) {
        int size = buffer.readVarInt();
        if (size < 0 || size > maximum) throw new IllegalArgumentException("Pattern generator packet size out of bounds");
        return size;
    }

    private static void writeStacks(FriendlyByteBuf buffer, List<GenericStack> stacks) {
        buffer.writeVarInt(stacks.size());
        for (var stack : stacks) buffer.writeNbt(GenericStack.writeTag(stack));
    }

    private static List<GenericStack> readStacks(FriendlyByteBuf buffer) {
        int size = readSize(buffer, 4096);
        var result = new ArrayList<GenericStack>(size);
        for (int i = 0; i < size; i++) {
            var tag = buffer.readNbt();
            var stack = tag == null ? null : GenericStack.readTag(tag);
            if (stack != null) result.add(stack);
        }
        return List.copyOf(result);
    }

    private static void writeText(FriendlyByteBuf buffer, Component component) {
        buffer.writeUtf(Component.Serializer.toJson(component == null ? Component.empty() : component), 32767);
    }

    private static Component readText(FriendlyByteBuf buffer) {
        Component parsed = Component.Serializer.fromJson(buffer.readUtf(32767));
        return parsed == null ? Component.empty() : parsed;
    }

    private static void writeBits(FriendlyByteBuf buffer, BitSet bits) {
        long[] data = bits.toLongArray();
        buffer.writeVarInt(data.length);
        for (long word : data) buffer.writeLong(word);
    }

    private static BitSet readBits(FriendlyByteBuf buffer, int size) {
        int words = buffer.readVarInt();
        int maximum = (Math.max(0, size) + 63) / 64;
        if (words < 0 || words > maximum) throw new IllegalArgumentException("Pattern generator bitset out of bounds");
        long[] data = new long[words];
        for (int i = 0; i < words; i++) data[i] = buffer.readLong();
        BitSet bits = BitSet.valueOf(data);
        if (bits.length() > size) bits.clear(size, bits.length());
        return bits;
    }

    @Override
    public void handleClientAction(int id, FriendlyByteBuf buffer) {
        if (id < TYPE || id > BUNDLE) {
            super.handleClientAction(id, buffer);
            return;
        }
        if (holder.isRemote() || holder.isInvalid()) return;
        if (id == EXPORT_PRESET) {
            exportPreset(buffer);
            return;
        }
        if (id == APPLY_PRESET || id == INSPECT_PRESET) {
            importPreset(id, buffer);
            return;
        }
        if (id == COLUMNS) {
            settings.previewColumns = PatternGeneratorLayout.columns(buffer.readVarInt());
            page = 0;
            refreshPreview();
            save();
            push();
            return;
        }
        if (id == FILL) {
            int expected = buffer.readVarInt();
            if (running || pendingCount > 0 || expected != revision || !ready || !(holder.getPlayer() instanceof ServerPlayer player)) {
                push();
                return;
            }
            if (settings.transferRecipes.isEmpty() && selected > 0) {
                var chosen = matching.stream().filter(entry -> !unchecked.contains(entry.id())).toList();
                if (chosen.size() > PatternGeneratorPresetFiles.MAX_RECIPES) {
                    feedback = Component.translatable(PREFIX + "fill_too_many", PatternGeneratorPresetFiles.MAX_RECIPES);
                    push();
                    return;
                }
                settings.transferConfiguration = settings.configuration();
                for (var entry : chosen) {
                    settings.transferRecipes.add(entry.id());
                    unchecked.add(entry.id());
                }
                if (settings.generatorId == null) settings.generatorId = UUID.randomUUID();
                queuedTransfers = settings.transferRecipes.size();
                selected = 0;
            }
            availablePatterns = PatternGeneratorTransfer.available(player, settings.generatorId);
            if (availablePatterns == 0 && queuedTransfers == 0) {
                push();
                return;
            }
            settings.transferArmed = true;
            save();
            player.closeContainer();
            player.displayClientMessage(Component.translatable(PREFIX + "fill_hint"), true);
            return;
        }
        if (id == CANCEL) {
            settings.pending.clear();
            pendingCount = 0;
            running = false;
            settings.transferRecipes.clear();
            settings.transferConfiguration = new CompoundTag();
            queuedTransfers = 0;
            settings.transferArmed = false;
            if (holder.getPlayer() instanceof ServerPlayer player) PatternGeneratorTransfer.cancel(player);
            feedback = Component.translatable(PREFIX + "cancelled");
            save();
            push();
            return;
        }
        if (id == WHITE) {
            String input = buffer.readUtf(256);
            String output = buffer.readUtf(256);
            BitSet inputItems = readBits(buffer, BuiltInRegistries.ITEM.size());
            BitSet inputFluids = readBits(buffer, BuiltInRegistries.FLUID.size());
            BitSet outputItems = readBits(buffer, BuiltInRegistries.ITEM.size());
            BitSet outputFluids = readBits(buffer, BuiltInRegistries.FLUID.size());
            if ((pendingCount > 0 || queuedTransfers > 0)
                    && (!input.equals(settings.inputWhite) || !output.equals(settings.outputWhite))) {
                push();
                return;
            }
            settings.inputWhite = input;
            settings.outputWhite = output;
            copyBits(settings.inputNamedItems, inputItems);
            copyBits(settings.inputNamedFluids, inputFluids);
            copyBits(settings.outputNamedItems, outputItems);
            copyBits(settings.outputNamedFluids, outputFluids);
            ready = true;
            rebuild(true);
            save();
            push();
            return;
        }
        if (id == EXT) {
            int scale = PatternGeneratorScale.clamp(buffer.readVarInt());
            int outputScale = PatternGeneratorScale.clamp(buffer.readVarInt());
            int minTier = PatternGeneratorSettings.tier(buffer.readVarInt());
            int maxTier = PatternGeneratorSettings.tier(buffer.readVarInt());
            int sort = PatternGeneratorFilter.sortMode(buffer.readVarInt());
            boolean skip = buffer.readBoolean();
            String query = PatternGeneratorSettings.limit(buffer.readUtf(256));
            if (pendingCount > 0 || queuedTransfers > 0) {
                push();
                return;
            }
            if (minTier >= 0 && maxTier >= 0 && minTier > maxTier) {
                int swap = minTier;
                minTier = maxTier;
                maxTier = swap;
            }
            settings.scale = scale;
            settings.outputScale = outputScale;
            settings.minTier = minTier;
            settings.maxTier = maxTier;
            settings.sort = sort;
            settings.skipOwned = skip;
            settings.recipeQuery = query;
            ready = true;
            page = 0;
            rebuild(true);
            save();
            push();
            return;
        }
        if (id == BUNDLE) {
            generateBundle(buffer);
            return;
        }
        if (id == GENERATE) {
            int expected = buffer.readVarInt();
            if (running || queuedTransfers > 0 || expected != revision || !ready) {
                push();
                return;
            }
            if (settings.pending.isEmpty()) {
                for (var entry : matching) {
                    if (!unchecked.contains(entry.id())) settings.pending.add(entry.id());
                }
            }
            if (!settings.pending.isEmpty()) {
                generated = 0;
                failed = 0;
                skipped = 0;
                running = true;
                pendingCount = settings.pending.size();
                feedback = Component.empty();
                save();
                push();
            }
            return;
        }
        if (id == PAGE) {
            int expected = buffer.readVarInt();
            int direction = buffer.readVarInt();
            if (expected == revision && Math.abs(direction) == 1) {
                page = PatternGeneratorLayout.page(page + direction, count, settings.previewColumns);
                refreshPreview();
            }
            push();
            return;
        }
        if (pendingCount > 0 || queuedTransfers > 0) {
            push();
            return;
        }
        if (id == TYPE) {
            String chosen = buffer.readUtf(256);
            if (types.stream().anyMatch(type -> type.registryName.toString().equals(chosen))) {
                settings.recipeType = chosen;
                page = 0;
                refreshMachine();
                rebuild(true);
                save();
            }
        } else if (id == INCLUDE_TAG) {
            boolean input = buffer.readBoolean();
            boolean exclude = buffer.readBoolean();
            int slot = buffer.readVarInt();
            String tag = buffer.readUtf(256);
            if (slot >= 0 && slot < PatternGeneratorSettings.FILTER_SLOTS) {
                settings.filterTags(input, exclude)[slot] = PatternGeneratorSettings.validTag(
                        settings.filterSlots(input, exclude)[slot], tag);
                page = 0;
                rebuild(true);
                save();
            }
        } else if (id == CIRCUIT) {
            int chosen = buffer.readVarInt();
            if (chosen >= PatternGeneratorFilter.ANY_CIRCUIT && chosen <= 32) {
                settings.circuit = chosen;
                page = 0;
                rebuild(true);
                save();
            }
        } else if (id == CLEAR) {
            settings.clearFilters();
            ready = true;
            rebuild(true);
            save();
        } else if (id == SELECT) {
            int expected = buffer.readVarInt();
            String chosen = buffer.readUtf(256);
            if (expected == revision && matching.stream().anyMatch(entry -> entry.id().equals(chosen))) {
                if (!unchecked.remove(chosen)) unchecked.add(chosen);
                selected = (int) matching.stream().filter(entry -> !unchecked.contains(entry.id())).count();
                refreshPreview();
            }
        } else if (id == SELECT_ALL) {
            int expected = buffer.readVarInt();
            boolean checked = buffer.readBoolean();
            if (expected == revision) {
                unchecked.clear();
                if (!checked) matching.forEach(entry -> unchecked.add(entry.id()));
                selected = checked ? count : 0;
                refreshPreview();
            }
        }
        push();
    }

    private void exportPreset(FriendlyByteBuf buffer) {
        int expected = buffer.readVarInt();
        String name = buffer.readUtf(48).strip();
        if (!(holder.getPlayer() instanceof ServerPlayer player)) return;
        if (expected != revision || !ready || pendingCount > 0 || selected <= 0 || name.isEmpty()) {
            writeUpdateInfo(PRESET_FILE, output -> output.writeBoolean(false));
            return;
        }
        var ids = matching.stream().filter(entry -> !unchecked.contains(entry.id())).map(PatternGeneratorRecipes.Entry::id).toList();
        var config = settings.configuration();
        try {
            var preset = PatternGeneratorPresetFiles.create(name, player.getName().getString(),
                    player.getUUID().toString(), config.toString(), ids);
            PatternGeneratorPresetFiles.decode(PatternGeneratorPresetFiles.encode(preset));
            writeUpdateInfo(PRESET_FILE, output -> {
                output.writeBoolean(true);
                output.writeUtf(preset.name(), 48);
                output.writeUtf(preset.author(), 64);
                output.writeUtf(preset.authorId(), 64);
                output.writeUtf(preset.savedAt(), 64);
                output.writeNbt(config);
                writeIds(output, ids);
            });
        } catch (RuntimeException invalid) {
            writeUpdateInfo(PRESET_FILE, output -> output.writeBoolean(false));
        }
    }

    private void receivePresetFile(FriendlyByteBuf buffer) {
        boolean requested = saveRequested;
        saveRequested = false;
        if (!buffer.readBoolean()) {
            if (requested) presetMessage = tr("preset_save_rejected");
            return;
        }
        String name = buffer.readUtf(48);
        String author = buffer.readUtf(64);
        String uuid = buffer.readUtf(64);
        String date = buffer.readUtf(64);
        CompoundTag config = buffer.readNbt();
        List<String> ids = readIds(buffer);
        if (!holder.isRemote() || !requested || config == null) return;
        try {
            var safe = new PatternGeneratorSettings();
            safe.applyConfiguration(config);
            PatternGeneratorClientHooks.get().save(new PatternGeneratorPresetFiles.Preset(
                    name, author, uuid, date, safe.configuration().toString(), ids));
            presetMessage = tr("preset_saved");
        } catch (Exception error) {
            presetError(error);
        }
    }

    private static void writeIds(FriendlyByteBuf buffer, List<String> ids) {
        buffer.writeVarInt(ids.size());
        for (String id : ids) buffer.writeUtf(id, 256);
    }

    private static List<String> readIds(FriendlyByteBuf buffer) {
        int size = readSize(buffer, PatternGeneratorPresetFiles.MAX_RECIPES);
        var ids = new LinkedHashSet<String>();
        for (int i = 0; i < size; i++) {
            String id = buffer.readUtf(256);
            if (ResourceLocation.tryParse(id) == null) throw new IllegalArgumentException("Invalid preset recipe ID");
            ids.add(id);
        }
        return List.copyOf(ids);
    }

    private void importPreset(int action, FriendlyByteBuf buffer) {
        int request = 0;
        try {
            request = buffer.readVarInt();
            CompoundTag config = buffer.readNbt();
            List<String> ids = readIds(buffer);
            if (config == null || config.toString().length() > 65536 || ids.isEmpty()) {
                throw new IllegalArgumentException("Empty preset");
            }
            var candidate = new PatternGeneratorSettings();
            candidate.applyConfiguration(config);
            List<GTRecipeType> supported = PatternGeneratorRecipes.machineTypes(candidate.machine);
            GTRecipeType type = supported.stream().filter(entry -> entry.registryName.toString().equals(candidate.recipeType))
                    .findFirst().orElseThrow(() -> new IllegalArgumentException("Unsupported machine/mode in preset"));
            if (!(holder.getPlayer() instanceof ServerPlayer player)) return;
            Set<String> wanted = Set.copyOf(ids);
            List<PatternGeneratorRecipes.Entry> available = PatternGeneratorRecipes.recipes(player, type).stream()
                    .filter(entry -> wanted.contains(entry.id())).toList();
            if (action == INSPECT_PRESET) {
                int token = request;
                List<Preview> samples = available.stream().limit(150).map(entry -> preview(entry, false)).toList();
                writeUpdateInfo(PRESET_PREVIEW, output -> {
                    output.writeVarInt(token);
                    output.writeBoolean(true);
                    output.writeItem(candidate.machine);
                    output.writeUtf(PatternGeneratorRecipes.typeTranslation(type), 256);
                    output.writeVarInt(available.size());
                    output.writeVarInt(ids.size() - available.size());
                    output.writeVarInt(samples.size());
                    for (var value : samples) writePreview(output, value);
                });
            } else {
                if (pendingCount > 0 || queuedTransfers > 0 || running) {
                    push();
                    return;
                }
                settings.applyConfiguration(candidate.configuration());
                settings.inputNamedItems.clear();
                settings.inputNamedFluids.clear();
                settings.outputNamedItems.clear();
                settings.outputNamedFluids.clear();
                restoredSelection = wanted;
                ready = settings.inputWhite.isBlank() && settings.outputWhite.isBlank();
                page = 0;
                refreshMachine();
                rebuild(true);
                save();
                push();
            }
        } catch (RuntimeException invalid) {
            if (action == INSPECT_PRESET) {
                int token = request;
                writeUpdateInfo(PRESET_PREVIEW, output -> {
                    output.writeVarInt(token);
                    output.writeBoolean(false);
                });
            } else {
                feedback = Component.translatable(PREFIX + "preset_unavailable");
                push();
            }
        }
    }

    private void receivePresetPreview(FriendlyByteBuf buffer) {
        int request = buffer.readVarInt();
        boolean valid = buffer.readBoolean();
        if (!valid) {
            if (request == presetRequest) {
                inspectedAvailable = 0;
                presetMessage = tr("preset_unavailable");
            }
            return;
        }
        ItemStack machine = buffer.readItem();
        String mode = buffer.readUtf(256);
        int available = buffer.readVarInt();
        int missing = buffer.readVarInt();
        int size = readSize(buffer, 150);
        var samples = new ArrayList<Preview>();
        for (int i = 0; i < size; i++) samples.add(readPreview(buffer));
        if (!holder.isRemote() || request != presetRequest || chosenPreset == null) return;
        inspectedMachine = machine.getHoverName().getString();
        inspectedMode = Component.translatable(mode).getString();
        inspectedAvailable = available;
        inspectedMissing = missing;
        presetGrid.clearAllWidgets();
        for (int i = 0; i < samples.size(); i++) {
            presetGrid.addWidget(recipeCell(samples.get(i), i % 6 * 24, i / 6 * 19, 22, false));
        }
        presetGrid.setScrollYOffset(0);
        if (available > samples.size()) presetMessage = tr("preset_sample", samples.size());
        else if (missing > 0) presetMessage = tr("preset_missing");
        else presetMessage = "";
    }

    private static void copyBits(BitSet target, BitSet source) {
        target.clear();
        target.or(source);
    }

    private void generateBundle(FriendlyByteBuf buffer) {
        int expected = buffer.readVarInt();
        if (running || queuedTransfers > 0 || pendingCount > 0 || expected != revision || !ready
                || !(holder.getPlayer() instanceof ServerPlayer player)) {
            push();
            return;
        }
        var chosen = matching.stream().filter(entry -> !unchecked.contains(entry.id())).toList();
        if (chosen.isEmpty()) {
            feedback = Component.translatable(PREFIX + "bundle_empty");
            push();
            return;
        }
        if (chosen.size() > SelectedRecipePattern.MAX_RECIPES) {
            feedback = Component.translatable(PREFIX + "bundle_too_many", SelectedRecipePattern.MAX_RECIPES);
            push();
            return;
        }
        int scale = PatternGeneratorScale.clamp(settings.scale);
        int outputScale = PatternGeneratorScale.clamp(settings.outputScale);
        var ids = new ArrayList<String>(chosen.size());
        for (var entry : chosen) {
            try {
                if (entry.recipe().recipeType == null || entry.recipe().recipeType.registryName == null
                        || types.stream().noneMatch(type -> type == entry.recipe().recipeType)
                        || !entry.recipe().recipeType.registryName.toString().equals(settings.recipeType)) {
                    throw new IllegalArgumentException("Recipe no longer belongs to the selected machine mode");
                }
                PatternGeneratorRecipes.validate(entry, player);
                boolean[] overflow = new boolean[1];
                scaleStacks(entry.encodedInputs(), scale, overflow);
                scaleStacks(entry.encodedOutputs(), PatternGeneratorScale.outputFactor(scale, outputScale), overflow);
                if (overflow[0]) throw new ArithmeticException("pattern amount overflow");
                ids.add(entry.id());
            } catch (ArithmeticException overflow) {
                feedback = Component.translatable(PREFIX + "scale_overflow");
                push();
                return;
            } catch (RuntimeException error) {
                feedback = Component.translatable(PREFIX + "recipes_changed");
                push();
                return;
            }
        }
        ItemStack bundle = SelectedRecipePattern.create(new SelectedRecipePattern.Contents(
                scale, outputScale, settings.recipeType, ids));
        if (bundle.isEmpty()) {
            feedback = Component.translatable(PREFIX + "bundle_empty");
            push();
            return;
        }
        player.getInventory().placeItemBackInInventory(bundle);
        feedback = Component.translatable(PREFIX + "bundle_result", ids.size());
        player.displayClientMessage(feedback, true);
        push();
    }

    private void generateBatch() {
        if (!(holder.getPlayer() instanceof ServerPlayer player)) return;
        for (int index = 0; index < BATCH_PER_TICK && !settings.pending.isEmpty(); index++) {
            String id = settings.pending.get(0);
            var entry = matching.stream().filter(candidate -> candidate.id().equals(id)).findFirst().orElse(null);
            ResourceLocation key = ResourceLocation.tryParse(id);
            if (entry == null || key == null || player.server.getRecipeManager().byKey(key).orElse(null) != entry.recipe()) {
                running = false;
                feedback = Component.translatable(PREFIX + "recipes_changed");
                break;
            }
            try {
                if (settings.skipOwned && PatternGeneratorTransfer.owns(player, id)) {
                    skipped++;
                } else {
                    if (types.stream().noneMatch(type -> type == entry.recipe().recipeType)
                            || !entry.recipe().recipeType.registryName.toString().equals(settings.recipeType)) {
                        throw new IllegalArgumentException("Recipe no longer belongs to the selected machine mode");
                    }
                    ItemStack pattern = PatternGeneratorRecipes.encode(entry, player, settings.scale, settings.outputScale);
                    if (pattern.isEmpty()) throw new IllegalStateException("Recipe cannot be encoded: " + id);
                    PatternGeneratorTransfer.mark(pattern, settings);
                    player.getInventory().placeItemBackInInventory(pattern);
                    generated++;
                }
            } catch (RuntimeException error) {
                failed++;
                GTDishanhaiMod.LOGGER.error("Pattern generator could not encode recipe {}", id, error);
            }
            settings.pending.remove(0);
            unchecked.add(id);
        }
        pendingCount = settings.pending.size();
        availablePatterns = PatternGeneratorTransfer.available(player, settings.generatorId);
        selected = (int) matching.stream().filter(entry -> !unchecked.contains(entry.id())).count();
        refreshPreview();
        if (settings.pending.isEmpty()) {
            running = false;
            if (skipped > 0) feedback = Component.translatable(PREFIX + "result_skipped", generated, skipped, failed);
            else if (failed == 0) feedback = Component.translatable(PREFIX + "result", generated);
            else feedback = Component.translatable(PREFIX + "result_failed", generated, failed);
            player.displayClientMessage(feedback, true);
        }
        save();
        push();
    }
}
