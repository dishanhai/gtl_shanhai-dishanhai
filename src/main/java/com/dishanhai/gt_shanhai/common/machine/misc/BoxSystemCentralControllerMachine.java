package com.dishanhai.gt_shanhai.common.machine.misc;

import com.dishanhai.gt_shanhai.GTDishanhaiRegistration;
import com.dishanhai.gt_shanhai.api.DShanhaiTextUtil;
import com.dishanhai.gt_shanhai.api.machine.SelectableRecipeTypeSetRecipeLogic;
import com.dishanhai.gt_shanhai.api.machine.SelectableRecipeTypeSetMachine;
import com.gregtechceu.gtceu.api.capability.recipe.FluidRecipeCapability;
import com.gregtechceu.gtceu.api.capability.recipe.EURecipeCapability;
import com.gregtechceu.gtceu.api.capability.recipe.ItemRecipeCapability;
import com.gregtechceu.gtceu.api.capability.recipe.RecipeCapability;
import com.gregtechceu.gtceu.api.data.RotationState;
import com.gregtechceu.gtceu.api.gui.GuiTextures;
import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity;
import com.gregtechceu.gtceu.api.machine.MultiblockMachineDefinition;
import com.gregtechceu.gtceu.api.machine.multiblock.PartAbility;
import com.gregtechceu.gtceu.api.pattern.BlockPattern;
import com.gregtechceu.gtceu.api.pattern.FactoryBlockPattern;
import com.gregtechceu.gtceu.api.pattern.Predicates;
import com.gregtechceu.gtceu.api.pattern.util.RelativeDirection;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.GTRecipeType;
import com.gregtechceu.gtceu.api.recipe.RecipeHelper;
import com.gregtechceu.gtceu.api.recipe.content.Content;
import com.gregtechceu.gtceu.api.recipe.content.ContentModifier;
import com.gregtechceu.gtceu.api.recipe.ingredient.SizedIngredient;
import com.gregtechceu.gtceu.api.registry.GTRegistries;
import com.gregtechceu.gtceu.common.data.GTRecipeTypes;
import com.gtladd.gtladditions.common.recipe.GTLAddRecipesTypes;
import com.lowdragmc.lowdraglib.gui.widget.ComponentPanelWidget;
import com.lowdragmc.lowdraglib.gui.widget.DraggableScrollableWidgetGroup;
import com.lowdragmc.lowdraglib.gui.widget.Widget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;
import com.lowdragmc.lowdraglib.side.fluid.FluidStack;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;
import org.gtlcore.gtlcore.common.data.GTLRecipeTypes;
import org.gtlcore.gtlcore.api.machine.trait.ILockRecipe;

import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Set;

import static com.dishanhai.gt_shanhai.GTDishanhaiMod.MOD_ID;

public class BoxSystemCentralControllerMachine extends SelectableRecipeTypeSetMachine {

    private static final String KEY_BOX_LITE = "sh_box_lite";
    private static final String KEY_ROUTINGS = "routings";
    private static final String KEY_PLAN = "plan";
    private static final int MAX_ROUTINGS = 16;

    private static final GTRecipeType[] RECIPE_TYPES = {
            GTRecipeTypes.FURNACE_RECIPES,
            GTRecipeTypes.ALLOY_SMELTER_RECIPES,
            GTRecipeTypes.ASSEMBLER_RECIPES,
            GTRecipeTypes.CIRCUIT_ASSEMBLER_RECIPES,
            GTRecipeTypes.CHEMICAL_RECIPES,
            GTRecipeTypes.LARGE_CHEMICAL_RECIPES,
            GTRecipeTypes.MIXER_RECIPES,
            GTRecipeTypes.CENTRIFUGE_RECIPES,
            GTRecipeTypes.ELECTROLYZER_RECIPES,
            GTRecipeTypes.EXTRACTOR_RECIPES,
            GTRecipeTypes.COMPRESSOR_RECIPES,
            GTRecipeTypes.MACERATOR_RECIPES,
            GTRecipeTypes.FORGE_HAMMER_RECIPES,
            GTRecipeTypes.BENDER_RECIPES,
            GTRecipeTypes.LATHE_RECIPES,
            GTRecipeTypes.WIREMILL_RECIPES,
            GTRecipeTypes.FORMING_PRESS_RECIPES,
            GTRecipeTypes.EXTRUDER_RECIPES,
            GTRecipeTypes.PACKER_RECIPES,
            GTRecipeTypes.CANNER_RECIPES,
            GTRecipeTypes.FLUID_SOLIDFICATION_RECIPES,
            GTRecipeTypes.AUTOCLAVE_RECIPES,
            GTRecipeTypes.THERMAL_CENTRIFUGE_RECIPES,
            GTRecipeTypes.ELECTROMAGNETIC_SEPARATOR_RECIPES,
            GTRecipeTypes.SIFTER_RECIPES,
            GTRecipeTypes.LASER_ENGRAVER_RECIPES,
            GTRecipeTypes.POLARIZER_RECIPES,
            GTRecipeTypes.CUTTER_RECIPES,
            GTRecipeTypes.ORE_WASHER_RECIPES,
            GTRecipeTypes.ARC_FURNACE_RECIPES,
            GTRecipeTypes.FLUID_HEATER_RECIPES,
            GTRecipeTypes.DISTILLERY_RECIPES,
            GTRecipeTypes.ROCK_BREAKER_RECIPES,
            GTRecipeTypes.SCANNER_RECIPES,
            GTRecipeTypes.GAS_COLLECTOR_RECIPES,
            GTRecipeTypes.BREWING_RECIPES,
            GTRecipeTypes.FERMENTING_RECIPES,
            GTRecipeTypes.CHEMICAL_BATH_RECIPES,
            GTRecipeTypes.AIR_SCRUBBER_RECIPES,
            GTLRecipeTypes.GREENHOUSE_RECIPES,
            GTLRecipeTypes.INCUBATOR_RECIPES,
            GTLRecipeTypes.DEHYDRATOR_RECIPES,
            GTLRecipeTypes.VACUUM_DRYING_RECIPES,
            GTLRecipeTypes.HEAT_EXCHANGER_RECIPES,
            GTLRecipeTypes.LARGE_RECYCLER_RECIPES,
            GTLRecipeTypes.DISASSEMBLY_RECIPES,
            GTLRecipeTypes.NEUTRON_ACTIVATOR_RECIPES,
            GTLRecipeTypes.ELEMENT_COPYING_RECIPES,
            GTLRecipeTypes.MASS_FABRICATOR_RECIPES,
            GTLRecipeTypes.MATTER_FABRICATOR_RECIPES,
            GTLRecipeTypes.ISA_MILL_RECIPES,
            GTLRecipeTypes.FLOTATING_BENEFICIATION_RECIPES,
            GTLAddRecipesTypes.INSTANCE.getSPACE_ORE_PROCESSOR(),
            GTLAddRecipesTypes.INSTANCE.getBIOLOGICAL_SIMULATION()
    };

    private final List<BoxRouting> boxRoutings = new ArrayList<>();
    private BoxRecipePlan boxPlan = new BoxRecipePlan();

    // 合成后的锁定配方只在路由/锁定状态变化时才重建（rebuildBoxPlan/NBT 读档），
    // 每 tick 的 lookupRecipeIterator/getGTRecipe 只读缓存，不再对每条路由重扫整棵配方查找树。
    private GTRecipe cachedLockedBoxRecipe;
    private boolean lockedBoxRecipeDirty = true;

    public BoxSystemCentralControllerMachine(IMachineBlockEntity holder, Object... args) {
        super(holder, GTRecipeTypes.ASSEMBLER_RECIPES, args);
    }

    @Override
    public BoxSystemCentralControllerRecipeLogic createRecipeLogic(Object... args) {
        return new BoxSystemCentralControllerRecipeLogic(this);
    }

    @Override
    public int getMaxParallel() {
        return isFormed() ? 160 : 1;
    }

    @Override
    public int getAdditionalThread() {
        return isFormed() ? 16 : 0;
    }

    @Override
    public void addDisplayText(List<Component> textList) {
        if (isFormed()) {
            textList.add(Component.literal("盒系统中央控制器").withStyle(ChatFormatting.AQUA));
            textList.add(Component.literal("§7已选择配方类型: §f" + getSelectedRecipeTypeCount()
                    + "§7/§f" + getAllSelectableRecipeTypes().length));
            textList.add(Component.literal("§b并行: §f" + getMaxParallel() + " §7· §d线程: §f" + getAdditionalThread()));
            textList.add(Component.literal("§7Box 路由: §f" + boxRoutings.size() + "§7/§f" + MAX_ROUTINGS
                    + " §7· 锁定: " + (boxPlan.locked ? "§a是" : "§8否")));
        } else {
            textList.add(Component.translatable("gtceu.multiblock.invalid_structure").withStyle(ChatFormatting.RED));
        }
        textList.add(Component.translatable("gt_shanhai.machine.box_system_central_controller.name")
                .withStyle(ChatFormatting.GOLD));
    }

    @Override
    public void saveCustomPersistedData(CompoundTag tag, boolean forDrop) {
        super.saveCustomPersistedData(tag, forDrop);
        CompoundTag root = new CompoundTag();
        ListTag routings = new ListTag();
        for (BoxRouting routing : boxRoutings) {
            routings.add(routing.save());
        }
        root.put(KEY_ROUTINGS, routings);
        root.put(KEY_PLAN, boxPlan.save());
        tag.put(KEY_BOX_LITE, root);
    }

    @Override
    public void loadCustomPersistedData(CompoundTag tag) {
        super.loadCustomPersistedData(tag);
        boxRoutings.clear();
        boxPlan = new BoxRecipePlan();
        if (!tag.contains(KEY_BOX_LITE)) {
            return;
        }
        CompoundTag root = tag.getCompound(KEY_BOX_LITE);
        ListTag routings = root.getList(KEY_ROUTINGS, 10);
        for (int i = 0; i < routings.size() && boxRoutings.size() < MAX_ROUTINGS; i++) {
            boxRoutings.add(BoxRouting.load(routings.getCompound(i)));
        }
        if (root.contains(KEY_PLAN)) {
            boxPlan = BoxRecipePlan.load(root.getCompound(KEY_PLAN));
        } else {
            rebuildBoxPlan(false);
        }
        // 读档时不立即重建缓存：RecipeManager 在方块实体加载阶段未必已就绪，延后到下一次
        // buildLockedBoxRecipe() 调用（RecipeLogic 每 tick 都会调）时再懒计算，规避加载时序问题。
        lockedBoxRecipeDirty = true;
    }

    @Override
    public Widget createUIWidget() {
        int width = 190;
        int height = 170;
        WidgetGroup group = new WidgetGroup(0, 0, width, height);
        DraggableScrollableWidgetGroup scrollGroup = new DraggableScrollableWidgetGroup(4, 4, width - 8, height - 8);
        scrollGroup.setBackground(GuiTextures.DISPLAY);

        ComponentPanelWidget textPanel = new ComponentPanelWidget(4, 5, this::buildBoxControlText);
        textPanel.setMaxWidthLimit(width - 20);
        textPanel.clickHandler((cmd, cd) -> {
            if (cd.isRemote) return;
            switch (cmd) {
                case "box_capture" -> captureCurrentRecipeAsRouting();
                case "box_lock" -> setBoxPlanLocked(!boxPlan.locked);
                case "box_clear" -> clearBoxRoutings();
                case "box_double" -> scaleBoxRoutings(2);
                case "box_half" -> scaleBoxRoutings(-2);
            }
        });
        scrollGroup.addWidget(textPanel);
        group.addWidget(scrollGroup);
        group.setBackground(GuiTextures.BACKGROUND_INVERSE);
        return group;
    }

    private void buildBoxControlText(List<Component> list) {
        list.add(Component.literal("§b§l盒系统中央控制器"));
        list.add(Component.literal(isFormed() ? "§a结构已成型" : "§c结构未成型"));
        list.add(Component.literal("§7路由: §f" + boxRoutings.size() + "§7/§f" + MAX_ROUTINGS
                + " §7锁定: " + (boxPlan.locked ? "§aON" : "§8OFF")));
        list.add(Component.literal("§7最终并行: §f" + boxPlan.parallel
                + " §7EU/t: §f" + boxPlan.eut + " §7耗时: §f" + boxPlan.duration));
        list.add(Component.literal("§7输入: §f" + boxPlan.itemInputs.size() + "物品 / " + boxPlan.fluidInputs.size() + "流体"));
        list.add(Component.literal("§7输出: §f" + boxPlan.itemOutputs.size() + "物品 / " + boxPlan.fluidOutputs.size() + "流体"));
        list.add(Component.literal(""));
        list.add(Component.literal("")
                .append(ComponentPanelWidget.withButton(Component.literal("§a[捕获当前配方]"), "box_capture"))
                .append(" ")
                .append(ComponentPanelWidget.withButton(Component.literal(boxPlan.locked ? "§e[解锁]" : "§e[锁定]"), "box_lock")));
        list.add(Component.literal("")
                .append(ComponentPanelWidget.withButton(Component.literal("§b[并行x2]"), "box_double"))
                .append(" ")
                .append(ComponentPanelWidget.withButton(Component.literal("§9[并行/2]"), "box_half"))
                .append(" ")
                .append(ComponentPanelWidget.withButton(Component.literal("§c[清空]"), "box_clear")));
        list.add(Component.literal(""));
        if (boxRoutings.isEmpty()) {
            list.add(Component.literal("§8暂无路由。机器运行并锁定配方后可捕获当前配方。"));
        } else {
            int start = Math.max(0, boxRoutings.size() - 5);
            for (int i = start; i < boxRoutings.size(); i++) {
                BoxRouting routing = boxRoutings.get(i);
                list.add(Component.literal("§7#" + (i + 1) + " §f" + shortText(routing.recipeId)
                        + " §8(" + shortText(routing.recipeType) + ") §b×" + routing.parallel));
            }
        }
        list.add(Component.literal(""));
        list.add(Component.literal(boxPlan.locked
                ? "§aBox++ Lite: 锁定后将按路由合成运行时配方。"
                : "§8Box++ Lite: 未锁定时只保存路由摘要。"));
    }

    private void captureCurrentRecipeAsRouting() {
        if (boxPlan.locked || boxRoutings.size() >= MAX_ROUTINGS) {
            return;
        }
        GTRecipe recipe = getCandidateRecipeForBoxRouting();
        if (recipe == null) {
            return;
        }
        boxRoutings.add(BoxRouting.fromRecipe(recipe));
        rebuildBoxPlan(false);
        markDirty();
        if (getLevel() != null && !getLevel().isClientSide) {
            notifyBlockUpdate();
        }
    }

    private GTRecipe getCandidateRecipeForBoxRouting() {
        ILockRecipe lockRecipe = (ILockRecipe) getRecipeLogic();
        GTRecipe lockedRecipe = lockRecipe.getLockRecipe();
        if (lockedRecipe != null) {
            return lockedRecipe;
        }
        for (GTRecipeType type : getSelectedRecipeTypes()) {
            if (type == null) {
                continue;
            }
            GTRecipe recipe = type.getLookup().find(this, r -> getRecipeLogic().checkMatchedRecipeAvailable(r));
            if (recipe != null) {
                return recipe;
            }
        }
        return null;
    }

    private void setBoxPlanLocked(boolean locked) {
        rebuildBoxPlan(locked);
        markDirty();
    }

    private void clearBoxRoutings() {
        if (boxPlan.locked) {
            return;
        }
        boxRoutings.clear();
        rebuildBoxPlan(false);
        markDirty();
    }

    private void scaleBoxRoutings(int factor) {
        if (boxPlan.locked || boxRoutings.isEmpty()) {
            return;
        }
        if (factor == 2) {
            int total = 0;
            for (BoxRouting routing : boxRoutings) {
                total += routing.parallel;
            }
            if (total * 2 > getMaxParallel()) {
                return;
            }
            for (BoxRouting routing : boxRoutings) {
                routing.parallel *= 2;
            }
        } else if (factor == -2) {
            for (BoxRouting routing : boxRoutings) {
                if ((routing.parallel & 1) == 1) {
                    return;
                }
            }
            for (BoxRouting routing : boxRoutings) {
                routing.parallel = Math.max(1, routing.parallel / 2);
            }
        }
        rebuildBoxPlan(false);
        markDirty();
    }

    private void rebuildBoxPlan(boolean locked) {
        BoxRecipePlan next = new BoxRecipePlan();
        next.locked = locked;
        Map<String, Integer> modules = new LinkedHashMap<>();
        for (BoxRouting routing : boxRoutings) {
            int parallel = Math.max(1, routing.parallel);
            next.parallel += parallel;
            next.eut += routing.eut;
            next.duration += routing.duration;
            addScaled(next.itemInputs, routing.itemInputs, parallel);
            addScaled(next.itemOutputs, routing.itemOutputs, parallel);
            addScaled(next.fluidInputs, routing.fluidInputs, parallel);
            addScaled(next.fluidOutputs, routing.fluidOutputs, parallel);
            String module = moduleForRecipeType(routing.recipeType);
            modules.put(module, Math.max(modules.getOrDefault(module, 0), 0));
        }
        next.requiredModules.addAll(modules.keySet());
        boxPlan = next;
        lockedBoxRecipeDirty = true;
    }

    private GTRecipe buildLockedBoxRecipe() {
        if (lockedBoxRecipeDirty) {
            cachedLockedBoxRecipe = computeLockedBoxRecipe();
            lockedBoxRecipeDirty = false;
        }
        return cachedLockedBoxRecipe;
    }

    private GTRecipe computeLockedBoxRecipe() {
        if (!boxPlan.locked || boxRoutings.isEmpty()) {
            return null;
        }
        Map<RecipeCapability<?>, List<Content>> inputs = new IdentityHashMap<>();
        Map<RecipeCapability<?>, List<Content>> outputs = new IdentityHashMap<>();
        Map<RecipeCapability<?>, List<Content>> tickInputs = new IdentityHashMap<>();
        Map<RecipeCapability<?>, List<Content>> tickOutputs = new IdentityHashMap<>();
        long totalEUt = 0L;
        int totalDuration = 0;
        boolean foundAny = false;

        for (BoxRouting routing : boxRoutings) {
            GTRecipe source = findRoutingSourceRecipe(routing);
            if (source == null) {
                return null;
            }
            int parallel = Math.max(1, routing.parallel);
            GTRecipe scaled = parallel > 1 ? source.copy(ContentModifier.multiplier(parallel), false) : source.copy();
            mergeContents(inputs, scaled.inputs);
            mergeContents(outputs, scaled.outputs);
            mergeContents(tickInputs, scaled.tickInputs);
            mergeContents(tickOutputs, scaled.tickOutputs);
            totalEUt += Math.max(0L, RecipeHelper.getInputEUt(source));
            totalDuration += Math.max(1, source.duration);
            foundAny = true;
        }
        if (!foundAny) {
            return null;
        }
        tickInputs.put(EURecipeCapability.CAP, List.of(new Content(totalEUt, 10000, 10000, 0, null, null)));
        int duration = Math.max(1, totalDuration);
        GTRecipe recipe = new GTRecipe(GTRecipeTypes.DUMMY_RECIPES,
                new ResourceLocation(MOD_ID, "box_system_central_controller/runtime"),
                inputs, outputs, tickInputs, tickOutputs,
                new HashMap<>(), new HashMap<>(), new HashMap<>(), new HashMap<>(),
                new ArrayList<>(), new ArrayList<>(), new CompoundTag(), duration, false);
        recipe.parallels = Math.max(1, boxPlan.parallel);
        return recipe;
    }

    private GTRecipe findRoutingSourceRecipe(BoxRouting routing) {
        if (routing == null || routing.recipeType == null || routing.recipeId == null) {
            return null;
        }
        GTRecipeType type;
        try {
            type = GTRegistries.RECIPE_TYPES.get(new ResourceLocation(routing.recipeType));
        } catch (Exception ignored) {
            return null;
        }
        if (type == null) {
            return null;
        }
        ResourceLocation recipeId;
        try {
            recipeId = new ResourceLocation(routing.recipeId);
        } catch (Exception ignored) {
            return null;
        }
        try {
            var branch = type.getLookup().getLookup();
            if (branch == null) {
                return null;
            }
            final GTRecipe[] found = new GTRecipe[1];
            branch.getRecipes(true).forEach(recipe -> {
                if (found[0] == null && recipe != null && recipeId.equals(recipe.getId())) {
                    found[0] = recipe;
                }
            });
            return found[0];
        } catch (Exception ignored) {
            return null;
        }
    }

    private static void mergeContents(Map<RecipeCapability<?>, List<Content>> target,
                                      Map<RecipeCapability<?>, List<Content>> source) {
        for (Map.Entry<RecipeCapability<?>, List<Content>> entry : source.entrySet()) {
            if (entry.getValue() == null || entry.getValue().isEmpty()) {
                continue;
            }
            List<Content> list = target.computeIfAbsent(entry.getKey(), key -> new ArrayList<>());
            list.addAll(entry.getValue());
        }
    }

    private static void addScaled(List<String> target, List<String> source, int parallel) {
        for (String value : source) {
            target.add("x" + parallel + " " + value);
        }
    }

    private static String moduleForRecipeType(String recipeType) {
        if (recipeType == null) {
            return "unknown";
        }
        String id = recipeType.toLowerCase();
        if (id.contains("assembler") || id.contains("assembly")) return "assembly";
        if (id.contains("chemical") || id.contains("mixer")) return "chemical";
        if (id.contains("distill") || id.contains("crack")) return "distillation";
        if (id.contains("furnace") || id.contains("smelt") || id.contains("arc")) return "thermal";
        if (id.contains("centrifuge") || id.contains("electrolyzer") || id.contains("separator")) return "separation";
        if (id.contains("cut") || id.contains("macerator") || id.contains("bender") || id.contains("wiremill")) return "forming";
        if (id.contains("solid") || id.contains("fluid")) return "fluid";
        return "general";
    }

    private static String shortText(String value) {
        if (value == null || value.isEmpty()) {
            return "unknown";
        }
        int index = value.indexOf(':');
        return index >= 0 && index + 1 < value.length() ? value.substring(index + 1) : value;
    }

    public static class BoxSystemCentralControllerRecipeLogic extends SelectableRecipeTypeSetRecipeLogic {
        public BoxSystemCentralControllerRecipeLogic(BoxSystemCentralControllerMachine machine) {
            super(machine);
        }

        @Override
        public BoxSystemCentralControllerMachine getMachine() {
            return (BoxSystemCentralControllerMachine) super.getMachine();
        }

        @Override
        protected Set<GTRecipe> lookupRecipeIterator() {
            GTRecipe boxRecipe = getMachine().buildLockedBoxRecipe();
            if (boxRecipe != null) {
                return Collections.singleton(boxRecipe);
            }
            return super.lookupRecipeIterator();
        }

        @Override
        protected GTRecipe getGTRecipe() {
            GTRecipe boxRecipe = getMachine().buildLockedBoxRecipe();
            if (boxRecipe != null && checkRecipe(boxRecipe)) {
                return boxRecipe;
            }
            return super.getGTRecipe();
        }
    }

    public static BlockPattern createPattern(MultiblockMachineDefinition definition) {
        Block casing = getBlock("gtceu", "bronze_machine_casing");

        return FactoryBlockPattern.start(RelativeDirection.RIGHT, RelativeDirection.UP, RelativeDirection.BACK)
                .aisle("BBBBB", "BCCCB", "BCCCB", "BCCCB", "BBBBB")
                .aisle("BCCCB", "C   C", "C   C", "C   C", "BCCCB")
                .aisle("BCCCB", "C   C", "C A C", "C   C", "BCCCB")
                .aisle("BCCCB", "C   C", "C   C", "C   C", "BCCCB")
                .aisle("BBBBB", "BCCCB", "BC~CB", "BCCCB", "BBBBB")
                .where('~', Predicates.controller(Predicates.blocks(definition.getBlock())))
                .where('A', Predicates.blocks(Blocks.CYAN_STAINED_GLASS))
                .where('C', Predicates.blocks(Blocks.BLACK_CONCRETE))
                .where('B', Predicates.blocks(casing)
                        .or(Predicates.abilities(PartAbility.IMPORT_ITEMS).setPreviewCount(1))
                        .or(Predicates.abilities(PartAbility.EXPORT_ITEMS).setPreviewCount(1))
                        .or(Predicates.abilities(PartAbility.IMPORT_FLUIDS).setPreviewCount(1))
                        .or(Predicates.abilities(PartAbility.EXPORT_FLUIDS).setPreviewCount(1))
                        .or(Predicates.abilities(PartAbility.INPUT_ENERGY).setPreviewCount(1)))
                .where(' ', Predicates.any())
                .build();
    }

    public static MultiblockMachineDefinition register() {
        var def = GTDishanhaiRegistration.REGISTRATE
                .multiblock("box_system_central_controller", BoxSystemCentralControllerMachine::new)
                .rotationState(RotationState.NON_Y_AXIS)
                .recipeTypes(RECIPE_TYPES)
                .appearanceBlock(() -> getBlock("gtceu", "bronze_machine_casing"))
                .pattern(BoxSystemCentralControllerMachine::createPattern)
                .workableCasingRenderer(
                        new ResourceLocation("gtceu", "block/casings/steam/bronze/side"),
                        new ResourceLocation(MOD_ID, "block/multiblock/box_system_central_controller"))
                .register();

        def.setTooltipBuilder((stack, tooltips) -> {
            tooltips.add(DShanhaiTextUtil.createElectricText("盒系统中央控制器"));
            tooltips.add(Component.literal(""));
            tooltips.add(Component.literal("§7机器类型: §e产线封装者"));
            tooltips.add(Component.literal("§7首版提供多配方类型选择与统一运行入口"));
            tooltips.add(Component.literal("§b支持输入/输出总线、输入/输出仓、能源仓替换外壳"));
            tooltips.add(Component.literal("§a并行 160，跨配方线程 16"));
            tooltips.add(Component.literal("§8Box++ 行为移植第一阶段: 先跑起来"));
        });
        return def;
    }

    private static Block getBlock(String namespace, String path) {
        Block block = ForgeRegistries.BLOCKS.getValue(new ResourceLocation(namespace, path));
        return block == null ? Blocks.BARRIER : block;
    }

    private static class BoxRouting {
        String recipeId = "unknown";
        String recipeType = "unknown";
        long eut;
        int duration;
        int parallel = 1;
        final List<String> itemInputs = new ArrayList<>();
        final List<String> itemOutputs = new ArrayList<>();
        final List<String> fluidInputs = new ArrayList<>();
        final List<String> fluidOutputs = new ArrayList<>();

        static BoxRouting fromRecipe(GTRecipe recipe) {
            BoxRouting routing = new BoxRouting();
            ResourceLocation id = recipe.getId();
            routing.recipeId = id == null ? "unknown" : id.toString();
            routing.recipeType = recipe.recipeType == null || recipe.recipeType.registryName == null
                    ? "unknown" : recipe.recipeType.registryName.toString();
            long inputEu = RecipeHelper.getInputEUt(recipe);
            long outputEu = RecipeHelper.getOutputEUt(recipe);
            routing.eut = inputEu != 0 ? inputEu : outputEu;
            routing.duration = recipe.duration;
            routing.itemInputs.addAll(extractItemStacks(recipe, true));
            routing.itemOutputs.addAll(extractItemStacks(recipe, false));
            routing.fluidInputs.addAll(extractFluidStacks(recipe, true));
            routing.fluidOutputs.addAll(extractFluidStacks(recipe, false));
            return routing;
        }

        CompoundTag save() {
            CompoundTag tag = new CompoundTag();
            tag.putString("recipeId", recipeId);
            tag.putString("recipeType", recipeType);
            tag.putLong("eut", eut);
            tag.putInt("duration", duration);
            tag.putInt("parallel", parallel);
            saveStringList(tag, "itemInputs", itemInputs);
            saveStringList(tag, "itemOutputs", itemOutputs);
            saveStringList(tag, "fluidInputs", fluidInputs);
            saveStringList(tag, "fluidOutputs", fluidOutputs);
            return tag;
        }

        static BoxRouting load(CompoundTag tag) {
            BoxRouting routing = new BoxRouting();
            routing.recipeId = tag.getString("recipeId");
            routing.recipeType = tag.getString("recipeType");
            routing.eut = tag.getLong("eut");
            routing.duration = tag.getInt("duration");
            routing.parallel = Math.max(1, tag.getInt("parallel"));
            routing.itemInputs.addAll(loadStringList(tag, "itemInputs"));
            routing.itemOutputs.addAll(loadStringList(tag, "itemOutputs"));
            routing.fluidInputs.addAll(loadStringList(tag, "fluidInputs"));
            routing.fluidOutputs.addAll(loadStringList(tag, "fluidOutputs"));
            return routing;
        }
    }

    private static class BoxRecipePlan {
        final List<String> itemInputs = new ArrayList<>();
        final List<String> itemOutputs = new ArrayList<>();
        final List<String> fluidInputs = new ArrayList<>();
        final List<String> fluidOutputs = new ArrayList<>();
        final List<String> requiredModules = new ArrayList<>();
        long eut;
        int duration;
        int parallel;
        boolean locked;

        CompoundTag save() {
            CompoundTag tag = new CompoundTag();
            tag.putLong("eut", eut);
            tag.putInt("duration", duration);
            tag.putInt("parallel", parallel);
            tag.putBoolean("locked", locked);
            saveStringList(tag, "itemInputs", itemInputs);
            saveStringList(tag, "itemOutputs", itemOutputs);
            saveStringList(tag, "fluidInputs", fluidInputs);
            saveStringList(tag, "fluidOutputs", fluidOutputs);
            saveStringList(tag, "requiredModules", requiredModules);
            return tag;
        }

        static BoxRecipePlan load(CompoundTag tag) {
            BoxRecipePlan plan = new BoxRecipePlan();
            plan.eut = tag.getLong("eut");
            plan.duration = tag.getInt("duration");
            plan.parallel = tag.getInt("parallel");
            plan.locked = tag.getBoolean("locked");
            plan.itemInputs.addAll(loadStringList(tag, "itemInputs"));
            plan.itemOutputs.addAll(loadStringList(tag, "itemOutputs"));
            plan.fluidInputs.addAll(loadStringList(tag, "fluidInputs"));
            plan.fluidOutputs.addAll(loadStringList(tag, "fluidOutputs"));
            plan.requiredModules.addAll(loadStringList(tag, "requiredModules"));
            return plan;
        }
    }

    private static void saveStringList(CompoundTag tag, String key, List<String> values) {
        ListTag list = new ListTag();
        for (String value : values) {
            list.add(StringTag.valueOf(value == null ? "" : value));
        }
        tag.put(key, list);
    }

    private static List<String> loadStringList(CompoundTag tag, String key) {
        List<String> result = new ArrayList<>();
        ListTag list = tag.getList(key, 8);
        for (int i = 0; i < list.size(); i++) {
            String value = list.getString(i);
            if (!value.isEmpty()) {
                result.add(value);
            }
        }
        return result;
    }

    private static List<String> extractItemStacks(GTRecipe recipe, boolean input) {
        List<String> result = new ArrayList<>();
        Map<com.gregtechceu.gtceu.api.capability.recipe.RecipeCapability<?>, List<Content>> contents = input ? recipe.inputs : recipe.outputs;
        List<Content> list = contents.get(ItemRecipeCapability.CAP);
        if (list == null) {
            return result;
        }
        for (Content content : list) {
            if (content.content instanceof SizedIngredient ingredient) {
                ItemStack[] stacks = ingredient.getItems();
                if (stacks.length > 0) {
                    result.add(formatItem(stacks[0], ingredient.getAmount()));
                }
            } else if (content.content instanceof ItemStack stack) {
                result.add(formatItem(stack, stack.getCount()));
            } else if (content.content != null) {
                result.add(String.valueOf(content.content));
            }
        }
        return result;
    }

    private static List<String> extractFluidStacks(GTRecipe recipe, boolean input) {
        List<String> result = new ArrayList<>();
        Map<com.gregtechceu.gtceu.api.capability.recipe.RecipeCapability<?>, List<Content>> contents = input ? recipe.inputs : recipe.outputs;
        List<Content> list = contents.get(FluidRecipeCapability.CAP);
        if (list == null) {
            return result;
        }
        for (Content content : list) {
            if (content.content instanceof FluidStack stack) {
                ResourceLocation id = ForgeRegistries.FLUIDS.getKey(stack.getFluid());
                result.add(stack.getAmount() + "mB " + (id == null ? "unknown" : id.toString()));
            } else if (content.content != null) {
                result.add(String.valueOf(content.content));
            }
        }
        return result;
    }

    private static String formatItem(ItemStack stack, int count) {
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
        return count + "x " + (id == null ? "unknown" : id.toString());
    }
}
