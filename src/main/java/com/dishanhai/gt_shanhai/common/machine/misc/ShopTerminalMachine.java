package com.dishanhai.gt_shanhai.common.machine.misc;

import appeng.api.networking.IManagedGridNode;
import appeng.api.storage.MEStorage;
import com.dishanhai.gt_shanhai.GTDishanhaiRegistration;
import com.dishanhai.gt_shanhai.common.machine.ae.DShanhaiAENetworkMachine;
import com.dishanhai.gt_shanhai.common.shop.ShopAeNetwork;
import com.gregtechceu.gtceu.api.block.MetaMachineBlock;
import com.gregtechceu.gtceu.api.blockentity.MetaMachineBlockEntity;
import com.gregtechceu.gtceu.api.data.RotationState;
import com.gregtechceu.gtceu.api.item.MetaMachineItem;
import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity;
import com.gregtechceu.gtceu.api.machine.MachineDefinition;
import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.api.machine.feature.IMachineLife;
import com.gregtechceu.gtceu.api.registry.registrate.MachineBuilder;
import com.gregtechceu.gtceu.client.renderer.machine.WorkableCasingMachineRenderer;
import com.gregtechceu.gtceu.integration.ae2.machine.trait.GridNodeHolder;
import com.lowdragmc.lowdraglib.syncdata.annotation.DescSynced;
import com.lowdragmc.lowdraglib.syncdata.annotation.Persisted;
import com.lowdragmc.lowdraglib.syncdata.field.ManagedFieldHolder;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.EnumSet;
import java.util.UUID;

import static com.dishanhai.gt_shanhai.GTDishanhaiMod.MOD_ID;

/**
 * 山海商店终端：独立于 FTBQ AE 提交器（{@link FtbqAeSubmitterMachine}）给商店提供 AE 绑定能力。
 *
 * <p>放置即绑定放置者玩家 UUID（不走 FTBQ 队伍），只要连上 AE 线缆且网络在线，该玩家购物开启
 * AE 模式时就会优先走这台终端所在的网络。绑定源统一通过 {@link ShopAeNetwork} 解析，
 * 商店结算代码本身不关心到底是提交器还是商店终端在提供网络。</p>
 */
public class ShopTerminalMachine extends MetaMachine
        implements IMachineLife, DShanhaiAENetworkMachine, ShopAeNetwork.Provider {

    public static final ManagedFieldHolder MANAGED_FIELD_HOLDER = new ManagedFieldHolder(
            ShopTerminalMachine.class, MetaMachine.MANAGED_FIELD_HOLDER);

    @Persisted
    @DescSynced
    private UUID boundPlayerId;
    @Persisted
    @DescSynced
    private String boundPlayerName = "";
    @Persisted
    private final GridNodeHolder nodeHolder;
    @DescSynced
    private boolean isOnline;

    public ShopTerminalMachine(IMachineBlockEntity holder) {
        super(holder);
        this.nodeHolder = new GridNodeHolder(this);
        exposeGridNodeOnAllSides();
    }

    @Override
    public ManagedFieldHolder getFieldHolder() {
        return MANAGED_FIELD_HOLDER;
    }

    @Override
    public void onLoad() {
        super.onLoad();
        exposeGridNodeOnAllSides();
        if (!isRemote()) {
            ShopAeNetwork.register(this);
        }
    }

    @Override
    public void onUnload() {
        super.onUnload();
        if (!isRemote()) {
            ShopAeNetwork.unregister(this);
        }
    }

    @Override
    public void onMachinePlaced(@Nullable LivingEntity player, ItemStack stack) {
        if (player instanceof Player p) {
            boundPlayerId = p.getUUID();
            boundPlayerName = p.getName().getString();
            markDirty();
        }
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
    }

    @Override
    public String getAeJadeKind() {
        return "山海商店终端";
    }

    // ===== ShopAeNetwork.Provider：按放置者玩家 UUID 匹配，不走 FTBQ 队伍 =====

    @Override
    public boolean servesPlayer(ServerPlayer player) {
        return player != null && boundPlayerId != null && boundPlayerId.equals(player.getUUID());
    }

    @Override
    public MEStorage storage() {
        var grid = getMainNode().getGrid();
        return grid == null ? null : grid.getStorageService().getInventory();
    }

    public String getJadeBoundPlayerText() {
        if (boundPlayerId == null) return "未绑定";
        return boundPlayerName == null || boundPlayerName.isBlank() ? boundPlayerId.toString() : boundPlayerName;
    }

    public static MachineDefinition register() {
        MachineDefinition def = MachineBuilder.create(
                GTDishanhaiRegistration.REGISTRATE,
                "shop_terminal",
                MachineDefinition::createDefinition,
                ShopTerminalMachine::new,
                MetaMachineBlock::new,
                MetaMachineItem::new,
                MetaMachineBlockEntity::createBlockEntity
        )
                .rotationState(RotationState.ALL)
                .renderer(() -> new WorkableCasingMachineRenderer(
                        new ResourceLocation(MOD_ID, "block/casings/ftbq_ae_submitter_casing"),
                        new ResourceLocation(MOD_ID, "block/machine/part/shop_terminal")))
                .register();

        def.setTooltipBuilder((stack, tooltips) -> {
            tooltips.add(Component.literal("§6§l山海商店终端"));
            tooltips.add(Component.literal("§7可连接 AE 线缆，放置后自动绑定放置者"));
            tooltips.add(Component.literal("§7购物开启 AE 模式后优先走这台终端所在网络"));
            tooltips.add(Component.literal("§7和 FTBQ AE 提交器互不干扰，二者都能给商店供网"));
            tooltips.add(Component.literal("§c每台终端只服务放置它的那个玩家").withStyle(ChatFormatting.RED));
        });
        return def;
    }
}
