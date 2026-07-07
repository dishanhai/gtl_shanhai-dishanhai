package com.dishanhai.gt_shanhai.network;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.FriendlyByteBuf;
import org.jetbrains.annotations.Nullable;

/**
 * 服务端→客户端：通知客户端隐藏/恢复机器周围的环方块。
 * 客户端用 {@link com.dishanhai.gt_shanhai.client.ShanhaiRingHelper} 处理。
 * <p>
 * 扩展：{@code ringType} 预留用于区分不同环类型。
 */
public class SHideRingPacket {

    public final BlockPos pos;
    public final Direction facing;
    public final boolean add; // true=成形隐藏, false=解体恢复
    @Nullable
    public final String ringType; // 扩展：环类型标识，null 表示默认

    public SHideRingPacket(BlockPos pos, Direction facing, boolean add) {
        this(pos, facing, add, null);
    }

    public SHideRingPacket(BlockPos pos, Direction facing, boolean add, @Nullable String ringType) {
        this.pos = pos;
        this.facing = facing;
        this.add = add;
        this.ringType = ringType;
    }

    public SHideRingPacket(FriendlyByteBuf buf) {
        this.pos = buf.readBlockPos();
        this.facing = buf.readEnum(Direction.class);
        this.add = buf.readBoolean();
        this.ringType = buf.readBoolean() ? buf.readUtf() : null;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBlockPos(pos);
        buf.writeEnum(facing);
        buf.writeBoolean(add);
        buf.writeBoolean(ringType != null);
        if (ringType != null) buf.writeUtf(ringType);
    }
}
