package com.dishanhai.gt_shanhai.common.item;

import com.dishanhai.gt_shanhai.common.machine.preview.ShanhaiNyarlathotepPreviewMachine;
import com.dishanhai.gt_shanhai.common.machine.primordial.guixu.GuixuEngineLink;
import com.dishanhai.gt_shanhai.common.machine.primordial.guixu.GuixuNyarlathotepLink;
import com.dishanhai.gt_shanhai.common.machine.primordial.guixu.GuixuVisionCoreMachine;
import com.gregtechceu.gtceu.api.machine.MetaMachine;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

import java.util.List;

/** 山海：記錄歸墟，再連接奈亞；綁定資料與舊引擎連結道具完全分離。 */
public final class GuixuNyarlathotepLinkModuleItem extends Item {
    private static final String KEY_ROOT = "sh_guixu_nyarlathotep_source";
    private static final String KEY_DIMENSION = "dimension";
    private static final String KEY_POSITION = "position";
    private static final String LANG = "gt_shanhai.item.guixu_nyarlathotep_link_module.";

    public GuixuNyarlathotepLinkModuleItem(Properties properties) { super(properties); }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        return handleBlockUse(context.getLevel(), context.getPlayer(), context.getItemInHand(), context.getClickedPos());
    }

    public InteractionResult handleBlockUse(Level level, Player player, ItemStack stack, BlockPos clickedPos) {
        if (player == null) return InteractionResult.PASS;
        MetaMachine machine = MetaMachine.getMachine(level, clickedPos);
        if (player.isShiftKeyDown()) {
            if (!level.isClientSide) {
                if (machine instanceof GuixuVisionCoreMachine host && host.hasNyarlathotepLink()) {
                    GuixuNyarlathotepLink.unlinkHost(host);
                    send(player, "unlinked", ChatFormatting.YELLOW);
                } else if (machine instanceof ShanhaiNyarlathotepPreviewMachine target && target.hasGuixuLink()) {
                    GuixuNyarlathotepLink.unlinkTarget(target);
                    send(player, "unlinked", ChatFormatting.YELLOW);
                } else {
                    clearBinding(stack);
                    send(player, "cleared", ChatFormatting.YELLOW);
                }
            }
            return InteractionResult.sidedSuccess(level.isClientSide);
        }
        if (machine instanceof GuixuVisionCoreMachine host) {
            if (!level.isClientSide) {
                if (!host.isFormed()) {
                    send(player, "host_invalid", ChatFormatting.RED);
                } else {
                    CompoundTag binding = new CompoundTag();
                    binding.putString(KEY_DIMENSION, GuixuEngineLink.dimensionOf(level));
                    binding.putLong(KEY_POSITION, host.getPos().asLong());
                    stack.getOrCreateTag().put(KEY_ROOT, binding);
                    send(player, "recorded", ChatFormatting.GREEN, host.getPos().toShortString());
                }
            }
            return InteractionResult.sidedSuccess(level.isClientSide);
        }
        if (!(machine instanceof ShanhaiNyarlathotepPreviewMachine target)) return InteractionResult.PASS;
        if (!level.isClientSide) bindTarget(level, player, stack, target);
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    private void bindTarget(Level level, Player player, ItemStack stack, ShanhaiNyarlathotepPreviewMachine target) {
        CompoundTag binding = getBinding(stack);
        if (binding == null) {
            send(player, "no_source", ChatFormatting.RED);
            return;
        }
        MetaMachine source = GuixuNyarlathotepLink.machineAt(level.getServer(), binding.getString(KEY_DIMENSION),
                BlockPos.of(binding.getLong(KEY_POSITION)));
        if (!(source instanceof GuixuVisionCoreMachine host)) {
            send(player, "source_unavailable", ChatFormatting.RED);
            return;
        }
        switch (GuixuNyarlathotepLink.bind(host, target)) {
            case LINKED -> {
                if (player.getAbilities().instabuild) clearBinding(stack);
                else stack.shrink(1);
                send(player, "linked", ChatFormatting.GREEN, target.getPos().toShortString());
            }
            case ALREADY_LINKED -> send(player, "already_linked", ChatFormatting.YELLOW);
            case HOST_OCCUPIED -> send(player, "host_occupied", ChatFormatting.RED);
            case TARGET_OCCUPIED -> send(player, "target_occupied", ChatFormatting.RED);
            case UNAVAILABLE -> send(player, "endpoints_invalid", ChatFormatting.RED);
        }
    }

    @Override
    public void appendHoverText(ItemStack stack, Level level, List<Component> tooltip, TooltipFlag flag) {
        for (int line = 0; line < 5; line++) {
            tooltip.add(Component.translatable(LANG + "tooltip." + line)
                    .withStyle(line == 0 ? ChatFormatting.LIGHT_PURPLE : ChatFormatting.GRAY));
        }
        CompoundTag binding = getBinding(stack);
        if (binding != null) {
            tooltip.add(Component.translatable(LANG + "source", binding.getString(KEY_DIMENSION),
                    BlockPos.of(binding.getLong(KEY_POSITION)).toShortString()).withStyle(ChatFormatting.AQUA));
        }
    }

    private static CompoundTag getBinding(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag == null || !tag.contains(KEY_ROOT, Tag.TAG_COMPOUND)) return null;
        CompoundTag binding = tag.getCompound(KEY_ROOT);
        return binding.contains(KEY_DIMENSION, Tag.TAG_STRING) && binding.contains(KEY_POSITION, Tag.TAG_LONG)
                ? binding : null;
    }

    private static void clearBinding(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag != null) tag.remove(KEY_ROOT);
    }

    private static void send(Player player, String key, ChatFormatting color, Object... args) {
        player.displayClientMessage(Component.translatable(LANG + key, args).withStyle(color), true);
    }
}
