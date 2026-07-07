package com.dishanhai.gt_shanhai.common.machine.misc;

import com.dishanhai.gt_shanhai.GTDishanhaiRegistration;
import com.dishanhai.gt_shanhai.api.DShanhaiTextUtil;
import com.dishanhai.gt_shanhai.api.machine.SelectableRecipeTypeSetMachine;
import com.dishanhai.gt_shanhai.api.recipe.DShanhaiRecipeTypes;
import com.dishanhai.gt_shanhai.common.machine.DShanhaiMachines;
import com.dishanhai.gt_shanhai.common.machine.part.DShanhaiDivergenceEngineMachine;
import com.dishanhai.gt_shanhai.common.machine.part.DShanhaiMaintenanceHatchMachine;
import com.dishanhai.gt_shanhai.common.machine.primordial.PrimordialOmegaEngineModuleBase;
import com.gregtechceu.gtceu.api.block.IMachineBlock;
import com.gregtechceu.gtceu.api.capability.recipe.IO;
import com.gregtechceu.gtceu.api.data.RotationState;
import com.gregtechceu.gtceu.api.gui.GuiTextures;
import com.gregtechceu.gtceu.api.item.MetaMachineItem;
import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity;
import com.gregtechceu.gtceu.api.machine.MachineDefinition;
import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.api.machine.MultiblockMachineDefinition;
import com.gregtechceu.gtceu.api.machine.multiblock.PartAbility;
import com.gregtechceu.gtceu.api.machine.trait.NotifiableItemStackHandler;
import com.gregtechceu.gtceu.api.pattern.BlockPattern;
import com.gregtechceu.gtceu.api.pattern.FactoryBlockPattern;
import com.gregtechceu.gtceu.api.pattern.Predicates;
import com.gregtechceu.gtceu.api.pattern.util.RelativeDirection;
import com.gregtechceu.gtceu.api.recipe.GTRecipeType;
import com.lowdragmc.lowdraglib.gui.widget.ComponentPanelWidget;
import com.lowdragmc.lowdraglib.gui.widget.DraggableScrollableWidgetGroup;
import com.lowdragmc.lowdraglib.gui.widget.SlotWidget;
import com.lowdragmc.lowdraglib.gui.widget.Widget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;
import com.lowdragmc.lowdraglib.syncdata.annotation.DescSynced;
import com.lowdragmc.lowdraglib.syncdata.annotation.Persisted;
import com.lowdragmc.lowdraglib.syncdata.field.ManagedFieldHolder;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.List;

import static com.dishanhai.gt_shanhai.GTDishanhaiMod.MOD_ID;

public class ProxyExecutorMachine extends SelectableRecipeTypeSetMachine {

    private static final ManagedFieldHolder MANAGED_FIELD_HOLDER = new ManagedFieldHolder(
            ProxyExecutorMachine.class,
            SelectableRecipeTypeSetMachine.MANAGED_FIELD_HOLDER);

    private static final int BASE_MAX_PARALLEL = 64;
    private static final long BASE_PARALLEL_QUADRATIC_MULTIPLIER = 8L;
    private static final Object2IntMap<ResourceLocation> BOOST_MULTIPLIERS = createBoostMultipliers();

    @Persisted @DescSynced
    public final NotifiableItemStackHandler targetMachineSlot;

    @Persisted @DescSynced
    public final NotifiableItemStackHandler boostSlot;

    @Persisted @DescSynced
    public final NotifiableItemStackHandler threadBoostSlot;

    @DescSynced
    private String cachedTargetId = "";

    public ProxyExecutorMachine(IMachineBlockEntity holder, Object... args) {
        super(holder, DShanhaiRecipeTypes.PROXY_EXECUTION, args);
        this.targetMachineSlot = new NotifiableItemStackHandler(this, 1, IO.NONE, IO.BOTH) {
            @Override
            public int getSlotLimit(int slot) {
                return 64;
            }
        };
        this.targetMachineSlot.storage.setFilter(ProxyExecutorMachine::hasProxyRecipeTypes);
        this.targetMachineSlot.storage.setOnContentsChanged(() -> refreshTargetMachine(false));
        this.boostSlot = new NotifiableItemStackHandler(this, 1, IO.NONE, IO.BOTH) {
            @Override
            public int getSlotLimit(int slot) {
                return 1;
            }
        };
        this.boostSlot.storage.setFilter(ProxyExecutorMachine::isProxyBoostItem);
        this.threadBoostSlot = new NotifiableItemStackHandler(this, 1, IO.NONE, IO.BOTH) {
            @Override
            public int getSlotLimit(int slot) {
                return 64;
            }
        };
        this.threadBoostSlot.storage.setFilter(ProxyExecutorMachine::isThreadBoostItem);
    }

    @Override
    public ManagedFieldHolder getFieldHolder() {
        return MANAGED_FIELD_HOLDER;
    }

    @Override
    public int getMaxParallel() {
        if (!isFormed()) {
            return 1;
        }
        long parallel = getRecipeLogicMaxParallel();
        return parallel >= Integer.MAX_VALUE ? Integer.MAX_VALUE : Math.max(1, (int) parallel);
    }

    @Override
    public long getRecipeLogicMaxParallel() {
        if (!isFormed()) {
            return 1L;
        }
        return Math.max(getTargetParallel(), getWhitelistedPartParallel());
    }

    @Override
    public int getAdditionalThread() {
        long boost = getThreadBoost();
        return boost >= Integer.MAX_VALUE ? Integer.MAX_VALUE : Math.max(0, (int) boost);
    }

    @Override
    public void onMachineRemoved() {
        clearInventory(targetMachineSlot.storage);
        clearInventory(boostSlot.storage);
        clearInventory(threadBoostSlot.storage);
    }

    @Override
    public GTRecipeType[] getAllSelectableRecipeTypes() {
        MachineDefinition target = getTargetDefinition();
        if (target == null || target.getRecipeTypes() == null) {
            return new GTRecipeType[0];
        }
        return target.getRecipeTypes();
    }

    @Override
    public void addDisplayText(List<Component> textList) {
        if (!isFormed()) {
            textList.add(Component.translatable("gtceu.multiblock.invalid_structure").withStyle(ChatFormatting.RED));
        } else {
            textList.add(Component.literal("代理执行者").withStyle(ChatFormatting.AQUA));
            MachineDefinition target = getTargetDefinition();
            if (target == null) {
                textList.add(Component.literal("§c未放入可代理的 GTCEu 机器"));
            } else {
                textList.add(Component.literal("§7目标机器: §f").append(machineDisplayName(target)));
                textList.add(Component.literal("§7可用配方类型: §f" + getSelectedRecipeTypeCount()
                        + "§7/§f" + getAllSelectableRecipeTypes().length));
                textList.add(Component.literal("§b代理并行: §f" + formatParallel(getRecipeLogicMaxParallel())));
                textList.add(Component.literal("§7共鸣倍率: §f" + getBoostMultiplier() + "x"));
                textList.add(Component.literal("§d跨配方线程: §f" + formatParallel(getAdditionalThread())));
            }
        }
        textList.add(Component.translatable("gt_shanhai.machine.proxy_executor.name").withStyle(ChatFormatting.GOLD));
    }

    @Override
    public Widget createUIWidget() {
        int width = 190;
        int height = 170;
        WidgetGroup group = new WidgetGroup(0, 0, width, height);
        DraggableScrollableWidgetGroup scrollGroup = new DraggableScrollableWidgetGroup(4, 4, width - 8, height - 32);
        scrollGroup.setBackground(GuiTextures.DISPLAY);

        ComponentPanelWidget textPanel = new ComponentPanelWidget(4, 5, this::buildProxyText);
        textPanel.setMaxWidthLimit(width - 20);
        textPanel.clickHandler((cmd, cd) -> {
            if (cd.isRemote) {
                return;
            }
            if ("proxy_refresh".equals(cmd)) {
                refreshTargetMachine(true);
            } else if ("proxy_next".equals(cmd)) {
                selectNextRecipeType();
            }
        });
        scrollGroup.addWidget(textPanel);
        group.addWidget(scrollGroup);

        group.addWidget(new SlotWidget(targetMachineSlot.storage, 0, 8, height - 24)
                .setBackground(GuiTextures.SLOT)
                .setHoverTooltips(Component.literal("放入要代理执行的 GTCEu 机器")));
        group.addWidget(new SlotWidget(boostSlot.storage, 0, 30, height - 24)
                .setBackground(GuiTextures.SLOT)
                .setHoverTooltips(Component.literal("放入代理共鸣核心提升基础并行")));
        group.addWidget(new SlotWidget(threadBoostSlot.storage, 0, 52, height - 24)
                .setBackground(GuiTextures.SLOT)
                .setHoverTooltips(Component.literal("放入世线残片系列提升跨配方线程")));
        group.setBackground(GuiTextures.BACKGROUND_INVERSE);
        return group;
    }

    private void buildProxyText(List<Component> list) {
        list.add(Component.literal("§b§l代理执行者"));
        list.add(Component.literal(isFormed() ? "§a结构已成型" : "§c结构未成型"));
        MachineDefinition target = getTargetDefinition();
        if (target == null) {
            list.add(Component.literal("§8控制槽: 放入 GTCEu 机器方块"));
            list.add(Component.literal("§8支持单方块机器与多方块控制器"));
        } else {
            list.add(Component.literal("§7目标: §f").append(machineDisplayName(target)));
            GTRecipeType primary = getPrimarySelectedRecipeType();
            list.add(Component.literal("§7当前模式: §f").append(recipeTypeDisplayName(primary)));
            list.add(Component.literal("§7模式数量: §f" + getAllSelectableRecipeTypes().length));
            list.add(Component.literal("§7代理并行: §f" + formatParallel(getRecipeLogicMaxParallel())));
            list.add(Component.literal("§7共鸣倍率: §f" + getBoostMultiplier() + "x"));
            list.add(Component.literal("§7跨配方线程: §f" + formatParallel(getAdditionalThread())));
            list.add(Component.literal("§8机器数量² × 8 × 共鸣核心倍率；终焉枢纽/太初分歧可覆写"));
            list.add(Component.literal("§8世线残片系列提供跨配方线程"));
        }
        list.add(Component.literal(""));
        list.add(Component.literal("")
                .append(ComponentPanelWidget.withButton(Component.literal("§a[刷新目标]"), "proxy_refresh"))
                .append(" ")
                .append(ComponentPanelWidget.withButton(Component.literal("§b[下一模式]"), "proxy_next")));
        list.add(Component.literal(""));
        GTRecipeType[] types = getAllSelectableRecipeTypes();
        if (types.length == 0) {
            list.add(Component.literal("§8暂无可代理配方类型"));
        } else {
            for (int i = 0; i < types.length && i < 8; i++) {
                boolean selected = isRecipeTypeSelected(types[i]);
                list.add(Component.literal(selected ? "§a> " : "§8  ").append(recipeTypeDisplayName(types[i])));
            }
            if (types.length > 8) {
                list.add(Component.literal("§8... +" + (types.length - 8)));
            }
        }
    }

    private void refreshTargetMachine(boolean forceSelectFirst) {
        MachineDefinition target = getTargetDefinition();
        String nextId = target == null ? "" : target.getId().toString();
        if (!forceSelectFirst && nextId.equals(cachedTargetId)) {
            return;
        }
        cachedTargetId = nextId;
        retainAvailableRecipeTypeSelection(forceSelectFirst);
        markDirty();
        if (getLevel() != null && !getLevel().isClientSide) {
            notifyBlockUpdate();
        }
    }

    private void selectNextRecipeType() {
        GTRecipeType[] types = getAllSelectableRecipeTypes();
        if (types.length == 0) {
            return;
        }
        GTRecipeType primary = getPrimarySelectedRecipeType();
        int index = 0;
        for (int i = 0; i < types.length; i++) {
            if (types[i] == primary) {
                index = (i + 1) % types.length;
                break;
            }
        }
        selectOnlyRecipeType(types[index]);
    }

    private MachineDefinition getTargetDefinition() {
        return getMachineDefinition(targetMachineSlot.getStackInSlot(0));
    }

    private long getTargetParallel() {
        ItemStack stack = targetMachineSlot.getStackInSlot(0);
        if (stack == null || stack.isEmpty()) {
            return 1L;
        }
        long machineCount = Math.max(1, Math.min(BASE_MAX_PARALLEL, stack.getCount()));
        int boost = getBoostMultiplier();
        long base = saturatedMultiply(machineCount, machineCount);
        base = saturatedMultiply(base, BASE_PARALLEL_QUADRATIC_MULTIPLIER);
        long parallel = saturatedMultiply(base, boost);
        return Math.max(1L, parallel);
    }

    private long getWhitelistedPartParallel() {
        if (getLevel() == null) {
            return 1L;
        }
        long parallel = 0;
        Direction front = getFrontFacing();
        Direction back = front.getOpposite();
        Direction right = front.getClockWise();
        BlockPos origin = getPos();
        for (int depth = 0; depth < 3; depth++) {
            for (int x = -1; x <= 1; x++) {
                for (int y = -1; y <= 1; y++) {
                    BlockPos pos = origin.relative(back, depth).relative(right, x).above(y);
                    MetaMachine machine = MetaMachine.getMachine(getLevel(), pos);
                    if (machine instanceof DShanhaiMaintenanceHatchMachine hatch) {
                        long value = hatch.getRawParallel();
                        if (value > 0) {
                            parallel = saturatedAdd(parallel, value);
                        }
                    } else if (machine instanceof DShanhaiDivergenceEngineMachine hatch) {
                        int value = hatch.getCurrentParallel();
                        if (value > 0) {
                            parallel = saturatedAdd(parallel, value);
                        }
                    }
                }
            }
        }
        return Math.max(1L, parallel);
    }

    private long getThreadBoost() {
        ItemStack stack = threadBoostSlot.getStackInSlot(0);
        ResourceLocation id = getItemId(stack);
        if (id == null) {
            return 0L;
        }
        long base = PrimordialOmegaEngineModuleBase.getThreadBoostValue(id.toString());
        if (base <= 0) {
            return 0L;
        }
        long count = Math.max(1, stack.getCount());
        long value = base * count;
        return value < 0 ? Long.MAX_VALUE : value;
    }

    private int getBoostMultiplier() {
        ItemStack stack = boostSlot.getStackInSlot(0);
        ResourceLocation id = getItemId(stack);
        return id == null ? 1 : BOOST_MULTIPLIERS.getInt(id);
    }

    private static boolean hasProxyRecipeTypes(ItemStack stack) {
        MachineDefinition definition = getMachineDefinition(stack);
        return definition != null && definition.getRecipeTypes() != null && definition.getRecipeTypes().length > 0;
    }

    private static MachineDefinition getMachineDefinition(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        Item item = stack.getItem();
        if (item instanceof MetaMachineItem machineItem) {
            return machineItem.getDefinition();
        }
        if (item instanceof BlockItem blockItem && blockItem.getBlock() instanceof IMachineBlock machineBlock) {
            return machineBlock.getDefinition();
        }
        return null;
    }

    private static boolean isProxyBoostItem(ItemStack stack) {
        ResourceLocation id = getItemId(stack);
        return id != null && BOOST_MULTIPLIERS.containsKey(id);
    }

    private static Object2IntMap<ResourceLocation> createBoostMultipliers() {
        Object2IntOpenHashMap<ResourceLocation> multipliers = new Object2IntOpenHashMap<>();
        multipliers.defaultReturnValue(1);
        registerBoostMultipliers(multipliers, 1, 16);
        registerBoostMultipliers(multipliers, 2, 256);
        registerBoostMultipliers(multipliers, 3, 1024);
        registerBoostMultipliers(multipliers, 4, 4096);
        registerBoostMultipliers(multipliers, 5, 16384);
        registerBoostMultipliers(multipliers, 6, 65536);
        return multipliers;
    }

    private static void registerBoostMultipliers(Object2IntMap<ResourceLocation> multipliers, int mk, int baseMultiplier) {
        String baseId = "proxy_resonance_core_mk" + mk;
        multipliers.put(new ResourceLocation("dishanhai", baseId), baseMultiplier);
        multipliers.put(new ResourceLocation("dishanhai", baseId + "a"), baseMultiplier * 2);
        multipliers.put(new ResourceLocation("dishanhai", baseId + "b"), baseMultiplier * 3);
    }

    private static boolean isThreadBoostItem(ItemStack stack) {
        ResourceLocation id = getItemId(stack);
        return id == null || PrimordialOmegaEngineModuleBase.getThreadBoostValue(id.toString()) > 0;
    }

    private static ResourceLocation getItemId(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        return ForgeRegistries.ITEMS.getKey(stack.getItem());
    }

    private static Component machineDisplayName(MachineDefinition definition) {
        if (definition == null) {
            return Component.literal("unknown");
        }
        String key = definition.getDescriptionId();
        if (key != null) {
            return Component.translatable(key);
        }
        return Component.literal(definition.getId().toString());
    }

    private static Component recipeTypeDisplayName(GTRecipeType type) {
        if (type == null || type.registryName == null) {
            return Component.literal("unknown");
        }
        String namespace = type.registryName.getNamespace();
        String path = type.registryName.getPath();
        String[] keys = new String[] {
                "gtceu." + path,
                "gtceu.recipe_type." + path,
                type.registryName.toLanguageKey(),
                "recipe_type." + path,
                "gtceu.recipe_type." + namespace + "." + path
        };
        for (String key : keys) {
            String translated = Component.translatable(key).getString();
            if (!translated.equals(key)) {
                return Component.translatable(key);
            }
        }
        return Component.literal(type.registryName.toString());
    }

    private static long saturatedMultiply(long a, long b) {
        if (a <= 0 || b <= 0) {
            return 0L;
        }
        if (a > Long.MAX_VALUE / b) {
            return Long.MAX_VALUE;
        }
        return a * b;
    }

    private static long saturatedAdd(long a, long b) {
        if (b > 0 && a > Long.MAX_VALUE - b) {
            return Long.MAX_VALUE;
        }
        return a + b;
    }

    private static String formatParallel(long parallel) {
        if (parallel >= Long.MAX_VALUE / 2) {
            return "Long.MAX_VALUE";
        }
        if (parallel >= 1_000_000_000) {
            return String.format(java.util.Locale.ROOT, "%.2GE", parallel / 1_000_000_000.0D);
        }
        if (parallel >= 1_000_000) {
            return String.format(java.util.Locale.ROOT, "%.2GM", parallel / 1_000_000.0D);
        }
        if (parallel >= 1_000) {
            return String.format(java.util.Locale.ROOT, "%.2GK", parallel / 1_000.0D);
        }
        return String.valueOf(parallel);
    }

    public static BlockPattern createPattern(MultiblockMachineDefinition definition) {
        Block casing = getBlock("gtceu", "bronze_machine_casing");
        return FactoryBlockPattern.start(RelativeDirection.RIGHT, RelativeDirection.UP, RelativeDirection.BACK)
                .aisle("CCC", "C~C", "CCC")
                .aisle("CCC", "CCC", "CCC")
                .aisle("CCC", "CCC", "CCC")
                .where('~', Predicates.controller(Predicates.blocks(definition.getBlock())))
                .where('C', Predicates.blocks(casing)
                        .or(Predicates.abilities(PartAbility.IMPORT_ITEMS).setPreviewCount(1))
                        .or(Predicates.abilities(PartAbility.EXPORT_ITEMS).setPreviewCount(1))
                        .or(Predicates.abilities(PartAbility.IMPORT_FLUIDS).setPreviewCount(1))
                        .or(Predicates.abilities(PartAbility.EXPORT_FLUIDS).setPreviewCount(1))
                        .or(Predicates.abilities(PartAbility.INPUT_ENERGY).setPreviewCount(1))
                        .or(proxyParallelOverrideParts()))
                .where(' ', Predicates.any())
                .build();
    }

    public static MultiblockMachineDefinition register() {
        MultiblockMachineDefinition def = GTDishanhaiRegistration.REGISTRATE
                .multiblock("proxy_executor", ProxyExecutorMachine::new)
                .rotationState(RotationState.NON_Y_AXIS)
                .recipeTypes(DShanhaiRecipeTypes.PROXY_EXECUTION)
                .appearanceBlock(() -> getBlock("gtceu", "bronze_machine_casing"))
                .pattern(ProxyExecutorMachine::createPattern)
                .workableCasingRenderer(
                        new ResourceLocation("gtceu", "block/casings/steam/bronze/side"),
                        new ResourceLocation(MOD_ID, "block/multiblock/proxy_executor"))
                .register();

        def.setTooltipBuilder((stack, tooltips) -> {
            tooltips.add(DShanhaiTextUtil.createElectricText("代理执行者"));
            tooltips.add(Component.literal(""));
            tooltips.add(Component.literal("§7控制槽放入 GTCEu 机器，即可代理运行其配方类型"));
            tooltips.add(Component.literal("§b支持单方块机器与多方块控制器"));
            tooltips.add(Component.literal("§a机器堆叠数量² × 8 × 代理共鸣核心倍率"));
            tooltips.add(Component.literal("§6代理共鸣核心 MK1-MK6 分别提供: 16x, 256x, 1024x, 4096x, 16384x, 65536x 共鸣核心倍率"));
            tooltips.add(Component.literal("§6每级 A/B 子核心分别提供本级 2x/3x 共鸣核心倍率"));
            tooltips.add(Component.literal("§d世线残片系列提供跨配方线程"));
            tooltips.add(Component.literal("§a结构仓室仅允许终焉聚合枢纽/太初分歧引擎覆写并行"));
            tooltips.add(Component.literal("§a3x3x3 青铜结构"));
            tooltips.add(Component.literal("§8NXY Proxy 思路移植"));
        });
        return def;
    }

    private static com.gregtechceu.gtceu.api.pattern.TraceabilityPredicate proxyParallelOverrideParts() {
        com.gregtechceu.gtceu.api.pattern.TraceabilityPredicate predicate = Predicates.blocks(Blocks.BARRIER);
        if (DShanhaiMachines.MAINTENANCE_HATCH != null) {
            predicate = predicate.or(Predicates.blocks(DShanhaiMachines.MAINTENANCE_HATCH.getBlock()).setPreviewCount(1));
        }
        if (DShanhaiMachines.DIVERGENCE_ENGINE != null) {
            predicate = predicate.or(Predicates.blocks(DShanhaiMachines.DIVERGENCE_ENGINE.getBlock()).setPreviewCount(1));
        }
        return predicate;
    }

    private static Block getBlock(String namespace, String path) {
        Block block = ForgeRegistries.BLOCKS.getValue(new ResourceLocation(namespace, path));
        return block == null ? Blocks.BARRIER : block;
    }
}
