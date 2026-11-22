package com.shanhai.mixin.pump;

import com.shanhai.common.win.ShanhaiPump;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 山海重构 · <b>消息泵插点 1/2</b>：Forge 自己的主线程任务执行器每转一圈，就给窗口线程一次抽消息的机会。
 *
 * <h2>1. 为什么是这个方法（{@code javap} 取证，不是印象）</h2>
 * <pre>
 * javap -classpath fmlcore-1.20.1-47.4.16.jar 'net.minecraftforge.fml.ModWorkManager$SyncExecutor'
 *   class net.minecraftforge.fml.ModWorkManager$SyncExecutor
 *         implements net.minecraftforge.fml.ModWorkManager$DrivenExecutor {
 *     public boolean driveOne();
 *     public boolean selfDriven();
 *     public void execute(java.lang.Runnable);
 *   }
 * </pre>
 * 它在加载期被<b>高频</b>调用：{@code ModLoader.waitForTransition} 是个
 * {@code while (!future.isDone()) executor.drive(ticker);} 的忙等循环，而
 * {@code DrivenExecutor#drive} 每转一圈至少进一次 {@code driveOne()}。
 * 也就是说：<b>加载期主线程每空转一圈就经过这里一次</b>（那时没有帧，这是少数还在动的节拍）。
 * 上面这段 {@code drive -> driveOne} 的字节码取证见 {@code ShanhaiLoaderHeartbeatMixin} 的类注释
 * （它已经插在同一处 HEAD，两条 {@code @Inject} 并列互不影响）。
 *
 * <h2>2. 为什么放在公共的 {@code mixins} 数组（不是 {@code client}）</h2>
 * 目标类 {@code ModWorkManager} 两端都有，专服也在用；而本注入体调的
 * {@link ShanhaiPump#tick()} 是<b>纯 dist 中立</b>的（它只持有一个 {@code Runnable}，
 * 常量池里没有任何客户端类，也没有 JNA）。所以专服上这条注入的代价只是
 * "读一次 volatile 得到 null 然后返回"，不会碰任何客户端类。
 * <p>真正的实现（{@code com.shanhai.client.win} 那个类）只由客户端 setup 装进来。
 *
 * <h2>3. 三条纪律</h2>
 * <ul>
 *   <li>{@code remap = false}：{@code ModWorkManager} 是 Forge 自己的类，名字本来就不是 SRG，
 *       不需要映射，也不经过 refmap（绕开"refmap 少一条映射就静默不生效"那条路）；</li>
 *   <li>{@code @Mixin(targets = "...")} 而不是类字面量：{@code SyncExecutor} 是<b>包私有</b>类，
 *       跨包写不出类字面量；写字符串还顺带让本文件编译期<b>不依赖</b> fmlcore；</li>
 *   <li>{@code require = 0}：上游改名 / 被别的 coremod 动过时只记一行 WARN，<b>不打崩启动</b>；
 *       失败现象是"没有 {@code [SHANHAI-PUMP]} 读数行"，不是起不来。</li>
 * </ul>
 * <p>形参只有 {@code CallbackInfoReturnable<Boolean>}（目标方法无参、返回 {@code boolean}）；
 * 少写或多写参数会在应用期报 {@code Invalid descriptor}，而那是 {@code require = 0} 管不到的。
 */
@Mixin(targets = "net.minecraftforge.fml.ModWorkManager$SyncExecutor", remap = false)
public class ShanhaiSyncExecutorPumpMixin {

    /**
     * HEAD：在目标方法<b>取下一个任务之前</b>跑一次泵。
     *
     * <p>注入体只有一行（{@link ShanhaiPump#tick()}），它自己吞掉一切 Throwable
     * -> 从 Forge 的加载推进链里往外抛异常这件事不存在。
     */
    @Inject(method = "driveOne", at = @At("HEAD"), require = 0, remap = false)
    private void shanhai$pump(final CallbackInfoReturnable<Boolean> cir) {
        ShanhaiPump.tick();
    }
}
