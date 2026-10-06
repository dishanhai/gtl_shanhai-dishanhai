package com.dishanhai.gt_shanhai.common.holo;

/**
 * 山海重构 · 全息菜单的<b>输入判定表（纯函数，不碰 Minecraft）</b>。
 *
 * <h2>1. 为什么把"判什么"从"怎么接事件"里拆出来</h2>
 * 用户点单的四条规格是<b>逻辑</b>（拿着模块右键开/关、右键按钮进面板、优先级谁高谁低），
 * 而它们最容易出的错<b>不是逻辑错，是接错事件</b>——原版一次右键在客户端会依次
 * {@code useItem(MAIN_HAND)}、{@code useItem(OFF_HAND)}；按住不放还会每 4 个 tick 再来一次。
 * 这些是"怎么接"的问题。
 * <p>拆出来的收益很具体：<b>四条规格变成一张真值表，可以在没有游戏的机器上逐格验</b>
 * （{@link #selfTest()} 就是那张表，含 3 组负对照）。接事件那一层只负责把现场翻译成
 * "拿着模块吗 / 是新鲜按下吗 / 打的是空气吗 / 打在几号板上"四个布尔与一个下标。
 *
 * <h2>2. 四条规格 ⇒ 判定顺序（顺序本身就是规格 ④ 的实现）</h2>
 * <pre>
 *   ① 长按不算新的一拍     ： !freshPress                          ⇒ 什么都不做
 *   ② 拿着模块右键（对空气 或 对着全息板）： holdingModule            ⇒ 【切换】开关（开着就关）
 *      · 这一条排在"点在几号板上"【前面】= 规格 ④ 的"优先级"：
 *        拿着模块对着全息按钮右键也只会走到这里 ⇒ <b>只会关投影，绝不会开面板</b>
 *      · 🔴 为什么是"对空气【或】对着全息板"而不是只认空气：
 *        全息板浮在玩家前方 2.8 格、而"拾取"距离是 4.5 格 ⇒ <b>板后面 1.7 格内的方块会被原版 pick 到</b>
 *        （站在离墙 3 格的地方正对全息投影，就是这种现场）。那时 {@code hitResult} 是 BLOCK，
 *        如果只认"空气"，玩家就会遇到<b>关不掉投影</b> —— 而他眼睛明明看着那块板。
 *        ⇒ 判据 = 打不到任何东西，<b>或者</b>全息板比打到的那个方块/实体更近。
 *        （反过来说：正对着一堵【比全息板更近】的墙按住模块右键，什么都不做 —— 那才是"不是对空气"。）
 *   ③ 投影没画出来就点不到 ： !menuEnabled                        ⇒ 什么都不做
 *   ④ 没打到板 / 板被挡住   ： boardIndex&lt;0 || !boardClear         ⇒ 什么都不做
 *      · 这一条是规格 ②「其他不管按什么都不关闭」的落点：<b>只有上面①②能关</b>
 *   ⑤ 点到「未启用」那四块 ： !enabled[boardIndex]                 ⇒ 什么都不做（点了没反应）
 *   ⑥ 点到「配方修改」      ： action == ACTION_RECIPE_EDIT        ⇒ 请求开面板
 * </pre>
 */
public final class ShanhaiHoloMenuInputRules {

    private ShanhaiHoloMenuInputRules() {}

    /** 什么都不做。 */
    public static final int ACT_NONE = 0;

    /** 切换投影开关（规格 ① 开 / ② 关）。 */
    public static final int ACT_TOGGLE_MENU = 1;

    /** 请求打开配方编辑器面板（规格 ③）。 */
    public static final int ACT_OPEN_EDITOR = 2;

    /** 🆕 打开全息设置面板（纯客户端：距离 / 朝向档 / 布局）。 */
    public static final int ACT_OPEN_SETTINGS = 3;

    /** 🆕 打开全息命令输入框（输入框本身是客户端；命令以管理员身份发到服务端执行）。 */
    public static final int ACT_OPEN_COMMAND = 4;

    /**
     * 按<b>真实板表</b>判定；参数含义见类注释 §2。
     *
     * @param holdingModule  主手或副手里拿着创始现实修改模块
     * @param freshPress     这是本次"按下右键"的第一拍（按住不放的重复回调传 {@code false}）
     * @param airHit         原版 {@code hitResult} 是 {@code MISS}（没点到方块、也没点到实体）
     * @param boardIndex     射线打中的全息板下标（0 基）；{@code -1} = 没打到
     * @param boardClear     打中的那块板<b>没有被挡住</b>（= 打不到任何东西，或板比方块/实体更近）
     * @param menuEnabled    投影现在是开着的（没画出来的板子点不到）
     * @return {@link #ACT_NONE} / {@link #ACT_TOGGLE_MENU} / {@link #ACT_OPEN_EDITOR}
     */
    public static int decide(boolean holdingModule, boolean freshPress, boolean airHit,
                             int boardIndex, boolean boardClear, boolean menuEnabled) {
        return decideTable(ShanhaiHoloMenuBoards.ENABLED, ShanhaiHoloMenuBoards.ACTION,
                ShanhaiHoloMenuBoards.BOARD_COUNT,
                holdingModule, freshPress, airHit, boardIndex, boardClear, menuEnabled);
    }

    /**
     * 同 {@link #decide}，但<b>板表由参数给</b>。
     * <p>存在的唯一理由 = 负对照：拿一张"五块全可用"或"可用但没有动作码"的表去跑，
     * 必须得到和真表<b>不同</b>的答案 —— 否则"未启用的板点了没反应"这条判据是恒绿的。
     */
    public static int decideTable(boolean[] enabled, int[] action, int count,
                                  boolean holdingModule, boolean freshPress, boolean airHit,
                                  int boardIndex, boolean boardClear, boolean menuEnabled) {
        // ① 长按/连发只算一次
        if (!freshPress) {
            return ACT_NONE;
        }
        // ②④ 拿着模块右键：对空气、或正对着没被挡住的全息板 ⇒ 【切换】。
        //    它排在下面所有"点在几号板上"的判定【前面】⇒ 拿着模块永远开不了面板（规格 ④）。
        if (holdingModule) {
            final boolean aimingAtHologram = airHit || (boardIndex >= 0 && boardClear);
            return aimingAtHologram ? ACT_TOGGLE_MENU : ACT_NONE;
        }
        // ③ 投影没画出来 ⇒ 那些板子不存在于世界里
        if (!menuEnabled) {
            return ACT_NONE;
        }
        // ② 其他一切（打方块、打实体、打空处、打被挡住的板）都不关也不开
        if (boardIndex < 0 || boardIndex >= count || !boardClear) {
            return ACT_NONE;
        }
        // ⑤ 未启用：点了没有任何反应
        if (enabled == null || !enabled[boardIndex]) {
            return ACT_NONE;
        }
        // ⑥ 可用的那块，动作码决定做什么
        //    🔴 2026-10-06：这里原来写的是"是配方编辑才开面板、否则不做事"（当时只有一块可用）。
        //       现在有三块可用 ⇒ 把"可用但动作码不认识"与"未启用"分开：
        //       未启用 ⇒ 不做事（⑤ 已拦）；可用 ⇒ 按动作码分派，认不出来的码仍然不做事。
        if (action == null) {
            return ACT_NONE;
        }
        return switch (action[boardIndex]) {
            case ShanhaiHoloMenuBoards.ACTION_RECIPE_EDIT -> ACT_OPEN_EDITOR;
            case ShanhaiHoloMenuBoards.ACTION_HOLO_SETTINGS -> ACT_OPEN_SETTINGS;
            case ShanhaiHoloMenuBoards.ACTION_COMMAND -> ACT_OPEN_COMMAND;
            default -> ACT_NONE;
        };
    }

    /** 动作码 ⇒ 中文（日志与报告都用它，避免英文码出现在人看的行里）。 */
    public static String actionName(int action) {
        return switch (action) {
            case ACT_TOGGLE_MENU -> "切换投影";
            case ACT_OPEN_EDITOR -> "打开配方面板";
            case ACT_OPEN_SETTINGS -> "打开设置面板";
            case ACT_OPEN_COMMAND -> "打开命令输入框";
            default -> "无动作";
        };
    }

    /**
     * 一行读数（"谁开的关"用）。
     * <p>🔴 排布<b>刻意写成"输入的现场"</b>：日志要能回答"当时到底拿着模块没有、
     * 打的是空气还是方块、打在几号板上"，而不是只写一句结果。
     */
    public static String contextLine(boolean holdingModule, boolean freshPress, boolean airHit,
                                     int boardIndex, boolean boardClear, boolean menuEnabled) {
        return "holding_module=" + holdingModule
                + " fresh_press=" + freshPress
                + " air_hit=" + airHit
                + " board=" + (boardIndex < 0 ? "none"
                        : String.valueOf(ShanhaiHoloMenuBoards.boardNumber(boardIndex)))
                + " board_idx=" + boardIndex
                + " board_clear=" + boardClear
                + " menu_enabled=" + menuEnabled;
    }

    // ------------------------------------------------------------------ 真值表自检

    /** 自检结果（命中格数 / 总格数）。 */
    public record Report(int pass, int total, String bad) {
        public boolean ok() {
            return bad == null;
        }
    }

    /**
     * 真值表自检（离线可跑，不需要 Minecraft）。
     *
     * <p>其中 <b>3 组是负对照</b>：
     * <pre>
     *   N1 把板表换成"五块全可用" ⇒ 同样的现场（点第 1 格）必须【变成】开面板
     *      （证明"未启用 ⇒ 点了没反应"这一条不是恒绿的）
     *   N2 把板表换成"第 2 格可用但动作码是 NONE" ⇒ 必须不做事
     *      （证明"动作码要对"这一条真的在生效）
     *   N3 【同现场、只翻 holding_module】两个答案必须不同：拿着=切换 / 不拿=开面板
     *      （证明规格 ④ 的优先级真的由 holding_module 决定，而不是碰巧都对）
     *   N4 🆕 真表但把「设置」那格的动作码挖空（仍可用）⇒ 必须不做事
     *      （证明"可用"这一位不等于"开面板"）
     *   N4b 🆕 真表但把「命令」那格的动作码换成配方编辑 ⇒ 必须【变成】开面板
     *      （N4 的另一半：判的确实是动作码，不是板号也不是板上名字）
     * </pre>
     * 格数是<b>运行时数出来的</b>（{@link Report#total}），不写死常量 —— 写死的数字一定会漂。
     */
    public static Report selfTest() {
        final StringBuilder bad = new StringBuilder();
        final int[] n = {0, 0};

        // ---- 真表下的现场逐格 ----
        c(bad, n, "长按第二拍不做事",
                decide(true, false, true, -1, false, true), ACT_NONE);
        c(bad, n, "拿着模块对空气右键=切换",
                decide(true, true, true, -1, false, true), ACT_TOGGLE_MENU);
        // 🔴 这一格是规格 ④ 的正身：现场与"不拿模块点按钮"（下面那一格）【逐字相同】，
        //    唯一的差别就是 holding_module。全息板不是世界里的实体/方块 ⇒ 对着它右键时
        //    原版 hitResult 通常是 MISS（air_hit=true），板后面有方块时也可以是 false 但板更近。
        c(bad, n, "拿着模块对着全息按钮右键=切换(不是开面板)",
                decide(true, true, true, ShanhaiHoloMenuBoards.ENABLED_INDEX, true, true), ACT_TOGGLE_MENU);
        c(bad, n, "拿着模块对着全息按钮右键(板后有墙,板更近)=切换",
                decide(true, true, false, ShanhaiHoloMenuBoards.ENABLED_INDEX, true, true), ACT_TOGGLE_MENU);
        c(bad, n, "拿着模块打方块=不做事",
                decide(true, true, false, -1, false, true), ACT_NONE);
        c(bad, n, "拿着模块+全息板被更近的方块挡住=不做事",
                decide(true, true, false, ShanhaiHoloMenuBoards.ENABLED_INDEX, false, true), ACT_NONE);
        c(bad, n, "没拿模块打空气=不做事(其他按键都不关)",
                decide(false, true, true, -1, false, true), ACT_NONE);
        c(bad, n, "没拿模块点『配方修改』=开面板",
                decide(false, true, true, ShanhaiHoloMenuBoards.ENABLED_INDEX, true, true), ACT_OPEN_EDITOR);
        // 🆕 2026-10-06：新启用的两格各自的那一格（没有它们，"新板子能点"这条判据恒绿）
        c(bad, n, "没拿模块点『设置』=打开设置面板",
                decide(false, true, true, ShanhaiHoloMenuBoards.SETTINGS_INDEX, true, true), ACT_OPEN_SETTINGS);
        c(bad, n, "没拿模块点『命令』=打开命令输入框",
                decide(false, true, true, ShanhaiHoloMenuBoards.COMMAND_INDEX, true, true), ACT_OPEN_COMMAND);
        // 规格 ④ 的优先级对新板子同样成立（拿着模块点『设置』= 切换投影，绝不是开设置）
        c(bad, n, "拿着模块点『设置』=切换投影(不是开设置)",
                decide(true, true, true, ShanhaiHoloMenuBoards.SETTINGS_INDEX, true, true), ACT_TOGGLE_MENU);
        c(bad, n, "拿着模块点『命令』=切换投影(不是开命令框)",
                decide(true, true, true, ShanhaiHoloMenuBoards.COMMAND_INDEX, true, true), ACT_TOGGLE_MENU);
        for (int i = 0; i < ShanhaiHoloMenuBoards.BOARD_COUNT; i++) {
            if (ShanhaiHoloMenuBoards.isEnabled(i)) {
                continue;                   // 可用的那几格上面各自有一条（不再是"只有第 2 格"）
            }
            c(bad, n, "没拿模块点第 " + ShanhaiHoloMenuBoards.boardNumber(i) + " 格(未启用)=不做事",
                    decide(false, true, true, i, true, true), ACT_NONE);
        }
        c(bad, n, "投影关着时点板=不做事",
                decide(false, true, true, ShanhaiHoloMenuBoards.ENABLED_INDEX, true, false), ACT_NONE);
        c(bad, n, "板被方块挡住=不做事",
                decide(false, true, true, ShanhaiHoloMenuBoards.ENABLED_INDEX, false, true), ACT_NONE);
        c(bad, n, "没打到板=不做事",
                decide(false, true, true, -1, false, true), ACT_NONE);

        // ---- N1：把"未启用"那张表换成"五块全可用" ⇒ 同样的现场必须变成开面板 ----
        final boolean[] allOn = {true, true, true, true, true};
        final int[] editForAll = {ShanhaiHoloMenuBoards.ACTION_RECIPE_EDIT,
                ShanhaiHoloMenuBoards.ACTION_RECIPE_EDIT, ShanhaiHoloMenuBoards.ACTION_RECIPE_EDIT,
                ShanhaiHoloMenuBoards.ACTION_RECIPE_EDIT, ShanhaiHoloMenuBoards.ACTION_RECIPE_EDIT};
        c(bad, n, "N1 负对照没响(第 1 格也开着却仍不做事)",
                decideTable(allOn, editForAll, 5, false, true, true, 0, true, true), ACT_OPEN_EDITOR);

        // ---- N2：可用但没有动作码 ⇒ 必须不做事 ----
        final int[] noAction = {ShanhaiHoloMenuBoards.ACTION_NONE, ShanhaiHoloMenuBoards.ACTION_NONE,
                ShanhaiHoloMenuBoards.ACTION_NONE, ShanhaiHoloMenuBoards.ACTION_NONE,
                ShanhaiHoloMenuBoards.ACTION_NONE};
        final boolean[] onlySecondOn = {false, true, false, false, false};
        c(bad, n, "N2 负对照没响(可用但那格没有动作码也开面板)",
                decideTable(onlySecondOn, noAction, 5,
                        false, true, true, ShanhaiHoloMenuBoards.ENABLED_INDEX, true, true), ACT_NONE);

        // ---- N4：🆕 2026-10-06 —— 真表，但把「设置」那格的动作码挖空（仍可用）⇒ 必须不做事
        //      证明"可用"这一位【不】等于"开面板"：动作码认不出来就不做事，不会退化成开配方面板。
        final int[] settingsNoAction = ShanhaiHoloMenuBoards.ACTION.clone();
        settingsNoAction[ShanhaiHoloMenuBoards.SETTINGS_INDEX] = ShanhaiHoloMenuBoards.ACTION_NONE;
        c(bad, n, "N4 负对照没响(设置那格可用但没动作码也当成了开面板)",
                decideTable(ShanhaiHoloMenuBoards.ENABLED, settingsNoAction, 5,
                        false, true, true, ShanhaiHoloMenuBoards.SETTINGS_INDEX, true, true), ACT_NONE);
        //      同一个负对照的"另一半"：把「命令」那格的动作码换成配方编辑 ⇒ 它必须变成【开面板】
        //      （= 判的确实是动作码，不是板号也不是名字）
        final int[] commandAsEditor = ShanhaiHoloMenuBoards.ACTION.clone();
        commandAsEditor[ShanhaiHoloMenuBoards.COMMAND_INDEX] = ShanhaiHoloMenuBoards.ACTION_RECIPE_EDIT;
        c(bad, n, "N4b 负对照没响(动作码换成配方编辑却没变成开面板)",
                decideTable(ShanhaiHoloMenuBoards.ENABLED, commandAsEditor, 5,
                        false, true, true, ShanhaiHoloMenuBoards.COMMAND_INDEX, true, true), ACT_OPEN_EDITOR);

        // ---- N3：同现场（对着「配方修改」那一格、打的是空气、板比方块近、投影开着）
        //          只翻 holding_module，两个答案必须不同 ----
        final int withModule = decide(true, true, true,
                ShanhaiHoloMenuBoards.ENABLED_INDEX, true, true);
        final int withoutModule = decide(false, true, true,
                ShanhaiHoloMenuBoards.ENABLED_INDEX, true, true);
        n[1]++;
        if (withModule == ACT_TOGGLE_MENU && withoutModule == ACT_OPEN_EDITOR) {
            n[0]++;
        } else {
            bad.append(" N3 负对照没响(优先级没做反)：拿着模块=").append(actionName(withModule))
                    .append("，不拿模块=").append(actionName(withoutModule)).append(';');
        }

        final String b = bad.length() == 0 ? null : bad.toString();
        return new Report(n[0], n[1], b);
    }

    /** 一行自检读数（写进日志；失败时由调用方打 ERROR）。 */
    public static String selfTestLine() {
        final Report r = selfTest();
        return "holo_input_rules_selftest " + r.pass() + "/" + r.total()
                + " PASS=" + r.ok()
                + (r.ok() ? "（含 5 组负对照：五块全可用 / 可用但无动作码 / 优先级对翻 / "
                + "设置格挖空动作码 / 命令格换成配方编辑）"
                : (" FAILED:" + r.bad()));
    }

    /** 自检里一格"应当等于"。 */
    private static void c(StringBuilder bad, int[] n, String what, int actual, int expected) {
        n[1]++;
        if (actual == expected) {
            n[0]++;
        } else {
            bad.append(' ').append(what).append("：得到 ").append(actionName(actual))
                    .append("，应当是 ").append(actionName(expected)).append(';');
        }
    }
}
