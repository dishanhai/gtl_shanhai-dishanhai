package com.dishanhai.gt_shanhai.client.gui.starlifter;

import com.dishanhai.gt_shanhai.common.machine.starlifter.StarLifterHudTarget;
import com.dishanhai.gt_shanhai.common.machine.starlifter.StarLifterHudTelemetry.Snapshot;
import com.dishanhai.gt_shanhai.common.machine.starlifter.StarLifterHudTelemetry.State;
import com.dishanhai.gt_shanhai.network.StarLifterHudNetwork;
import com.dishanhai.sh_better_ui.client.api.ShBetterUiApi;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.LinkedHashMap;

import static com.dishanhai.gt_shanhai.common.machine.starlifter.StarLifterHudTelemetry.unavailable;

/** Only the visible Jade renderer drives requests. Samples can be shared by recently observed members. */
public final class StarLifterHudCache {
    private static final long POLL_MS = 250, SWITCH_MS = 110, FRESH_MS = 700;
    private static final Snapshot WAITING = unavailable(State.WAITING_FOR_DATA);
    private static final Snapshot EXPIRED = unavailable(State.EXPIRED);
    private static final Snapshot UNAVAILABLE = unavailable(State.DATA_UNAVAILABLE);
    private static final LinkedHashMap<Target, Member> MEMBERS = new LinkedHashMap<>();
    private static final LinkedHashMap<Owner, Sample> OWNERS = new LinkedHashMap<>();
    private static final PendingRequests PENDING = new PendingRequests();
    private static ClientLevel world;
    private static Target active;
    private static int sequence;
    private static long requestedAt = Long.MIN_VALUE / 2, firstRequestedAt;
    private static boolean requested, supported;

    public record Target(ClientLevel level, BlockPos pos, BlockState block, BlockEntity entity, StarLifterHudTarget role) {}
    private record Owner(BlockPos pos, long incarnation) {}
    private record Member(Owner owner, Snapshot detached, long at) {}
    private record Sample(Snapshot value, long at) {}

    static final class PendingRequests {
        private final LinkedHashMap<Integer, Long> sentAt = new LinkedHashMap<>();

        void sent(int sequence, long now) {
            sentAt.values().removeIf(time -> now - time > FRESH_MS);
            sentAt.put(sequence, now);
        }

        Long take(int sequence, long now) {
            Long time = sentAt.remove(sequence);
            if (time == null || now - time > FRESH_MS) return null;
            sentAt.values().removeIf(older -> older <= time);
            return time;
        }

        void clear() { sentAt.clear(); }
    }

    private StarLifterHudCache() {}

    public static Snapshot snapshot(Target target, long now) {
        if (target.level != world) return WAITING;
        if (!supported) return UNAVAILABLE;
        var member = MEMBERS.get(target);
        if (member == null) {
            return target.equals(active) && requested && now - firstRequestedAt > FRESH_MS ? UNAVAILABLE : WAITING;
        }
        if (now - member.at > FRESH_MS) return EXPIRED;
        if (member.owner == null) return member.detached;
        var sample = OWNERS.get(member.owner);
        if (sample == null || now - sample.at > FRESH_MS) return EXPIRED;
        var snapshot = sample.value;
        if (target.role != StarLifterHudTarget.CORE && !snapshot.formed()) return UNAVAILABLE;
        return snapshot;
    }

    public static Snapshot observe(Target target, long now) {
        if (world != target.level) {
            clear();
            world = target.level;
            var connection = Minecraft.getInstance().getConnection();
            supported = connection != null && StarLifterHudNetwork.supported(connection.getConnection());
        }
        if (!supported) return UNAVAILABLE;
        if (!target.equals(active)) {
            stopObserving();
            active = target;
        }
        if (now - requestedAt >= (requested ? POLL_MS : SWITCH_MS)) {
            PENDING.sent(++sequence, now);
            StarLifterHudNetwork.request(sequence, world.dimension().location(), target.pos);
            if (!requested) firstRequestedAt = now;
            requestedAt = now;
            requested = true;
        }
        return snapshot(target, now);
    }

    public static void accept(StarLifterHudNetwork.Reply reply) {
        var target = active;
        if (target == null || world != Minecraft.getInstance().level || !world.dimension().location().equals(reply.dimension())
                || !target.pos.equals(reply.pos()) || target.role != reply.role()
                || world.getBlockState(target.pos).getBlock() != target.block.getBlock()
                || world.getBlockEntity(target.pos) != target.entity) return;
        long now = ShBetterUiApi.motion().nowMs();
        Long sentAt = PENDING.take(reply.sequence(), now);
        if (sentAt == null) return;
        var snapshot = reply.snapshot();
        var before = MEMBERS.get(target);
        Owner owner = snapshot.controller() == null ? null : new Owner(snapshot.controller(), snapshot.incarnation());
        if (before != null && before.owner != null && !before.owner.equals(owner)) {
            OWNERS.remove(before.owner);
            MEMBERS.values().removeIf(member -> before.owner.equals(member.owner));
        }
        MEMBERS.put(target, new Member(owner, owner == null ? snapshot : null, sentAt));
        if (owner != null) {
            OWNERS.keySet().removeIf(existing -> existing.pos.equals(owner.pos) && !existing.equals(owner));
            OWNERS.put(owner, new Sample(snapshot, sentAt));
        }
        MEMBERS.values().removeIf(member -> now - member.at > FRESH_MS);
        OWNERS.values().removeIf(sample -> now - sample.at > FRESH_MS);
    }

    static void stopObserving() {
        active = null;
        requested = false;
        PENDING.clear();
    }

    public static void clear() {
        world = null;
        stopObserving();
        MEMBERS.clear();
        OWNERS.clear();
        requestedAt = Long.MIN_VALUE / 2;
    }
}
