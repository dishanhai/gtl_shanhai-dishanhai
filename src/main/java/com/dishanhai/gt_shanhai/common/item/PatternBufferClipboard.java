package com.dishanhai.gt_shanhai.common.item;

import appeng.api.inventories.InternalInventory;
import appeng.core.definitions.AEItems;

import com.gregtechceu.gtceu.api.item.tool.ToolHelper;
import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiController;
import com.gregtechceu.gtceu.api.machine.feature.multiblock.IMultiPart;

import com.lowdragmc.lowdraglib.misc.ItemStackTransfer;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import org.gtlcore.gtlcore.common.machine.multiblock.part.ae.MECraftPatternContainerPartMachine;
import org.gtlcore.gtlcore.common.machine.multiblock.part.ae.MEPatternBufferPartMachine;

import java.util.ArrayList;
import java.util.List;

/**
 * 样板总成工具箱的剪贴板层：把 GTLCore「样板总成复制工具」与「样板总成剪切工具」合成一件，并补齐上游没有的部分。
 *
 * <p>剪贴板 NBT 刻意与两把原版工具完全同构——同挂在 {@code ToolHelper.getBehaviorsTag} 下，
 * 复制存 {@code tag}、剪切存 {@code cut}，载荷都是 {@code {name, patterns:[{pattern, cacheCount, ...}]}}。
 * 于是 ME 样板总成 ↔ ME 样板核心 的剪贴板可以互相套用（核心侧忽略催化剂/共享槽等总成专属字段，
 * 总成侧读不到这些字段时按空处理）。</p>
 *
 * <p>相对上游两把工具新增：① 支持 ME 样板核心；② 复制模式可对多方块控制器批量套用。</p>
 */
public final class PatternBufferClipboard {

    /** 复制载荷键，与 GTLCore 复制工具一致。 */
    static final String COPY_KEY = "tag";
    /** 剪切载荷键，与 GTLCore 剪切工具一致。 */
    static final String CUT_KEY = "cut";

    private static final String NAME_KEY = "name";
    private static final String PATTERNS_KEY = "patterns";
    private static final String PATTERN_KEY = "pattern";
    private static final String CACHE_COUNT_KEY = "cacheCount";
    /** 样板总成新建时 cacheRecipeCount 全填 1，核心侧没有这个概念，写回默认值即可。 */
    private static final byte DEFAULT_CACHE_COUNT = 1;

    private PatternBufferClipboard() {}

    /** 一次操作的结果：{@code count} 为实际处理的样板数，{@code supported} 为目标是否可处理。 */
    public record Result(boolean supported, int count) {

        public boolean changed() {
            return supported && count > 0;
        }

        static final Result UNSUPPORTED = new Result(false, 0);

        static Result of(int count) {
            return new Result(true, count);
        }
    }

    // ------------------------------------------------------------------ 剪贴板状态

    public static CompoundTag clipboardTag(ItemStack tool) {
        return ToolHelper.getBehaviorsTag(tool);
    }

    public static boolean hasCopy(ItemStack tool) {
        CompoundTag tags = clipboardTag(tool);
        return tags.contains(COPY_KEY) && !patternList(tags.getCompound(COPY_KEY)).isEmpty();
    }

    public static boolean hasCut(ItemStack tool) {
        CompoundTag tags = clipboardTag(tool);
        return tags.contains(CUT_KEY) && !tags.getCompound(CUT_KEY).isEmpty();
    }

    public static int patternCount(ItemStack tool, String key) {
        CompoundTag tags = clipboardTag(tool);
        return tags.contains(key) ? patternList(tags.getCompound(key)).size() : 0;
    }

    public static String storedName(ItemStack tool, String key) {
        CompoundTag tags = clipboardTag(tool);
        return tags.contains(key) ? tags.getCompound(key).getString(NAME_KEY) : "";
    }

    public static void clear(ItemStack tool, String key) {
        clipboardTag(tool).remove(key);
    }

    /** 解出剪贴板里的样板本体，供 GUI 只读展示。 */
    public static List<ItemStack> previewStacks(ItemStack tool, String key, int limit) {
        CompoundTag tags = clipboardTag(tool);
        if (!tags.contains(key)) return List.of();
        ListTag patterns = patternList(tags.getCompound(key));
        List<ItemStack> stacks = new ArrayList<>(Math.min(limit, patterns.size()));
        for (int index = 0; index < patterns.size() && stacks.size() < limit; index++) {
            ItemStack stack = ItemStack.of(patterns.getCompound(index).getCompound(PATTERN_KEY));
            if (!stack.isEmpty()) stacks.add(stack);
        }
        return stacks;
    }

    private static ListTag patternList(CompoundTag payload) {
        return payload.getList(PATTERNS_KEY, Tag.TAG_COMPOUND);
    }

    // ------------------------------------------------------------------ 复制 / 套用

    /** 潜行右键：把目标的样板与命名抓进剪贴板。 */
    public static Result copyFrom(MetaMachine machine, ItemStack tool) {
        if (machine instanceof MEPatternBufferPartMachine buffer) {
            CompoundTag tags = clipboardTag(tool);
            buffer.copyToTag(tags);
            return Result.of(patternList(tags.getCompound(COPY_KEY)).size());
        }
        if (machine instanceof MECraftPatternContainerPartMachine core) {
            CompoundTag payload = readCore(core, false);
            int count = patternList(payload).size();
            if (count > 0) {
                clipboardTag(tool).put(COPY_KEY, payload);
            }
            return Result.of(count);
        }
        return Result.UNSUPPORTED;
    }

    /** 右键：把剪贴板里的样板套用到目标（每张样板消耗玩家背包里的 1 张空白样板）。 */
    public static Result applyCopy(MetaMachine machine, ItemStack tool, ServerPlayer player) {
        CompoundTag tags = clipboardTag(tool);
        if (!tags.contains(COPY_KEY)) return Result.of(0);
        CompoundTag payload = tags.getCompound(COPY_KEY);

        if (machine instanceof MEPatternBufferPartMachine buffer) {
            // 上游 copyFromTag 自己找空白样板并扣除，只是不回报数量——按空槽变化反推。
            int before = emptySlotCount(buffer);
            buffer.copyFromTag(payload, player);
            return Result.of(Math.max(0, before - emptySlotCount(buffer)));
        }
        if (machine instanceof MECraftPatternContainerPartMachine core) {
            return Result.of(writeCore(core, payload, player));
        }
        return Result.UNSUPPORTED;
    }

    /**
     * 复制模式点多方块控制器：把剪贴板套用到该多方块内全部样板总成与样板核心。
     * 上游两把工具都只认单个部件，这是本模组新增的批量能力。
     */
    public static Result applyCopyToMultiblock(IMultiController controller, ItemStack tool, ServerPlayer player) {
        if (!controller.isFormed()) return Result.UNSUPPORTED;
        int total = 0;
        boolean anyTarget = false;
        for (IMultiPart part : controller.getParts()) {
            Result result = applyCopy(part.self(), tool, player);
            if (!result.supported()) continue;
            anyTarget = true;
            total += result.count();
        }
        return anyTarget ? Result.of(total) : Result.UNSUPPORTED;
    }

    // ------------------------------------------------------------------ 剪切 / 粘贴

    /** 潜行右键：把目标的全部配置剪进剪贴板（源侧清空）。 */
    public static Result cutFrom(MetaMachine machine, ItemStack tool) {
        if (machine instanceof MEPatternBufferPartMachine buffer) {
            CompoundTag tags = clipboardTag(tool);
            buffer.cutToTag(tags);
            if (!tags.contains(CUT_KEY)) return Result.of(0);
            return Result.of(patternList(tags.getCompound(CUT_KEY)).size());
        }
        if (machine instanceof MECraftPatternContainerPartMachine core) {
            CompoundTag payload = readCore(core, true);
            int count = patternList(payload).size();
            if (count > 0) {
                clipboardTag(tool).put(CUT_KEY, payload);
                core.markDirty();
            }
            return Result.of(count);
        }
        return Result.UNSUPPORTED;
    }

    /** 右键：把剪切内容还原到目标；成功后剪贴板清空（剪切是一次性的）。 */
    public static Result pasteCut(MetaMachine machine, ItemStack tool) {
        CompoundTag tags = clipboardTag(tool);
        if (!tags.contains(CUT_KEY)) return Result.of(0);
        CompoundTag payload = tags.getCompound(CUT_KEY);

        if (machine instanceof MEPatternBufferPartMachine buffer) {
            int count = patternList(payload).size();
            if (!buffer.pasteFromTag(payload)) return Result.of(0);
            tags.remove(CUT_KEY);
            return Result.of(count);
        }
        if (machine instanceof MECraftPatternContainerPartMachine core) {
            // 核心侧不消耗空白样板：剪切是搬运不是复制，样板本体就在剪贴板里。
            int written = writeCore(core, payload, null);
            if (written <= 0) return Result.of(0);
            if (written >= patternList(payload).size()) {
                tags.remove(CUT_KEY);
            } else {
                // 只塞进去一部分：把已写入的从载荷里摘掉，剩下的留在剪贴板继续找地方放。
                trimWritten(payload, written);
            }
            return Result.of(written);
        }
        return Result.UNSUPPORTED;
    }

    // ------------------------------------------------------------------ ME 样板核心适配

    /** 把核心里的样板读成与样板总成同构的载荷；{@code take} 为 true 时同时清空核心。 */
    private static CompoundTag readCore(MECraftPatternContainerPartMachine core, boolean take) {
        ItemStackTransfer inventory = core.getPatternInventory();
        CompoundTag payload = new CompoundTag();
        payload.putString(NAME_KEY, "");
        ListTag patterns = new ListTag();
        for (int slot = 0; slot < inventory.getSlots(); slot++) {
            ItemStack stack = inventory.getStackInSlot(slot);
            if (stack.isEmpty()) continue;
            CompoundTag entry = new CompoundTag();
            entry.put(PATTERN_KEY, stack.serializeNBT());
            entry.putByte(CACHE_COUNT_KEY, DEFAULT_CACHE_COUNT);
            patterns.add(entry);
            if (take) inventory.setStackInSlot(slot, ItemStack.EMPTY);
        }
        payload.put(PATTERNS_KEY, patterns);
        return payload;
    }

    /**
     * 把载荷里的样板写进核心的空槽。{@code player} 非 null 时每写一张扣 1 张空白样板
     * （复制语义，与上游 copyFromTag 一致）；为 null 时不扣（剪切语义）。
     *
     * @return 实际写入张数；核心的 molecularFilter 会挡下加工样板，所以可能少于载荷张数
     */
    private static int writeCore(MECraftPatternContainerPartMachine core, CompoundTag payload,
                                 ServerPlayer player) {
        ItemStackTransfer inventory = core.getPatternInventory();
        ListTag patterns = patternList(payload);
        int written = 0;
        int slot = 0;
        for (int index = 0; index < patterns.size(); index++) {
            ItemStack pattern = ItemStack.of(patterns.getCompound(index).getCompound(PATTERN_KEY));
            if (pattern.isEmpty()) continue;
            int target = findWritableSlot(inventory, pattern, slot);
            if (target < 0) break;
            if (player != null && !consumeBlankPattern(player)) break;
            inventory.setStackInSlot(target, pattern);
            slot = target + 1;
            written++;
        }
        if (written > 0) core.markDirty();
        return written;
    }

    private static int findWritableSlot(ItemStackTransfer inventory, ItemStack pattern, int from) {
        for (int slot = from; slot < inventory.getSlots(); slot++) {
            if (inventory.getStackInSlot(slot).isEmpty() && inventory.isItemValid(slot, pattern)) return slot;
        }
        return -1;
    }

    private static boolean consumeBlankPattern(ServerPlayer player) {
        Inventory inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty() && AEItems.BLANK_PATTERN.isSameAs(stack)) {
                stack.shrink(1);
                return true;
            }
        }
        return false;
    }

    /** 摘掉载荷里已经写出去的前 N 张，剩下的留在剪贴板。 */
    private static void trimWritten(CompoundTag payload, int written) {
        ListTag patterns = patternList(payload);
        ListTag remaining = new ListTag();
        for (int index = written; index < patterns.size(); index++) {
            remaining.add(patterns.getCompound(index).copy());
        }
        payload.put(PATTERNS_KEY, remaining);
    }

    private static int emptySlotCount(MEPatternBufferPartMachine buffer) {
        // getTerminalPatternInventory() 直接返回 internalPatternInventory 本体，不是副本。
        InternalInventory inventory = buffer.getTerminalPatternInventory();
        int empty = 0;
        for (int slot = 0; slot < inventory.size(); slot++) {
            if (inventory.getStackInSlot(slot).isEmpty()) empty++;
        }
        return empty;
    }
}
