package com.dishanhai.gt_shanhai.mixin;

import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.crafting.ICraftingService;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.menu.me.crafting.CraftingPlanSummary;
import appeng.menu.me.crafting.CraftingPlanSummaryEntry;

import com.dishanhai.gt_shanhai.common.ae2.CraftingPlanOverflowDetector;
import com.dishanhai.gt_shanhai.common.ae2.CraftingRecursionDetector;
import com.dishanhai.gt_shanhai.common.item.CraftingPlanVirtualMarkerAccess;
import com.dishanhai.gt_shanhai.common.item.VirtualPatternEncodingHelper;

import it.unimi.dsi.fastutil.objects.Object2LongMap;

import net.minecraft.network.FriendlyByteBuf;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class CraftingPlanVirtualMarkerMixins {

    /** 依赖链最多同步几个节点，避免超长链撑爆数据包和提示框。 */
    private static final int MAX_SYNCED_PATH = 6;
    /** 单次计划最多做几次递归检测，避免大计划里逐项深搜。 */
    private static final int MAX_DETECTIONS_PER_PLAN = 24;

    private CraftingPlanVirtualMarkerMixins() {}

    /**
     * GTLCore 的 {@code CraftingPlanSummaryEntryMixin.read} 是 {@code cancellable = true}，
     * 结尾调 {@code cir.setReturnValue(...)}——那会 {@code cancel()}，把排在它后面的回调全部吃掉。
     * 两边都用默认 priority 1000 时，谁先执行取决于 mixin config 的加载顺序，不确定；
     * 一旦 GTLCore 抢先，我们写进 buffer 的字节就没人读走，下一个 entry 的 AEKey 会读到残留而错位。
     * 压到 500 让本 mixin 稳定先应用、先执行，write / read 两端顺序也因此对称。
     */
    @Mixin(value = CraftingPlanSummaryEntry.class, remap = false, priority = 500)
    public static class Entry implements CraftingPlanVirtualMarkerAccess {

        @Unique
        private boolean gtShanhai$virtualPresence;

        // 注意：Mixin 不保证把 @Unique 实例字段的初始化器合并进目标类构造器，
        // 这两个字段实际会停在 JVM 默认值 null，所有读取点必须自己兜底。
        @Unique
        private CraftingRecursionDetector.Kind gtShanhai$recursionKind;

        @Unique
        private List<AEKey> gtShanhai$recursionPath;

        @Unique
        private boolean gtShanhai$noPattern;

        @Unique
        private boolean gtShanhai$overflow;

        @Override
        public boolean gtShanhai$isVirtualPresence() {
            return gtShanhai$virtualPresence;
        }

        @Override
        public void gtShanhai$setVirtualPresence(boolean virtualPresence) {
            gtShanhai$virtualPresence = virtualPresence;
        }

        @Override
        public CraftingRecursionDetector.Kind gtShanhai$getRecursionKind() {
            return gtShanhai$recursionKind == null ? CraftingRecursionDetector.Kind.NONE : gtShanhai$recursionKind;
        }

        @Override
        public List<AEKey> gtShanhai$getRecursionPath() {
            return gtShanhai$recursionPath == null ? List.of() : gtShanhai$recursionPath;
        }

        @Override
        public void gtShanhai$setRecursion(CraftingRecursionDetector.Kind kind, List<AEKey> path) {
            gtShanhai$recursionKind = kind == null ? CraftingRecursionDetector.Kind.NONE : kind;
            gtShanhai$recursionPath = path == null ? List.of() : path;
        }

        @Override
        public boolean gtShanhai$isNoPattern() {
            return gtShanhai$noPattern;
        }

        @Override
        public void gtShanhai$setNoPattern(boolean noPattern) {
            gtShanhai$noPattern = noPattern;
        }

        @Override
        public boolean gtShanhai$isOverflow() {
            return gtShanhai$overflow;
        }

        @Override
        public void gtShanhai$setOverflow(boolean overflow) {
            gtShanhai$overflow = overflow;
        }

        @Inject(method = "write", at = @At("TAIL"), remap = false)
        private void gtShanhai$writeMarkers(FriendlyByteBuf buffer, CallbackInfo ci) {
            buffer.writeBoolean(gtShanhai$virtualPresence);
            buffer.writeBoolean(gtShanhai$noPattern);
            buffer.writeBoolean(gtShanhai$overflow);
            buffer.writeVarInt(gtShanhai$getRecursionKind().ordinal());
            List<AEKey> recursionPath = gtShanhai$getRecursionPath();
            int count = Math.min(MAX_SYNCED_PATH, recursionPath.size());
            buffer.writeVarInt(count);
            for (int i = 0; i < count; i++) {
                AEKey.writeKey(buffer, recursionPath.get(i));
            }
        }

        @Inject(method = "read", at = @At("RETURN"), remap = false)
        private static void gtShanhai$readMarkers(FriendlyByteBuf buffer,
                CallbackInfoReturnable<CraftingPlanSummaryEntry> cir) {
            CraftingPlanSummaryEntry entry = cir.getReturnValue();
            boolean virtualPresence = buffer.readBoolean();
            boolean noPattern = buffer.readBoolean();
            boolean overflow = buffer.readBoolean();
            CraftingRecursionDetector.Kind[] kinds = CraftingRecursionDetector.Kind.values();
            int kindId = buffer.readVarInt();
            CraftingRecursionDetector.Kind kind = kindId >= 0 && kindId < kinds.length
                    ? kinds[kindId]
                    : CraftingRecursionDetector.Kind.NONE;
            int count = buffer.readVarInt();
            List<AEKey> path = new ArrayList<>(Math.max(0, Math.min(count, MAX_SYNCED_PATH)));
            for (int i = 0; i < count; i++) {
                path.add(AEKey.readKey(buffer));
            }
            if (entry instanceof CraftingPlanVirtualMarkerAccess access) {
                access.gtShanhai$setVirtualPresence(virtualPresence);
                access.gtShanhai$setNoPattern(noPattern);
                access.gtShanhai$setOverflow(overflow);
                access.gtShanhai$setRecursion(kind, path);
            }
        }
    }

    @Mixin(value = CraftingPlanSummary.class, remap = false)
    public static class Summary {

        @Inject(method = "fromJob", at = @At("RETURN"), remap = false)
        private static void gtShanhai$markPlanEntries(IGrid grid, IActionSource actionSource,
                ICraftingPlan job, CallbackInfoReturnable<CraftingPlanSummary> cir) {
            CraftingPlanSummary summary = cir.getReturnValue();
            if (summary == null) return;

            Object2LongMap<AEKey> requirements = VirtualPatternEncodingHelper.collectPresenceRequirements(job);
            ICraftingService craftingService = grid == null ? null : grid.getCraftingService();
            Set<AEKey> overflowKeys = CraftingPlanOverflowDetector.collectOverflowKeys(job);
            int detections = 0;

            for (CraftingPlanSummaryEntry entry : summary.getEntries()) {
                if (!(entry instanceof CraftingPlanVirtualMarkerAccess access)) continue;
                if (requirements.containsKey(entry.getWhat())) {
                    access.gtShanhai$setVirtualPresence(true);
                }
                // 数量回绕成负数 = 已经溢出 long。AE2 的 tooltip / 格子小字全是 `> 0` 才渲染，
                // 溢出后整格空白，玩家连「这里出事了」都看不出来，所以优先标出来。
                // 只看这三个终值会漏掉「绕满一整圈又落回正数」的情况，所以还要认 overflowKeys。
                if (overflowKeys.contains(entry.getWhat())
                        || entry.getStoredAmount() < 0L || entry.getMissingAmount() < 0L
                        || entry.getCraftAmount() < 0L) {
                    access.gtShanhai$setOverflow(true);
                }

                if (craftingService == null) continue;
                // 不缺失、但要合成的条目：只做便宜的自反判定，不做深搜。
                // 自反样板靠库存里那几个种子就能让计划算出来，missingAmount 是 0，
                // 但种子耗尽就再也起不了步；净产出 ≤ 0 时更是压根做不出更多。
                if (entry.getMissingAmount() <= 0L) {
                    if (entry.getCraftAmount() > 0L) {
                        CraftingRecursionDetector.Result selfLoop =
                                CraftingRecursionDetector.detectSelfLoop(craftingService, entry.getWhat());
                        if (selfLoop.recursive()) {
                            access.gtShanhai$setRecursion(selfLoop.kind(), selfLoop.path());
                        }
                    }
                    continue;
                }
                if (!craftingService.isCraftable(entry.getWhat())) {
                    access.gtShanhai$setNoPattern(true);
                    continue;
                }
                // 有样板却仍然缺失，最常见的原因就是被 AE2 的递归防护静默丢弃了。
                if (detections++ >= MAX_DETECTIONS_PER_PLAN) continue;
                CraftingRecursionDetector.Result result =
                        CraftingRecursionDetector.detect(craftingService, entry.getWhat());
                if (result.recursive()) {
                    access.gtShanhai$setRecursion(result.kind(), result.path());
                }
            }
        }
    }
}
