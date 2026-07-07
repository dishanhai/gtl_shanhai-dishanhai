package com.dishanhai.gt_shanhai.common.machine.primordial.module.core;
import com.dishanhai.gt_shanhai.common.machine.primordial.*;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraftforge.registries.ForgeRegistries;

import com.google.common.primitives.Ints;

import com.dishanhai.gt_shanhai.api.DShanhaiTextUtil;

import com.gregtechceu.gtceu.api.capability.recipe.IO;
import com.gregtechceu.gtceu.api.gui.fancy.IFancyUIProvider;
import com.gregtechceu.gtceu.api.gui.fancy.TabsWidget;
import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity;
import com.gregtechceu.gtceu.api.machine.TickableSubscription;
import com.gregtechceu.gtceu.api.machine.trait.NotifiableItemStackHandler;

import com.lowdragmc.lowdraglib.gui.widget.SlotWidget;
import com.lowdragmc.lowdraglib.gui.widget.Widget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;

import java.util.List;
import java.util.UUID;

public class PrimordialBiologicalCore extends PrimordialOmegaEngineModuleBase {

    private static final java.util.Map<Item, Long> ITEM_PARALLEL_MAP = new java.util.HashMap<>();
    private static final long DEFAULT_PARALLEL = 64L;

    private static Item ITEM_WZRM, ITEM_WZJC, ITEM_WZCZ1, ITEM_WZSB, ITEM_WZCZ2;
    private static Item ITEM_WZQS, ITEM_WZGL, ITEM_WZSW, ITEM_WZCX, ITEM_WZYH, ITEM_WZCZ3, ITEM_CREATE_MK, ITEM_WZAX, ITEM_WZXC, ITEM_WZHY, ITEM_WZDF, ITEM_REALITY_ANCHOR;

    private long currentParallel = DEFAULT_PARALLEL;
    private TickableSubscription parallelScanSubs;
    private final NotifiableItemStackHandler machineStorage;

    public PrimordialBiologicalCore(IMachineBlockEntity holder, Object... args) {
        super(holder, args);
        if (getUuid() == null) setUuid(UUID.randomUUID());
        initItems();
        machineStorage = createMachineStorage();
    }

    private NotifiableItemStackHandler createMachineStorage() {
        var handler = new NotifiableItemStackHandler(this, 1, IO.NONE, IO.BOTH);
        handler.setFilter(stack -> stack == null || stack.isEmpty() || ITEM_PARALLEL_MAP.containsKey(stack.getItem()));
        return handler;
    }

    @Override
    public Widget createUIWidget() {
        Widget w = super.createUIWidget();
        if (w instanceof WidgetGroup g) {
            var s = g.getSize();
            var slot = new SlotWidget(machineStorage.storage, 0, s.width - 30, s.height - 30, true, true);
            configureParallelModuleSlot(slot);
            g.addWidget(slot);
        }
        return w;
    }

    private static void initItems() {
        if (ITEM_WZRM != null) return;
        ITEM_WZRM = ForgeRegistries.ITEMS.getValue(new ResourceLocation("dishanhai", "wzrm"));
        ITEM_WZJC = ForgeRegistries.ITEMS.getValue(new ResourceLocation("dishanhai", "wzjc"));
        ITEM_WZCZ1 = ForgeRegistries.ITEMS.getValue(new ResourceLocation("dishanhai", "wzcz1"));
        ITEM_WZSB = ForgeRegistries.ITEMS.getValue(new ResourceLocation("dishanhai", "wzsb"));
        ITEM_WZCZ2 = ForgeRegistries.ITEMS.getValue(new ResourceLocation("dishanhai", "wzcz2"));
        ITEM_WZQS = ForgeRegistries.ITEMS.getValue(new ResourceLocation("dishanhai", "wzqs"));
        ITEM_WZGL = ForgeRegistries.ITEMS.getValue(new ResourceLocation("dishanhai", "wzgl"));
        ITEM_WZSW = ForgeRegistries.ITEMS.getValue(new ResourceLocation("dishanhai", "wzsw"));
        ITEM_WZCX = ForgeRegistries.ITEMS.getValue(new ResourceLocation("dishanhai", "wzcx"));
        ITEM_WZYH = ForgeRegistries.ITEMS.getValue(new ResourceLocation("dishanhai", "wzyh"));
        ITEM_WZCZ3 = ForgeRegistries.ITEMS.getValue(new ResourceLocation("dishanhai", "wzcz3"));
        ITEM_CREATE_MK = ForgeRegistries.ITEMS.getValue(new ResourceLocation("dishanhai", "create_mk"));
        ITEM_WZAX = ForgeRegistries.ITEMS.getValue(new ResourceLocation("dishanhai", "wzax"));
        ITEM_WZXC = ForgeRegistries.ITEMS.getValue(new ResourceLocation("dishanhai", "wzxc"));
        ITEM_WZHY = ForgeRegistries.ITEMS.getValue(new ResourceLocation("dishanhai", "wzhy"));
        ITEM_WZDF = ForgeRegistries.ITEMS.getValue(new ResourceLocation("dishanhai", "wzdf"));
        ITEM_REALITY_ANCHOR = ForgeRegistries.ITEMS.getValue(new ResourceLocation("dishanhai", "reality_anchor_module"));


        ITEM_PARALLEL_MAP.put(ITEM_WZRM, 128L);
        ITEM_PARALLEL_MAP.put(ITEM_WZJC, 256L);
        ITEM_PARALLEL_MAP.put(ITEM_WZCZ1, 512L);
        ITEM_PARALLEL_MAP.put(ITEM_WZSB, 2048L);
        ITEM_PARALLEL_MAP.put(ITEM_WZCZ2, 16384L);
        ITEM_PARALLEL_MAP.put(ITEM_WZQS, 65536L);
        ITEM_PARALLEL_MAP.put(ITEM_WZGL, 524288L);
        ITEM_PARALLEL_MAP.put(ITEM_WZSW, 2097152L);
        ITEM_PARALLEL_MAP.put(ITEM_WZCX, 268435456L);
        ITEM_PARALLEL_MAP.put(ITEM_WZYH, 2147483647L);
        ITEM_PARALLEL_MAP.put(ITEM_WZCZ3, 4611686018427387903L);
        ITEM_PARALLEL_MAP.put(ITEM_CREATE_MK, Long.MAX_VALUE);
        ITEM_PARALLEL_MAP.put(ITEM_REALITY_ANCHOR, 6917529027641081855L);
        ITEM_PARALLEL_MAP.put(ITEM_WZAX, 4096L);
        ITEM_PARALLEL_MAP.put(ITEM_WZXC, 1024L);
        ITEM_PARALLEL_MAP.put(ITEM_WZHY, 1048576L);
        ITEM_PARALLEL_MAP.put(ITEM_WZDF, 536870912L);

    }

    private void scanBoostItem() {
        currentParallel = ITEM_PARALLEL_MAP.getOrDefault(machineStorage.storage.getStackInSlot(0).getItem(), DEFAULT_PARALLEL);
    }

    @Override
    public void onStructureFormed() {
        super.onStructureFormed();
        scanBoostItem();
        parallelScanSubs = subscribeServerTick(parallelScanSubs, () -> { if (getOffsetTimer() % 3 == 0) scanBoostItem(); });
    }

    @Override
    public void onStructureInvalid() {
        super.onStructureInvalid();
        if (parallelScanSubs != null) { parallelScanSubs.unsubscribe(); parallelScanSubs = null; }
    }

    @Override
    protected NotifiableItemStackHandler[] getPersistedStorages() {
        return new NotifiableItemStackHandler[]{ machineStorage, threadBoostSlot };
    }

    @Override
    public PrimordialBiologicalCoreLogic createRecipeLogic(Object... args) {
        return new PrimordialBiologicalCoreLogic(this);
    }

    @Override
    public PrimordialBiologicalCoreLogic getRecipeLogic() {
        return (PrimordialBiologicalCoreLogic) recipeLogic;
    }

    @Override
    public int getMaxParallel() { return Ints.saturatedCast(currentParallel); }
    public long getCurrentParallel() { return currentParallel; }

    @Override
    public long getMaxVoltage() { return Long.MAX_VALUE; }
    @Override
    public int getTier() { return 9; }

    @Override
    public List<IFancyUIProvider> getSubTabs() { return java.util.Collections.emptyList(); }

    @Override
    public void attachSideTabs(TabsWidget tabsWidget) {
        super.attachSideTabs(tabsWidget);
    }

    @Override
    protected void addParallelDisplay(List<Component> textList) {
        var stack = machineStorage.storage.getStackInSlot(0);
        var itemName = stack.isEmpty()
            ? Component.translatable("gt_shanhai.machine.module_slot.empty").withStyle(ChatFormatting.GRAY)
            : stack.getHoverName().copy().withStyle(ChatFormatting.AQUA);
        textList.add(Component.literal("").append(DShanhaiTextUtil.createElectricText("已安装: ")).append(itemName));
        long p = getCurrentParallel();
        boolean inf = p >= Long.MAX_VALUE / 2;
        textList.add(Component.literal("").append(DShanhaiTextUtil.createElectricText("并行上限: "))
                .append(inf ? DShanhaiTextUtil.createUltimateRainbow("∞ 无限") : DShanhaiTextUtil.createAuroraText(String.format("%,d", p))));
        addThreadBoostDisplay(textList);
    }

    @Override
    public void addDisplayText(List<Component> textList) {
        if (isFormed()) {
            addHostStatusDisplay(textList);
            if (!canModuleWork()) return;
            addEnergyDisplay(textList);
            addParallelDisplay(textList);
            addWorkingStatus(textList);
            textList.add(Component.translatable("gt_shanhai.machine.primordial_biological_core.mode").withStyle(ChatFormatting.GREEN));
        } else {
            textList.add(Component.translatable("gtceu.multiblock.invalid_structure").withStyle(ChatFormatting.RED));
        }
        textList.add(Component.translatable("gt_shanhai.machine.primordial_biological_core.name").withStyle(ChatFormatting.GOLD));
    }
}


