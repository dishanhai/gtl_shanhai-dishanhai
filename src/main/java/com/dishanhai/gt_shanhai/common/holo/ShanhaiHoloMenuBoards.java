package com.dishanhai.gt_shanhai.common.holo;

import com.dishanhai.gt_shanhai.GTDishanhaiMod;

/**
 * 山海重构 · 悬浮全息菜单的<b>五块板的唯一一份定义</b>（<b>两侧都会加载的 common 类</b>）。
 *
 * <h2>1. 为什么它不能留在 {@code com.shanhai.client.holo} 里</h2>
 * 板子的标题原本只写在客户端那个纯渲染类 {@code ShanhaiHoloMenuTuning}（带
 * {@code @OnlyIn(CLIENT)}）里。这张表要<b>同时</b>被三处读：
 * <pre>
 *   ① 客户端渲染器（画标题、画「未启用」）与客户端输入层（判定"这块板能不能点"）
 *   ② 服务端（收到 C2S 之后【必须自己再判一次】这块板能不能用，不能信客户端）
 *   ③ 专服/客户端开机的自检日志（唯一能在机器上判的形态）
 * </pre>
 * ②③ 在专用服务端上跑，引用一个 {@code @OnlyIn(CLIENT)} 的类会直接
 * {@code NoClassDefFoundError}（本工程的老坑，见 {@code ShanhaiRecipeEditorFactory} 的注释）。
 * ⇒ 表只能放在<b>两侧都存在</b>的 common 包里，客户端那份 {@code LABEL_ZH} 改成
 * <b>指向本类的同一个数组</b>（不是复制一份 —— 两处各写一份中文标题必然漂移）。
 *
 * <h2>2. 🔴 用户点单：五块板里哪些能用</h2>
 * 用户原话（逐字）：
 * <blockquote>
 *   拿着创始现实修改模块，对空气右键，打开全息投影，然后再右键关闭
 *   （其他不管按什么都不关闭，除非这个模块已经不在玩家身上了），
 *   然后玩家右键全息按钮（这个优先级低于拿着创始现实修改模块右键，也就是说玩家拿着创始现实修改
 *   模块对全息按钮右键，是关闭全息投影而不是打开面版），就可以进入配方修改面版了
 * </blockquote>
 * <blockquote>
 *   还是5个按键，修改成1个是配方修改，另外4个是未启用
 * </blockquote>
 * <blockquote>
 *   可以在全息屏幕里面把我们的设置加进去了，然后点开配置设置的也是全息的面版
 * </blockquote>
 * <blockquote>
 *   还可以新增一个 cmd 的功能，全息输入框，可以执行cmd的命令，拥有管理员权限
 * </blockquote>
 *
 * <h3>2.1 🔴 2026-10-06（第二次改动）：从「一块可用」变成「三块可用」</h3>
 * 前两条点单把表定成「只有 idx=1「配方修改」可用、其余四块写『未启用』」——那一条用户已经实测通过。
 * 后两条点单<b>又点名要两个新功能</b>（设置面板 / 命令输入框），而板子总共只有 5 格、
 * 用户没有要求加第 6 格 ⇒ <b>只能从原来那 4 格里挑两格启用</b>。
 * <pre>
 *   idx=1 「配方修改」  可用（原样，一个字没动）
 *   idx=4 「设置」      🆕 可用 ⇒ 点开是【全息风格的设置面板】（原来那个槽位本来就叫「设置」）
 *   idx=3 「命令」      🆕 可用 ⇒ 点开是【全息命令输入框】（启用的是原来叫「批量处理」的槽位，
 *                        因为另外两格「配方查询」「配方删除」与"敲一条命令"语义上更不搭）
 *   idx=0 / idx=2       仍然「未启用」、点了没反应
 * </pre>
 * ⚠️ <b>这一条改动动了用户已验收的一项</b>（原来写的是"四格未启用没反应"，现在是"两格"）
 * —— 它是用户这两条点单的<b>必然结果</b>，已写进报告的「偏离」一节与「待你确认」。
 *
 * <h3>2.2 板号口径（日志与报告都用它）</h3>
 * {@code board} 是 <b>1 基</b>（人话口径：board=1 是第 1 格…board=5 是第 5 格），
 * {@code idx} 是 0 基（= 数组下标）。两种都打进日志，避免"看到 board=2 却不知道是第几块"。
 */
public final class ShanhaiHoloMenuBoards {

    private ShanhaiHoloMenuBoards() {}

    /** 菜单板块数。<b>固定 5</b>（用户点单：还是 5 个按键）。 */
    public static final int BOARD_COUNT = 5;

    /** 未启用那几块，板上写的中文（用户点单的原词）。 */
    public static final String DISABLED_LABEL_ZH = "未启用";

    /** 未启用那几块，板上写的英文副标题。 */
    public static final String DISABLED_LABEL_EN = "NOT ENABLED";

    /**
     * 板上的<b>中文主标题</b>（渲染器与输入判定的实际显示口径）。
     * <p>顺序 = 环绕档里 index 递增的方向。
     */
    public static final String[] LABEL_ZH = {
            DISABLED_LABEL_ZH, "配方修改", DISABLED_LABEL_ZH, "命令", "设置"};

    /** 板上的<b>英文副标题</b>。 */
    public static final String[] LABEL_EN = {
            DISABLED_LABEL_EN, "RECIPE EDIT", DISABLED_LABEL_EN, "COMMAND", "SETTINGS"};

    /**
     * 五个槽位<b>原本叫</b>什么（{@link #SLOT_ZH}）。
     * <p>它是<b>历史台账</b>（用户点单把 4 格改成「未启用」之前的原名），也是
     * {@link #checkTable} 判"板上名字有没有漂"的依据；被改过用途的槽位登记在
     * {@link #REPURPOSED_ZH} 里。
     */
    public static final String[] SLOT_ZH = {"配方查询", "配方修改", "配方删除", "批量处理", "设置"};

    /**
     * 🆕 2026-10-06：<b>被"改用途"的槽位</b>（下标 ⇒ 板上显示的名字）。
     * <p>为什么需要这张小表：idx=3 那个槽位历史上叫「批量处理」，本轮用户点单要一个"命令"功能
     * ⇒ 借用它 ⇒ <b>板上的名字与槽位原名【故意】不同</b>。
     * 而 {@link #checkTable} 要抓的正是"板上名字与预期不一致"（名字漂了这类静默 bug）
     * ⇒ 必须有一处明确记下"这一格是被借用的、期望名是「命令」"，
     * 否则判据要么误报（本次自检当场抓到的那一条）、要么为了不误报而变得恒绿。
     * <p>⚠️ 以后要再借一格：<b>必须同时改这里</b>（改 {@code LABEL_ZH} 而不登记会被自检当场拦下）。
     */
    public static final java.util.Map<Integer, String> REPURPOSED_ZH =
            java.util.Map.of(3, "命令");

    /** 第 {@code i} 块板<b>应当</b>显示的板上名字（可用 ⇒ 借用名或槽位名；停用 ⇒ 「未启用」）。 */
    public static String expectedLabelOf(int i, boolean enabledHere) {
        if (!enabledHere) {
            return DISABLED_LABEL_ZH;
        }
        final String repurposed = REPURPOSED_ZH.get(i);
        return repurposed != null ? repurposed : SLOT_ZH[i];
    }

    /**
     * 哪几块板<b>可用</b>。
     * <p>🔴 2026-10-06 第二次改动：{@link #ENABLED_INDEX}（配方修改）、{@link #SETTINGS_INDEX}（设置）、
     * {@link #COMMAND_INDEX}（命令）三块可用；idx=0 与 idx=2 仍然「未启用」、点了没反应。
     */
    public static final boolean[] ENABLED = {false, true, false, true, true};

    /** 「配方修改」那块板的下标 —— <b>唯一能开服务端配方面板</b>的那一块。 */
    public static final int ENABLED_INDEX = 1;

    /** 🆕 「设置」那块板的下标（点开是全息设置面板，纯客户端）。 */
    public static final int SETTINGS_INDEX = 4;

    /** 🆕 「命令」那块板的下标（点开是全息命令输入框；执行时以管理员身份发到服务端）。 */
    public static final int COMMAND_INDEX = 3;

    /** 动作码：这块板什么都不做。 */
    public static final int ACTION_NONE = 0;

    /** 动作码：打开配方编辑器面板（服务端权威路径）。 */
    public static final int ACTION_RECIPE_EDIT = 1;

    /** 🆕 动作码：打开全息设置面板（纯客户端；不改任何服务端状态）。 */
    public static final int ACTION_HOLO_SETTINGS = 2;

    /** 🆕 动作码：打开全息命令输入框（客户端开输入框；命令本身以管理员身份发到服务端执行）。 */
    public static final int ACTION_COMMAND = 3;

    /** 每块板的动作码。<b>与 {@link #ENABLED} 必须一致</b>（自检就是查这一条）。 */
    public static final int[] ACTION = {
            ACTION_NONE, ACTION_RECIPE_EDIT, ACTION_NONE, ACTION_COMMAND, ACTION_HOLO_SETTINGS};

    /** 本类所有日志行的前缀（便于 grep）。 */
    public static final String PREFIX = "[SHANHAI-HOLO]";

    // ------------------------------------------------------------------ 访问器

    public static boolean isEnabled(int index) {
        return index >= 0 && index < BOARD_COUNT && ENABLED[index];
    }

    public static int actionOf(int index) {
        return index >= 0 && index < BOARD_COUNT ? ACTION[index] : ACTION_NONE;
    }

    /** 板上显示的中文主标题（未启用的那几块返回「未启用」）。 */
    public static String labelOf(int index) {
        return index >= 0 && index < BOARD_COUNT ? LABEL_ZH[index] : "?";
    }

    /** 该槽位<b>原本</b>的名字（只进日志/报告）。 */
    public static String slotOf(int index) {
        return index >= 0 && index < BOARD_COUNT ? SLOT_ZH[index] : "?";
    }

    /** 0 基下标 ⇒ 1 基板号（日志/报告口径）。 */
    public static int boardNumber(int index) {
        return index + 1;
    }

    /** 1 基板号 ⇒ 0 基下标（面板请求包里走的是 0 基，日志里两个都打）。 */
    public static int indexOfBoardNumber(int board) {
        return board - 1;
    }

    /**
     * <b>服务端那条唯一的判据</b>：收到"点了某块板"之后，这个板号能不能开面板。
     *
     * <p>🔴 服务端<b>不信客户端</b>：客户端发来的只是一个 int，服务端拿<b>同一张表</b>再判一次
     * （{@link ShanhaiHoloMenuNetwork#onEditorRequest}）。放在本类而不是放在通道类里，
     * 是因为本类是"纯算术、两侧都加载、离线也能跑"的那一层 —— 判据因此可以在没有游戏的机器上验。
     */
    public static boolean canOpenEditor(int index) {
        return canOpenEditorTable(ENABLED, ACTION, index);
    }

    /** {@link #canOpenEditor(int)} 的<b>参数化版</b>（负对照要拿一张故意写错的表去跑它）。 */
    public static boolean canOpenEditorTable(boolean[] enabled, int[] action, int index) {
        if (enabled == null || index < 0 || index >= enabled.length || !enabled[index]) {
            return false;
        }
        return action != null && index < action.length && action[index] == ACTION_RECIPE_EDIT;
    }

    // ------------------------------------------------------------------ 读数与自检

    /** 一行总表（开机就写进日志；这是"五块板里只有一块可用"的机器可判形态）。 */
    public static String tableLine() {
        final StringBuilder sb = new StringBuilder(320);
        sb.append("holo_board_table");
        for (int i = 0; i < BOARD_COUNT; i++) {
            sb.append(" | board=").append(boardNumber(i))
                    .append(" idx=").append(i)
                    .append(" label=").append(LABEL_ZH[i])
                    .append(" slot=").append(SLOT_ZH[i])
                    .append(" enabled=").append(ENABLED[i])
                    .append(" action=").append(ACTION[i]);
        }
        return sb.toString();
    }

    /**
     * 判一张表合不合规（<b>纯函数，参数化</b> —— 负对照要拿它去判一张故意写错的表）。
     *
     * <h4>🔴 2026-10-06 规则改了：从「恰好一块可用」改成「可用集必须与板上名字一致」</h4>
     * 旧规则（{@code enabledCount != 1 ⇒ 报错}）在"两块可用"的新口径下必然误报，
     * 于是把真正要守的东西写成三条<b>与块数无关</b>的不变量：
     * <pre>
     *   ① 每一块的<b>板上名字</b>必须等于 {@link #expectedLabelOf}（可用 ⇒ 借用名或槽位名；停用 ⇒ 「未启用」）
     *      —— 抓"名字漂了"（板上写着 A、槽位说是 B 这类静默 bug）；
     *   ② 可用 ⇒ 动作码必须非 NONE（抓"点了没反应"）；停用 ⇒ 动作码必须是 NONE（抓"停用了还能点"）；
     *   ③ 面板入口必须在：{@link #ENABLED_INDEX} 必须可用且动作码必须是配方编辑
     *      —— 抓"手滑把配方修改关掉/改成别的动作"。
     * </pre>
     *
     * @return {@code null} = 合规；否则是"哪里不合规"的中文描述
     */
    public static String checkTable(int count, String[] labels, boolean[] enabled, int[] action) {
        if (count != BOARD_COUNT) {
            return "板块数不是 " + BOARD_COUNT + "（实际 " + count + "）";
        }
        if (labels == null || labels.length != count || enabled == null || enabled.length != count
                || action == null || action.length != count) {
            return "三个数组长度与板块数不一致";
        }
        int enabledCount = 0;
        for (int i = 0; i < count; i++) {
            if (enabled[i]) {
                enabledCount++;
            }
        }
        if (enabledCount == 0) {
            return "一块可用的板都没有（点了会整块没反应）";
        }
        for (int i = 0; i < count; i++) {
            // ① 板上名字必须与预期一致
            final String want = expectedLabelOf(i, enabled[i]);
            if (!want.equals(labels[i])) {
                return "第 " + boardNumber(i) + " 块板上写的名字是「" + labels[i] + "」，应当是「" + want
                        + "」（" + (enabled[i] ? "可用" : "已停用") + "）";
            }
            // ② 可用性与动作码必须一致
            if (enabled[i] && action[i] == ACTION_NONE) {
                return "第 " + boardNumber(i) + " 块（" + labels[i] + "）可用却没有动作码（点了没反应）";
            }
            if (!enabled[i] && action[i] != ACTION_NONE) {
                return "第 " + boardNumber(i) + " 块（" + labels[i] + "）已停用却挂着动作码 " + action[i];
            }
        }
        // ③ 面板入口必须在（「配方修改」那块）
        if (!enabled[ENABLED_INDEX]) {
            return "「配方修改」那一格（第 " + boardNumber(ENABLED_INDEX) + " 块）被停用了 —— 面板就没入口了";
        }
        if (action[ENABLED_INDEX] != ACTION_RECIPE_EDIT) {
            return "「配方修改」那一格的动作码不是配方编辑：" + action[ENABLED_INDEX];
        }
        return null;
    }

    /**
     * 自检（开机日志里跑；返回一行机器可判读数）。
     *
     * <p>判据 1 条正对照 + <b>7 条负对照</b>——没有负对照，"全绿"可能只是判据从来不响
     * （本工程血规矩：「凡我自己写的检查脚本报出的结果，采信前必须先证明脚本自己是对的」）：
     * <pre>
     *   ① 正对照：真实的五块板表 ⇒ {@link #checkTable} 必须返回 null
     *   ② 负对照 A：把「设置」那格停用、板上名字仍写「设置」 ⇒ 必须报错（名字与可用性漂了）
     *   ③ 负对照 B：把「设置」那格的动作码改成 NONE（仍可用） ⇒ 必须报错（可用却没动作）
     *   ④ 负对照 C：把「未启用」那格挂上配方编辑的动作码（仍停用） ⇒ 必须报错
     *   ⑤ 负对照 D：把「未启用」那格的板上名字改成「设置」（仍停用） ⇒ 必须报错
     *   ⑥ 负对照 E：把「设置」那格的板上名字改成「未启用」（仍可用） ⇒ 必须报错
     *   ⑦ 负对照 F：五块全停用 ⇒ 必须报错（一块都不剩）
     *   ⑧ 负对照 G：把「配方修改」那格的动作码改成命令 ⇒ 必须报错（面板入口没了的唯一可见痕迹）
     * </pre>
     */
    public static String selfCheck() {
        final StringBuilder bad = new StringBuilder();
        int pass = 0;
        // ⚠️ 这个数必须与下面 pass++ 的处数逐一点得上：1 正对照（checkTable）
        //    + 7 条 checkTable 负对照（A..G）+ 5 条服务端判据（正对照 1 + 负对照 E/F/H 3
        //    + "不该接受的却接受了" 1）= 13。
        final int total = 13;

        final String pos = checkTable(BOARD_COUNT, LABEL_ZH, ENABLED, ACTION);
        if (pos == null) {
            pass++;
        } else {
            bad.append(" 正对照失败=").append(pos).append(';');
        }

        final String[] settingsOff = LABEL_ZH.clone();
        final boolean[] offSet = ENABLED.clone();
        offSet[SETTINGS_INDEX] = false;
        if (checkTable(BOARD_COUNT, settingsOff, offSet, ACTION) != null) {
            pass++;
        } else {
            bad.append(" 负对照A没响（设置那格停用了、板上却仍写着「设置」也判合规）;");
        }

        final int[] settingsNoAction = ACTION.clone();
        settingsNoAction[SETTINGS_INDEX] = ACTION_NONE;
        if (checkTable(BOARD_COUNT, LABEL_ZH, ENABLED, settingsNoAction) != null) {
            pass++;
        } else {
            bad.append(" 负对照B没响（设置那格可用却没有动作码也判合规）;");
        }

        final int[] disabledWithAction = ACTION.clone();
        disabledWithAction[0] = ACTION_RECIPE_EDIT;
        if (checkTable(BOARD_COUNT, LABEL_ZH, ENABLED, disabledWithAction) != null) {
            pass++;
        } else {
            bad.append(" 负对照C没响（停用的第 1 格挂着动作码也判合规）;");
        }

        final String[] renamedDisabled = LABEL_ZH.clone();
        renamedDisabled[0] = "设置";
        if (checkTable(BOARD_COUNT, renamedDisabled, ENABLED, ACTION) != null) {
            pass++;
        } else {
            bad.append(" 负对照D没响（停用的第 1 格板上写着「设置」也判合规）;");
        }

        final String[] settingsAsDisabled = LABEL_ZH.clone();
        settingsAsDisabled[SETTINGS_INDEX] = DISABLED_LABEL_ZH;
        if (checkTable(BOARD_COUNT, settingsAsDisabled, ENABLED, ACTION) != null) {
            pass++;
        } else {
            bad.append(" 负对照E没响（可用的设置那格板上写着「未启用」也判合规）;");
        }

        final boolean[] noneOn = {false, false, false, false, false};
        final int[] noneAction = {ACTION_NONE, ACTION_NONE, ACTION_NONE, ACTION_NONE, ACTION_NONE};
        final String[] allDisabledLabels = {DISABLED_LABEL_ZH, DISABLED_LABEL_ZH,
                DISABLED_LABEL_ZH, DISABLED_LABEL_ZH, DISABLED_LABEL_ZH};
        if (checkTable(BOARD_COUNT, allDisabledLabels, noneOn, noneAction) != null) {
            pass++;
        } else {
            bad.append(" 负对照F没响（五块全停用也判合规）;");
        }

        final int[] editorHijacked = ACTION.clone();
        editorHijacked[ENABLED_INDEX] = ACTION_COMMAND;
        if (checkTable(BOARD_COUNT, LABEL_ZH, ENABLED, editorHijacked) != null) {
            pass++;
        } else {
            bad.append(" 负对照G没响（配方修改那格被改成命令动作也判合规）;");
        }

        // ---- 服务端那条判据（canOpenEditor）的正/负对照 ----
        if (canOpenEditor(ENABLED_INDEX)) {
            pass++;
        } else {
            bad.append(" 服务端判据把可用的第 ").append(boardNumber(ENABLED_INDEX)).append(" 格拒了;");
        }
        final StringBuilder wronglyAccepted = new StringBuilder();
        for (int i = 0; i < BOARD_COUNT; i++) {
            if (i != ENABLED_INDEX && canOpenEditor(i)) {
                wronglyAccepted.append(i).append(',');
            }
        }
        for (int rogue : new int[]{-1, BOARD_COUNT, 99}) {
            if (canOpenEditor(rogue)) {
                wronglyAccepted.append(rogue).append(',');
            }
        }
        // 🔴 新口径下"设置 / 命令"两块是【可用】的，但它们【绝不能】被服务端当成"开配方面板"放行
        //    —— 这一条就是那个"可用 ≠ 能开面板"的机器判据。
        if (wronglyAccepted.length() == 0) {
            pass++;
        } else {
            bad.append(" 服务端判据不该接受的却接受了 idx=").append(wronglyAccepted).append(';');
        }
        // 负对照 E：把表换成"五块全可用【且全挂配方编辑】" ⇒ 第 1 格必须【变成】可接受。
        final boolean[] allOn = {true, true, true, true, true};
        final int[] allEdit = {ACTION_RECIPE_EDIT, ACTION_RECIPE_EDIT, ACTION_RECIPE_EDIT,
                ACTION_RECIPE_EDIT, ACTION_RECIPE_EDIT};
        if (canOpenEditorTable(allOn, allEdit, 0)) {
            pass++;
        } else {
            bad.append(" 负对照E没响（五块全可用且全挂配方编辑时第 1 格仍被拒）;");
        }
        // 负对照 F：只把 enabled 全开、动作码仍用真表 ⇒ 第 1 格必须【仍拒绝】
        if (!canOpenEditorTable(allOn, ACTION, 0)) {
            pass++;
        } else {
            bad.append(" 负对照F没响（只看 enabled 不看动作码也放行）;");
        }
        // 负对照 H：🆕 把【真表】原样喂进去，第 4 格（设置）与第 5 格（命令）必须仍被服务端拒绝
        //   —— 证明"新启用的两格"没有顺带把服务端配方面板的门打开。
        if (!canOpenEditorTable(ENABLED, ACTION, SETTINGS_INDEX)
                && !canOpenEditorTable(ENABLED, ACTION, COMMAND_INDEX)) {
            pass++;
        } else {
            bad.append(" 负对照H没响（设置/命令那两格被服务端当成了配方面板入口）;");
        }

        final String line = "holo_board_selftest " + pass + "/" + total + " PASS=" + (pass == total)
                + " enabled_idx=[1(配方修改),4(设置),3(命令)]"
                + " disabled_idx=[0,2]"
                + " enabled_label=" + LABEL_ZH[ENABLED_INDEX]
                + " disabled_label=" + DISABLED_LABEL_ZH
                + "（判据：可用块上写它自己的名字（槽位名或被借用登记过的名字）且挂动作码／"
                + "停用块写「未启用」且不挂动作码／面板入口必须在；另含 10 条负对照）";
        if (bad.length() > 0) {
            GTDishanhaiMod.LOGGER.error("{} holo_board_selftest FAILED: {}", PREFIX, bad.toString());
        }
        return line;
    }
}
