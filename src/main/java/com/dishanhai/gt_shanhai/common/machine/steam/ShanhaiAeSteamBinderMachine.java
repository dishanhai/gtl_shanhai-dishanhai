package com.dishanhai.gt_shanhai.common.machine.steam;

import appeng.api.networking.IManagedGridNode;
import com.dishanhai.gt_shanhai.GTDishanhaiRegistration;
import com.dishanhai.gt_shanhai.common.machine.ae.DShanhaiAENetworkMachine;
import com.gregtechceu.gtceu.api.block.MetaMachineBlock;
import com.gregtechceu.gtceu.api.blockentity.MetaMachineBlockEntity;
import com.gregtechceu.gtceu.api.data.RotationState;
import com.gregtechceu.gtceu.api.item.MetaMachineItem;
import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity;
import com.gregtechceu.gtceu.api.machine.MachineDefinition;
import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.api.machine.feature.IMachineLife;
import com.gregtechceu.gtceu.api.registry.registrate.MachineBuilder;
import com.gregtechceu.gtceu.client.renderer.machine.MachineRenderer;
import com.gregtechceu.gtceu.integration.ae2.machine.trait.GridNodeHolder;
import com.lowdragmc.lowdraglib.syncdata.annotation.DescSynced;
import com.lowdragmc.lowdraglib.syncdata.annotation.Persisted;
import com.lowdragmc.lowdraglib.syncdata.field.ManagedFieldHolder;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.EnumSet;

/** 山海：用 AE 線接入選定網路的獨立綁定方塊，不提供倉室或流體緩衝。 */
public final class ShanhaiAeSteamBinderMachine extends MetaMachine
        implements IMachineLife, DShanhaiAENetworkMachine {
    public static final ManagedFieldHolder MANAGED_FIELD_HOLDER = new ManagedFieldHolder(
            ShanhaiAeSteamBinderMachine.class, MetaMachine.MANAGED_FIELD_HOLDER);
    @Persisted
    private final GridNodeHolder nodeHolder;
    @DescSynced
    private boolean online;

    public ShanhaiAeSteamBinderMachine(IMachineBlockEntity holder) {
        super(holder);
        nodeHolder = new GridNodeHolder(this);
        getMainNode().setExposedOnSides(EnumSet.allOf(Direction.class));
    }

    @Override
    public ManagedFieldHolder getFieldHolder() { return MANAGED_FIELD_HOLDER; }

    @Override
    public IManagedGridNode getMainNode() { return nodeHolder.getMainNode(); }

    @Override
    public boolean isOnline() { return online; }

    @Override
    public void setOnline(boolean value) { online = value; }

    @Override
    public String getAeJadeKind() { return "AE 蒸汽绑定器"; }

    @Override
    public void onMachinePlaced(LivingEntity entity, ItemStack stack) {
        if (entity instanceof Player player) getMainNode().setOwningPlayer(player);
    }

    public static MachineDefinition register() {
        return MachineBuilder.create(GTDishanhaiRegistration.REGISTRATE, "ae_steam_binder_block",
                        MachineDefinition::createDefinition, ShanhaiAeSteamBinderMachine::new,
                        MetaMachineBlock::new, MetaMachineItem::new, MetaMachineBlockEntity::createBlockEntity)
                .rotationState(RotationState.NON_Y_AXIS)
                .renderer(() -> new MachineRenderer(new ResourceLocation("gt_shanhai", "block/ae_steam_binder_block")))
                .tooltips(Component.translatable("gt_shanhai.ae_steam_binder.block_tooltip"))
                .register();
    }
}
