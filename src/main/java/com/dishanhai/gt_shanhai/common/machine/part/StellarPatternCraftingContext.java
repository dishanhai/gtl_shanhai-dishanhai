package com.dishanhai.gt_shanhai.common.machine.part;

import org.jetbrains.annotations.Nullable;

/**
 * 星律样板下发期间的 AE 下单者上下文。
 * <p>
 * AE/量子 CPU 都在服务端线程同步调用 provider.pushPattern；星律只在该调用栈内读取一次，
 * 用完立即清理，避免污染同 tick 其他样板下发。
 */
public final class StellarPatternCraftingContext {

    private record Frame(Integer player, Order order) {}
    private static final ThreadLocal<java.util.Deque<Frame>> CONTEXT = ThreadLocal.withInitial(java.util.ArrayDeque::new);

    public record Order(appeng.api.networking.crafting.ICraftingLink link, appeng.api.stacks.GenericStack output,
                        java.util.function.LongSupplier remaining, Integer playerId, String cpu) {
        public StellarPatternOrderInfo snapshot(net.minecraft.server.MinecraftServer server) {
            if (link == null) return StellarPatternOrderInfo.UNKNOWN;
            String requester = "自动任务";
            if (playerId != null) {
                var player = appeng.api.features.IPlayerRegistry.getConnected(server, playerId);
                var uuid = appeng.api.features.IPlayerRegistry.getMapping(server).getProfileId(playerId);
                requester = (player == null ? "离线玩家" : player.getGameProfile().getName())
                        + " (AE #" + playerId + (uuid == null ? "" : ", " + uuid) + ")";
            }
            return new StellarPatternOrderInfo(link.getCraftingID().toString(), requester, cpu,
                    output == null ? "<unknown>" : output.what().getDisplayName().getString(),
                    output == null ? "<unknown>" : output.what().getId().toString(),
                    output == null ? 0 : output.amount(), remaining == null ? 0 : remaining.getAsLong(),
                    StellarPatternOrderInfo.stateOf(link));
        }
    }

    private StellarPatternCraftingContext() {}

    public static void push(@Nullable Integer aePlayerId) {
        CONTEXT.get().push(new Frame(aePlayerId, null));
    }

    public static void pushOrder(@Nullable Order order) {
        CONTEXT.get().push(new Frame(order == null ? null : order.playerId(), order));
    }

    public static void pop() {
        var stack = CONTEXT.get();
        if (!stack.isEmpty()) stack.pop();
        if (stack.isEmpty()) CONTEXT.remove();
    }

    @Nullable
    public static Integer currentAePlayerId() {
        Frame frame = CONTEXT.get().peek();
        return frame == null ? null : frame.player();
    }

    public static @Nullable Order currentOrder() {
        Frame frame = CONTEXT.get().peek();
        return frame == null ? null : frame.order();
    }
}
