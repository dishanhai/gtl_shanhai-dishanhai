package com.dishanhai.gt_shanhai.network;

import com.dishanhai.gt_shanhai.common.machine.primordial.PrimordialOmegaEngineMachine;
import com.gregtechceu.gtceu.api.machine.MetaMachine;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * 中子星渲染面板的稳定 C2S 控制通道。
 *
 * <p>LDLib 的 ButtonWidget 会自动生成嵌套 WidgetGroup action，但面板处于动画、重排
 * 或客户端/服务端子树下标暂时不一致时，那个路由会静默丢弃。这里把动作压成
 * 「机器位置 + 动作 + 已计算步长」，服务端仍由机器 setter 做最终门控与钳位。</p>
 */
public final class PrimordialStarRenderActionPacket {

    public static final int ACTION_TOGGLE_MODE = 0;
    public static final int ACTION_TOGGLE_PALETTE = 1;
    public static final int ACTION_CYCLE_RAINBOW_PERIOD = 2;
    public static final int ACTION_STEP_RADIUS = 3;
    public static final int ACTION_STEP_HUE = 4;

    private final BlockPos pos;
    private final int action;
    private final int value;

    public PrimordialStarRenderActionPacket(BlockPos pos, int action, int value) {
        this.pos = pos;
        this.action = action;
        this.value = value;
    }

    public PrimordialStarRenderActionPacket(FriendlyByteBuf buffer) {
        this.pos = buffer.readBlockPos();
        this.action = buffer.readVarInt();
        this.value = buffer.readInt();
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeBlockPos(pos);
        buffer.writeVarInt(action);
        buffer.writeInt(value);
    }

    public static void handle(PrimordialStarRenderActionPacket packet,
                              Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> applyOnServer(packet, context.getSender()));
        context.setPacketHandled(true);
    }

    private static void applyOnServer(PrimordialStarRenderActionPacket packet, ServerPlayer player) {
        if (player == null) {
            return;
        }
        Level level = player.level();
        if (level == null || level.isClientSide || !level.isLoaded(packet.pos)) {
            return;
        }
        MetaMachine metaMachine = MetaMachine.getMachine(level, packet.pos);
        if (!(metaMachine instanceof PrimordialOmegaEngineMachine machine)) {
            return;
        }
        switch (packet.action) {
            case ACTION_TOGGLE_MODE -> machine.toggleStarRenderMode();
            case ACTION_TOGGLE_PALETTE -> machine.toggleStarPalette();
            case ACTION_CYCLE_RAINBOW_PERIOD -> machine.cycleRainbowPeriod();
            case ACTION_STEP_RADIUS -> machine.stepStarRadius(packet.value);
            case ACTION_STEP_HUE -> machine.stepStarHue(packet.value);
            default -> {
            }
        }
    }
}
