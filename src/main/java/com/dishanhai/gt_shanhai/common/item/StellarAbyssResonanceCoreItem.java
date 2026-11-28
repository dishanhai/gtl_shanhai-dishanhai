package com.dishanhai.gt_shanhai.common.item;

import com.dishanhai.gt_shanhai.common.machine.primordial.guixu.GuixuStarLiftingModule;
import com.dishanhai.gt_shanhai.common.machine.starlifter.StarLifterControllerMachine;
import com.dishanhai.gt_shanhai.common.machine.starlifter.StellarResonanceLinks;
import com.gregtechceu.gtceu.api.machine.MetaMachine;
import net.minecraft.ChatFormatting;
import net.minecraft.core.GlobalPos;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public final class StellarAbyssResonanceCoreItem extends Item {
    private static final String SOURCE = "ShanhaiStellarResonanceSource";
    private static final String TEXT = "gt_shanhai.item.stellar_abyss_resonance_core.";

    public StellarAbyssResonanceCoreItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult onItemUseFirst(ItemStack stack, UseOnContext context) {
        var player = context.getPlayer();
        if (player == null) return InteractionResult.PASS;
        var level = context.getLevel();
        var pos = context.getClickedPos();
        var machine = MetaMachine.getMachine(level, pos);
        boolean endpoint = machine instanceof StarLifterControllerMachine || machine instanceof GuixuStarLiftingModule;
        boolean sneaking = player.isShiftKeyDown();
        if (!endpoint && !(sneaking && hasSource(stack))) return InteractionResult.PASS;
        if (!(level instanceof ServerLevel serverLevel)) return InteractionResult.SUCCESS;

        var address = GlobalPos.of(level.dimension(), pos);
        var links = StellarResonanceLinks.get(serverLevel.getServer());
        if (sneaking) {
            if (endpoint && links.unlink(address)) {
                send(player, "unlinked", ChatFormatting.YELLOW);
            } else if (hasSource(stack)) {
                stack.removeTagKey(SOURCE);
                send(player, "cleared", ChatFormatting.YELLOW);
            } else {
                send(player, "not_linked", ChatFormatting.YELLOW);
            }
        } else if (machine instanceof StarLifterControllerMachine) {
            if (StellarResonanceLinks.starReady(serverLevel.getServer(), address)) {
                stack.getOrCreateTag().put(SOURCE, GlobalPos.CODEC.encodeStart(NbtOps.INSTANCE, address).result().orElseThrow());
                send(player, "recorded", ChatFormatting.GREEN, address.dimension().location(), pos.toShortString());
            } else {
                send(player, "unavailable", ChatFormatting.RED);
            }
        } else if (machine instanceof GuixuStarLiftingModule module) {
            var star = source(stack);
            if (star == null) {
                send(player, "no_source", ChatFormatting.RED);
            } else {
                switch (links.connect(serverLevel, star, module)) {
                    case LINKED -> {
                        stack.removeTagKey(SOURCE);
                        send(player, "linked", ChatFormatting.GREEN);
                    }
                    case ALREADY_LINKED -> send(player, "already_linked", ChatFormatting.YELLOW);
                    case STAR_OCCUPIED -> send(player, "star_occupied", ChatFormatting.RED);
                    case MODULE_OCCUPIED -> send(player, "module_occupied", ChatFormatting.RED);
                    case UNAVAILABLE -> send(player, "unavailable", ChatFormatting.RED);
                }
            }
        }
        return InteractionResult.CONSUME;
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        for (int line = 0; line < 3; line++) {
            tooltip.add(Component.translatable(TEXT + "tooltip." + line).withStyle(ChatFormatting.GRAY));
        }
        var address = source(stack);
        if (address != null) {
            tooltip.add(Component.translatable(TEXT + "recorded", address.dimension().location(),
                    address.pos().toShortString()).withStyle(ChatFormatting.AQUA));
        }
    }

    private static boolean hasSource(ItemStack stack) {
        return stack.getTag() != null && stack.getTag().contains(SOURCE, Tag.TAG_COMPOUND);
    }

    @Nullable
    private static GlobalPos source(ItemStack stack) {
        return hasSource(stack) ? GlobalPos.CODEC.parse(NbtOps.INSTANCE, stack.getTag().get(SOURCE)).result().orElse(null) : null;
    }

    private static void send(Player player, String key, ChatFormatting color, Object... args) {
        player.displayClientMessage(Component.translatable(TEXT + key, args).withStyle(color), true);
    }
}
