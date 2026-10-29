package com.dishanhai.gt_shanhai.common.cover;

import com.dishanhai.gt_shanhai.GTDishanhaiMod;
import com.dishanhai.gt_shanhai.common.item.DShanhaiItems;
import com.dishanhai.gt_shanhai.common.machine.steam.ShanhaiAeSteamBinderMachine;
import com.gregtechceu.gtceu.api.capability.GTCapabilityHelper;
import com.gregtechceu.gtceu.api.capability.ICoverable;
import com.gregtechceu.gtceu.api.item.component.IAddInformation;
import com.gregtechceu.gtceu.api.item.component.IInteractionItem;
import com.gregtechceu.gtceu.api.machine.MetaMachine;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.ArrayList;

/** 山海：記錄專用 AE 綁定方塊位置；攜帶即可讓新裝覆蓋板自動綁定。 */
@Mod.EventBusSubscriber(modid = GTDishanhaiMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ShanhaiAeSteamBinderBehavior implements IInteractionItem, IAddInformation {
    public static final ShanhaiAeSteamBinderBehavior INSTANCE = new ShanhaiAeSteamBinderBehavior();
    private ShanhaiAeSteamBinderBehavior() {}

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        Player player = event.getEntity();
        ItemStack stack = event.getItemStack();
        if (!player.isShiftKeyDown() || player.isSpectator() || !player.mayBuild()
                || !stack.is(DShanhaiItems.AE_STEAM_BINDER.get())) return;
        Level level = event.getLevel();
        Direction side = event.getFace();
        if (side == null) return;
        ICoverable holder = GTCapabilityHelper.getCoverable(level, event.getPos(), side);
        if (holder == null) return;
        Direction coverSide = ICoverable.rayTraceCoverableSide(holder, player);
        if (coverSide == null) coverSide = side;
        if (!(holder.getCoverAtSide(coverSide) instanceof ShanhaiAeSteamCover)) return;

        // 兩端均攔截挖掘；僅服務端起始事件回收一次，沿用原生資料保存與掉落。
        event.setCanceled(true);
        if (event.getAction() != PlayerInteractEvent.LeftClickBlock.Action.START) return;
        if (player instanceof ServerPlayer && level.mayInteract(player, event.getPos())
                && player.mayUseItemAt(event.getPos(), coverSide, stack)
                && holder.removeCover(coverSide, player)) {
            player.displayClientMessage(Component.translatable("gt_shanhai.ae_steam_binder.removed"), true);
        }
    }

    @Override
    public InteractionResult onItemUseFirst(ItemStack stack, UseOnContext context) {
        Level level = context.getLevel();
        var player = context.getPlayer();
        if (player == null) return InteractionResult.PASS;
        if (level.isClientSide) return InteractionResult.SUCCESS;
        var pos = context.getClickedPos();
        Direction side = context.getClickedFace();
        ICoverable holder = GTCapabilityHelper.getCoverable(level, pos, side);
        if (holder != null) {
            Direction coverSide = ICoverable.rayTraceCoverableSide(holder, player);
            if (coverSide == null) coverSide = side;
            if (holder.getCoverAtSide(coverSide) instanceof ShanhaiAeSteamCover cover) {
                CompoundTag current = cover.bindingSnapshot();
                CompoundTag selected = stack.hasTag()
                        ? stack.getTag().getCompound(ShanhaiAeSteamCover.BINDING_TAG) : new CompoundTag();
                CompoundTag next = player.isShiftKeyDown() ? new CompoundTag() : toggleBinding(current, selected);
                if (!current.isEmpty() || !next.isEmpty() || player.isShiftKeyDown()) {
                    cover.bind(next);
                    player.displayClientMessage(Component.translatable(next.isEmpty()
                            ? "gt_shanhai.ae_steam_binder.cleared" : "gt_shanhai.ae_steam_binder.bound"), true);
                } else {
                    player.displayClientMessage(Component.translatable("gt_shanhai.ae_steam_binder.select_first"), true);
                }
                return InteractionResult.CONSUME;
            }
        }
        // 可先記錄尚未通電的綁定方塊；是否能供汽在覆蓋板每次解析時判斷。
        if (!(MetaMachine.getMachine(level, pos) instanceof ShanhaiAeSteamBinderMachine)) {
            player.displayClientMessage(Component.translatable("gt_shanhai.ae_steam_binder.no_node"), true);
            return InteractionResult.CONSUME;
        }
        CompoundTag target = new CompoundTag();
        target.putString("dimension", level.dimension().location().toString());
        target.putLong("pos", pos.asLong());
        target.putByte("side", (byte) side.get3DDataValue());
        stack.getOrCreateTag().put(ShanhaiAeSteamCover.BINDING_TAG, target);
        player.displayClientMessage(Component.translatable("gt_shanhai.ae_steam_binder.selected",
                ShanhaiAeSteamCover.bindingDescription(target)), true);
        return InteractionResult.CONSUME;
    }

    static CompoundTag toggleBinding(CompoundTag current, CompoundTag selected) {
        if (!current.isEmpty()) return new CompoundTag();
        return validBinding(selected) ? selected.copy() : new CompoundTag();
    }

    public static CompoundTag placementBinding(Player player, ItemStack coverStack) {
        CompoundTag saved = coverStack.hasTag()
                ? coverStack.getTag().getCompound(ShanhaiAeSteamCover.BINDING_TAG) : new CompoundTag();
        List<CompoundTag> carried = new ArrayList<>();
        // 多支綁定器時：副手、主手、物品欄槽位順序，取第一支有效記錄。
        addCarriedBinding(carried, player.getOffhandItem());
        addCarriedBinding(carried, player.getMainHandItem());
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            addCarriedBinding(carried, player.getInventory().getItem(slot));
        }
        return choosePlacementBinding(saved, carried);
    }

    private static void addCarriedBinding(List<CompoundTag> carried, ItemStack stack) {
        if (stack.is(DShanhaiItems.AE_STEAM_BINDER.get()) && stack.hasTag()) {
            carried.add(stack.getTag().getCompound(ShanhaiAeSteamCover.BINDING_TAG));
        }
    }

    static CompoundTag choosePlacementBinding(CompoundTag saved, List<CompoundTag> carried) {
        for (CompoundTag candidate : carried) {
            if (validBinding(candidate)) return candidate.copy();
        }
        return validBinding(saved) ? saved.copy() : new CompoundTag();
    }

    private static boolean validBinding(CompoundTag target) {
        return target.contains("dimension", Tag.TAG_STRING)
                && ResourceLocation.tryParse(target.getString("dimension")) != null
                && target.contains("pos", Tag.TAG_LONG)
                && target.contains("side", Tag.TAG_BYTE)
                && target.getByte("side") >= 0 && target.getByte("side") < 6;
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> lines, TooltipFlag flag) {
        lines.add(Component.translatable("gt_shanhai.ae_steam_binder.tooltip"));
        lines.add(Component.translatable("gt_shanhai.ae_steam_binder.remove_tooltip"));
        if (stack.hasTag() && stack.getTag().contains(ShanhaiAeSteamCover.BINDING_TAG)) {
            lines.add(Component.literal(ShanhaiAeSteamCover.bindingDescription(
                    stack.getTag().getCompound(ShanhaiAeSteamCover.BINDING_TAG))));
        }
    }
}
