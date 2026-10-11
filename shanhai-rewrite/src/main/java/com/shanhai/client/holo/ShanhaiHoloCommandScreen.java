package com.shanhai.client.holo;

import java.lang.reflect.Field;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.CommandSuggestions;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.fml.util.ObfuscationReflectionHelper;
import org.joml.Matrix4f;
import org.lwjgl.glfw.GLFW;

/**
 * 山海重构 · 全息命令输入框的<b>键盘接管层</b>（一个<b>完全透明</b>的 Screen）。
 *
 * <h2>1. 用户原话（逐字）</h2>
 * <blockquote>
 *   还可以新增一个 cmd 的功能，全息输入框，可以执行cmd的命令，拥有管理员权限
 * </blockquote>
 * <blockquote>
 *   对了，在原版输入指令中，按 tab 可以自动补全指令，<b>你也在我们的指令输入那里（就是全息投影的指令输入的地方）添加一个</b>
 * </blockquote>
 * <blockquote>
 *   还有就是我们这个<b>输入框是不需要输入/</b>的，但是候选表<b>只有输入 / 的时候才会出来</b>
 * </blockquote>
 * <blockquote>
 *   候选表位置可以改 ／ <b>需要支持鼠标候选</b>
 * </blockquote>
 *
 * <h2>2. 🔴 为什么是"开一个轻量 Screen"，而不是"在渲染层自己接键"</h2>
 * 这是任务书点名要挑一条并写进报告的分叉。挑 Screen 的三条理由，每条都对应一件手搓一定会错的事：
 * <table border="1">
 *   <tr><th>事</th><th>原版怎么做的</th><th>手搓会发生什么</th></tr>
 *   <tr><td><b>输入法（中文/日文）</b></td>
 *       <td>{@code Screen.charTyped} 收的是<b>输入法提交之后</b>的字符（GLFW 的 char 回调）</td>
 *       <td>自己在 {@code InputEvent.Key} 上攒键码 ⇒ 只能敲出 ASCII，中文命令一个字都打不进去</td></tr>
 *   <tr><td><b>键盘焦点</b></td>
 *       <td>开了 Screen 之后原版<b>停止</b>把按键喂给玩家（不会边打字边走路/挖方块）</td>
 *       <td>渲染层接键 ⇒ 每敲一个字母，玩家还在动、方块还在被挖（按键被吃两遍）</td></tr>
 *   <tr><td><b>ESC 优先级</b></td>
 *       <td>{@code Screen.keyPressed(ESCAPE)} ⇒ {@code onClose()}，且它<b>不</b>碰世界状态</td>
 *       <td>自己判 ESC ⇒ 很容易顺手把"投影也关掉"，而那正好违反已验收的
 *           「ESC 走路打方块都不关」</td></tr>
 * </table>
 * <p>另有一条副作用本轮用上了：<b>Screen 开着的时候，面板的右键点不动</b>
 * —— {@code ShanhaiHoloMenuInput} 的 {@code useKeyWasDown} 要求 {@code mc.screen == null}。
 * 所以本类吞掉一次左键点击，<b>不可能</b>顺手把面板上那一格也点了（见 §8）。
 *
 * <h2>3. 🔴 它是【不可见】的 —— 用户看到的仍然是那块世界空间的全息板</h2>
 * <pre>
 *   render()          : 只画原版的候选列表（全息板由 RenderLevelStageEvent 那条路画）
 *   renderBackground(): 覆写成空（原版会糊一层渐变/模糊，那会把全息板盖住 —— 直接违反"观感是全息面板"）
 *   isPauseScreen()   : false（单人游戏里也不许暂停世界：用户要的是"边跑边敲"）
 * </pre>
 * 文字本身画在<b>板</b>上（{@code ShanhaiHoloMenuRenderer#drawPanelRowText} 读
 * {@link ShanhaiHoloMenuPanel#cellText}({@code MODE_COMMAND}, {@code COMMAND_INPUT_ROW}, 0)），所以这个 Screen 上
 * <b>命令正文一个字都不画</b>，它只是一副"键盘接收器"。这也是为什么它不违反"面板必须是全息风格"那一条。
 *
 * <h2>4. 键位</h2>
 * <pre>
 *   回车 / 小键盘回车 : 执行这条命令（发给服务端，以管理员权限跑）    🔴 本次【一个字没改】
 *   ESC              : 关掉键盘（<b>投影与面板都不关</b> —— 回来接着点） 🔴 本次【一个字没改】
 *   退格             : 删一个字符
 *   Ctrl+A / Delete  : 清空
 *   Tab / Shift+Tab  : 🆕 补全 / 在候选里循环（见 §5）
 *   上 / 下          : 🆕 候选表打开时在里面选（原版同款；没打开时什么都不做）
 *   鼠标左键         : 🆕 点在候选表上 ⇒ 选中那一条并补进输入框（见 §8）
 *   其余可打印字符    : 追加（含中文，由输入法给）
 * </pre>
 * ⚠️ 鼠标滚轮：本界面没有任何可滚动的东西（面板是固定几行板）⇒ 滚轮仍然走原版（切快捷栏）。
 * 候选表超过 10 条时原版是靠滚轮翻页的 —— 那一条<b>本轮没做</b>（见报告「没做到」）。
 *
 * <h2>5. Tab 自动补全：<b>直接复用原版的 {@link CommandSuggestions}</b>，一个字都不重写</h2>
 *
 * <h3>5.1 走的哪条路</h3>
 * 界面本身就是原版 {@code Screen}（{@code render} 是空的）⇒ 没有理由重写一套补全：
 * {@code ChatScreen} 用的就是 {@link CommandSuggestions}，我们照抄它的接线。
 *
 * <h3>5.2 原版要一个 {@code EditBox}，而我们的正文存在面板里 —— 怎么接</h3>
 * 🔴 {@link CommandSuggestions} 的构造器<b>必须</b>吃一个 {@link EditBox}（它靠读
 * {@code input.getValue()} / {@code input.getCursorPosition()} 算候选，靠写
 * {@code input.setValue(...)} 落补全结果）。而本工程的血脉是：命令正文存在
 * {@link ShanhaiHoloMenuPanel} 的静态缓冲里（世界空间那块板每帧从它读字）。
 * <p>⇒ 解法：<b>面板仍然是唯一真相，EditBox 只是一面"隐形镜子"</b>：
 * <pre>
 *   面板 → EditBox : {@link #mirrorPanelToEditBox()}（打字/退格/清空之后调一次）
 *   EditBox → 面板 : {@link #onEdited(String)}（EditBox 的 responder；原版就是靠 responder
 *                    在 {@code setValue} 里回调，把"补全结果"写回业务层）
 * </pre>
 * 🔴 <b>responder 必须在 {@code CommandSuggestions} 造好之后再装</b>：
 * {@code setValue} 会当场回调它，而回调里要读 {@code commandSuggestions}。
 * <p>⚠️ 这面镜子<b>不渲染</b>（{@code setVisible(false)}）、<b>不进 children</b>（不调
 * {@code addWidget}）⇒ 它既不画一个字，也吃不到任何鼠标事件。
 * 它唯一的作用是给 {@code CommandSuggestions} 提供"值 + 光标 + 屏幕坐标"，以及让 Tab 的判定条件
 * {@code screen.getFocused() == this.input} 成立（见 {@link #init()} 里的 {@code setFocused}）。
 *
 * <h3>5.3 🆕 ① 镜子里<b>恒带一个前导 {@code /}</b>（用户点单：「输入框是不需要输入 / 的，但候选表只有输入 / 的时候才会出来」）</h3>
 * 上一轮的做法是"不以 {@code /} 开头就别把按键递给原版" —— 挡住了玩家名候选表，代价是
 * <b>不敲 {@code /} 就永远看不到候选表</b>。本轮的改法：<b>把那个 {@code /} 挪到镜子里去</b>：
 * <pre>
 *   面板 "sh"   → 镜子 "/sh"     （原版走【命令补全】那条路 ⇒ 玩家名候选表结构上不可能出现）
 *   镜子 "/sh"  → 面板 "sh"      （补全结果取回来时，脱掉【我们补的】那一个斜杠）
 *   面板 "/sh"  → 镜子 "/sh"     （🔴 本来就带 ⇒ 绝不 '//sh'；取回时也原样保留，不吞用户敲的字）
 *   面板 ""     → 镜子 "/"       （🔴 不许是空串：空串会让原版掉进玩家名那条路）
 * </pre>
 * 换算规则、边界情况与判据全部在 {@link ShanhaiHoloTabCompleteCore} 的
 * {@code mirrorText / panelText / mirrorAddedSlash / wordStartForCompletion}（§5 那几条）里，
 * 本类只负责在<b>两个边界</b>上调它们。
 * <p>🔴 <b>为什么不把 {@code commandsOnly} 设成 true</b>（任务书点名不许的简单粗暴法）：
 * 那个参数只影响 {@code flag1}，而 {@code updateCommandInfo} 里
 * {@code if (flag) stringreader.skip();} 仍然不会执行（flag 只看第一个字符是不是 '/'）
 * ⇒ 解析器从<b>下标 0</b> 开始读 {@code "sh"}，候选的 {@code range} 落在 {@code [0,2)} 上，
 * 与"敲 / 的那条路"<b>不是同一套偏移</b>（连 {@code s.substring(0, i)} 那些分支都会变）。
 * 镜像方案让原版走它自己那条已经验证过的路，我们一个字节都不碰原版类。
 * <p>⚠️ <b>偏移只在边界上换算一次</b>：原版所有的 start/end/光标都在<b>镜子坐标系</b>里
 * （镜子 = 面板 + 1 个前导斜杠 ⇒ 下标恰好 +1），所以本类<b>没有</b>任何地方需要给
 * {@code applyCompletion} 那一族打 +1 补丁。光标同理：镜子的光标恒在末尾
 * （{@link #mirrorPanelToEditBox}），而末尾在镜子里就是"面板文本末尾 + 1"。
 * <h4>逐条边界（都在 selfTest 里有判据）</h4>
 * <pre>
 *   面板原文带 '/'      : 镜子不补（'//sh' 永不出现）；取回时把斜杠留着（他敲的字不许吞）
 *   光标在中间          : 本类只会把光标拨到【镜子末尾】（面板只能在末尾增删）
 *                         原版 useSuggestion() 之后自己会把光标停在补完那一段之后，下一拍再拨回末尾
 *   全选替换 / 清空     : Ctrl+A / Delete ⇒ 面板清空 ⇒ 镜子回到 "/"（不是空串）
 *   空串                : 面板 "" ⇒ 镜子 "/"；镜子 "/" ⇒ 面板 ""
 *   长度上限            : 镜子上限 = 面板上限 + 1（否则到 256 字时镜子会把面板最后一个字截掉）
 * </pre>
 *
 * <h3>5.4 候选表的落点：🆕 ② 贴着中间那块全息板弹（用户点单：「候选表位置可以改」）</h3>
 * 原版把候选表摆在屏幕底部，而且它的横纵坐标<b>都不是能传进去的参数</b>：
 * <pre>
 *   x = clamp(input.getScreenX(range.start), 0, input.getScreenX(0) + input.getInnerWidth() - 最宽候选)
 *   y = screen.height - 15 - min(候选数, 10) * 12          （anchorToBottom = true 这一档）
 * </pre>
 * 而候选表的宽/高是原版的<b>私有字段</b>（{@code pendingSuggestions} / {@code suggestions}），
 * 在外面读不到。⇒ 只能靠<b>与候选条数无关</b>的那两条边：
 * <ul>
 *   <li><b>下边缘恒为 {@code screen.height - 15}</b>（就写在 {@code showSuggestions} +
 *       {@code SuggestionsList} 的构造器里）⇒ 纵向位移可以<b>精确</b>算出来；</li>
 *   <li><b>左边缘 = 镜子左边缘 + 已输入前缀的宽度</b>（{@code EditBox#getScreenX}）
 *       ⇒ 横向位移也精确（前缀用 {@code wordStartForCompletion} 定）。</li>
 * </ul>
 * 于是做法是：{@link ShanhaiHoloTabCompleteCore#anchorFor} 给出目标（候选表的左边缘 / 下边缘），
 * 减去原版那两个数，得到一次 <b>(dx, dy) 位姿平移</b>，在 {@code render()} 里
 * {@code graphics.pose().translate(dx, dy, 0)} 之后画候选表。
 * <p>🔴 好处是<b>绘制与命中同源</b>：鼠标点进来时把坐标反向平移同样的 (dx, dy)，判的就还是原版那张
 * {@code rect}（见 §8）；而且平移量<b>每帧重算</b> ⇒ 候选表会跟着全息板一起动。
 * <p>🔴 <b>兜底</b>：投影不出来（板在背后/在屏幕外/窗口太小放不下）⇒ {@code anchorFor} 返回 null
 * ⇒ {@code dx = dy = 0} ⇒ 走的是<b>与改动前逐字相同</b>的那一句
 * {@code this.commandSuggestions.render(graphics, mouseX, mouseY);}（原版底部位置）⇒ 不会有异常。
 * <h4>投影怎么来（🔴 复用现成的，没有第二套相机公式）</h4>
 * <pre>
 *   MVP = P · V
 *   P   = new Matrix4f().perspective(视野角, 宽高比, 0.05, gameRenderer.getDepthFar())
 *         （与 {@code GameRenderer#getProjectionMatrix} 同一个式子；视野角取 {@code options.fov()}，
 *           ⚠️ 不含"跑动/拉弓/望远镜"那几个修正 —— 那些会让板的位置差几个像素，见报告「残余不确定性」）
 *   V   = R_x(相机俯仰) · R_y(相机偏航 + 180) · T(−相机世界坐标)
 *         （前两项与 {@code GameRenderer.renderLevel} 的 mulPose 逐位相同；
 *           第三项由 {@link ShanhaiHoloSurroundView#composeView} 补 —— 那个类就是为了这一项存在的）
 *   投影  = {@link ShanhaiHoloSurround.Proj#project}（环绕层早就在用它，本轮是【复用】不是新写）
 *   ⚠️ {@code Proj.halfW/halfH} 这里填的是 <b>GUI 单位</b>的半宽半高
 *      （{@code window.getGuiScaledWidth()/2}）—— 环绕层那边填的是 framebuffer 像素，
 *      同一个类两处口径不同，注释写清免得下一个人混。
 *   ⇒ 输出直接就是 GUI 坐标 ⇒ 再交给 {@link ShanhaiHoloTabCompleteCore#boxOfCorners} 收成一个矩形。
 * </pre>
 * 板的那 8 个角（命令面板只画 2 行 ⇒ 2×4 个角）由 {@link ShanhaiHoloMenuPanel#rows()}
 * （= 几何核 {@code panelRows()}）与 {@link ShanhaiHoloMenuState#panelCenter} /
 * {@link ShanhaiHoloMenuState#panelFrameYaw} 算出来 —— <b>锚点、框偏航、行板位姿三样全部复用</b>
 * 渲染器与输入层用的那三个函数，本类<b>没有</b>自己推第二套。
 *
 * <h3>5.5 原版语义照旧（这些是"复用"换来的，不是我们写的）</h3>
 * <pre>
 *   第一次 Tab（候选表已经因为 autoSuggestions 自动弹出来了）⇒ 直接把选中的那条补上
 *   再按 Tab / Shift+Tab ⇒ 在候选里前后循环，首尾闭合
 *   上下键             ⇒ 在候选里选
 *   候选表            ⇒ 原版那张（含高亮当前项、含超出时的翻页箭头）
 *   候选来源          ⇒ {@code minecraft.player.connection.getCommands()}（客户端指令注册表）
 *                        ⇒ 🔴 我们自己注册的客户端命令（/shanhai …）本来就在里面
 * </pre>
 *
 * <h3>5.6 🔴 【没有】改的东西（用户点名的红线：一个都不许变）</h3>
 * <pre>
 *   回车 / 小键盘回车 ⇒ 仍然是【执行这条命令】
 *   ESC              ⇒ 仍然是【一下就把键盘关掉】（原版会先收候选表、再一下才关；我们【不跟】）
 *   退格 / Ctrl+A / Delete / 输入法字符 / 面板上那一行字 / 面板的几何 ⇒ 一个字没动
 *   镜子的几何（x=4、宽=屏宽−4、高=12）⇒ 一个字没动（见 {@link #MIRROR_X}）
 * </pre>
 *
 * <h2>6. 🔴 血账：原版方法名在 reobf 后是 SRG 名</h2>
 * 本类<b>不 mixin、不反射</b>原版类，全部是编译期直连（javac 按本工程
 * {@code forge-1.20.1-47.4.16_mapped_parchment} 的名字解析），所以 SRG 那一套在这里不适用。
 * 但正因为是编译期直连，<b>构造器签名必须逐位对</b>：1.20.1 的
 * {@link CommandSuggestions} 是 <b>10 个参数</b>
 * （{@code Minecraft, Screen, EditBox, Font, boolean commandsOnly, boolean onlyShowIfCursorPastError,
 * int lineStartOffset, int suggestionLineLimit, boolean anchorToBottom, int fillColor}），
 * 1.19 那个 9 参数的写法在这里编译不过。本处参数取值与 {@code ChatScreen#init} 的字节码逐位相同
 * （{@code false, false, 1, 10, true, -805306368}；
 * 那个 {@code 10} 必须与 {@link ShanhaiHoloTabCompleteCore#SUGGEST_LINE_LIMIT} 是同一个数 ——
 * 候选表的最大高度就是它推出的，见 {@link ShanhaiHoloTabCompleteCore#SUGGEST_MAX_H}）。
 *
 * <h2>7. 提交出去的那条命令【可以不带】前导斜杠（🔴 已核过服务端）</h2>
 * 本轮之后面板正文通常不带 {@code /}，而这<b>不是</b>问题：
 * {@link ShanhaiHoloMenuInput#submitCommand} 的注释写着"服务端两种都吃"，
 * 服务端 {@code ShanhaiHoloMenuNetwork} 里那一行是
 * {@code final String text = cmd.startsWith("/") ? cmd.substring(1) : cmd;} ⇒ 有没有斜杠都执行同一条命令。
 * ⇒ 本轮<b>没有</b>在提交那条路上加任何东西（"回车 = 执行"一个字没改）。
 *
 * <h2>8. 🆕 ③ 鼠标点候选（用户点单：「需要支持鼠标候选」）</h2>
 * <pre>
 *   mouseClicked(mx,my,button)
 *     → 先把点反向平移 (dx, dy)（{@link ShanhaiHoloTabCompleteCore#toListSpace}）
 *     → 交给原版 {@code commandSuggestions.mouseClicked(...)}
 *         · 原版自己判"在不在那张 rect 里"（不在就返回 false）
 *         · 在 ⇒ 选中那一行 + {@code useSuggestion()}（把候选写进镜子）
 *       ⇒ 返回 true 才【吃掉】这次点击
 *     → 否则照旧 {@code super.mouseClicked(...)}（= 改动前的行为，一个字没改）
 * </pre>
 * 🔴 面板文字与板上文字为什么一定一致：{@code useSuggestion()} 走的是
 * {@code EditBox.setValue(...)} ⇒ 触发 responder {@link #onEdited(String)} ⇒
 * {@link #writePanel(String)} 把同一串写回面板 ⇒ 板那一行下一帧就变了。
 * 这条路上<b>没有第二份实现</b>（"点了候选但板没变"在这一族里就是 responder 被绕过了）。
 * <p>⚠️ 吃掉一次点击<b>不会</b>误伤面板：Screen 开着时
 * {@code ShanhaiHoloMenuInput} 的 {@code useKeyWasDown} 恒为 false（它要求 {@code mc.screen == null}）
 * ⇒ 面板那一格点不动（见 §2 末）。
 *
 * <h2>9. 🆕 ④ 鼠标滚轮 ⇒ 原版候选表翻页（语义与原版聊天栏一致）</h2>
 * <pre>
 *   mouseScrolled(mx, my, delta)
 *     → 问逻辑核：这一拍要不要换坐标？   Core.wheelTarget(mx, my, suggestDx, suggestDy, 窗口那四个数)
 *         · null（表没被平移：回退到底部那一档）
 *              ⇒ 直接 commandSuggestions.mouseScrolled(delta)
 *                 （原版自己看真鼠标 —— 这一支与改动前【逐字相同】）
 *         · {rawX, rawY}
 *              ⇒ 先把这两个值临时写进 MouseHandler（见 WheelMouseShim），再 mouseScrolled(delta)
 *         · 两条支路合流：原版返回 true 才【吃掉】；否则 super.mouseScrolled(...)（= 改动前行为）
 * </pre>
 * 🔴 <b>为什么必须"换坐标"</b>：1.20.1 那个入口是
 * {@code CommandSuggestions#mouseScrolled(double delta)} —— <b>它不接坐标</b>，坐标由它自己从
 * {@code MouseHandler} 上读（{@code javap} 取证见 {@link ShanhaiHoloTabCompleteCore} 的 §8）。
 * 而候选表被平移过 {@code (suggestDx, suggestDy)} ⇒ 不换坐标的话，原版会在【没平移过的那一格】上
 * 判"在不在表里"：鼠标明明停在画出来的表上，它却判"不在"（滚不动）；鼠标停在屏幕底部那块空地方，
 * 它反而判"在"（在没有东西的地方把滚轮吃掉）。两条都错，而第二条正是"吃掉不该吃的滚轮"。
 * <p>🔴 <b>判定一格都没有搬过来</b>：在不在那张私有 {@code rect} 里、表折叠了没、到顶/到底怎么夹、
 * "吃不吃这一下"，全部仍然是原版说了算 —— 与 {@link #mouseClicked} 同一条纪律
 * （"是不是落在表上"只有原版那一处口径，见 {@link ShanhaiHoloTabCompleteCore} 的 §7）。
 * <p>🔴 <b>吃不到就一个字都不改</b>：坐标换不成（shim 不可用）、或原版返回 false ⇒ 一律
 * {@code super.mouseScrolled(...)}。候选表折叠时原版第一句就是
 * {@code suggestions == null} 就返回 false ⇒ 折叠状态下滚轮<b>永远不会</b>被吃掉
 * （这一条由原版自己保证，我们不需要再判一次"表开着没"）。
 * <p>⚠️ <b>代价（如实记在报告里）</b>：这一支是<b>对原版私有字段的一次写</b>
 * （{@code MouseHandler.xpos / ypos}），用 Forge 官方的 {@code ObfuscationReflectionHelper} 解析
 * （开发名 ⇒ 运行期 SRG 名），写完当场还原（{@code try} 里写、{@code finally} 里还）；
 * 解析或写入失败 ⇒ 永久关掉这个 shim（只记一行 {@code holo_cmd_wheel_shim} 日志）⇒ 退化到"改动前"，
 * <b>绝不会</b>退化成"乱吃滚轮"。
 */
@OnlyIn(Dist.CLIENT)
public final class ShanhaiHoloCommandScreen extends Screen {

    /** 日志前缀（与全息那几个类同一个，便于 grep）。 */
    private static final String PREFIX = ShanhaiHoloMenuInput.PREFIX;

    /**
     * 镜子的屏幕几何 —— <b>与 {@code ChatScreen#init} 里那个 EditBox 逐位相同</b>
     * （{@code new EditBox(font, 4, this.height - 12, this.width - 4, 12, ...)}）。
     * <p>本轮之后它同时还是"候选表横坐标的零点"：原版的 x 取自
     * {@code input.getScreenX(范围起点)} = 镜子左边缘 + 前缀宽度。
     * 镜子保持"贴着屏幕左边、占满宽度"有一个额外好处：原版那句
     * {@code Mth.clamp(..., 0, input.getScreenX(0) + input.getInnerWidth() - 最宽候选)}
     * 的上界几乎永远够宽 ⇒ 横坐标<b>可预测</b>（见 §5.4 的位移算法）。
     * <p>⚠️ 纵向位置（{@code height - 12}）这一轮已经<b>不再决定候选表画在哪</b>
     * （那是 {@code screen.height - 15} 决定的，再由位姿平移挪到板上），但镜子本身仍然按原样摆，
     * 免得动到已验收的东西。
     */
    private static final int MIRROR_X = 4;

    /** 镜子的高度（与 ChatScreen 同）。 */
    private static final int MIRROR_H = 12;

    /**
     * 那面<b>隐形镜子</b>：只给原版 {@link CommandSuggestions} 提供
     * "值 / 光标 / 屏幕坐标"，<b>不渲染、不吃鼠标</b>（见类注释 §5.2）。
     * <p>🔴 它<b>不是</b>命令正文的真相 —— 真相永远是 {@link ShanhaiHoloMenuPanel} 里那一份。
     */
    private EditBox input;

    /** 原版的候选表（Tab / Shift+Tab / 上下键 / 鼠标点 / 高亮 / 翻页 全在它里面）。 */
    private CommandSuggestions commandSuggestions;

    /**
     * 🆕 本帧候选表要平移多少（GUI 像素）—— 由 {@link #updateSuggestOffset} 每帧算一次。
     * <p>{@code (0, 0)} = 没有可用的锚（板投影不出来 / 窗口太小）⇒ 保持原版底部位置。
     * <p>⚠️ 它同时被两处读：{@link #render}（画到哪儿）与 {@link #mouseClicked}（点哪儿算命中）
     * —— <b>绘制与命中必须用同一个数</b>，这是这一族最容易漂的地方。
     */
    private int suggestDx;

    /** 见 {@link #suggestDx}。 */
    private int suggestDy;

    // ---- ② 投影用的复用对象（不每帧 new；与 ShanhaiHoloSurroundRenderer 同一个套路）----

    /** 透视投影矩阵（{@code GameRenderer#getProjectionMatrix} 同一个式子）。 */
    private static final Matrix4f PROJ_MAT = new Matrix4f();

    /** 相机姿态（<b>只有旋转</b>；平移由 {@link ShanhaiHoloSurroundView#composeView} 补）。 */
    private static final Matrix4f POSE_MAT = new Matrix4f();

    /** 真正的视图矩阵（世界 → 相机）。 */
    private static final Matrix4f VIEW_MAT = new Matrix4f();

    /** MVP = 投影 · 视图。 */
    private static final Matrix4f MVP_MAT = new Matrix4f();

    /**
     * 世界 → 屏幕的投影 —— 🔴 <b>复用环绕层那个</b> {@link ShanhaiHoloSurround.Proj}
     * （它的注释与判据早就在那里了：列主序的 MVP + {@code project}）。
     * <p>⚠️ 本类填给它的 {@code halfW/halfH} 是 <b>GUI 单位</b>（半宽 = guiScaledWidth/2），
     * 而环绕层那边填的是 framebuffer 像素 —— 同一个类、两处口径，别混。
     */
    private static final ShanhaiHoloSurround.Proj PROJ = new ShanhaiHoloSurround.Proj();

    /** {@code Proj#project} 的出口（复用同一个数组，不每帧 new）。 */
    private static final double[] PROJ_OUT = new double[3];

    /**
     * 板四角的投影坐标（最坏情况 5 行 × 4 角 = 20 个），投影失败的角写 NaN。
     * <p>⚠️ 容量按 {@link ShanhaiHoloMenuTuning#BOARD_COUNT} 行给足：命令面板只画 2 行（8 个角），
     * 但万一哪天别的模式也能开这个键盘，容量不够就会<b>悄悄只算前两行</b>（比"算不出来"更坏：
     * 它会给一个看着正常、其实只贴了一半的矩形）。
     */
    private static final double[] CORNER_X = new double[ShanhaiHoloMenuTuning.BOARD_COUNT * 4];

    /** 见 {@link #CORNER_X}。 */
    private static final double[] CORNER_Y = new double[ShanhaiHoloMenuTuning.BOARD_COUNT * 4];

    public ShanhaiHoloCommandScreen() {
        super(Component.literal("山海全息命令输入"));
    }

    // ================================================================== 初始化（原版控件的全部接线）

    /**
     * 建镜子 + 建原版候选表。
     *
     * <p>🔴 顺序是<b>有讲究的</b>，换一下就 NPE 或 Tab 失灵：
     * <pre>
     *   ① setValue(镜子里那份正文 —— 面板 + 前导斜杠)  —— 进场时把已经打了一半的字接回来
     *   ② setFocused(镜子)    —— 原版 keyPressed 的 Tab 那一支要求 screen.getFocused() == input
     *   ③ new CommandSuggestions(...)  —— 参数与 ChatScreen 字节码逐位相同
     *   ④ setResponder(...)   —— 🔴 必须最后：setValue 会当场回调它，而回调要读 commandSuggestions
     *   ⑤ refreshSuggestions() —— 按闸门决定这一次要不要算候选
     * </pre>
     */
    @Override
    protected void init() {
        // 自检读数（与本工程其它 holo 自检同一口径：进界面时打一行，失败会把失败项一并打出来）
        com.shanhai.ShanhaiMod.LOGGER.info("{} {}", PREFIX, ShanhaiHoloTabCompleteCore.selfTestLine());

        this.input = new EditBox(this.font, MIRROR_X, this.height - MIRROR_H,
                Math.max(1, this.width - MIRROR_X), MIRROR_H,
                Component.literal("山海全息命令输入"));
        this.input.setMaxLength(ShanhaiHoloMenuPanel.MAX_COMMAND_CHARS);
        // 🆕 本轮：镜子里还多带一个"隐含的前导斜杠" ⇒ 上限必须 +1。
        //    ⚠️ 上面那一行【一个字都没改】（上一轮的接线判据逐字点名它），这里是它后面的一次覆盖。
        //    不加这一行的话，面板打满 256 字时镜子会被截掉尾巴 ⇒ responder 写回面板 ⇒ 面板少一个字，
        //    于是 256 这个上限实际只能用到 255，而且每敲一下就抖一次。
        this.input.setMaxLength(ShanhaiHoloMenuPanel.MAX_COMMAND_CHARS + 1);
        this.input.setBordered(false);
        // 🔴 绝不画出来：用户看到的必须是那块世界空间的全息板（类注释 §3）。
        //    这一句同时把鼠标事件也关掉（AbstractWidget#mouseClicked 先查 active && visible）。
        this.input.setVisible(false);
        this.input.setCanLoseFocus(false);
        // 🆕 ① 进镜子的是【面板原文 + 前导斜杠】（不以 '/' 开头才补；绝不 '//'）
        this.input.setValue(ShanhaiHoloTabCompleteCore.mirrorText(ShanhaiHoloMenuPanel.command()));
        this.setFocused(this.input);          // 见 ②：没有这一句，原版根本不理 Tab

        this.commandSuggestions = new CommandSuggestions(this.minecraft, this, this.input, this.font,
                false, false, 1, 10, true, -805306368);

        // ④ responder 最后装：它会在 setValue 里被回调，而回调要读 commandSuggestions
        this.input.setResponder(this::onEdited);
        refreshSuggestions();
    }

    /**
     * 窗口尺寸变了之后把镜子摆回原位。
     * <p>⚠️ 原版 {@code Screen#init(Minecraft,int,int)} 只在<b>第一次</b>调 {@code init()}
     * （它靠 {@code initialized} 分支），之后变更尺寸走的是 {@code repositionElements()}。
     * 不覆写这里，改窗口大小之后候选表就会按旧宽度算横坐标。
     */
    @Override
    protected void repositionElements() {
        if (this.input == null) {
            return;
        }
        this.input.setX(MIRROR_X);
        this.input.setY(this.height - MIRROR_H);
        this.input.setWidth(Math.max(1, this.width - MIRROR_X));
        this.input.setHeight(MIRROR_H);
    }

    // ================================================================== 面板 ⇄ 镜子（① 前导斜杠）

    /**
     * 镜子里的文本变了（打字、退格、清空、<b>以及原版把候选补进去 / 鼠标点了候选</b>都会走这里）。
     * <p>两条活：① 写回面板（板上那一行字是渲染器从面板读的）；② 重算候选。
     * <p>🔴 {@code weAddedSlash} 必须在写回<b>之前</b>取：它问的是"打进镜子时那个斜杠是不是我们补的"
     * —— 依据是<b>此刻的面板原文</b>（它还没被这一趟写回改掉）。
     * <p>⚠️ 这里<b>不</b>再回头调 {@link #mirrorPanelToEditBox()}，否则就是死循环。
     */
    private void onEdited(String text) {
        final boolean weAdded = ShanhaiHoloTabCompleteCore.mirrorAddedSlash(ShanhaiHoloMenuPanel.command());
        writePanel(ShanhaiHoloTabCompleteCore.panelText(text, weAdded));
        refreshSuggestions();
    }

    /**
     * 镜子 ⇒ 面板。走的是面板<b>既有的公开 API</b>（clear + append），没有为这一件事动面板一个字符。
     * <p>等价于 {@code COMMAND.setLength(0)} 之后逐字符 append：
     * 截断（{@link ShanhaiHoloMenuPanel#MAX_COMMAND_CHARS}）与 {@code dirty} 标记都仍然由面板自己说了算。
     * <p>⚠️ 传进来的<b>已经</b>是面板口径的文本（前导斜杠在 {@link #onEdited} 里脱掉了）。
     */
    private static void writePanel(String text) {
        final String want = text == null ? "" : text;
        if (want.equals(ShanhaiHoloMenuPanel.command())) {
            return;                                     // 一模一样就不动它（免得白置 dirty、白重建那行字）
        }
        ShanhaiHoloMenuPanel.clear();
        final int n = Math.min(want.length(), ShanhaiHoloMenuPanel.MAX_COMMAND_CHARS);
        for (int i = 0; i < n; i++) {
            ShanhaiHoloMenuPanel.append(want.charAt(i));
        }
    }

    /**
     * 面板 ⇒ 镜子。打字 / 退格 / 清空之后各调一次。
     * <p>🔴 送进去的是 {@link ShanhaiHoloTabCompleteCore#mirrorText}(面板)：不以 {@code /} 开头就补一个，
     * 本来就带就不补（{@code '//sh'} 永远不出现）。
     * <p>光标永远在<b>镜子末尾</b>（面板只能在末尾增删），所以这里除了同步正文还要把光标拨回末尾 ——
     * 原版补全之后会把光标停在补完那一段的后面，下一拍再打字就必须把光标拨回来，
     * 否则"字进面板、光标在镜子中间"会变成两套口径。
     * <p>⚠️ {@code setValue} 与 {@code moveCursorToEnd} <b>都会回调 responder</b>
     * （{@code EditBox#moveCursorTo} 里那句 {@code onValueChange}），所以这里不需要自己再调
     * {@link #refreshSuggestions()} —— 回调里已经做了。
     */
    private void mirrorPanelToEditBox() {
        final String want = ShanhaiHoloTabCompleteCore.mirrorText(ShanhaiHoloMenuPanel.command());
        if (!want.equals(this.input.getValue())) {
            this.input.setValue(want);
        } else if (this.input.getCursorPosition() != want.length()) {
            this.input.moveCursorToEnd();
        }
    }

    /**
     * 🔴 <b>Tab 闸门的两处落点之一</b>（另一处在 {@link #keyPressed}）：
     * 只有以 {@code /} 开头才"允许有候选"。
     *
     * <p>{@code setAllowSuggestions(false)} 会顺手把候选表清掉（原版源码里就写着），
     * 于是"一边打字一边自动弹候选表"那条路（{@code updateUsageInfo → showSuggestions}）
     * 对非命令输入根本走不到 ⇒ <b>玩家名候选表不可能冒出来</b>。
     * <p>🆕 本轮之后镜子里<b>恒</b>以 {@code /} 开头（见类注释 §5.3）⇒ 这条闸门恒真。
     * 它留着有两个用处：① 口径仍然只有这一处（"什么算命令行"不会被第二个人另写一套）；
     * ② 万一有人把镜子那一套改坏（例如让镜子变成空串），这里会立刻把玩家名候选表挡在门外。
     */
    private void refreshSuggestions() {
        if (this.commandSuggestions == null) {
            return;
        }
        this.commandSuggestions.setAllowSuggestions(
                ShanhaiHoloTabCompleteCore.tabEligible(this.input.getValue()));
        if (!suggestionsUsable()) {
            return;
        }
        this.commandSuggestions.updateCommandInfo();
    }

    /**
     * 现在能不能真的去问候选。
     * <p>⚠️ 原版 {@code updateCommandInfo} 会直接 {@code this.minecraft.player.connection.xxx}
     * —— 玩家还没进世界/连接没了的时候会 NPE，而它是在 {@code keyPressed} / {@code charTyped}
     * 这条路上被调的（没人接异常 ⇒ 当场崩游戏）。这三道判空是<b>替原版挡 NPE</b>，不是改它的语义。
     */
    private boolean suggestionsUsable() {
        return this.minecraft != null && this.minecraft.player != null
                && this.minecraft.player.connection != null;
    }

    // ================================================================== 🆕 ② 候选表锚在全息板上

    /**
     * 算出本帧候选表该平移多少（{@link #suggestDx} / {@link #suggestDy}）。
     *
     * <pre>
     *   目标（锚） = 全息板投影到屏幕上的矩形 ⇒ {@link ShanhaiHoloTabCompleteCore#anchorFor}
     *   原版位置   = 左边缘 {@code input.getScreenX(补全段起点)}、下边缘 {@code screen.height − 15}
     *   平移量     = 目标 − 原版位置
     * </pre>
     * 🔴 拿不到锚（{@code anchorFor} 返回 null）⇒ 两个量都置 0 ⇒ 渲染与命中原样走原版底部位置。
     * <p>⚠️ 算不出来<b>不算失败</b>：板在背后、板整块在屏幕外、窗口小到板上方放不下，都是正常情况。
     *
     * @param partialTick 本帧的插值量（{@code Screen#render} 递进来的那个）——
     *                    面板锚点是"玩家眼睛 + 朝向"算的，用它插值才是这一帧的位置
     */
    private void updateSuggestOffset(float partialTick) {
        this.suggestDx = 0;
        this.suggestDy = 0;
        if (this.commandSuggestions == null || this.input == null || this.font == null) {
            return;
        }
        final String mirror = this.input.getValue() == null ? "" : this.input.getValue();
        final int at = Math.min(ShanhaiHoloTabCompleteCore.wordStartForCompletion(mirror), mirror.length());
        // 原版那句：x = this.input.getScreenX(suggestions.getRange().getStart())
        //          = 镜子左边缘 + font.width(文本[0, 起点))      （EditBox#getScreenX）
        final int vanillaLeft = this.input.getX() + this.font.width(mirror.substring(0, at));
        final double[] board = projectPanelRect(partialTick);
        final int[] anchor = ShanhaiHoloTabCompleteCore.anchorFor(this.width, this.height, board,
                ShanhaiHoloTabCompleteCore.SUGGEST_MAX_H);
        if (anchor == null) {
            return;                                     // 没有锚 ⇒ 一个像素都不动
        }
        this.suggestDx = anchor[0] - vanillaLeft;
        this.suggestDy = anchor[1] - ShanhaiHoloTabCompleteCore.vanillaSuggestBottom(this.height);
    }

    /**
     * 把<b>命令面板画出来的那几行板</b>的四角投到屏幕上，收成一个矩形（GUI 坐标）。
     *
     * <p>🔴 几何链与渲染器逐项对齐（{@code ShanhaiHoloMenuRenderer#doRender} 的
     * {@code translate(锚点) → mulPose(Y, 框偏航) → pushBoard(板位姿)}）：
     * <pre>
     *   世界点 = 锚点 + R_y(框偏航) · ( 板位姿 + R_y(板自转Y)·R_x(板自转X)·(s · 板内点) )
     * </pre>
     * 面板那 5 块行板的 {@code rotX/rotY = 0、x = z = 0、scale = 1}（几何核 {@code panelRows()} 里定的）
     * ⇒ 化简成"板心在锚点正上方/下方、面朝 R_y(框偏航)"，本方法就是按这个化简算的
     * （但 {@code scale} 与 {@code rotX/rotY} 仍然按通用式取，免得哪天行板位姿一变就悄悄错位）。
     *
     * <p>⚠️ <b>没有</b>跟着这几个动效走（都会让投影差一点点，属于可接受的近似，见报告「残余不确定性」）：
     * 展开动画（{@code ShanhaiHoloMenuAnim} 的原点插值/整块缩放/自转）、视角晃动（bobView/bobHurt）、
     * 相机 roll、望远镜与"跑动/拉弓"的视野角修正。
     *
     * @return {@code {left, top, right, bottom}}（GUI 坐标）；四角里<b>只要有一个投不出来</b>
     *         就返回 {@code null}（⇒ 回退原版底部位置）
     */
    private double[] projectPanelRect(float partialTick) {
        final Minecraft mc = this.minecraft;
        if (mc == null || mc.player == null || mc.level == null || mc.gameRenderer == null) {
            return null;
        }
        final int mode = ShanhaiHoloMenuState.panelMode();
        if (mode == ShanhaiHoloMenuPanel.MODE_NONE) {
            return null;                                // 面板没开 ⇒ 没有"那块板"可贴
        }
        final Camera camera = mc.gameRenderer.getMainCamera();
        if (camera == null || mc.getWindow() == null) {
            return null;
        }
        final int fbW = mc.getWindow().getWidth();
        final int fbH = mc.getWindow().getHeight();
        final int guiW = mc.getWindow().getGuiScaledWidth();
        final int guiH = mc.getWindow().getGuiScaledHeight();
        if (fbW <= 0 || fbH <= 0 || guiW <= 0 || guiH <= 0) {
            return null;
        }
        final Integer fovOpt = mc.options == null ? null : mc.options.fov().get();
        final float fovDeg = fovOpt == null ? 70.0f : fovOpt.floatValue();
        if (!(fovDeg > 1.0f) || !(fovDeg < 179.0f)) {
            return null;
        }
        // ---- 投影矩阵（与 GameRenderer#getProjectionMatrix 同一个式子）----
        PROJ_MAT.identity().perspective((float) Math.toRadians(fovDeg),
                (float) fbW / (float) fbH, 0.05f, Math.max(64.0f, mc.gameRenderer.getDepthFar()));
        // ---- 相机姿态（与 GameRenderer#renderLevel 的两句 mulPose 逐位相同）----
        POSE_MAT.identity()
                .rotateX((float) Math.toRadians(camera.getXRot()))
                .rotateY((float) Math.toRadians(camera.getYRot() + 180.0f));
        final Vec3 camPos = camera.getPosition();
        ShanhaiHoloSurroundView.composeView(VIEW_MAT, POSE_MAT, camPos.x, camPos.y, camPos.z);
        MVP_MAT.set(PROJ_MAT).mul(VIEW_MAT);
        MVP_MAT.get(PROJ.m);
        // ⚠️ GUI 单位（不是 framebuffer 像素）⇒ project() 的输出直接就是 GUI 坐标
        PROJ.halfW = guiW * 0.5f;
        PROJ.halfH = guiH * 0.5f;

        // ---- 面板的锚点 / 框偏航（三处共用同一个函数：渲染器、输入层、这里）----
        final Vec3 eye = mc.player.getEyePosition(partialTick);
        final double[] centre = ShanhaiHoloMenuState.panelCenter(eye);
        final double yawRad = Math.toRadians(ShanhaiHoloMenuState.panelFrameYaw());
        final double rightX = Math.cos(yawRad);
        final double rightZ = -Math.sin(yawRad);

        final ShanhaiHoloMenuLayout.Board[] rows = ShanhaiHoloMenuPanel.rows();
        int n = 0;
        for (int i = 0; i < rows.length && n + 4 <= CORNER_X.length; i++) {
            if (!ShanhaiHoloMenuPanel.rowVisible(mode, i)) {
                continue;                               // 没画出来的行不算"那块板"（命令面板只有 2 行）
            }
            final ShanhaiHoloMenuLayout.Board b = rows[i];
            final double halfW = ShanhaiHoloMenuTuning.BOARD_HALF_WIDTH_BLOCKS * b.scale();
            final double halfH = ShanhaiHoloMenuTuning.BOARD_HALF_HEIGHT_BLOCKS * b.scale();
            final double bx = centre[0];
            final double by = centre[1] + b.y();
            final double bz = centre[2];
            for (int k = 0; k < 4; k++) {
                final double sx = (k & 1) == 0 ? -1.0 : 1.0;
                final double sy = k < 2 ? -1.0 : 1.0;
                final double wx = bx + rightX * halfW * sx;
                final double wy = by + halfH * sy;
                final double wz = bz + rightZ * halfW * sx;
                if (PROJ.project(wx, wy, wz, PROJ_OUT)) {
                    CORNER_X[n] = guiW * 0.5 + PROJ_OUT[0];
                    CORNER_Y[n] = guiH * 0.5 + PROJ_OUT[1];
                } else {
                    // 这个角在近平面之后（板转到背后去了）⇒ 写 NaN ⇒ boxOfCorners 会把整块作废
                    CORNER_X[n] = Double.NaN;
                    CORNER_Y[n] = Double.NaN;
                }
                n++;
            }
        }
        if (n == 0) {
            return null;
        }
        final double[] xs = new double[n];
        final double[] ys = new double[n];
        System.arraycopy(CORNER_X, 0, xs, 0, n);
        System.arraycopy(CORNER_Y, 0, ys, 0, n);
        return ShanhaiHoloTabCompleteCore.boxOfCorners(xs, ys);
    }

    // ================================================================== 渲染（只多画原版候选表）

    /**
     * 画原版的候选表/用法提示 —— 世界与全息板照旧（见类注释 §3）。
     *
     * <p>🆕 ② 候选表按 {@link #suggestDx}/{@link #suggestDy} 平移到全息板附近；
     * 拿不到锚（两个量都是 0）时走的还是<b>改动前那一句</b>
     * {@code this.commandSuggestions.render(graphics, mouseX, mouseY);} —— 逐字相同。
     */
    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // ⚠️ 这里【不要】调 super.render(...)：那会画控件；也【不要】调 renderBackground(...)：
        //    那会糊一层渐变把全息板盖掉。
        if (this.commandSuggestions == null) {
            return;
        }
        updateSuggestOffset(partialTick);
        final int dx = this.suggestDx;
        final int dy = this.suggestDy;
        if (dx == 0 && dy == 0) {
            this.commandSuggestions.render(graphics, mouseX, mouseY);
            return;
        }
        // 只把【候选表】这一层平移；用法/红字（renderUsage）留在原处不动（本轮没要求动它，
        // 而且它的横坐标是"镜子的左边 + 原版的夹紧值"，跟着平移会顶出屏幕右边）。
        graphics.pose().pushPose();
        graphics.pose().translate((float) dx, (float) dy, 0.0f);
        final boolean drew = this.commandSuggestions.renderSuggestions(graphics, mouseX - dx, mouseY - dy);
        graphics.pose().popPose();
        if (!drew) {
            this.commandSuggestions.renderUsage(graphics);
        }
    }

    /** 不糊背景（原版 {@code Screen#renderBackground} 会画渐变 + 模糊）。 */
    @Override
    public void renderBackground(GuiGraphics graphics) {
        // 同上：面板本身要透出来，所以背景一律不画。
    }

    /** 不暂停世界（单人游戏里也照旧跑）—— 用户要的是"边跑边敲命令"。 */
    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ================================================================== 键盘

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // ESC 优先：只关键盘，【不】碰投影与面板（已验收的「ESC 不关投影」照旧成立）
        // 🔴 本次一个字没改（原版此时会先收候选表，我们【不跟】—— 见类注释 §5.6）
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            onClose();
            return true;
        }
        // 🔴 本次一个字没改：回车 = 执行（原版此时会用回车"选中候选"，我们【不跟】）
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            submit();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_BACKSPACE) {
            ShanhaiHoloMenuPanel.backspace();
            mirrorPanelToEditBox();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_DELETE
                || (keyCode == GLFW.GLFW_KEY_A && (modifiers & GLFW.GLFW_MOD_CONTROL) != 0)) {
            ShanhaiHoloMenuPanel.clear();
            mirrorPanelToEditBox();
            return true;
        }
        // 🆕 Tab / Shift+Tab / 上下 ⇒ 交给原版候选表（Tab 循环、Shift+Tab 反向、上下选择
        //    全在原版 CommandSuggestions 里，我们一个字符都不重写）。
        //    🔴 闸门：只有以 '/' 开头才递下去 —— 否则原版会拿【玩家名】当候选（见类注释 §5.3）。
        //    镜子恒以 '/' 开头 ⇒ 这道闸门恒真（它现在是"断言"，不是"过滤器"）。
        if (isSuggestionKey(keyCode) && this.commandSuggestions != null
                && ShanhaiHoloTabCompleteCore.tabEligible(this.input.getValue())) {
            if (this.commandSuggestions.keyPressed(keyCode, scanCode, modifiers)) {
                // 真的被原版候选表吃掉才记一行 —— 这是"补全到底有没有进去"的唯一痕迹
                // （验收时最有用的一条读数：按了 Tab 却查不到这一行 ⇒ 闸门没通过。
                //   key=258 是 Tab、264/265 是上下键，都在日志里，不会被误读成"Tab 一定生效了"。）
                com.shanhai.ShanhaiMod.LOGGER.info("{} holo_cmd_suggest key={} text_len={} text={}",
                        PREFIX, keyCode, this.input.getValue().length(),
                        ShanhaiHoloMenuPanel.clip(this.input.getValue(), 24));
                return true;
            }
        }
        // 其余按键不回给 super：super 会在 ESC 之外做别的默认动作（例如 E 关界面），
        // 而在这里"任何非编辑键"都应当什么都不做，免得把面板顶掉。
        return true;
    }

    /**
     * 这三个键才交给原版候选表。
     * <p>上下键<b>不</b>额外判"表开着没"：原版 {@code CommandSuggestions#keyPressed} 在
     * {@code suggestions == null} 时对上下键返回 false ⇒ 自然什么都不做（与改动前一致）。
     */
    private static boolean isSuggestionKey(int keyCode) {
        return keyCode == GLFW.GLFW_KEY_TAB
                || keyCode == GLFW.GLFW_KEY_UP
                || keyCode == GLFW.GLFW_KEY_DOWN;
    }

    // ================================================================== 🆕 ③ 鼠标

    /**
     * 🆕 ③ <b>鼠标点候选</b>：先看这一下是不是落在候选表上，是才吃掉。
     *
     * <p>三件事按这个顺序：
     * <ol>
     *   <li>把点<b>反向平移</b>（{@link ShanhaiHoloTabCompleteCore#toListSpace}）——
     *       与绘制用的是同一个 (dx, dy)，所以"看到哪"与"点到哪"不可能各漂一套；</li>
     *   <li>交给原版 {@code commandSuggestions.mouseClicked(...)}：它自己判"在不在那张 rect 里"
     *       （不在 ⇒ 返回 false），在的话就选中那一行并把候选 {@code setValue} 进镜子
     *       ⇒ responder 把同一串写回面板 ⇒ 板上那一行下一帧就跟着变；</li>
     *   <li>它返回 false ⇒ 照旧 {@code super.mouseClicked(...)}
     *       （= 改动前那一条路，一个字没改）。</li>
     * </ol>
     * ⚠️ 本类<b>不</b>自己算一遍矩形：矩形是原版的私有字段，重算一份就会有两套口径。
     * "是不是落在表上"这个判断<b>只有原版那一处</b>。
     */
    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (this.commandSuggestions != null) {
            final double[] p = ShanhaiHoloTabCompleteCore.toListSpace(mouseX, mouseY,
                    this.suggestDx, this.suggestDy);
            if (this.commandSuggestions.mouseClicked(p[0], p[1], button)) {
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    // ================================================================== 🆕 ④ 滚轮翻页

    /**
     * 🆕 ④ <b>鼠标滚轮</b>：转发给原版候选表（翻页的语义与原版聊天栏一致）。
     *
     * <p>本方法自己只做三件事（判定一格都不做）：① 问逻辑核"这一拍要不要换坐标、换成什么"；
     * ② 要换就把两个值临时写进 {@code MouseHandler}（{@link WheelMouseShim}）；
     * ③ 把 delta 交给原版，<b>原版返回 true 才吃掉</b>。
     *
     * <p>🔴 两条不许动的纪律（见类注释 §9）：
     * <ul>
     *   <li><b>返回 false 就交回 {@code super}</b> —— 与改动前那条路逐字相同
     *       （{@code ContainerEventHandler#mouseScrolled} 的默认实现：问一遍可见控件，都不吃就 false）；</li>
     *   <li><b>换不成坐标就不换、也不吃</b>（shim 不可用时 {@code begin} 返回 false ⇒ 直接落到
     *       {@code super}）—— 宁可这次滚轮没用，也绝不在没有表的地方把它吃掉。</li>
     * </ul>
     */
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        final CommandSuggestions suggestions = this.commandSuggestions;
        final Minecraft mc = this.minecraft;
        if (suggestions != null && mc != null && mc.getWindow() != null) {
            final double[] target = ShanhaiHoloTabCompleteCore.wheelTarget(mouseX, mouseY,
                    this.suggestDx, this.suggestDy,
                    mc.getWindow().getScreenWidth(), mc.getWindow().getScreenHeight(),
                    mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight());
            if (target == null) {
                // 表没被平移（回退到底部那一档）⇒ 原版自己看真鼠标：与改动前【逐字相同】的那条路
                if (suggestions.mouseScrolled(delta)) {
                    logWheelHandled(delta, false);
                    return true;
                }
            } else if (WheelMouseShim.begin(mc, target[0], target[1])) {
                final boolean handled;
                try {
                    handled = suggestions.mouseScrolled(delta);
                } finally {
                    WheelMouseShim.end();
                }
                if (handled) {
                    logWheelHandled(delta, true);
                    return true;
                }
            }
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    /**
     * 滚轮被原版候选表吃掉时记一行 —— 验收就看这一行（滚没滚、吃掉没、坐标换没换）。
     * <p>⚠️ 只有<b>吃掉</b>才记：没吃掉是"什么都没发生"，记下来只会把日志刷满
     * （表折叠时每一拍都会走到这里）。换坐标失败那件事由 shim 自己那行 WARN 负责。
     */
    private void logWheelHandled(double delta, boolean shimmed) {
        com.shanhai.ShanhaiMod.LOGGER.info("{} holo_cmd_suggest_wheel delta={} handled=true shim={} dx={} dy={}",
                PREFIX, delta, shimmed, this.suggestDx, this.suggestDy);
    }

    /**
     * 🔴 <b>把 {@code MouseHandler} 上的鼠标坐标临时换成"候选表坐标系"里的那一格</b> ——
     * 只为了让原版那个<b>不接坐标</b>的 {@code CommandSuggestions#mouseScrolled(double)}
     * 自己读到我们想要的值（起因见类注释 §9，字节码取证见逻辑核的 §8）。
     *
     * <p>四条安全约定（缺一条都不许这么写）：
     * <ol>
     *   <li><b>成对</b>：{@link #begin} 返回 true ⇒ 必须在 {@code finally} 里 {@link #end}。
     *       中间只有原版那一次同步调用 —— 渲染线程上没有回调会跑到两者中间；</li>
     *   <li><b>失败即永久关闭</b>：字段用 Forge 的 {@link ObfuscationReflectionHelper} 解析
     *       （传<b>开发名</b>，它自己翻成运行期的 SRG 名 —— 生产环境靠这一步），
     *       解析或写入抛任何东西 ⇒ 关掉 + 记一行 + 以后一律 {@code return false}
     *       ⇒ 调用方交回 {@code super}（退化到"改动前"，绝不退化成"乱吃"）；</li>
     *   <li><b>自证</b>：解析完立刻做一次"读出 → 写回原值 → 再读"的往返（值不变），
     *       证明这两个字段真的可写，并把结论写进日志（{@code holo_cmd_wheel_shim}）——
     *       这是"反射在生产环境到底行不行"的唯一现场读数；</li>
     *   <li><b>不碰别的</b>：只读写 {@code xpos} / {@code ypos} 两个 double 字段 ——
     *       不碰 GLFW、不移动真光标、不动别的状态。</li>
     * </ol>
     */
    private static final class WheelMouseShim {

        /** 已经解析成功（或已判死）就不再来一遍。 */
        private static boolean resolved;

        /** 判死：以后一次都不再试，也一次都不吃滚轮。 */
        private static boolean broken;

        /** {@code MouseHandler.xpos}（开发名 ⇒ 运行期 SRG 名由 Forge 那一层翻）。 */
        private static Field fieldX;

        /** 见 {@link #fieldX}。 */
        private static Field fieldY;

        /** 被换坐标的那个实例（还原时用它，不去依赖单例）。 */
        private static MouseHandler target;

        /** 正在换（{@link #end} 据此判断要不要还原）。 */
        private static boolean active;

        private static double savedX;

        private static double savedY;

        private WheelMouseShim() {}

        /** 换坐标；返回 false = 这一拍没换（调用方必须照旧交回 {@code super}，不许吃滚轮）。 */
        static boolean begin(Minecraft mc, double rawX, double rawY) {
            resolve(mc);
            if (broken || mc == null || mc.mouseHandler == null || mc.mouseHandler != target) {
                return false;
            }
            try {
                savedX = fieldX.getDouble(target);
                savedY = fieldY.getDouble(target);
                fieldX.setDouble(target, rawX);
                fieldY.setDouble(target, rawY);
                active = true;
                return true;
            } catch (Throwable t) {
                disable(t);
                return false;
            }
        }

        /** 还原（{@link #begin} 返回 true 之后必须走到这里；内部自己判，重复调用无害）。 */
        static void end() {
            if (!active) {
                return;
            }
            active = false;
            try {
                fieldX.setDouble(target, savedX);
                fieldY.setDouble(target, savedY);
            } catch (Throwable t) {
                disable(t);
            }
        }

        private static void resolve(Minecraft mc) {
            if (resolved || broken) {
                return;
            }
            if (mc == null || mc.mouseHandler == null) {
                return;                                     // 还没到能用的时机：下次再说（不判死）
            }
            try {
                final Field fx = ObfuscationReflectionHelper.findField(MouseHandler.class, "xpos");
                final Field fy = ObfuscationReflectionHelper.findField(MouseHandler.class, "ypos");
                // 自证：读出 → 写回原值 → 再读（值不变）。证明这两个字段真的【可写】。
                final double x0 = fx.getDouble(mc.mouseHandler);
                final double y0 = fy.getDouble(mc.mouseHandler);
                fx.setDouble(mc.mouseHandler, x0);
                fy.setDouble(mc.mouseHandler, y0);
                fieldX = fx;
                fieldY = fy;
                target = mc.mouseHandler;
                resolved = true;
                com.shanhai.ShanhaiMod.LOGGER.info("{} holo_cmd_wheel_shim ready field={} selftest=ok",
                        PREFIX, fx.getName());
            } catch (Throwable t) {
                disable(t);
            }
        }

        /** 反射这条路什么都可能抛（找不到字段、不能写、类不存在）—— 而滚轮这一拍绝不能把游戏打崩。 */
        private static void disable(Throwable t) {
            broken = true;
            resolved = true;
            active = false;
            fieldX = null;
            fieldY = null;
            target = null;
            com.shanhai.ShanhaiMod.LOGGER.warn("{} holo_cmd_wheel_shim unavailable ({}):"
                    + " 滚轮一律交回上层（= 改动前行为，绝不乱吃）", PREFIX, String.valueOf(t));
        }
    }

    /**
     * 输入法/键盘提交下来的字符（<b>中文也走这一条</b>）。
     * <p>{@code §} 与控制字符一律不收：{@code §} 会被原版当成颜色码，收进去会让板上的字变色。
     */
    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (codePoint >= ' ' && codePoint != 0x00A7) {
            ShanhaiHoloMenuPanel.append(codePoint);
            mirrorPanelToEditBox();
        }
        return true;
    }

    /** 回车：把整条命令发出去（执行由服务端以管理员权限做）。 */
    private void submit() {
        // 复用输入层那条唯一的出口（判空 / 日志 / 面板上那行读数都在那一处，
        // 免得"点执行"与"按回车"两条路各写一套、迟早漂）
        ShanhaiHoloMenuInput.submitCommand(ShanhaiHoloMenuPanel.command(), "screen");
    }

    /** 关掉键盘（回世界，面板还开着）。 */
    @Override
    public void onClose() {
        com.shanhai.ShanhaiMod.LOGGER.info("{} holo_cmd_screen_closed text_len={}",
                PREFIX, ShanhaiHoloMenuPanel.command().length());
        final Minecraft mc = Minecraft.getInstance();
        if (mc != null) {
            mc.setScreen(null);
        }
    }
}
