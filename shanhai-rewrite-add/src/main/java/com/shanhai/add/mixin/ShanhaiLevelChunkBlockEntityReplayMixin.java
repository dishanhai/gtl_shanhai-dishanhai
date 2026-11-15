package com.shanhai.add.mixin;

import com.shanhai.add.time.replay.ShanhaiReplayProgressRecorder;

import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 山海重构 · <b>方块实体"登记"的注入点</b>：{@code LevelChunk#setBlockEntity(BlockEntity)} HEAD。
 *
 * <h2>1. 为什么需要它（否则"提前建好的基地"永远进不了进度时间线）</h2>
 * GT 机器跑配方时<b>不</b>调 {@code setChanged}（javap 实证：{@code RecipeLogic#setProgress(int)}
 * 只有 {@code putfield progress; return}）⇒ 进度必须由记录器每 tick 主动读。
 * 可是"读谁"？只能读<b>登记过</b>的、能读到进度的方块实体。
 * {@code LevelChunk#setBlockEntity} 是方块实体进入世界的<b>唯一入口</b>
 * （放方块、区块从磁盘加载、结构生成都会走它）⇒ 在这里登记，
 * 就能覆盖"玩家进服时基地早就建好了"的情况，而不是只有新放的机器才算。
 *
 * <h2>2. 只登记"能读到进度"的（其余连 map 都不进）</h2>
 * 具体判据（GT {@code MetaMachineBlockEntity→IRecipeLogicMachine→RecipeLogic}）全在普通类里，
 * 本类只转发 {@code (Object) be}。
 *
 * <h2>3. 与另外两个混入的关系</h2>
 * <ul>
 *   <li>{@code ShanhaiLevelChunkReplayMixin} 挂的是同一个类的 {@code setBlockState}（记方块变化）；</li>
 *   <li>本类挂 {@code setBlockEntity}（记"哪些 BE 要盯进度"）—— 两者互不影响。</li>
 * </ul>
 */
@Mixin(LevelChunk.class)
public abstract class ShanhaiLevelChunkBlockEntityReplayMixin {

    @Inject(method = "setBlockEntity(Lnet/minecraft/world/level/block/entity/BlockEntity;)V",
            at = @At("HEAD"), require = 1)
    private void shanhai$observeForReplay(BlockEntity blockEntity, CallbackInfo ci) {
        try {
            ShanhaiReplayProgressRecorder.observe(blockEntity);
        } catch (Throwable ignored) {
            // 登记失败只意味着这台机器进不了进度时间线（不影响世界）
        }
    }
}
