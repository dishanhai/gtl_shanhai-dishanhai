package com.dishanhai.gt_shanhai.common.item.terminal;

import com.dishanhai.gt_shanhai.GTDishanhaiMod;
import com.dishanhai.gt_shanhai.common.machine.DShanhaiMachines;
import com.dishanhai.gt_shanhai.common.machine.part.RecipeTypePatternBufferPartMachine;
import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity;
import com.gregtechceu.gtceu.api.machine.MachineDefinition;
import com.lowdragmc.lowdraglib.gui.factory.UIFactory;
import com.lowdragmc.lowdraglib.gui.modular.ModularUI;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

public final class ShanhaiStellarRemoteUIFactory extends UIFactory<ShanhaiStellarRemoteUIHolder> {

    public static final ShanhaiStellarRemoteUIFactory INSTANCE = new ShanhaiStellarRemoteUIFactory();
    private static boolean registered;

    private ShanhaiStellarRemoteUIFactory() {
        super(new ResourceLocation(GTDishanhaiMod.MOD_ID, "stellar_remote_config"));
    }

    public static void register() {
        if (registered) return;
        registered = true;
        UIFactory.register(INSTANCE);
    }

    public boolean openSlot(ServerPlayer player, RecipeTypePatternBufferPartMachine machine,
            ShanhaiPatternManagementMenuHost terminalHost, int slot) {
        boolean opened = openUI(new ShanhaiStellarRemoteUIHolder(
                GlobalPos.of(machine.getLevel().dimension(), machine.getPos()),
                ShanhaiStellarRemoteUIHolder.Mode.SLOT_CATALYST, slot, machine, terminalHost), player);
        GTDishanhaiMod.LOGGER.info(
                "[StellarRemoteUI] server open mode=SLOT_CATALYST slot={} target={} opened={}",
                slot, machine.getPos(), opened);
        return opened;
    }

    public boolean openStock(ServerPlayer player, RecipeTypePatternBufferPartMachine machine,
            ShanhaiPatternManagementMenuHost terminalHost) {
        boolean opened = openUI(new ShanhaiStellarRemoteUIHolder(
                GlobalPos.of(machine.getLevel().dimension(), machine.getPos()),
                ShanhaiStellarRemoteUIHolder.Mode.STOCK_INPUT, -1, machine, terminalHost), player);
        GTDishanhaiMod.LOGGER.info(
                "[StellarRemoteUI] server open mode=STOCK_INPUT slot=-1 target={} opened={}",
                machine.getPos(), opened);
        return opened;
    }

    public boolean openPattern(ServerPlayer player, RecipeTypePatternBufferPartMachine machine,
            ShanhaiPatternManagementMenuHost terminalHost) {
        boolean opened = openUI(new ShanhaiStellarRemoteUIHolder(
                GlobalPos.of(machine.getLevel().dimension(), machine.getPos()),
                ShanhaiStellarRemoteUIHolder.Mode.FULL_PATTERN, -1, machine, terminalHost), player);
        GTDishanhaiMod.LOGGER.info(
                "[StellarRemoteUI] server open mode=FULL_PATTERN slot=-1 target={} opened={}",
                machine.getPos(), opened);
        return opened;
    }

    @Override
    protected ModularUI createUITemplate(ShanhaiStellarRemoteUIHolder holder, Player player) {
        return holder.createUI(player);
    }

    @Override
    @OnlyIn(Dist.CLIENT)
    protected ShanhaiStellarRemoteUIHolder readHolderFromSyncData(FriendlyByteBuf buffer) {
        ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION, buffer.readResourceLocation());
        GlobalPos pos = GlobalPos.of(dimension, buffer.readBlockPos());
        ShanhaiStellarRemoteUIHolder.Mode mode = buffer.readEnum(ShanhaiStellarRemoteUIHolder.Mode.class);
        int slot = buffer.readVarInt();
        RecipeTypePatternBufferPartMachine mirror = createClientMirror();
        GTDishanhaiMod.LOGGER.info(
                "[StellarRemoteUI] client read mode={} slot={} target={} mirror={}",
                mode, slot, pos, mirror == null ? "missing" : mirror.getClass().getName());
        return new ShanhaiStellarRemoteUIHolder(pos, mode, slot, mirror, null);
    }

    @Override
    protected void writeHolderToSyncData(FriendlyByteBuf buffer, ShanhaiStellarRemoteUIHolder holder) {
        buffer.writeResourceLocation(holder.targetPos().dimension().location());
        buffer.writeBlockPos(holder.targetPos().pos());
        buffer.writeEnum(holder.mode());
        buffer.writeVarInt(holder.slot());
    }

    @OnlyIn(Dist.CLIENT)
    private static RecipeTypePatternBufferPartMachine createClientMirror() {
        try {
            MachineDefinition definition = DShanhaiMachines.RECIPE_TYPE_PATTERN_BUFFER;
            if (definition == null) {
                GTDishanhaiMod.LOGGER.error("[StellarRemoteUI] client mirror definition is missing");
                return null;
            }
            BlockEntity blockEntity = definition.getBlockEntityType().create(
                    net.minecraft.core.BlockPos.ZERO, definition.getBlock().defaultBlockState());
            if (blockEntity instanceof IMachineBlockEntity machineBlockEntity
                    && machineBlockEntity.getMetaMachine() instanceof RecipeTypePatternBufferPartMachine stellar) {
                return stellar;
            }
            GTDishanhaiMod.LOGGER.error(
                    "[StellarRemoteUI] client mirror has unexpected block entity: {}",
                    blockEntity == null ? "null" : blockEntity.getClass().getName());
        } catch (RuntimeException exception) {
            GTDishanhaiMod.LOGGER.error("[StellarRemoteUI] client mirror creation failed", exception);
        }
        return null;
    }
}
