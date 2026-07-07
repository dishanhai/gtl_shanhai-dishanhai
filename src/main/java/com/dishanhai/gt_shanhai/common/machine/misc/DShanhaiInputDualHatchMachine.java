package com.dishanhai.gt_shanhai.common.machine.misc;

import appeng.api.config.Actionable;
import appeng.api.networking.crafting.ICraftingLink;
import appeng.api.networking.crafting.ICraftingRequester;
import appeng.api.networking.crafting.ICraftingService;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.helpers.MultiCraftingTracker;

import com.dishanhai.gt_shanhai.common.machine.ae.DShanhaiAENetworkMachine;
import com.google.common.collect.ImmutableSet;
import com.gregtechceu.gtceu.api.capability.recipe.IO;
import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity;
import com.gregtechceu.gtceu.api.machine.feature.IDataStickInteractable;
import com.gregtechceu.gtceu.api.machine.trait.NotifiableFluidTank;
import com.gregtechceu.gtceu.api.machine.trait.NotifiableItemStackHandler;
import com.gregtechceu.gtceu.common.item.IntCircuitBehaviour;
import com.gregtechceu.gtceu.integration.ae2.gui.widget.AEFluidConfigWidget;
import com.gregtechceu.gtceu.integration.ae2.gui.widget.AEItemConfigWidget;
import com.gregtechceu.gtceu.integration.ae2.machine.MEBusPartMachine;
import com.gregtechceu.gtceu.integration.ae2.machine.trait.GridNodeHolder;
import com.gregtechceu.gtceu.integration.ae2.slot.ExportOnlyAEFluidList;
import com.gregtechceu.gtceu.integration.ae2.slot.ExportOnlyAEFluidSlot;
import com.gregtechceu.gtceu.integration.ae2.slot.ExportOnlyAEItemList;
import com.gregtechceu.gtceu.integration.ae2.slot.ExportOnlyAEItemSlot;
import com.gregtechceu.gtceu.integration.ae2.utils.SerializableManagedGridNode;
import com.lowdragmc.lowdraglib.gui.widget.LabelWidget;
import com.lowdragmc.lowdraglib.gui.widget.Widget;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;
import com.lowdragmc.lowdraglib.syncdata.ISubscription;
import com.lowdragmc.lowdraglib.syncdata.annotation.Persisted;
import com.lowdragmc.lowdraglib.syncdata.field.ManagedFieldHolder;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

public class DShanhaiInputDualHatchMachine extends MEBusPartMachine
        implements ICraftingRequester, DShanhaiAENetworkMachine, IDataStickInteractable {

    private static final ManagedFieldHolder MANAGED_FIELD_HOLDER = new ManagedFieldHolder(
            DShanhaiInputDualHatchMachine.class,
            MEBusPartMachine.MANAGED_FIELD_HOLDER);
    private static final int ITEM_SLOT_COUNT = 4;
    private static final int FLUID_SLOT_COUNT = 2;
    private static final String DATA_STICK_TAG = "ShanhaiMERequestableInputDualHatch";
    private static final String CRAFTING_TRACKER_TAG = "ShanhaiRequestableDualCraftingTracker";

    @Persisted
    public final NotifiableFluidTank tank;

    private ExportOnlyAEItemList aeItemHandler;
    private ExportOnlyAEFluidList aeFluidHandler;
    private final MultiCraftingTracker craftingTracker;
    private ISubscription tankSubs;

    public DShanhaiInputDualHatchMachine(IMachineBlockEntity holder, Object... args) {
        super(holder, IO.IN, args);
        this.tank = createTank(args);
        this.craftingTracker = new MultiCraftingTracker(this, ITEM_SLOT_COUNT + FLUID_SLOT_COUNT);
    }

    @Override
    protected NotifiableItemStackHandler createInventory(Object... args) {
        this.aeItemHandler = new ExportOnlyAEItemList(this, ITEM_SLOT_COUNT);
        return this.aeItemHandler;
    }

    protected NotifiableFluidTank createTank(Object... args) {
        this.aeFluidHandler = new ExportOnlyAEFluidList(this, FLUID_SLOT_COUNT);
        return this.aeFluidHandler;
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
        this.tankSubs = this.tank.addChangedListener(this::updateInventorySubscription);
    }

    @Override
    public void onUnload() {
        super.onUnload();
        if (this.tankSubs != null) {
            this.tankSubs.unsubscribe();
            this.tankSubs = null;
        }
    }

    @Override
    public void onMachineRemoved() {
        flushInventory();
    }

    @Override
    protected void updateInventorySubscription() {
        if (shouldSubscribe()) {
            this.autoIOSubs = subscribeServerTick(this.autoIOSubs, this::autoIO);
        } else if (this.autoIOSubs != null) {
            this.autoIOSubs.unsubscribe();
            this.autoIOSubs = null;
        }
    }

    @Override
    protected void autoIO() {
        if (isWorkingEnabled() && shouldSyncME() && updateMEStatus()) {
            syncME();
            updateInventorySubscription();
        }
    }

    protected void syncME() {
        var grid = getMainNode().getGrid();
        if (grid == null) return;

        ICraftingService craftingService = grid.getCraftingService();
        var storage = grid.getStorageService().getInventory();

        ExportOnlyAEItemSlot[] itemSlots = this.aeItemHandler.getInventory();
        for (int i = 0; i < itemSlots.length; i++) {
            ExportOnlyAEItemSlot slot = itemSlots[i];
            GenericStack exceed = slot.exceedStack();
            if (exceed != null) {
                long amount = exceed.amount();
                long inserted = storage.insert(exceed.what(), amount, Actionable.MODULATE, this.actionSource);
                if (inserted > 0) {
                    slot.extractItem(0, safeItemAmount(inserted), false, true);
                    continue;
                }
                slot.extractItem(0, safeItemAmount(amount), false, true);
            }

            GenericStack request = slot.requestStack();
            if (request == null) continue;

            long extracted = storage.extract(request.what(), request.amount(), Actionable.MODULATE, this.actionSource);
            if (extracted < request.amount()) {
                this.craftingTracker.handleCrafting(i, request.what(), request.amount() - extracted,
                        getLevel(), craftingService, this.actionSource);
            }
            if (extracted > 0) {
                slot.addStack(new GenericStack(request.what(), extracted));
            }
        }

        ExportOnlyAEFluidSlot[] fluidSlots = this.aeFluidHandler.getInventory();
        for (int i = 0; i < fluidSlots.length; i++) {
            ExportOnlyAEFluidSlot slot = fluidSlots[i];
            GenericStack exceed = slot.exceedStack();
            if (exceed != null) {
                long amount = exceed.amount();
                long inserted = storage.insert(exceed.what(), amount, Actionable.MODULATE, this.actionSource);
                if (inserted > 0) {
                    slot.drain(inserted, false, true);
                    continue;
                }
                slot.drain(amount, false, true);
            }

            GenericStack request = slot.requestStack();
            if (request == null) continue;

            long extracted = storage.extract(request.what(), request.amount(), Actionable.MODULATE, this.actionSource);
            if (extracted < request.amount()) {
                this.craftingTracker.handleCrafting(ITEM_SLOT_COUNT + i, request.what(), request.amount() - extracted,
                        getLevel(), craftingService, this.actionSource);
            }
            if (extracted > 0) {
                slot.addStack(new GenericStack(request.what(), extracted));
            }
        }
    }

    @Override
    public long insertCraftedItems(ICraftingLink link, AEKey what, long amount, Actionable mode) {
        long remaining = amount;

        for (ExportOnlyAEItemSlot slot : this.aeItemHandler.getInventory()) {
            remaining = insertCraftedIntoItemSlot(slot, what, remaining, mode);
            if (remaining <= 0) return amount;
        }

        for (ExportOnlyAEFluidSlot slot : this.aeFluidHandler.getInventory()) {
            remaining = insertCraftedIntoFluidSlot(slot, what, remaining, mode);
            if (remaining <= 0) return amount;
        }

        return amount - remaining;
    }

    @Override
    public void jobStateChange(ICraftingLink link) {
        this.craftingTracker.jobStateChange(link);
        updateInventorySubscription();
    }

    @Override
    public ImmutableSet<ICraftingLink> getRequestedJobs() {
        return this.craftingTracker.getRequestedJobs();
    }

    @Override
    public String getAeJadeKind() {
        return "物品+流体输入总成 §8(可请求)";
    }

    @Override
    public int getAeConfiguredSlots() {
        int count = 0;
        for (ExportOnlyAEItemSlot slot : this.aeItemHandler.getInventory()) {
            if (slot.getConfig() != null) count++;
        }
        for (ExportOnlyAEFluidSlot slot : this.aeFluidHandler.getInventory()) {
            if (slot.getConfig() != null) count++;
        }
        return count;
    }

    @Override
    public int getAeStockedSlots() {
        int count = 0;
        for (ExportOnlyAEItemSlot slot : this.aeItemHandler.getInventory()) {
            if (slot.getStock() != null && slot.getStock().amount() > 0) count++;
        }
        for (ExportOnlyAEFluidSlot slot : this.aeFluidHandler.getInventory()) {
            if (slot.getStock() != null && slot.getStock().amount() > 0) count++;
        }
        return count;
    }

    @Override
    public int getAePendingSlots() {
        int count = 0;
        for (ExportOnlyAEItemSlot slot : this.aeItemHandler.getInventory()) {
            GenericStack request = slot.requestStack();
            if (request != null && request.amount() > 0) count++;
        }
        for (ExportOnlyAEFluidSlot slot : this.aeFluidHandler.getInventory()) {
            GenericStack request = slot.requestStack();
            if (request != null && request.amount() > 0) count++;
        }
        return count;
    }

    @Override
    public int getAeTotalSlots() {
        return ITEM_SLOT_COUNT + FLUID_SLOT_COUNT;
    }

    @Override
    public int getAeActiveJobs() {
        return this.craftingTracker.getRequestedJobs().size();
    }

    @Override
    public Widget createUIWidget() {
        WidgetGroup group = new WidgetGroup(0, 0, 176, 238);
        group.addWidget(new LabelWidget(3, 0, () -> this.isOnline
                ? "gtceu.gui.me_network.online"
                : "gtceu.gui.me_network.offline"));
        group.addWidget(new LabelWidget(3, 30, () -> "gt_shanhai.gui.input_dual_hatch.fluids"));
        group.addWidget(new AEFluidConfigWidget(70, 58, this.aeFluidHandler));
        group.addWidget(new LabelWidget(3, 134, () -> "gt_shanhai.gui.input_dual_hatch.items"));
        group.addWidget(new AEItemConfigWidget(52, 162, this.aeItemHandler));
        return group;
    }

    @Override
    public boolean onDataStickLeftClick(Player player, ItemStack dataStick) {
        if (!isRemote()) {
            CompoundTag tag = new CompoundTag();
            tag.put(DATA_STICK_TAG, writeConfigToTag());
            dataStick.setTag(tag);
            dataStick.setHoverName(Component.literal("ME可请求输入总成配置"));
            player.sendSystemMessage(Component.literal("已复制 ME可请求输入总成 配置"));
        }
        return true;
    }

    @Override
    public InteractionResult onDataStickRightClick(Player player, ItemStack dataStick) {
        CompoundTag tag = dataStick.getTag();
        if (tag == null || !tag.contains(DATA_STICK_TAG)) {
            return InteractionResult.PASS;
        }
        if (!isRemote()) {
            readConfigFromTag(tag.getCompound(DATA_STICK_TAG));
            updateInventorySubscription();
            player.sendSystemMessage(Component.literal("已粘贴 ME可请求输入总成 配置"));
        }
        return InteractionResult.sidedSuccess(isRemote());
    }

    @Override
    public void saveCustomPersistedData(CompoundTag tag, boolean forDrop) {
        super.saveCustomPersistedData(tag, forDrop);
        if (!forDrop) {
            CompoundTag trackerTag = new CompoundTag();
            this.craftingTracker.writeToNBT(trackerTag);
            tag.put(CRAFTING_TRACKER_TAG, trackerTag);
        }
    }

    @Override
    public void loadCustomPersistedData(CompoundTag tag) {
        super.loadCustomPersistedData(tag);
        if (tag.contains(CRAFTING_TRACKER_TAG)) {
            this.craftingTracker.readFromNBT(tag.getCompound(CRAFTING_TRACKER_TAG));
        }
    }

    private void flushInventory() {
        var grid = getMainNode().getGrid();
        if (grid == null) return;

        var storage = grid.getStorageService().getInventory();
        for (ExportOnlyAEItemSlot slot : this.aeItemHandler.getInventory()) {
            GenericStack stock = slot.getStock();
            if (stock != null) {
                storage.insert(stock.what(), stock.amount(), Actionable.MODULATE, this.actionSource);
            }
        }
        for (ExportOnlyAEFluidSlot slot : this.aeFluidHandler.getInventory()) {
            GenericStack stock = slot.getStock();
            if (stock != null) {
                storage.insert(stock.what(), stock.amount(), Actionable.MODULATE, this.actionSource);
            }
        }
    }

    private CompoundTag writeConfigToTag() {
        CompoundTag tag = new CompoundTag();
        CompoundTag itemConfigs = new CompoundTag();
        CompoundTag fluidConfigs = new CompoundTag();

        for (int i = 0; i < this.aeItemHandler.getInventory().length; i++) {
            GenericStack config = this.aeItemHandler.getInventory()[i].getConfig();
            if (config != null) {
                itemConfigs.put(Integer.toString(i), GenericStack.writeTag(config));
            }
        }

        for (int i = 0; i < this.aeFluidHandler.getInventory().length; i++) {
            GenericStack config = this.aeFluidHandler.getInventory()[i].getConfig();
            if (config != null) {
                fluidConfigs.put(Integer.toString(i), GenericStack.writeTag(config));
            }
        }

        tag.put("ItemConfigStacks", itemConfigs);
        tag.put("FluidConfigStacks", fluidConfigs);
        tag.putByte("GhostCircuit", (byte) IntCircuitBehaviour.getCircuitConfiguration(
                this.circuitInventory.getStackInSlot(0)));
        return tag;
    }

    private void readConfigFromTag(CompoundTag tag) {
        if (tag.contains("ItemConfigStacks")) {
            CompoundTag itemConfigs = tag.getCompound("ItemConfigStacks");
            for (int i = 0; i < this.aeItemHandler.getInventory().length; i++) {
                String key = Integer.toString(i);
                this.aeItemHandler.getInventory()[i].setConfig(
                        itemConfigs.contains(key) ? GenericStack.readTag(itemConfigs.getCompound(key)) : null);
            }
        }

        if (tag.contains("FluidConfigStacks")) {
            CompoundTag fluidConfigs = tag.getCompound("FluidConfigStacks");
            for (int i = 0; i < this.aeFluidHandler.getInventory().length; i++) {
                String key = Integer.toString(i);
                this.aeFluidHandler.getInventory()[i].setConfig(
                        fluidConfigs.contains(key) ? GenericStack.readTag(fluidConfigs.getCompound(key)) : null);
            }
        }

        if (tag.contains("GhostCircuit")) {
            this.circuitInventory.setStackInSlot(0, IntCircuitBehaviour.stack(tag.getByte("GhostCircuit")));
        }
    }

    private static long insertCraftedIntoItemSlot(ExportOnlyAEItemSlot slot, AEKey what, long remaining,
                                                  Actionable mode) {
        GenericStack request = slot.requestStack();
        if (request == null || request.what() != what) return remaining;

        long accepted = Math.min(request.amount(), remaining);
        if (mode == Actionable.MODULATE) {
            slot.addStack(new GenericStack(what, accepted));
        }
        return remaining - accepted;
    }

    private static long insertCraftedIntoFluidSlot(ExportOnlyAEFluidSlot slot, AEKey what, long remaining,
                                                   Actionable mode) {
        GenericStack request = slot.requestStack();
        if (request == null || request.what() != what) return remaining;

        long accepted = Math.min(request.amount(), remaining);
        if (mode == Actionable.MODULATE) {
            slot.addStack(new GenericStack(what, accepted));
        }
        return remaining - accepted;
    }

    private static int safeItemAmount(long amount) {
        return amount > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) amount;
    }

    private static final class RequestableGridNodeHolder extends GridNodeHolder {

        private RequestableGridNodeHolder(DShanhaiInputDualHatchMachine machine) {
            super(machine);
        }

        @Override
        protected SerializableManagedGridNode createManagedNode() {
            SerializableManagedGridNode node = super.createManagedNode();
            node.addService(ICraftingRequester.class, (DShanhaiInputDualHatchMachine) this.machine);
            return node;
        }
    }
}
