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
import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity;
import com.gregtechceu.gtceu.integration.ae2.machine.MEInputHatchPartMachine;
import com.gregtechceu.gtceu.integration.ae2.machine.trait.GridNodeHolder;
import com.gregtechceu.gtceu.integration.ae2.slot.ExportOnlyAEFluidSlot;
import com.gregtechceu.gtceu.integration.ae2.utils.SerializableManagedGridNode;
import com.lowdragmc.lowdraglib.syncdata.field.ManagedFieldHolder;

import net.minecraft.nbt.CompoundTag;

public class DShanhaiMERequestableInputHatchMachine extends MEInputHatchPartMachine
        implements ICraftingRequester, DShanhaiAENetworkMachine {

    private static final ManagedFieldHolder MANAGED_FIELD_HOLDER = new ManagedFieldHolder(
            DShanhaiMERequestableInputHatchMachine.class,
            MEInputHatchPartMachine.MANAGED_FIELD_HOLDER);
    private static final String CRAFTING_TRACKER_TAG = "ShanhaiRequestableFluidCraftingTracker";

    private final MultiCraftingTracker craftingTracker;

    public DShanhaiMERequestableInputHatchMachine(IMachineBlockEntity holder, Object... args) {
        super(holder, args);
        this.craftingTracker = new MultiCraftingTracker(this, this.aeFluidHandler.getInventory().length);
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
    protected void syncME() {
        var grid = getMainNode().getGrid();
        if (grid == null) return;

        ICraftingService craftingService = grid.getCraftingService();
        var storage = grid.getStorageService().getInventory();
        var slots = this.aeFluidHandler.getInventory();

        for (int i = 0; i < slots.length; i++) {
            ExportOnlyAEFluidSlot slot = slots[i];
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
                this.craftingTracker.handleCrafting(i, request.what(), request.amount() - extracted,
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
        for (ExportOnlyAEFluidSlot slot : this.aeFluidHandler.getInventory()) {
            GenericStack request = slot.requestStack();
            if (request == null || request.what() != what) continue;

            long accepted = Math.min(request.amount(), remaining);
            remaining -= accepted;
            if (mode == Actionable.MODULATE) {
                slot.addStack(new GenericStack(what, accepted));
            }
            if (remaining <= 0) break;
        }
        return amount - remaining;
    }

    @Override
    public void jobStateChange(ICraftingLink link) {
        this.craftingTracker.jobStateChange(link);
        updateTankSubscription();
    }

    @Override
    public ImmutableSet<ICraftingLink> getRequestedJobs() {
        return this.craftingTracker.getRequestedJobs();
    }

    @Override
    public String getAeJadeKind() {
        return "流体输入仓室 §8(可请求)";
    }

    @Override
    public int getAeConfiguredSlots() {
        int count = 0;
        for (ExportOnlyAEFluidSlot slot : this.aeFluidHandler.getInventory()) {
            if (slot.getConfig() != null) count++;
        }
        return count;
    }

    @Override
    public int getAeStockedSlots() {
        int count = 0;
        for (ExportOnlyAEFluidSlot slot : this.aeFluidHandler.getInventory()) {
            if (slot.getStock() != null && slot.getStock().amount() > 0) count++;
        }
        return count;
    }

    @Override
    public int getAePendingSlots() {
        int count = 0;
        for (ExportOnlyAEFluidSlot slot : this.aeFluidHandler.getInventory()) {
            GenericStack request = slot.requestStack();
            if (request != null && request.amount() > 0) count++;
        }
        return count;
    }

    @Override
    public int getAeTotalSlots() {
        return this.aeFluidHandler.getInventory().length;
    }

    @Override
    public int getAeActiveJobs() {
        return this.craftingTracker.getRequestedJobs().size();
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

    private static final class RequestableGridNodeHolder extends GridNodeHolder {

        private RequestableGridNodeHolder(DShanhaiMERequestableInputHatchMachine machine) {
            super(machine);
        }

        @Override
        protected SerializableManagedGridNode createManagedNode() {
            SerializableManagedGridNode node = super.createManagedNode();
            node.addService(ICraftingRequester.class, (DShanhaiMERequestableInputHatchMachine) this.machine);
            return node;
        }
    }
}
