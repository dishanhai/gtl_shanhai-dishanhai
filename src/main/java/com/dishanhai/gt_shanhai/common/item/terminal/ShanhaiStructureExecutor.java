package com.dishanhai.gt_shanhai.common.item.terminal;

import java.util.ArrayList;
import java.util.List;

import com.dishanhai.gt_shanhai.common.item.terminal.ShanhaiTerminalAeBinding.Context;
import com.dishanhai.gt_shanhai.common.item.terminal.ShanhaiTerminalMaterialService.Preflight;
import com.gregtechceu.gtceu.api.machine.MetaMachine;

import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

public final class ShanhaiStructureExecutor {

    public record Result(boolean success, int changed, String message) {}

    private final ShanhaiTerminalMaterialService materials;

    public ShanhaiStructureExecutor(ShanhaiTerminalMaterialService materials) {
        this.materials = materials;
    }

    public Result execute(ServerPlayer player, ShanhaiStructurePlan plan, Context ae) {
        if (player.getServer() == null) return new Result(false, 0, "服务端不可用");
        ServerLevel level = player.getServer().getLevel(plan.target().dimension());
        if (level == null || !level.hasChunkAt(plan.target().pos())) {
            return new Result(false, 0, "目标区块未加载");
        }
        Result heightFailure = validateBuildHeight(level, plan);
        if (heightFailure != null) return heightFailure;
        Preflight preflight = materials.preflight(plan, player, ae);
        if (!preflight.success()) return new Result(false, 0, preflight.reason());
        List<ItemStack> replacementReturns = plan.entries().stream()
                .filter(entry -> entry.kind() == ShanhaiStructurePlan.Kind.REPLACE)
                .map(ShanhaiStructurePlan.Entry::current).filter(stack -> !stack.isEmpty()).toList();
        if (!materials.canReturnAll(player, ae, replacementReturns)) {
            return new Result(false, 0, "旧部件无回收空间");
        }
        List<ItemStack> forcedReturns = new ArrayList<>(plan.entries().stream()
                .filter(entry -> entry.kind() == ShanhaiStructurePlan.Kind.FORCE_REPLACE)
                .map(ShanhaiStructurePlan.Entry::current).filter(stack -> !stack.isEmpty()).toList());
        if (!materials.canStoreDismantled(player, ae, forcedReturns)) {
            return new Result(false, 0, "阻挡方块无回收空间");
        }
        forcedReturns.clear();
        ShanhaiTerminalMaterialService.BuildBatch buildBatch = materials.prepareBuildBatch(player, ae, plan);
        if (buildBatch == null) return new Result(false, 0, "施工期间材料发生变化");

        int changed = 0;
        List<ShanhaiStructurePlan.Entry> changedEntries = new ArrayList<>();
        for (ShanhaiStructurePlan.Entry entry : plan.entries()) {
            if (!entry.requiresMaterial()) continue;
            ItemStack material = buildBatch.takeOne(entry.desired());
            if (material.isEmpty()) {
                buildBatch.refundRemaining(player, ae);
                if (!materials.storeDismantled(player, ae, forcedReturns)) {
                    return new Result(false, changed, "材料变化且已替换方块回收失败");
                }
                return new Result(false, changed, "施工期间材料发生变化");
            }
            ItemStack removed = ItemStack.EMPTY;
            boolean forced = entry.kind() == ShanhaiStructurePlan.Kind.FORCE_REPLACE;
            if (entry.kind() == ShanhaiStructurePlan.Kind.REPLACE || forced) {
                removed = removeExisting(level, entry);
            }
            if (!place(level, player, entry, material)) {
                restoreExisting(level, entry, removed);
                materials.refund(player, ae, material);
                buildBatch.refundRemaining(player, ae);
                if (!materials.storeDismantled(player, ae, forcedReturns)) {
                    return new Result(false, changed, "方块放置失败且已替换方块回收失败");
                }
                return new Result(false, changed, "方块放置失败: " + entry.pos().toShortString());
            }
            if (!removed.isEmpty()) {
                if (forced) {
                    forcedReturns.add(removed);
                } else if (!materials.refund(player, ae, removed)) {
                    buildBatch.refundRemaining(player, ae);
                    if (!materials.storeDismantled(player, ae, forcedReturns)) {
                        return new Result(false, changed + 1, "旧部件与阻挡方块回收失败");
                    }
                    return new Result(false, changed + 1, "旧部件回收失败");
                }
            }
            changed++;
            changedEntries.add(entry);
        }
        if (!materials.storeDismantled(player, ae, forcedReturns)) {
            return new Result(false, changed, "阻挡方块回收失败");
        }
        if (!buildBatch.refundRemaining(player, ae)) {
            return new Result(false, changed, "剩余材料回收失败");
        }
        orientPlacedBlocks(level, plan, changedEntries);
        return new Result(true, changed, forcedReturns.isEmpty()
                ? "施工完成"
                : "施工完成，回收 " + forcedReturns.size() + " 个阻挡方块");
    }

    public Result executeCreative(ServerPlayer player, ShanhaiStructurePlan plan, Context ae) {
        if (player.getServer() == null) return new Result(false, 0, "服务端不可用");
        ServerLevel level = player.getServer().getLevel(plan.target().dimension());
        if (level == null || !level.hasChunkAt(plan.target().pos())) {
            return new Result(false, 0, "目标区块未加载");
        }
        Result heightFailure = validateBuildHeight(level, plan);
        if (heightFailure != null) return heightFailure;

        int changed = 0;
        List<ShanhaiStructurePlan.Entry> changedEntries = new ArrayList<>();
        List<ItemStack> forcedReturns = new ArrayList<>();
        for (ShanhaiStructurePlan.Entry entry : plan.entries()) {
            if (!entry.requiresMaterial()) continue;
            ItemStack material = entry.desired().copyWithCount(1);
            ItemStack removed = ItemStack.EMPTY;
            boolean forced = entry.kind() == ShanhaiStructurePlan.Kind.FORCE_REPLACE;
            if (entry.kind() == ShanhaiStructurePlan.Kind.REPLACE || forced) {
                removed = removeExisting(level, entry);
            }
            if (!place(level, player, entry, material)) {
                restoreExisting(level, entry, removed);
                storeCreativeForcedReturns(player, ae, forcedReturns);
                return new Result(false, changed, "方块放置失败: " + entry.pos().toShortString());
            }
            if (forced && !removed.isEmpty()) forcedReturns.add(removed);
            changed++;
            changedEntries.add(entry);
        }
        if (!storeCreativeForcedReturns(player, ae, forcedReturns)) {
            return new Result(false, changed, "创造模式施工完成，但阻挡方块回收失败");
        }
        orientPlacedBlocks(level, plan, changedEntries);
        return new Result(true, changed, forcedReturns.isEmpty()
                ? "创造模式施工完成"
                : "创造模式施工完成，回收 " + forcedReturns.size() + " 个阻挡方块");
    }

    private boolean storeCreativeForcedReturns(ServerPlayer player, Context ae, List<ItemStack> forcedReturns) {
        if (forcedReturns.isEmpty()) return true;
        if (materials.storeDismantled(player, ae, forcedReturns)) return true;
        return ae != null && materials.storeDismantled(player, null, forcedReturns);
    }

    public Result dismantle(ServerPlayer player, ShanhaiStructurePlan plan, Context ae) {
        if (player.getServer() == null) return new Result(false, 0, "服务端不可用");
        ServerLevel level = player.getServer().getLevel(plan.target().dimension());
        if (level == null || !level.hasChunkAt(plan.target().pos())) {
            return new Result(false, 0, "目标区块未加载");
        }
        List<ItemStack> dismantleReturns = plan.entries().stream()
                .filter(entry -> !entry.current().isEmpty())
                .filter(entry -> entry.kind() != ShanhaiStructurePlan.Kind.MANUAL)
                .map(ShanhaiStructurePlan.Entry::current).toList();
        if (!materials.canStoreDismantled(player, ae, dismantleReturns)) {
            return new Result(false, 0, "拆解物无回收空间");
        }
        int changed = 0;
        List<ShanhaiStructurePlan.Entry> removedEntries = new ArrayList<>();
        List<ItemStack> removedStacks = new ArrayList<>();
        for (ShanhaiStructurePlan.Entry entry : plan.entries()) {
            if (entry.current().isEmpty() || entry.kind() == ShanhaiStructurePlan.Kind.MANUAL) continue;
            ItemStack removed = removeExisting(level, entry);
            if (!removed.isEmpty()) {
                removedEntries.add(entry);
                removedStacks.add(removed);
            }
            changed++;
        }
        if (!materials.storeDismantled(player, ae, removedStacks)) {
            for (int i = 0; i < removedEntries.size(); i++) {
                restoreExisting(level, removedEntries.get(i), removedStacks.get(i));
            }
            return new Result(false, 0, "拆解物回收失败");
        }
        return new Result(true, changed, "拆解完成");
    }

    private Result validateBuildHeight(ServerLevel level, ShanhaiStructurePlan plan) {
        ShanhaiStructureBuildHeightValidator.Result result =
                ShanhaiStructureBuildHeightValidator.validateForGtceu(
                        plan, level.getMinBuildHeight(), level.getMaxBuildHeight());
        if (result.valid()) return null;
        if (level.getMaxBuildHeight() > result.maxBuildHeight() && result.upperLimitExceeded()) {
            return new Result(false, 0, "结构超过 GTCEu 多方块固定上限 Y"
                    + result.maxBuildY() + "，首个越界位置: "
                    + result.firstViolation().toShortString());
        }
        return new Result(false, 0, "结构超出世界高度范围 Y="
                + result.minBuildHeight() + ".." + result.maxBuildY()
                + "，首个越界位置: " + result.firstViolation().toShortString());
    }

    private ItemStack removeExisting(ServerLevel level, ShanhaiStructurePlan.Entry entry) {
        level.setBlock(entry.pos(), Blocks.AIR.defaultBlockState(), 3);
        return entry.current().copy();
    }

    private void restoreExisting(ServerLevel level, ShanhaiStructurePlan.Entry entry, ItemStack removed) {
        if (removed.isEmpty()) return;
        Block block = Block.byItem(removed.getItem());
        if (block != Blocks.AIR) level.setBlock(entry.pos(), block.defaultBlockState(), 3);
    }

    private boolean place(ServerLevel level, ServerPlayer player,
                          ShanhaiStructurePlan.Entry entry, ItemStack material) {
        if (!(material.getItem() instanceof BlockItem blockItem)) return false;
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(entry.pos()), Direction.UP, entry.pos(), false);
        BlockPlaceContext context = new BlockPlaceContext(
                level, player, InteractionHand.MAIN_HAND, material, hit);
        InteractionResult result = blockItem.place(context);
        return result.consumesAction() && !level.getBlockState(entry.pos()).isAir();
    }

    private void orientPlacedBlocks(ServerLevel level, ShanhaiStructurePlan plan,
                                    List<ShanhaiStructurePlan.Entry> changedEntries) {
        MetaMachine controller = MetaMachine.getMachine(level, plan.target().pos());
        Direction preferred = controller == null ? Direction.NORTH : controller.getFrontFacing();
        for (ShanhaiStructurePlan.Entry entry : changedEntries) {
            BlockState state = level.getBlockState(entry.pos());
            Direction found = null;
            Direction[] candidates = { preferred, Direction.NORTH, Direction.SOUTH,
                    Direction.WEST, Direction.EAST, Direction.UP, Direction.DOWN };
            MetaMachine placedMachine = MetaMachine.getMachine(level, entry.pos());
            for (Direction direction : candidates) {
                if (placedMachine != null && !placedMachine.isFacingValid(direction)) continue;
                if (level.getBlockState(entry.pos().relative(direction)).isAir()) {
                    found = direction;
                    break;
                }
            }
            if (found == null) found = preferred;
            if (state.hasProperty(BlockStateProperties.FACING)) {
                level.setBlock(entry.pos(), state.setValue(BlockStateProperties.FACING, found), 3);
            } else if (state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
                Direction horizontal = found.getAxis().isHorizontal() ? found : preferred;
                if (!horizontal.getAxis().isHorizontal()) horizontal = Direction.NORTH;
                level.setBlock(entry.pos(), state.setValue(BlockStateProperties.HORIZONTAL_FACING, horizontal), 3);
            }
        }
    }
}
