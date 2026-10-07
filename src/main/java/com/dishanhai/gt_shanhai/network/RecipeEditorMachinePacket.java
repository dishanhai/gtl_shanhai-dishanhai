package com.dishanhai.gt_shanhai.network;

import com.dishanhai.gt_shanhai.common.recipe.editor.ShanhaiRecipeMachineMapping;
import com.dishanhai.gt_shanhai.common.recipe.editor.ShanhaiRecipeQuery;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;

import java.util.List;
import java.util.function.Supplier;

/** C2S: resolve a ghosted machine into its server-authoritative recipe types. */
public final class RecipeEditorMachinePacket {

    private final ItemStack machine;

    public RecipeEditorMachinePacket(ItemStack machine) {
        this.machine = machine == null ? ItemStack.EMPTY : machine.copyWithCount(1);
    }

    public RecipeEditorMachinePacket(FriendlyByteBuf buffer) {
        this.machine = buffer.readItem();
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeItem(machine);
    }

    public static void handle(RecipeEditorMachinePacket packet,
                              Supplier<NetworkEvent.Context> supplier) {
        NetworkEvent.Context context = supplier.get();
        ServerPlayer sender = context.getSender();
        context.enqueueWork(() -> {
            if (sender == null) return;
            if (!sender.hasPermissions(2)) {
                ShanhaiNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> sender),
                        new RecipeEditorResultPacket(
                                RecipeEditorResultPacket.Status.PERMISSION_DENIED,
                                "permission-denied",
                                ShanhaiRecipeQuery.currentRevision(),
                                ""));
                return;
            }
            List<ShanhaiRecipeMachineMapping.TypeOption> options =
                    ShanhaiRecipeMachineMapping.describe(sender, packet.machine);
            JsonObject payload = new JsonObject();
            payload.addProperty("machine", ShanhaiRecipeMachineMapping.machineId(packet.machine));
            JsonArray types = new JsonArray();
            for (ShanhaiRecipeMachineMapping.TypeOption option : options) {
                JsonObject value = new JsonObject();
                value.addProperty("id", option.id());
                value.addProperty("label", option.label());
                value.addProperty("count", option.recipeCount());
                if (option.owned()) value.addProperty("owned", true);
                types.add(value);
            }
            payload.add("types", types);
            boolean machinePresent = packet.machine != null && !packet.machine.isEmpty();
            ShanhaiNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> sender),
                    new RecipeEditorResultPacket(
                            machinePresent && options.isEmpty()
                                    ? RecipeEditorResultPacket.Status.VALIDATION_ERROR
                                    : RecipeEditorResultPacket.Status.SUCCESS,
                            "machine",
                            com.dishanhai.gt_shanhai.common.recipe.editor.ShanhaiRecipeQuery.currentRevision(),
                            payload.toString()));
        });
        context.setPacketHandled(true);
    }
}
