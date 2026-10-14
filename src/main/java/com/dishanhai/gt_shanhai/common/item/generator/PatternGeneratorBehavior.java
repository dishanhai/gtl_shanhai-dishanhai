package com.dishanhai.gt_shanhai.common.item.generator;

import com.dishanhai.gt_shanhai.common.gui.generator.PatternGeneratorWidget;
import com.gregtechceu.gtceu.api.gui.GuiTextures;
import com.gregtechceu.gtceu.api.item.component.IAddInformation;
import com.gregtechceu.gtceu.api.item.component.IItemLifeCycle;
import com.gregtechceu.gtceu.api.item.component.IItemUIFactory;

import com.lowdragmc.lowdraglib.gui.factory.HeldItemUIFactory;
import com.lowdragmc.lowdraglib.gui.modular.ModularUI;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 手持样板生成工具。功能参照源物品 {@code gtl_enhancedcore:pattern_generator}。
 * 组装配方的源物品是 {@code gtlcore:ultimate_terminal}、{@code ae2:pattern_encoding_terminal}、
 * {@code gtceu:data_stick}、{@code #gtceu:circuits/ev}、{@code gtceu:ev_sensor}、
 * {@code gtceu:ev_emitter} 与 {@code gtceu:soldering_alloy}。
 * 本物品的 NBT、预设格式和材质独立，不读取 Enhanced Core 的设置。行为本身无状态。
 */
public final class PatternGeneratorBehavior implements IItemUIFactory, IItemLifeCycle, IAddInformation {
    public static final PatternGeneratorBehavior INSTANCE = new PatternGeneratorBehavior();

    private PatternGeneratorBehavior() {}

    @Override
    public ModularUI createUI(HeldItemUIFactory.HeldItemHolder holder, Player player) {
        return new ModularUI(PatternGeneratorWidget.WIDTH, PatternGeneratorWidget.HEIGHT, holder, player)
                .background(GuiTextures.BACKGROUND)
                .widget(new PatternGeneratorWidget(holder));
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Item item, Level level, Player player, InteractionHand hand) {
        if (player instanceof ServerPlayer serverPlayer) {
            PatternGeneratorTransfer.cancel(serverPlayer);
            var settings = PatternGeneratorSettings.load(player.getItemInHand(hand));
            settings.transferArmed = false;
            player.getItemInHand(hand).getOrCreateTag().put(PatternGeneratorSettings.TAG, settings.write());
            HeldItemUIFactory.INSTANCE.openUI(serverPlayer, hand);
        }
        return new InteractionResultHolder<>(InteractionResult.SUCCESS, player.getItemInHand(hand));
    }

    @Override
    public InteractionResult onItemUseFirst(ItemStack stack, UseOnContext context) {
        if (!PatternGeneratorSettings.load(stack).transferArmed) return InteractionResult.PASS;
        if (context.getPlayer() instanceof ServerPlayer player) PatternGeneratorTransfer.begin(player, context);
        return InteractionResult.SUCCESS;
    }

    @Override
    public void inventoryTick(ItemStack stack, Level level, Entity entity, int slot, boolean selected) {
        if (entity instanceof ServerPlayer player) PatternGeneratorTransfer.tick(player, stack);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        if (context.getPlayer() instanceof ServerPlayer player) {
            player.displayClientMessage(Component.translatable("message.gt_shanhai.pattern_generator.right_click_air"), true);
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> lines, TooltipFlag flag) {
        for (String key : List.of("open", "choose", "extend", "generate", "fill", "presets", "source")) {
            lines.add(Component.translatable("tooltip.gt_shanhai.pattern_generator." + key).withStyle(ChatFormatting.GRAY));
        }
    }
}
