package com.shanhai.add.mixin;

import com.shanhai.add.time.replay.ShanhaiReplayRecorder;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 山海重构 · <b>时间倒流的记录注入点</b>：{@code LevelChunk#setBlockState} HEAD。
 *
 * <h2>1. 目标方法是 javap 查证的</h2>
 * <pre>
 *   public BlockState setBlockState(BlockPos, BlockState, boolean);
 *   descriptor: (Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Z)
 *               Lnet/minecraft/world/level/block/state/BlockState;
 * </pre>
 * {@code LevelChunk} 上只有这一个 {@code setBlockState}（无重载）⇒ 描述符没有抄错的空间。
 * 它是方块写入的<b>最内层唯一入口</b>；世界生成走 {@code WorldGenRegion}/{@code ProtoChunk}，碰不到这里。
 *
 * <h2>2. 🔴 混入类体内零 Minecraft 成员引用</h2>
 * 本工程的 mixin 注解处理器<b>只把 {@code @Inject} 选中的方法名</b>写进 refmap
 * （实证：{@code build\mixinrefmap\shanhai_add.refmap.json} 只有方法映射，字段/@At 目标条目一条都没有）。
 * 混入类体内直接调 MC 成员 ⇒ 生产环境拿<b>字面开发名</b>去 SRG 命名的目标类上找 ⇒ 找不到 ⇒ 混入失败或崩。
 * 所以本类只做三件事：类型对得上的形参、{@code (Object) this}、一次转发调用；全部 MC 操作
 * （{@code getLevel/getBlockState/getBlockEntity/saveWithFullMetadata}）都在
 * {@link ShanhaiReplayRecorder}（普通类，会被 reobf）里做。
 *
 * <h2>3. 与既有探针互不影响</h2>
 * 本体已有 {@code ShanhaiSetBlockWatchMixin} 挂在<b>同一个</b> HEAD（纯日志探针，「零行为改动」）。
 * 两个不同 jar 的混入可以同时 @Inject 同一个方法，执行顺序按配置顺序，本类<b>不改返回值、不 cancel、
 * 不 @Redirect</b> ⇒ 无论谁先谁后，对原版行为的影响都是零。
 *
 * <h2>4. 为什么 {@code require = 1}</h2>
 * 目标方法找不到时<b>加载期就炸</b>，而不是"倒流静默地什么都记不到"（那种失败在游戏里看起来
 * 像"倒流没反应"，极难查）。这与本工程既有的探针取舍一致。
 */
@Mixin(LevelChunk.class)
public abstract class ShanhaiLevelChunkReplayMixin {

    /**
     * 方块写入的最内层入口：只把现场原样转交给普通类。
     *
     * @param pos      写入坐标
     * @param newState 即将写入的新状态（HEAD 时区块里还是旧状态，普通类读得到 A）
     * @param isMoving 原版形参，本类不使用（照抄以匹配描述符）
     * @param cir      原版返回值回调，本类<b>不</b>触碰
     */
    @Inject(
            method = "setBlockState(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Z)"
                    + "Lnet/minecraft/world/level/block/state/BlockState;",
            at = @At("HEAD"),
            require = 1)
    private void shanhai$recordForReplay(BlockPos pos, BlockState newState, boolean isMoving,
                                         CallbackInfoReturnable<BlockState> cir) {
        try {
            ShanhaiReplayRecorder.onSetBlockState((Object) this, pos, newState);
        } catch (Throwable ignored) {
            // 第二道保险：真正的自吞在 ShanhaiReplayRecorder 内部（记录器坏了绝不能挡住方块写入）
        }
    }
}
