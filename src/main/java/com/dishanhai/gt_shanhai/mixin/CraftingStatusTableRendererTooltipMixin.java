package com.dishanhai.gt_shanhai.mixin;

import appeng.api.stacks.AEKey;
import appeng.client.gui.me.crafting.CraftingStatusTableRenderer;
import appeng.menu.me.crafting.CraftingStatusEntry;

import com.dishanhai.gt_shanhai.client.PatternSourceClientCache;
import com.dishanhai.gt_shanhai.common.item.PatternRecipeTypeHelper;
import com.gregtechceu.gtceu.api.recipe.GTRecipeType;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;

/**
 * 合成状态详情页悬浮提示追加两行：配方类型 + 供应它的样板总成坐标。原版"正在合成/计划合成"
 * 只有数量，不知道对应哪个配方类型、卡在哪台机器，这两行直接给出定位信息。
 */
@Mixin(value = CraftingStatusTableRenderer.class, remap = false)
public class CraftingStatusTableRendererTooltipMixin {

    @Inject(method = "getEntryTooltip", at = @At("RETURN"), cancellable = true, remap = false)
    private void gtShanhai$appendPatternSource(CraftingStatusEntry entry,
            CallbackInfoReturnable<List<Component>> cir) {
        AEKey what = entry.getWhat();
        PatternSourceClientCache.Info info = PatternSourceClientCache.get(what);
        if (info == null) return;

        List<Component> lines = new ArrayList<>(cir.getReturnValue());
        lines.add(Component.literal("§b配方类型：§f" + gtShanhai$describeType(info.recipeTypeId())));
        lines.add(Component.literal("§b样板来源：§f" + info.pos().getX() + ", " + info.pos().getY() + ", " + info.pos().getZ())
                .append(Component.literal(" §7[右键传送]").withStyle(ChatFormatting.GRAY)));
        cir.setReturnValue(lines);
    }

    private static String gtShanhai$describeType(String recipeTypeId) {
        if (recipeTypeId.isEmpty()) return recipeTypeId;
        GTRecipeType type = PatternRecipeTypeHelper.resolveRecipeType(recipeTypeId);
        if (type == null || type.registryName == null) return recipeTypeId;
        String key = type.registryName.toLanguageKey();
        String translated = Component.translatable(key).getString();
        return translated.equals(key) ? recipeTypeId : translated + " (" + recipeTypeId + ")";
    }
}
