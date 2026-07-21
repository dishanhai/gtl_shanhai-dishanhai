package com.dishanhai.gt_shanhai.mixin;

import appeng.api.client.AEKeyRendering;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.AmountFormat;
import appeng.client.gui.me.crafting.CraftingStatusTableRenderer;
import appeng.menu.me.crafting.CraftingStatusEntry;

import com.dishanhai.gt_shanhai.client.PatternSourceClientCache;
import com.dishanhai.gt_shanhai.client.QuantumCraftingStatusClientCache;
import com.dishanhai.gt_shanhai.common.ae2.quantum.QuantumCraftingStatus;
import com.dishanhai.gt_shanhai.common.item.PatternRecipeTypeHelper;
import com.dishanhai.gt_shanhai.network.QuantumCraftingStatusRequestPacket;
import com.dishanhai.gt_shanhai.network.ShanhaiNetwork;
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
 * 合成状态详情页悬浮提示：量子 CPU 的真实调度状态、配方类型和样板来源。
 */
@Mixin(value = CraftingStatusTableRenderer.class, remap = false)
public class CraftingStatusTableRendererTooltipMixin {

    @Inject(method = "getEntryTooltip", at = @At("RETURN"), cancellable = true, remap = false)
    private void gtShanhai$appendPatternSource(CraftingStatusEntry entry,
            CallbackInfoReturnable<List<Component>> cir) {
        AEKey what = entry.getWhat();
        if (what == null) return;
        if ((entry.getActiveAmount() > 0L || entry.getPendingAmount() > 0L)
                && QuantumCraftingStatusClientCache.shouldRequest(what)) {
            ShanhaiNetwork.CHANNEL.sendToServer(new QuantumCraftingStatusRequestPacket(what));
        }

        PatternSourceClientCache.Info info = PatternSourceClientCache.get(what);
        QuantumCraftingStatus status = QuantumCraftingStatusClientCache.get(what);
        if (info == null && status == null && entry.getActiveAmount() <= 0L && entry.getPendingAmount() <= 0L) return;

        List<Component> lines = new ArrayList<>(cir.getReturnValue());
        if (status != null) {
            gtShanhai$appendCraftingStatus(lines, what, status);
        } else if (entry.getPendingAmount() > 0L) {
            lines.add(Component.literal("§e当前状态：§7正在读取阻塞原因..."));
        } else if (entry.getActiveAmount() > 0L) {
            lines.add(Component.literal("§e当前状态：§a已发料，等待机器返还"));
        }
        if (info != null) {
            lines.add(Component.literal("§b配方类型：§f" + gtShanhai$describeType(info.recipeTypeId())));
            lines.add(Component.literal("§b样板来源：§f" + info.pos().getX() + ", " + info.pos().getY() + ", " + info.pos().getZ())
                    .append(Component.literal(" §7[右键传送]").withStyle(ChatFormatting.GRAY)));
        }
        cir.setReturnValue(lines);
    }

    private static void gtShanhai$appendCraftingStatus(List<Component> lines, AEKey output,
            QuantumCraftingStatus status) {
        lines.add(Component.literal("§e当前状态：" + gtShanhai$statusText(status.state())));
        AEKey blockingInput = status.blockingInput();
        if (blockingInput != null) {
            lines.add(Component.literal("§c阻塞原料：§f").append(AEKeyRendering.getDisplayName(blockingInput)));
            lines.add(Component.literal("§7CPU已有 / 每份需要：§f"
                    + blockingInput.formatAmount(status.availableInput(), AmountFormat.FULL)
                    + " §7/ §f"
                    + blockingInput.formatAmount(status.requiredInputPerPattern(), AmountFormat.FULL)));
            if (status.waitingForInput() > 0L || status.pendingInput() > 0L) {
                lines.add(Component.literal("§7上游返还中 / 未发计划：§f"
                        + blockingInput.formatAmount(status.waitingForInput(), AmountFormat.FULL)
                        + " §7/ §f"
                        + blockingInput.formatAmount(status.pendingInput(), AmountFormat.FULL)));
            }
        }
        if (status.state() == QuantumCraftingStatus.State.READY_TO_DISPATCH) {
            lines.add(Component.literal("§7当前可发 / 剩余样板份数：§f"
                    + status.runnablePatterns() + " §7/ §f" + status.remainingPatterns()));
        }
        if (status.waitingForOutput() > 0L || status.pendingOutput() > 0L) {
            lines.add(Component.literal("§7本项返还中 / 未发计划：§f"
                    + output.formatAmount(status.waitingForOutput(), AmountFormat.FULL)
                    + " §7/ §f"
                    + output.formatAmount(status.pendingOutput(), AmountFormat.FULL)));
        }
    }

    private static String gtShanhai$statusText(QuantumCraftingStatus.State state) {
        return switch (state) {
            case WAITING_UPSTREAM -> "§6等待上游原料";
            case MISSING_INPUT -> "§c原料不足（无上游任务）";
            case NO_PROVIDER -> "§c找不到可用样板提供器";
            case PROVIDER_BUSY -> "§6样板提供器忙";
            case READY_TO_DISPATCH -> "§b原料齐全，等待发料调度";
            case WAITING_MACHINE -> "§a已发料，等待机器返还";
            case INVALID_PATTERN -> "§c样板输入无效";
            case PLANNED -> "§e仍在计划中，未定位直接任务";
        };
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
