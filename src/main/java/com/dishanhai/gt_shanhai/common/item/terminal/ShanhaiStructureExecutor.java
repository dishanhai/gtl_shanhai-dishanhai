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
        Preflight preflight = materials.preflight(plan, player, ae);
        if (!preflight.success()) return new Result(false, 0, preflight.reason());
        if (player.getServer() == null) return new Result(false, 0, "服务端不可用");
        ServerLevel level = player.getServer().getLevel(plan.target().dimension());
        if (level == null || !level.hasChunkAt(plan.target().pos())) {
            return new Result(false, 0, "目标区块未加载");
        }
        List<ItemStack> replacementReturns = plan.entries().stream()
                .filter(entry -> entry.kind() == ShanhaiStructurePlan.Kind.REPLACE)
                .map(ShanhaiStructurePlan.Entry::current).filter(stack -> !stack.isEmpty()).toList();
        if (!materials.canReturnAll(player, ae, replacementReturns)) {
            return new Result(false, 0, "旧部件无回收空间");
        }

        int changed = 0;
        List<ShanhaiStructurePlan.Entry> changedEntries = new ArrayList<>();
        for (ShanhaiStructurePlan.Entry entry : plan.entries()) {
            if (!entry.requiresMaterial()) continue;
            ItemStack material = materials.takeOne(player, ae, entry.desired());
            if (material.isEmpty()) return new Result(false, changed, "施工期间材料发生变化");
            ItemStack removed = ItemStack.EMPTY;
            if (entry.kind() == ShanhaiStructurePlan.Kind.REPLACE) {
                removed = removeExisting(level, entry);
            }
            if (!place(level, player, entry, material)) {
                restoreExisting(level, entry, removed);
                materials.refund(player, ae, material);
                return new Result(false, changed, "方块放置失败: " + entry.pos().toShortString());
            }
            if (!removed.isEmpty() && !materials.refund(player, ae, removed)) {
                return new Result(false, changed + 1, "旧部件回收失败");
            }
            changed++;
            changedEntries.add(entry);
        }
        orientPlacedBlocks(level, plan, changedEntries);
        return new Result(true, changed, "施工完成");
    }

    public Result executeCreative(ServerPlayer player, ShanhaiStructurePlan plan) {
        if (player.getServer() == null) return new Result(false, 0, "服务端不可用");
        ServerLevel level = player.getServer().getLevel(plan.target().dimension());
        if (level == null || !level.hasChunkAt(plan.target().pos())) {
            return new Result(false, 0, "目标区块未加载");
        }

        int changed = 0;
        List<ShanhaiStructurePlan.Entry> changedEntries = new ArrayList<>();
        for (ShanhaiStructurePlan.Entry entry : plan.entries()) {
            if (!entry.requiresMaterial()) continue;
            ItemStack material = entry.desired().copyWithCount(1);
            ItemStack removed = ItemStack.EMPTY;
            if (entry.kind() == ShanhaiStructurePlan.Kind.REPLACE) {
                removed = removeExisting(level, entry);
            }
            if (!place(level, player, entry, material)) {
                restoreExisting(level, entry, removed);
                return new Result(false, changed, "方块放置失败: " + entry.pos().toShortString());
            }
            changed++;
            changedEntries.add(entry);
        }
        orientPlacedBlocks(level, plan, changedEntries);
        return new Result(true, changed, "创造模式施工完成");
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
