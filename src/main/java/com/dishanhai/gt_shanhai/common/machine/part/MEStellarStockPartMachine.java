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
    @Persisted
    private CompoundTag manualStockConfiguration = new CompoundTag();
    @Persisted
    private CompoundTag automaticStockConfiguration = new CompoundTag();
    @DescSynced
    private String patternStatus = STATUS_PREFIX + "empty";

    private List<ItemStack> decodedPatterns = List.of();
    private List<MEStellarStockTargetPlanner.Input<AEKey>> cachedInputs;
    private boolean patternDirty = true;

    private record SlotConfiguration(GenericStack item, GenericStack fluid) {

        private boolean isEmpty() {
            return item == null && fluid == null;
        }
    }

    private record ConfigurationPlan(List<SlotConfiguration> applied,
                                     List<SlotConfiguration> automatic) {}

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
        if (!hasPattern()) {
            super.setAutoPullMode(mode);
        }
    }

    private void updatePatternConfiguration() {
        patternDirty = false;
        if (!hasPattern()) {
            decodedPatterns = List.of();
            cachedInputs = null;
            reconcileManualConfiguration();
            ensureCapacity(requiredCapacityForConfigurations(
                    readConfigurations(manualStockConfiguration, aeItemHandler.getSlots())));
            applyTargets(Map.of());
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
        reconcileManualConfiguration();

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
                List<SlotConfiguration> manual = readConfigurations(
                        manualStockConfiguration, aeItemHandler.getSlots());
                int required = Math.addExact(
                        MEStellarStockTargetPlanner.requiredCapacity(cachedInputs),
                        countConfiguredSlots(manual));
                ensureCapacity(Math.max(required, requiredCapacityForConfigurations(manual)));
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

    private boolean hasPattern() {
        if (patternInventory == null) {
            return false;
        }
        for (int i = 0; i < patternInventory.getSlots(); i++) {
            if (!patternInventory.getStackInSlot(i).isEmpty()) {
                return true;
            }
        }
        return false;
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

    private static int countConfiguredSlots(List<SlotConfiguration> configurations) {
        int count = 0;
        for (SlotConfiguration configuration : configurations) {
            if (!configuration.isEmpty()) {
                count++;
            }
        }
        return count;
    }

    private static int requiredCapacityForConfigurations(List<SlotConfiguration> configurations) {
        int highest = 0;
        for (int i = 0; i < configurations.size(); i++) {
            if (!configurations.get(i).isEmpty()) {
                highest = i + 1;
            }
        }
        if (highest <= BASE_CONFIG_SIZE) {
            return BASE_CONFIG_SIZE;
        }
        return ((highest + CONFIG_PAGE_SIZE - 1) / CONFIG_PAGE_SIZE) * CONFIG_PAGE_SIZE;
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
            automaticStockConfiguration = new CompoundTag();
            manualStockConfiguration = new CompoundTag();
            return;
        }
        CompoundTag saved = savedStockConfiguration;
        savedStockConfiguration = new CompoundTag();
        int mode = saved.getInt("AutoPullMode");
        super.setAutoPullMode(mode);
        if (mode == AUTO_PULL_OFF && !manualStockConfiguration.contains("Initialized")) {
            for (int i = 0; i < aeItemHandler.getSlots(); i++) {
                setConfig(aeItemHandler.getInventory()[i], saved.contains("Item" + i)
                        ? GenericStack.readTag(saved.getCompound("Item" + i)) : null);
                setConfig(aeFluidHandler.getInventory()[i], saved.contains("Fluid" + i)
                        ? GenericStack.readTag(saved.getCompound("Fluid" + i)) : null);
            }
            notifyConfigChanged();
        }
        automaticStockConfiguration = new CompoundTag();
        manualStockConfiguration = new CompoundTag();
        markDirty();
    }

    private void applyTargets(Map<AEKey, Long> targets) {
        reconcileManualConfiguration();
        List<SlotConfiguration> manual = readConfigurations(
                manualStockConfiguration, aeItemHandler.getSlots());
        List<SlotConfiguration> previousAutomatic = readConfigurations(
                automaticStockConfiguration, aeItemHandler.getSlots());
        ConfigurationPlan plan = mergeManualAndAutomatic(manual, previousAutomatic, targets);
        boolean changed = false;
        for (int i = 0; i < aeItemHandler.getSlots(); i++) {
            SlotConfiguration configuration = plan.applied().get(i);
            changed |= setConfig(aeItemHandler.getInventory()[i], configuration.item());
            changed |= setConfig(aeFluidHandler.getInventory()[i], configuration.fluid());
        }
        writeAutomaticConfiguration(plan.automatic());
        if (changed) {
            notifyConfigChanged();
            markDirty();
        }
    }

    private ConfigurationPlan mergeManualAndAutomatic(List<SlotConfiguration> manual,
                                                       List<SlotConfiguration> previousAutomatic,
                                                       Map<AEKey, Long> targets) {
        int capacity = aeItemHandler.getSlots();
        GenericStack[] items = new GenericStack[capacity];
        GenericStack[] fluids = new GenericStack[capacity];
        GenericStack[] automaticItems = new GenericStack[capacity];
        GenericStack[] automaticFluids = new GenericStack[capacity];
        for (int i = 0; i < capacity; i++) {
            items[i] = manual.get(i).item();
            fluids[i] = manual.get(i).fluid();
        }

        for (Map.Entry<AEKey, Long> target : targets.entrySet()) {
            AEKey key = target.getKey();
            int index = findManualSlot(manual, key);
            if (index < 0) {
                index = findAutomaticSlot(previousAutomatic, manual, key);
            }
            if (index < 0) {
                index = findFreeSlot(manual, items, fluids);
            }
            if (index < 0) {
                throw new IllegalArgumentException("Too many combined stock configurations");
            }
            GenericStack existing = key instanceof AEItemKey ? items[index] : fluids[index];
            long amount = existing == null ? target.getValue()
                    : Math.max(existing.amount(), target.getValue());
            GenericStack configuration = new GenericStack(key, amount);
            if (key instanceof AEItemKey) {
                items[index] = configuration;
                automaticItems[index] = configuration;
            } else {
                fluids[index] = configuration;
                automaticFluids[index] = configuration;
            }
        }

        List<SlotConfiguration> applied = new ArrayList<>(capacity);
        List<SlotConfiguration> automatic = new ArrayList<>(capacity);
        for (int i = 0; i < capacity; i++) {
            applied.add(new SlotConfiguration(items[i], fluids[i]));
            automatic.add(new SlotConfiguration(automaticItems[i], automaticFluids[i]));
        }
        return new ConfigurationPlan(List.copyOf(applied), List.copyOf(automatic));
    }

    private static int findManualSlot(List<SlotConfiguration> manual, AEKey key) {
        for (int i = 0; i < manual.size(); i++) {
            SlotConfiguration configuration = manual.get(i);
            GenericStack stack = key instanceof AEItemKey ? configuration.item() : configuration.fluid();
            if (stack != null && Objects.equals(stack.what(), key)) {
                return i;
            }
        }
        return -1;
    }

    private static int findAutomaticSlot(List<SlotConfiguration> previousAutomatic,
                                         List<SlotConfiguration> manual,
                                         AEKey key) {
        for (int i = 0; i < previousAutomatic.size(); i++) {
            if (!manual.get(i).isEmpty()) {
                continue;
            }
            SlotConfiguration configuration = previousAutomatic.get(i);
            GenericStack stack = key instanceof AEItemKey ? configuration.item() : configuration.fluid();
            if (stack != null && Objects.equals(stack.what(), key)) {
                return i;
            }
        }
        return -1;
    }

    private static int findFreeSlot(List<SlotConfiguration> manual,
                                    GenericStack[] items,
                                    GenericStack[] fluids) {
        for (int i = 0; i < manual.size(); i++) {
            if (manual.get(i).isEmpty() && items[i] == null && fluids[i] == null) {
                return i;
            }
        }
        return -1;
    }

    private void reconcileManualConfiguration() {
        int capacity = aeItemHandler.getSlots();
        List<SlotConfiguration> current = readCurrentConfigurations(capacity);
        if (!manualStockConfiguration.contains("Initialized")) {
            writeManualConfiguration(current);
            return;
        }
        List<SlotConfiguration> previousManual = readConfigurations(manualStockConfiguration, capacity);
        List<SlotConfiguration> previousAutomatic = readConfigurations(automaticStockConfiguration, capacity);
        List<SlotConfiguration> next = new ArrayList<>(capacity);
        for (int i = 0; i < capacity; i++) {
            SlotConfiguration currentConfiguration = current.get(i);
            SlotConfiguration manualConfiguration = previousManual.get(i);
            SlotConfiguration automaticConfiguration = previousAutomatic.get(i);
            next.add(new SlotConfiguration(
                    reconcileChannel(currentConfiguration.item(), manualConfiguration.item(),
                            automaticConfiguration.item()),
                    reconcileChannel(currentConfiguration.fluid(), manualConfiguration.fluid(),
                            automaticConfiguration.fluid())));
        }
        writeManualConfiguration(List.copyOf(next));
    }

    private static GenericStack reconcileChannel(GenericStack current,
                                                 GenericStack manual,
                                                 GenericStack automatic) {
        if (Objects.equals(current, automatic) || Objects.equals(current, manual)) {
            return manual;
        }
        return current;
    }

    private List<SlotConfiguration> readCurrentConfigurations(int capacity) {
        List<SlotConfiguration> configurations = new ArrayList<>(capacity);
        for (int i = 0; i < capacity; i++) {
            configurations.add(new SlotConfiguration(
                    aeItemHandler.getInventory()[i].getConfig(),
                    aeFluidHandler.getInventory()[i].getConfig()));
        }
        return configurations;
    }

    private static List<SlotConfiguration> readConfigurations(CompoundTag storage, int capacity) {
        List<SlotConfiguration> configurations = new ArrayList<>(capacity);
        for (int i = 0; i < capacity; i++) {
            configurations.add(new SlotConfiguration(
                    readConfiguration(storage, "Item", i),
                    readConfiguration(storage, "Fluid", i)));
        }
        return configurations;
    }

    private static GenericStack readConfiguration(CompoundTag storage, String prefix, int index) {
        String key = prefix + index;
        return storage.contains(key) ? GenericStack.readTag(storage.getCompound(key)) : null;
    }

    private void writeManualConfiguration(List<SlotConfiguration> configurations) {
        CompoundTag next = writeConfigurations(configurations);
        if (!next.equals(manualStockConfiguration)) {
            manualStockConfiguration = next;
            markDirty();
        }
    }

    private void writeAutomaticConfiguration(List<SlotConfiguration> configurations) {
        CompoundTag next = writeConfigurations(configurations);
        if (!next.equals(automaticStockConfiguration)) {
            automaticStockConfiguration = next;
            markDirty();
        }
    }

    private static CompoundTag writeConfigurations(List<SlotConfiguration> configurations) {
        CompoundTag storage = new CompoundTag();
        storage.putBoolean("Initialized", true);
        for (int i = 0; i < configurations.size(); i++) {
            writeConfiguration(storage, "Item", i, configurations.get(i).item());
            writeConfiguration(storage, "Fluid", i, configurations.get(i).fluid());
        }
        return storage;
    }

    private static void writeConfiguration(CompoundTag storage,
                                           String prefix,
                                           int index,
                                           GenericStack configuration) {
        if (configuration != null) {
            storage.put(prefix + index, GenericStack.writeTag(configuration));
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
