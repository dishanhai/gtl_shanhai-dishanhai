package com.dishanhai.gt_shanhai.common.machine.part;

import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.ICraftingLink;
import appeng.api.networking.crafting.ICraftingRequester;
import appeng.api.networking.crafting.ICraftingService;
import appeng.api.stacks.AEFluidKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.helpers.MultiCraftingTracker;

import com.dishanhai.gt_shanhai.common.machine.ae.DShanhaiAENetworkMachine;
import com.google.common.collect.ImmutableSet;
import com.gregtechceu.gtceu.api.gui.GuiTextures;
import com.gregtechceu.gtceu.api.gui.fancy.ConfiguratorPanel;
import com.gregtechceu.gtceu.api.gui.fancy.IFancyConfigurator;
import com.gregtechceu.gtceu.api.gui.fancy.IFancyConfiguratorButton;
import com.gregtechceu.gtceu.integration.ae2.machine.trait.GridNodeHolder;
import com.gregtechceu.gtceu.integration.ae2.utils.SerializableManagedGridNode;
import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity;
import com.gregtechceu.gtceu.integration.ae2.slot.ExportOnlyAESlot;
import com.gregtechceu.gtceu.integration.ae2.slot.ExportOnlyAEItemList;
import com.gregtechceu.gtceu.integration.ae2.slot.ExportOnlyAEFluidList;
import com.lowdragmc.lowdraglib.gui.texture.ColorBorderTexture;
import com.lowdragmc.lowdraglib.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib.gui.texture.ResourceTexture;
import com.lowdragmc.lowdraglib.gui.texture.TextTexture;
import com.lowdragmc.lowdraglib.gui.widget.LabelWidget;
import com.lowdragmc.lowdraglib.gui.widget.TextFieldWidget;
import com.lowdragmc.lowdraglib.gui.widget.SlotWidget;
import com.lowdragmc.lowdraglib.gui.widget.SwitchWidget;
import com.lowdragmc.lowdraglib.gui.widget.Widget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;
import com.lowdragmc.lowdraglib.misc.ItemStackTransfer;
import com.lowdragmc.lowdraglib.syncdata.annotation.DescSynced;
import com.lowdragmc.lowdraglib.syncdata.annotation.Persisted;
import com.lowdragmc.lowdraglib.syncdata.field.ManagedFieldHolder;
import com.lowdragmc.lowdraglib.utils.Position;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import org.gtlcore.gtlcore.api.machine.trait.MEStock.IMESlot;
import org.gtlcore.gtlcore.api.machine.trait.MEStock.IOptimizedMEList;
import org.gtlcore.gtlcore.common.machine.multiblock.part.MEDualHatchStockPartMachine;

import it.unimi.dsi.fastutil.objects.Object2LongMap;

import java.lang.reflect.Field;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import com.gregtechceu.gtceu.api.machine.MetaMachine;

/**
 * Pattern inputs and tag-matched items configure network-backed stock.
 * Shortages submit AE crafting requests; crafted items return to the network.
 */
public class MEStellarStockPartMachine extends MEDualHatchStockPartMachine
        implements ICraftingRequester, DShanhaiAENetworkMachine {

    private static final int BASE_PATTERN_SLOTS = 5;
    private static final int BASE_CONFIG_SIZE = 64;
    private static final int CONFIG_PAGE_SIZE = 16;
    private static final ManagedFieldHolder MANAGED_FIELD_HOLDER =
            new ManagedFieldHolder(MEStellarStockPartMachine.class,
                    MEDualHatchStockPartMachine.MANAGED_FIELD_HOLDER);
    private static final String STATUS_PREFIX = "gt_shanhai.machine.me_stellar_stock_part_machine.status.";
    private static final String ITEM_TRACKER_TAG = "ShanhaiStellarItemCraftingTracker";
    private static final String FLUID_TRACKER_TAG = "ShanhaiStellarFluidCraftingTracker";
    private static final ResourceTexture TAG_FILTER_TEXTURE =
            new ResourceTexture("gtceu:textures/gui/list.png");

    @Persisted
    private final ItemStackTransfer patternInventory;
    @Persisted
    private String tagWhite = "";
    @Persisted
    private String tagBlack = "";
    @DescSynced
    @Persisted
    private boolean requestEnabled = true;
    @Persisted
    private CompoundTag savedStockConfiguration = new CompoundTag();
    @Persisted
    private CompoundTag manualStockConfiguration = new CompoundTag();
    @Persisted
    private CompoundTag automaticStockConfiguration = new CompoundTag();
    @DescSynced
    @Persisted
    private int disabledPatternMask;
    @DescSynced
    private String patternStatus = STATUS_PREFIX + "empty";

    private List<ItemStack> decodedPatterns = List.of();
    private List<MEStellarStockTargetPlanner.Input<AEKey>> cachedInputs;
    private boolean patternDirty = true;
    private MultiCraftingTracker itemTracker;
    private MultiCraftingTracker fluidTracker;
    private int trackerSlots;
    private StellarTagFilter cachedTagFilter;
    private String cachedTagWhite;
    private String cachedTagBlack;

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
    protected GridNodeHolder createNodeHolder() {
        return new RequestableGridNodeHolder(this);
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
        if (!isRemote() && requestEnabled && isWorkingEnabled() && isOnline() && shouldSyncME()) {
            requestMissingStock();
        }
    }

    @Override
    protected void setAutoPullMode(int mode) {
        if (!hasPattern() && !hasTagFilter()) {
            super.setAutoPullMode(mode);
        }
    }

    private void updatePatternConfiguration() {
        patternDirty = false;
        boolean tagging = hasTagFilter();
        if (!hasPattern() && !tagging) {
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

        if (!hasPattern()) {
            decodedPatterns = List.of();
            cachedInputs = List.of();
        } else {
            List<ItemStack> currentPatterns = new ArrayList<>();
            for (int i = 0; i < patternInventory.getSlots(); i++) {
                ItemStack current = isPatternPullEnabled(i)
                        ? patternInventory.getStackInSlot(i) : ItemStack.EMPTY;
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
        }

        Map<AEKey, Long> targets = new LinkedHashMap<>();
        boolean patternOk = cachedInputs != null;
        if (patternOk && !cachedInputs.isEmpty()) {
            IGrid grid = getMainNode().getGrid();
            KeyCounter available = grid == null ? new KeyCounter()
                    : grid.getStorageService().getCachedInventory();
            try {
                targets.putAll(MEStellarStockTargetPlanner.plan(
                        cachedInputs, available::get, aeItemHandler.getSlots()));
            } catch (ArithmeticException | IllegalArgumentException ignored) {
                patternOk = false;
                targets.clear();
            }
        }
        if (tagging) {
            appendTagTargets(targets);
        }
        if (!patternOk && hasPattern() && !tagging) {
            applyTargets(Map.of());
            patternStatus = STATUS_PREFIX + "invalid";
            return;
        }
        try {
            applyTargets(targets);
            if (hasPattern() && !patternOk) {
                patternStatus = STATUS_PREFIX + "invalid";
            } else if (hasPattern()) {
                patternStatus = STATUS_PREFIX + "active";
            } else {
                patternStatus = STATUS_PREFIX + "tag";
            }
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

    private boolean isPatternPullEnabled(int slot) {
        return slot >= 0 && slot < BASE_PATTERN_SLOTS
                && (disabledPatternMask & (1 << slot)) == 0;
    }

    private void setPatternPullEnabled(int slot, boolean enabled) {
        if (isRemote() || slot < 0 || slot >= BASE_PATTERN_SLOTS
                || isPatternPullEnabled(slot) == enabled) {
            return;
        }
        if (enabled) {
            disabledPatternMask &= ~(1 << slot);
        } else {
            disabledPatternMask |= 1 << slot;
        }
        decodedPatterns = List.of();
        cachedInputs = null;
        patternDirty = true;
        markDirty();
        updatePatternConfiguration();
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
            final int slot = i;
            group.addWidget(new SwitchWidget(19, 11 + i * 18, 4, 16,
                    (click, enabled) -> setPatternPullEnabled(slot, enabled))
                    .setTexture(new ColorBorderTexture(-1, 0xFFFF3030),
                            new ColorBorderTexture(-1, 0xFF20B24B))
                    .setPressed(isPatternPullEnabled(slot))
                    .setSupplier(() -> isPatternPullEnabled(slot))
                    .setHoverTooltips(Component.translatable("gt_shanhai.machine.me_stellar_stock_part_machine.pattern_pull")));
        }
        group.addWidget(new LabelWidget(0, height + 3, () -> patternStatus));
        return group;
    }

    @Override
    public void attachConfigurators(ConfiguratorPanel panel) {
        super.attachConfigurators(panel);
        panel.attachConfigurators(new IFancyConfiguratorButton.Toggle(
                new TextTexture("关"),
                new TextTexture("请求"),
                this::isRequestEnabled,
                (click, enabled) -> setRequestEnabled(enabled))
                .setTooltipsSupplier(enabled -> List.of(Component.translatable(enabled
                        ? "gt_shanhai.machine.me_stellar_stock_part_machine.request.on"
                        : "gt_shanhai.machine.me_stellar_stock_part_machine.request.off"))));
        panel.attachConfigurators(new TagFilterConfigurator());
    }

    private boolean isRequestEnabled() {
        return requestEnabled;
    }

    private void setRequestEnabled(boolean enabled) {
        if (isRemote() || requestEnabled == enabled) {
            return;
        }
        requestEnabled = enabled;
        markDirty();
    }

    @Override
    protected CompoundTag writeConfigToTag() {
        CompoundTag tag = super.writeConfigToTag();
        tag.putString("TagWhite", tagWhite);
        tag.putString("TagBlack", tagBlack);
        tag.putBoolean("RequestEnabled", requestEnabled);
        return tag;
    }

    @Override
    protected void readConfigFromTag(CompoundTag tag) {
        super.readConfigFromTag(tag);
        if (tag.contains("TagWhite")) {
            tagWhite = tag.getString("TagWhite");
        }
        if (tag.contains("TagBlack")) {
            tagBlack = tag.getString("TagBlack");
        }
        if (tag.contains("RequestEnabled")) {
            requestEnabled = tag.getBoolean("RequestEnabled");
        }
        cachedTagFilter = null;
        patternDirty = true;
    }

    @Override
    public void onMachineRemoved() {
        clearInventory(patternInventory);
        super.onMachineRemoved();
    }

    @Override
    public long insertCraftedItems(ICraftingLink link, AEKey what, long amount, Actionable mode) {
        IGrid grid = getMainNode().getGrid();
        if (grid == null || what == null || amount <= 0) {
            return 0;
        }
        appeng.api.storage.MEStorage storage = grid.getStorageService().getInventory();
        if (storage == null) {
            return 0;
        }
        return storage.insert(what, amount, mode, this.actionSource);
    }

    @Override
    public void jobStateChange(ICraftingLink link) {
        if (itemTracker != null) {
            itemTracker.jobStateChange(link);
        }
        if (fluidTracker != null) {
            fluidTracker.jobStateChange(link);
        }
        updateInventorySubscription();
    }

    @Override
    public ImmutableSet<ICraftingLink> getRequestedJobs() {
        if (itemTracker == null && fluidTracker == null) {
            return ImmutableSet.of();
        }
        ImmutableSet.Builder<ICraftingLink> jobs = ImmutableSet.builder();
        if (itemTracker != null) {
            jobs.addAll(itemTracker.getRequestedJobs());
        }
        if (fluidTracker != null) {
            jobs.addAll(fluidTracker.getRequestedJobs());
        }
        return jobs.build();
    }

    @Override
    public String getAeJadeKind() {
        return "星空可请求标签库存输入";
    }

    @Override
    public int getAeTotalSlots() {
        return aeItemHandler.getSlots() + aeFluidHandler.getInventory().length;
    }

    @Override
    public int getAeConfiguredSlots() {
        return countConfigured(true);
    }

    @Override
    public int getAeStockedSlots() {
        return countStocked();
    }

    @Override
    public int getAePendingSlots() {
        return countPending();
    }

    @Override
    public int getAeActiveJobs() {
        return getRequestedJobs().size();
    }

    @Override
    public void saveCustomPersistedData(CompoundTag tag, boolean forDrop) {
        super.saveCustomPersistedData(tag, forDrop);
        if (forDrop || itemTracker == null) {
            return;
        }
        CompoundTag itemTag = new CompoundTag();
        CompoundTag fluidTag = new CompoundTag();
        itemTracker.writeToNBT(itemTag);
        fluidTracker.writeToNBT(fluidTag);
        tag.put(ITEM_TRACKER_TAG, itemTag);
        tag.put(FLUID_TRACKER_TAG, fluidTag);
    }

    @Override
    public void loadCustomPersistedData(CompoundTag tag) {
        super.loadCustomPersistedData(tag);
        ensureTrackers();
        if (tag.contains(ITEM_TRACKER_TAG)) {
            itemTracker.readFromNBT(tag.getCompound(ITEM_TRACKER_TAG));
        }
        if (tag.contains(FLUID_TRACKER_TAG)) {
            fluidTracker.readFromNBT(tag.getCompound(FLUID_TRACKER_TAG));
        }
    }

    private void requestMissingStock() {
        IGrid grid = getMainNode().getGrid();
        if (grid == null) {
            return;
        }
        ensureTrackers();
        ICraftingService craftingService = grid.getCraftingService();
        int itemSlots = Math.min(aeItemHandler.getSlots(), trackerSlots);
        for (int i = 0; i < itemSlots; i++) {
            GenericStack request = aeItemHandler.getInventory()[i].requestStack();
            if (request == null || request.amount() <= 0) {
                continue;
            }
            itemTracker.handleCrafting(i, request.what(), request.amount(),
                    getLevel(), craftingService, this.actionSource);
        }
        int fluidSlots = Math.min(aeFluidHandler.getInventory().length, trackerSlots);
        for (int i = 0; i < fluidSlots; i++) {
            GenericStack request = aeFluidHandler.getInventory()[i].requestStack();
            if (request == null || request.amount() <= 0) {
                continue;
            }
            fluidTracker.handleCrafting(i, request.what(), request.amount(),
                    getLevel(), craftingService, this.actionSource);
        }
    }

    private void ensureTrackers() {
        int slots = aeItemHandler.getSlots();
        if (itemTracker != null && trackerSlots >= slots) {
            return;
        }
        MultiCraftingTracker nextItems = new MultiCraftingTracker(this, slots);
        MultiCraftingTracker nextFluids = new MultiCraftingTracker(this, slots);
        if (itemTracker != null) {
            CompoundTag itemTag = new CompoundTag();
            CompoundTag fluidTag = new CompoundTag();
            itemTracker.writeToNBT(itemTag);
            fluidTracker.writeToNBT(fluidTag);
            nextItems.readFromNBT(itemTag);
            nextFluids.readFromNBT(fluidTag);
        }
        itemTracker = nextItems;
        fluidTracker = nextFluids;
        trackerSlots = slots;
    }

    private boolean hasTagFilter() {
        return tagWhite != null && !tagWhite.trim().isEmpty()
                || tagBlack != null && !tagBlack.trim().isEmpty();
    }

    private void setTagWhite(String value) {
        String next = value == null ? "" : value;
        if (next.equals(tagWhite)) {
            return;
        }
        tagWhite = next;
        cachedTagFilter = null;
        if (!isRemote()) {
            patternDirty = true;
            markDirty();
            updatePatternConfiguration();
        }
    }

    private void setTagBlack(String value) {
        String next = value == null ? "" : value;
        if (next.equals(tagBlack)) {
            return;
        }
        tagBlack = next;
        cachedTagFilter = null;
        if (!isRemote()) {
            patternDirty = true;
            markDirty();
            updatePatternConfiguration();
        }
    }

    private void appendTagTargets(Map<AEKey, Long> targets) {
        List<GenericStack> matches = scanTagMatches();
        if (matches.isEmpty()) {
            return;
        }
        List<SlotConfiguration> manual = readConfigurations(manualStockConfiguration,
                Math.max(aeItemHandler.getSlots(), BASE_CONFIG_SIZE));
        int extra = 0;
        for (GenericStack match : matches) {
            if (!targets.containsKey(match.what())) {
                extra++;
            }
        }
        ensureCapacity(Math.max(aeItemHandler.getSlots(),
                occupiedWithoutNewTags(targets, manual) + extra));
        manual = readConfigurations(manualStockConfiguration, aeItemHandler.getSlots());
        int occupied = occupiedWithoutNewTags(targets, manual);
        int capacity = aeItemHandler.getSlots();
        for (GenericStack match : matches) {
            if (targets.containsKey(match.what())) {
                continue;
            }
            if (occupied >= capacity) {
                break;
            }
            targets.put(match.what(), Math.max(1L, match.amount()));
            occupied++;
        }
    }

    private static int occupiedWithoutNewTags(Map<AEKey, Long> targets, List<SlotConfiguration> manual) {
        int occupied = targets.size();
        for (SlotConfiguration configuration : manual) {
            if (configuration.item() != null && !targets.containsKey(configuration.item().what())) {
                occupied++;
            }
            if (configuration.fluid() != null && !targets.containsKey(configuration.fluid().what())) {
                occupied++;
            }
        }
        return occupied;
    }

    private List<GenericStack> scanTagMatches() {
        IGrid grid = getMainNode().getGrid();
        if (grid == null) {
            return List.of();
        }
        KeyCounter available = grid.getStorageService().getCachedInventory();
        if (available == null) {
            return List.of();
        }
        StellarTagFilter filter = getTagFilter();
        int limit = tagSlotBudget();
        List<GenericStack> matches = new ArrayList<>();
        for (Object2LongMap.Entry<AEKey> entry : available) {
            if (matches.size() >= limit) {
                break;
            }
            AEKey key = entry.getKey();
            long amount = entry.getLongValue();
            if (amount <= 0 || !(key instanceof AEItemKey)) {
                continue;
            }
            AEItemKey itemKey = (AEItemKey) key;
            if (!filter.isListed(itemKey)) {
                continue;
            }
            matches.add(new GenericStack(itemKey, amount));
        }
        return matches;
    }

    private StellarTagFilter getTagFilter() {
        if (cachedTagFilter == null
                || !Objects.equals(cachedTagWhite, tagWhite)
                || !Objects.equals(cachedTagBlack, tagBlack)) {
            cachedTagWhite = tagWhite;
            cachedTagBlack = tagBlack;
            cachedTagFilter = new StellarTagFilter(tagWhite, tagBlack);
        }
        return cachedTagFilter;
    }

    private static int tagSlotBudget() {
        return com.dishanhai.gt_shanhai.config.DShanhaiConfig.COMMON.tagBusSlotsCount.get();
    }

    private int countConfigured(boolean includeFluid) {
        int count = 0;
        for (int i = 0; i < aeItemHandler.getSlots(); i++) {
            if (aeItemHandler.getInventory()[i].getConfig() != null) {
                count++;
            }
            if (includeFluid && aeFluidHandler.getInventory()[i].getConfig() != null) {
                count++;
            }
        }
        return count;
    }

    private int countStocked() {
        int count = 0;
        for (int i = 0; i < aeItemHandler.getSlots(); i++) {
            GenericStack item = aeItemHandler.getInventory()[i].getStock();
            GenericStack fluid = aeFluidHandler.getInventory()[i].getStock();
            if (item != null && item.amount() > 0) {
                count++;
            }
            if (fluid != null && fluid.amount() > 0) {
                count++;
            }
        }
        return count;
    }

    private int countPending() {
        int count = 0;
        for (ExportOnlyAESlot slot : aeItemHandler.getInventory()) {
            GenericStack request = slot.requestStack();
            if (request != null && request.amount() > 0) {
                count++;
            }
        }
        for (ExportOnlyAESlot slot : aeFluidHandler.getInventory()) {
            GenericStack request = slot.requestStack();
            if (request != null && request.amount() > 0) {
                count++;
            }
        }
        return count;
    }

    private final class TagFilterConfigurator implements IFancyConfigurator {

        @Override
        public Component getTitle() {
            return Component.translatable("gui.gtlcore.tag_filter_config");
        }

        @Override
        public IGuiTexture getIcon() {
            return TAG_FILTER_TEXTURE;
        }

        @Override
        public WidgetGroup createConfigurator() {
            WidgetGroup group = new WidgetGroup(0, 0, 200, 80);
            group.addWidget(new LabelWidget(3, 5, () ->
                    Component.translatable("gui.gtlcore.tag_whitelist").getString()));
            TextFieldWidget white = new TextFieldWidget(3, 18, 194, 14,
                    () -> tagWhite, MEStellarStockPartMachine.this::setTagWhite);
            white.setCurrentString(tagWhite);
            group.addWidget(white);
            group.addWidget(new LabelWidget(3, 38, () ->
                    Component.translatable("gui.gtlcore.tag_blacklist").getString()));
            TextFieldWidget black = new TextFieldWidget(3, 50, 194, 14,
                    () -> tagBlack, MEStellarStockPartMachine.this::setTagBlack);
            black.setCurrentString(tagBlack);
            group.addWidget(black);
            return group;
        }
    }

    private static final class RequestableGridNodeHolder extends GridNodeHolder {

        private RequestableGridNodeHolder(MEStellarStockPartMachine machine) {
            super(machine);
        }

        @Override
        protected SerializableManagedGridNode createManagedNode() {
            SerializableManagedGridNode node = super.createManagedNode();
            node.addService(ICraftingRequester.class, (MEStellarStockPartMachine) this.machine);
            return node;
        }
    }

    /**
     * Same wildcard rules as the big tag filter stock bus: {@code forge:ores/*} style
     * is expressed as {@code forge:ores*}, {@code *raw*}, and {@code @modid}.
     */
    private static final class StellarTagFilter {

        private final String[] whiteTags;
        private final String[] blackTags;
        private final boolean hasWhite;
        private final HashMap<Object, Boolean> cache = new HashMap<>();

        private StellarTagFilter(String white, String black) {
            String whitelist = white == null ? "" : white.trim();
            String blacklist = black == null ? "" : black.trim();
            whiteTags = whitelist.isEmpty() ? new String[0] : whitelist.split("[|,]");
            blackTags = blacklist.isEmpty() ? new String[0] : blacklist.split("[|,]");
            hasWhite = whiteTags.length > 0;
        }

        private boolean isListed(AEItemKey itemKey) {
            Boolean cached = cache.get(itemKey.getPrimaryKey());
            if (cached != null) {
                return cached;
            }
            Set<String> tags = itemKey.getItem().builtInRegistryHolder().tags()
                    .map(tag -> tag.location().toString())
                    .collect(Collectors.toSet());
            boolean listed = matches(itemKey, tags);
            cache.put(itemKey.getPrimaryKey(), listed);
            return listed;
        }

        private boolean matches(AEItemKey itemKey, Set<String> tags) {
            for (String black : blackTags) {
                String pattern = black.trim();
                if (!pattern.isEmpty() && matchesAny(pattern, itemKey, tags)) {
                    return false;
                }
            }
            if (!hasWhite) {
                return true;
            }
            for (String white : whiteTags) {
                String pattern = white.trim();
                if (!pattern.isEmpty() && matchesAny(pattern, itemKey, tags)) {
                    return true;
                }
            }
            return false;
        }

        private static boolean matchesAny(String pattern, AEItemKey key, Set<String> tags) {
            if (pattern.startsWith("@")) {
                return modIdMatches(pattern.substring(1),
                        key.getItem().builtInRegistryHolder().key().location().getNamespace());
            }
            return tagMatchesAny(pattern, tags);
        }

        private static boolean modIdMatches(String pattern, String modId) {
            boolean startWild = pattern.startsWith("*");
            boolean endWild = pattern.endsWith("*");
            if (startWild && endWild && pattern.length() > 2) {
                return modId.contains(pattern.substring(1, pattern.length() - 1));
            }
            if (startWild && pattern.length() > 1) {
                return modId.endsWith(pattern.substring(1));
            }
            if (endWild && pattern.length() > 1) {
                return modId.startsWith(pattern.substring(0, pattern.length() - 1));
            }
            return modId.equals(pattern);
        }

        private static boolean tagMatchesAny(String pattern, Set<String> tags) {
            boolean startWild = pattern.startsWith("*");
            boolean endWild = pattern.endsWith("*");
            if (startWild && endWild && pattern.length() > 2) {
                String middle = pattern.substring(1, pattern.length() - 1);
                for (String tag : tags) {
                    if (tag.contains(middle)) {
                        return true;
                    }
                }
                return false;
            }
            if (startWild && pattern.length() > 1) {
                String suffix = pattern.substring(1);
                for (String tag : tags) {
                    if (tag.endsWith(suffix)) {
                        return true;
                    }
                }
                return false;
            }
            if (endWild && pattern.length() > 1) {
                String prefix = pattern.substring(0, pattern.length() - 1);
                for (String tag : tags) {
                    if (tag.startsWith(prefix)) {
                        return true;
                    }
                }
                return false;
            }
            return tags.contains(pattern);
        }
    }
}
