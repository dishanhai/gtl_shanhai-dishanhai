package com.dishanhai.gt_shanhai.common.time.anchor;

import java.util.function.Supplier;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

/**
 * 山海重构 · 锚点条的 <b>S→C 消息</b>（一条消息 = 一个 {@link ShanhaiAnchorBarFrame}）。
 *
 * <h2>1. 只有一个字段</h2>
 * 消息体就是那个 {@code record} —— 不在网络层再拆一遍字段：
 * 拆过之后"消息里的 8 个字段"与"帧里的 8 个字段"就有两份真相，
 * 某天只改一边（漏一个 {@code writeVarLong}）编译期完全看不出来，
 * 表现出来是"倒流时条卡在 50%"。装成一个线程不安全的不可变对象，这种错只剩一处可犯。
 *
 * <h2>2. 线格式（🔴 顺序 = {@link ShanhaiAnchorBarFrame} 的字段顺序，别随手插字段）</h2>
 * <pre>
 *   VarLong anchorTick | VarInt windowTicks | VarLong nowTick | Boolean replayActive
 *   VarLong replayFromTick | VarLong replayToTick | VarLong replayCursorTick | Byte flags
 * </pre>
 * 用 VarLong 而不是 Long：tick 号在 1200 窗口里量级 ~1e5，VarLong 只占 3 字节（Long 恒定 8 字节），
 * 而这批包在倒流里是每 5 tick 一条。负值（{@link ShanhaiAnchorBarFrame#NO_ANCHOR} = -1）
 * 走 10 字节的兜底编码，语义完全正确（{@code readVarLong} 原样读回 -1）—— 离线往返已经验过。
 *
 * <h2>3. 为什么挂在 {@code shanhai:clock} 通道的 id 2</h2>
 * 扩展里已经有一个注册好、且在玩家登录之前就装好的通道（{@code ShanhaiClockNetwork}，id 0/1）。
 * 锚点条和世界时钟是同一件事的两个面（同一次倒流的两个读数），塞进同一条通道的好处是
 * <b>多一个握手就多一个能把客户端拦在门外的失败点</b>（版本号不一致 = 整条通道失效）。
 * 代价只是 id 表里多一格。
 *
 * <h2>4. 客户端处理只做一件事：写进缓存</h2>
 * 不在网络线程里算状态、不碰 {@code Minecraft}：只把帧存进
 * {@code com.dishanhai.gt_shanhai.client.time.hud.ShanhaiAnchorBarCache}（volatile 写），
 * 下一帧渲染时 HUD 自己去取。这样网络线程上永远不会碰到渲染状态。
 *
 * <p>🔴 {@code DistExecutor.unsafeRunWhenOn} 的那层 lambda：客户端类只有在 lambda
 * <b>真的被执行</b>时才会被加载 ⇒ 专用服务端不会因为这条消息碰到任何客户端类
 * （与 {@code ShanhaiClockNetwork.ClockStateMsg#handle} 逐字同款）。
 */
public record ShanhaiAnchorBarMsg(ShanhaiAnchorBarFrame frame) {

    /** 编解码往返用的字段数（判据 ① 按它数，别改成魔法数字）。 */
    public static final int FIELD_COUNT = 8;

    public ShanhaiAnchorBarMsg {
        if (frame == null) {
            // 坏输入 ⇒ "无锚点帧"（失败方向永远是"不画"）
            frame = ShanhaiAnchorBarFrame.noAnchor(0L, 0);
        }
    }

    // ------------------------------------------------------------------ 编解码

    public static void encode(ShanhaiAnchorBarMsg msg, FriendlyByteBuf buf) {
        final ShanhaiAnchorBarFrame f = msg.frame();
        buf.writeVarLong(f.anchorTick());        // 1
        buf.writeVarInt(f.windowTicks());        // 2
        buf.writeVarLong(f.nowTick());           // 3
        buf.writeBoolean(f.replayActive());      // 4
        buf.writeVarLong(f.replayFromTick());    // 5
        buf.writeVarLong(f.replayToTick());      // 6
        buf.writeVarLong(f.replayCursorTick());  // 7
        buf.writeByte(f.flags());                // 8
    }

    public static ShanhaiAnchorBarMsg decode(FriendlyByteBuf buf) {
        final long anchorTick = buf.readVarLong();
        final int windowTicks = buf.readVarInt();
        final long nowTick = buf.readVarLong();
        final boolean replayActive = buf.readBoolean();
        final long replayFromTick = buf.readVarLong();
        final long replayToTick = buf.readVarLong();
        final long replayCursorTick = buf.readVarLong();
        final byte flags = buf.readByte();
        return new ShanhaiAnchorBarMsg(new ShanhaiAnchorBarFrame(anchorTick, windowTicks, nowTick,
                replayActive, replayFromTick, replayToTick, replayCursorTick, flags));
    }

    public static void handle(ShanhaiAnchorBarMsg msg, Supplier<NetworkEvent.Context> ctxSupplier) {
        final NetworkEvent.Context ctx = ctxSupplier.get();
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> {
            // 只写一个 volatile 快照：网络线程不碰渲染，渲染线程下一帧自己来取
            com.dishanhai.gt_shanhai.client.time.hud.ShanhaiAnchorBarCache.apply(msg.frame());
        });
        ctx.setPacketHandled(true);
    }

    @Override
    public String toString() {
        return "AnchorBarStateMsg[" + this.frame.toLine() + "]";
    }
}
