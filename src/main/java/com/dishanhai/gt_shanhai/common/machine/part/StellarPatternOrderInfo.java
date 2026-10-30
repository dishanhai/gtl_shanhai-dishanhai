package com.dishanhai.gt_shanhai.common.machine.part;

import appeng.api.networking.crafting.ICraftingLink;
import net.minecraft.nbt.CompoundTag;

/** 山海：AE 訂單觀測快照。取料、紅框消失及 CPU 消失都不是訂單完成證據。 */
public record StellarPatternOrderInfo(String id, String requester, String cpu, String output, String outputId,
                                     long requested, long remaining, State state) {
    public enum State {
        RUNNING, UNTRACKED, CANCELLED, COMPLETED;
        public boolean resolved() { return this == COMPLETED; }
        public boolean terminal() { return this == COMPLETED || this == CANCELLED; }
    }

    public static final StellarPatternOrderInfo UNKNOWN = new StellarPatternOrderInfo("", "", "", "", "", 0, 0, State.UNTRACKED);

    public StellarPatternOrderInfo {
        id = id == null ? "" : id;
        requester = requester == null ? "" : requester;
        cpu = cpu == null || cpu.isBlank() ? "未命名 CPU" : cpu;
        output = output == null ? "" : output;
        outputId = outputId == null ? "" : outputId;
        requested = Math.max(0, requested);
        remaining = Math.max(0, remaining);
        state = state == null || id.isBlank() ? State.UNTRACKED : state;
    }

    public static State stateOf(ICraftingLink link) {
        if (link == null) return State.UNTRACKED;
        if (link.isCanceled()) return State.CANCELLED;
        return link.isDone() ? State.COMPLETED : State.RUNNING;
    }

    public StellarPatternOrderInfo withOutcome(State observed, long remaining) {
        if (state.terminal() || state == State.UNTRACKED) return this;
        return new StellarPatternOrderInfo(id, requester, cpu, output, outputId, requested, remaining, observed);
    }

    public CompoundTag save() {
        var tag = new CompoundTag();
        StellarPatternHistoryText.put(tag, "id", id); StellarPatternHistoryText.put(tag, "requester", requester);
        StellarPatternHistoryText.put(tag, "cpu", cpu); StellarPatternHistoryText.put(tag, "output", output);
        StellarPatternHistoryText.put(tag, "outputId", outputId);
        tag.putLong("requested", requested); tag.putLong("remaining", remaining); tag.putString("state", state.name());
        return tag;
    }

    public static StellarPatternOrderInfo load(CompoundTag tag) {
        State state;
        try { state = State.valueOf(tag.getString("state")); } catch (IllegalArgumentException ignored) { state = State.UNTRACKED; }
        return new StellarPatternOrderInfo(StellarPatternHistoryText.get(tag, "id"), StellarPatternHistoryText.get(tag, "requester"),
                StellarPatternHistoryText.get(tag, "cpu"), StellarPatternHistoryText.get(tag, "output"),
                StellarPatternHistoryText.get(tag, "outputId"), tag.getLong("requested"), tag.getLong("remaining"), state);
    }
}
