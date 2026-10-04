package com.shanhai.machine.module;

import com.google.gson.JsonObject;
import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.api.machine.trait.RecipeLogic;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.RecipeCondition;
import com.gregtechceu.gtceu.api.recipe.condition.RecipeConditionType;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.shanhai.ShanhaiMod;
import com.shanhai.common.text.ShanhaiTextParser;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.Nullable;

/**
 * 山海重构 · <b>物质模块等级配方条件</b>（老山海 {@code module_level} 的移植，<b>简化版</b>）。
 *
 * <h2>0. 出处</h2>
 * <pre>
 * 上游：originals/upstream\gtl_shanhai-dishanhai\src\main\java\com\dishanhai\gt_shanhai\api\ModuleLevelCondition.java
 *       （236 行，{@code extends com.gregtechceu.gtceu.api.recipe.RecipeCondition}）
 * 上游注册：GTDishanhaiGTAddon.java:34-38
 *       GTRegistries.RECIPE_CONDITIONS.unfreeze();
 *       GTRegistries.RECIPE_CONDITIONS.register("module_level", ModuleLevelCondition.TYPE);
 *       GTRegistries.RECIPE_CONDITIONS.freeze();
 * </pre>
 *
 * <h2>1. 🔴 判定规则 = <b>等级上界比较</b>（用户 2026-09-26 定案，<b>刻意不用老山海那套</b>）</h2>
 * <p><b>用户原话（逐字）</b>：
 * <blockquote>
 * 「对了，我们的配方全都是只会要求有 1 个物质模块，不然若一个低级配方需要 4 个低级模块，
 * 而我要升级的时候材料只能够一个高级模块，那甚至不能执行低级配方，这样就很不好，所以物质模块的要求是有就行」
 * <br>「其实不用老山海的解法，用我这个解法就好，我不希望游戏太复杂」
 * </blockquote>
 * ⇒ <b>本类的判定式只有一条</b>：
 * <pre>
 *   槽里的物质模块等级  &gt;=  要求等级        ⇒ 通过
 *   其中   要求等级   = {@code PrimordialModuleMachine.getModuleLevelById(moduleId)}（由物品 id 自己决定）
 *          槽里等级   = {@code module.getMatterModuleLevel()}（空槽 = 0）
 * </pre>
 * ⇒ <b>不看数量、不做等效换算、没有"高等级顶多个低等级"这一层</b>。
 * 配方的 N（{@code "1x …"} 里那个 1）<b>一律写 1</b>，且<b>不参与判定</b>（见 §3）。
 * <p>⚠️ <b>与上游的三处差别，逐条写明（不许含糊）</b>：
 * <ol>
 *   <li>上游有 {@code ModuleLevelEquivalence.calculateEquivalentCount}
 *       （等级差 0~3 每级翻倍、差 ≥4 = {@code Long.MAX_VALUE}）<b>—— 本工程【没有这个类】，刻意不移植</b>
 *       （用户原话：「不用老山海的解法」）。上游那个 {@code requiredLevel} 是<b>数量</b>，
 *       本工程的语义里<b>数量不参与判定</b>。</li>
 *   <li>上游 {@code test()} 只走<b>主机聚合分支</b>（16 模块位合并等效量）；本工程走<b>模块分支</b>。
 *       理由见 §4。</li>
 *   <li>上游 {@code getStyledName()} 用 {@code DShanhaiTextUtil} / {@code ShanhaiTextAPI}
 *       两个<b>本工程不存在</b>的类做服务端预解析；本工程改成直接返回物品的显示名 Component
 *       （2026-09-26 修复轮起 = {@code new ItemStack(item).getHoverName()}），理由见 {@link #displayName()}。</li>
 * </ol>
 *
 * <h2>2. 语义示例（用户点名的三条判据）</h2>
 * <pre>
 *   要求 = shanhai:introductory_material_module（该物品 = 1 级）⇒ 要求等级 = 1
 *     装 [material_deduction_module = 3 级]  ⇒ 3 ≥ 1 ⇒ ✅ 通过
 *     装 [introductory_material_module = 1 级] ⇒ 1 ≥ 1 ⇒ ✅ 通过
 *     空槽（等级 0）                            ⇒ 0 ≥ 1 ⇒ ❌ 不通过
 * </pre>
 *
 * <h2>3. ⚠️ 一个必须讲清楚的歧义（如实交代，别当成"肯定对"）</h2>
 * <p>「要求等级」有两个可能的读法：**(a) 由 {@code moduleId} 这个物品自己在等级表里的档位决定**；
 * **(b) 由构造器第二个实参 {@link #requiredLevel}（也就是 KJS 里 {@code "1x"} 的那个 1）决定**。
 * 本类取 **(a)**，理由两条：
 * <ol>
 *   <li>{@code shanhai_pf_recipes.js}（<b>已冻结、不许改</b>）的 §6 注释写死了：
 *       「这里的 N 是【数量】，而"等级"由那个物品 id 自己决定」；</li>
 *   <li>读法 (b) 下 {@code "4x <1 级模块>"} 会变成"要求等级 4"（用户明确讨厌的那种复杂度），
 *       而 {@code "1x <3 级模块>"} 又会丢掉"要求 3 级"这件事 ⇒ (b) 与"把模块等级作为配方要求"这个目标矛盾。</li>
 * </ol>
 * <p>⇒ 对<b>当前唯一的那条配方 ⑦</b>（{@code "1x shanhai:introductory_material_module"}）两种读法<b>给出同一个门槛 1</b>
 * ⇒ 用户那三条判据在两种读法下都成立。若将来要按读法 (b) 走，改一行即可（见 {@link #requiredLevelForGate()}）。
 *
 * <h2>4. ⛔ 【未移植】主机聚合分支（16 模块位合并等效量）</h2>
 * <p>上游 {@code hostEquivalentCountSatisfies(IModularMachineHost)}（原 :161-185）依赖
 * {@code org.gtlcore.gtlcore.api.machine.multiblock.IModularMachineHost} /
 * {@code IModularMachineModule} 与 {@code PrimordialOmegaEngineMachine#getModuleSet()} /
 * {@code #getEquivalentModuleCountForLevel(int)} —— <b>本工程一个都没有，本轮不移植</b>。
 * <p>⇒ 后果，<b>明确写出来</b>：本条件在<b>非模块机</b>上（含主机、含任何别的机器）<b>一律判 false</b>，
 * 并打一条<b>只出现一次</b>的可 grep WARN（{@code [SHANHAI-MODULE-LEVEL]}）。
 * <b>刻意不选"静默放行"</b>：放行 = 门槛失效 —— 那正是本项目明令禁止的「悄悄不发生」型失败。
 * <p>实际影响 = 0：本条件目前只挂在 {@code shanhai:pf/photon} 一条配方上，而它属于 {@code photon_siphon} 类型，
 * 该类型只被模块（原初分歧发生器 / 原初山海调试模块）挂载（取证：{@code ModuleRegistry.RECIPE_DIVERGENCE_GENERATOR}）。
 *
 * <h2>5. {@link #REQUIREMENTS} 静态表：保留但不参与判定（如实交代）</h2>
 * <p>{@link #register} / {@link #clearRequirements} / {@link #getRequirements} 是上游"配方 ID → 条件列表"
 * 的内存表，供老山海那条模块判定链使用。<b>本工程没有那条链</b>（见 §4 与 {@link #test}）⇒ 这三方法目前
 * <b>没有任何调用者</b>。保留只为与上游逐字对照。<b>不要以为写了 {@code register(...)} 就会生效。</b>
 *
 * <h2>6. 🔴 2026-09-26 修复轮：门槛被算成 0（用户报「空槽也能跑」）＋ 提示行改成中文名</h2>
 *
 * <h3>6.1 现象（用户截图，逐条列原始字符串）</h3>
 * <pre>
 *   JEI 那一行 : 模块要求: 1x shanhai:introductory_material_module （等级 &gt;= 0）
 *   机器 GUI    : 已安装模块:（空槽） ＋ 当前机器模式: 光子虹吸 ＋ 运行正常
 * </pre>
 * 用户原话：「<b>jei显示是这样的，我希望它可以写中文，而且我发现它的限制并没有生效，我没放物质模块它能工作</b>」
 *
 * <h3>6.2 根因（判据 = 显示串反推，不是猜）</h3>
 * <ol>
 *   <li>{@link #getTooltips()} 的字节码只有三段：{@code "§b模块要求："} ＋ {@code getStyledName()} ＋
 *       {@code " §7（等级 ≥ " + N + "）"}（{@code javap -c} 可证，本类里<b>没有任何</b>"N× "前缀拼接）。</li>
 *   <li>⇒ 上一行里那个 {@code 1x } 前缀<b>只可能来自名字那一段</b>，而名字那一段在物品查不到时会
 *       <b>原样回退成 {@link #moduleId}</b>。</li>
 *   <li>⇒ 显示的 {@code 1x shanhai:introductory_material_module} 就是<b>构造时收到的 {@code moduleId} 原文</b>；
 *       它<b>不是 17 个模块 id 之一</b>（表里是 {@code shanhai:introductory_material_module}）⇒
 *       {@code getModuleLevelById} 走 {@code getOrDefault(..., 0)} ⇒ <b>门槛 = 0</b>。</li>
 *   <li>⇒ 门槛 0 的后果是<b>语义反转</b>：{@code 0 &gt;= 0} 为真 ⇒ <b>空槽反而通过</b>。
 *       这就是「没放物质模块它也能工作」的机制（门槛失效，而且是"越缺东西越放行"）。</li>
 * </ol>
 * <p>⚠️ 本条里"0 是怎么来的"和"那个 {@code 1x } 前缀是谁传进来的"<b>是两件事</b>：
 * 前者由 {@code getOrDefault} 的静默语义决定（本类的问题，已修）；
 * 后者来自脚本侧的 {@code "Nx &lt;物品id&gt;"} 写法（{@code shanhai_pf_recipes.js} §6，<b>本轮未改</b>）。
 * 本类的修法<b>不依赖</b>去指认后者——见 §6.3。
 *
 * <h3>6.3 修法一：把 {@code "Nx <id>"} 写法在 jar 侧也认下来（{@link #normalizeModuleId}）</h3>
 * <p>老山海的配方侧写法本来就是 {@code "Nx <物质模块物品id>"}（由它的引擎在 jar 侧切分），
 * 本工程的 KJS 也照抄了这个写法。既然脚本与条件之间<b>隔着一层写法</b>，就在条件里把这层写法认下来：
 * 剥掉数字 + {@code x/X} 前缀后再查表。⇒ 两种入参（{@code "1x id"} 与 {@code "id"}）<b>都得到 1</b>。
 *
 * <h3>6.4 修法二：查不到的 id <b>不再静默当成 0</b>（fail-closed ＋ 一次性 WARN）</h3>
 * <p>🔴 这是本轮最重要的语义变更，<b>刻意选的</b>：
 * <ul>
 *   <li>查不到时<b>放行</b>（旧行为：门槛 0 ⇒ 空槽也通过）= 门槛失效 = 本项目最忌讳的「悄悄不发生」；</li>
 *   <li>查不到时<b>拦下</b>（新行为：门槛 {@link #UNRESOLVABLE_GATE} ⇒ 任何模块等级都过不了）
 *       ＋ 一条可 grep 的 WARN 点名那个 id。</li>
 * </ul>
 * ⇒ 一个写错的 id 从此是「配方跑不起来 ＋ 日志点名」，而不是「配方偷偷不需要模块」。
 * <p>⚠️ 唯一例外：{@code moduleId} 为空串（= {@link #createTemplate()} / GTCEu 的 {@code createDefault()}
 * 造出来的模板实例，它<b>本来就没有配置</b>）⇒ 同样 fail-closed，但<b>不</b>打 WARN（那不是配置错误）。
 *
 * <h3>6.5 修法三：提示行改成中文名（用户要求）</h3>
 * <pre>
 *   改前: 模块要求: 1x shanhai:introductory_material_module（等级 &gt;= 0）
 *   改后: 模块要求: 1× 入门物质模块（等级 ≥ 1）
 * </pre>
 * <ul>
 *   <li>名字 = {@code new ItemStack(item).getHoverName()} —— 这就是"标准做法"：
 *       它内部是 {@code Component.translatable(item.getDescriptionId())}，
 *       即 <b>lang 键 {@code item.shanhai.introductory_material_module}</b>，
 *       客户端用 {@code assets/shanhai/lang/zh_cn.json} 渲成「入门物质模块」。</li>
 *   <li>数量位 = 构造器第二个实参（{@code "1x"} 里那个 1），渲染成 {@code 1× }。</li>
 *   <li>⚠️ <b>诚实边界</b>：专用服务端<b>不加载客户端 lang</b>，所以在冒烟日志里这行会打印出
 *       <b>lang 键本身</b>（{@code item.shanhai.introductory_material_module}）而不是中文。
 *       这不是没修好，而是"键 → 值"的解析发生在客户端；证明方式 = 键 + 反读 lang 表（见 §6.6）。</li>
 * </ul>
 *
 * <h3>6.6 本轮的机器可验判据</h3>
 * <pre>
 *   ① 门槛值   : requiredLevelForGate() == 1（对 "1x shanhai:introductory_material_module" 与
 *                "shanhai:introductory_material_module" 两种入参都成立）
 *   ② 空槽     : meetsLevelRequirement(0, 1) == false
 *   ③ 有模块   : meetsLevelRequirement(1, 1) == true ／ meetsLevelRequirement(3, 1) == true
 *   ④ 中文名   : 提示行里的名字 == lang 键 item.shanhai.introductory_material_module，
 *                且该键在 jar 的 zh_cn.json 里 == 「入门物质模块」（反读核对）
 *   ⑤ 走的哪条 : 日志 [SHANHAI-PF] module-level-condition available=true ⇒ 走等级门槛、催化剂形态已停用
 * </pre>
 *
 * <h2>7. 🔴🔴 2026-10-01 二次修正：「模块要求」那一行露码的【真根因】= 这条渲染路径根本不经过解析器</h2>
 *
 * <h3>7.1 现象（用户原话 ＋ 图1 原文）</h3>
 * <pre>
 *   用户：「2：通过，但是字又露码了，如图1，这不就是抄老的代码吗，怎么能搞出这么多事」
 *   图1（JEI【原初世线切割】配方页底部那一行，逐字）：
 *     &amp;$ultimateRainbow-模块要求：1× 物质推演模块（等级 ≥ …
 *   ⇒ 码本身被画了出来；而**同一个槽位**右下角那个 18×18 展示槽是好的（那条走另一条渲染路径）。
 * </pre>
 *
 * <h3>7.2 取证：四段 `javap -p -c` 原文（不是推断）</h3>
 * <pre>
 *   ① gtceu-1.20.1-1.4.4.jar  com.gregtechceu.gtceu.integration.GTRecipeWidget
 *        :553  invokevirtual  RecipeCondition.getTooltips:()Lnet/minecraft/network/chat/Component;
 *        :559  goto 529                                  ← getTooltips() == null 时【整条不加】
 *        :640  new           class com/lowdragmc/lowdraglib/gui/widget/LabelWidget
 *        :644  iconst_3 / :645 getfield xOffset / :649 isub
 *        :655  invokevirtual  RecipeCondition.getTooltips()
 *        :660  invokeinterface Component.getString:()Ljava/lang/String;     ← 🔴 降级成 String
 *        :665  invokespecial  LabelWidget."&lt;init&gt;":(IILjava/lang/String;)V  ← 🔴 String 构造器
 *
 *   ② ldlib-forge-1.20.1-1.0.33.b.jar  com.lowdragmc.lowdraglib.gui.widget.LabelWidget
 *        LabelWidget(IILjava/lang/String;)V  :4 invokedynamic  lambda$new$0:(String)Supplier
 *                                           :9 invokespecial  LabelWidget(IILjava/util/function/Supplier;)V
 *        ⇒ 走 Supplier 那条 ⇒ 字段 component 恒为 null（Component 构造器才会 putfield component）
 *
 *   ③ 同一个类  drawInBackground(GuiGraphics,int,int,float)V
 *        :24 getfield component / :27 ifnonnull 121     ← 🔴 两条分支在这里分开
 *        【String 分支】:85 aload_1(GuiGraphics) :93 行文本 :~108
 *                       invokevirtual GuiGraphics.m_280056_:(Font;Ljava/lang/String;IIIZ)I
 *        【Component 分支】:146 invokevirtual GuiGraphics.m_280614_:(Font;Component;IIIZ)I
 *
 *   ④ forge-1.20.1-47.4.16_mapped_parchment_2023.09.03-1.20.1.jar  net.minecraft.client.gui.Font
 *        drawInBatch(String,FFIZLorg/joml/Matrix4f;Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/client/gui/Font$DisplayMode;II)I
 *          :22 invokevirtual drawInBatch:(Ljava/lang/String;…IIZ)I         ← 转 11 参 String 重载
 *        drawInBatch(String,….boolean) :20 invokevirtual drawInternal:(Ljava/lang/String;…Z)I
 *        private drawInternal(String,…) :51 / :81 invokevirtual renderText:(Ljava/lang/String;…Z)F
 *        ⇒ **全程没有一次 drawInBatch(FormattedCharSequence,…)**
 *   而 com.shanhai.mixin.ShanhaiFontStyleMixin 只注入
 *     {@code drawInBatch(Lnet/minecraft/util/FormattedCharSequence;…)} ＋ 三个 width 重载
 *   ⇒ ⇒ 🔴 **我们的解析器（ShanhaiTextParser.parse）在这条路径上【一次都不会被调用】**
 *        ⇒ 原版渲染把 {@code &$ultimateRainbow-} 当 7+15 个普通字符画在屏幕上。
 * </pre>
 *
 * <h3>7.3 三条推论（逐条推翻/确认上一轮的说法）</h3>
 * <ol>
 *   <li>🔴 <b>上一轮的判据不足以证明"不露码"</b>：它只把拼好的字符串喂给 {@code ShanhaiTextParser}
 *       看 {@code ours=true} —— 而实机上这个串<b>压根不会被送进解析器</b>。
 *       ⇒ 判据在上一轮全绿、实机照样露码，这不是运气不好，是判据选错了层。</li>
 *   <li>⚠️ <b>2026-09-30 那次露码</b>（{@code §b模块要求：1× &$electric-创始现实修改模块 §7（等级 ≥ 17）}）
 *       <b>同一条根因</b>，不是"§ 与 &$ 混用"那条规则（那条规则在这次事件里根本没被触发）。
 *       那次把名字先剥码的修法<b>是对的</b>（去掉 {@code &} ⇒ 安全），只是理由写错了 ——
 *       {@code ShanhaiTextParser#stripStyleCode} 的注释已同步更正。</li>
 *   <li>⚠️ <b>上一版那条"零 {@code &}"硬规则，本版【有条件放宽】</b>（⛔ 不是取消，别当成取消了）：
 *       <ul>
 *         <li><b>放宽的依据</b>：String 家族的注入点已补（见 §7.4 甲），
 *             实测 LDLib 那条链（{@code m_280056_} → {@code drawString(Font,String,float,…)} → {@code m_272078_}）
 *             <b>正好落在新注入点上</b> ⇒ 含 {@code &$…-} 的文本在这条路上<b>会</b>被解析器接管。</li>
 *         <li>🔴 <b>仍然要守的三种情形</b>（任一条不成立就别写 {@code &}）：
 *             ① 目标文本走的是 <b>{@code Font.drawInBatch8xOutline}</b>（描边路径，
 *                原版 {@code SignRenderer} / Jade {@code ProgressStyle} 用它，见混入类注释 §2.5）；
 *             ② 目标文本会<b>离开客户端</b>（进网络包 / 存档 / 日志 / 服务端拼接）——
 *                解析器只活在客户端；
 *             ③ 一行里同时出现 {@code §} 与 {@code &$…-}（{@code parse()} 判 {@code ours=false} ⇒ 交回原版 ⇒ 露码）。</li>
 *         <li>✅ 本行的三个出口 {@code displayName} / {@code gateText} / {@code plainDisplayName} /
 *             {@code plainGateText} 仍然<b>照旧过 {@link #noAmpEcho(String)}</b> —— 理由是第 ③ 条：
 *             若名字里自带 {@code &$…-}，拼进那行就成了"码中码"，parser 的正文里会多一个 {@code &}。
 *             ⇒ 所以"正文里零 {@code &}"这条不变式<b>继续成立</b>（离线读数见交接文档 §11.4）。</li>
 *       </ul></li>
 * </ol>
 *
 * <h3>7.4 🔴 2026-10-01 第三轮：用户【选了甲】并已落地（本节上一版写着"两个选项、本轮一个都没做"）</h3>
 * 用户原话（逐字）：<b>「启动就崩其实是最好修的，要是莫名其妙崩了才难修」</b>
 * ⇒ 注入点保留 {@code require = 1}（不降级成静默失效），风险改用<b>离线判据</b>排除。
 * <ol>
 *   <li><b>甲（已落地）</b>：给 <b>{@code Font.drawInBatch(String, …, boolean)}（11 参那个）</b>补了一条同款注入
 *       （{@code ShanhaiFontStyleMixin#shanhai$styleDrawInBatchString}）＋ 渲染器加了
 *       {@code ShanhaiFontStyleRenderer#renderString} ⇒ 本行<b>恢复</b>为
 *       {@code &$ultimateRainbow-模块要求：…}（= 用户点单的"归一那串流动彩虹"）。
 *       <pre>
 *   &#64;Inject(method = "drawInBatch(Ljava/lang/String;FFIZLorg/joml/Matrix4f;"
 *           + "Lnet/minecraft/client/renderer/MultiBufferSource;"
 *           + "Lnet/minecraft/client/gui/Font$DisplayMode;IIZ)I",
 *           at = @At("HEAD"), cancellable = true, require = 1)
 *       </pre>
 *       🔴 <b>上一版这里写的描述符是【10 参】那个，是错的</b>：10 参只转调 11 参，
 *       而 LDLib 实际走的是 {@code GuiGraphics.m_280056_} → {@code drawString(Font,String,float,…)}
 *       → <b>直调 11 参</b>（运行时 SRG 原文 {@code :40 invokevirtual Font.m_272078_}）
 *       ⇒ 只注 10 参<b>一条都罩不住</b>。完整链与实测见 {@code handoff\outbound\模块要求-露码修正.md} §11。</li>
 *   <li><b>乙（未采用）</b>：整行两级原版色（{@code §b} 题头 ＋ {@code §7} 数值）。
 *       ⚠️ <b>它仍然是"零风险"的回退档</b>：若首构建/首启动出问题，把 {@link #getTooltips()} 换回
 *       {@code Component.literal("§b模块要求：")…} 三行即可（原文保留在 {@link #getTooltips()} 的 javadoc 里）。</li>
 * </ol>
 * 🔴 <b>两条独立的风险轴都已在离线上判死</b>（读数与 {javap} 原文见交接文档 §11.2）：
 * ① 描述符在目标类里的<b>匹配数 == 1</b>（开发 jar ＋ SRG jar 各一遍）；
 * ② 该 dev 描述符在喂给注解处理器的 TSRG 里<b>有 SRG 映射</b>（{@code drawInBatch(String,…,IIZ)} → {@code m_272078_}）。
 * <p>⚠️ <b>仍未证实</b>：真构建后 jar 里能不能看到合并进去的注入、以及实机观感 —— 需要构建＋启动，本轮红线禁止。
 * 交接文档里给了构建后立刻可跑的 {@code javap} 验收命令。
 */
public class ModuleLevelCondition extends RecipeCondition {

    public static final Codec<ModuleLevelCondition> CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    Codec.STRING.fieldOf("module_id").forGetter(c -> c.moduleId),
                    Codec.INT.fieldOf("level").forGetter(c -> c.requiredLevel)
            ).apply(instance, ModuleLevelCondition::new)
    );

    public static final RecipeConditionType<ModuleLevelCondition> TYPE = new RecipeConditionType<>(
            () -> new ModuleLevelCondition("", 0),
            ModuleLevelCondition.CODEC
    );

    /** 模块物品 id（如 {@code shanhai:introductory_material_module}）。 */
    public final String moduleId;

    /**
     * KJS 写法 {@code "Nx <物品id>"} 里的 <b>N</b>。
     * <p>🔴 <b>本工程不拿它当门槛，也不拿它当需求数量</b> —— 见类注释 §1/§3。
     * 保留字段只为：① 与上游 JSON / 网络协议逐字同形（{@code level} 键）；② 显示（{@code ×N}）。
     */
    public final int requiredLevel;

    // ====== 静态注册表：绕过 KubeJS 序列化/反序列化问题（上游原样保留，本工程无调用者） ======
    /** 配方ID → 模块条件列表 */
    private static final java.util.Map<String, java.util.List<ModuleLevelCondition>> REQUIREMENTS =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** 配方注册时调用，将条件存入内存表（⚠️ 见类注释 §5：本工程当前无调用者、不参与判定）。 */
    public static void register(String recipeId, ModuleLevelCondition cond) {
        REQUIREMENTS.computeIfAbsent(recipeId, k -> new java.util.ArrayList<>()).add(cond);
    }

    /** 每次配方重载前清空上一轮条件，避免修改后的旧需求继续残留。 */
    public static void clearRequirements() {
        REQUIREMENTS.clear();
    }

    /** 运行时按完整配方 ID 精确查询，禁止相似配方之间串条件。 */
    public static java.util.List<ModuleLevelCondition> getRequirements(String recipeId) {
        if (recipeId == null || recipeId.isEmpty()) return null;
        return REQUIREMENTS.get(recipeId);
    }

    public ModuleLevelCondition(String moduleId, int level) {
        super(false);
        this.moduleId = moduleId;
        this.requiredLevel = level;
    }

    // ═══════════════════════════ 判定（用户 2026-09-26 定案的简化版） ═══════════════════════════

    /**
     * 本条件要的等级 —— <b>由 {@code moduleId} 这个物品在等级表里的档位决定</b>（类注释 §3 的读法 (a)）。
     * <p>{@code PrimordialModuleMachine.MODULE_LEVELS} 是<b>全工程唯一</b>的"物品 id → 等级"映射
     * （1..17；见 {@code PrimordialModuleMachine#getModuleLevelById} 的 javadoc），
     * 本类<b>不另建一套</b>。
     * <p>⚠️ 若将来要改成读法 (b)（用构造器第二个实参当门槛），把本方法的返回值换成 {@code requiredLevel} 即可 ——
     * <b>但那是语义变更，需先拿用户裁决</b>。
     *
     * <p>🔴 <b>2026-09-26 修复</b>（类注释 §6.2）：查表前先过 {@link #effectiveModuleId()}（剥掉
     * {@code "Nx "} 配方侧前缀）；<b>查不到时不再返回 0</b>，而是 {@link #UNRESOLVABLE_GATE} ＋ 一条
     * 点名 id 的 WARN。原先"查不到 → 0"让门槛<b>语义反转</b>（0 &gt;= 0 ⇒ 空槽通过），
     * 这就是用户看到的「没放物质模块它也能工作」。
     */
    public int requiredLevelForGate() {
        final String id = effectiveModuleId();
        if (id.isEmpty()) {
            // createTemplate() / GTCEu createDefault() 造出来的"未配置"模板：fail-closed，但不打 WARN。
            return UNRESOLVABLE_GATE;
        }
        final int level = PrimordialModuleMachine.getModuleLevelById(id);
        if (level <= 0) {
            shanhai$warnUnknownIdOnce(id);
            return UNRESOLVABLE_GATE;
        }
        return level;
    }

    /**
     * 🔴 查不到 id 时的门槛（<b>fail-closed</b>，见类注释 §6.4）。
     *
     * <p>取值 = {@link Integer#MAX_VALUE}：它<b>永远大于</b>任何槽里等级（槽里等级最大 17），
     * ⇒ {@link #meetsLevelRequirement} 恒 false ⇒ 配方被拦下（而不是被放行）。
     * <p>⚠️ 为什么不返回 0：0 = "无门槛"，那正是本轮要修的 bug —— 一个写错的 id 会<b>静默</b>
     * 让门槛消失。这里的取舍是<b>宁可跑不起来，也不许悄悄放行</b>。
     */
    public static final int UNRESOLVABLE_GATE = Integer.MAX_VALUE;

    /**
     * 把配方侧写法 {@code "Nx <物品id>"}（老山海 / 本工程 KJS 的写法）剥成<b>纯物品 id</b>。
     *
     * <pre>
     *   "1x shanhai:introductory_material_module" -&gt; "shanhai:introductory_material_module"
     *   "shanhai:introductory_material_module"    -&gt; 原样（没有前缀）
     *   "4x shanhai:basic_material_module"         -&gt; "shanhai:basic_material_module"
     *   "1X shanhai:basic_material_module"         -&gt; "shanhai:basic_material_module"（大写 X 也认）
     * </pre>
     * ⚠️ 只剥【数字开头 + x/X】这一种前缀，不做别的猜测：一个真实物品 id 不可能以数字开头
     * （{@code ResourceLocation} 的路径本来就不允许数字开头之外的怪写法，且本表 17 个 id 都不以数字开头）。
     *
     * @param raw 原样入参，可为 null
     * @return 纯 id；入参为 null 时返回空串（不是 null，调用方不必再判空）
     */
    public static String normalizeModuleId(@Nullable String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.trim();
        int i = 0;
        while (i < s.length() && s.charAt(i) >= '0' && s.charAt(i) <= '9') {
            i++;
        }
        if (i > 0 && i < s.length() && (s.charAt(i) == 'x' || s.charAt(i) == 'X')) {
            s = s.substring(i + 1).trim();
        }
        return s;
    }

    /** {@link #moduleId} 去掉配方侧数量前缀之后的<b>真物品 id</b>（本类所有查表都用它）。 */
    public String effectiveModuleId() {
        return normalizeModuleId(moduleId);
    }

    /** 门槛是否可解（false = id 写错了 / 没配置；此时门槛 = {@link #UNRESOLVABLE_GATE}）。 */
    public boolean isGateResolvable() {
        String id = effectiveModuleId();
        return !id.isEmpty() && PrimordialModuleMachine.getModuleLevelById(id) > 0;
    }

    /** 显示用门槛文本：可解 = 数字；不可解 = {@code ?（未识别的物质模块 id: …）}。 */
    private String gateText() {
        if (isGateResolvable()) {
            return String.valueOf(PrimordialModuleMachine.getModuleLevelById(effectiveModuleId()));
        }
        final String id = effectiveModuleId();
        // 🔴 2026-10-01：回显的 id 必须过 noAmpEcho —— 这一行会被送进 LDLib 的 String 标签
        //    （见类注释 §7：那条路径不经过解析器）⇒ 从这里漏出一个 `&` 就等于让玩家看见原始码。
        //    门可解时走的是纯数字分支，所以这一步只影响"畸形入参"的回显。
        return "?（未识别的物质模块 id：" + (id.isEmpty() ? "未配置" : noAmpEcho(id)) + "）";
    }

    /**
     * 🔴 <b>回显 module_id 时用的"绝对零 {@code &}"净化器</b>（2026-10-01 新增）。
     *
     * <pre>
     *   ① 先过 {@link ShanhaiTextParser#stripStyleCode(String)}：剥掉【以 &$ 开头】的 `&$样式名-` 前缀码；
     *   ② 若剥完还残留 `&`（例如脚本侧写成 `"1x &$golden-x"`、或 `&$` 不在串首），
     *      把残留的 `&` <b>整个删掉</b>。
     * </pre>
     * <p><b>为什么敢删</b>：这一步的入参是<b>物质模块的物品 id</b>；合法 id 是
     * {@code ResourceLocation}（{@code [a-z0-9_.-]} ＋ 一个 {@code :}）——<b>里面不可能有 {@code &}</b>
     * ⇒ 删掉 `&` 不丢失任何真信息，换来的是那条提示行的<b>绝对不变式「零 {@code &}」</b>
     * （这条不变式 = 它不会被原版把码画出来的充分条件，见类注释 §7.3）。
     * <p>⚠️ 它是<b>显示层</b>的净化器，<b>不参与任何判定</b>：门槛值的计算一条都不经过它
     * （{@link #requiredLevelForGate()} 用的是 {@link #effectiveModuleId()} 的原值）。
     */
    private static String noAmpEcho(@Nullable String raw) {
        if (raw == null) {
            return "";
        }
        final String stripped = ShanhaiTextParser.stripStyleCode(raw);
        return stripped.indexOf('&') < 0 ? stripped : stripped.replace("&", "");
    }

    /**
     * 查不到 id 时只打一次的可 grep WARN（避免每 tick 刷屏）。
     * <p>它是「id 写错了」这件事在日志里的<b>唯一</b>痕迹 —— 没有它，这个失败和"门槛本来就是 0"长得一样。
     */
    private void shanhai$warnUnknownIdOnce(String normalizedId) {
        final String key = normalizedId;
        if (!shanhai$unknownIdWarned.add(key)) {
            return;
        }
        ShanhaiMod.LOGGER.warn("[SHANHAI-MODULE-LEVEL] 条件 `module_level` 的 module_id 不是 17 个物质模块之一 ⇒ "
                        + "门槛按【不可解】处理（= 任何等级都过不了，配方会被拦下，不会静默放行）。"
                        + "收到的原文 =《{}》，剥掉数量前缀后 =《{}》，"
                        + "要求等级字段（构造器第二个实参，只用于显示）= {}。"
                        + "正确写法：module_id 必须是 PrimordialModuleMachine.MODULE_LEVELS 里那 17 个 id 之一"
                        + "（例如 shanhai:introductory_material_module），可带 `Nx ` 前缀。",
                moduleId, key, requiredLevel);
    }

    /** 已经 WARN 过的 id 集合（每个 id 只报一次）。 */
    private static final java.util.Set<String> shanhai$unknownIdWarned =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * 🔴 <b>纯函数形态的判定式（本类唯一的判据）</b> —— 拆出来是为了能被离线装置直接调用取证
     * （{@code _batchfix-verify\ModuleLevelGateProof.java}；{@code checkModuleLevel} 需要一台真机器，离线跑不了）。
     *
     * <pre>
     *   槽里等级 &gt;= 要求等级  ⇒ 通过
     *   空槽（槽里等级 = 0）⇒ ❌ 不通过（因为要求等级恒 ≥ 1，见 {@link #requiredLevelForGate()}）
     * </pre>
     * ⚠️ {@code installedLevel} 是<b>槽里那个模块自己的等级</b>，不是数量；数量<b>不参与</b>。
     */
    public static boolean meetsLevelRequirement(int installedLevel, int requiredLevel) {
        return installedLevel >= requiredLevel;
    }

    /**
     * 🔴 模块侧判定 —— <b>本工程唯一真正生效的那条分支</b>（见类注释 §1/§4）。
     *
     * <pre>
     *   槽空  ⇒ getMatterModuleLevel() = 0 ⇒ 0 &gt;= 要求等级(≥1) ⇒ false
     *   有模块 ⇒ 该模块自己的等级 &gt;= 要求等级 ⇒ true
     * </pre>
     */
    public boolean checkModuleLevel(@Nullable MetaMachine machine) {
        // ── 模块机器路径（本工程 PrimordialModuleRecipeLogic 会走到这里）：只看本机自己的物质模块槽 ──
        if (machine instanceof PrimordialModuleMachine module) {
            return meetsLevelRequirement(module.getMatterModuleLevel(), requiredLevelForGate());
        }

        // ── ⛔ 主机聚合分支【未移植】（见类注释 §4）────────────────────────────────
        //    上游 hostEquivalentCountSatisfies(...) 依赖 IModularMachineHost / IModularMachineModule /
        //    PrimordialOmegaEngineMachine#getModuleSet / #getEquivalentModuleCountForLevel ——
        //    本工程一个都没有。这里【不静默放行】，而是判 false + 一条只出现一次的 WARN。
        if (!shanhai$notPortedWarned) {
            shanhai$notPortedWarned = true;
            ShanhaiMod.LOGGER.warn("[SHANHAI-MODULE-LEVEL] 条件 `module_level`({}，门槛等级 {}) 被一台【非模块机】求值 ⇒ "
                            + "判 false。原因：老山海的主机聚合分支（16 模块位合并）本轮【未移植】"
                            + "（依赖 IModularMachineHost / getEquivalentModuleCountForLevel，本工程没有）。"
                            + "实测影响应为 0：该条件目前只挂在 shanhai:pf/photon 上，其类型 photon_siphon 只被模块挂载。"
                            + "若你看到本行的机器本应跑这条配方 ⇒ 那就是原因，需要先补主机聚合分支。machine={}",
                    moduleId, requiredLevelForGate(), machine == null ? "null" : machine.getClass().getName());
        }
        return false;
    }

    /** 非模块机路径被走到时只打一次的闸门（避免每 tick 刷屏）。 */
    private static boolean shanhai$notPortedWarned = false;

    /**
     * 条件求值入口（{@code GTRecipe#checkConditions} 会调到这里）。
     *
     * <p>🔴 <b>与上游刻意不同</b>：上游这里只走主机聚合分支，本工程走
     * {@link #checkModuleLevel}（模块分支 + 未移植时的响亮失败），理由见类注释 §4。
     *
     * <p>{@code recipeLogic} 为 {@code null} ⇒ 没有任何机器上下文可判（例如纯展示 / 校验调用），
     * 此时<b>不拦</b>（返回 true）：那不是"某台机器上的准入判定"，判 false 只会把配方标成非法。
     */
    @Override
    public boolean test(GTRecipe recipe, @Nullable RecipeLogic recipeLogic) {
        if (recipeLogic == null) {
            return true;
        }
        return checkModuleLevel(recipeLogic.getMachine());
    }

    @Override
    public RecipeConditionType<?> getType() {
        return TYPE;
    }

    /**
     * 🔴 <b>2026-10-01：「模块要求」那一行的文本样式码 = 世线残片·归一 用的那一个。</b>
     *
     * <h2>用户原话（逐字）</h2>
     * <blockquote>
     * 「2：通过，但是可以换个颜色，感觉这样区分度不太大，可以换成【世线残片-归一】的那个颜色」
     * </blockquote>
     *
     * <h2>颜色从哪来（🔴 从它自己的 lang 原文取，不是猜的）</h2>
     * <pre>
     * 文件：shanhai-rewrite\src\main\resources\assets\shanhai\lang\zh_cn.json  第 216 行
     * 原文：  "item.shanhai.thread_shard_6": "&$ultimateRainbow-世线残片·归一",
     * </pre>
     * ⇒ 样式名 = {@code ultimateRainbow}
     * ⇒ 色板 = {@link com.shanhai.common.text.ShanhaiTextPalette} 里
     *    {@code reg("ultimateRainbow", ULTIMATE_RAINBOW_RGB, false)}
     *    （{@code ShanhaiTextPalette.java:270}，常量在 {@code :134-145}，共 <b>40 色</b>）：
     * <pre>
     *   0xFF4444, 0xFF5B2D, 0xFF7117, 0xFF8800, 0xFF9900, 0xFFAA00, 0xFFBB00, 0xFFCC17,
     *   0xFFDD2D, 0xFFEE44, 0xE3F444, 0xC6F944, 0xAAFF44, 0x88FF44, 0x66FF44, 0x44FF44,
     *   0x44FF66, 0x44FF88, 0x44FFAA, 0x44F4C6, 0x44E8E3, 0x44DDFF, 0x44C1FF, 0x44A4FF,
     *   0x4488FF, 0x5571FF, 0x665BFF, 0x7744FF, 0x8E44FF, 0xA444FF, 0xBB44FF, 0xD244FF,
     *   0xE844FF, 0xFF44FF, 0xFF4FE8, 0xFF5BD2, 0xFF66BB, 0xFF71AA, 0xFF7D99, 0xFF8888
     * </pre>
     * （与 {@code item.shanhai.universal_parallel_overdriver}「寰宇并行超限器」是同一个样式 —— 那一族都这么写。）
     *
     * <h2>⚠️ 如实交代三件事</h2>
     * <ol>
     *   <li><b>这不是一个静态 RGB，是一段会流动的色板</b>（40 色 × 按字符错位 + 按时间推进，
     *       算法见 {@code ShanhaiFontStyleRenderer}）。归一的物品名本身也是这么渲染的
     *       ⇒ 这一行将与归一的物品名<b>观感逐字一致</b>（这就是用户点的那件事）。
     *       若用户其实想要"一个固定不动的颜色"，把 {@link #REQ_LINE_STYLE} 换成
     *       {@code "§b"} / {@code "§6"} 这类原版码即可 —— <b>一行改动</b>。</li>
     *   <li><b>本码里没有任何效果字符</b>（没有 {@code ?} glitch、没有 {@code *} floatX、没有 {@code ~} 等）
     *       ⇒ 字<b>不移位、不闪烁</b>，只有颜色随色板变化。</li>
     *   <li>🔴 用 {@code &$…-} 的前提是整行不许再出现 {@code §}（{@code ShanhaiTextParser.parse}
     *       遇到"同一行既有 {@code §} 又有 {@code &$}"会判 {@code ours=false} 交回原版
     *       ⇒ <b>美化码会原样画出来</b>）。⇒ 原来那三段 {@code §b模块要求：} ＋ 名字 ＋
     *       {@code §7（等级 ≥ N）} 当时并成了<b>一个 body</b>，颜色不再分两级。</li>
     * </ol>
     *
     * <h2>🔴 状态：<u>2026-10-01 第三轮起【重新启用】</u>（此前一轮被标为"已停用"）</h2>
     * 停用的那一轮里，本常量一旦被 {@link #getTooltips()} 使用就会露码 ——
     * 因为那一行走的是 LDLib {@code LabelWidget(String)} ⇒ {@code Font.drawInBatch(String,…)}
     * ⇒ <b>绕过当时唯一的注入点（FCS）</b>，见类注释 §7.2 的四段字节码。
     * <p>🔴 <b>2026-10-01 第三轮</b>补上了 String 家族的注入点
     * （{@code ShanhaiFontStyleMixin#shanhai$styleDrawInBatchString}，注的是 {@code m_272078_}），
     * 且实测 LDLib 那条链（{@code m_280056_} → {@code drawString(Font,String,float,…)}）<b>正好落在它上面</b>
     * ⇒ 本常量由 {@link #getTooltips()} 使用<b>不再露码</b>（前提：整行无 {@code §}，见上面第 3 条）。
     * <p>⚠️ 代价（照旧保留）：这会<b>接管任意调用方</b>画出的、长得像 {@code &$样式名-正文} 的裸字符串
     * ⇒ 全局副作用，如实记在 {@code ShanhaiFontStyleMixin} 类注释 §2.5。
     */
    private static final String REQ_LINE_STYLE = "&$ultimateRainbow-";

    /**
     * 配方页那一行「模块要求」（{@link com.gregtechceu.gtceu.integration.GTRecipeWidget} 会把它
     * 变成一条 {@code LabelWidget}，见该类 {@code :115-129} 的反编译原文：
     * {@code new LabelWidget(3 - xOffset, yOffset, condition.getTooltips().getString())} ——
     * <b>外面不加任何前缀码</b>，所以我们这一行整行就是它自己的正文）。
     *
     * <h2>版本史（逐字，别再把上面那条"只要没有 § 就安全"当成充分条件）</h2>
     * <pre>
     *   2026-09-30 之前 : §b模块要求： 1× &$electric-创始现实修改模块 §7（等级 ≥ 17）  ← 露码（用户截图）
     *   2026-09-30      : §b模块要求： 1× 创始现实修改模块           §7（等级 ≥ 17）  ← 名字先剥码 ⇒ 整行零 &
     *   2026-10-01 首版 : &$ultimateRainbow-模块要求：1× 物质推演模块（等级 ≥ 3）     ← 又露码（用户图1）
     *   2026-10-01 二版 : §b模块要求： 1× 物质推演模块               §7（等级 ≥ 3）  ← 整行零 &（保守档）
     *   2026-10-01 三版 : &$ultimateRainbow-模块要求：1× 物质推演模块（等级 ≥ 3）     ← 用户选甲
     *   2026-10-03 四版 : &$ultimateRainbow-模块要求：1× 物质推演模块                 ← 🔴 本版：用户点单去掉等级段
     * </pre>
     *
     * <h2>🔴 2026-10-01 第三轮（历史留档：当时的最新版，已由下方 2026-10-03 第四轮取代）：
     * 走【甲】—— 恢复归一彩虹，前提已用离线判据判死</h2>
     * 上一版之所以退回纯 {@code §}，是因为那一行走的渲染路径<b>没有</b>我们的注入点。
     * 本版把那条注入点补上了（{@code Font.drawInBatch(String, …, boolean)} 11 参那个，
     * 见 {@link com.shanhai.mixin.ShanhaiFontStyleMixin} 类注释 §2 的家族图与
     * {@code handoff\outbound\模块要求-露码修正.md} §11 的实测调用链）：
     * <pre>
     *   GTRecipeWidget:660  Component.getString()          ⇒ 文本降级成 java.lang.String
     *   GTRecipeWidget:665  LabelWidget.&lt;init&gt;(IILjava/lang/String;)V
     *   LabelWidget.drawInBackground:108 → GuiGraphics.m_280056_:(Font;String;IIIZ)I
     *      → GuiGraphics.drawString(Font,String,float,…) :40 → Font.m_272078_:(String;…IIZ)I
     *   ⇒ 🔴 m_272078_ = 那一轮新注入点 ⇒ 解析器在这条路上【会被调用】（改前不会）
     * </pre>
     * <b>回退办法（一行，随时可用）</b>：把本方法体换回上一版的三行
     * <pre>
     *   return Component.literal("§b模块要求：").append(displayNameWithCount())
     *           .append(Component.literal(" §7（等级 ≥ " + gateText() + "）"));
     * </pre>
     * ⚠️ <b>2026-10-03 起这条回退办法会同时把等级那一段加回来</b>（用户同日已点单去掉它）
     * ⇒ 若只是因为"彩虹色出问题"要回退，请<b>保留现在这一行正文、只换样式码</b>
     * （把 {@link #REQ_LINE_STYLE} 换成 {@code "§b"} 或 {@code "§6"}），别用上面这三行。
     * <p>🔴 <b>若那次构建的注入没生效</b>，{@code require = 1} 的表现是<b>启动即崩</b>（不是静默露码）——
     * 这正是用户 2026-10-01 拍板要的失败形态（原话：「启动就崩其实是最好修的，要是莫名其妙崩了才难修」）。
     * <p>🔴 <b>2026-10-03 第四轮（本版）：用户点单【去掉「（等级 ≥ N）」那一段】</b>
     * <blockquote>「把后面等级&gt;=多少去了，挡视线了」</blockquote>
     * ⇒ 那一行现在<b>只画「模块要求：1× 虚像物质模块」</b>，等级数字不再出现在配方页上。
     * <b>判定一个字都没动</b>（门槛照旧拦，见 {@link #test}）；被去掉的只是"把它写出来"。
     *
     * <pre>
     *   改前（2026-10-01 三版）: &amp;$ultimateRainbow-模块要求：1× 虚像物质模块（等级 ≥ 4）
     *   改后（2026-10-03 四版）: &amp;$ultimateRainbow-模块要求：1× 虚像物质模块
     * </pre>
     * ⚠️ 尾串检查：去的是<b>整段</b>（连同它前面的括号一起），所以改后既没有空括号，
     *    也没有尾随空格（{@code plainNameWithCount()} 后面直接结束）⇒ 上面"整行无 §"的前提不变。
     * <p>⚠️ 代价（如实记）：{@code plainGateText()} 里那条「未识别的物质模块 id」标记也<b>不再画在这里</b>；
     *    那种配方仍会在加载期打 WARN（见本类 {@code [SHANHAI-MODULE-LEVEL]} 的日志），<b>不是静默失效</b>。
     */
    @Override
    public Component getTooltips() {
        // 甲方案：整行一个 `&$ultimateRainbow-` 正文（不许含 §，否则 parser 判 ours=false 交回原版 ⇒ 露码）。
        // 名字走 plain*（= noAmpEcho 过的串）⇒ 对任意 module_id 入参，正文里都不会混进第二个 `&`。
        // 🔴 2026-10-03：等级那一段（`（等级 ≥ N）`）已按用户点单删除 —— 只改显示，不动判定。
        return Component.literal(REQ_LINE_STYLE + "模块要求：" + plainNameWithCount());
    }

    public Component getFailTooltip() {
        return Component.literal("§c✗ 模块不足：").append(displayNameWithCount())
                .append(Component.literal(" §7（等级 ≥ " + gateText() + "）"));
    }

    public Component getPassTooltip() {
        return Component.literal("§a✓ 模块满足：").append(displayNameWithCount())
                .append(Component.literal(" §7（等级 ≥ " + gateText() + "）"));
    }

    /**
     * 提示行里的「数量 ＋ 名字」那一段：{@code 1× 入门物质模块}。
     *
     * <pre>
     *   改前（2026-09-26 之前）：1x shanhai:introductory_material_module     ← 裸 id，还是配方侧写法
     *   改后（本轮）            ：1× 入门物质模块                            ← 该物品的【显示名】
     * </pre>
     * <ul>
     *   <li>数量 = 构造器第二个实参（{@code "1x"} 里那个 1，{@link #requiredLevel}），只用于显示、不参与判定；
     *       为 0 时整段省略（用 {@code N× } 而不是 {@code Nx }）。</li>
     *   <li>名字 = {@link #displayName()}，即物品的显示名 Component（客户端按 lang 表渲成中文）。</li>
     * </ul>
     */
    private Component displayNameWithCount() {
        Component name = displayName();
        if (requiredLevel <= 0) {
            return name;
        }
        return Component.literal(requiredLevel + "× ").append(name);
    }

    // ═══════════════════════════════ 「模块要求」那一行的纯文本版本 ═══════════════════════════════
    //
    //  🔴 2026-10-01 新增（用户点单：那一行换成「世线残片·归一」的颜色）。
    //  为什么当时另起一套"纯 String"方法，而不能复用上面的 Component 版本：
    //    · Component 版本里写死着 `§7（未配置模块）` 这样的**原版色码**；
    //    · 而那一行当时整行用 `&$ultimateRainbow-` ⇒ 按当时的口径"一个 § 都不能有"。
    //  🔴🔴 2026-10-01 二次修正：上面那条口径被实机证伪（整行无 § 照样露码，真根因见类注释 §7）
    //      ⇒ 上一轮 getTooltips() 曾改回 Component + 纯 § 形态，这三个方法当时【无调用者】。
    //  🔴 2026-10-01 第三轮（本版）：用户选甲 ⇒ getTooltips() 换回
    //      `REQ_LINE_STYLE + "模块要求：" + plainNameWithCount() + "（等级 ≥ " + plainGateText() + "）"`
    //      ⇒ **这三个方法恢复为【在用】**（不再是备件）。之所以当初就留着它们，正是因为
    //      "甲方案落地时不必重新推导物品名那层" —— 这一轮兑现了。
    //  ⚠️ 两条路的物品名解析逻辑逐字相同，只是返回类型不同（String vs Component）——
    //     刻意不互相调用：Component → String 要走 getString()，会把 lang 解析提前到服务端，
    //     而 `§`-free 那一版只在配方页（客户端）用，形状最简单。
    //  🔴 2026-10-03 第四轮：用户点单去掉「（等级 ≥ N）」⇒ getTooltips() 现在只拼
    //      `REQ_LINE_STYLE + "模块要求：" + plainNameWithCount()`
    //      ⇒ 本小节里 **{@link #plainNameWithCount()} 仍在用；{@link #plainGateText()} 已无调用者**
    //        （备件，理由见它自己的 javadoc）。

    /** {@link #displayNameWithCount()} 的 §-free 版（{@code 1× 入门物质模块}）。<b>在用</b>（{@link #getTooltips()}）。 */
    private String plainNameWithCount() {
        final String name = plainDisplayName();
        return requiredLevel <= 0 ? name : requiredLevel + "× " + name;
    }

    /**
     * {@link #displayName()} 的 §-free 版：该模块的显示名，<b>既不含 {@code §} 也不含 {@code &$…-}</b>。
     *
     * <p>剥码走的是<b>本工程真的工具方法</b> {@link #noAmpEcho(String)}
     * （内部就是 {@link ShanhaiTextParser#stripStyleCode(String)} ＋ 删残留 {@code &}；
     * 不是这里手写的字符串处理）⇒ 与其它同族站点（{@code ModuleCatalystSlotUI} 的「催化剂：」行等）
     * 是同一条规则。
     * <p>解析不出物品时回退成<b>纯 id</b>（与 {@code displayName()} 同口径）；未配置时给一句中文说明。
     */
    private String plainDisplayName() {
        final String id = effectiveModuleId();
        if (id.isEmpty()) {
            return "（未配置模块）";
        }
        try {
            final ResourceLocation rl = ResourceLocation.tryParse(id);
            if (rl != null) {
                final Item item = ForgeRegistries.ITEMS.getValue(rl);
                if (item != null && item != Items.AIR) {
                    return noAmpEcho(new ItemStack(item).getHoverName().getString());
                }
            }
        } catch (Exception ignored) {
            // 注册表还没起来 / 物品不存在 ⇒ 回退到纯 id（下面那行），不影响任何判定。
        }
        return noAmpEcho(id);
    }

    /**
     * {@link #gateText()} 的 §-free 版。
     *
     * <p>⚠️ {@code gateText()} 的返回值只会是「纯数字」或「{@code ?（未识别的物质模块 id：…）}」两种形态
     * （后者已在 {@code gateText()} 内部过了一次 {@link #noAmpEcho(String)}），
     * 这里再过一次是同一条不变式的兜底：<b>这条行上一个 {@code &} 都不许有</b>。
     *
     * <p>⛔ <b>2026-10-03 起【本方法已无调用者】（备件，不是删除 —— 与 {@link #getFailTooltip()} /
     * {@link #getPassTooltip()} 同样的处理）</b>：{@link #getTooltips()} 里那一段
     * 「{@code （等级 ≥ N）}」已按用户点单删除（原话「把后面等级&gt;=多少去了，挡视线了」）
     * ⇒ 那行不再需要 §-free 的等级文本。
     * <p>为什么<b>保留</b>而不是删：① 它与 {@code gateText()} 是本类"等级文本"的两个出口，
     * 将来那一行若要恢复成带等级的形态（用户随时可能改回来），这里是唯一的现成件；
     * ② 删掉它不会让任何错误变响，只会在恢复时多写一遍 {@code noAmpEcho} 包装。留着的代价 = 0 调用点。
     */
    private String plainGateText() {
        return noAmpEcho(gateText());
    }

    /**
     * 模块名 —— <b>用该物品自己的显示名（标准做法）</b>。
     *
     * <p>实现 = {@code new ItemStack(item).getHoverName()}：它内部就是
     * {@code Component.translatable(item.getDescriptionId())}（{@code Item.getName(ItemStack)}），
     * 即 lang 键 {@code item.shanhai.introductory_material_module}，
     * <b>在客户端</b>由 {@code assets/shanhai/lang/zh_cn.json} 渲成「入门物质模块」。
     * <p>⚠️ 用 {@link ResourceLocation#tryParse} 而不是 {@code new ResourceLocation(...)}：
     * 后者对非法 id（例如带 {@code "1x "} 前缀的那种）会<b>抛异常</b>，只能靠 catch 兜住；
     * 前者返回 null，语义更清楚。查不到物品时回退成<b>剥过前缀的纯 id</b>（而不是整条配方侧写法），
     * 并在后面由 {@link #gateText()} 明确标出"未识别"。
     *
     * <h2>🔴 与上游的偏离（如实交代）</h2>
     * <p>上游这里解析 {@code &$style-} 前缀并用 {@code DShanhaiTextUtil.createStyled(text, style)} 预渲染；
     * <b>这两个类本工程都没有</b>（全工程 0 命中）。
     * <p>本工程对应的是<b>客户端绘制期解析</b>（{@code ShanhaiTextParser}，{@code &$…-} 文本码的解析器）。
     * 本轮<b>不再</b>取 {@code getHoverName().getString()}（那会把可翻译 Component 拍平成字符串，
     * 丢掉 lang 解析），而是**原样返回 Component**，让客户端在绘制期解析 —— 这既满足"要显示中文"，
     * 也保住了上游那条 {@code &$…-} 管线的位置。
     *
     * <h2>🔴 2026-09-30 三改：上面那句"原样返回 Component"<u>已被推翻一半</u>（如实交代）</h2>
     * 实机 bug（用户截图）：「量子化现实重构」配方页底部那行显示成
     * {@code 模块要求：1× &$electric-创始现实修改模块（等级 ≥ 17）} —— <b>美化码原样露出来了</b>。
     * <p>⚠️ <b>2026-10-01 更正本段原写的"根因"</b>：当时写成"整行同时含 § 与 {@code &$} ⇒ 命中解析器的
     * 混用规则"，<b>那是错的</b> —— 这条文本由 {@code GTRecipeWidget} 用 {@code getString()} 送进 LDLib 的
     * String 标签，走 {@code Font.drawInBatch(String,…)}，<b>解析器一次都没被调用</b>。
     * 真根因与四段 {@code javap} 原文见类注释 §7。⇒ 但修法<b>方向是对的</b>：
     * <b>先 {@link #noAmpEcho(String)} 再做成字面量</b>，那一行就不再含 {@code &}
     * ⇒ 无论走哪条渲染路径都不会露码（{@code §b}/{@code §7} 照常生效）。
     * <b>代价（如实说）</b>：这个名字在本行不再走色板渐变，改成跟随该行的原版颜色。
     * <p>⚠️ 传的仍然是 {@code getString()} 的结果 —— 但这里的调用点全部在<b>客户端</b>
     * （JEI 配方页 / JEI 催化剂页），lang 已经解析过；服务端不会渲染这个 tooltip。
     */
    private Component displayName() {
        final String id = effectiveModuleId();
        if (id.isEmpty()) {
            return Component.literal("§7（未配置模块）");
        }
        try {
            final ResourceLocation rl = ResourceLocation.tryParse(id);
            if (rl != null) {
                final Item item = ForgeRegistries.ITEMS.getValue(rl);
                if (item != null && item != Items.AIR) {
                    // 🔴 2026-09-30 起：**先剥掉 `&$…-` 前缀码**再做成字面量；
                    //    2026-10-01 改成过 noAmpEcho（= 剥前缀码 ＋ 删掉残留的 `&`）。
                    //    不剥的后果（用户两次截图）：本方法的结果会被送进 LDLib 的
                    //    `LabelWidget(IILjava/lang/String;)` ⇒ 走 `Font.drawInBatch(String,…)`
                    //    ⇒ **不经过我们的混入/解析器** ⇒ 原版把 `&$electric-` 当普通字符画出来，
                    //    玩家看到的就是「模块要求：1× &$electric-创始现实修改模块（等级 ≥ 17）」。
                    //    ⚠️ 2026-10-01 更正：这不是"§ 与 &$ 混用"那条规则造成的（那次它根本没被触发）——
                    //    真根因与字节码取证见类注释 §7。
                    return Component.literal(noAmpEcho(new ItemStack(item).getHoverName().getString()));
                }
            }
        } catch (Exception ignored) {
            // 注册表还没起来 / 物品不存在 ⇒ 回退到纯 id（下面那行），不影响任何判定。
        }
        // 🔴 2026-10-01：回退出来的 id 也过 noAmpEcho（理由同 gateText()：这一行会进 LDLib 的
        //    String 标签，那条路径不经过解析器 ⇒ 漏一个 `&` 就露码）。
        return Component.literal(noAmpEcho(id));
    }

    /** 提示行里显示的物品 id（给离线取证装置与日志用；不含配方侧数量前缀）。 */
    public String displayNameIdForProbe() {
        return effectiveModuleId();
    }

    @Override
    public RecipeCondition createTemplate() {
        return new ModuleLevelCondition("", 0);
    }

    @Override
    public JsonObject serialize() {
        JsonObject json = super.serialize();
        json.addProperty("module_id", moduleId);
        json.addProperty("level", requiredLevel);
        return json;
    }

    /**
     * 反序列化。
     *
     * <p>🔴 2026-09-26 修复：接受<b>两种形状</b>，并且缺字段不再 NPE。
     * <pre>
     *   ① 扁平（GTCEu 自家条件的约定，也是本类 serialize() 写出来的形状）：
     *        { "module_id": "shanhai:introductory_material_module", "level": 1 }
     *   ② KubeJS {@code GTRecipeComponents$5.write()} 写出来的外层包装：
     *        { "type": "module_level", "data": { "module_id": …, "level": … } }
     * </pre>
     * 为什么必须认 ②：KubeJS 那个组件的 {@code write} 产出 ②，而它的 {@code read} 在拿到 JsonObject 时
     * 会把<b>这个外层对象原样</b>交给 {@code condition.deserialize(json)}
     * （取证：{@code javap -c com.gregtechceu.gtceu.integration.kjs.recipe.components.GTRecipeComponents$5}）。
     * ⇒ 旧实现里 {@code json.get("module_id")} 在 ② 上会取到 null 并 NPE（配方直接解析失败）。
     * <p>缺字段时：{@code module_id} 缺失 ⇒ 空串（= 未配置，门槛不可解、拦下并（对非空 id）打 WARN）；
     * {@code level} 缺失 ⇒ 0（只影响显示的数量位）。
     */
    @Override
    public ModuleLevelCondition deserialize(JsonObject json) {
        super.deserialize(json);
        JsonObject data = json;
        if (json.has("data") && json.get("data").isJsonObject()) {
            data = json.getAsJsonObject("data");
        }
        final var mid = data.get("module_id");
        final var lvl = data.get("level");
        return new ModuleLevelCondition(
                mid == null || mid.isJsonNull() ? "" : mid.getAsString(),
                lvl == null || lvl.isJsonNull() ? 0 : lvl.getAsInt()
        );
    }

    @Override
    public void toNetwork(FriendlyByteBuf buf) {
        super.toNetwork(buf);
        buf.writeUtf(moduleId);
        buf.writeVarInt(requiredLevel);
    }

    @Override
    public ModuleLevelCondition fromNetwork(FriendlyByteBuf buf) {
        super.fromNetwork(buf);
        return new ModuleLevelCondition(buf.readUtf(), buf.readVarInt());
    }
}
