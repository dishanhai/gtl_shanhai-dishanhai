package com.dishanhai.gt_shanhai.common.machine.part;

import appeng.api.crafting.IPatternDetails;
import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.networking.IGrid;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;

import com.gregtechceu.gtceu.api.gui.GuiTextures;
import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity;
import com.gregtechceu.gtceu.integration.ae2.slot.ExportOnlyAESlot;
import com.gregtechceu.gtceu.integration.ae2.slot.ExportOnlyAEItemList;
import com.gregtechceu.gtceu.integration.ae2.slot.ExportOnlyAEFluidList;
import com.lowdragmc.lowdraglib.gui.widget.LabelWidget;
import com.lowdragmc.lowdraglib.gui.widget.SlotWidget;
import com.lowdragmc.lowdraglib.gui.widget.Widget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;
import com.lowdragmc.lowdraglib.misc.ItemStackTransfer;
import com.lowdragmc.lowdraglib.syncdata.annotation.DescSynced;
import com.lowdragmc.lowdraglib.syncdata.annotation.Persisted;
import com.lowdragmc.lowdraglib.syncdata.field.ManagedFieldHolder;
import com.lowdragmc.lowdraglib.utils.Position;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;

import org.gtlcore.gtlcore.api.machine.trait.MEStock.IMESlot;
import org.gtlcore.gtlcore.api.machine.trait.MEStock.IOptimizedMEList;
import org.gtlcore.gtlcore.common.machine.multiblock.part.MEDualHatchStockPartMachine;

import java.lang.reflect.Field;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import com.gregtechceu.gtceu.api.machine.MetaMachine;

/**
 * Encoded patterns configure network-backed inputs; only the inherited handlers consume materials.
 */
public class MEStellarStockPartMachine extends MEDualHatchStockPartMachine {

    private static final int BASE_PATTERN_SLOTS = 5;
    private static final int BASE_CONFIG_SIZE = 64;
    private static final int CONFIG_PAGE_SIZE = 16;
    private static final ManagedFieldHolder MANAGED_FIELD_HOLDER =
            new ManagedFieldHolder(MEStellarStockPartMachine.class,
                    MEDualHatchStockPartMachine.MANAGED_FIELD_HOLDER);
    private static final String STATUS_PREFIX = "gt_shanhai.machine.me_stellar_stock_part_machine.status.";

    @Persisted
    private final ItemStackTransfer patternInventory;
    @Persisted
    private CompoundTag savedStockConfiguration = new CompoundTag();
    @DescSynced
    private String patternStatus = STATUS_PREFIX + "empty";

    private List<ItemStack> decodedPatterns = List.of();
    private List<MEStellarStockTargetPlanner.Input<AEKey>> cachedInputs;
    private boolean patternDirty = true;

    public MEStellarStockPartMachine(IMachineBlockEntity holder, Object... args) {
        super(holder, args);
        patternInventory = new ItemStackTransfer(BASE_PATTERN_SLOTS) {
            @Override
            public int getSlotLimit(int slot) {
                return 1;
            }
        };
        patternInventory.setFilter(PatternDetailsHelper::isEncodedPattern);
        patternInventory.setOnContentsChanged(() -> {
            patternDirty = true;
            markDirty();
        });
    }

    @Override
    public ManagedFieldHolder getFieldHolder() {
        return MANAGED_FIELD_HOLDER;
    }

    @Override
    public void onLoad() {
        super.onLoad();
        patternDirty = true;
        if (!isRemote()) {
            updatePatternConfiguration();
        }
    }

    @Override
    public void autoIO() {
        int interval = getOffset() == 0 ? 50 : Math.max(1, getOffset());
        if (!isRemote() && (patternDirty || getOffsetTimer() % interval == 0)) {
            updatePatternConfiguration();
        }
        super.autoIO();
    }

    @Override
    protected void setAutoPullMode(int mode) {
        if (patternInventory == null || patternInventory.getStackInSlot(0).isEmpty()) {
            super.setAutoPullMode(mode);
        }
    }

    private void updatePatternConfiguration() {
        patternDirty = false;
        boolean hasPattern = false;
        for (int i = 0; i < patternInventory.getSlots(); i++) {
            if (!patternInventory.getStackInSlot(i).isEmpty()) {
                hasPattern = true;
                break;
            }
        }
        if (!hasPattern) {
            decodedPatterns = List.of();
            cachedInputs = null;
            ensureCapacity(BASE_CONFIG_SIZE);
            restoreStockConfiguration();
            patternStatus = STATUS_PREFIX + "empty";
            return;
        }

        if (!savedStockConfiguration.contains("AutoPullMode")) {
            saveStockConfiguration();
            super.setAutoPullMode(AUTO_PULL_OFF);
        } else if (getAutoPullMode() != AUTO_PULL_OFF) {
            super.setAutoPullMode(AUTO_PULL_OFF);
        }

        List<ItemStack> currentPatterns = new ArrayList<>();
        for (int i = 0; i < patternInventory.getSlots(); i++) {
            ItemStack current = patternInventory.getStackInSlot(i);
            currentPatterns.add(current.copy());
        }
        if (!samePatterns(currentPatterns, decodedPatterns)) {
            decodedPatterns = List.copyOf(currentPatterns);
            try {
                List<IPatternDetails> details = new ArrayList<>();
                for (ItemStack current : currentPatterns) {
                    if (!current.isEmpty()) {
                        IPatternDetails decoded = PatternDetailsHelper.decodePattern(current, getLevel(), false);
                        if (decoded == null) {
                            throw new IllegalArgumentException("Invalid encoded pattern");
                        }
                        details.add(decoded);
                    }
                }
                cachedInputs = readInputs(details);
                ensureCapacity(MEStellarStockTargetPlanner.requiredCapacity(cachedInputs));
            } catch (RuntimeException ignored) {
                cachedInputs = null;
            }
        }
        if (cachedInputs == null) {
            applyTargets(Map.of());
            patternStatus = STATUS_PREFIX + "invalid";
            return;
        }

        IGrid grid = getMainNode().getGrid();
        KeyCounter available = grid == null ? new KeyCounter()
                : grid.getStorageService().getCachedInventory();
        try {
            applyTargets(MEStellarStockTargetPlanner.plan(cachedInputs, available::get, aeItemHandler.getSlots()));
            patternStatus = STATUS_PREFIX + "active";
        } catch (ArithmeticException | IllegalArgumentException ignored) {
            applyTargets(Map.of());
            patternStatus = STATUS_PREFIX + "invalid";
        }
    }

    private static List<MEStellarStockTargetPlanner.Input<AEKey>> readInputs(List<IPatternDetails> details) {
        List<MEStellarStockTargetPlanner.Input<AEKey>> inputs = new ArrayList<>();
        for (IPatternDetails detail : details) {
            for (IPatternDetails.IInput input : detail.getInputs()) {
                List<MEStellarStockTargetPlanner.Candidate<AEKey>> candidates = new ArrayList<>();
                for (GenericStack candidate : input.getPossibleInputs()) {
                    if (candidate != null && (candidate.what() instanceof AEItemKey
                            || candidate.what() instanceof AEFluidKey)) {
                        candidates.add(new MEStellarStockTargetPlanner.Candidate<>(
                                candidate.what(), candidate.amount()));
                    }
                }
                inputs.add(new MEStellarStockTargetPlanner.Input<>(List.copyOf(candidates), input.getMultiplier()));
            }
        }
        return List.copyOf(inputs);
    }

    private static boolean samePatterns(List<ItemStack> first, List<ItemStack> second) {
        if (first.size() != second.size()) {
            return false;
        }
        for (int i = 0; i < first.size(); i++) {
            if (!ItemStack.isSameItemSameTags(first.get(i), second.get(i))
                    || first.get(i).getCount() != second.get(i).getCount()) {
                return false;
            }
        }
        return true;
    }

    private void ensureCapacity(int required) {
        int capacity = Math.max(BASE_CONFIG_SIZE, ((required + CONFIG_PAGE_SIZE - 1) / CONFIG_PAGE_SIZE) * CONFIG_PAGE_SIZE);
        if (aeItemHandler.getSlots() == capacity) {
            return;
        }
        try {
            ExportOnlyAEItemList oldItems = aeItemHandler;
            ExportOnlyAEFluidList oldFluids = aeFluidHandler;
            Constructor<?> itemConstructor = oldItems.getClass().getDeclaredConstructor(
                    MEDualHatchStockPartMachine.class, MetaMachine.class, int.class);
            Constructor<?> fluidConstructor = oldFluids.getClass().getDeclaredConstructor(
                    MEDualHatchStockPartMachine.class, MetaMachine.class, int.class);
            itemConstructor.setAccessible(true);
            fluidConstructor.setAccessible(true);
            ExportOnlyAEItemList newItems = (ExportOnlyAEItemList) itemConstructor.newInstance(this, this, capacity);
            ExportOnlyAEFluidList newFluids = (ExportOnlyAEFluidList) fluidConstructor.newInstance(this, this, capacity);
            copyConfiguration(oldItems, newItems);
            copyConfiguration(oldFluids, newFluids);
            replaceBackingInventory(oldItems, newItems);
            replaceBackingInventory(oldFluids, newFluids);
            notifyConfigChanged();
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("Unable to resize ME stellar stock handlers", exception);
        }
    }

    private static void replaceBackingInventory(ExportOnlyAEItemList target, ExportOnlyAEItemList resized)
            throws ReflectiveOperationException {
        Field inventory = ExportOnlyAEItemList.class.getDeclaredField("inventory");
        inventory.setAccessible(true);
        inventory.set(target, resized.getInventory());
        Field itemTransfer = ExportOnlyAEItemList.class.getDeclaredField("itemTransfer");
        itemTransfer.setAccessible(true);
        itemTransfer.set(target, null);
        for (ExportOnlyAESlot slot : target.getInventory()) {
            slot.setOnContentsChanged(target::onContentsChanged);
            if (slot instanceof IMESlot) {
                ((IMESlot) slot).setOnConfigChanged(((IOptimizedMEList) target)::onConfigChanged);
            }
        }
    }

    private static void replaceBackingInventory(ExportOnlyAEFluidList target, ExportOnlyAEFluidList resized)
            throws ReflectiveOperationException {
        Field inventory = ExportOnlyAEFluidList.class.getDeclaredField("inventory");
        inventory.setAccessible(true);
        inventory.set(target, resized.getInventory());
        Field fluidStorages = ExportOnlyAEFluidList.class.getDeclaredField("fluidStorages");
        fluidStorages.setAccessible(true);
        fluidStorages.set(target, null);
        for (ExportOnlyAESlot slot : target.getInventory()) {
            slot.setOnContentsChanged(target::onContentsChanged);
            if (slot instanceof IMESlot) {
                ((IMESlot) slot).setOnConfigChanged(((IOptimizedMEList) target)::onConfigChanged);
            }
        }
    }

    private static void copyConfiguration(ExportOnlyAEItemList source, ExportOnlyAEItemList target) {
        int limit = Math.min(source.getSlots(), target.getSlots());
        for (int i = 0; i < limit; i++) {
            GenericStack config = source.getInventory()[i].getConfig();
            ExportOnlyAESlot targetSlot = target.getInventory()[i];
            ((IMESlot) targetSlot).setConfigWithoutNotify(config);
            targetSlot.setStock(source.getInventory()[i].getStock());
        }
    }

    private static void copyConfiguration(ExportOnlyAEFluidList source, ExportOnlyAEFluidList target) {
        int limit = Math.min(source.getInventory().length, target.getInventory().length);
        for (int i = 0; i < limit; i++) {
            GenericStack config = source.getInventory()[i].getConfig();
            ExportOnlyAESlot targetSlot = target.getInventory()[i];
            ((IMESlot) targetSlot).setConfigWithoutNotify(config);
            targetSlot.setStock(source.getInventory()[i].getStock());
        }
    }

    private void saveStockConfiguration() {
        CompoundTag saved = new CompoundTag();
        saved.putInt("AutoPullMode", getAutoPullMode());
        for (int i = 0; i < aeItemHandler.getSlots(); i++) {
            GenericStack item = aeItemHandler.getInventory()[i].getConfig();
            GenericStack fluid = aeFluidHandler.getInventory()[i].getConfig();
            if (item != null) {
                saved.put("Item" + i, GenericStack.writeTag(item));
            }
            if (fluid != null) {
                saved.put("Fluid" + i, GenericStack.writeTag(fluid));
            }
        }
        savedStockConfiguration = saved;
        markDirty();
    }

    private void restoreStockConfiguration() {
        if (!savedStockConfiguration.contains("AutoPullMode")) {
            return;
        }
        CompoundTag saved = savedStockConfiguration;
        savedStockConfiguration = new CompoundTag();
        int mode = saved.getInt("AutoPullMode");
        super.setAutoPullMode(mode);
        if (mode == AUTO_PULL_OFF) {
            for (int i = 0; i < aeItemHandler.getSlots(); i++) {
                setConfig(aeItemHandler.getInventory()[i], saved.contains("Item" + i)
                        ? GenericStack.readTag(saved.getCompound("Item" + i)) : null);
                setConfig(aeFluidHandler.getInventory()[i], saved.contains("Fluid" + i)
                        ? GenericStack.readTag(saved.getCompound("Fluid" + i)) : null);
            }
            notifyConfigChanged();
        }
        markDirty();
    }

    private void applyTargets(Map<AEKey, Long> targets) {
        var iterator = targets.entrySet().iterator();
        boolean changed = false;
        for (int i = 0; i < aeItemHandler.getSlots(); i++) {
            GenericStack item = null;
            GenericStack fluid = null;
            if (iterator.hasNext()) {
                Map.Entry<AEKey, Long> target = iterator.next();
                GenericStack config = new GenericStack(target.getKey(), target.getValue());
                if (target.getKey() instanceof AEItemKey) {
                    item = config;
                } else {
                    fluid = config;
                }
            }
            changed |= setConfig(aeItemHandler.getInventory()[i], item);
            changed |= setConfig(aeFluidHandler.getInventory()[i], fluid);
        }
        if (changed) {
            notifyConfigChanged();
            markDirty();
        }
    }

    private static boolean setConfig(ExportOnlyAESlot slot, GenericStack config) {
        if (Objects.equals(slot.getConfig(), config)) {
            return false;
        }
        ((IMESlot) slot).setConfigWithoutNotify(config);
        slot.setStock((GenericStack) null);
        return true;
    }

    private void notifyConfigChanged() {
        ((IOptimizedMEList) aeItemHandler).onConfigChanged();
        ((IOptimizedMEList) aeFluidHandler).onConfigChanged();
        ((IOptimizedMEList) aeItemHandler).setChanged(true);
        ((IOptimizedMEList) aeFluidHandler).setChanged(true);
        aeItemHandler.notifyListeners();
        aeFluidHandler.notifyListeners();
    }

    @Override
    public Widget createUIWidget() {
        Widget stockWidget = super.createUIWidget();
        int height = stockWidget.getSize().height;
        stockWidget.setSelfPosition(new Position(24, 0));
        WidgetGroup group = new WidgetGroup(0, 0, stockWidget.getSize().width + 24, height + 16);
        group.addWidget(stockWidget);
        for (int i = 0; i < BASE_PATTERN_SLOTS; i++) {
            group.addWidget(new SlotWidget(patternInventory, i, 0, 10 + i * 18)
                    .setBackground(GuiTextures.SLOT, GuiTextures.PATTERN_OVERLAY));
        }
        group.addWidget(new LabelWidget(0, height + 3, () -> patternStatus));
        return group;
    }

    @Override
    public void onMachineRemoved() {
        clearInventory(patternInventory);
        super.onMachineRemoved();
    }
}
