package com.shanhai.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.shanhai.client.ShanhaiWindowTitle;

/**
 * 山海重构 · <b>Forge 加载进度泵混入</b>：让「主循环还没开始」的那一段加载期也有标题心跳。
 *
 * <h2>0. 要治的是什么（用户 2026-11-20 报 + 已实测的事实，不是推测）</h2>
 * 用户原话：「加载上面的时间有的时候会卡住不动」。
 * <p>{@code latest.log}（那局）：{@code 22:08:20.229} 窗口出生给出标题快照 →
 * {@code 22:10:15.873} 才有第一条 {@code (mode=initial)}（= 第一个 {@code RenderTickEvent.END}）。
 * 中间 115 秒：本 mod 一行日志都没有，而且<b>遮罩也没画过一帧</b>
 * ⇒ 那段时间 {@code ClientTickEvent} / {@code RenderTickEvent} <b>一个都没派发</b>，
 * 任何「每帧刷新」的钩子（含 {@code LoadingOverlay#render}）都不可能生效。
 *
 * <h2>1. 🔴 为什么选这个目标（{@code javap} 取证，不是印象）</h2>
 * <h3>1.1 先否掉一个容易想到的答案：1.20.1 Forge <b>没有</b> {@code ProgressManager}</h3>
 * 取证对象 = 运行期真正在用的那个 jar（{@code latest.log} 22:07:58 那行打的就是它）：
 * {@code libraries/net/minecraftforge/fmlloader/1.20.1-47.4.16/fmlloader-1.20.1-47.4.16.jar}。
 * 该 jar 里 {@code net/minecraftforge/fml/loading/progress/} 只有四样东西：
 * {@code StartupNotificationManager}、{@code ProgressMeter}、{@code Message}、{@code Message$MessageType}
 * —— <b>没有 {@code ProgressManager}</b>（那个名字是更早版本的 Forge 的）。
 * 而 {@code StartupNotificationManager}/{@code ProgressMeter} 这一套的消费端是「启动画面 / 早期窗口」
 * （{@code ForgeLoadingOverlay} 每帧读一次）；本机这局日志 22:07:57 明确写着
 * {@code ImmediateWindowProvider not loading because splash screen is disabled} ⇒ 那条路一帧都不走，
 * 挂它等于挂在一个没人调的地方。
 *
 * <h3>1.2 选中的目标：{@code net.minecraftforge.fml.ModWorkManager$SyncExecutor#driveOne()Z}</h3>
 * <pre>
 * javap -p -classpath fmlcore-1.20.1-47.4.16.jar \
 *       'net.minecraftforge.fml.ModWorkManager$SyncExecutor'
 *   class net.minecraftforge.fml.ModWorkManager$SyncExecutor
 *         implements net.minecraftforge.fml.ModWorkManager$DrivenExecutor {
 *     public boolean driveOne();
 *     public boolean selfDriven();      // false
 *     public void execute(java.lang.Runnable);
 *   }
 *
 * javap -c -p -classpath fmlcore-1.20.1-47.4.16.jar \
 *       'net.minecraftforge.fml.ModWorkManager$DrivenExecutor'
 *   public default void drive(java.lang.Runnable);
 *     0: aload_0 / selfDriven() / ifne 27
 *     9: aload_1 / Runnable.run()          ← ticker（Forge 自己的加载进度回调）
 *    15: aload_0 / driveOne() / ifne 15    ← 循环：每执行完一个主线程任务就回来看一眼
 *
 * javap -c -p -classpath fmlcore-1.20.1-47.4.16.jar 'net.minecraftforge.fml.ModLoader'
 *   private void waitForTransition(IModLoadingState, DrivenExecutor, Runnable, CompletableFuture);
 *     0: aload 4 / CompletableFuture.isDone() / ifne 18
 *     8: aload_2 / DrivenExecutor.drive(Runnable)   ← ★ 忙等：没完成就再转一圈
 *    15: goto 0
 * </pre>
 * ⇒ <b>为什么它会被高频调用</b>：{@code ModLoader.waitForTransition} 是个
 * {@code while (!future.isDone()) syncExecutor.drive(ticker);} 的忙等循环；而每一次
 * {@code drive(...)} 至少进一次 {@code driveOne()}（见上面默认方法的字节码：先跑 ticker，再 driveOne，
 * 返回 false 才退出）。也就是说<b>加载期主线程每空转一圈就经过我们一次</b> ——
 * 这正是「加载期没有帧、但主线程还活着」时唯一还存在的节拍。
 * <p>同族调用点（同一个 pump，都在加载控制流上）：{@code handleInlineTransition}（3 处）、
 * {@code dispatchAndHandleError}（1 处），方法名与签名同为
 * {@code ModWorkManager$DrivenExecutor#drive(Ljava/lang/Runnable;)V}。
 *
 * <h3>1.3 为什么不是接口上的 {@code DrivenExecutor#drive(Runnable)} 本身</h3>
 * 它是 <b>interface 的 default 方法</b>：往上 mixin 要面对「接口混入」那一套额外约束
 * （混入类自身要不要声明成接口、default 方法体的注入语义），而收益为零 ——
 * 由上面的字节码可见 {@code drive} 每次调用<b>必定</b>进一次 {@code driveOne}，
 * 两个钩子的触发频率是同一个量级。选普通类的普通方法，风险最小。
 *
 * <h3>1.4 线程：它在<b>主线程</b>上（这是本条能成立的前提）</h3>
 * 这局的日志里，模组加载的后半段（{@code FMLCommonSetup} / KubeJS startup / 客户端 reload 监听器注册）
 * 全部打在 <b>{@code [Render thread]}</b> 上（例：22:08:35.711 {@code GregTechCEu: GTCEu common proxy init!}、
 * 22:08:52.311 {@code [SHANHAI-REALITY] geometry_loader_registered}），并行部分才在
 * {@code [modloading-worker-0]} 上 ⇒ 加载泵跑在主线程。
 * <p>⚠️ 但我们<b>不</b>拿这个结论当免检：{@link ShanhaiWindowTitle#tickFromLoader()} 里第一件事就是
 * 用原版 {@code Minecraft#isSameThread()} 复核，不在主线程就永久关掉这条链路（GLFW 只许主线程碰）。
 *
 * <h2>2. 🔴 {@code remap = false} + {@code require = 0} + 全程 {@code try/catch}</h2>
 * <ul>
 *   <li><b>{@code remap = false}</b>：目标是 Forge 自己的类（不是被 MCP 重映射的原版类），
 *       方法名 {@code driveOne} 在运行期就是这个名字，绕开「refmap 里没这条映射 ⇒ 静默不生效」那条路。</li>
 *   <li><b>{@code require = 0}</b>：目标方法改名 / 被别的 coremod 动过 ⇒ 只记一行 WARN，
 *       <b>不抛、不打崩启动</b>；失败现象是「加载期标题仍冻结但不崩」，判据是
 *       {@code latest.log} 里<b>没有</b> {@code [SHANHAI-TITLE] loader_heartbeat thread=…} 这一行。</li>
 *   <li><b>{@code @Mixin(targets = "…")}</b> 而不是类字面量：{@code SyncExecutor} 是<b>包私有</b>类，
 *       跨包写不出类字面量；写字符串还顺带让本文件编译期<b>不依赖</b> fmlcore。</li>
 *   <li><b>注入体只有一行</b>（{@code tickFromLoader()}）+ 一个 {@code catch (Throwable)}：
 *       我们挂的是<b>模组加载</b>的推进链，从这里抛出去就不是标题难看，而是整局起不来。</li>
 * </ul>
 *
 * <h2>3. 🔴 为什么进 {@code shanhai.mixin.json} 的 {@code client} 数组（不是 {@code mixins}）</h2>
 * <ol>
 *   <li><b>目标类两个端都有</b>（{@code ModWorkManager} 在 fmlcore 里，专服同样加载）；
 *       若放进公共的 {@code mixins} 数组，专服也会应用这条注入，然后去加载
 *       {@link ShanhaiWindowTitle} —— 而那个类的常量池里有 {@code net/minecraft/client/…}
 *       ⇒ 正是本工程血账里那条 {@code Attempted to load class … for invalid dist DEDICATED_SERVER}。
 *       虽然注入体有 {@code try/catch (Throwable)} 兜着，但那是「兜」，不是「不走到」。</li>
 *   <li>放 {@code client} 数组 ⇒ 专服<b>根本不加载这个混入类</b>，连上面那条路径都不存在；
 *       而且 {@code client} 数组是在<b>混入应用期</b>就按端分流，比「注入体里判 dist」更早更硬。</li>
 *   <li>关于「加载期 {@code Dist} 可能还没就绪」的顾虑：本条<b>不依赖</b>任何运行期 dist 判断
 *       （不写 {@code FMLEnvironment.dist} 分支），完全交给配置文件的 {@code client} 数组 ——
 *       所以那个时机问题对本条不存在。这也与本文件旁边两个混入
 *       （{@code ShanhaiWindowTitleMixin} / {@code ShanhaiLoadingOverlayMixin}）同一口径。</li>
 * </ol>
 * <p>目标与原版/Forge 之外的一切无关：本文件常量池里<b>没有任何第三方 mod 的类名</b>。
 */
@Mixin(targets = "net.minecraftforge.fml.ModWorkManager$SyncExecutor")
public abstract class ShanhaiLoaderHeartbeatMixin {

    /**
     * 加载泵每转一圈就调一次 {@link ShanhaiWindowTitle#tickFromLoader()}。
     *
     * <p>形参只有 {@code CallbackInfo}（目标方法无参）—— 少写/多写参数会在应用期报
     * {@code Invalid descriptor}，而那是 {@code require = 0} <b>管不到</b>的
     * （它只管「注入点找不到」）。
     */
    @Inject(method = "driveOne", at = @At("HEAD"), remap = false, require = 0)
    private void shanhai$loaderHeartbeat(CallbackInfo ci) {
        try {
            ShanhaiWindowTitle.tickFromLoader();
        } catch (Throwable ignored) {
            // 装饰性功能：从 Forge 的加载泵里抛出去会打断模组加载，任何异常都在这里吞掉。
        }
    }
}
