package com.dishanhai.gt_shanhai.mixin;

import appeng.api.client.AEKeyRendering;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.AmountFormat;
import appeng.client.gui.me.crafting.CraftConfirmTableRenderer;
import appeng.menu.me.crafting.CraftingPlanSummaryEntry;

import com.dishanhai.gt_shanhai.common.ae2.CraftingRecursionDetector;
import com.dishanhai.gt_shanhai.common.item.CraftingPlanVirtualMarkerAccess;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;

/**
 * GTLCore 的 {@code CraftConfirmTableRendererMixin} 也注入 {@code getEntryDescription}，
 * 且是 {@code TAIL + cancellable} 结尾无条件 {@code setReturnValue}——那会 cancel 掉排在后面的回调。
 * 压到 500 保证本 mixin 先执行；同时下面那个注入<b>不</b>调 setReturnValue、直接改原列表，
 * 这样不 cancel，GTLCore 的「合成轮次 / 库存占用」两行还能照常追加，两边共存。
 */
@Mixin(value = CraftConfirmTableRenderer.class, remap = false, priority = 500)
public class CraftConfirmTableRendererVirtualMarkerMixin {

    @Inject(method = "getEntryDescription", at = @At("RETURN"), remap = false)
    private void gtShanhai$addVirtualPresenceDescription(CraftingPlanSummaryEntry entry,
            CallbackInfoReturnable<List<Component>> cir) {
        boolean virtualPresence = gtShanhai$isVirtualPresence(entry);
        boolean overflow = entry instanceof CraftingPlanVirtualMarkerAccess access
                && access.gtShanhai$isOverflow();
        if (!virtualPresence && !overflow) return;
        // 就地改 AE2 返回的那个 ArrayList，不 setReturnValue——一旦 cancel，GTLCore 的行就没了。
        List<Component> lines = cir.getReturnValue();
        if (lines == null) return;
        if (virtualPresence) {
            lines.add(0, Component.translatable("gui.gt_shanhai.crafting_plan.virtual_presence")
                    .withStyle(ChatFormatting.LIGHT_PURPLE));
        }
        // 溢出时 AE2 三个数字全被 `> 0` 挡掉，格子上一个字都没有；这行得排最前面。
        if (overflow) {
            lines.add(0, Component.literal("§4⚠ 数值溢出"));
        }
    }

    /** 溢出项按「缺失」着色，让玩家扫一眼就能在满屏格子里找到它。 */
    @Inject(method = "getEntryOverlayColor", at = @At("RETURN"), cancellable = true, remap = false)
    private void gtShanhai$overflowOverlayColor(CraftingPlanSummaryEntry entry,
            CallbackInfoReturnable<Integer> cir) {
        if (cir.getReturnValueI() != 0) return;
        if (entry instanceof CraftingPlanVirtualMarkerAccess access && access.gtShanhai$isOverflow()) {
            // 与 AE2 自己给 missingAmount > 0 用的半透明红一致。
            cir.setReturnValue(452919296);
        }
    }

    @Inject(method = "getEntryTooltip", at = @At("RETURN"), cancellable = true, remap = false)
    private void gtShanhai$addPlanDiagnostics(CraftingPlanSummaryEntry entry,
            CallbackInfoReturnable<List<Component>> cir) {
        boolean virtualPresence = gtShanhai$isVirtualPresence(entry);
        CraftingPlanVirtualMarkerAccess access =
                entry instanceof CraftingPlanVirtualMarkerAccess a ? a : null;
        boolean hasDiagnostics = access != null
                && (access.gtShanhai$isNoPattern()
                        || access.gtShanhai$isOverflow()
                        || access.gtShanhai$getRecursionKind() != CraftingRecursionDetector.Kind.NONE);
        boolean missing = entry.getMissingAmount() > 0L;
        if (!virtualPresence && !hasDiagnostics && !missing) return;

        List<Component> lines = new ArrayList<>(cir.getReturnValue());
        if (virtualPresence) {
            lines.add(Component.translatable("gui.gt_shanhai.crafting_plan.virtual_presence.detail")
                    .withStyle(ChatFormatting.LIGHT_PURPLE));
        }
        if (hasDiagnostics) {
            // 不缺失的自反项，AE2 自己已经把可用／合成数量写清楚了，不再重复一遍。
            if (missing) gtShanhai$appendBreakdown(lines, entry);
            gtShanhai$appendDiagnostics(lines, entry, access);
        } else if (missing) {
            // 有样板、又不成环，却还是缺——那缺口在它的上游，不在它自己。
            // AE2 这时只给一行「缺失数量」的数字，不说为什么，这里把原因补上。
            lines.add(Component.literal("§6⚠ 有样板但原料不足 — 缺的是它的上游材料，不是它本身"));
            lines.add(Component.literal("§7   顺依赖链往上找缺口，或直接往网络里补基础材料"));
        }
        cir.setReturnValue(lines);
    }

    /** 把 AE2 的 stored/craft/missing 三段拆开写清楚，玩家不用猜哪个数字是什么。 */
    private static void gtShanhai$appendBreakdown(List<Component> lines, CraftingPlanSummaryEntry entry) {
        AEKey what = entry.getWhat();
        if (entry.getStoredAmount() > 0L) {
            lines.add(Component.literal("§7需从网络抽取：§f"
                    + what.formatAmount(entry.getStoredAmount(), AmountFormat.FULL)));
        }
        if (entry.getCraftAmount() > 0L) {
            lines.add(Component.literal("§7需合成：§f"
                    + what.formatAmount(entry.getCraftAmount(), AmountFormat.FULL)
                    + " §8(有样板)"));
        }
        if (entry.getMissingAmount() > 0L) {
            lines.add(Component.literal("§c完全缺失：§f"
                    + what.formatAmount(entry.getMissingAmount(), AmountFormat.FULL)));
        }
    }

    /** 解释「为什么缺失」——AE2 只给数字，不给原因。 */
    private static void gtShanhai$appendDiagnostics(List<Component> lines,
            CraftingPlanSummaryEntry entry, CraftingPlanVirtualMarkerAccess access) {
        // 溢出时这一格所有数字都已回绕，其余诊断都不可信，报完就收。
        if (access.gtShanhai$isOverflow()) {
            gtShanhai$appendOverflow(lines, entry);
            return;
        }
        if (access.gtShanhai$isNoPattern()) {
            lines.add(Component.literal("§4⚠ 无样板提供器 — 下单后此项会永久阻塞"));
            lines.add(Component.literal("§4   请先为它编码样板，或手动往网络里投料"));
            return;
        }
        switch (access.gtShanhai$getRecursionKind()) {
            case SELF_LOOP_NO_GAIN -> {
                lines.add(Component.literal("§4⚠ 自反样板且净产出为零 — 备多少现货都下不了单"));
                lines.add(Component.literal("§4   样板吃掉的自身数量 ≥ 产出，例：1A + 水 → 1A"));
                lines.add(Component.literal("§c   每一轮都要等量现货垫底，总量永远不会增长"));
                lines.add(Component.literal("§7   请改样板配比，让它真正增产"));
            }
            case SELF_LOOP -> {
                lines.add(Component.literal("§4⚠ 自反样板：产出自己也消耗自己"));
                // AE2 的递归防护会拒绝「用同一样板去做它自己的输入」，
                // 所以这部分输入没有合成路径可走，只能吃现货，且必须一次备够。
                lines.add(Component.literal("§c   AE2 不允许再用同一样板去做它自己的输入，"));
                lines.add(Component.literal("§c   这部分只能吃网络现货，备不够就直接判缺失"));
                gtShanhai$appendSeedBudget(lines, entry);
            }
            case CYCLE -> {
                lines.add(Component.literal("§4⚠ 递归依赖环 — AE2 已静默丢弃成环样板，故本项缺失"));
                lines.add(gtShanhai$formatCycle(access.gtShanhai$getRecursionPath()));
                lines.add(Component.literal("§4   请打断环路：删掉其中一个样板，或为环上某项备好现货"));
            }
            default -> {
            }
        }
    }

    /**
     * 自反样板的现货账：把「还差多少才下得了单」直接算给玩家看。
     *
     * <p>{@code storedAmount} 是能从网络抽到的，{@code missingAmount} 是抽不到的，
     * 两者相加就是这一项绕不开的现货需求量——因为递归防护堵死了「合成它自己」这条路。
     */
    private static void gtShanhai$appendSeedBudget(List<Component> lines, CraftingPlanSummaryEntry entry) {
        AEKey what = entry.getWhat();
        long stored = entry.getStoredAmount();
        long shortage = entry.getMissingAmount();
        long need = stored + shortage;
        if (shortage > 0L) {
            lines.add(Component.literal("§4   现货 §f" + what.formatAmount(stored, AmountFormat.FULL)
                    + " §4/ 需要 §f" + what.formatAmount(need, AmountFormat.FULL)
                    + " §4→ 还差 §f" + what.formatAmount(shortage, AmountFormat.FULL)
                    + " §4，下不了单"));
            lines.add(Component.literal("§7   先把这些现货备进网络，或改样板让它少吃自己"));
        } else {
            lines.add(Component.literal("§6   现货 §f" + what.formatAmount(stored, AmountFormat.FULL)
                    + " §6刚好够本次下单，但这批打完就没了"));
            lines.add(Component.literal("§7   下次还想做同样的量，得先把现货补回来"));
        }
    }

    /**
     * 数量已经超出 {@code long}。AE2 全程 {@code if (amount > 0)} 才渲染，
     * 回绕成负数后整格空白——这里把「空白」翻译成人话，并把回绕值原样列出来便于定位是哪一段炸的。
     */
    private static void gtShanhai$appendOverflow(List<Component> lines, CraftingPlanSummaryEntry entry) {
        lines.add(Component.literal("§4⚠ 数值溢出 — 本项数量超出 64 位整数上限"));
        lines.add(Component.literal("§c   AE2 用 long 记数量，上限 9,223,372,036,854,775,807"));
        lines.add(Component.literal("§c   本项已回绕为负数，计划不可信，下单必然出错"));
        lines.add(Component.literal("§8   回绕值：可用 " + entry.getStoredAmount()
                + " / 缺失 " + entry.getMissingAmount()
                + " / 合成 " + entry.getCraftAmount()));
        lines.add(Component.literal("§7   请大幅调低单次下单量，分批合成"));
    }

    private static Component gtShanhai$formatCycle(List<AEKey> path) {
        MutableComponent line = Component.literal("§c   依赖链：");
        for (int i = 0; i < path.size(); i++) {
            if (i > 0) line.append(Component.literal(" §8→ "));
            line.append(AEKeyRendering.getDisplayName(path.get(i)).copy().withStyle(ChatFormatting.WHITE));
        }
        return line;
    }

    private static boolean gtShanhai$isVirtualPresence(CraftingPlanSummaryEntry entry) {
        return entry instanceof CraftingPlanVirtualMarkerAccess access
                && access.gtShanhai$isVirtualPresence();
    }
}
