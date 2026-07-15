package com.dishanhai.gt_shanhai.common.machine.primordial.module;

import com.dishanhai.gt_shanhai.api.DShanhaiTextUtil;
import com.dishanhai.gt_shanhai.common.machine.primordial.PrimordialOmegaEngineModuleBase;
import com.google.common.primitives.Ints;
import com.gregtechceu.gtceu.api.capability.recipe.IO;
import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity;
import com.gregtechceu.gtceu.api.machine.TickableSubscription;
import com.gregtechceu.gtceu.api.machine.trait.NotifiableItemStackHandler;
import com.lowdragmc.lowdraglib.gui.widget.SlotWidget;
import com.lowdragmc.lowdraglib.gui.widget.Widget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public abstract class PrimordialParallelProcessingModuleBase extends PrimordialOmegaEngineModuleBase {

    private static final Map<Item, Long> ITEM_PARALLEL_MAP = new HashMap<>();
    private static final long DEFAULT_PARALLEL = 64L;
    private long currentParallel = DEFAULT_PARALLEL;
    private TickableSubscription parallelScanSubs;
    private final NotifiableItemStackHandler machineStorage;

    protected PrimordialParallelProcessingModuleBase(IMachineBlockEntity holder, Object... args) {
        super(holder, args);
        if (getUuid() == null) setUuid(UUID.randomUUID());
        initItems();
        machineStorage = createMachineStorage();
    }

    protected abstract String getMachineNameKey();

    protected abstract String getMachineModeKey();

    @Override
    protected NotifiableItemStackHandler[] getPersistedStorages() {
        return new NotifiableItemStackHandler[] { machineStorage, threadBoostSlot };
    }

    private NotifiableItemStackHandler createMachineStorage() {
        var handler = new NotifiableItemStackHandler(this, 1, IO.NONE, IO.BOTH);
        handler.setFilter(stack -> stack == null || stack.isEmpty() || ITEM_PARALLEL_MAP.containsKey(stack.getItem()));
        return handler;
    }

    private static void initItems() {
        if (!ITEM_PARALLEL_MAP.isEmpty()) return;
        String[] ids = { "wzrm", "wzjc", "wzcz1", "wzsb", "wzcz2", "wzqs", "wzgl", "wzsw",
                "wzcx", "wzyh", "wzcz3", "create_mk", "reality_anchor_module", "wzax", "wzxc", "wzhy", "wzdf" };
        long[] parallels = { 128L, 256L, 512L, 2048L, 16384L, 65536L, 524288L, 2097152L,
                268435456L, 2147483647L, 4611686018427387903L, Long.MAX_VALUE, 6917529027641081855L,
                4096L, 1024L, 1048576L, 536870912L };
        for (int i = 0; i < ids.length; i++) {
            Item item = ForgeRegistries.ITEMS.getValue(new ResourceLocation("dishanhai", ids[i]));
            if (item != null) ITEM_PARALLEL_MAP.put(item, parallels[i]);
        }
    }

    private void scanBoostItem() {
        var stack = machineStorage.storage.getStackInSlot(0);
        long base = ITEM_PARALLEL_MAP.getOrDefault(stack.getItem(), DEFAULT_PARALLEL);
        currentParallel = applyModuleCountParallelMultiplier(base, stack.getCount());
    }

    @Override
    public void onStructureFormed() {
        super.onStructureFormed();
        scanBoostItem();
        parallelScanSubs = subscribeServerTick(parallelScanSubs, () -> {
            if (getOffsetTimer() % 3 == 0) scanBoostItem();
        });
    }

    @Override
    public void onStructureInvalid() {
        super.onStructureInvalid();
        if (parallelScanSubs != null) {
            parallelScanSubs.unsubscribe();
            parallelScanSubs = null;
        }
    }

    @Override
    public int getMaxParallel() {
        return Ints.saturatedCast(currentParallel);
    }

    @Override
    public long getMaxVoltage() {
        return Long.MAX_VALUE;
    }

    @Override
    public int getTier() {
        return 9;
    }

    @Override
    public Widget createUIWidget() {
        Widget widget = super.createUIWidget();
        if (widget instanceof WidgetGroup group) {
            var size = group.getSize();
            var slot = new SlotWidget(machineStorage.storage, 0, size.width - 30, size.height - 30, true, true);
            configureParallelModuleSlot(slot);
            group.addWidget(slot);
        }
        return widget;
    }

    @Override
    protected void addParallelDisplay(List<Component> textList) {
        var stack = machineStorage.storage.getStackInSlot(0);
        var itemName = stack.isEmpty()
                ? Component.translatable("gt_shanhai.machine.module_slot.empty").withStyle(ChatFormatting.GRAY)
                : stack.getHoverName().copy().withStyle(ChatFormatting.AQUA);
        textList.add(Component.literal("").append(DShanhaiTextUtil.createElectricText("已安装: ")).append(itemName));
        boolean infinite = currentParallel >= Long.MAX_VALUE / 2;
        var parallelText = infinite ? DShanhaiTextUtil.createUltimateRainbow("∞ 无限")
                : DShanhaiTextUtil.createAuroraText(String.format("%,d", currentParallel));
        textList.add(Component.literal("").append(DShanhaiTextUtil.createElectricText("并行上限: ")).append(parallelText));
        addThreadBoostDisplay(textList);
    }

    @Override
    public void addDisplayText(List<Component> textList) {
        if (isFormed()) {
            addHostStatusDisplay(textList);
            if (!canModuleWork()) {
                textList.add(Component.translatable(getMachineNameKey()).withStyle(ChatFormatting.GOLD));
                return;
            }
            addEnergyDisplay(textList);
            addParallelDisplay(textList);
            addWorkingStatus(textList);
            textList.add(Component.translatable(getMachineModeKey()).withStyle(ChatFormatting.GREEN));
        } else {
            textList.add(Component.translatable("gtceu.multiblock.invalid_structure").withStyle(ChatFormatting.RED));
        }
        textList.add(Component.translatable(getMachineNameKey()).withStyle(ChatFormatting.GOLD));
    }
}
