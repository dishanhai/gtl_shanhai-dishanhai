package com.shanhai.common.recipe;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.shanhai.ShanhaiMod;
import com.shanhai.item.ShanhaiItems;
import com.shanhai.registry.ShanhaiRegistration;
import com.tterrag.registrate.util.entry.ItemEntry;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 山海重构 · <b>「私货配方统计」横幅</b>（原版同名功能的自研重制版）。
 *
 * <h2>0. 这个类是干什么的</h2>
 * 复刻老版私货那条「配方统计横幅」，但<b>全部由本工程自研</b>，<b>不复用老版任何类</b>：
 * <ul>
 *   <li><b>配方加载完成</b> → 往日志打<b>一行机器可判的统计</b>（见 §2 的固定格式）；</li>
 *   <li><b>玩家登录后 160 tick</b> → 给该玩家发多行聊天横幅（见 §3）。</li>
 * </ul>
 *
 * <h2>1. 为什么是「Java 侧累加器」而不是「KJS 直接报数」</h2>
 * 老版的计数链是：<b>KJS 逐条 {@code recordRecipe(type, ok, id, msg)} 上报</b>
 * → Java {@code AtomicLong} 累加（{@code RECIPE_TOTAL/SUCCESS/DISABLED/FAILED}）→ 横幅取 {@code .get()}。
 * 本类照这条链重做，理由不是「像老版」，而是<b>它本来就更对</b>：
 * <ol>
 *   <li><b>登录横幅晚于配方加载</b>：横幅在玩家登录后 160 tick 才发，那时 KJS 的 recipes 回调早就结束了
 *       ⇒ 数字必须有<b>一个活在 Java 侧的载体</b>，否则横幅无从取值。</li>
 *   <li><b>单一数据源</b>：数只在一处（{@link #addResult(boolean)}）累加、只在一处（{@link #reportSummary(String)}）
 *       读出。工程里已有的教训是「两处都写会互相掩盖故障」，所以本类<b>不接受</b>「KJS 直接传总数」
 *       那种第二条写入通道。</li>
 * </ol>
 *
 * <h2>2. 🔴 数字口径声明（机器判定行的字段本身就是口径）</h2>
 * 配方加载完成打的那一行，格式<b>固定</b>：
 * <pre>
 * [SHANHAI-SPEC] recipe_stats scope=&lt;scope&gt; total=&lt;n&gt; success=&lt;n&gt; failed=&lt;n&gt;
 * </pre>
 * 口径三条，逐条写死：
 * <ol>
 *   <li><b>谁数的</b>：<b>本类的 {@link AtomicLong} 累加器</b>（Java 侧）。数据来源是 <b>KJS 逐条上报</b>
 *       —— 即 {@code shanhai_test_recipes.js} 在自己的循环里对每条配方调一次 {@link #addResult(boolean)}。
 *       <b>不是</b> KubeJS 的 {@code Added N recipes}。</li>
 *   <li><b>数的范围</b>：<b>只有调了 {@link #addResult(boolean)} 的那些配方</b>。当前唯一调用方是
 *       {@code kubejs/server_scripts/shanhai_test_recipes.js}（⚠️ 2026-09-27 更正：原文写「16 条占位测试配方」，
 *       实测是 <b>39 条</b> —— 见运行日志 {@code scope=kjs_shanhai_test_recipes total=39}）；
 *       另有 {@code shanhai_pf_recipes.js} 于 2026-09-27 接入，用<b>独立 scope {@code shanhai_pf}</b>
 *       上报（它先于测试脚本执行，并在自己那批之前调 {@link #reset()}，因此两批数字互不污染）；
 *       宿主脚本（{@code gtceu.js} 等 15 个）<b>不上报</b>，因此<b>不在</b>这个数字里。
 *       ⇒ 这个数字<b>不等于</b>整合包的配方总数，读的时候必须带着 {@code scope=} 一起读。</li>
 *   <li><b>成功/失败判据</b>：{@code success} = 「本脚本调用 GTCEu recipe builder 没有抛异常」的条数，
 *       {@code failed} = 抛了异常的条数。<b>它不等于</b>「RecipeManager 最终接受了的条数」——
 *       后者要看 KubeJS 自己后面打的那行 {@code Added N recipes ..., with M failed recipes}（那行覆盖全部脚本）。
 *       ⚠️ 本行日志出现得比 {@code Added N recipes} 那行<b>早</b>（KJS 先收集、后应用），这是老版也有的时序。</li>
 * </ol>
 * <p>
 * ⚠️ <b>没有 {@code skipped} 字段</b>：老版的 {@code disabled}/{@code errors} 对应它自己的
 * 「配方加载已禁用」开关（见 §5 留档常量 {@code 配方加载已禁用，已跳过}）。<b>重制版目前没有任何
 * 「禁用/跳过配方」的概念</b> —— 工程里搜不到对应的注册类、配置项或 KJS 入口。把它写成一个恒为 0
 * 的字段就是<b>印一个假数字</b>（本工程红线：宁可缺，不可假），所以本类<b>只数 total/success/failed 三个</b>，
 * 等真有「跳过」通道时再加第四个字段（那时字段集合会变，解析方要同步改 —— 这是有意的、可见的变更）。
 *
 * <h2>3. 横幅为什么用 {@code TickEvent} 自己数 tick，而不是 {@code server.scheduleInTicks(160, …)}</h2>
 * <b>本工程没有 {@code scheduleInTicks} 可用</b>，这是查证过的，不是推测：
 * <ul>
 *   <li>老版那句 {@code event.server.scheduleInTicks(160, …)} 里的 {@code event} 是 <b>KubeJS 的事件</b>，
 *       {@code scheduleInTicks} 来自 <b>KubeJS</b> 的 {@code dev.latvian.mods.kubejs.core.MinecraftServerKJS}
 *       （取证：{@code jar tf kubejs-forge-2001.6.5-build.26.jar | grep MinecraftServerKJS} 命中）。
 *       ⇒ 它是 KJS 侧接口 mixin，<b>不是 Forge/原版 API</b>。</li>
 *   <li>原版 {@code MinecraftServer} 上<b>没有</b> {@code scheduleInTicks}
 *       （取证：{@code javap -p net.minecraft.server.MinecraftServer} 的方法表里无此方法）。</li>
 *   <li>用 {@code server.tell(new TickTask(server.getTickCount() + 160, task))} 造延迟<b>也是错的</b>：
 *       {@code MinecraftServer.shouldRun(TickTask)} 的字节码是
 *       {@code return task.getTick() + 3 < this.tickCount || this.haveTime();}
 *       ⇒ 未来 160 tick 的任务因为 {@code haveTime()} 为真而<b>立刻</b>被 poll 执行，{@code tick} 字段
 *       不是延迟机制。同理 {@code server.execute(...)} 会被 {@code wrapRunnable} 包成
 *       {@code tickCount} 当刻的任务，也不延迟。</li>
 * </ul>
 * ⇒ 结论：在 FORGE 总线上订阅 {@link TickEvent.ServerTickEvent}（{@code Phase.END}）**自己数 160 tick**。
 * 这是<b>行为可验证</b>的写法：延迟长度就是数出来的 tick 数，没有依赖任何需要猜语义的内部机制。
 * 空表时第一行就 return，稳态开销为 0。
 *
 * <h2>4. 🔴 老版横幅原文（留档，不是本类要输出的内容）</h2>
 * <b>老版由 {@code com.dishanhai.gt_shanhai.api.DShanhaiRecipeEngine} 打出</b>（Java 侧）。
 * 下面是<b>用户 2026-09 截图里的逐字原文</b>（本类的<b>版式依据</b>）：
 * <pre>
 * ======= 山海 私货配方统计 =======
 * [OK]配方库 加载完成!
 * ✅ 成功加载: 560 个配方
 * ⚠️ 已被禁用配方数量: 7
 * 😊 配方库检测无报错 祝领航员航行无阻!
 * 🔷 当前神人私货版本:v2.7.4fix(日志系统版本2.7.3)
 * 🔷 当前gt_shanhai模组版本:v1.0.0
 * 🔷 当前API总控系统版本为2.9.1
 * 欢迎来到GTL寰宇联合重工巨企
 * 此成功信息回执由JAVA侧: DShanhaiRecipeEngine 生成
 * 老大我们这样熬夜写私货心脏真的不会自己先休息吗
 * ==============================
 * </pre>
 * <p>
 * 被用户点名「已经没有了的功能描述可以不写」⇒ <b>本类刻意不输出的 3 条</b>：
 * <ol>
 *   <li>{@code 🔷 当前API总控系统版本为2.9.1}：重制版<b>没有任何 API 版本类/常量</b>；老版里它也只是
 *       KJS 侧的一个字面量，不驱动任何逻辑。</li>
 *   <li>{@code (日志系统版本2.7.3)} 那个括号：全工程搜「日志系统」无任何注册/类/配置，只是版本串里的文字。</li>
 *   <li>{@code 此成功信息回执由JAVA侧: DShanhaiRecipeEngine 生成} <b>的类名</b>：重制版是自研，
 *       不该署老版的类名 ⇒ 改成 {@link #RECEIPT_CLASS_NAME}（= 本类名）。这一行本身<b>保留</b>，只换署名。</li>
 * </ol>
 *
 * <h4>4.1 ⚠️ 顺带纠一处「计划外的偏差」，是查出来的，不是我想改</h4>
 * <p>
 * 🔴 <b>【本节原文保留，但它已被证伪/作废，不要再照着做】</b>（本工程规矩：作废项保留原文，不删）：
 * </p>
 * <blockquote>
 * 本类<b>不使用</b>老版横幅的样式前缀 {@code &$?body_golden-} / {@code &$*body_moss-} /
 * {@code &$body_aurora-} / {@code &$body_silver-}：那套前缀由 <b>{@code DShanhaiTextUtil}</b> 解析，
 * 重制版<b>没有这个类</b>，照抄只会把 {@code &$body_moss-} 原样显示出来。
 * </blockquote>
 * <p>
 * 🔴 <b>【作废原因 · 2026 本轮】</b>上面那句「照抄只会原样显示」<b>只在"没有客户端 mixin"这个前提下成立</b>，
 * 而那个前提<b>已经不存在了</b>：本轮新增了客户端 {@code Font} 混入
 * （{@code com.shanhai.mixin.ShanhaiFontStyleMixin} + {@code com.shanhai.client.text.ShanhaiFontStyleRenderer}，
 * 走 {@code shanhai.mixin.json} 的 {@code client} 数组），它接管了
 * {@code Font.drawInBatch(FormattedCharSequence,…)} 这个<b>所有文字渲染的唯一收口</b>。
 * ⇒ 现在文案是「服务端发裸字面量、客户端逐帧算色」，<b>与原版的机制完全一致</b>。
 * ⇒ 用户裁决：「横幅 6 行加回 {@code &$…-} 前缀」。
 * </p>
 * <p>
 * ⚠️ <b>仍未变的边界</b>：客户端<b>没有</b>本 mod 的时候（或混入没生效的时候），
 * 这 6 行会显示成带 {@code &$…-} 的裸码。本 mod 双端同 jar，正常玩法下客户端必然带着它
 * （方案 md §7 风险 4 已经记过这一条）。
 * </p>
 * <p>
 * <b>留档时发现一处与任务书转录不一致，如实记下</b>（取证命令见 §6）：把
 * {@code originals/analysis/gtsh_refs/x/com/dishanhai/gt_shanhai/api/DShanhaiRecipeEngine.class} 的字符串常量
 * 用 {@code javap -J-Dfile.encoding=UTF-8 -c -p} 取出来，实际是：
 * <pre>
 * &amp;$?body_golden-============= 山海私货配方统计 =============     &lt;- 两侧各 13 个 '='
 * &amp;$*body_moss-[OK] 配方库加载完成!
 * §a😋 配方库检测无报错 祝领航员航行无阻!                          &lt;- 是 😋 不是 😊
 * &amp;$body_aurora-欢迎来到GTL寰宇联合重工巨企
 * &amp;$body_moss-此成功信息回执由JAVA侧: DShanhaiRecipeEngine 生成
 * &amp;$body_silver-老大我们这样熬夜写私货心脏真的不会自己先休息吗
 * &amp;$?body_golden-===========================================     &lt;- 43 个 '='
 * </pre>
 * 两个「来源」对不上：用户截图里是 {@code ==========} 与 {@code 😊}，而 {@code originals/analysis/gtsh_refs}
 * 里那份是 13/43 个 {@code =} 与 {@code 😋}；且那份里<b>根本没有</b> {@code ✅ 成功加载} /
 * {@code ⚠️ 已被禁用配方数量} / {@code 🔷 当前…版本} 这几行（它们来自另一处/另一版本）。
 * <b>本类按任务书给出的用户截图原文输出</b>（分隔线 {@link #BAR_TOP} / {@link #BAR_BOTTOM} 提成常量，
 * 要改一个 token 就行），并把差异记在这里 —— 因为横幅只能人眼看，机器验不了，宁可留一处可查的分歧。
 * </p>
 * <p>
 * ⚠️ 本轮<b>只加前缀、没改正文</b>：{@link #BAR_TOP}/{@link #BAR_BOTTOM} 的 {@code '='} 个数与
 * 「山海 私货配方统计」中间那个空格<b>保持用户截图版</b>（7/30 个 {@code '='}），
 * <b>没有</b>换成上面那份 13/43 个。理由：用户本轮要的是「加回前缀」，换分隔线长度是另一件事，
 * 且这两个值在本类里本来就有单独的留档分歧。要切过去只需改这两个常量。
 * </p>
 *
 * <h2>5. 触发时机（照原版）</h2>
 * <ul>
 *   <li><b>日志行</b>：由 KJS 在 {@code ServerEvents.recipes} 回调<b>末尾</b>调
 *       {@link #reportSummary(String)} 触发（老版同款：配方加载完成那一刻只往日志打一行）。</li>
 *   <li><b>横幅</b>：{@link PlayerEvent.PlayerLoggedInEvent} → 延迟 {@link #BANNER_DELAY_TICKS} tick
 *       （= 160，<b>照原版</b>）→ {@link #sendBannerTo(ServerPlayer)}。
 *       ⇒ 🔴 <b>无头专服永远看不到横幅</b>（没有玩家登录），横幅只能由用户进游戏验。</li>
 * </ul>
 * 关于「用户说的是『加载配方的时候』」：原版给玩家看的那条<b>确实是登录后 160 tick</b>，
 * 而「配方加载完成」那一刻原版只打日志 ⇒ <b>两处都做</b>：加载完成打日志、玩家登录打横幅。
 *
 * <h2>6. 本类依赖的 API 逐条取证（都不是推测）</h2>
 * <pre>
 * javap -cp &lt;forge-1.20.1-47.4.16_mapped_parchment&gt; -p net.minecraft.server.MinecraftServer
 *   → 有 getTickCount()/tell()，**没有** scheduleInTicks
 * javap ... -c -p net.minecraft.server.MinecraftServer   （shouldRun）
 *   → getTick()+3 &lt; tickCount || haveTime()
 * javap ... -p 'net.minecraftforge.event.entity.player.PlayerEvent$PlayerLoggedInEvent'
 *   → 只有 getEntity()（**没有** getServer()）
 * javap ... -p 'net.minecraftforge.event.TickEvent$ServerTickEvent'
 *   → getServer()、父类 public final Phase phase、Phase.END 存在
 * javap ... -p net.minecraft.server.level.ServerPlayer        → sendSystemMessage(Component)
 * javap ... -p net.minecraft.server.players.PlayerList        → getPlayer(UUID)
 * javap ... -p net.minecraft.world.entity.Entity              → getServer()
 * javap -v -p 'net.minecraftforge.fml.common.Mod$EventBusSubscriber'（javafmllanguage jar）
 *   → value() 的 AnnotationDefault = {Dist.CLIENT, Dist.DEDICATED_SERVER}  ⇒ 不写 value 即两侧都注册
 * javap -cp &lt;fmlcore jar&gt; -p net.minecraftforge.fml.ModList   → get()/getModContainerById(String)
 * javap -cp &lt;forgespi jar&gt; -p net.minecraftforge.forgespi.language.IModInfo
 *   → getVersion() 返回 org.apache.maven.artifact.versioning.ArtifactVersion
 * </pre>
 */
@Mod.EventBusSubscriber(modid = ShanhaiMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ShanhaiRecipeStats {

    /** 本类所有日志行的前缀，沿用本工程既有前缀（{@code ModuleSlotDiagnostics.PREFIX} 同款）。 */
    public static final String LOG_PREFIX = "[SHANHAI-SPEC]";

    /** 机器判定行的固定键名。grep 这一个词就能定位到统计行。 */
    public static final String STATS_LOG_KEY = "recipe_stats";

    /**
     * 本 scope 的上报方：{@code kubejs/server_scripts/shanhai_test_recipes.js}。
     * ⚠️ 2026-09-27 更正：原文写「16 条占位测试配方」，<b>实测是 39 条</b>
     * （运行日志 {@code scope=kjs_shanhai_test_recipes total=39 success=39 failed=0}）。
     * <p>另有 {@code shanhai_pf_recipes.js} 于 2026-09-27 接入，用<b>另一个 scope</b>
     * （见 {@code scope=shanhai_pf}），两者数字互不污染。
     *
     * <p>这个字符串会被打进日志行的 {@code scope=} 字段，所以它<b>就是口径的一半</b>——
     * 看到 {@code scope=kjs_shanhai_test_recipes} 就该知道：数字只覆盖那一个脚本报上来的配方。
     */
    public static final String SCOPE_KJS_TEST = "kjs_shanhai_test_recipes";

    /** 横幅里「回执由谁生成」的署名。<b>是本类自己的名字，不是老版的 {@code DShanhaiRecipeEngine}。</b> */
    public static final String RECEIPT_CLASS_NAME = "ShanhaiRecipeStats";

    // ------------------------------------------------------------------ 🆕 两条按配方类型现数的横幅行（2026-10-01）

    /**
     * 「原初物质定型」的配方类型 id（= 该类型 lang 键 {@code gtceu.primordial_matter_forming} 去掉 {@code gtceu.}）。
     *
     * <p>取证：那 2457 条配方（2026-10-01 起在
     * {@code kubejs\server_scripts\[server_scripts]shanhai_primordial_forming.js}，
     * 原先在 jar 内 {@code data/shanhai/recipes/primordial_forming/}）的配方类型逐字是
     * {@code "gtceu:primordial_matter_forming"}；类型本身在
     * {@link ShanhaiRecipeTypes#PRIMORDIAL_MATTER_FORMING} 注册。
     */
    public static final String TYPE_ID_FORMING = "gtceu:primordial_matter_forming";

    /**
     * 「原初激光蚀刻」的配方类型 id。
     *
     * <p>取证：本工程 {@code assets/shanhai/lang/zh_cn.json} 的 {@code gtceu.primordial_laser_etching}
     * = 「原初激光蚀刻」；配方由<b>实例侧</b> {@code kubejs\server_scripts\shanhai_lens_goodbye.js}
     * 注册（283 条，见 {@code handoff\outbound\原初激光蚀刻与蜂群铸造.md}）。
     */
    public static final String TYPE_ID_LASER_ETCHING = "gtceu:primordial_laser_etching";

    /** 横幅上这两行的中文标签（与用户点单的用词逐字一致）。 */
    public static final String TYPE_LABEL_FORMING = "原初物质定型";

    /** 横幅上这两行的中文标签。 */
    public static final String TYPE_LABEL_LASER = "原初激光蚀刻";

    /** 玩家登录后等多少 tick 再发横幅 —— <b>160，照原版</b>（8 秒）。 */
    public static final int BANNER_DELAY_TICKS = 160;

    /**
     * 横幅首行。老版是 {@code &$?body_golden-============= 山海私货配方统计 =============}（13/13 个 '='）。
     *
     * <p>🔴 本轮 <b>样式前缀 {@code &$?body_golden-} 已加回</b>（见 {@link #PREFIX_GLITCH_GOLDEN} 与类注释 §4.1）。
     * 分隔线数量仍按用户截图（7 个 '='）—— 本轮只加前缀、不改正文。
     */
    public static final String BAR_TOP = "======= 山海 私货配方统计 =======";

    /** 横幅末行（用户截图：30 个 '='；老版常量里那份是 43 个 '='，见类注释 §4.1）。 */
    public static final String BAR_BOTTOM = "==============================";

    // ------------------------------------------------------------------ &$…- 样式前缀

    /**
     * 横幅 6 行的样式前缀 —— <b>照原版原文，一个字都没改</b>。
     *
     * <p>语法 {@code &<效果字符>$<样式名>-<正文>}；由客户端
     * {@code com.shanhai.mixin.ShanhaiFontStyleMixin} 在 {@code Font.drawInBatch} 里解析并逐帧算色
     * ⇒ 服务端只管发<b>裸字面量</b>（这里发的就是它），<b>原版也是这么干的</b>
     * （取证：{@code DShanhaiRecipeEngine.java:408-448} 全是裸 {@code Component.literal}）。
     *
     * <table border="1">
     *   <tr><th>常量</th><th>前缀</th><th>效果</th><th>用在哪一行</th></tr>
     *   <tr><td>{@link #PREFIX_GLITCH_GOLDEN}</td><td>{@code &$?body_golden-}</td>
     *       <td>{@code ?}=glitch（偶发掺青/红 + 亚像素位移）＋ 金色板 200ms/格</td>
     *       <td>首行、末行</td></tr>
     *   <tr><td>{@link #PREFIX_FLOATX_MOSS}</td><td>{@code &$*body_moss-}</td>
     *       <td>{@code *}=floatX（左右 ±2px 波浪）＋ 苔藓色板</td><td>{@code [OK]配方库 加载完成!}</td></tr>
     *   <tr><td>{@link #PREFIX_AURORA}</td><td>{@code &$body_aurora-}</td>
     *       <td>极光色板</td><td>{@code 欢迎来到GTL寰宇联合重工巨企}</td></tr>
     *   <tr><td>{@link #PREFIX_MOSS}</td><td>{@code &$body_moss-}</td>
     *       <td>苔藓色板</td><td>回执行</td></tr>
     *   <tr><td>{@link #PREFIX_SILVER}</td><td>{@code &$body_silver-}</td>
     *       <td>银白色板（白↔灰往返）</td><td>末句</td></tr>
     * </table>
     * <p>
     * 🔴 <b>{@code ?} 与 {@code *} 是本工程实现了的<b>全部</b>效果字符</b>
     * （清单与「没做的那 9 个怎么退化」见 {@code ShanhaiTextParser} 类注释 §2/§3）；
     * 其余 9 行正文（{@code ✅ 成功加载} / {@code 😊 配方库检测无报错} / {@code 🔷 当前版本} 等）
     * <b>刻意不加前缀</b>——理由见 {@link #bannerLines()} 的注释。
     */
    public static final String PREFIX_GLITCH_GOLDEN = "&$?body_golden-";

    /** {@code &$*body_moss-}：苔藓色板 + floatX 左右波浪。 */
    public static final String PREFIX_FLOATX_MOSS = "&$*body_moss-";

    /** {@code &$body_aurora-}：极光色板。 */
    public static final String PREFIX_AURORA = "&$body_aurora-";

    /** {@code &$body_moss-}：苔藓色板。 */
    public static final String PREFIX_MOSS = "&$body_moss-";

    /** {@code &$body_silver-}：银白色板。 */
    public static final String PREFIX_SILVER = "&$body_silver-";

    /**
     * 🔴 2026-10-01（用户点单 ④）<b>横幅「中间那几行」的样式前缀</b>。
     *
     * <p>用户原话（逐字）：「另外，我们配方显示，<b>如图1，中间的字是白的</b>，也可以给他们装上美化
     * （<b>改成彩色的，但是不要晃动</b>）」。
     * 他给的图（`attachments\v1\objects\67\673a4ca2…`，941×436）就是<b>这条登录横幅</b>：
     * 首行/次行/末三行都是流动色，而中间那几行（物质解构 / 正式配方 / 测试配方 / 总数 / 无报错 / 版本）
     * <b>全是白的</b> —— 本常量就是给它们上色的那一个开关。
     *
     * <h4>为什么选 {@code body_golden}</h4>
     * <ul>
     *   <li><b>没有效果字符</b>（既不是 {@code ?} glitch 也不是 {@code *} floatX）
     *       ⇒ 用户那句「<b>不要晃动</b>」是硬要求，这个前缀里一个效果字符都没有
     *       ⇒ <b>任意两帧里字的位置完全一致</b>（只有颜色在流动）；</li>
     *   <li>它与横幅首尾那条分隔线是同一族色板（11 色、正文慢速 200ms/格），横幅整体更成一个色系；</li>
     *   <li>色板本身在 {@link com.shanhai.common.text.ShanhaiTextPalette} 的注册表里
     *       （{@code body_golden}），不是拼出来的名字 ⇒ 不会退化成白字。</li>
     * </ul>
     * ⚠️ <b>只给「中间那几行」用</b>；{@code ⚠️ 失败} 那一行<b>刻意不上色</b>
     * —— 一条报错被染成流光溢彩会传递错误信号（既有设计，本轮不动）。
     */
    public static final String PREFIX_STAT = "&$body_golden-";

    /**
     * 值取不到时的显示串（构建日期 / 版本号通用）。
     * <b>宁可显示「不可用」，也不显示一个编出来的值。</b>
     */
    public static final String VERSION_UNAVAILABLE = "(不可用)";

    // ------------------------------------------------------------------ 🆕 /shanhai statistics（2026-10-03）

    /**
     * 命令根字面量（用户原话：「在游戏聊天框输入 {@code /shanhai Statistics}」）。
     *
     * <p>⚠️ <b>刻意不加 {@code .requires()}</b>：默认权限等级 0 ⇒ <b>任何人都能用</b>
     * （用户要自己用；且他也可能在控制台里跑）。
     */
    public static final String COMMAND_ROOT = "shanhai";

    /**
     * 子命令字面量（<b>小写</b>）。Brigadier 的 literal <b>区分大小写</b>
     * ⇒ 小写与大写是两个不同的节点，两个都注册就能同时吃 {@code /shanhai statistics}
     * 与 {@code /shanhai Statistics}（用户 2026-10-03 原话：「Statistics前面s小写，我打错了」）。
     */
    public static final String COMMAND_ARG = "statistics";

    /** 首字母大写的<b>兼容别名</b>子命令字面量（用户最初那版的写法，保留以免他手快打错）。 */
    public static final String COMMAND_ARG_ALIAS = "Statistics";

    /**
     * 重打横幅那一拍的日志键名 —— <b>只写在日志里，不进横幅正文</b>。
     *
     * <p>为什么不往横幅里塞「（手动重打）」：那一版的文案/行数<b>用户已经验收过</b>，
     * 重打出来的东西必须与登录那条<b>逐字节相同</b>；要区分「这是手动的那条」看这行日志即可。
     */
    public static final String REPLAY_LOG_KEY = "banner_replay";

    // ------------------------------------------------------------------ 累加器

    /**
     * 🔴 2026-10-01：<b>全部计数器搬到 {@link ShanhaiBatchCounters}</b>（纯算术核，不依赖 Minecraft）。
     *
     * <p><b>为什么搬</b>：用户这一轮对着横幅报了「物质解构: <b>1193</b>」而真值是 <b>1154</b>。
     * 根因是「每批条数」取的是<b>那一刻的 {@code TOTAL} 快照</b>，而 {@code TOTAL} 只有脚本自己调
     * {@code reset()} 才会清 —— <b>解构脚本是全工程唯一不调 {@code reset()} 的</b>
     * ⇒ 那一批把别人加过的数一起显示了出来。
     * <p>修法（口径见 {@link ShanhaiBatchCounters} 类注释）：本批条数 = <b>本次累计 − 上次上报（或上次清零）时的累计</b>。
     * <p>⚠️ <b>对外签名一个都没改</b>（{@code reset/addResult/reportSummary/total/success/failed/lifetime}
     * 全部逐字节同签名）——KubeJS 那三个脚本<b>不需要任何改动</b>，也不存在"重载"这个 Rhino 雷区。
     */
    private static final ShanhaiBatchCounters CORE = ShanhaiBatchCounters.get();

    /**
     * 计数清零。由 KJS 在每次配方加载<b>开始上报之前</b>调一次
     * —— 否则 {@code /reload} 会让同一条配方被累加两次。
     *
     * <p>注意：本方法和 {@link #addResult(boolean)} {@link #reportSummary(String)} 由 KJS 包在
     * <b>同一个 try/catch</b> 里连续调用；任何一步抛异常 ⇒ {@link #reportSummary(String)} 不会被调到
     * ⇒ <b>不会打出那一行</b>。这是刻意的：宁可让机器判定的那一行<b>缺席（响亮失败）</b>，
     * 也不许打出一行<b>残缺但看起来正常</b>的数字。
     */
    public static void reset() {
        CORE.reset();
    }

    /**
     * 上报<b>一条</b>配方的结果。这是全工程<b>唯一</b>的计数入口（单一数据源）。
     *
     * <p>参数刻意是 {@code boolean} 而不是字符串状态：Rhino 侧只有 `true`/`false` 两种字面量，
     * <b>不存在拼错状态字符串导致静默记错</b>的可能。也刻意<b>不重载</b>这个方法 ——
     * Rhino 的重载解析在有多个同签名候选时是踩坑高发区。
     *
     * @param ok 该配方成功进了 GTCEu recipe builder（true）还是抛了异常（false）
     */
    public static void addResult(boolean ok) {
        CORE.addResult(ok);
    }

    /** 🔴 终身累计上报条数（{@link #reset()} 不清）。顺序无关，任何脚本位置读都得到同一个数。 */
    public static long lifetime() {
        return CORE.lifetime();
    }

    public static long total() {
        return CORE.total();
    }

    public static long success() {
        return CORE.success();
    }

    public static long failed() {
        return CORE.failed();
    }

    /** scope → 横幅上的中文名。查不到就用 scope 原文（<b>绝不编一个名字</b>）。 */
    private static final Map<String, String> SCOPE_LABELS = Map.of(
            SCOPE_KJS_TEST, "测试配方",
            // 🔴 2026-09-27 用户点单：横幅上这行原来直接显示英文 scope 名 `shanhai_pf`，改成中文「正式配方」
            "shanhai_pf", "正式配方",
            "shanhai_deconstruct", "物质解构");

    // ------------------------------------------------------------------ 待发横幅

    /**
     * 玩家 UUID → 还差多少 tick 发横幅。
     *
     * <p>用 UUID 而不是直接存 {@link ServerPlayer} 引用：玩家可能在 160 tick 内掉线，
     * 到点时再从 {@code PlayerList} 查一次，查不到就静默跳过，<b>不会留下一个已经被丢弃的实体强引用</b>。
     */
    private static final Map<UUID, Integer> PENDING_BANNER = new ConcurrentHashMap<>();

    private ShanhaiRecipeStats() {}

    // ================================================================== 累加器 API
    //
    //  ⛔ 2026-10-01 作废并删除的原文（留档，别往回加）——
    //     `reset()` 里的 `TOTAL/SUCCESS/FAILED.set(0)`、`addResult()` 里的三个 incrementAndGet()、
    //     以及 `lifetime()/total()/success()/failed()` 四个 `.get()`。
    //     它们已整体搬到 `ShanhaiBatchCounters`（纯算术核），本类只做转发：
    //     上面那 6 个方法（:337–:369）就是转发层，对外签名逐字节未变。
    //     原因：`BY_SCOPE` 原本存"那一刻的 TOTAL 快照"，而解构脚本不调 reset() ⇒ 显示偏大（1193 vs 1154）。

    // ================================================================== 配方加载完成：日志行

    /**
     * 打出<b>机器判定行</b>并返回它。由 KJS 在 {@code ServerEvents.recipes} 回调末尾调用。
     *
     * <p>格式固定（字段集合有意封闭，口径见类注释 §2）：
     * <pre>
     * [SHANHAI-SPEC] recipe_stats scope=&lt;scope&gt; total=&lt;n&gt; success=&lt;n&gt; failed=&lt;n&gt;
     * </pre>
     * <p>实现细节：用 {@link com.shanhai.ShanhaiMod#LOGGER} 而不是另起一个
     * {@code LogUtils.getLogger()} —— 类注释里写明过，各打各的 logger 会让 grep 漏掉。
     * 且这行<b>是纯字符串、不含 {@code {}} 占位符</b>，所以走 {@code info(String)} 时不会被 SLF4J
     * 的占位符替换逻辑动到任何字符。
     *
     * @param scope 这一批数字覆盖的范围（见 {@link #SCOPE_KJS_TEST}）。null/空串会被换成 {@code unknown}，
     *              以免打出一行 {@code scope=} 后面空着的、看起来像解析失败的日志。
     * @return 打出去的那一行原文（便于单测与人工核对）
     */
    public static String reportSummary(String scope) {
        String safeScope = (scope == null || scope.isEmpty()) ? "unknown" : scope;
        // 🔴 2026-10-01：留档改由纯算术核算【本批】条数（差值法），不再是"那一刻的 TOTAL 快照"。
        //    修的就是用户报的「物质解构: 1193」（真值 1154）——根因与推演见 ShanhaiBatchCounters 类注释。
        long[] batch = CORE.closeBatch(safeScope);
        long t = CORE.total();
        long s = CORE.success();
        long f = CORE.failed();
        String line = LOG_PREFIX + " " + STATS_LOG_KEY
                + " scope=" + safeScope
                + " total=" + t
                + " success=" + s
                + " failed=" + f
                + " lifetime=" + CORE.lifetime()
                + " batch=" + batch[0];
        ShanhaiMod.LOGGER.info(line);
        return line;
    }

    // ================================================================== 玩家登录：横幅

    /**
     * 玩家登录 → 排一次 160 tick 后的横幅。
     *
     * <p>边判说明：不需要 {@code isClientSide} 之类的判断 —— {@code PlayerLoggedInEvent} 只在<b>逻辑服务端</b>
     * 派发，且这里只读不写、只往聊天框发字，不碰任何存档数据。
     */
    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        PENDING_BANNER.put(player.getUUID(), BANNER_DELAY_TICKS);
    }

    /**
     * 服务端 tick 计数 → 到点发横幅。
     *
     * <p>{@code Phase.END} 才计：一个 tick 只数一次（START/END 各来一次会让 160 tick 变成 80 tick）。
     * 第一行 {@code isEmpty()} 短路 ⇒ 稳态（没有待发横幅）时这个处理器只是一次 map 判空。
     */
    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        if (PENDING_BANNER.isEmpty()) {
            return;
        }
        MinecraftServer server = event.getServer();
        Iterator<Map.Entry<UUID, Integer>> it = PENDING_BANNER.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Integer> entry = it.next();
            int remaining = entry.getValue() - 1;
            if (remaining > 0) {
                entry.setValue(remaining);
                continue;
            }
            it.remove();
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player != null) {
                sendBannerTo(player);
            }
        }
    }

    /**
     * 横幅内容（10 行，逐行发，每行一个 {@link Component}）。
     *
     * <p>⚠️ <b>「失败」那行与「无报错」那行互斥</b>：{@code failed > 0} 只显示失败行，
     * {@code failed == 0} 才显示「配方库检测无报错 祝领航员航行无阻!」。
     * 老版是两行都显示（它的「禁用 7」不算失败），这里按任务书给的版式做互斥。
     *
     * <p>内容一律用 {@link Component#literal(String)} 直出<b>字面量</b>，不走 lang 键
     * ⇒ <b>本轮不需要动 {@code assets/shanhai/lang/zh_cn.json}（改前=改后=58 键）</b>。
     *
     * <h4>🔴 哪些行带 {@code &$…-} 前缀、哪些不带 —— 这是照原版的分工，不是省事</h4>
     * <b>带前缀的 6 行</b>（每行都照原版原文）：首行 / {@code [OK]配方库 加载完成!} /
     * {@code 欢迎来到GTL寰宇联合重工巨企} / 回执行 / 末句 / 末行。
     * <p>
     * <b>不带前缀的 3 类行</b>（{@code ✅ 成功加载}、{@code 😊 配方库检测无报错}、{@code 🔷 重制版构建日期}，
     * 以及互斥的 {@code ⚠️ 失败}）—— 理由有三条，按分量排序：
     * <ol>
     *   <li><b>原版这几行本来就没有 {@code &$} 前缀</b>。老版对应的三行是
     *       {@code §a📦 成功加载: §eN§a 个配方} / {@code §a😋 配方库检测无报错…} /
     *       {@code §a💽 当前神人私货版本:v…}（取证：方案 md §3.4 的逐行解码表，
     *       第 3/4/5 行的「解析出的色板」一栏是空的）⇒ <b>原版是有意让"只有 6 行在流动"</b>
     *       （方案 md §3.4 原文：「注意：不是每行都有特效，只有 5 行有」）。
     *       给它们加渐变会把这个层次感做平。</li>
     *   <li><b>{@code ⚠️ 失败} 行<b>不该</b>是喜庆的流动色</b>：一条报错被染成流光溢彩会传递错误信号。
     *       本工程口径是「宁可缺，不可假」——它保持素色就是"这条是特殊状态"的可视信号。</li>
     *   <li><b>本轮任务范围就是「加回前缀」</b>：给这 3 行挑新样式属于<b>新增设计</b>，
     *       而用户给的选项里明确包含「或保持无前缀 ✅」。真要给它们挑样式，改 3 个字符串即可。</li>
     * </ol>
     */
    public static List<String> bannerLines() {
        return bannerLinesFor(null);
    }

    /**
     * 横幅内容（**逐行发，每行一个 {@link Component}**）。
     *
     * <h4>🔴 2026-10-01 用户点单新增的两行（原初物质定型 / 原初激光蚀刻）</h4>
     * 这两条<b>不来自任何脚本上报</b>，而是<b>在横幅要发的那一刻，从服务器自己的 {@code RecipeManager} 现数</b>
     * （{@link #countRecipesOfType(MinecraftServer, String)}）。理由有两条，都是硬的：
     * <ol>
     *   <li><b>原初物质定型是 KubeJS 配方</b>（2026-10-01 从数据包迁到
     *       {@code kubejs\server_scripts\[server_scripts]shanhai_primordial_forming.js}，2457 条），
     *       <b>那个脚本不上报 {@code ShanhaiStats}</b> ⇒ 它不在任何 {@code addResult} 的计数里；</li>
     *   <li><b>原初激光蚀刻由实例侧的 {@code shanhai_lens_goodbye.js} 创建</b>，那个脚本
     *       <b>全文不含 {@code ShanhaiStats}</b>（现查：命中 0）⇒ 同样不在任何上报里。</li>
     * </ol>
     * ⇒ 唯一的真源就是「配方表里现在到底有几条这个类型的配方」。<b>数不到就显示「(不可用)」，绝不编数字</b>
     * （本工程铁律：宁可缺，不可假）。
     *
     * <h4>🔴 每批条数的口径（2026-10-01 修正）</h4>
     * 分批那几行取 {@link ShanhaiBatchCounters#batchOf(String)}（= <b>该批自己的真实条数</b>）；
     * 而「shanhai 相关配方总数」那一行 = <b>终身累计 {@link #lifetime()} ＋ 两条现数行</b>
     * （{@link #grandTotalOf}）。<b>不再是</b>"只取终身累计" ——
     * 那正是用户 2026-10-01 报的「这加起来对不上啊」：那两行现数值不走 {@code addResult}，
     * 不进 LIFETIME，于是总数行比横幅上五行之和少一截。
     * LIFETIME 自身的累加语义（{@link ShanhaiBatchCounters#reset()} 不清它）<b>不变</b>。
     */
    public static List<String> bannerLinesFor(MinecraftServer server) {
        long failed = CORE.failed();
        long lifetime = CORE.lifetime();

        List<String> lines = new ArrayList<>(18);
        lines.add(PREFIX_GLITCH_GOLDEN + BAR_TOP);
        lines.add(PREFIX_FLOATX_MOSS + "[OK]配方库 加载完成!");
        // 🔴 2026-09-27 改：原来这里只有一行「✅ 成功加载: <TOTAL> 个配方」。
        //    现在改成【一行一批】；2026-10-01 又把「本批条数」的口径修成差值法（见 ShanhaiBatchCounters）。
        //    排序：批条数多的在前；同数按 scope 名字典序（保证两次运行顺序一致，便于比对）。
        List<String> scopes = CORE.scopes();
        scopes.sort((a, b) -> {
            long[] va = CORE.batchOf(a);
            long[] vb = CORE.batchOf(b);
            long ta = va == null ? 0L : va[0];
            long tb = vb == null ? 0L : vb[0];
            return ta != tb ? Long.compare(tb, ta) : a.compareTo(b);
        });
        for (String sc : scopes) {
            long[] v = CORE.batchOf(sc);
            if (v == null) {
                continue;
            }
            String label = SCOPE_LABELS.getOrDefault(sc, sc);
            lines.add(PREFIX_STAT + "✅ " + label + ": " + v[0] + " 个配方"
                    + (v[2] > 0 ? ("（失败 " + v[2] + " 个）") : ""));
        }
        if (scopes.isEmpty()) {
            // 一批都没上报过（例如还没走到配方加载）⇒ 说清楚，不编数字、也不显示一个像模像样的 0
            lines.add(PREFIX_STAT + "✅ 暂无可统计的配方批次");
        }
        // 🆕 2026-10-01（用户点单）：这两条按【配方类型】现数，不依赖任何脚本上报。
        //    🔴 现数值先落到局部变量：「总数」那一行要【复用】它们 —— 不许为了总数再遍历一次配方表。
        final long liveForming = countRecipesOfType(server, TYPE_ID_FORMING);
        final long liveLaser = countRecipesOfType(server, TYPE_ID_LASER_ETCHING);
        lines.add(recipeTypeCountLine(TYPE_LABEL_FORMING, liveForming));
        lines.add(recipeTypeCountLine(TYPE_LABEL_LASER, liveLaser));
        // 🔴 山海相关【全部】配方的汇总 = 【横幅上五行之和】。
        //    用户原话（2026-10-01）：「我还发现个bug，这加起来对不上啊」——
        //    根因：上面那两行是【发横幅这一刻从配方表现数】的 ⇒ 不走 addResult ⇒ 不进 LIFETIME，
        //    于是"总数"少算了它们（实例实测：五行之和 2748，而总数行印 1279 = 1154+86+39）。
        //    ⇒ 现行口径 = {@link #grandTotalOf}(KJS 三批的 LIFETIME, 刚现数的这两行)（用户拍板 A）。
        //    ⚠️ LIFETIME 的「随 /reload 累加、不被 reset 清零」语义【不变】（用户没要求改它）。
        //    ⚠️ 运行期读，代码里没有任何写死的数字。
        lines.add(PREFIX_STAT + "🔷 shanhai 相关配方总数: " + grandTotalText(lifetime, liveForming, liveLaser));
        // 🆕 2026-10-03（用户原话：「还有左下角横幅可以再统计一下注册的山海物品」）
        //    🔴 本轮【只新增这一行】，上面那些统计行一个字节都没动（前缀、顺序、措辞全保持原样）。
        //    这一行的两个口径、以及「拿不到 ⇒ (不可用)」的规则，逐条写在
        //    {@link ShanhaiItemCountCore} 的类注释 §1 / §2 里。
        lines.add(itemCountLine());
        if (failed > 0) {
            lines.add("⚠️ 失败: " + failed + " 个");
        } else {
            lines.add(PREFIX_STAT + "😊 配方库检测无报错 祝领航员航行无阻!");
        }
        lines.add(PREFIX_STAT + "🔷 重制版构建日期: " + buildDate());
        lines.add(PREFIX_AURORA + "欢迎来到GTL寰宇联合重工巨企");
        lines.add(PREFIX_MOSS + "此成功信息回执由JAVA侧: " + RECEIPT_CLASS_NAME + " 生成");
        lines.add(PREFIX_SILVER + "老大我们这样熬夜写私货心脏真的不会自己先休息吗");
        lines.add(PREFIX_GLITCH_GOLDEN + BAR_BOTTOM);
        return lines;
    }

    /** 一条「配方类型 → 条数」的横幅行。<b>数不到（-1）就显示「(不可用)」，绝不编一个 0</b>。 */
    private static String recipeTypeCountLine(String label, long count) {
        return count < 0L
                ? (PREFIX_STAT + "✅ " + label + ": (不可用)")
                : (PREFIX_STAT + "✅ " + label + ": " + count + " 个配方");
    }

    /**
     * 横幅「🔷 shanhai 相关配方总数」那一行的<b>纯算术核</b>（用户 2026-10-01 拍板 A）。
     *
     * <p>口径 = <b>【三批 KJS 上报的终身累计 LIFETIME】＋【两行现数值】</b>，
     * 也就是「横幅上五行之和」。判据：横幅印的总数 <b>必须</b>等于上面五行相加 ——
     * 用户就是拿这个发现旧实现漏算的（旧实现只印 LIFETIME，不含那两行现数值）。
     *
     * <p>🔴 这里是<b>纯算术</b>：不读配方表、不读计数器、不写任何状态 ⇒ 离线就能验
     * （{@code node tools\check-pf-declared-vs-disk.mjs --banner <文件>} 用的就是同一条算式）。
     *
     * @return {@code kjsLifetime + formingCount + laserCount}；两行里<b>任一行取不到（&lt;0）
     *         ⇒ 返回 -1</b>（调用方按「不可用」显示：只知道自己那一半的和就印出来，等于印一个假总数）
     */
    static long grandTotalOf(long kjsLifetime, long formingCount, long laserCount) {
        if (formingCount < 0L || laserCount < 0L) {
            return -1L;
        }
        return kjsLifetime + formingCount + laserCount;
    }

    /** {@link #grandTotalOf} 的显示形式：{@code -1} ⇒ {@link #VERSION_UNAVAILABLE}（「(不可用)」）。 */
    private static String grandTotalText(long kjsLifetime, long formingCount, long laserCount) {
        final long t = grandTotalOf(kjsLifetime, formingCount, laserCount);
        return t < 0L ? VERSION_UNAVAILABLE : String.valueOf(t);
    }

    /**
     * 横幅上那几条「✅ &lt;批名&gt;: N 个配方」的<b>条数之和</b>（= 各批 {@link ShanhaiBatchCounters#batchOf}
     * 的第 0 项相加）。
     *
     * <p>只给启动期那行日志用：把「总数 == 五行之和」这件事变成<b>一行可比对的读数</b>
     * （{@code banner_rows_sum=… banner_total=…}），不必等玩家登录看横幅。
     * 只遍历 batch 留档（几条），<b>不遍历配方表</b>。
     */
    static long batchRowsSum() {
        long sum = 0L;
        for (String sc : CORE.scopes()) {
            final long[] v = CORE.batchOf(sc);
            if (v != null) {
                sum += v[0];
            }
        }
        return sum;
    }

    /**
     * <b>数出「服务器现在的配方表里，这个类型有几条」</b>（横幅新增两行的唯一数据源）。
     *
     * <p>实现取 `RecipeManager#getRecipes()` 后按<b>类型句柄身份</b>过滤，
     * 而不是 `byType(RecipeType)` —— 后者在 1.20.1 是 <b>private</b>
     * （取证：{@code javap -p net.minecraft.world.item.crafting.RecipeManager} 里
     * {@code private byType(...)}；公开的只有 {@code getRecipesFor(...)} 与 {@code getRecipes()}）。
     * 类型句柄从 {@code BuiltInRegistries.RECIPE_TYPE} 取，与配方自己的 {@code getType()} 是同一批注册对象
     * ⇒ 引用相等比较成立。
     *
     * @return 条数；<b>服务器为 null / 类型没注册 / 取不到配方表 ⇒ -1</b>（调用方必须当成「不可用」）
     */
    public static long countRecipesOfType(MinecraftServer server, String fullTypeId) {
        if (server == null || fullTypeId == null) {
            return -1L;
        }
        try {
            RecipeType<?> type = BuiltInRegistries.RECIPE_TYPE.get(new ResourceLocation(fullTypeId));
            if (type == null) {
                return -1L;
            }
            long n = 0L;
            for (Recipe<?> recipe : server.getRecipeManager().getRecipes()) {
                if (recipe.getType() == type) {
                    n++;
                }
            }
            return n;
        } catch (Throwable t) {
            return -1L;
        }
    }

    // ================================================================== 🆕 横幅「注册的山海物品」那一行（2026-10-03）

    /**
     * 注册表 id 前缀 = {@code modid + ":"}。
     * <p>🔴 <b>真源是 {@link ShanhaiMod#MOD_ID}</b>，这里<b>不另写一个字面量 {@code "shanhai"}</b>
     * ——本工程的教训是「同一个常量被两处各写一份，必然漂移」。
     */
    private static final String SHANHAI_ID_PREFIX = ShanhaiMod.MOD_ID + ":";

    /**
     * 横幅那一行的<b>唯一构造入口</b>：前缀复用既有 {@link #PREFIX_STAT}
     * （{@code &$body_golden-}，<b>只有颜色流动、不含 {@code ?} / {@code *} 任何效果字符</b>
     * ⇒ 字不会抖；用户明令「物品名抖动的字看不清」）。
     */
    public static String itemCountLine() {
        return ShanhaiItemCountCore.line(PREFIX_STAT, registryShanhaiItemCount(), registrateItemLedgerCount());
    }

    /**
     * <b>口径 A · 从 Forge 物品注册表现数</b>：「{@code shanhai:} 命名空间下现在有多少个物品」。
     *
     * <p>数据链：{@link ForgeRegistries#ITEMS}（Forge 的物品注册表本体，javap 已核实其
     * {@code getKeys()} 返回 {@code Set<ResourceLocation>}）→ {@link #shanhaiItemIdStrings()}
     * → {@link ShanhaiItemCountCore#countNamespace(Iterable, String)} 按前缀数条数。
     * <b>代码里没有任何写死的物品总数</b>（本工程铁律：活界面上不许印假数字）。
     *
     * @return 条数；<b>注册表读不到 ⇒ -1</b>（调用方必须显示 {@code (不可用)}，不许回落成 0）
     */
    public static long registryShanhaiItemCount() {
        return ShanhaiItemCountCore.countNamespace(shanhaiItemIdStrings(), SHANHAI_ID_PREFIX);
    }

    /**
     * 把 Forge 物品注册表里的全部 id 读成字符串表。
     *
     * <p>🔴 <b>读不到就返回 {@code null}（= 「拿不到」），绝不返回空表</b>：
     * 空表会被 {@link ShanhaiItemCountCore#countNamespace} 正确地折成 {@code 0}
     * ——而 {@code 0} 的意思是「注册表里一个山海物品都没有」这个<b>具体事实</b>，
     * 与「这次读取失败了」是两件完全不同的事。把后者说成前者就是印假数字。
     */
    private static List<String> shanhaiItemIdStrings() {
        try {
            Set<ResourceLocation> keys = ForgeRegistries.ITEMS.getKeys();
            if (keys == null) {
                return null;
            }
            List<String> ids = new ArrayList<>(keys.size());
            for (ResourceLocation id : keys) {
                if (id != null) {
                    ids.add(id.toString());
                }
            }
            return ids;
        } catch (Throwable t) {
            // 注册表还没填 / 已被拆 / 任何反射级事故 —— 一律按「拿不到」处理。
            return null;
        }
    }

    /**
     * <b>口径 B · 交叉核对</b>：Registrate 自己的<b>物品登记台账</b>条目数
     * （{@code REGISTRATE.getAll(Registries.ITEM).size()}）。
     *
     * <p>为什么它是一个<b>独立</b>口径：口径 A 读的是<b>游戏里的 Forge 注册表</b>，
     * 而这里读的是 <b>Registrate 内存里的 {@code registrations} 表</b>（javap 已核实
     * {@code AbstractRegistrate.getAll(ResourceKey)} 存在且返回 {@code Collection<RegistryEntry<R>>}）。
     * 两条链的唯一交汇点是「Registrate 有没有真的把东西塞进注册表」——
     * 若某次注册静默失败，台账会比注册表多，两个数就会不等，那一行会当场自己说出来。
     *
     * @return 台账条目数；<b>取不到 ⇒ -1</b>（同样显示 {@code (不可用)}，绝不回落）
     */
    public static long registrateItemLedgerCount() {
        try {
            return ShanhaiRegistration.REGISTRATE.getAll(Registries.ITEM).size();
        } catch (Throwable t) {
            return ShanhaiItemCountCore.UNAVAILABLE;
        }
    }

    /**
     * <b>口径 C（只进日志，不进横幅）</b>：{@link ShanhaiItems} 上声明了多少个静态
     * {@link ItemEntry} 字段 —— 即「这个类自己说它注册了几个物品」。
     *
     * <p>⚠️ <b>它量的是另一个集合</b>（{@code ShanhaiItems} 一个类的声明数），
     * <b>不是</b>注册表里 {@code shanhai:} 物品总数：每个流体都会由 Registrate 自动派生一个
     * {@code shanhai:<id>_bucket} 桶物品（{@code ShanhaiFluids} 的 25 个流体 ⇒ 25 个桶），
     * 还有经 {@code REGISTRATE} 注册的机器方块物品。所以它<b>不能</b>拿去跟口径 A 做
     * 「一致/不一致」判定（那会让横幅常亮「不一致」），只作为启动期日志里的<b>分解依据</b>。
     *
     * @return 字段数；反射失败 ⇒ -1
     */
    static int shanhaiItemsDeclaredFields() {
        try {
            int n = 0;
            for (Field f : ShanhaiItems.class.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) && ItemEntry.class.isAssignableFrom(f.getType())) {
                    n++;
                }
            }
            return n;
        } catch (Throwable t) {
            return -1;
        }
    }

    /**
     * 口径 C 的「落表数」：上面那些 {@link ItemEntry} 句柄里，有几个的 id <b>真的</b>能在
     * Forge 注册表里查到（{@link ForgeRegistries#ITEMS} 的 {@code containsKey}，javap 已核实）。
     *
     * <p>用途：把「声明了 185 个」与「实际落表 185 个」区分开 —— 若两者不等，
     * 就是某些物品<b>声明了却没注册进游戏</b>（那种故障在玩家眼里只是「物品不见了」，不报错）。
     *
     * @return 落表数；反射失败 ⇒ -1
     */
    static long shanhaiItemsInRegistry() {
        try {
            long n = 0L;
            for (Field f : ShanhaiItems.class.getDeclaredFields()) {
                if (!Modifier.isStatic(f.getModifiers()) || !ItemEntry.class.isAssignableFrom(f.getType())) {
                    continue;
                }
                Object v = f.get(null);
                if (v instanceof ItemEntry<?> entry) {
                    ResourceLocation id = entry.getId();
                    if (id != null && ForgeRegistries.ITEMS.containsKey(id)) {
                        n++;
                    }
                }
            }
            return n;
        } catch (Throwable t) {
            return -1L;
        }
    }

    /**
     * <b>启动期</b>把「注册的山海物品」那一行的读数与判据打进日志。
     *
     * <p>为什么要这个钩子：横幅<b>只有玩家登录才发</b>，而无头专服没有玩家
     * ⇒ 「新增那一行的数字对不对」在冒烟里<b>永远看不到</b>（与 {@code banner_total_check} 同理）。
     * 这里把同一份计算搬到启动期，于是冒烟日志本身就能给出判据：
     * <pre>
     * [SHANHAI-SPEC] item_count_reads registry_shanhai=&lt;A&gt; registrate_ledger=&lt;B&gt; shanhaiitems_fields=&lt;C&gt;
     *                shanhaiitems_in_registry=&lt;C2&gt; qmark=0 star=0 unavail_text_eq_existing=true
     * [SHANHAI-SPEC] banner_item_line &lt;那一行的最终文本，含 &amp;$body_golden- 前缀&gt;
     * [SHANHAI-SPEC] item_count_selftest ok cases=8
     * </pre>
     * <p>{@code qmark} / {@code star} 就是判据 ④ 的机器读数（抖动红线：都必须为 0）。
     */
    private static void logItemCountDiagnostics() {
        final long registryCount = registryShanhaiItemCount();
        final long ledgerCount = registrateItemLedgerCount();
        final String line = ShanhaiItemCountCore.line(PREFIX_STAT, registryCount, ledgerCount);
        ShanhaiMod.LOGGER.info(LOG_PREFIX + " item_count_reads registry_shanhai=" + registryCount
                + " registrate_ledger=" + ledgerCount
                + " shanhaiitems_fields=" + shanhaiItemsDeclaredFields()
                + " shanhaiitems_in_registry=" + shanhaiItemsInRegistry()
                + " qmark=" + ShanhaiItemCountCore.countChar(line, '?')
                + " star=" + ShanhaiItemCountCore.countChar(line, '*')
                + " unavail_text_eq_existing=" + ShanhaiItemCountCore.UNAVAILABLE_TEXT.equals(VERSION_UNAVAILABLE));
        ShanhaiMod.LOGGER.info(LOG_PREFIX + " banner_item_line " + line);
        try {
            ShanhaiMod.LOGGER.info(LOG_PREFIX + " item_count_selftest " + ShanhaiItemCountCore.selfTest());
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.error(LOG_PREFIX + " item_count_selftest FAILED: " + t);
        }
    }

    /**
     * <b>启动期</b>把整条横幅<b>逐行</b>打进日志（判据 ①「那一行真的在横幅字符串里」的机器证据）。
     *
     * <p>横幅只有在玩家登录后 160 tick 才会发到聊天栏，无头专服看不到它 ⇒ 这里打的是
     * {@link #bannerLinesFor(MinecraftServer)} 的<b>同一份返回值</b>（同一个方法、同一个顺序），
     * 因此日志里读到的文本就是玩家会看到的那条横幅（只差客户端把 {@code &$…-} 前缀解析成流动色）。
     * <p>⚠️ 这一行<b>不会</b>真的发出去：它只写日志，不碰任何玩家/存档。
     */
    private static void logBannerPreview(MinecraftServer server) {
        try {
            List<String> lines = bannerLinesFor(server);
            for (int i = 0; i < lines.size(); i++) {
                ShanhaiMod.LOGGER.info(LOG_PREFIX + " banner_preview idx=" + i + " " + lines.get(i));
            }
            ShanhaiMod.LOGGER.info(LOG_PREFIX + " banner_preview_lines=" + lines.size());
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.error(LOG_PREFIX + " banner_preview FAILED: " + t);
        }
    }

    /** 给玩家逐行发横幅。{@code null} 玩家静默跳过（调用方本来就会判，这里再加一道）。 */
    public static void sendBannerTo(ServerPlayer player) {
        if (player == null) {
            return;
        }
        // 🔴 2026-10-01：横幅要多算两条「按配方类型现数」的行 ⇒ 必须拿到服务器（配方表在它身上）。
        // 🔴 2026-10-03：投递动作整体抽到 {@link #deliverBanner}，与 /shanhai statistics 那条路【共用】
        //    —— 两条路共用同一个 {@link #bannerLinesFor} 生成文本、同一个投递方法发送，
        //    不存在「两份文案各自漂移」的可能。
        deliverBanner(bannerLinesFor(player.getServer()), player);
    }

    /**
     * 🔴 <b>横幅投递的唯一出口</b>（登录路径与 {@code /shanhai statistics} 命令路径共用）。
     *
     * <p>分流规则（用户 2026-10-03 点单）：
     * <ul>
     *   <li><b>执行者是人</b>（{@code player != null}）⇒ 逐行 {@code sendSystemMessage} 到他的聊天框；</li>
     *   <li><b>执行者是控制台 / 命令方块 / 其它非玩家源</b>（{@code player == null}）⇒
     *       <b>逐行写进日志</b>（{@link ShanhaiMod#LOGGER}，即 {@code latest.log}）。
     *       🔴 这一条是<b>必须</b>的：无头专服没有玩家，只有把控制台那条路落到日志里，
     *       「命令真的把横幅重新生成了一遍」才<b>可被机器验证</b>。</li>
     * </ul>
     *
     * <p>⚠️ 两种分流发出去的<b>字符串集合完全一致</b>（都来自同一个 {@code lines} 参数）
     * —— 客户端把 {@code &$…-} 前缀解析成流动色这件事与本方法无关（那在 {@code Font.drawInBatch} 里）。
     *
     * @param lines  {@link #bannerLinesFor(MinecraftServer)} 的返回值（<b>调用方先算好</b>，
     *               保证「日志里记的行数」与「真正发出去的行数」是同一份，不会各算一次）
     * @param player 人类执行者；{@code null} ⇒ 走日志
     */
    private static void deliverBanner(List<String> lines, ServerPlayer player) {
        for (String line : lines) {
            if (player != null) {
                player.sendSystemMessage(Component.literal(line));
            } else {
                ShanhaiMod.LOGGER.info(line);
            }
        }
    }

    // ================================================================== 🆕 /shanhai statistics（2026-10-03）

    /**
     * 🔴 注册 {@code /shanhai statistics}（+ 兼容别名 {@code /shanhai Statistics}）。
     *
     * <h4>为什么挂在 {@link RegisterCommandsEvent} 上、并且就在本类里</h4>
     * <ul>
     *   <li>本类已经是 {@code @Mod.EventBusSubscriber(modid = …, bus = Bus.FORGE)}（且<b>不写 {@code value}</b>
     *       ⇒ 两个 dist 都注册，见类注释 §6 的 {@code javap -v} 取证）。
     *       {@code RegisterCommandsEvent} 正是<b>发在 Forge 总线上</b>的事件
     *       ⇒ 本类挂一个 {@code @SubscribeEvent} 就够了，<b>不需要</b>去 {@code ShanhaiMod} 构造器里
     *       再加一条显式 {@code addListener}（少一处需要同步维护的挂载点）。</li>
     *   <li>🔴 <b>不是客户端注册</b>：本类没有 {@code Dist.CLIENT} 限定，单机（集成服务端）与专服都会走到
     *       —— 用户玩的是单机，只在 {@code Dist.CLIENT} 注册反而会让他的单人存档里没有这条命令。</li>
     * </ul>
     *
     * <h4>大小写</h4>
     * Brigadier 的 literal 是<b>逐字符比较</b>的 ⇒ 两个 literal 都注册。
     * 用户 2026-10-03 更正：「{@code Statistics}前面 s 小写，我打错了」⇒ <b>主命令是小写那条</b>，
     * 大写那条<b>保留为兼容别名</b>（他一开始就是那么写的，两条都留不留坑）。
     */
    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal(COMMAND_ROOT)
                        .then(commandBranch(COMMAND_ARG))
                        .then(commandBranch(COMMAND_ARG_ALIAS)));
        // 注册期打一行 INFO：这条日志本身就是「命令树真的挂上了」的机器可判证据
        // （无头专服里控制台能不能跑命令，先看这一行在不在）。
        ShanhaiMod.LOGGER.info(LOG_PREFIX + " command_registered /" + COMMAND_ROOT + " " + COMMAND_ARG
                + " (+alias /" + COMMAND_ROOT + " " + COMMAND_ARG_ALIAS + ")");
    }

    /** {@code /shanhai <name>} 那一支：无参数、任何权限、执行即重打横幅。 */
    private static LiteralArgumentBuilder<CommandSourceStack> commandBranch(String name) {
        return Commands.literal(name).executes(ctx -> replayBanner(ctx.getSource()));
    }

    /**
     * <b>把左下角那条横幅按当前真实状态重新生成并打一遍</b>（命令路径的唯一入口）。
     *
     * <h4>🔴 数字全部现读，这里<b>没有任何缓存</b></h4>
     * 文本来自 {@link #bannerLinesFor(MinecraftServer)} —— 与登录那条横幅<b>同一个方法、同一份实时状态</b>：
     * <ul>
     *   <li>三批条数取 {@code CORE}（本批差值法的实时读数）；</li>
     *   <li>「原初物质定型 / 原初激光蚀刻」两行<b>当场遍历服务器 {@code RecipeManager}</b> 现数；</li>
     *   <li>「注册的山海物品」当场遍历 {@code ForgeRegistries.ITEMS} 现数；</li>
     *   <li>构建日期当场读 jar 内 {@code assets/shanhai/build.properties}（刻意不缓存）。</li>
     * </ul>
     * ⇒ 例如中间 {@code /reload} 过、或又注册了物品，重打出来的数就是<b>那一刻</b>的数。
     *
     * @return 打出去的行数（Brigadier 的 {@code result}，同时便于人工核对「是不是 15 行」）
     */
    public static int replayBanner(CommandSourceStack source) {
        MinecraftServer server = source.getServer();
        List<String> lines = bannerLinesFor(server);
        ServerPlayer player = source.getPlayer();
        ShanhaiMod.LOGGER.info(LOG_PREFIX + " " + REPLAY_LOG_KEY
                + " lines=" + lines.size()
                + " by=" + (player != null ? player.getGameProfile().getName() : "console"));
        deliverBanner(lines, player);
        return lines.size();
    }

    /**
     * <b>服务器完全起来之后</b>打两行机器可判的日志。
     *
     * <p>为什么要这个钩子：横幅本身<b>只有玩家登录才发</b>，而无头专服没有玩家
     * ⇒ 「新增两行的数字对不对」在冒烟里<b>永远看不到</b>。这两行把同一份计算搬到启动期，
     * 于是「冒烟日志」就能给出判据（用户原话与冒烟纪律见 handoff）。
     *
     * <pre>
     * [SHANHAI-SPEC] batch_counters_selftest ok cases=8（…）           ← 或 batch_counters_selftest FAILED: …
     * [SHANHAI-SPEC] recipe_type_counts gtceu:primordial_matter_forming=2457 gtceu:primordial_laser_etching=283
     * [SHANHAI-SPEC] banner_total_check banner_rows_sum=1279 kjs_lifetime=1279 live_forming=2457 live_laser=283 banner_total=4019 identity=OK
     * </pre>
     *
     * <p>第三行 = 判据「横幅总数 == 五行之和」的机器可比对读数（{@code 4019 = 1279 ＋ 2457 ＋ 283}）。
     * ⚠️ 样例里的批次数字（1279 / 283）取自用户 2026-10-01 的横幅截图，<b>不是本轮实测</b>
     * （本轮禁止启动游戏）；{@code identity=DIFF} 才说明口径分叉，{@code OK} 说明五行相加对得上。
     *
     * <h4>🔴 自检失败为什么只 ERROR 不抛（与本工程 ShanhaiConcurrencyTables.selfTest 的做法不同）</h4>
     * 那条先例是在<b>机器注册期</b>抛异常；本处是<b>服务器已启动</b>，抛出去等于把用户的存档会话当场打断，
     * 而失败的对象只是「横幅上两个数字算得对不对」。
     * 本工程的冒烟判据是「含 {@code shanhai} 的 {@code /ERROR]} 行 = 0」⇒ <b>这里打 ERROR 就已经会被机器判红</b>，
     * 响亮程度等价，爆炸半径小得多。（自检本身已在离线三段自证里验过：
     * {@code java BatchProof new/old/new} ⇒ 0 / 1 / 0。）
     */
    @SubscribeEvent
    public static void onServerStarted(net.minecraftforge.event.server.ServerStartedEvent event) {
        try {
            ShanhaiMod.LOGGER.info(ShanhaiBatchCounters.selfTest());
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.error(LOG_PREFIX + " batch_counters_selftest FAILED: " + t);
        }
        MinecraftServer server = event.getServer();
        // 派发时的 scope 摘要（人读用；冒烟日志里能一眼看出每批各多少条）
        ShanhaiMod.LOGGER.info(LOG_PREFIX + " batch_summary " + CORE.summary());
        // 🔴 现数值只算一次，下面两行【复用】它 —— 与横幅同一口径（见 grandTotalOf 的注释）。
        //    这里不新增遍历：横幅那边也是各算一次，两边都只遍历一遍配方表。
        final long liveForming = countRecipesOfType(server, TYPE_ID_FORMING);
        final long liveLaser = countRecipesOfType(server, TYPE_ID_LASER_ETCHING);
        ShanhaiMod.LOGGER.info(LOG_PREFIX + " recipe_type_counts "
                + TYPE_ID_FORMING + "=" + liveForming
                + " " + TYPE_ID_LASER_ETCHING + "=" + liveLaser);
        // 🔴 判据「横幅总数 == 五行之和」的启动期读数（横幅要等玩家登录才发 ⇒ 冒烟日志里看不见，这里补上）：
        //    banner_rows_sum = 那几条「✅ <批名>: N 个配方」的和
        //    banner_total    = LIFETIME ＋ 上面两行现数值（= 横幅那一行真正会印的数）
        //    identity=DIFF ⇒ 两者对不上，说明"总数"与横幅五行之和的口径又分叉了（必须查）。
        final long rowsSum = batchRowsSum();
        final long kjsLifetime = CORE.lifetime();
        final long bannerTotal = grandTotalOf(kjsLifetime, liveForming, liveLaser);
        ShanhaiMod.LOGGER.info(LOG_PREFIX + " banner_total_check banner_rows_sum=" + rowsSum
                + " kjs_lifetime=" + kjsLifetime
                + " live_forming=" + liveForming + " live_laser=" + liveLaser
                + " banner_total=" + (bannerTotal < 0L ? VERSION_UNAVAILABLE : bannerTotal)
                + " identity=" + (bannerTotal >= 0L && bannerTotal == rowsSum + liveForming + liveLaser
                        ? "OK" : "DIFF"));
        // 🆕 2026-10-03：新增那一行（🔷 注册的山海物品）的启动期读数 + 整条横幅的逐行文本。
        //    🔴 【只追加】：上面那些既有日志行的措辞、顺序一个字节都没动。
        logItemCountDiagnostics();
        logBannerPreview(server);
    }

    /**
     * 构建日期的资源路径（{@code src/main/resources/} 下的路径 ⇒ 运行期按 classpath 资源读）。
     * <p>它在 jar 里的条目名就是 {@code assets/shanhai/build.properties}（判据 ① 直接读它）。
     */
    public static final String BUILD_DATE_RESOURCE = "/assets/shanhai/build.properties";

    /** {@link #BUILD_DATE_RESOURCE} 里的键名。 */
    public static final String BUILD_DATE_KEY = "build_date";

    /** 构建日期的合法形态：{@code yyyy-MM-dd}。<b>只认这一种</b>，其余一律当读不到。 */
    private static final java.util.regex.Pattern BUILD_DATE_FORMAT =
            java.util.regex.Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

    /**
     * 取本 mod 的<b>构建日期</b>（横幅 {@code 🔷 重制版构建日期: …} 那一行）。
     *
     * <h4>为什么是「构建日期」而不是「版本号」</h4>
     * 用户 2026-10-02 原话：「重制版左下角的横幅显示当前版本可以改成构建日期，
     * <b>符合我们用日期定义版本的模式</b>」。⇒ 本工程用日期当版本口径，
     * 横幅就印日期；{@code mod_version}（{@code gradle.properties}）保持 {@code 0.1.0} <b>不动</b>，
     * 那是另一件事。
     *
     * <h4>🔴 值的唯一来源是构建期注入，本类不写死任何日期</h4>
     * 值来自 {@link #BUILD_DATE_RESOURCE}（{@code assets/shanhai/build.properties}），
     * 那是个<b>占位符文件</b>（{@code build_date=${build_date}}），由 {@code build.gradle} 的
     * {@code processResources -> filesMatching(...) { expand ... }} 在构建时替换成构建当天。
     * ⇒ <b>源码里 grep 不到任何字面日期</b>，改日期只能改构建输入（或系统时钟）。
     *
     * <h4>🔴 取不到就返回 {@link #VERSION_UNAVAILABLE}，绝不回落到写死的日期</h4>
     * 这是本工程既有的铁律（同 {@link #modVersion()} 那条注释）——「宁可缺，不可假」。
     * 具体会走到这条路的情形（都<b>不</b>是理论假设，是本轮判据 ② 实测过的）：
     * <ul>
     *   <li>占位符<b>没被 expand 覆盖</b>（{@code build.properties} 没写进 {@code filesMatching}）
     *       ⇒ 读出来是字面量 {@code ${build_date}} ⇒ 形态不符 ⇒ 不可用；</li>
     *   <li>资源缺失 / 键名改了 / IO 抛异常 / 类加载器拿不到这个条目。</li>
     * </ul>
     *
     * <p>⚠️ <b>刻意不做缓存</b>：这条路径每个玩家登录只走一次，省下的 IO 是零头；
     * 而缓存会把「第一次读失败」永久固化下来，让一次偶发失败变成整局游戏的假「(不可用)」。
     *
     * @return {@code yyyy-MM-dd}，或 {@link #VERSION_UNAVAILABLE}
     */
    public static String buildDate() {
        String raw;
        try {
            raw = readBuildDateFromResource();
        } catch (Throwable ignored) {
            // 资源读不到、类加载器异常、Properties 解析失败……一律降级成「不可用」，
            // 不抛给横幅调用方（横幅少一行总好过整个登录事件炸掉）。
            raw = VERSION_UNAVAILABLE;
        }
        return raw;
    }

    /** {@link #buildDate()} 的纯读取核：拿不到/形态不对都返回 {@link #VERSION_UNAVAILABLE}。 */
    private static String readBuildDateFromResource() throws java.io.IOException {
        java.util.Properties props = new java.util.Properties();
        // 用 ShanhaiRecipeStats.class 自己的类加载器（而不是线程上下文那个）：
        // 在 Forge 里前者才保证解析到【本 mod 的 jar】。
        // 并且用 load(InputStream) 而不是 load(Reader)：properties 按 ISO-8859-1 解码，
        // 而本文件刻意保持纯 ASCII（日期），两边口径一致。
        try (java.io.InputStream in = ShanhaiRecipeStats.class.getResourceAsStream(BUILD_DATE_RESOURCE)) {
            if (in == null) {
                return VERSION_UNAVAILABLE;
            }
            props.load(in);
        }
        String value = props.getProperty(BUILD_DATE_KEY);
        if (value == null) {
            return VERSION_UNAVAILABLE;
        }
        value = value.trim();
        // 🔴 这一条就是「没被 expand 覆盖」的守门人：占位符原文 ${build_date} 到这里被拒。
        if (!BUILD_DATE_FORMAT.matcher(value).matches()) {
            return VERSION_UNAVAILABLE;
        }
        return value;
    }

    /**
     * 取本 mod 的运行期版本（{@code mods.toml} 的 {@code version="${mod_version}"}）。
     *
     * <p>⚠️ 2026-10-02：<b>横幅已经不再用这一行</b>（改为 {@link #buildDate()}）。
     * 本方法<b>刻意保留</b>：它是公开 API，删掉属于「改版本相关的东西」，
     * 而本轮任务范围只有横幅那一行。
     *
     * <p>取不到就返回 {@link #VERSION_UNAVAILABLE}，<b>绝不回落到写死的版本号</b> ——
     * 活界面上印假数字是本工程的血账之一。
     *
     * <p>老版那一行是 {@code v2.7.4fix}，带 {@code v} 前缀，所以这里也补一个（版本串本身若已带 v/V 就不重复补）。
     */
    public static String modVersion() {
        String raw;
        try {
            raw = ModList.get()
                    .getModContainerById(ShanhaiMod.MOD_ID)
                    .map(container -> container.getModInfo().getVersion().toString())
                    .orElse(VERSION_UNAVAILABLE);
        } catch (Throwable ignored) {
            raw = VERSION_UNAVAILABLE;
        }
        if (raw.isEmpty()) {
            return VERSION_UNAVAILABLE;
        }
        char first = raw.charAt(0);
        return (first == 'v' || first == 'V') ? raw : ("v" + raw);
    }
}
