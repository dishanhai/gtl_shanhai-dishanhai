package com.dishanhai.gt_shanhai.common.cover;

import appeng.api.config.Actionable;
import appeng.api.networking.IGridNode;
import appeng.api.networking.security.IActionHost;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEFluidKey;
import com.gregtechceu.gtceu.api.capability.ICoverable;
import com.gregtechceu.gtceu.api.cover.CoverBehavior;
import com.gregtechceu.gtceu.api.cover.CoverDefinition;
import com.gregtechceu.gtceu.api.item.component.IAddInformation;
import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.api.machine.TickableSubscription;
import com.gregtechceu.gtceu.api.machine.feature.IRecipeLogicMachine;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiPart;
import com.gregtechceu.gtceu.api.machine.steam.SimpleSteamMachine;
import com.gregtechceu.gtceu.api.registry.GTRegistries;
import com.dishanhai.gt_shanhai.client.renderer.cover.ShanhaiAeSteamCoverRenderer;
import com.dishanhai.gt_shanhai.common.machine.steam.ShanhaiAeSteamBinderMachine;
import com.gregtechceu.gtceu.common.machine.multiblock.part.SteamHatchPartMachine;
import com.gregtechceu.gtceu.common.machine.multiblock.steam.SteamParallelMultiblockMachine;
import com.lowdragmc.lowdraglib.syncdata.annotation.Persisted;
import com.lowdragmc.lowdraglib.syncdata.annotation.DescSynced;
import com.lowdragmc.lowdraglib.syncdata.field.ManagedFieldHolder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;
import org.gtlcore.gtlcore.common.machine.multiblock.part.LargeSteamHatchPartMachine;
import org.gtlcore.gtlcore.common.machine.multiblock.steam.LargeSteamParallelMultiblockMachine;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/** 山海：綁定 AE 節點後按原生加工需求直接扣 gtceu:steam，不預抽入本地槽。 */
public class ShanhaiAeSteamCover extends CoverBehavior implements IActionHost {
    public static final String BINDING_TAG = "shanhaiAeSteamBinding";
    private static final String CREDIT_TAG = "shanhaiAeSteamCredit";
    public static final ResourceLocation STEAM_ID = new ResourceLocation("gtceu", "steam");
    public static final ManagedFieldHolder MANAGED_FIELD_HOLDER = new ManagedFieldHolder(
            ShanhaiAeSteamCover.class, CoverBehavior.MANAGED_FIELD_HOLDER);
    public static final IAddInformation ITEM_TOOLTIP = (stack, level, lines, flag) -> {
        lines.add(Component.translatable("gt_shanhai.ae_steam_cover.tooltip"));
        lines.add(Component.translatable("gt_shanhai.ae_steam_cover.exact"));
        if (stack.hasTag() && stack.getTag().contains(BINDING_TAG)) {
            lines.add(Component.literal(bindingDescription(stack.getTag().getCompound(BINDING_TAG))));
        }
    };

    @Persisted
    @DescSynced
    private CompoundTag binding = new CompoundTag();
    @Persisted
    private long prepaidSteam;
    private TickableSubscription subscription;

    public ShanhaiAeSteamCover(CoverDefinition definition, ICoverable holder, Direction side) {
        super(definition, holder, side);
    }

    public static CoverDefinition register() {
        var id = new ResourceLocation("gt_shanhai", "ae_steam_cover");
        var definition = new CoverDefinition(id, ShanhaiAeSteamCover::new,
                new ShanhaiAeSteamCoverRenderer());
        GTRegistries.COVERS.unfreeze();
        GTRegistries.COVERS.register(id, definition);
        return definition;
    }

    @Override
    public ManagedFieldHolder getFieldHolder() { return MANAGED_FIELD_HOLDER; }

    @Override
    public boolean canAttach() {
        MetaMachine machine = host();
        return isSteamConsumer(machine) && installedOn(machine) == null;
    }

    private static boolean isSteamConsumer(@Nullable MetaMachine machine) {
        return machine instanceof SimpleSteamMachine || machine instanceof SteamParallelMultiblockMachine
                || machine instanceof LargeSteamParallelMultiblockMachine
                || machine instanceof SteamHatchPartMachine || machine instanceof LargeSteamHatchPartMachine;
    }

    @Nullable
    private MetaMachine host() {
        return MetaMachine.getMachine(coverHolder.getLevel(), coverHolder.getPos());
    }

    @Nullable
    public static ShanhaiAeSteamCover installedOn(@Nullable MetaMachine machine) {
        if (machine == null) return null;
        for (Direction side : Direction.values()) {
            if (machine.getCoverContainer().getCoverAtSide(side) instanceof ShanhaiAeSteamCover cover) return cover;
        }
        return null;
    }

    /** 原生多方塊的蒸汽 handler 歸屬蒸汽倉，需沿其實際控制器回找覆蓋板。 */
    @Nullable
    public static ShanhaiAeSteamCover forConsumer(MetaMachine machine) {
        ShanhaiAeSteamCover own = installedOn(machine);
        if (own != null) return own;
        if (machine instanceof IMultiPart part) {
            for (var controller : part.getControllers()) {
                ShanhaiAeSteamCover cover = installedOn(controller.self());
                if (cover != null) return cover;
            }
        }
        return null;
    }

    public void bind(CompoundTag target) {
        binding = target.copy();
        coverHolder.markDirty();
        wakeConsumers();
    }

    CompoundTag bindingSnapshot() {
        return binding.copy();
    }

    public static String bindingDescription(CompoundTag target) {
        BlockPos pos = BlockPos.of(target.getLong("pos"));
        return target.getString("dimension") + " [" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + "]";
    }

    @Override
    public void onAttached(ItemStack stack, ServerPlayer player) {
        super.onAttached(stack, player);
        binding = ShanhaiAeSteamBinderBehavior.placementBinding(player, stack);
        if (!binding.isEmpty()) {
            player.displayClientMessage(Component.translatable("gt_shanhai.ae_steam_binder.bound"), true);
        }
        if (stack.hasTag()) prepaidSteam = Math.max(0, stack.getTag().getLong(CREDIT_TAG));
    }

    @Override
    public ItemStack getPickItem() {
        ItemStack stack = super.getPickItem().copy();
        if (!binding.isEmpty()) stack.getOrCreateTag().put(BINDING_TAG, binding.copy());
        else stack.removeTagKey(BINDING_TAG);
        if (prepaidSteam > 0) stack.getOrCreateTag().putLong(CREDIT_TAG, prepaidSteam);
        else stack.removeTagKey(CREDIT_TAG);
        return stack;
    }

    @Override
    public void onLoad() {
        super.onLoad();
        subscription = coverHolder.subscribeServerTick(subscription, () -> {
            if (coverHolder.getOffsetTimer() % 20 == 0) wakeConsumers();
        });
    }

    @Override
    public void onUnload() {
        if (subscription != null) { subscription.unsubscribe(); subscription = null; }
        super.onUnload();
    }

    @Override
    public void onRemoved() {
        onUnload();
        wakeConsumers();
        super.onRemoved();
    }

    private void wakeConsumers() {
        MetaMachine machine = host();
        wake(machine);
        if (machine instanceof IMultiPart part) {
            for (var controller : part.getControllers()) wake(controller.self());
        }
    }

    private static void wake(@Nullable MetaMachine machine) {
        if (machine instanceof IRecipeLogicMachine consumer) consumer.getRecipeLogic().updateTickSubscription();
    }

    @Override
    @Nullable
    public IGridNode getActionableNode() {
        if (!(coverHolder.getLevel() instanceof ServerLevel current) || binding.isEmpty()) return null;
        ResourceLocation dimension = ResourceLocation.tryParse(binding.getString("dimension"));
        if (dimension == null) return null;
        ServerLevel target = current.getServer().getLevel(ResourceKey.create(Registries.DIMENSION, dimension));
        BlockPos pos = BlockPos.of(binding.getLong("pos"));
        if (target == null || !target.hasChunkAt(pos)) return null;
        // 專用方塊一旦被拆除，即使同位置換成其他 AE 設備也不可悄悄改接網路。
        if (!(MetaMachine.getMachine(target, pos) instanceof ShanhaiAeSteamBinderMachine binder)) return null;
        IGridNode node = binder.getMainNode().getNode();
        return node != null && node.isActive() ? node : null;
    }

    @Nullable
    private static AEFluidKey steamKey() {
        var fluid = ForgeRegistries.FLUIDS.getValue(STEAM_ID);
        return fluid != null && STEAM_ID.equals(ForgeRegistries.FLUIDS.getKey(fluid)) ? AEFluidKey.of(fluid) : null;
    }

    public long availableSteam() {
        IGridNode node = getActionableNode();
        AEFluidKey key = steamKey();
        if (node == null || key == null) return 0;
        long available = node.getGrid().getStorageService().getInventory()
                .extract(key, Long.MAX_VALUE, Actionable.SIMULATE, IActionSource.ofMachine(this));
        return available > Long.MAX_VALUE - prepaidSteam ? Long.MAX_VALUE : available + prepaidSteam;
    }

    public boolean consume(List<Long> energy, double rate, boolean simulate) {
        long amount = ShanhaiAeSteamDemand.requiredSteam(energy, rate);
        if (amount == 0) return true;
        IGridNode node = getActionableNode();
        AEFluidKey key = steamKey();
        if (node == null || key == null || amount < 0) return false;
        var inventory = node.getGrid().getStorageService().getInventory();
        var source = IActionSource.ofMachine(this);
        var account = new ShanhaiAeSteamDemand.Account(prepaidSteam);
        ShanhaiAeSteamDemand.SteamStorage storage = (requested, simulation) -> inventory.extract(key, requested,
                simulation ? Actionable.SIMULATE : Actionable.MODULATE, source);
        try {
            return ShanhaiAeSteamDemand.consume((requested, simulation) -> account.extract(storage, requested, simulation),
                    amount, simulate);
        } finally {
            if (prepaidSteam != account.credit()) {
                prepaidSteam = account.credit();
                coverHolder.markDirty();
            }
        }
    }
}
