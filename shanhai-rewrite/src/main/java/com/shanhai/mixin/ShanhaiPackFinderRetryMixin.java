package com.shanhai.mixin;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.fml.ModLoader;
import net.minecraftforge.fml.event.IModBusEvent;

/**
 * 山海重构 · 把「进世界时那一次 {@code AddPackFindersEvent} 派发」由「失败就重试一次」改成
 * <b>捕获 → 记录 → 放行</b>（catch / log / continue）。
 *
 * <h2>0. 🔴 结论先说：重试是错的（2026-11-21 09:33:29 真实客户端崩溃的教训）</h2>
 * <b>重试别人的事件处理器 = 把半截状态再跑一遍，代价是崩游戏；正确做法是记录并放行。</b>
 * <ul>
 *   <li>重发 {@code AddPackFindersEvent} 不是「再试一次同样的请求」，而是<b>把别人的事件处理器再执行一遍</b>。
 *       别人第一次失败时可能已经改了一半状态（清空过表、删过配方、改过注册表），或者本来就假设「只跑一次」</li>
 *   <li>于是第二次只会更糟，而我们当时把第二次的异常<b>放出去了</b> ⇒ 崩游戏。
 *       历史日志里 {@code [SHANHAI-PACKRETRY]} 一共 <b>0 条</b>：这条重试路径从来没被触发过，第一次触发就把客户端崩了</li>
 *   <li>正确做法：<b>捕获、打一行日志、然后正常返回</b>。退回原版行为 —— 这次进世界用<b>不完整</b>的 pack 列表
 *       （这正是原版 {@code ServerPacksSource} 在没有任何 mod 重试时的行为），缺一个数据包远好过闪退</li>
 * </ul>
 *
 * <h2>1. 为什么仍然是 {@code @Redirect}（包住“发事件”这一句），而不是 {@code @Inject} 重放原逻辑</h2>
 * <ul>
 *   <li>{@code @Inject(at = @At("HEAD"))} <b>根本包不住</b>方法体：HEAD 处还没有任何代码执行，
 *       它拿不到「后面那些代码抛出来的异常」（唯一能“接住”方法体异常的原生手段就是包住那个会抛的调用）。
 *       若真要走 {@code @Inject}，就只能<b>自己把原方法体复制一遍</b>（构造 {@code PackRepository}、
 *       构造两个 {@code RepositorySource}、再造一个事件并 post）——那等于把原版 + Forge patch 的实现
 *       抄进本 mod，任何一次上游微调都会让「重放的那份」与「真正的那份」悄悄分叉。风险远大于收益。</li>
 *   <li>{@code @WrapWithCondition} <b>做不了</b>：它的返回值是 {@code boolean}，只能决定「跳过/不跳过」，
 *       既包不住异常也不改返回语义，本类要的「吞掉异常后继续」它表达不了。</li>
 *   <li>{@code @Redirect} 恰好只替换 {@code invokevirtual ModLoader.postEvent(Event)V} 这一条指令：
 *       不复制任何原逻辑，成功路径的语义（调用次数、返回值、后面的 {@code aload/areturn}）一个字都不改。</li>
 * </ul>
 * <p>不使用 MixinExtras 的 {@code @WrapOperation}（它天生抗 redirect 冲突）的原因：
 * 那会在<b>原版类</b>上再压一个「运行期必须存在某个第三方注入库」的依赖 ——
 * 该库缺失时 {@code require = 0} 救不了（注解类本身加载不到 ⇒ mixin 配置直接报错），
 * 等于给一个「防崩」补丁引入一个新的崩法。原生 {@code @Redirect} 则随 Mixin 一起来，永远在。
 *
 * <h2>2. 语义（严格照这个来，一行都不多）</h2>
 * <pre>
 * 原版那一次 postEvent：
 *   成功 ⇒ 直接返回（与今天完全一样，连日志都不打，不多调任何东西）
 *   抛异常 ⇒ 捕获、打一行日志、<b>正常返回</b>（不重发、不包装、不 sneaky throw）
 * </pre>
 * <p>🔴 <b>绝不 post 第二次</b>：本方法体内 {@code postEvent} <b>只出现一次</b>，任何分支都不回到它。
 *
 * <h2>3. 吞掉之后世界还能进（这是保留的目标，不是副作用）</h2>
 * 本回调是 {@code void}，正常返回后原方法继续执行它后面的 {@code aload/areturn}
 * ⇒ 用<b>已经成功注册的那部分</b> {@code RepositorySource} 建 {@code PackRepository}
 * ⇒ 这次进世界的服务端数据包<b>不完整</b>，但游戏继续。
 * 这正是原版 {@code ServerPacksSource} 在「没有任何 mod 重试」时的行为
 * （原版没有这层 catch，所以它是靠异常直接崩；我们补的只是「不让它崩」，不是「补全数据包」）。
 *
 * <h2>4. 日志（逐字，别改格式）</h2>
 * <pre>
 * 本会话第一次异常：
 * [SHANHAI-PACKRETRY] pack_finder_failed type=&lt;异常类名&gt; msg=&lt;首行，截断 200 字&gt; swallowed=true action=continue_without_pack
 * 本会话第二次及以后：
 * [SHANHAI-PACKRETRY] pack_finder_failed_again count=N      （N = 本会话累计失败次数，从 2 起）
 * </pre>
 * <ul>
 *   <li><b>同一会话只打一次全行</b>：进世界是个高频动作，玩家反复进存档会把这行刷满；后续只留计数。</li>
 *   <li>{@code type} 是 {@link Class#getName()} 的<b>全限定名</b>（例如 {@code net.minecraftforge.fml.ModLoadingException}），
 *       与崩溃报告里的类名对得上，便于 grep。</li>
 *   <li>{@code msg} 只取 {@link Throwable#getMessage()} 的<b>首行</b>并按<b>字符数</b>硬截断到 200
 *       （不追加 {@code ...}，保证 {@code msg} 字段本身 ≤ 200 字）；控制字符替换成空格（纯 ASCII，避免日志编码乱码）；
 *       {@code getMessage()} 返回 {@code null} 时写 {@code msg=null}。</li>
 *   <li>「会话」= 一次游戏进程的生命周期：本类的计数是静态字段，重启游戏即归零。</li>
 * </ul>
 *
 * <h2>5. 为什么写死在 {@code mixins}（公共）数组，而不是 {@code client}</h2>
 * <ul>
 *   <li>目标类 {@code net.minecraft.server.packs.repository.ServerPacksSource} 是<b>两端共用</b>的原版类
 *       （专用服务端进世界时同样走 {@code createPackRepository}），本类是纯公共逻辑、
 *       不引用任何客户端类 ⇒ 放进 {@code mixins} 不会把服务端带崩；</li>
 *   <li>放进 {@code client} 的代价是<b>专用服务端完全没有这层兜底</b>：同一个方法在两端行为不同，
 *       而这条链路失败的模式（别人处理器半截状态）与侧无关；</li>
 *   <li>反过来说，本 mixin 在服务端最坏情况也只是「{@code require = 0} 没命中 ⇒ 什么都不做」。</li>
 * </ul>
 *
 * <h2>6. 🔴 {@code require = 0} + {@code remap = false} + 全程 {@code try/catch(Throwable)}</h2>
 * <ul>
 *   <li>{@code remap = false} + 硬写 SRG 名 {@code m_245786_}、{@code @At} 里硬写 Forge 的原始
 *       owner/描述符：生产运行期（Forge 1.20.1）方法名<b>就是</b> SRG，字面量直接命中，
 *       不经过 refmap —— 「refmap 里少一条映射」这种静默失效路径被整条绕开。
 *       代价是开发环境（named 映射）里挂不上，那是可接受的：验收走的是构建后的 jar。</li>
 *   <li>{@code require = 0} ⇒ 注入点找不到时只记一行 WARN（若 debug 打开），<b>不抛、不打崩启动</b>。
 *       本文件的失败现象因此是「<b>没有 PACKRETRY 日志、崩法不变</b>」，不是起不来。</li>
 *   <li>日志这一步再单独包一层 {@code try/catch(Throwable)}：
 *       <b>兜底自己绝不许制造新异常</b>（例如取 {@code getMessage()} 时对方抛、或打印时 OOM）。</li>
 * </ul>
 *
 * <h2>7. 类名里的 {@code Retry} 是历史遗留（有意保留）</h2>
 * 本类的行为已经不是重试了，但类名 {@code ShanhaiPackFinderRetryMixin} 仍按字符串被
 * {@code shanhai.mixin.json} 的 {@code "mixins"}（公共侧）数组引用（当前是该数组最后一项）；
 * 改一次名要同时动配置，收益为零、风险不小 ⇒ 名字保留，语义以本 javadoc 为准。
 */
@Mixin(ServerPacksSource.class)
public abstract class ShanhaiPackFinderRetryMixin {

    /**
     * 本类日志前缀：一眼能在 latest.log 里抓出来。
     *
     * <p>字段名与下面各方法名之所以都带 {@code shanhai}/{@code SHANHAI_} 前缀：Mixin 会把本类的
     * 字段与方法<b>合并进目标类</b>（原版 {@code ServerPacksSource}），裸名 {@code PREFIX} 之类
     * 会跟别的 mod 往同一个类上挂的成员撞名。
     */
    private static final String SHANHAI_PACKRETRY_PREFIX = "[SHANHAI-PACKRETRY]";

    /** 日志器名：固定不变，方便在 latest.log 里按 logger 过滤。 */
    private static final String SHANHAI_PACKRETRY_LOGGER = "shanhai/pack-retry";

    /** {@code msg} 字段的硬上限（字符数）。见类 javadoc 第 4 节。 */
    private static final int SHANHAI_PACKRETRY_MSG_MAX = 200;

    /**
     * 本进程（= 一次游戏会话）累计「吞掉了几次包注册异常」。
     *
     * <p>用途只有一个：让<b>第一次</b>打完整那行、之后只打 {@code pack_finder_failed_again count=N}，
     * 避免玩家反复进存档把日志刷满。静态字段 ⇒ 重启游戏自动归零。
     *
     * <p>不加锁：{@code createPackRepository} 只在主线程被调用（进世界/开服那一下），
     * 且即便极端情况下多线程撞上，最坏后果只是「多打或少打一行日志」，不影响吞异常这件事本身。
     */
    private static int SHANHAI_PACKRETRY_SWALLOWED_COUNT;

    /**
     * 包住 {@code ServerPacksSource#m_245786_} 里那一次 {@code ModLoader.postEvent(Event)}：
     * 调用<b>一次</b>；成功即返回（一分钱都不多花）；抛异常就记录下来并放行（<b>不重发</b>）。
     *
     * <p>证据（{@code javap -p -c} 反编译 {@code 1.20.1-47.4.16} 的 parchment 映射 jar）：
     * <pre>
     * public static PackRepository createPackRepository(java.nio.file.Path);
     *   …
     *  39: invokestatic  #165  // Method net/minecraftforge/fml/ModLoader.get:()Lnet/minecraftforge/fml/ModLoader;
     *  42: new           #167  // class net/minecraftforge/event/AddPackFindersEvent
     *  63: invokevirtual #192  // Method net/minecraftforge/fml/ModLoader.postEvent:(Lnet/minecraftforge/eventbus/api/Event;)V
     *  66: aload_1 / 67: areturn
     * </pre>
     * <b>整个方法体里 {@code postEvent} 只有偏移 63 这一处</b>，所以不写 {@code ordinal} 也不会误伤；
     * 那个 {@code (Event)V} 描述符是泛型 {@code <T extends Event & IModBusEvent> void postEvent(T)}
     * 擦除到左界 {@code Event} 的结果（同一个 jar 里 {@code fmlcore} 的 {@code ModLoader} 亦为此签名）。
     *
     * <h3>为什么实参写成 {@code (Event & IModBusEvent) event}</h3>
     * 回调的参数类型<b>必须</b>和 {@code @At} 那个调用点的实参类型一致（{@code Event}），
     * 否则 Mixin 的方法描述符检查就过不去；但泛型签名要求 {@code T extends Event & IModBusEvent}，
     * 直接传一个静态类型为 {@code Event} 的变量<b>编译不过</b>（javac 实测报
     * 「无法将 ModLoader 中的方法 postEvent 应用到给定类型」）。交叉类型强转是这两头唯一的交集：
     * 形参仍是 {@code Event}（描述符匹配），实参在编译期被当成 {@code Event & IModBusEvent}（过泛型边界）。
     * 它多出一条 {@code checkcast IModBusEvent}，而那个对象就是本方法上 21 字节前刚
     * {@code new AddPackFindersEvent(...)} 出来的、{@code implements IModBusEvent} 的实例
     * ⇒ 这条 checkcast 不可能抛。
     *
     * @param modLoader 被替换掉的那次调用的接收者（{@code ModLoader.get()} 的返回值）
     * @param event     被派发的那一个事件实例
     */
    @Redirect(
            method = "m_245786_",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraftforge/fml/ModLoader;postEvent(Lnet/minecraftforge/eventbus/api/Event;)V"),
            require = 0,
            remap = false)
    private static void shanhai$postEventSwallow(final ModLoader modLoader, final Event event) {
        try {
            // 🔴 唯一的一次派发。成功 ⇒ 直接走完方法（与改动前逐位相同：不多调、不打日志、返回值语义不变）。
            modLoader.postEvent((Event & IModBusEvent) event);
        } catch (final Throwable t) {
            // 捕获 → 记录 → 放行。绝不重发：重发等于把别人的半截状态再跑一遍。
            shanhai$logSwallowed(t);
        }
    }

    /**
     * 打日志：本会话第一次打完整那行，之后只打计数行。见类 javadoc 第 4 节。
     *
     * <p>整个方法是「尽力而为」：{@code getMessage()} 或日志实现自己抛异常时，结果只能是
     * 「这一行日志没打出来 / 格式退化」，<b>不许</b>变成一个新的崩溃。
     */
    private static void shanhai$logSwallowed(final Throwable t) {
        final int count = ++SHANHAI_PACKRETRY_SWALLOWED_COUNT; // 先计数：后面即使打印失败，账也是对的
        try {
            final Logger log = LoggerFactory.getLogger(SHANHAI_PACKRETRY_LOGGER);
            if (count <= 1) {
                log.warn("{} pack_finder_failed type={} msg={} swallowed=true action=continue_without_pack",
                        SHANHAI_PACKRETRY_PREFIX, shanhai$typeName(t), shanhai$firstLineOf(t));
            } else {
                log.warn("{} pack_finder_failed_again count={}", SHANHAI_PACKRETRY_PREFIX, Integer.valueOf(count));
            }
        } catch (final Throwable ignored) {
            // 见上：兜底不许自己崩。日志丢了只是少一条可观测性，不影响「放行」。
        }
    }

    /** 异常类名（全限定名）；{@code t} 为 {@code null} 时写 {@code null}。 */
    private static String shanhai$typeName(final Throwable t) {
        if (t == null) {
            return "null";
        }
        final String name = t.getClass().getName();
        return (name == null || name.isEmpty()) ? "null" : name;
    }

    /**
     * 取 {@link Throwable#getMessage()} 的<b>首行</b>，按字符数硬截断到 {@value #SHANHAI_PACKRETRY_MSG_MAX}，
     * 控制字符替换成空格（纯 ASCII，避免日志文件编码带来的乱码）。
     *
     * <p>先按换行切、再截断：{@code msg} 必须是一行，否则日志按行读的解析器会把一条异常拆成多条。
     * 截断<b>不</b>追加 {@code ...}，以保证 {@code msg} 字段本身不超过 200 字。
     */
    private static String shanhai$firstLineOf(final Throwable t) {
        if (t == null) {
            return "null";
        }
        final String raw;
        try {
            raw = t.getMessage();
        } catch (final Throwable ignored) {
            return "null"; // 对方 getMessage() 自己抛：宁可写 null，也不许连累吞异常这条路
        }
        if (raw == null) {
            return "null";
        }
        final int n = Math.min(raw.length(), SHANHAI_PACKRETRY_MSG_MAX);
        final StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            final char c = raw.charAt(i);
            if (c == '\n' || c == '\r') {
                break; // 只要首行
            }
            sb.append((c < 0x20 || c == 0x7F) ? ' ' : c);
        }
        return sb.toString();
    }
}
