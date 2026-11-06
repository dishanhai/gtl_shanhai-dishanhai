package com.dishanhai.gt_shanhai.common.machine.part.cloud;

import appeng.api.networking.IGridNodeListener;
import appeng.api.networking.IManagedGridNode;
import com.dishanhai.gt_shanhai.GTDishanhaiRegistration;
import com.gregtechceu.gtceu.api.data.RotationState;
import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity;
import com.gregtechceu.gtceu.api.machine.MachineDefinition;
import com.gregtechceu.gtceu.api.machine.feature.IInteractedMachine;
import com.gregtechceu.gtceu.api.machine.feature.IFancyUIMachine;
import com.gregtechceu.gtceu.api.machine.feature.IMachineLife;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiController;
import com.gregtechceu.gtceu.api.machine.multiblock.part.MultiblockPartMachine;
import com.gregtechceu.gtceu.client.renderer.machine.WorkableCasingMachineRenderer;
import com.gregtechceu.gtceu.integration.ae2.machine.feature.IGridConnectedMachine;
import com.gregtechceu.gtceu.integration.ae2.machine.trait.GridNodeHolder;
import com.lowdragmc.lowdraglib.gui.modular.ModularUI;
import com.lowdragmc.lowdraglib.gui.modular.ModularUIContainer;
import com.lowdragmc.lowdraglib.gui.widget.WidgetGroup;
import com.lowdragmc.lowdraglib.syncdata.annotation.DescSynced;
import com.lowdragmc.lowdraglib.syncdata.annotation.Persisted;
import com.lowdragmc.lowdraglib.syncdata.field.ManagedFieldHolder;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

import java.util.EnumSet;
import java.util.UUID;

/** 山海：主庫唯一 AE 接線與玩家身份部件，不保存或搬移樣板原件。 */
public final class StellarCloudAECoreMachine extends MultiblockPartMachine
        implements IGridConnectedMachine, IInteractedMachine, IFancyUIMachine, IMachineLife {
    private static final String PREFIX = "gt_shanhai.cloud.ae_core.";
    private static final ManagedFieldHolder FIELDS = new ManagedFieldHolder(
            StellarCloudAECoreMachine.class, MultiblockPartMachine.MANAGED_FIELD_HOLDER);
    @Persisted private final GridNodeHolder nodeHolder;
    @Persisted private UUID owner, boundPlayer;
    @DescSynced private boolean online;

    public StellarCloudAECoreMachine(IMachineBlockEntity holder) {
        super(holder);
        nodeHolder = new GridNodeHolder(this);
        getMainNode().setExposedOnSides(EnumSet.allOf(Direction.class));
    }

    public static MachineDefinition register() {
        var definition = GTDishanhaiRegistration.REGISTRATE
                .machine("cloud_pattern_library_ae_core", StellarCloudAECoreMachine::new)
                .rotationState(RotationState.ALL)
                .renderer(() -> new WorkableCasingMachineRenderer(
                        new ResourceLocation("gt_shanhai", "block/casings/recipe_type_pattern_buffer_casing"),
                        new ResourceLocation("gt_shanhai", "block/machine/part/recipe_type_pattern_buffer")))
                .register();
        definition.setTooltipBuilder((stack, lines) -> {
            lines.add(Component.translatable(PREFIX + "tooltip.role"));
            lines.add(Component.translatable(PREFIX + "tooltip.network"));
            lines.add(Component.translatable(PREFIX + "tooltip.owner"));
        });
        return definition;
    }

    @Override public ManagedFieldHolder getFieldHolder() { return FIELDS; }
    @Override public IManagedGridNode getMainNode() { return nodeHolder.getMainNode(); }
    @Override public boolean isOnline() { return online; }
    @Override public void setOnline(boolean value) { online = value; }
    @Override public boolean canShared() { return false; }
    public @Nullable UUID boundPlayerId() { return boundPlayer; }

    /** 必須是此主庫的唯一部件，且本地綁定、AE 節點身份及庫主三者一致。 */
    public boolean readyFor(StellarCloudLibraryMachine controller, @Nullable UUID libraryOwner) {
        var node = getMainNode().getNode();
        return !getHolder().getSelf().isRemoved() && controller.isFormed() && isFormed()
                && getControllers().size() == 1 && getControllers().getFirst() == controller
                && boundPlayer != null && boundPlayer.equals(libraryOwner)
                && node != null && node.isActive() && boundPlayer.equals(node.getOwningPlayerProfileId());
    }

    @Override public void onMachinePlaced(LivingEntity placer, ItemStack stack) {
        if (placer instanceof ServerPlayer player) {
            owner = player.getUUID();
            bindPlayer(player);
        }
    }

    @Override public void onLoad() {
        super.onLoad();
        getMainNode().setExposedOnSides(EnumSet.allOf(Direction.class));
    }

    @Override public void onRotated(Direction oldFacing, Direction newFacing) {
        super.onRotated(oldFacing, newFacing);
        getMainNode().setExposedOnSides(EnumSet.allOf(Direction.class));
    }

    @Override public void onMainNodeStateChanged(IGridNodeListener.State reason) {
        IGridConnectedMachine.super.onMainNodeStateChanged(reason);
        notifyControllers();
    }

    @Override public void addedToController(IMultiController controller) {
        super.addedToController(controller);
        notifyControllers();
    }

    @Override public void removedFromController(IMultiController controller) {
        super.removedFromController(controller);
        if (controller instanceof StellarCloudLibraryMachine library) library.refreshNetworkBinding();
    }

    @Override public void onUnload() {
        online = false;
        // node.destroy 之前先讓主庫停止顯示在線，實際供應仍逐次檢查節點與部件。
        for (var controller : getControllers()) {
            if (controller instanceof StellarCloudLibraryMachine library) library.aeCoreUnloaded();
        }
        super.onUnload();
    }

    @Override public void onMachineRemoved() {
        for (var controller : getControllers()) {
            if (controller instanceof StellarCloudLibraryMachine library) library.aeCoreUnloaded();
        }
    }

    @Override public void saveCustomPersistedData(CompoundTag tag, boolean forDrop) {
        super.saveCustomPersistedData(tag, forDrop);
        if (forDrop) {
            tag.remove("owner"); tag.remove("boundPlayer"); tag.remove("nodeHolder");
        }
    }

    private void bindPlayer(ServerPlayer player) {
        if (owner == null) owner = player.getUUID();
        boundPlayer = player.getUUID();
        getMainNode().setOwningPlayer(player);
        markDirty(); notifyControllers();
    }

    private void notifyControllers() {
        if (getLevel() == null || isRemote()) return;
        for (var controller : getControllers()) {
            if (controller instanceof StellarCloudLibraryMachine library) library.refreshNetworkBinding();
        }
    }

    private boolean allowed(ServerPlayer player) {
        return (owner == null || owner.equals(player.getUUID())) && player.serverLevel() == getLevel()
                && player.distanceToSqr(getPos().getX() + .5, getPos().getY() + .5, getPos().getZ() + .5) <= 64
                && player.serverLevel().mayInteract(player, getPos());
    }

    @Override public boolean shouldOpenUI(Player player, InteractionHand hand, BlockHitResult hit) {
        return !(player instanceof ServerPlayer server) || allowed(server);
    }

    private String changeBinding(Player player, boolean bind) {
        if (!(player instanceof ServerPlayer server) || !allowed(server)
                || !(server.containerMenu instanceof ModularUIContainer menu) || menu.getModularUI().holder != this) {
            return PREFIX + "status.denied";
        }
        if (bind) bindPlayer(server);
        else {
            boundPlayer = null;
            getMainNode().setOwningPlayerId(-1);
            markDirty(); notifyControllers();
        }
        return PREFIX + (bind ? "status.bound" : "status.unbound");
    }

    @Override public ModularUI createUI(Player player) {
        return StellarCloudAECoreUi.createUI(this, player);
    }

    @Override public boolean hasPlayerInventory() { return false; }

    @Override public WidgetGroup createUIWidget() {
        return StellarCloudAECoreUi.create(this::cloudUiView, (player, bind) -> changeBinding(player, bind));
    }

    private StellarCloudUiContract.CoreView cloudUiView(Player player) {
        String denied = player instanceof ServerPlayer server && allowed(server) ? "" : PREFIX + "status.denied";
        return new StellarCloudUiContract.CoreView(true, boundPlayer, isOnline(), getControllers().stream()
                .anyMatch(controller -> controller instanceof StellarCloudLibraryMachine library
                        && library.aeBindingReady()), StellarCloudUiContract.control(denied),
                StellarCloudUiContract.control(!denied.isEmpty() ? denied : boundPlayer != null ? "" : PREFIX + "status.already_unbound"));
    }
}
