package com.dishanhai.gt_shanhai.common.item;

import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.inventories.InternalInventory;

import com.dishanhai.gt_shanhai.GTDishanhaiMod;
import com.dishanhai.gt_shanhai.api.ae.DShanhaiVirtualCellSavedData;
import com.gregtechceu.gtceu.api.GTValues;
import com.gregtechceu.gtceu.api.gui.GuiTextures;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.GTRecipeType;
import com.gregtechceu.gtceu.api.recipe.RecipeHelper;
import com.gregtechceu.gtceu.integration.ae2.gui.widget.AETextInputButtonWidget;
import com.lowdragmc.lowdraglib.gui.factory.HeldItemUIFactory;
import com.lowdragmc.lowdraglib.gui.modular.ModularUI;
import com.lowdragmc.lowdraglib.gui.texture.GuiTextureGroup;
import com.lowdragmc.lowdraglib.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib.gui.texture.TextTexture;
import com.lowdragmc.lowdraglib.gui.widget.ButtonWidget;
import com.lowdragmc.lowdraglib.gui.widget.ImageWidget;
import com.lowdragmc.lowdraglib.gui.widget.LabelWidget;
import com.lowdragmc.lowdraglib.gui.widget.TabButton;
import com.lowdragmc.lowdraglib.gui.widget.TabContainer;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;

import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;

import org.gtlcore.gtlcore.api.item.tool.ae2.patternTool.Ae2GtmProcessingPattern;
import org.gtlcore.gtlcore.api.item.tool.ae2.patternTool.GTRecipeManager;
import org.gtlcore.gtlcore.common.item.PatternTestBehavior;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class ShanhaiPatternTestBehavior extends PatternTestBehavior {

    public static final ShanhaiPatternTestBehavior INSTANCE = new ShanhaiPatternTestBehavior();

    private static final String TAG_CONFIG = "gt_shanhai_pattern_debug";
    private static final String KEY_ANALYSIS_TYPE = "analysisType";
    private static final String KEY_ANALYSIS_CIRCUIT = "analysisCircuit";
    private static final String KEY_GENERATOR_TYPE = "generatorType";
    private static final String KEY_GENERATOR_CIRCUIT = "generatorCircuit";
    private static final String KEY_GENERATOR_SCALE = "generatorScale";
    private static final String KEY_INPUTS_BLACK = "inputsBlack";
    private static final String KEY_INPUTS_WHITE = "inputsWhite";
    private static final String KEY_OUTPUTS_BLACK = "outputsBlack";
    private static final String KEY_OUTPUTS_WHITE = "outputsWhite";
    private static final String KEY_MODIFIER_PATTERN_MULTIPLIER = "modifierPatternMultiplier";
    private static final String KEY_MODIFIER_PATTERN_DIVISOR = "modifierPatternDivisor";
    private static final String KEY_MODIFIER_OUTPUT_MULTIPLIER = "modifierOutputMultiplier";
    private static final String KEY_MODIFIER_OUTPUT_DIVISOR = "modifierOutputDivisor";
    private static final String KEY_MODIFIER_MAX_ITEM = "modifierMaxItem";
    private static final String KEY_MODIFIER_MAX_FLUID = "modifierMaxFluid";
    private static final String KEY_MODIFIER_APPLIED_TIMES = "modifierAppliedTimes";
    private static final long DEFAULT_MODIFIER_LIMIT = 1_000_000L;

    @Override
    public ModularUI createUI(HeldItemUIFactory.HeldItemHolder heldItemHolder, Player player) {
        CompoundTag config = getConfig(heldItemHolder);
        WidgetGroup analysis = new WidgetGroup(8, 0, 160, 50)
                .addWidget(new ImageWidget(4, 4, 152, 42, GuiTextures.DISPLAY))
                .addWidget(new LabelWidget(6, 6, Component.translatable("gt_shanhai.gui.pattern_debug.analysis").getString()))
                .addWidget(textInput(82, 6, config.getString(KEY_ANALYSIS_TYPE),
                        value -> putString(heldItemHolder, KEY_ANALYSIS_TYPE, value),
                        "tooltip.gtlcore.set_recipe_type"))
                .addWidget(textInput(82, 20, Integer.toString(config.getInt(KEY_ANALYSIS_CIRCUIT)),
                        value -> putInt(heldItemHolder, KEY_ANALYSIS_CIRCUIT, value, 0),
                        "tooltip.gtlcore.set_programmed_circuit"))
                .addWidget(new ButtonWidget(6, 24, 64, 20,
                        buttonTexture("gtlcore.gui.start_analysis"),
                        clickData -> useAnalysisRecipesBaby(heldItemHolder))
                        .setHoverTooltips(Component.translatable("gt_shanhai.tooltip.pattern_debug.exact_type")));

        int scale = config.contains(KEY_GENERATOR_SCALE, Tag.TAG_INT)
                ? Math.max(1, config.getInt(KEY_GENERATOR_SCALE)) : 1;
        WidgetGroup generator = new WidgetGroup(8, 50, 160, 114)
                .addWidget(new ImageWidget(4, 4, 152, 106, GuiTextures.DISPLAY))
                .addWidget(new LabelWidget(6, 6, Component.translatable("gtlcore.gui.pattern_debug").getString()))
                .addWidget(new ButtonWidget(6, 24, 64, 20,
                        buttonTexture("gtlcore.gui.get_pattern"),
                        clickData -> useAe2PatternGenerator(heldItemHolder))
                        .setHoverTooltips(Component.translatable("gt_shanhai.tooltip.pattern_debug.virtual_inputs")))
                .addWidget(textInput(82, 6, config.getString(KEY_GENERATOR_TYPE),
                        value -> putString(heldItemHolder, KEY_GENERATOR_TYPE, value),
                        "tooltip.gtlcore.set_recipe_type"))
                .addWidget(textInput(82, 20, Integer.toString(config.getInt(KEY_GENERATOR_CIRCUIT)),
                        value -> putInt(heldItemHolder, KEY_GENERATOR_CIRCUIT, value, 0),
                        "tooltip.gtlcore.set_programmed_circuit"))
                .addWidget(textInput(82, 34, Integer.toString(scale),
                        value -> putInt(heldItemHolder, KEY_GENERATOR_SCALE, value, 1),
                        "tooltip.gtlcore.set_pattern_multiplier"))
                .addWidget(textInput(82, 48, config.getString(KEY_INPUTS_WHITE),
                        value -> putString(heldItemHolder, KEY_INPUTS_WHITE, value),
                        "tooltip.gtlcore.input_whitelist_keywords"))
                .addWidget(textInput(82, 62, config.getString(KEY_INPUTS_BLACK),
                        value -> putString(heldItemHolder, KEY_INPUTS_BLACK, value),
                        "tooltip.gtlcore.input_blacklist_keywords"))
                .addWidget(textInput(82, 76, config.getString(KEY_OUTPUTS_WHITE),
                        value -> putString(heldItemHolder, KEY_OUTPUTS_WHITE, value),
                        "tooltip.gtlcore.output_whitelist_keywords"))
                .addWidget(textInput(82, 90, config.getString(KEY_OUTPUTS_BLACK),
                        value -> putString(heldItemHolder, KEY_OUTPUTS_BLACK, value),
                        "tooltip.gtlcore.output_blacklist_keywords"));

        WidgetGroup debugPage = new WidgetGroup(0, 16, 176, 164)
                .addWidget(analysis)
                .addWidget(generator);
        WidgetGroup modifierPage = createModifierPage(heldItemHolder, config);
        TabContainer tabs = new TabContainer(0, 0, 176, 180);
        tabs.addTab(tabButton(8, 1, "生成与分析"), debugPage);
        TabButton modifierTab = tabButton(90, 1, "样板修改");
        modifierTab.setHoverTooltips(Component.translatable("gt_shanhai.gui.pattern_debug.modifier_hint"));
        tabs.addTab(modifierTab, modifierPage);

        return new ModularUI(176, 180, heldItemHolder, player)
                .widget(tabs)
                .background(GuiTextures.BACKGROUND);
    }

    private static WidgetGroup createModifierPage(
            HeldItemUIFactory.HeldItemHolder holder, CompoundTag config) {
        int patternMultiplier = getInt(config, KEY_MODIFIER_PATTERN_MULTIPLIER, 1);
        int patternDivisor = getInt(config, KEY_MODIFIER_PATTERN_DIVISOR, 1);
        int outputMultiplier = getInt(config, KEY_MODIFIER_OUTPUT_MULTIPLIER, 1);
        int outputDivisor = getInt(config, KEY_MODIFIER_OUTPUT_DIVISOR, 1);
        long maxItem = getLong(config, KEY_MODIFIER_MAX_ITEM, DEFAULT_MODIFIER_LIMIT);
        long maxFluid = getLong(config, KEY_MODIFIER_MAX_FLUID, DEFAULT_MODIFIER_LIMIT);
        int appliedTimes = getInt(config, KEY_MODIFIER_APPLIED_TIMES, 1);

        WidgetGroup panel = new WidgetGroup(8, 0, 160, 154)
                .addWidget(new ImageWidget(4, 4, 152, 146, GuiTextures.DISPLAY))
                .addWidget(new LabelWidget(6, 6,
                        Component.translatable("gt_shanhai.gui.pattern_debug.modifier").getString()))
                .addWidget(new LabelWidget(6, 22, "全局 ×"))
                .addWidget(textInput(82, 20, Integer.toString(patternMultiplier),
                        value -> putInt(holder, KEY_MODIFIER_PATTERN_MULTIPLIER, value, 1),
                        "tooltip.gtlcore.pattern_multiplier_scale"))
                .addWidget(new LabelWidget(6, 36, "全局 ÷"))
                .addWidget(textInput(82, 34, Integer.toString(patternDivisor),
                        value -> putInt(holder, KEY_MODIFIER_PATTERN_DIVISOR, value, 1),
                        "tooltip.gtlcore.pattern_divider_scale"))
                .addWidget(new LabelWidget(6, 50, "输出 ×"))
                .addWidget(textInput(82, 48, Integer.toString(outputMultiplier),
                        value -> putInt(holder, KEY_MODIFIER_OUTPUT_MULTIPLIER, value, 1),
                        "gt_shanhai.tooltip.pattern_debug.output_multiplier"))
                .addWidget(new LabelWidget(6, 64, "输出 ÷"))
                .addWidget(textInput(82, 62, Integer.toString(outputDivisor),
                        value -> putInt(holder, KEY_MODIFIER_OUTPUT_DIVISOR, value, 1),
                        "gt_shanhai.tooltip.pattern_debug.output_divisor"))
                .addWidget(new LabelWidget(6, 78, "物品上限"))
                .addWidget(textInput(82, 76, Long.toString(maxItem),
                        value -> putLong(holder, KEY_MODIFIER_MAX_ITEM, value, 1L),
                        "tooltip.gtlcore.pattern_max_item_stack"))
                .addWidget(new LabelWidget(6, 92, "流体上限"))
                .addWidget(textInput(82, 90, Long.toString(maxFluid),
                        value -> putLong(holder, KEY_MODIFIER_MAX_FLUID, value, 1L),
                        "tooltip.gtlcore.pattern_max_fluid_stack"))
                .addWidget(new LabelWidget(6, 106, "应用次数"))
                .addWidget(textInput(82, 104, Integer.toString(appliedTimes),
                        value -> putInt(holder, KEY_MODIFIER_APPLIED_TIMES, value, 1),
                        "tooltip.gtlcore.pattern_applied_number"));
        return new WidgetGroup(0, 16, 176, 164).addWidget(panel);
    }

    @Override
    public void useAe2PatternGenerator(HeldItemUIFactory.HeldItemHolder heldItemHolder) {
        if (!(heldItemHolder.getPlayer() instanceof ServerPlayer serverPlayer)) return;
        CompoundTag config = getConfig(heldItemHolder);
        String configuredType = config.getString(KEY_GENERATOR_TYPE);
        GTRecipeType recipeType = resolveRecipeType(configuredType);
        if (recipeType == null) {
            sendInvalidType(serverPlayer, configuredType);
            return;
        }

        int circuit = config.getInt(KEY_GENERATOR_CIRCUIT);
        int scale = config.contains(KEY_GENERATOR_SCALE, Tag.TAG_INT)
                ? Math.max(1, config.getInt(KEY_GENERATOR_SCALE)) : 1;
        GTRecipeManager manager = new GTRecipeManager();
        manager.filterRecipesByType(recipeType);
        manager.filterRecipesByCircuit(circuit);
        filterByKeywords(manager, config);

        List<ItemStack> generatedPatterns = new ArrayList<>();
        List<GTRecipe> recipes = manager.getRecipes();
        for (GTRecipe recipe : recipes) {
            try {
                // false: force-wrap non-consumables except programmed circuits, which remain raw selectors.
                Ae2GtmProcessingPattern pattern = ShanhaiPatternEncoder.encode(recipe, serverPlayer, false);
                if (pattern == null) continue;
                pattern.setScale(scale, false);
                addLore(pattern, recipe, circuit);
                ItemStack patternItemStack = pattern.getPatternItemStack();
                PatternRecipeTypeHelper.writeAuthoritativeRecipeType(patternItemStack, recipe);
                generatedPatterns.add(patternItemStack);
            } catch (RuntimeException exception) {
                GTDishanhaiMod.LOGGER.error("山海样板调试工具生成配方 {} 失败", recipe.id, exception);
            }
        }

        if (generatedPatterns.size() > 20) {
            ItemStack sda = packPatternsAsSda(serverPlayer, generatedPatterns);
            if (!sda.isEmpty()) {
                deliver(serverPlayer, sda);
            } else {
                generatedPatterns.forEach(pattern -> deliver(serverPlayer, pattern));
            }
        } else {
            generatedPatterns.forEach(pattern -> deliver(serverPlayer, pattern));
        }
        serverPlayer.displayClientMessage(Component.translatable(
                "gt_shanhai.message.pattern_debug.generated", generatedPatterns.size())
                .withStyle(ChatFormatting.GREEN), false);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        if (!(context.getPlayer() instanceof ServerPlayer serverPlayer)) {
            return InteractionResult.SUCCESS;
        }
        if (!serverPlayer.isShiftKeyDown()) {
            return super.useOn(context);
        }

        InternalInventory inventory = PatternInventoryTargetHelper.find(context);
        if (inventory == null) {
            serverPlayer.displayClientMessage(Component.translatable(
                    "gt_shanhai.message.pattern_debug.modifier_target_required")
                    .withStyle(ChatFormatting.RED), true);
            return InteractionResult.FAIL;
        }

        CompoundTag config = getConfig(context.getItemInHand());
        ShanhaiPatternModifier.ModificationResult result = ShanhaiPatternModifier.modifyInventory(
                serverPlayer, inventory, modifierSettings(config));
        serverPlayer.displayClientMessage(Component.translatable(
                "gt_shanhai.message.pattern_debug.modified",
                result.modified(), result.skipped(), result.failed())
                .withStyle(result.failed() == 0 ? ChatFormatting.GREEN : ChatFormatting.YELLOW), true);
        return InteractionResult.SUCCESS;
    }

    private static ShanhaiPatternModifier.Settings modifierSettings(CompoundTag config) {
        return new ShanhaiPatternModifier.Settings(
                getInt(config, KEY_MODIFIER_PATTERN_MULTIPLIER, 1),
                getInt(config, KEY_MODIFIER_PATTERN_DIVISOR, 1),
                getInt(config, KEY_MODIFIER_OUTPUT_MULTIPLIER, 1),
                getInt(config, KEY_MODIFIER_OUTPUT_DIVISOR, 1),
                getLong(config, KEY_MODIFIER_MAX_ITEM, DEFAULT_MODIFIER_LIMIT),
                getLong(config, KEY_MODIFIER_MAX_FLUID, DEFAULT_MODIFIER_LIMIT),
                getInt(config, KEY_MODIFIER_APPLIED_TIMES, 1));
    }

    @Override
    public void useAnalysisRecipesBaby(HeldItemUIFactory.HeldItemHolder heldItemHolder) {
        if (!(heldItemHolder.getPlayer() instanceof ServerPlayer serverPlayer)) return;
        CompoundTag config = getConfig(heldItemHolder);
        String configuredType = config.getString(KEY_ANALYSIS_TYPE);
        GTRecipeType recipeType = resolveRecipeType(configuredType);
        if (recipeType == null) {
            sendInvalidType(serverPlayer, configuredType);
            return;
        }
        analysisRecipesBaby(recipeType, config.getInt(KEY_ANALYSIS_CIRCUIT));
    }

    static String normalizeRecipeTypeId(String rawValue) {
        String value = rawValue == null ? "" : rawValue.trim();
        if (value.isEmpty()) return "";
        String normalized = value.contains(":") ? value : "gtceu:" + value;
        ResourceLocation id = ResourceLocation.tryParse(normalized);
        return id == null ? "" : id.toString();
    }

    private static GTRecipeType resolveRecipeType(String rawValue) {
        return PatternRecipeTypeHelper.resolveRecipeType(normalizeRecipeTypeId(rawValue));
    }

    private static void filterByKeywords(GTRecipeManager manager, CompoundTag config) {
        applyInputFilter(manager, config.getString(KEY_INPUTS_WHITE), true);
        applyInputFilter(manager, config.getString(KEY_INPUTS_BLACK), false);
        applyOutputFilter(manager, config.getString(KEY_OUTPUTS_WHITE), true);
        applyOutputFilter(manager, config.getString(KEY_OUTPUTS_BLACK), false);
    }

    private static void applyInputFilter(GTRecipeManager manager, String value, boolean white) {
        String[] keywords = keywords(value);
        if (keywords.length > 0) manager.filterRecipesByInputsIdArray(keywords, white);
    }

    private static void applyOutputFilter(GTRecipeManager manager, String value, boolean white) {
        String[] keywords = keywords(value);
        if (keywords.length > 0) manager.filterRecipesByOutputsIdArray(keywords, white);
    }

    private static String[] keywords(String value) {
        String trimmed = value == null ? "" : value.trim();
        return trimmed.isEmpty() ? new String[0] : trimmed.split("\\s+");
    }

    private static void addLore(Ae2GtmProcessingPattern pattern, GTRecipe recipe, int circuit) {
        String recipeTypeId = recipe.recipeType == null || recipe.recipeType.registryName == null
                ? "unknown" : recipe.recipeType.registryName.toString();
        long inputEUt = RecipeHelper.getInputEUt(recipe);
        int tier = Math.max(0, Math.min(GTValues.VN.length - 1, RecipeHelper.getRecipeEUtTier(recipe)));
        pattern.setLore(Component.literal("配方类型: " + recipeTypeId + " 电路: " + circuit));
        pattern.setLore(Component.literal("电压: " + GTValues.VN[tier] + " (" + inputEUt + " EU/t)"));
    }

    private static ItemStack packPatternsAsSda(ServerPlayer player, List<ItemStack> patterns) {
        MinecraftServer server = player.getServer();
        if (server == null || patterns == null || patterns.isEmpty()) return ItemStack.EMPTY;

        Map<AEKey, BigInteger> amounts = new LinkedHashMap<>();
        for (ItemStack pattern : patterns) {
            if (pattern == null || pattern.isEmpty()) continue;
            AEItemKey key = AEItemKey.of(pattern);
            if (key != null) {
                amounts.merge(key, BigInteger.valueOf(Math.max(1, pattern.getCount())), BigInteger::add);
            }
        }
        if (amounts.isEmpty()) return ItemStack.EMPTY;

        UUID uuid = UUID.randomUUID();
        ItemStack sda = new ItemStack(GTDishanhaiMod.SUPER_DISK_ARRAY.get());
        sda.getOrCreateTag().putUUID(SuperDiskArrayInventory.TAG_UUID, uuid);
        sda.setHoverName(Component.literal("山海样板调试结果 (" + patterns.size() + ")"));
        DShanhaiVirtualCellSavedData.get(server)
                .updateCellBig(uuid, "sda", SuperDiskArrayItem.TOTAL_BYTES, amounts);
        return sda;
    }

    private static void deliver(ServerPlayer player, ItemStack stack) {
        if (!player.addItem(stack)) {
            player.drop(stack, false);
        }
    }

    private static AETextInputButtonWidget textInput(int x, int y, String text,
                                                      java.util.function.Consumer<String> onConfirm,
                                                      String tooltipKey) {
        return new AETextInputButtonWidget(x, y, 72, 12)
                .setText(text)
                .setOnConfirm(onConfirm)
                .setButtonTooltips(Component.translatable(tooltipKey));
    }

    private static GuiTextureGroup buttonTexture(String translationKey) {
        return new GuiTextureGroup(new IGuiTexture[] {
                GuiTextures.BUTTON,
                new TextTexture(Component.translatable(translationKey).getString())
        });
    }

    private static TabButton tabButton(int x, int y, String text) {
        IGuiTexture normal = new GuiTextureGroup(
                GuiTextures.BUTTON, new TextTexture(text));
        IGuiTexture selected = new GuiTextureGroup(
                GuiTextures.BUTTON, new TextTexture("§e" + text));
        return new TabButton(x, y, 78, 14).setTexture(normal, selected);
    }

    private static CompoundTag getConfig(HeldItemUIFactory.HeldItemHolder heldItemHolder) {
        return getConfig(heldItemHolder.getHeld());
    }

    private static CompoundTag getConfig(ItemStack stack) {
        CompoundTag root = stack.getOrCreateTag();
        if (!root.contains(TAG_CONFIG, Tag.TAG_COMPOUND)) {
            root.put(TAG_CONFIG, new CompoundTag());
        }
        return root.getCompound(TAG_CONFIG);
    }

    private static void putString(HeldItemUIFactory.HeldItemHolder holder, String key, String value) {
        getConfig(holder).putString(key, value == null ? "" : value.trim());
        holder.markAsDirty();
    }

    private static void putInt(HeldItemUIFactory.HeldItemHolder holder, String key, String value, int minimum) {
        try {
            int parsed = Integer.parseInt(value.trim());
            getConfig(holder).putInt(key, Math.max(minimum, parsed));
            holder.markAsDirty();
        } catch (RuntimeException exception) {
            holder.getPlayer().displayClientMessage(Component.translatable(
                    "gt_shanhai.message.pattern_debug.invalid_number", value).withStyle(ChatFormatting.RED), false);
        }
    }

    private static void putLong(HeldItemUIFactory.HeldItemHolder holder, String key, String value, long minimum) {
        try {
            long parsed = Long.parseLong(value.trim());
            getConfig(holder).putLong(key, Math.max(minimum, parsed));
            holder.markAsDirty();
        } catch (RuntimeException exception) {
            holder.getPlayer().displayClientMessage(Component.translatable(
                    "gt_shanhai.message.pattern_debug.invalid_number", value).withStyle(ChatFormatting.RED), false);
        }
    }

    private static int getInt(CompoundTag config, String key, int defaultValue) {
        return config.contains(key, Tag.TAG_INT) ? Math.max(1, config.getInt(key)) : defaultValue;
    }

    private static long getLong(CompoundTag config, String key, long defaultValue) {
        return config.contains(key, Tag.TAG_LONG) ? Math.max(1L, config.getLong(key)) : defaultValue;
    }

    private static void sendInvalidType(ServerPlayer player, String configuredType) {
        player.displayClientMessage(Component.translatable(
                "gt_shanhai.message.pattern_debug.invalid_type", configuredType).withStyle(ChatFormatting.RED), false);
    }

    private ShanhaiPatternTestBehavior() {}
}
