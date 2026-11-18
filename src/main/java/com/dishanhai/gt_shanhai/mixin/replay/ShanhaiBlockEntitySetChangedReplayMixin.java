package com.dishanhai.gt_shanhai.mixin.replay;

import com.dishanhai.gt_shanhai.common.time.replay.ShanhaiReplayBlockEntityRecorder;

import net.minecraft.world.level.block.entity.BlockEntity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 山海重构 · <b>方块实体"被标记变化"的注入点</b>：{@code BlockEntity#setChanged()} HEAD。
 *
 * <h2>1. 为什么挂在这里（而不是每 N tick 扫全图）</h2>
 * 用户口径：「别周期兜底，那样看上去就是世界非连续的了」 ⇒ 方块实体必须<b>谁变了记谁、逐 tick 连续</b>。
 * {@code setChanged()} 是原版/第三方 mod 声明"我这个方块实体的持久化状态变了"的<b>唯一通用入口</b>
 * （容器增删物品、机器改库存、刷怪笼改计数、附魔台…都调它）。
 *
 * <h2>2. 这里只做一件事：打标记</h2>
 * 本方法在热路径上（GT/LDLib 有 markDirty 风暴），所以 HEAD 里<b>不序列化、不比对、不打日志</b>：
 * 只把 {@code (dim,pos)} 放进"本 tick 标记集"，真正的 NBT 差分在 tick 末由普通类做
 * （{@link ShanhaiReplayBlockEntityRecorder#flush}）。
 *
 * <h2>3. 🔴 混入类体内零 Minecraft 成员引用</h2>
 * 本工程的 refmap 只映射 {@code @Inject} 选中的方法名（实证：
 * {@code build\mixinrefmap\shanhai.refmap.json} 只有方法映射）⇒ 混入类体里直接碰 MC 成员
 * 会在生产环境拿开发名去 SRG 类上找 ⇒ 找不到。所以这里只有形参、{@code (Object) this}、一次转发。
 *
 * <h2>4. require = 1</h2>
 * 目标方法找不到时<b>加载期就炸</b>，而不是"方块实体这一层静默地什么都不记"。
 * 注意要用<b>完整描述符</b>：{@code BlockEntity} 上还有一个
 * {@code protected static void setChanged(Level, BlockPos, BlockState)} 同名方法。
 */
@Mixin(BlockEntity.class)
public abstract class ShanhaiBlockEntitySetChangedReplayMixin {

    @Inject(method = "setChanged()V", at = @At("HEAD"), require = 1)
    private void shanhai$markForReplay(CallbackInfo ci) {
        try {
            ShanhaiReplayBlockEntityRecorder.onSetChanged((Object) this);
        } catch (Throwable ignored) {
            // 第二道保险：真正的自吞在记录器内部（它坏了绝不能挡住 setChanged）
        }
    }
}
