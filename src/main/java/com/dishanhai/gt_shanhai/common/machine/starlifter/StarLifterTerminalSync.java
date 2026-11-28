package com.dishanhai.gt_shanhai.common.machine.starlifter;

import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.api.machine.multiblock.PartAbility;
import com.gregtechceu.gtceu.api.pattern.MultiblockState;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.GTRecipeSerializer;
import com.lowdragmc.lowdraglib.gui.widget.Widget;
import io.netty.buffer.Unpooled;
import net.minecraft.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.EnumMap;
import java.util.function.Consumer;

final class StarLifterTerminalSync extends Widget {
    private static final int STATE = 0, ACK = 1;
    enum Command { WORK, SHOW, REACTIVATE }
    record Response(int request, String message, long since) {
        boolean pending() { return message.equals("action.pending"); }
        boolean successful() { return message.equals("action.applied") || message.equals("capture.restored"); }
    }
    private static final String[] GROUPS = {"status", "progress", "recipe", "structure", "link", "reserve", "cost"};
    private static final PartAbility[] ABILITIES = {
            PartAbility.IMPORT_ITEMS, PartAbility.EXPORT_ITEMS, PartAbility.IMPORT_FLUIDS, PartAbility.EXPORT_FLUIDS,
            PartAbility.INPUT_ENERGY, PartAbility.INPUT_LASER, PartAbility.MAINTENANCE
    };
    static final String[] ABILITY_KEYS = {"item_in", "item_out", "fluid_in", "fluid_out", "energy", "laser", "maintenance"};

    private final StarLifterControllerMachine machine;
    private final CompoundTag state = new CompoundTag();
    private final EnumMap<Command, Response> responses = new EnumMap<>(Command.class);
    private Consumer<Response> feedback = ignored -> {};
    private int age;
    private int request;
    private GTRecipe recipe;
    private StellarCaptureRules.Rules rules;

    StarLifterTerminalSync(StarLifterControllerMachine machine) {
        super(0, 0, 0, 0);
        this.machine = machine;
    }

    CompoundTag group(String key) {
        return state.getCompound(key);
    }

    GTRecipe recipe() {
        return recipe;
    }

    boolean ready() {
        return state.contains("status");
    }

    void onFeedback(Consumer<Response> feedback) {
        this.feedback = feedback;
    }

    @Nullable Response response(Command command) {
        return responses.get(command);
    }

    void tickFeedback() {
        long now = Util.getMillis();
        var entries = responses.entrySet().iterator();
        while (entries.hasNext()) {
            var entry = entries.next();
            var response = entry.getValue();
            if (response.pending() && now - response.since > 5000) {
                var timeout = new Response(response.request, "action.timeout", now);
                entry.setValue(timeout);
                feedback.accept(timeout);
            } else if (!response.pending() && now - response.since > 3200) {
                entries.remove();
            }
        }
    }

    boolean submit(Command command, boolean enabled) {
        var previous = response(command);
        if (!ready() || previous != null && previous.pending()) return false;
        int sequence = ++request;
        responses.put(command, new Response(sequence, "action.pending", Util.getMillis()));
        writeClientAction(command.ordinal(), buf -> {
            buf.writeVarInt(sequence);
            if (command == Command.REACTIVATE) buf.writeInt(group("cost").hashCode());
            else buf.writeBoolean(enabled);
        });
        return true;
    }

    @Override
    public void handleClientAction(int id, FriendlyByteBuf buf) {
        var commands = Command.values();
        if (id < 0 || id >= commands.length || !(getGui().entityPlayer instanceof ServerPlayer player)) return;
        var command = commands[id];
        int sequence = buf.readVarInt();
        String result;
        if (!machine.canUseTerminal(player)) {
            result = "action.unavailable";
        } else if (command == Command.REACTIVATE) {
            result = buf.readInt() == cost(StellarCaptureRules.current()).hashCode()
                    ? machine.reactivateCapture(player) : "capture.changed";
        } else {
            boolean enabled = buf.readBoolean();
            if (command == Command.WORK && enabled && !machine.isStructureAvailable()) {
                result = machine.getMultiblockState().error == MultiblockState.UNLOAD_ERROR
                        ? "diagnostic.unloaded" : "state.unformed";
            } else {
                if (command == Command.WORK) machine.setWorkingEnabled(enabled);
                else machine.setShowEnabled(enabled);
                boolean applied = command == Command.WORK ? machine.isWorkingEnabled() : machine.isShowEnabled();
                result = applied == enabled ? "action.applied" : "action.rejected";
            }
        }
        detectAndSendChanges();
        String message = result;
        writeUpdateInfo(ACK, response -> {
            response.writeEnum(command);
            response.writeVarInt(sequence);
            response.writeUtf(message);
        });
    }

    @Override
    public void writeInitialData(FriendlyByteBuf buf) {
        update(true);
        buf.writeNbt(state);
    }

    @Override
    public void readInitialData(FriendlyByteBuf buf) {
        receive(buf.readNbt());
    }

    @Override
    public void detectAndSendChanges() {
        var delta = update(false);
        if (!delta.isEmpty()) writeUpdateInfo(STATE, buf -> buf.writeNbt(delta));
    }

    @Override
    public void readUpdateInfo(int id, FriendlyByteBuf buf) {
        if (id == STATE) receive(buf.readNbt());
        else if (id == ACK) {
            var command = buf.readEnum(Command.class);
            int sequence = buf.readVarInt();
            String message = buf.readUtf();
            var outstanding = responses.get(command);
            if (outstanding != null && outstanding.request == sequence) {
                var response = new Response(sequence, message, Util.getMillis());
                responses.put(command, response);
                if (outstanding.pending()) feedback.accept(response);
            }
        }
    }

    private void receive(@Nullable CompoundTag delta) {
        if (delta == null) return;
        for (String key : GROUPS) {
            if (delta.contains(key)) state.put(key, delta.getCompound(key));
        }
        if (delta.contains("recipe")) {
            var recipeTag = group("recipe");
            byte[] data = recipeTag.getByteArray("payload");
            recipe = null;
            if (data.length > 0) {
                var buf = new FriendlyByteBuf(Unpooled.wrappedBuffer(data));
                try {
                    recipe = GTRecipeSerializer.SERIALIZER.fromNetwork(buf.readResourceLocation(), buf);
                } finally {
                    buf.release();
                }
            }
        }
    }

    private CompoundTag update(boolean initial) {
        var delta = new CompoundTag();
        if (!(machine.getLevel() instanceof ServerLevel level)) return delta;
        var logic = machine.getRecipeLogic();
        boolean formed = machine.isStructureAvailable();
        var status = new CompoundTag();
        status.putString("state", logic.getStatus().name().toLowerCase(Locale.ROOT));
        status.putBoolean("formed", formed);
        status.putBoolean("work", machine.isWorkingEnabled());
        status.putBoolean("show", machine.isShowEnabled());
        status.putLong("start", machine.visualStartTick());
        status.putFloat("heading", StarLifterStructure.heading(machine.getFrontFacing()));
        var visual = machine.visualState(level);
        status.putByte("visualMode", (byte) visual.mode().ordinal());
        status.putLong("visualSince", visual.since());
        status.putBoolean("visualResonance", visual.resonance());
        status.putInt("visualReserve", visual.reserveLevel());
        status.putFloat("visualReserveFrom", visual.previousReserve());
        status.putLong("visualReserveSince", visual.reserveSince());
        var reason = logic.getFancyTooltip();
        if (!reason.isEmpty()) status.putString("reason", Component.Serializer.toJson(reason.get(0)));
        boolean structureChanged = formed != group("status").getBoolean("formed");
        put(delta, "status", status);

        var running = formed && !logic.isIdle() ? logic.getLastRecipe() : null;
        if (initial || running != recipe) put(delta, "recipe", encodeRecipe(running));
        if (initial || delta.contains("status") || delta.contains("recipe") || age % 4 == 0) {
            var progress = new CompoundTag();
            progress.putInt("current", running == null ? 0 : logic.getProgress());
            progress.putInt("total", running == null ? 0 : logic.getDuration());
            progress.putLong("completed", machine.completedBatches());
            put(delta, "progress", progress);
            var reserve = new CompoundTag();
            reserve.putLong("remaining", machine.captureRemaining());
            reserve.putBoolean("depleted", machine.captureDepleted());
            put(delta, "reserve", reserve);
        }
        var activeRules = StellarCaptureRules.current();
        if (initial || rules != activeRules) {
            rules = activeRules;
            put(delta, "cost", cost(rules));
        }
        if (initial || structureChanged || age % 10 == 0) {
            put(delta, "structure", structure(level, formed));
            put(delta, "link", link(level, formed));
        }
        age++;
        return delta;
    }

    private void put(CompoundTag delta, String key, CompoundTag value) {
        if (!value.equals(state.get(key))) {
            delta.put(key, value);
            state.put(key, value);
        }
    }

    private CompoundTag encodeRecipe(@Nullable GTRecipe running) {
        recipe = running;
        var data = new CompoundTag();
        if (running == null) return data;
        var buf = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buf.writeResourceLocation(running.getId());
            GTRecipeSerializer.SERIALIZER.toNetwork(buf, running);
            byte[] recipeBytes = new byte[buf.readableBytes()];
            buf.readBytes(recipeBytes);
            data.putByteArray("payload", recipeBytes);
        } finally {
            buf.release();
        }
        var origin = machine.getRecipeLogic().getLastOriginRecipe();
        data.putString("id", (origin == null ? running : origin).getId().toString());
        data.putInt("parallel", running.parallels);
        data.putInt("oc", running.ocTier);
        return data;
    }

    private static CompoundTag cost(StellarCaptureRules.Rules rules) {
        var data = new CompoundTag();
        data.putLong("capacity", rules.workTicks());
        var requirements = new ListTag();
        rules.items().forEach((item, count) -> {
            var line = new CompoundTag();
            line.putString("item", BuiltInRegistries.ITEM.getKey(item).toString());
            line.putInt("count", count);
            requirements.add(line);
        });
        data.put("items", requirements);
        return data;
    }

    private CompoundTag structure(ServerLevel level, boolean formed) {
        var data = new CompoundTag();
        if (formed) {
            var counts = new int[ABILITIES.length];
            var parts = machine.getParts();
            for (var part : parts) {
                var block = part.self().getBlockState().getBlock();
                for (int i = 0; i < counts.length; i++) {
                    if (ABILITIES[i].isApplicable(block)) counts[i]++;
                }
            }
            data.putIntArray("parts", counts);
            data.putInt("partCount", parts.size());
            return data;
        }
        var pattern = machine.getMultiblockState();
        boolean unloaded = pattern.error == MultiblockState.UNLOAD_ERROR;
        if (unloaded) data.putBoolean("unloaded", true);
        BlockPos failed = machine.failedPosition();
        if (failed == null && pattern.error != null && pattern.error != MultiblockState.UNINIT_ERROR) {
            failed = pattern.getPos();
        }
        if (failed != null) {
            var chunk = level.getChunkSource().getChunkNow(failed.getX() >> 4, failed.getZ() >> 4);
            if (chunk == null) {
                data.putLong("pos", failed.asLong());
                data.putBoolean("unloaded", true);
            } else if (!unloaded) {
                data.putLong("pos", failed.asLong());
                char symbol = StarLifterStructure.symbolAt(machine.getPos(), machine.getFrontFacing(), failed);
                data.putString("actual", chunk.getBlockState(failed).getBlock().getDescriptionId());
                if (symbol == '.') data.putString("expected", "block.minecraft.air");
                else if (symbol == '~') data.putString("expected", machine.getBlockState().getBlock().getDescriptionId());
                else if (symbol != ' ') data.putString("expected", StarLifterStructure.expectedBlock(symbol).getDescriptionId());
                data.putBoolean("port", symbol == 'K');
            }
        }
        return data;
    }

    private CompoundTag link(ServerLevel level, boolean formed) {
        var data = new CompoundTag();
        var endpoint = StellarResonanceLinks.get(level.getServer()).endpoint(level, machine.getPos(), formed);
        if (endpoint.address() != null) {
            data.putString("dimension", endpoint.address().dimension().location().toString());
            data.putLong("pos", endpoint.address().pos().asLong());
        }
        if (endpoint.host() != null) {
            data.putLong("host", endpoint.host().getPos().asLong());
            data.putBoolean("hostFormed", endpoint.host().isFormed());
        }
        data.putString("state", endpoint.state());
        return data;
    }
}
