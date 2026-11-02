package com.dishanhai.gt_shanhai.common.item;

import com.dishanhai.gt_shanhai.common.machine.preview.ShanhaiNyarlathotepEchoMachine;
import com.dishanhai.gt_shanhai.common.machine.preview.ShanhaiNyarlathotepPreviewMachine;
import com.dishanhai.gt_shanhai.common.machine.primordial.guixu.GuixuEngineLink;
import com.dishanhai.gt_shanhai.common.machine.primordial.guixu.GuixuNyarlathotepLink;
import com.gregtechceu.gtceu.api.machine.MetaMachine;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import java.util.List;

/** 山海：記錄奈亞，再右鍵回響建立遠端地址；不佔用歸墟原有連結。 */
public final class NyarlathotepEchoLinkModuleItem extends Item {
    private static final String KEY = "sh_nyarlathotep_echo_source";
    private static final String LANG = "gt_shanhai.item.nyarlathotep_echo_link_module.";
    public NyarlathotepEchoLinkModuleItem(Properties properties) { super(properties); }
    @Override public InteractionResult useOn(UseOnContext context) {
        return handleBlockUse(context.getLevel(), context.getPlayer(), context.getItemInHand(), context.getClickedPos());
    }
    public InteractionResult handleBlockUse(Level level, Player player, ItemStack stack, BlockPos pos) {
        if (player == null) return InteractionResult.PASS;
        var machine = MetaMachine.getMachine(level, pos);
        if (player.isShiftKeyDown()) {
            if (!level.isClientSide) {
                if (machine instanceof ShanhaiNyarlathotepEchoMachine echo) echo.clearNyarlathotepLink();
                if (stack.hasTag()) stack.getTag().remove(KEY);
                send(player, "cleared");
            }
            return InteractionResult.sidedSuccess(level.isClientSide);
        }
        if (machine instanceof ShanhaiNyarlathotepPreviewMachine source) {
            if (!level.isClientSide) {
                if (!source.isFormed() || !source.isNyarlathotepLinkLoaded() || source.isInValid()) send(player, "invalid");
                else {
                    var address = new CompoundTag();
                    address.putString("dimension", GuixuEngineLink.dimensionOf(level));
                    address.putLong("position", source.getPos().asLong());
                    stack.getOrCreateTag().put(KEY, address);
                    send(player, "recorded");
                }
            }
            return InteractionResult.sidedSuccess(level.isClientSide);
        }
        if (!(machine instanceof ShanhaiNyarlathotepEchoMachine echo)) return InteractionResult.PASS;
        if (!level.isClientSide) {
            CompoundTag address = stack.hasTag() && stack.getTag().contains(KEY) ? stack.getTag().getCompound(KEY) : null;
            if (address == null) send(player, "no_source");
            else if (!echo.isFormed() || !echo.isEchoLoaded() || echo.isInValid()) send(player, "invalid");
            else if (echo.hasNyarlathotepLink()) send(player, "occupied");
            else {
                var source = GuixuNyarlathotepLink.machineAt(level.getServer(), address.getString("dimension"), BlockPos.of(address.getLong("position")));
                if (!(source instanceof ShanhaiNyarlathotepPreviewMachine nyarlathotep) || !nyarlathotep.isFormed()
                        || !nyarlathotep.isNyarlathotepLinkLoaded() || nyarlathotep.isInValid()) send(player, "unavailable");
                else {
                    echo.setNyarlathotepLink(address.getString("dimension"), BlockPos.of(address.getLong("position")));
                    if (player.getAbilities().instabuild) stack.getTag().remove(KEY); else stack.shrink(1);
                    send(player, "linked");
                }
            }
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }
    @Override public void appendHoverText(ItemStack stack, Level level, List<Component> text, TooltipFlag flag) {
        for (int i = 0; i < 4; i++) text.add(Component.translatable(LANG + "tooltip." + i));
        if (stack.hasTag() && stack.getTag().contains(KEY)) {
            var address = stack.getTag().getCompound(KEY);
            text.add(Component.literal("§8" + address.getString("dimension") + " " + BlockPos.of(address.getLong("position")).toShortString()));
        }
    }
    private static void send(Player player, String key) { player.displayClientMessage(Component.translatable(LANG + key), true); }
}
