package com.shanhai.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.shanhai.client.ShanhaiWindowTitle;

import net.minecraft.client.Minecraft;

/**
 * 山海重构 · <b>客户端 {@code Minecraft#createTitle} 混入</b>：让窗口<b>一出生</b>就带我们的标题。
 *
 * <h2>0. 为什么（用户 2026-11-20 实测原话）</h2>
 * 「首先它加载的时候标题没改，只有最后才改了」。
 * <p>根因是<b>机制</b>、不是偶发：窗口标题是 {@code Minecraft.<init>} 创建窗口时调
 * {@code createTitle()}（SRG {@code m_91270_}）当场定下的，之后由 {@code updateTitle}
 * （{@code m_91341_}）只在选项变化/对局域网开放等少数时机再刷。
 * 而 {@link ShanhaiWindowTitle} 原来只挂了 {@code ClientTickEvent} / {@code RenderTickEvent.END}
 * —— <b>这两条都要等游戏主循环开始才会派发</b> ⇒ 整段「Mojang 红屏 + 各种 loading」期间
 * 窗口标题一直是原版那个 {@code Minecraft* Forge 1.20.1}，直到第一个 tick 才被我们改写。
 *
 * <h2>1. 为什么是 {@code @At("RETURN")} + {@code setReturnValue}</h2>
 * 不去碰 {@code Window}、也不去碰 {@code updateTitle}：只要<b>返回值</b>换成我们的串，
 * 所有「原版自己写标题」的调用点（构造器创建窗口、updateTitle、存档载入过程）就全部自动跟着变。
 * <p>代价只有一处：只改了「窗口出生那一帧」之后的覆盖工作仍然由
 * {@link ShanhaiWindowTitle} 的 tick/render 刷新负责（<b>原有逻辑一行没删</b>，两条链路分工不重叠）。
 *
 * <h2>2. 🔴 {@code remap = false} + 硬写 SRG 名 {@code m_91270_}</h2>
 * 生产环境（Forge 1.20.1 运行期）方法名<b>就是 SRG</b>，字面量直接命中，不经过 refmap
 * —— 这样「refmap 里没生成这条映射」这种静默失效路径被整条绕开（那正是本 bug 的翻版：
 * 名字对不上 ⇒ 注入没发生 ⇒ 标题照旧，而且没人报错）。
 * <p>副作用是<b>开发环境</b>（named/Mojmap 映射，方法名 {@code createTitle}）里这条注入挂不上 ——
 * 那是可以接受的：开发环境本来就有 tick/render 刷新兜着，且用户验收走的是构建后的 jar。
 *
 * <h2>3. 🔴 {@code require = 0}：拿不到就退回原版标题，绝不许让客户端起不来</h2>
 * 本类站在<b>客户端启动链路最早期</b>（{@code Minecraft.<init>} 里）。方法名漂了、反射不到、
 * 我们的拼串炸了 —— 任何一种情况的结果都必须是「标题仍是原版」而不是「客户端崩掉」：
 * <ul>
 *   <li>{@code require = 0} ⇒ 注入点找不到时只记一行 WARN，不抛、不打崩启动；</li>
 *   <li>方法体整段 {@code try/catch (Throwable)}；</li>
 *   <li>{@link ShanhaiWindowTitle#titleForStartup()} 拿不到信息时返回 {@code null}
 *       ⇒ 这里<b>不</b>调 {@code setReturnValue}，原版字符串照常返回。</li>
 * </ul>
 */
@Mixin(Minecraft.class)
public abstract class ShanhaiWindowTitleMixin {

    /**
     * 把 {@code Minecraft#createTitle()} 的返回值换成我们的串（拿不到就原样返回）。
     *
     * <p>{@code m_91270_} = 1.20.1 的官名 {@code Minecraft#createTitle}，
     * 描述符 {@code ()Ljava/lang/String;}（本工程 {@code ShanhaiWindowTitle} 类注释里有
     * {@code javap} 取证：{@code m_91341_} → {@code m_91270_} → {@code Window.m_85422_}）。
     * 只写方法名不写描述符 ⇒ 目标类里同名方法唯一时即可命中，签名漂了则由 {@code require = 0} 兜。
     *
     * <p>{@code cancellable = true} 是 {@code CallbackInfoReturnable#setReturnValue} 的硬要求
     * （不可取消的 callback 调它必抛 {@code IllegalStateException}）。
     */
    @Inject(method = "m_91270_", at = @At("RETURN"), cancellable = true, remap = false, require = 0)
    private void shanhai$createTitle(CallbackInfoReturnable<String> cir) {
        try {
            final String title = ShanhaiWindowTitle.titleForStartup();
            if (title != null && !title.isEmpty()) {
                cir.setReturnValue(title);
            }
        } catch (Throwable ignored) {
            // 纯装饰性功能：任何异常都不许往外观冒，最坏情况 = 窗口标题保持原版。
        }
    }
}
