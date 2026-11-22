package com.shanhai.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.shanhai.client.ShanhaiWindowTitle;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.LoadingOverlay;

/**
 * 山海重构 · <b>客户端 {@code LoadingOverlay}（加载遮罩）混入</b>：<b>加载页每画一帧就把窗口标题重拼一次</b>。
 *
 * <h2>0. 为什么（用户 2026-11-20 报的现象 + 日志实证）</h2>
 * 用户原话：「他这个加载上面的时间有的时候会卡住不动，然后版本号又变成不可用了」。
 * <p>日志实证（{@code latest.log}，2026-11-20 那局）：
 * <pre>
 * 484 行  22:08:20.229  窗口出生标题已给出… 版本号:不可用   加载时间:0小时0分钟27秒 (mode=startup)
 *         —— 中间 115 秒：本类（ShanhaiWindowTitle）一行日志都没有 ——
 * 3152 行 22:10:15.873  窗口标题已设置… (mode=initial)
 * </pre>
 * 即：{@code Minecraft#createTitle} 只给了<b>一个快照</b>，而「窗口出生 → 主循环开始」这一段
 * {@code ClientTickEvent} / {@code RenderTickEvent} <b>一个都没派发</b> ⇒ 标题在整段加载页里
 * <b>一动不动</b>（时间不走、转子不转、版本号停在快照上的值）。
 * <p>主循环一起来，加载遮罩每帧都会画 ⇒ 挂在它的 {@code render} 上就等于「加载页每帧刷新」。
 *
 * <h2>1. 注入到哪个方法（{@code javap -p} 取证，不是印象）</h2>
 * 取证对象 = {@code libraries/net/minecraft/client/1.20.1-20230612.114412/client-…-srg.jar}：
 * <pre>
 * net.minecraft.client.gui.screens.LoadingOverlay
 *   public void m_88315_(net.minecraft.client.gui.GuiGraphics, int, int, float);   ← 就是它
 * net.minecraft.client.gui.components.Renderable
 *   public abstract void m_88315_(net.minecraft.client.gui.GuiGraphics, int, int, float);
 * </pre>
 * ⇒ 类 = {@code net.minecraft.client.gui.screens.LoadingOverlay}，
 * 方法 = {@code m_88315_}，描述符 = {@code (Lnet/minecraft/client/gui/GuiGraphics;IIF)V}。
 * <p>「{@code m_88315_} 就是官名 {@code render}」的旁证：本机实例 {@code mods/} 里另有 4 份 refmap
 * 把 {@code render(Lnet/minecraft/client/gui/GuiGraphics;IIF)V} 映射到 {@code m_88315_}
 * （分别挂在 {@code LevelLoadingScreen} / {@code AbstractContainerScreen} / {@code DeathScreen} /
 * {@code AEBaseScreen} 这些<b>原版类</b>上）—— SRG 名只跟「名字 + 签名」有关，
 * 而 {@code net.minecraft.client.gui.components.Renderable} 里这个签名就是这个方法。
 *
 * <h2>2. 为什么是 {@code @At("HEAD")} 而不是 {@code RETURN}</h2>
 * <ol>
 *   <li><b>不依赖方法跑到底</b>：遮罩的 {@code render} 里只要抛了异常（别的 mod 在里面混入、
 *       资源没就绪……），{@code RETURN} 就<b>不会</b>执行 ⇒ 恰好是「最需要把标题刷一下」的时候漏掉；
 *       {@code HEAD} 在最前面，先刷标题再让遮罩自己画。</li>
 *   <li><b>目标方法有多个返回点</b>（{@code javap -c} 里能看到早退分支）。{@code @At("RETURN")}
 *       要求给<b>每一个</b> ARETURN 都插一份注入点，命中面越大越容易被别人的注入改坏；
 *       {@code HEAD} 只有一个点。</li>
 *   <li><b>时序上也更对</b>：{@code refresh()} 只做「拼串 + {@code glfwSetWindowTitle}」，
 *       不碰 GL 状态、不读绘制目标，放在遮罩动 GL 之前调用，不会和遮罩/光影的渲染状态打架。</li>
 * </ol>
 *
 * <h2>3. 🔴 {@code require = 0} + {@code remap = false} + 全程 {@code try/catch}</h2>
 * <p><b>{@code remap = false}</b>：生产环境方法名<b>就是 SRG</b>，直接命中，绕开「refmap 里没这条映射」
 * 这条静默失效路径。代价是<b>开发环境</b>（named 映射）里这条注入挂不上 —— 可以接受
 * （开发环境本来就有 tick/render 刷新兜着，用户验收走构建后的 jar）。
 * <p><b>{@code require = 0}</b>：目标方法被别的 mod 改掉 / 签名漂了 ⇒ 注入点找不到时
 * <b>只记一行 WARN，不抛、不打崩启动</b>。失败现象是「加载页标题仍冻结但不崩」，
 * 判据是 {@code latest.log} 里<b>没有</b> {@code 加载页刷新已接管（loading_overlay）} 这一行。
 * <p><b>全程 {@code try/catch (Throwable)}</b>：本功能纯装饰，最坏结果只能是「标题不更新」，
 * 绝不许把加载页搞崩。
 *
 * <h2>4. 🔴 目标必须是原版类，且只列 {@code client}</h2>
 * 本类只出现在 {@code shanhai.mixin.json} 的 {@code client} 数组里（并进已有那份配置，
 * 不新建第 N 份）⇒ <b>专服不加载</b>；混入目标 {@code LoadingOverlay} 与参数类型 {@code GuiGraphics}
 * 都是原版类，<b>常量池里没有任何第三方 mod 的类名</b>。
 */
@Mixin(LoadingOverlay.class)
public abstract class ShanhaiLoadingOverlayMixin {

    /**
     * 遮罩每渲染一帧 ⇒ 让 {@link ShanhaiWindowTitle} 重拼并写入一次窗口标题。
     *
     * <p>形参必须与目标方法一一对应（{@code GuiGraphics, int, int, float}）再加 {@code CallbackInfo}：
     * {@code @Inject} 的回调签名要和目标方法匹配，少写一个参数会在应用期报
     * {@code Invalid descriptor} —— 而那是 {@code require = 0} <b>管不到</b>的（它只管「注入点找不到」）。
     */
    @Inject(method = "m_88315_", at = @At("HEAD"), remap = false, require = 0)
    private void shanhai$refreshTitleOnLoadingFrame(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick,
                                                    CallbackInfo ci) {
        try {
            ShanhaiWindowTitle.tickFromOverlay();
        } catch (Throwable ignored) {
            // 装饰性功能：从加载遮罩里抛出去会毁掉整个加载页，任何异常都在这里吞掉。
        }
    }
}
