package com.dishanhai.gt_shanhai.common.machine.misc;

import appeng.api.networking.IManagedGridNode;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import appeng.api.storage.IStorageMounts;
import appeng.api.storage.IStorageProvider;

import com.dishanhai.gt_shanhai.GTDishanhaiRegistration;
import com.dishanhai.gt_shanhai.common.item.VirtualItemProviderHelper;
import com.dishanhai.gt_shanhai.common.machine.ae.DShanhaiAENetworkMachine;
import com.gregtechceu.gtceu.api.block.MetaMachineBlock;
import com.gregtechceu.gtceu.api.blockentity.MetaMachineBlockEntity;
import com.gregtechceu.gtceu.api.capability.recipe.IO;
import com.gregtechceu.gtceu.api.data.RotationState;
import com.gregtechceu.gtceu.api.gui.GuiTextures;
import com.gregtechceu.gtceu.api.gui.UITemplate;
import com.gregtechceu.gtceu.api.item.MetaMachineItem;
import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity;
import com.gregtechceu.gtceu.api.machine.MachineDefinition;
import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.api.machine.feature.IInteractedMachine;
import com.gregtechceu.gtceu.api.machine.feature.IUIMachine;
import com.gregtechceu.gtceu.api.machine.trait.NotifiableItemStackHandler;
import com.gregtechceu.gtceu.api.registry.registrate.MachineBuilder;
import com.gregtechceu.gtceu.client.renderer.machine.WorkableCasingMachineRenderer;
import com.gregtechceu.gtceu.integration.ae2.machine.feature.IGridConnectedMachine;
import com.gregtechceu.gtceu.integration.ae2.machine.trait.GridNodeHolder;
import com.lowdragmc.lowdraglib.gui.texture.IGuiTexture;
import com.lowdragmc.lowdraglib.gui.widget.LabelWidget;
import com.lowdragmc.lowdraglib.gui.widget.SlotWidget;
import com.lowdragmc.lowdraglib.gui.modular.ModularUI;
import com.lowdragmc.lowdraglib.syncdata.annotation.DescSynced;
import com.lowdragmc.lowdraglib.syncdata.annotation.Persisted;
import com.lowdragmc.lowdraglib.syncdata.field.ManagedFieldHolder;

import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.NotNull;

import java.util.EnumSet;

import static com.dishanhai.gt_shanhai.GTDishanhaiMod.LOGGER;
import static com.dishanhai.gt_shanhai.GTDishanhaiMod.MOD_ID;

public class VirtualItemSupplyMachine extends MetaMachine
        implements IInteractedMachine, IUIMachine, DShanhaiAENetworkMachine, IStorageProvider {

    public static final ManagedFieldHolder MANAGED_FIELD_HOLDER = new ManagedFieldHolder(
            VirtualItemSupplyMachine.class, MetaMachine.MANAGED_FIELD_HOLDER);
    public static final int SLOT_COUNT = 54;
    @Persisted
    @DescSynced
    public final NotifiableItemStackHandler providerSlots;

    @Persisted
    private final GridNodeHolder nodeHolder;

    @DescSynced
    private boolean isOnline;

    public VirtualItemSupplyMachine(IMachineBlockEntity holder) {
        super(holder);
        this.providerSlots = new NotifiableItemStackHandler(this, SLOT_COUNT, IO.BOTH) {
            @Override
            public void setStackInSlot(int index, @NotNull ItemStack stack) {
                super.setStackInSlot(index, stack);
                logSlotChange("set", index, stack);
            }

            @Override
            public @NotNull ItemStack insertItem(int slot, @NotNull ItemStack stack, boolean simulate) {
                ItemStack remaining = super.insertItem(slot, stack, simulate);
                if (!simulate && remaining.getCount() != stack.getCount()) {
                    logSlotChange("insert", slot, stack);
                }
                return remaining;
            }

            @Override
            public @NotNull ItemStack extractItem(int slot, int amount, boolean simulate) {
                ItemStack extracted = super.extractItem(slot, amount, simulate);
                if (!simulate && !extracted.isEmpty()) {
                    logSlotChange("extract", slot, extracted);
                }
                return extracted;
            }
        }.setFilter(stack -> !VirtualItemProviderHelper.isProviderItem(stack));
        this.providerSlots.storage.setOnContentsChanged(() -> {
            if (!isRemote()) {
                markDirty();
                IStorageProvider.requestUpdate(getMainNode());
            }
        });
        this.nodeHolder = new GridNodeHolder(this);
        exposeGridNodeOnAllSides();
        getMainNode().addService(IStorageProvider.class, this);
    }

    @Override
    public void onLoad() {
        super.onLoad();
        exposeGridNodeOnAllSides();
    }

    @Override
    public ManagedFieldHolder getFieldHolder() {
        return MANAGED_FIELD_HOLDER;
    }

    @Override
    public IManagedGridNode getMainNode() {
        return nodeHolder.getMainNode();
    }

    private void exposeGridNodeOnAllSides() {
        getMainNode().setExposedOnSides(EnumSet.allOf(Direction.class));
    }

    @Override
    public boolean isOnline() {
        return isOnline;
    }

    @Override
    public void setOnline(boolean online) {
        this.isOnline = online;
        if (online && !isRemote()) {
            IStorageProvider.requestUpdate(getMainNode());
        }
    }

    @Override
    public String getAeJadeKind() {
        return "虚拟物品供应仓";
    }

    @Override
    public int getAeTotalSlots() {
        return providerSlots.getSlots();
    }

    @Override
    public int getAeConfiguredSlots() {
        return (int) countStoredItems();
    }

    @Override
    public int getAeStockedSlots() {
        return (int) countStoredItems();
    }

    @Override
    public void mountInventories(IStorageMounts mounts) {
        // 供应机只作为虚拟物品的下单校验源，不能把 provider key 暴露成 AE 普通库存。
    }

    public Component getDescription() {
        return Component.translatable("block.gt_shanhai.virtual_item_supply_machine");
    }

    @Override
    public boolean shouldOpenUI(Player player, InteractionHand hand, BlockHitResult hit) {
        return true;
    }

    @Override
    public ModularUI createUI(Player player) {
        int rowSize = 9;
        ModularUI ui = new ModularUI(176, 244, this, player)
                .background(new IGuiTexture[]{GuiTextures.BACKGROUND})
                .widget(new LabelWidget(5, 5, () -> "§6虚拟物品供应机(" + countStoredItems() + "/" + SLOT_COUNT + ")"))
                .widget(UITemplate.bindPlayerInventory(player.getInventory(), GuiTextures.SLOT, 7, 162, true));

        for (int i = 0; i < SLOT_COUNT; i++) {
            int x = (i % rowSize) * 18;
            int y = (i / rowSize) * 18;
            ui.widget(new SlotWidget(providerSlots.storage, i, 7 + x, 17 + y, true, true)
                    .setBackgroundTexture(GuiTextures.SLOT));
        }
        return ui;
    }

    private long countStoredItems() {
        long count = 0;
        for (int i = 0; i < providerSlots.getSlots(); i++) {
            if (!providerSlots.getStackInSlot(i).isEmpty()) {
                count++;
            }
        }
        return count;
    }

    public boolean providesTarget(GenericStack target) {
        if (target == null || !(target.what() instanceof AEItemKey targetKey)) {
            return false;
        }
        return countProvidedTarget(targetKey) >= Math.max(1L, target.amount());
    }

    public long countProvidedTarget(AEKey targetKey) {
        if (!(targetKey instanceof AEItemKey)) {
            return 0;
        }
        long count = 0;
        for (int i = 0; i < providerSlots.getSlots(); i++) {
            ItemStack stack = providerSlots.getStackInSlot(i);
            ItemStack resolved = VirtualItemProviderHelper.resolveForRecipe(stack);
            if (!resolved.isEmpty() && VirtualItemProviderHelper.matchesTargetKey(AEItemKey.of(resolved), targetKey)) {
                count += resolved.getCount();
            }
        }
        return count;
    }

    private void logSlotChange(String action, int slot, ItemStack stack) {
        if (!isRemote()) {
            LOGGER.warn("[VirtualSupplyDiag] action={} pos={} slot={} stack={} count={}",
                    action, getPos(), slot, stack.getHoverName().getString(), stack.getCount());
        }
    }

    public static MachineDefinition register() {
        MachineDefinition def = MachineBuilder.create(
                GTDishanhaiRegistration.REGISTRATE,
                "virtual_item_supply_machine",
                MachineDefinition::createDefinition,
                VirtualItemSupplyMachine::new,
                MetaMachineBlock::new,
                MetaMachineItem::new,
                MetaMachineBlockEntity::createBlockEntity
        )
                .rotationState(RotationState.ALL)
                .renderer(() -> new WorkableCasingMachineRenderer(
                        new ResourceLocation("gtlcore", "block/casings/dimensionally_transcendent_casing"),
                        new ResourceLocation(MOD_ID, "block/machine/part/virtual_item_supply_machine")))
                .register();

        def.setTooltipBuilder((stack, tooltips) -> {
            tooltips.add(Component.literal("§6§l虚拟物品供应机"));
            tooltips.add(Component.literal(""));
            tooltips.add(Component.literal("§7接入 AE 网络后，作为虚拟物品提供器的下单校验源"));
            tooltips.add(Component.literal("§7槽内放入目标真实物品，允许对应虚拟提供器样板下单"));
            tooltips.add(Component.literal("§a不会向 AE 普通库存暴露虚拟物品，也不会消耗槽内物品"));
            tooltips.add(Component.literal("§b用于样板中的不消耗物品、电路、模具与催化剂"));
        });

        return def;
    }
}
