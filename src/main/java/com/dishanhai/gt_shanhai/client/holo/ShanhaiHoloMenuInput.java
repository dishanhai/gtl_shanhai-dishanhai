package com.dishanhai.gt_shanhai.client.holo;

import com.dishanhai.gt_shanhai.GTDishanhaiMod;
import com.dishanhai.gt_shanhai.common.holo.ShanhaiHoloMenuBoards;
import com.dishanhai.gt_shanhai.common.holo.ShanhaiHoloMenuInputRules;
import com.dishanhai.gt_shanhai.common.holo.ShanhaiHoloMenuNetwork;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 山海重构 · 全息菜单的<b>客户端输入层</b>：把用户的四条规格接到真实事件上。
 *
 * <h2>1. 四条规格（用户原话逐字 · 见 {@link ShanhaiHoloMenuInputRules} 的真值表）</h2>
 * <pre>
 *   ① 拿着创始现实修改模块，对【空气】右键 ⇒ 打开全息投影
 *   ② 再右键 ⇒ 关；模块不在玩家身上了 ⇒ 自动关；【其他任何按键都不关】
 *   ③ 右键全息按钮 ⇒ 进配方修改面板
 *   ④ 「拿着模块右键」优先于「右键全息按钮」：
 *      拿着模块时右键按钮 = 关投影，绝不是开面板
 * </pre>
 *
 * <h2>2. 🔴 三个入口（缺一个就有一条路"点了没反应"）</h2>
 * 一次"按下右键"在原版客户端会分叉成<b>互斥的两条</b>。取证是 javap 出来的
 * （{@code Minecraft.startUseItem} 字节码，逐行原文）：
 * <pre>
 *   405: itemstack.isEmpty()
 *   410: ifeq 442                    ← 手里【有东西】才跳过下面这段
 *   413-430: hitResult == null || hitResult.getType() == MISS ?
 *   433:   invokestatic ForgeHooks.onEmptyClick(player, hand)
 *          ↳ ForgeHooks.onEmptyClick : post(new PlayerInteractEvent$RightClickEmpty(player, hand))
 *   442: itemstack.isEmpty()
 *   447: ifne 503                    ← 🔴 空手 ⇒ 【直接跳过 useItem】，这一拍到此为止
 *   450:   gameMode.useItem(player, hand)
 *          ↳ MultiPlayerGameMode.useItem 的预测 lambda 偏移 46:
 *             invokestatic ForgeHooks.onItemRightClick(player, hand)
 *             ↳ ForgeHooks.onItemRightClick : post(new PlayerInteractEvent$RightClickItem(player, hand))
 * </pre>
 * ⇒ <b>{@code isEmpty} 为真只发 {@code RightClickEmpty}、为假只发 {@code RightClickItem}，两者严格互斥</b>；
 * 而<b>空手时 {@code useItem} 被整个跳过</b>。
 *
 * <p>🔴 这一条是<b>用户实测踩出来的</b>（2026-10-06 10:51–10:55 的日志）：
 * 第一版只订阅了 {@code RightClickItem} ⇒ 玩家<b>空着手</b>去点「配方修改」那一格时，
 * 事件<b>一次都不会发</b>，处理器根本进不来 ⇒ 日志里一行都没有、界面上毫无反应
 * （现场：全部 20+ 条记录都是 {@code hand=MAIN_HAND holding_module=true}，
 *  <b>一条 {@code holding_module=false} 都没有</b> —— 因为那些拍压根没进处理器）。
 *
 * <p>⇒ 本类现在挂三个入口，<b>没有一个是"多"的</b>：
 * <table border="1">
 *   <tr><th>入口</th><th>什么时候发</th><th>它单独负责什么</th></tr>
 *   <tr><td>{@code PlayerInteractEvent.RightClickItem}</td><td>手里有物品 + 原版打算 use 它</td>
 *       <td>规格 ①：拿着模块右键（模块是物品 ⇒ 只有这条路会响）</td></tr>
 *   <tr><td>{@code PlayerInteractEvent.RightClickEmpty}</td><td><b>空手</b> + 对空气</td>
 *       <td>🔴 规格 ③：<b>空手点全息按钮</b>（这正是用户报的那一条）</td></tr>
 *   <tr><td>{@code InputEvent.InteractionKeyMappingTriggered}</td>
 *       <td><b>每次按下使用键</b>都会发（{@code startUseItem} 偏移 72，在 {@code isEmpty} 判断【之前】，
 *           每只手各一次）</td>
 *       <td>兜底：空手 + 准星上正好有一格方块（全息板浮在 2.8 格、拾取距离 4.5 格 ⇒
 *           板后面 1.7 格内的方块会被拾取到，那时 {@code RightClickEmpty} 也不发）</td></tr>
 * </table>
 *
 * <h2>3. 三个入口共用的两道闸门（"一次点击只做一件事"）</h2>
 * <ol>
 *   <li><b>长按去抖</b>：{@link #useKeyWasDown} 记住"上一 tick 结束时右键是不是按着的"，
 *       只有<b>新鲜的按下</b>才算一拍（按住不放原版每 4~5 tick 会再调一次 {@code startUseItem}）。
 *       计时口径取证过：{@code Minecraft.tick()} 里 {@code handleKeybinds()} 在偏移 335、
 *       {@code ForgeEventFactory.onPostClientTick()}（= {@code ClientTickEvent.END}）在偏移 828
 *       ⇒ END 一定跑在 handleKeybinds <b>之后</b>，所以事件里读到的就是"上一 tick 的状态"。</li>
 *   <li><b>同一 tick 只认第一拍</b>：{@link #lastActedTick}（只在本拍<b>真的做了动作</b>时占用）
 *       —— 三个入口 × 两只手，最多一拍能进来 5 次，只有第一次执行。</li>
 * </ol>
 *
 * <h2>4. 🔴 "模块不在玩家身上了"到底怎么判（本类的唯一一处自行裁定）</h2>
 * 规格 ② 说「模块不在玩家身上了 ⇒ 自动关」，规格 ④ 又说「进面板要先让模块不在手上」。
 * 这两条只有一种读法能同时成立：
 * <pre>
 *   「不在身上」= 模块【整个离开玩家】（丢掉 / 放进箱子 / 被拿走），
 *   而"换到别的快捷栏格子 / 收进背包"仍然算【在身上】。
 * </pre>
 * 若按"不在手上就关"实现，玩家一换手投影就没了 ⇒ <b>规格 ③（右键按钮进面板）永远做不到</b>。
 * ⇒ 自动关闭的判据 = 主手 ∪ 副手 ∪ 背包（含护甲位/副手那 41 格）都没有那块模块。
 * <p>⚠️ 与"能不能点按钮"是<b>两个不同的开关</b>：
 * {@code holding_module}（= 主手 ∪ 副手，<b>从来看背包</b>）只决定"这一拍是不是拿着模块右键"；
 * 玩家把模块收进背包后，投影<b>照旧开着</b>，而 {@code holding_module} 已经是 false ⇒ 能点按钮。
 *
 * <h2>5. 本类【不做】的事</h2>
 * <ul>
 *   <li>不取消事件、不改 {@code setUseItem}：那块模块是个普通物品，右键本来什么也不做，
 *       留着原版行为不动最安全（"不许弄坏已验收的东西"）。</li>
 *   <li>不在这里开面板 —— 面板必须走服务端权威路径，见 {@link ShanhaiHoloMenuNetwork}。</li>
 *   <li>开面板时<b>不</b>顺手关掉投影：规格 ② 把"关"的入口列成了<b>只有两种</b>，
 *       多一个关闭时机就是与规格不符。</li>
 * </ul>
 */
@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(modid = GTDishanhaiMod.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ShanhaiHoloMenuInput {

    public static final String PREFIX = "[SHANHAI-HOLO]";

    /** 触发这件东西的物品 = 创始现实修改模块（第 17 档）。 */
    public static final ResourceLocation MODULE_ID =
            new ResourceLocation("dishanhai", "create_mk");

    /** 上一 tick 结束时"使用物品"键是不是按着的（长按去抖用；口径见类注释 §2 第 3 条）。 */
    private static boolean useKeyWasDown;

    /** 同一个游戏 tick 里只认第一拍（主手/副手 × 三个入口，最多一拍进来 5 次）。 */
    private static int lastActedTick = Integer.MIN_VALUE;

    /** 诊断日志的"同一 tick 只留一行"闸门（与 {@link #lastActedTick} 分开：
     *  前者只管"做没做动作"，这个只管"记不记一行"，两件事互不干扰）。 */
    private static int lastLoggedTick = Integer.MIN_VALUE;

    /** 自检只在进世界后的第一个 tick 打一次。 */
    private static boolean selfTestLogged;

    /** 懒解析的物品句柄（注册表冻结后才可能被调到）。 */
    private static Item moduleItem;

    private ShanhaiHoloMenuInput() {}

    // ------------------------------------------------------------------ 物品判定

    /** 这一叠是不是创始现实修改模块。 */
    public static boolean isModule(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        Item cached = moduleItem;
        if (cached == null) {
            cached = BuiltInRegistries.ITEM.get(MODULE_ID);
            if (cached == Items.AIR) {
                return false;                   // 还没注册好：这次就当不是，下次再来
            }
            moduleItem = cached;
        }
        return stack.is(cached);
    }

    /**
     * "模块还在玩家身上吗" —— 主手 ∪ 副手 ∪ 背包（{@code getContainerSize()} = 36 格物品
     * + 4 格护甲 + 1 格副手 = 41）。
     */
    public static boolean moduleAnywhere(Player player) {
        if (player == null) {
            return false;
        }
        if (isModule(player.getMainHandItem()) || isModule(player.getOffhandItem())) {
            return true;
        }
        final Inventory inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (isModule(inv.getItem(i))) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ 三个入口

    /**
     * 入口 1：<b>手里有物品</b> + 原版打算 use 它。
     * <p>规格 ①（拿着模块右键）只有这条路会响 —— 模块是物品。
     */
    @SubscribeEvent
    public static void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        handleClick(event.getEntity(), event.getHand(), "item");
    }

    /**
     * 入口 2：🔴 <b>空着手</b>对空气右键。
     * <p>这就是用户报的「空手点第 2 块没反应」那一条 —— 空手时原版<b>根本不调 {@code useItem}</b>
     * （{@code startUseItem} 偏移 447 {@code ifne 503}），所以 {@code RightClickItem} 永远不来。
     */
    @SubscribeEvent
    public static void onRightClickEmpty(PlayerInteractEvent.RightClickEmpty event) {
        handleClick(event.getEntity(), event.getHand(), "empty");
    }

    /**
     * 入口 3（兜底）：<b>每次按下使用键</b>都会发 —— 它在 {@code startUseItem} 偏移 72，
     * <b>在 {@code isEmpty} 判断之前</b>，每只手各一次。
     * <p>兜的是"空手 + 准星上刚好有一格方块"：全息板浮在 2.8 格、拾取距离 4.5 格
     * ⇒ 板后面 1.7 格内的方块会被拾取到，那时 {@code RightClickEmpty} 也不发。
     */
    @SubscribeEvent
    public static void onInteractionKey(net.minecraftforge.client.event.InputEvent.InteractionKeyMappingTriggered event) {
        if (!event.isUseItem()) {
            return;                             // 左键攻击 / 中键选取走的是别的路
        }
        handleClick(Minecraft.getInstance().player, event.getHand(), "key");
    }

    /**
     * 三个入口共用的处理核心（<b>判什么全在 {@link ShanhaiHoloMenuInputRules} 那张真值表里</b>，
     * 这里只负责把现场翻译成它要的那几个布尔与一个下标）。
     */
    private static void handleClick(Player rawPlayer, InteractionHand hand, String source) {
        final Minecraft mc = Minecraft.getInstance();
        final LocalPlayer lp = mc.player;
        if (lp == null || mc.level == null || rawPlayer != lp) {
            return;
        }
        final boolean mainHas = isModule(lp.getMainHandItem());
        final boolean offHas = isModule(lp.getOffhandItem());
        if (mainHas || offHas) {
            // 同一次点击会按 MAIN_HAND → OFF_HAND 各来一次 ⇒ 只认"模块所在的那只手"
            final InteractionHand moduleHand = mainHas ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND;
            if (hand != moduleHand) {
                return;
            }
        }
        // 🔴 只算手：这就是"拿着模块右键"这个开关的全部口径（**从来看背包**）。
        final boolean holdingModule = mainHas || offHas;
        final boolean freshPress = !useKeyWasDown;

        // 🔴🔴 2026-10-06：二级面板（设置 / 命令）开着的时候，这一拍打的是【面板的行】，
        //    而菜单那五块板此刻根本没画出来 ⇒ 必须在这里分一次流。
        //    ⚠️ "拿着模块右键 = 切换投影"这一条在面板里【仍然成立】（它是已验收的规格 ①②），
        //       所以分出去的那条路第一件事就是把这一条原样做掉（见 handlePanelClick）。
        if (ShanhaiHoloMenuState.panelOpen()) {
            handlePanelClick(mc, lp, hand, source, holdingModule, freshPress);
            return;
        }

        final HitResult hr = mc.hitResult;
        final boolean airHit = hr == null || hr.getType() == HitResult.Type.MISS;
        final boolean menuEnabled = ShanhaiHoloMenuState.enabled();

        // ⚠️ 射线【不】只在菜单开着时才算：投影关着的时候也要能回答"他刚才是不是在点那块板"
        //    （否则"点了没反应"又变成一条查不动的现象 —— 上一轮就是这么卡住的）。
        final Vec3 eye = lp.getEyePosition(1.0f);
        final Vec3 look = lp.getViewVector(1.0f);
        final Vec3 anchor = ShanhaiHoloMenuState.position();
        final ShanhaiHoloMenuHitTest.Hit hit = ShanhaiHoloMenuHitTest.raycast(
                ShanhaiHoloMenuState.layoutName(), ShanhaiHoloMenuState.distance(),
                anchor.x, anchor.y, anchor.z, ShanhaiHoloMenuState.frameYaw(),
                eye.x, eye.y, eye.z, look.x, look.y, look.z);
        final int boardIndex = hit == null ? -1 : hit.index();
        final double boardDistance = hit == null ? -1.0 : hit.distance();
        // "板没被挡住" = 打不到任何东西，或板比原版打到的方块/实体更近
        final boolean boardClear = hit != null && (airHit || hit.distance() < vanillaHitDistance(hr, eye));

        final int action = ShanhaiHoloMenuInputRules.decide(
                holdingModule, freshPress, airHit, boardIndex, boardClear, menuEnabled);

        // 每一拍"可能有事"的现场都留一行（同一 tick 只留一行）。
        // 判据：投影开着 / 拿着模块 / 打到了板 —— 三者全不成立才是"普通玩游戏的右键"，那时不记。
        if (freshPress && lp.tickCount != lastLoggedTick
                && (menuEnabled || holdingModule || boardIndex >= 0)) {
            lastLoggedTick = lp.tickCount;
            GTDishanhaiMod.LOGGER.info("{} holo_click source={} action={} player={} hand={} {}",
                    PREFIX, source, ShanhaiHoloMenuInputRules.actionName(action),
                    lp.getGameProfile().getName(), hand,
                    ShanhaiHoloMenuInputRules.contextLine(holdingModule, freshPress, airHit,
                            boardIndex, boardClear, menuEnabled));
        }

        if (action == ShanhaiHoloMenuInputRules.ACT_NONE) {
            return;
        }
        // 同一 tick 只执行第一次（三个入口 × 两只手，最多能进来 5 次）
        if (lp.tickCount == lastActedTick) {
            return;
        }
        lastActedTick = lp.tickCount;

        final String context = ShanhaiHoloMenuInputRules.contextLine(
                holdingModule, freshPress, airHit, boardIndex, boardClear, menuEnabled);
        if (action == ShanhaiHoloMenuInputRules.ACT_TOGGLE_MENU) {
            final boolean now = ShanhaiHoloMenuState.toggle();
            GTDishanhaiMod.LOGGER.info("{} holo_menu_toggled_by_item player={} hand={} now={} src={} {}",
                    PREFIX, lp.getGameProfile().getName(), hand, now, source, context);
        } else if (action == ShanhaiHoloMenuInputRules.ACT_OPEN_EDITOR) {
            // 🔴 加载期间再点 ⇒ 【不再发包】。除了"同一 tick 只认第一拍"那道闸门，
            //    这里再加一道跨 tick 的闸门：从"点了"到"面板出来"之间那一整段都算加载中。
            if (!ShanhaiHoloMenuPending.begin(boardIndex, System.nanoTime())) {
                GTDishanhaiMod.LOGGER.info("{} holo_board_click SKIPPED reason=already_loading board={} "
                                + "player={} src={} {}",
                        PREFIX, ShanhaiHoloMenuBoards.boardNumber(boardIndex),
                        lp.getGameProfile().getName(), source, context);
                return;
            }
            GTDishanhaiMod.LOGGER.info("{} holo_board_click board={} idx={} label={} dist={} player={} src={} "
                            + "loading_started=true {}",
                    PREFIX, ShanhaiHoloMenuBoards.boardNumber(boardIndex), boardIndex,
                    ShanhaiHoloMenuBoards.labelOf(boardIndex),
                    String.format(java.util.Locale.ROOT, "%.3f", boardDistance),
                    lp.getGameProfile().getName(), source, context);
            // 服务端权威路径：只把板号发过去，开面板由服务端做。
            // ⚠️ 这一句之后客户端【不做任何等待】—— 面板什么时候出来由服务端决定，
            //    客户端只负责把"正在加载"画出来（用户点名：加载期间不许阻塞游戏）。
            ShanhaiHoloMenuNetwork.requestEditor(boardIndex);
        } else if (action == ShanhaiHoloMenuInputRules.ACT_OPEN_SETTINGS) {
            // 🆕 2026-10-06：第 5 格「设置」⇒ 打开全息设置面板（纯客户端，不发包、不碰服务端状态）
            ShanhaiHoloMenuState.setPanelMode(ShanhaiHoloMenuPanel.MODE_SETTINGS);
            GTDishanhaiMod.LOGGER.info("{} holo_panel_opened panel={} board={} idx={} player={} src={} {}",
                    PREFIX, ShanhaiHoloMenuPanel.modeName(ShanhaiHoloMenuPanel.MODE_SETTINGS),
                    ShanhaiHoloMenuBoards.boardNumber(boardIndex), boardIndex,
                    lp.getGameProfile().getName(), source, context);
        } else if (action == ShanhaiHoloMenuInputRules.ACT_OPEN_COMMAND) {
            // 🆕 2026-10-06：第 4 格「命令」⇒ 打开全息命令面板（输入框由 Screen 接管键盘）
            ShanhaiHoloMenuState.setPanelMode(ShanhaiHoloMenuPanel.MODE_COMMAND);
            GTDishanhaiMod.LOGGER.info("{} holo_panel_opened panel={} board={} idx={} player={} src={} {}",
                    PREFIX, ShanhaiHoloMenuPanel.modeName(ShanhaiHoloMenuPanel.MODE_COMMAND),
                    ShanhaiHoloMenuBoards.boardNumber(boardIndex), boardIndex,
                    lp.getGameProfile().getName(), source, context);
        } else {
            // 认不出来的动作码（表里没有的那种）—— 不许静默：这是一条"点了没反应"的唯一痕迹
            GTDishanhaiMod.LOGGER.warn("{} holo_board_click_unhandled action={} board_idx={} player={} src={} {}",
                    PREFIX, ShanhaiHoloMenuInputRules.actionName(action), boardIndex,
                    lp.getGameProfile().getName(), source, context);
        }
    }

    /** 原版那次拾取的命中距离；没有命中返回 {@link Double#MAX_VALUE}。 */
    private static double vanillaHitDistance(HitResult hr, Vec3 eye) {
        if (hr == null || hr.getType() == HitResult.Type.MISS || hr.getLocation() == null) {
            return Double.MAX_VALUE;
        }
        return hr.getLocation().distanceTo(eye);
    }

    // ================================================================== 🆕 二级面板（设置 / 命令）

    /**
     * 面板开着时的那一拍右键。
     *
     * <h4>为什么它必须是一条独立的路</h4>
     * 面板画的是<b>另一列板</b>（{@link ShanhaiHoloMenuPanel#rows()}），五块菜单板此刻没画出来。
     * 拿菜单那条 {@code decide(...)} 真值表去判一个"面板上的行号"是没有意义的
     * （那张表的每一格都写着"第几块菜单板"）⇒ 这里单独一条，规则只有两条：
     * <pre>
     *   ① 拿着模块右键 ⇒ 切换投影（<b>已验收的规格 ①②</b>，在面板里也必须原样成立）；
     *   ② 否则 ⇒ 按 {@link ShanhaiHoloMenuPanel#clickAction} 那张表做那一格的事。
     * </pre>
     * 两道去抖闸门与菜单那条路<b>同一口径</b>（新鲜按下 / 同一 tick 只认第一拍），
     * 用的也是同一对静态字段 ⇒ 面板与菜单不可能各自去抖出两套手感。
     */
    private static void handlePanelClick(Minecraft mc, LocalPlayer lp, InteractionHand hand,
                                         String source, boolean holdingModule, boolean freshPress) {
        final int mode = ShanhaiHoloMenuState.panelMode();
        if (!freshPress) {
            return;                                 // 长按/连发只算一拍（与菜单同口径）
        }
        final HitResult hr = mc.hitResult;
        final boolean airHit = hr == null || hr.getType() == HitResult.Type.MISS;
        final Vec3 eye = lp.getEyePosition(1.0f);
        final Vec3 look = lp.getViewVector(1.0f);
        // 🆕 2026-10-06（用户实测第 ③ 条）：面板的锚点与框偏航一律走状态层
        //    （= 与渲染器同一个函数）。修复前这里用 lp.getYRot() 现算 ⇒ 渲染器与命中判定
        //    各自算一遍，面板"世界固定"就永远对不上。
        final double[] c = ShanhaiHoloMenuState.panelCenter(eye);
        final float fy = ShanhaiHoloMenuState.panelFrameYaw();
        final ShanhaiHoloMenuHitTest.Hit hit = ShanhaiHoloMenuHitTest.raycastRows(
                ShanhaiHoloMenuPanel.rows(), c[0], c[1], c[2], fy,
                eye.x, eye.y, eye.z, look.x, look.y, look.z);
        // 🆕 命令面板只剩两行：没画出来的行不许被指到/点到（否则会出现"点到看不见的东西"）
        final int rawRow = hit == null ? -1 : hit.index();
        final int row = ShanhaiHoloMenuPanel.rowVisible(mode, rawRow) ? rawRow : -1;
        final int cell = row < 0 ? -1 : ShanhaiHoloMenuPanel.cellOf(hit.localX());
        // "没被挡住"与菜单同一条口径：打不到任何东西，或面板比原版打到的方块/实体更近
        //   ⚠️ 分开两个量：几何上的"打得着"（几何clear）与"这一格真的存在"（clear）。
        //      拿着模块那一条路【只认几何】（不然"准星落在命令面板没画出来的那一行"时
        //      会不会切投影，会跟修复前不一样 —— 那是已验收的手感，不许改）。
        final boolean geometricClear = hit != null
                && (airHit || hit.distance() < vanillaHitDistance(hr, eye));
        final boolean clear = geometricClear && row >= 0;
        final int click = clear
                ? ShanhaiHoloMenuPanel.clickAction(mode, row, cell)
                : ShanhaiHoloMenuPanel.CLICK_NONE;

        final boolean moduleTap = holdingModule
                && (airHit || geometricClear);
        // 🆕 第 ③ 条：把面板框当前的偏航也打出来 —— "世界固定"生效时玩家扭头它不变，一眼可核
        GTDishanhaiMod.LOGGER.info("{} holo_panel_click source={} player={} hand={} panel={} "
                        + "row={} cell={} board_clear={} air_hit={} holding_module={} click={} "
                        + "panel_yaw={} panel_frame_yaw={}",
                PREFIX, source, lp.getGameProfile().getName(), hand,
                ShanhaiHoloMenuPanel.modeName(mode), row, cell, clear, airHit, holdingModule,
                ShanhaiHoloMenuPanel.clickName(click),
                String.format(java.util.Locale.ROOT, "%.3f", ShanhaiHoloMenuState.panelYawDeg()),
                String.format(java.util.Locale.ROOT, "%.3f", ShanhaiHoloMenuState.panelFrameYaw()));

        // ① 拿着模块 ⇒ 规格 ①②：切换投影（开着就关；面板随投影一起收）
        if (holdingModule) {
            if (!moduleTap) {
                return;                             // 面板被更近的方块挡住 / 没打中东西 ⇒ 不关
            }
            if (lp.tickCount == lastActedTick) {
                return;
            }
            lastActedTick = lp.tickCount;
            final boolean now = ShanhaiHoloMenuState.toggle();
            GTDishanhaiMod.LOGGER.info("{} holo_menu_toggled_by_item player={} hand={} now={} src={} "
                            + "panel_was={}",
                    PREFIX, lp.getGameProfile().getName(), hand, now, source,
                    ShanhaiHoloMenuPanel.modeName(mode));
            return;
        }
        // ② 面板格子
        if (click == ShanhaiHoloMenuPanel.CLICK_NONE) {
            return;
        }
        if (lp.tickCount == lastActedTick) {
            return;
        }
        lastActedTick = lp.tickCount;
        applyPanelClick(mc, lp, click, mode, row, cell, source);
    }

    /** 把一格的动作真正做掉（<b>全部即时生效、不发包</b>，除了"执行命令"那一条）。 */
    private static void applyPanelClick(Minecraft mc, LocalPlayer lp, int click, int mode,
                                        int row, int cell, String source) {
        switch (click) {
            case ShanhaiHoloMenuPanel.CLICK_CLOSE -> {
                ShanhaiHoloMenuState.closePanel();
                GTDishanhaiMod.LOGGER.info("{} holo_panel_closed how=return player={} src={}",
                        PREFIX, lp.getGameProfile().getName(), source);
            }
            case ShanhaiHoloMenuPanel.CLICK_DIST_DOWN ->
                    setDistanceAndLog(lp, ShanhaiHoloMenuState.distance()
                            - ShanhaiHoloMenuPanel.DISTANCE_STEP, source);
            case ShanhaiHoloMenuPanel.CLICK_DIST_UP ->
                    setDistanceAndLog(lp, ShanhaiHoloMenuState.distance()
                            + ShanhaiHoloMenuPanel.DISTANCE_STEP, source);
            case ShanhaiHoloMenuPanel.CLICK_FACING_FOLLOW -> {
                ShanhaiHoloMenuState.setFacing(ShanhaiHoloMenuState.Facing.FOLLOW);
                logPanelChoice(lp, "朝向=平滑跟随", source);
            }
            case ShanhaiHoloMenuPanel.CLICK_FACING_SNAP -> {
                ShanhaiHoloMenuState.setFacing(ShanhaiHoloMenuState.Facing.SNAP);
                logPanelChoice(lp, "朝向=立即转向", source);
            }
            case ShanhaiHoloMenuPanel.CLICK_FACING_FIXED -> {
                ShanhaiHoloMenuState.setFacing(ShanhaiHoloMenuState.Facing.FIXED);
                logPanelChoice(lp, "朝向=世界固定", source);
            }
            case ShanhaiHoloMenuPanel.CLICK_LAYOUT_RING -> {
                ShanhaiHoloMenuState.setLayout(ShanhaiHoloMenuState.Layout.RING);
                logPanelChoice(lp, "布局=环绕", source);
            }
            case ShanhaiHoloMenuPanel.CLICK_LAYOUT_COLUMN -> {
                ShanhaiHoloMenuState.setLayout(ShanhaiHoloMenuState.Layout.COLUMN);
                logPanelChoice(lp, "布局=竖列", source);
            }
            case ShanhaiHoloMenuPanel.CLICK_LAYOUT_ROW -> {
                ShanhaiHoloMenuState.setLayout(ShanhaiHoloMenuState.Layout.ROW);
                logPanelChoice(lp, "布局=横排", source);
            }
            case ShanhaiHoloMenuPanel.CLICK_CMD_TYPE -> openCommandScreen(mc, source);
            case ShanhaiHoloMenuPanel.CLICK_CMD_RUN -> runCommand(lp, source);
            case ShanhaiHoloMenuPanel.CLICK_CMD_CLEAR -> {
                ShanhaiHoloMenuPanel.clear();
                ShanhaiHoloMenuPanel.setLastResult("（已清空）");
                GTDishanhaiMod.LOGGER.info("{} holo_cmd_cleared player={} src={}",
                        PREFIX, lp.getGameProfile().getName(), source);
            }
            // ---- 🆕 2026-10-06（第二轮）：环绕特效四档 + 高级调参页 ----
            case ShanhaiHoloMenuPanel.CLICK_PRESET_OFF,
                 ShanhaiHoloMenuPanel.CLICK_PRESET_WEAK,
                 ShanhaiHoloMenuPanel.CLICK_PRESET_MID,
                 ShanhaiHoloMenuPanel.CLICK_PRESET_STRONG ->
                    setSurroundPresetAndLog(lp, ShanhaiHoloMenuPanel.presetOfClick(click), source);
            case ShanhaiHoloMenuPanel.CLICK_ADV_OPEN -> {
                // 进第二层：每一层都回到第 1 页（"上次翻到哪"不该变成第二层的隐藏状态）
                ShanhaiHoloMenuPanel.setAdvPage(0);
                ShanhaiHoloMenuState.setPanelMode(ShanhaiHoloMenuPanel.MODE_ADVANCED);
                GTDishanhaiMod.LOGGER.info("{} holo_panel_opened panel={} page=1/{} player={} src={}",
                        PREFIX, ShanhaiHoloMenuPanel.modeName(ShanhaiHoloMenuPanel.MODE_ADVANCED),
                        ShanhaiHoloMenuPanel.advPageCount(), lp.getGameProfile().getName(), source);
            }
            case ShanhaiHoloMenuPanel.CLICK_ADV_PREV -> flipAdvPageAndLog(lp, -1, source);
            case ShanhaiHoloMenuPanel.CLICK_ADV_NEXT -> flipAdvPageAndLog(lp, +1, source);
            case ShanhaiHoloMenuPanel.CLICK_ADV_BACK -> {
                // 🔴 返回 = 回【第一层设置面板】，不是关面板（关面板那一格在第一层的「返回」）
                ShanhaiHoloMenuState.setPanelMode(ShanhaiHoloMenuPanel.MODE_SETTINGS);
                GTDishanhaiMod.LOGGER.info("{} holo_panel_back_to_settings player={} src={}",
                        PREFIX, lp.getGameProfile().getName(), source);
            }
            case ShanhaiHoloMenuPanel.CLICK_PARAM_DOWN -> nudgeParamAndLog(lp, row, -1, source);
            case ShanhaiHoloMenuPanel.CLICK_PARAM_UP -> nudgeParamAndLog(lp, row, +1, source);
            default -> GTDishanhaiMod.LOGGER.warn("{} holo_panel_click_unhandled click={} mode={} row={} cell={}",
                    PREFIX, click, mode, row, cell);
        }
    }

    /** 切环绕特效档位（整组替换）—— 面板与日志读的是同一份状态。 */
    private static void setSurroundPresetAndLog(LocalPlayer lp, int preset, String source) {
        if (preset < 0) {
            return;
        }
        final int now = ShanhaiHoloSurround.setPreset(preset);
        GTDishanhaiMod.LOGGER.info("{} holo_surround_preset preset={} name={} player={} src={} | {}",
                PREFIX, now, ShanhaiHoloSurroundTuning.presetName(now),
                lp.getGameProfile().getName(), source, ShanhaiHoloSurroundTuning.summary());
    }

    /** 高级页翻页（到头停住）。 */
    private static void flipAdvPageAndLog(LocalPlayer lp, int delta, String source) {
        final int before = ShanhaiHoloMenuPanel.advPage();
        ShanhaiHoloMenuPanel.flipAdvPage(delta);
        final int now = ShanhaiHoloMenuPanel.advPage();
        GTDishanhaiMod.LOGGER.info("{} holo_adv_page page={}/{} (was {}) category={} player={} src={}",
                PREFIX, now + 1, ShanhaiHoloMenuPanel.advPageCount(), before + 1,
                ShanhaiHoloMenuPanel.pageCategories(), lp.getGameProfile().getName(), source);
    }

    /**
     * 高级页某一行的 ± —— <b>具体改哪一项由 (行, 当前页) 现算</b>
     * （见 {@link ShanhaiHoloMenuPanel#advParamIndex}），不在这里再写一套行号。
     */
    private static void nudgeParamAndLog(LocalPlayer lp, int row, int dir, String source) {
        final int idx = ShanhaiHoloMenuPanel.advParamIndex(row);
        if (idx < 0) {
            GTDishanhaiMod.LOGGER.warn("{} holo_adv_nudge_ignored row={} page={}/{}（这一行本页没有项）",
                    PREFIX, row, ShanhaiHoloMenuPanel.advPage() + 1,
                    ShanhaiHoloMenuPanel.advPageCount());
            return;
        }
        final float now = ShanhaiHoloSurround.nudgeParam(idx, dir);
        GTDishanhaiMod.LOGGER.info("{} holo_adv_nudge param={} name={} dir={} now={} player={} src={}",
                PREFIX, idx + 1, ShanhaiHoloSurroundTuning.PARAM_NAME[idx], dir,
                ShanhaiHoloSurroundTuning.paramText(idx), lp.getGameProfile().getName(), source);
    }

    private static void setDistanceAndLog(LocalPlayer lp, float want, String source) {
        final float d = ShanhaiHoloMenuState.setDistance(want);
        GTDishanhaiMod.LOGGER.info("{} holo_panel_set what=distance value={} player={} src={} state{{{}}}",
                PREFIX, d, lp.getGameProfile().getName(), source, ShanhaiHoloMenuState.describe());
    }

    private static void logPanelChoice(LocalPlayer lp, String what, String source) {
        GTDishanhaiMod.LOGGER.info("{} holo_panel_set what={} player={} src={} state{{{}}}",
                PREFIX, what, lp.getGameProfile().getName(), source, ShanhaiHoloMenuState.describe());
    }

    /**
     * 打开键盘输入框（<b>透明 Screen</b>）。
     * <p>选 Screen 而不是"在渲染层自己接键"的理由写在 {@link ShanhaiHoloCommandScreen} 的类注释里
     * （IME / 键盘焦点 / ESC 优先级三件事都是原版已经做对、手搓必然做错的）。
     */
    private static void openCommandScreen(Minecraft mc, String source) {
        if (mc.screen != null) {
            GTDishanhaiMod.LOGGER.info("{} holo_cmd_screen_skip reason=a_screen_is_already_open screen={} src={}",
                    PREFIX, mc.screen.getClass().getName(), source);
            return;
        }
        mc.setScreen(new ShanhaiHoloCommandScreen());
        GTDishanhaiMod.LOGGER.info("{} holo_cmd_screen_opened src={} text_len={}",
                PREFIX, source, ShanhaiHoloMenuPanel.command().length());
    }

    /**
     * 执行命令：<b>把整条字符串发给服务端，由服务端以【管理员权限】执行</b>。
     * <p>🔴 客户端<b>不</b>自己执行 —— 客户端根本没有服务端的命令树（{@code /give} 之类
     * 只存在于服务端），而且"以 op 身份执行"这件事只能由服务端来宣称（{@code withPermission(4)}）。
     */
    private static void runCommand(LocalPlayer lp, String source) {
        submitCommand(ShanhaiHoloMenuPanel.command(), source + "/board");
    }

    /**
     * 把一条命令送到服务端执行 —— <b>唯一的出口</b>。
     *
     * <p>「点面板上的『执行』」与「在输入框里按回车」是两条入口，但它们在<b>这里</b>汇合：
     * 判空、日志、面板上那行读数都只有这一份实现（本工程血规矩：同一个决定只能有一个落点）。
     *
     * @param raw    待执行的命令原文（可以带前导斜杠，服务端两种都吃）
     * @param source 谁触发的（进日志；{@code board} = 点面板那一格，{@code screen} = 回车）
     * @return true = 真的发出去了
     */
    public static boolean submitCommand(String raw, String source) {
        final String cmd = raw == null ? "" : raw.trim();
        if (cmd.isEmpty()) {
            ShanhaiHoloMenuPanel.setLastResult("空命令，没发");
            GTDishanhaiMod.LOGGER.info("{} holo_cmd_skipped reason=empty src={}", PREFIX, source);
            return false;
        }
        ShanhaiHoloMenuPanel.setLastResult("已发出：" + ShanhaiHoloMenuPanel.clip(cmd, 18));
        ShanhaiHoloMenuNetwork.requestCommand(cmd);
        GTDishanhaiMod.LOGGER.info("{} holo_cmd_submit src={} cmd={}", PREFIX, source, cmd);
        return true;
    }

    /**
     * 每 tick 刷一次"射线指着面板的哪一行"（只影响高亮）。
     *
     * <p>放在 tick 而不是渲染帧：<b>面板的高亮要跟点击用同一条口径</b>
     * （含"被方块挡住就不算指着"这一条），而那条口径读的是 {@code mc.hitResult}
     * —— 它是每 tick 由原版算好的。20 Hz 的悬停高亮足够，而且不会把"每帧一次射线"
     * 塞进渲染热路径里。
     */
    private static void updatePanelHover(Minecraft mc, LocalPlayer lp) {
        final Vec3 eye = lp.getEyePosition(1.0f);
        final Vec3 look = lp.getViewVector(1.0f);
        // 🆕 第 ③ 条：与渲染器、与点击同一个函数（面板的"世界固定"三处必须同口径）
        final double[] c = ShanhaiHoloMenuState.panelCenter(eye);
        final float fy = ShanhaiHoloMenuState.panelFrameYaw();
        final ShanhaiHoloMenuHitTest.Hit hit = ShanhaiHoloMenuHitTest.raycastRows(
                ShanhaiHoloMenuPanel.rows(), c[0], c[1], c[2], fy,
                eye.x, eye.y, eye.z, look.x, look.y, look.z);
        final int mode = ShanhaiHoloMenuState.panelMode();
        if (hit == null || !ShanhaiHoloMenuPanel.rowVisible(mode, hit.index())) {
            // 🆕 没画出来的行不算"指着"（命令面板只剩两行，射线可能穿过空行打到后面的板上）
            ShanhaiHoloMenuState.setPanelHover(-1, -1);
            return;
        }
        final HitResult hr = mc.hitResult;
        final boolean airHit = hr == null || hr.getType() == HitResult.Type.MISS;
        if (!(airHit || hit.distance() < vanillaHitDistance(hr, eye))) {
            ShanhaiHoloMenuState.setPanelHover(-1, -1);      // 被更近的东西挡住了 ⇒ 不算指着
            return;
        }
        ShanhaiHoloMenuState.setPanelHover(hit.index(), ShanhaiHoloMenuPanel.cellOf(hit.localX()));
    }

    // ------------------------------------------------------------------ 每 tick

    /**
     * 三个职责：
     * <ol>
     *   <li>更新"右键是不是按着的"（必须跑在 {@code handleKeybinds} 之后，见类注释 §2）；</li>
     *   <li>规格 ② 的第二个关闭入口：<b>模块整个离开玩家</b> ⇒ 自动关投影；</li>
     *   <li>🆕 「正在加载」状态机：<b>面板一出来就把提示收掉</b>（超时/投影被关掉也要收）。
     *       ⚠️ 它只是"读一眼 {@code mc.screen}"并清状态，<b>不做任何等待</b>。</li>
     * </ol>
     */
    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        final Minecraft mc = Minecraft.getInstance();
        final LocalPlayer lp = mc.player;
        useKeyWasDown = lp != null && mc.screen == null && mc.options.keyUse.isDown();
        if (lp == null || mc.level == null) {
            return;
        }
        if (!selfTestLogged) {
            selfTestLogged = true;
            logSelfTests();
        }

        // ---- 🆕 「正在加载」的收尾 ----
        if (ShanhaiHoloMenuPending.isPending()) {
            final long now = System.nanoTime();
            // ⚠️ 已等时长必须在 onTick【之前】取：onTick 一旦把状态收掉，elapsedMs 就回到 -1 了
            //    （第一版就是收完再取，日志里永远打 elapsed_ms=-1 —— 一条会撒谎的读数）。
            final long elapsed = ShanhaiHoloMenuPending.elapsedMs(now);
            // 🔴🔴 2026-10-06：判"面板出现了"时【必须排掉我们自己的命令输入框】。
            //    那个 Screen 是**完全透明的键盘接收器**（用户看到的还是全息板），它不是配方面板；
            //    不排掉的话会有一条**会撒谎的读数**：
            //      点「配方修改」（开始加载）→ 3 秒内又点开「命令」输入框
            //      ⇒ mc.screen != null ⇒ 这里当场记一条 holo_loading_done reason=screen_opened
            //      ⇒ 日志说"面板出现、加载完成"，而配方编辑器其实还没开
            //        （用户看到的是"正在加载"那根走马灯提前消失了）。
            //    ⇒ 口径：任何**别的** Screen 照旧算"面板出现"（一个字没改），只把这一种排掉。
            final boolean editorUp = mc.screen != null
                    && !(mc.screen instanceof ShanhaiHoloCommandScreen);
            final int reason = ShanhaiHoloMenuPending.onTick(
                    editorUp, ShanhaiHoloMenuState.enabled(), now);
            if (reason != ShanhaiHoloMenuPending.REASON_NONE) {
                final String screen = mc.screen == null ? "none" : mc.screen.getClass().getName();
                if (reason == ShanhaiHoloMenuPending.REASON_TIMEOUT) {
                    // 等了 3 秒还没看到面板 ⇒ 这一条是"服务端没开成/包丢了"的唯一可见痕迹
                    GTDishanhaiMod.LOGGER.warn("{} holo_loading_done reason={} elapsed_ms={} screen={} "
                                    + "（等超时了：面板没等到；看上面有没有 holo_editor_opened ok=false）",
                            PREFIX, ShanhaiHoloMenuPending.reasonName(reason), elapsed, screen);
                } else {
                    GTDishanhaiMod.LOGGER.info("{} holo_loading_done reason={} elapsed_ms={} screen={}",
                            PREFIX, ShanhaiHoloMenuPending.reasonName(reason), elapsed, screen);
                }
            }
        }

        if (!ShanhaiHoloMenuState.enabled()) {
            ShanhaiHoloMenuState.setPanelHover(-1, -1);
            return;
        }
        // 🆕 面板：每 tick 刷一次"射线指着哪一行"（只影响高亮；判定在点击那一拍做）
        if (ShanhaiHoloMenuState.panelOpen()) {
            updatePanelHover(mc, lp);
        } else {
            ShanhaiHoloMenuState.setPanelHover(-1, -1);
        }
        if (!moduleAnywhere(lp)) {
            ShanhaiHoloMenuState.setEnabled(false);
            GTDishanhaiMod.LOGGER.info("{} holo_menu_auto_closed reason=module_gone_from_player player={} "
                            + "hand_main={} hand_off={} inv_slots={}",
                    PREFIX, lp.getGameProfile().getName(),
                    isModule(lp.getMainHandItem()), isModule(lp.getOffhandItem()),
                    lp.getInventory().getContainerSize());
        }
    }

    /** 把几张离线自检表打进日志（进世界后第一个 tick 一次）。 */
    private static void logSelfTests() {
        GTDishanhaiMod.LOGGER.info("{} {}", PREFIX, ShanhaiHoloMenuInputRules.selfTestLine());
        GTDishanhaiMod.LOGGER.info("{} {}", PREFIX, ShanhaiHoloMenuHitTest.selfTestLine());
        GTDishanhaiMod.LOGGER.info("{} {}", PREFIX, ShanhaiHoloMenuPending.selfTestLine());
        GTDishanhaiMod.LOGGER.info("{} {}", PREFIX, ShanhaiHoloMenuPanel.selfTestLine());
        GTDishanhaiMod.LOGGER.info("{} {} {}", PREFIX, ShanhaiHoloMenuNetwork.statsLine(),
                "module_id=" + MODULE_ID);
    }
}
