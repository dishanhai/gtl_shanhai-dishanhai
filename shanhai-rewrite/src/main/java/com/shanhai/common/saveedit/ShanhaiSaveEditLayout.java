package com.shanhai.common.saveedit;

import java.util.ArrayList;
import java.util.List;

/**
 * 「存档直改」面板的<b>版式表</b>（纯 JDK：不引用任何 Minecraft / LDLib 类型）。
 *
 * <h2>一、为什么把版式单独拎出来</h2>
 * 三条理由，都是踩过的坑：
 * <ol>
 *   <li><b>离线可判</b>：LDLib 的控件<b>不裁也不换行</b>，一个越界/重叠的坐标在游戏里就是
 *       "字画到面板外面"或"按钮盖住按钮"，而这类错所有编译期检查都是绿的。
 *       把几何写成一个纯函数 {@link #layoutProblems()}，探针 c13 就能离线断言它自洽。</li>
 *   <li><b>面板类离线加载不了</b>：{@code ShanhaiSaveEditPanel} 继承 LDLib 的 {@code WidgetGroup}，
 *       只加载这个类就会触发 {@code IConfigurableWidget.<clinit>}，它要一个本机 mapped forge jar
 *       里没有的 SRG 方法（{@code Util.m_143827_}）⇒ 离线 {@code NoSuchMethodError}。
 *       把数字与自检放进纯 JDK 的这一个类，判据就完全不依赖 LDLib。</li>
 *   <li><b>页数是变量</b>：第四期从 3 页变 6 页，页签宽度必须<b>算</b>出来（{@code TAB_W/TAB_STEP}
 *       由 {@link #PAGE_COUNT} 推），而不是再写死一套坐标。</li>
 * </ol>
 *
 * <h2>二、版式（W=400 × H=232，<b>三期定的尺寸不变</b>）</h2>
 * <pre>
 *   y=6    [①玩家][②世界][③方块][④实体][⑤成就][⑥统计][⑦审计]  [读当前值 76] [确认 96]
 *          │ 页签每个 28 px（动态算），七个共占 208 px，右边留 14 px 才到按钮
 *   y=28   第 N 页的说明一行
 *   y=42.. 各页内容（① 从 42 起；②③④⑤⑥ 从 44~46 起；⑦ 从 44 起）
 *   y=220  §7状态 §f<服务端权威的一行>
 * </pre>
 * 🔴 每一行"长度不可控"的字都要先过面板里的 {@code fit(text, px)}：LDLib 不换行也不裁。
 */
public final class ShanhaiSaveEditLayout {

    // ================================================================== 尺寸 / 页号

    /** 面板宽。 */
    public static final int W = 400;
    /** 面板高。 */
    public static final int H = 232;

    /** 第 ① 页：玩家数据。 */
    public static final int PAGE_PLAYER = 0;
    /** 第 ② 页：{@code level.dat}。 */
    public static final int PAGE_LEVEL = 1;
    /** 第 ③ 页：区块方块（region）。 */
    public static final int PAGE_REGION = 2;
    /** 第 ④ 页：实体（entities）。 */
    public static final int PAGE_ENTITY = 3;
    /** 第 ⑤ 页：成就（advancements）。 */
    public static final int PAGE_ADVANCEMENT = 4;
    /** 第 ⑥ 页：统计（stats）。 */
    public static final int PAGE_STATS = 5;
    /**
     * 第 ⑦ 页：存档体检（双份登记）。
     *
     * <p>判据只立在世界数据本身（同一个 UUID / 同一组坐标同时出现在区块文件与非原版布局文件里），
     * 所以这一页对"任何名字的旁挂登记"一视同仁 —— 它<b>不认</b>任何具体模组名、实体名、文件名、字段名。
     */
    public static final int PAGE_AUDIT = 6;
    /** 🔴 页数：切页上下界、页签个数、控件总数全部由它算，<b>别处不许再写 6</b>。 */
    public static final int PAGE_COUNT = 7;

    /** 三个新页的明细行数（面板与 Holder 共用这一个数）。 */
    public static final int NEW_LINES = 7;

    // ================================================================== 第 ⑦ 页（审计）

    /**
     * 审计页的清单行数。
     *
     * <p>比其它三页多（9 行而不是 7 行）：这一页没有输入框，整页高度都让给清单，
     * 上限是"不压到下面的动作按钮"（{@link #AU_BTN_Y}）。这个数在
     * {@link #layoutProblems()} 里被离线断言过。
     */
    public static final int AU_LINES = 9;
    /** 摘要行 y（{@code 共 N 条 · 可删实体 K · 纯文本旁挂 L · 二进制旁挂 M}）。 */
    public static final int AU_SUMMARY_Y = 44;
    /** 清单第一行 y。 */
    public static final int AU_LINE_Y0 = 58;
    /** 清单行距。 */
    public static final int AU_LINE_STEP = 12;
    /** 「选中」那一行 y（显示选中项的类型/坐标/旁挂位置 + UUID）。 */
    public static final int AU_SEL_Y = 166;
    /** 动作按钮那一行 y。 */
    public static final int AU_BTN_Y = 180;
    /** 动作按钮高。 */
    public static final int AU_BTN_H = 16;
    /** 「◀ 上一条」x / 宽。 */
    public static final int AU_PREV_X = 6, AU_PREV_W = 62;
    /** 「▶ 下一条」x / 宽。 */
    public static final int AU_NEXT_X = 72, AU_NEXT_W = 62;
    /** 「删区块里那份」x / 宽。 */
    public static final int AU_DROP_ENTITY_X = 138, AU_DROP_ENTITY_W = 86;
    /** 「删旁挂那行」x / 宽（二进制旁挂时变灰，见面板里的 {@code AuditButton}）。 */
    public static final int AU_DROP_SIDECAR_X = 228, AU_DROP_SIDECAR_W = 86;
    /** 「重新扫描」x / 宽。 */
    public static final int AU_RESCAN_X = 318, AU_RESCAN_W = 76;
    /** 底部提示第一行 y。 */
    public static final int AU_HINT_Y0 = 200;

    // ================================================================== 全局（页签 / 按钮 / 状态行）

    /** 页签行 y。 */
    public static final int TAB_Y = 6;
    /** 页签高。 */
    public static final int TAB_H = 17;
    /** 第一个页签的 x。 */
    public static final int TAB_X0 = 4;
    /** 「读当前值」的 x / 宽。 */
    public static final int READ_X = 222, READ_W = 76;
    /** 「确认修改并重载」的 x / 宽。 */
    public static final int CONFIRM_X = 300, CONFIRM_W = 96;
    /** 页签能用的总宽度（到「读当前值」左边 6 px 为止）。 */
    public static final int TAB_SPACE = READ_X - TAB_X0 - 6;
    /** 每个页签的宽（动态算：{@code PAGE_COUNT} 一变就跟着变）。 */
    public static final int TAB_W = TAB_SPACE / PAGE_COUNT - 2;
    /** 页签步长（宽 + 2 px 间隙）。 */
    public static final int TAB_STEP = TAB_W + 2;
    /** 每页的说明行 y。 */
    public static final int SUB_Y = 28;

    /** 状态行 y。 */
    public static final int STATUS_Y = 220;
    /** 状态行前缀「状态」的 x。 */
    public static final int STATUS_LBL_X = 6;
    /** 状态行值的 x。 */
    public static final int STATUS_VAL_X = 42;

    // ================================================================== 第 ① 页（玩家）

    public static final int STAGE_X = 6, STAGE_Y = 42, STAGE_W = 64;
    public static final int BTN_H = 18;
    public static final int SUM_X = 6;
    public static final int SUM_Y0 = 66;
    public static final int SUM_STEP = 12;
    public static final int ROW_A_Y = 112;
    public static final int ROW_B_Y = 132;
    public static final int ROW_C_Y = 152;
    public static final int ROW_D_Y = 172;
    public static final int FIELD_H = 16;

    public static final int A_HEALTH_LBL = 6, A_HEALTH_FLD = 40, A_HEALTH_W = 48;
    public static final int A_XP_LBL = 96, A_XP_FLD = 128, A_XP_W = 44;
    public static final int A_FOOD_LBL = 180, A_FOOD_FLD = 210, A_FOOD_W = 32;
    public static final int A_SAT_LBL = 250, A_SAT_FLD = 280, A_SAT_W = 44;

    public static final int B_MODE_LBL = 6, B_MODE_FLD = 62, B_MODE_W = 40;
    public static final int B_DIM_LBL = 112, B_DIM_FLD = 144, B_DIM_W = 240;

    public static final int C_X_LBL = 6, C_X_FLD = 20, C_X_W = 52;
    public static final int C_Y_LBL = 82, C_Y_FLD = 96, C_Y_W = 52;
    public static final int C_Z_LBL = 158, C_Z_FLD = 172, C_Z_W = 52;
    public static final int C_YAW_LBL = 234, C_YAW_FLD = 258, C_YAW_W = 44;
    public static final int C_PITCH_LBL = 310, C_PITCH_FLD = 340, C_PITCH_W = 44;

    public static final int D_CLEAR_LBL = 6, D_CLEAR_FLD = 64, D_CLEAR_W = 176;
    public static final int D_CLEAR_HINT = 246;

    // ================================================================== 第 ② 页（level.dat）

    public static final int LV_ROW_Y0 = 46;
    public static final int LV_ROW_STEP = 15;
    public static final int LV_FIELD_H = 14;
    public static final int LV_A_LBL = 6, LV_A_CUR = 62, LV_A_FLD = 122, LV_A_W = 60;
    public static final int LV_B_LBL = 200, LV_B_CUR = 256, LV_B_FLD = 316, LV_B_W = 60;
    public static final int LV_LBL_W = 54;
    public static final int LV_CUR_W = 58;

    // ================================================================== 第 ③ 页（region）

    public static final int RG_ROW1_Y = 46;
    public static final int RG_ROW2_Y = 66;
    public static final int RG_ROW3_Y = 86;
    public static final int RG_BTN_Y = 106;
    public static final int RG_ROW5_Y = 126;
    public static final int RG_HINT_Y0 = 148;
    public static final int RG_HINT_STEP = 12;
    public static final int RG_BE_Y = 186;

    // ================================================================== 第 ④ 页（entities）

    /**
     * 第 ④ 页的<b>竖排节奏</b>（第五期改版：去掉 x/y/z 与"按类型 / 按 UUID"两行，
     * 只留维度一行 + 搜索一行 + 概况一行 + 7 行可点的实体 + <b>翻页一行</b>）。
     * <pre>
     *   y=28   说明（= {@code ShanhaiSaveEditEntityUi#hintLine()}）
     *   y=44   维度[......] [残留：折叠/显示] 已折叠 N 条
     *   y=62   搜索[..............] [列出] [清空]
     *   y=80   概况 <服务端算好的那一行>
     *   y=92 + 16*i   7 行可点的实体（每行一个 ButtonWidget，h=15）→ 最后一行 188..203
     *   y=204  [◀上一页] 第 N/M 页 [下一页▶] 共 358 条 · 本页 8-14   （204+16 正好贴到状态行）
     * </pre>
     * 🔴 这里的数字与下面 {@link #entitySlots()} 必须是同一份：控件由槽位表算出来，
     * 面板照着槽位表建控件，控件数由槽位表长度算 —— 三处只有<b>一个</b>来源。
     */
    public static final int EN_DIM_Y = 44;
    public static final int EN_SEARCH_Y = 62;
    public static final int EN_SUM_Y = 80;
    public static final int EN_ROW_Y0 = 92;
    public static final int EN_ROW_STEP = 16;
    /**
     * 行高 <b>15</b>（原 14）。
     *
     * <p>为什么是 15：按钮框是「边框 2 + 内区 + 边框 2」⇒ 14 的内区只有 10 px，而字高 9 px
     * 居中后上下各只剩 0.5 px（半像素裁剪，视觉上字贴着框、且与第 ⑤⑥ 页的 15 不一致）；
     * 15 的内区 = 11 px，字 9 px 居中后上下各留 <b>1 px</b>（整数像素）。
     * <p>与行距 16 的关系：第 i 行占 {@code y+0..y+15}，第 i+1 行从 {@code y+16} 起 ⇒
     * <b>相邻两行之间正好留 1 px</b>，既不相接也不重叠。
     * <p>🔴 与第 ⑤⑥ 页的 {@link #LP_ROW_H} <b>逐字相同</b>（两页的手感必须一样），改一处必须改两处。
     */
    public static final int EN_ROW_H = 15;
    /** 翻页那一行的 y / 按钮高（{@code 204+16 = 220} 正好贴到状态行，贴边不算重叠）。 */
    public static final int EN_PAGER_Y = 204, EN_PAGER_H = 16;
    /** 可点的实体行数（= {@link #NEW_LINES}；面板、Holder、槽位表共用）。 */
    public static final int EN_ROWS = NEW_LINES;
    /** 维度输入框宽。 */
    public static final int EN_DIM_W = 130;
    /** 残留开关按钮的 x / 宽。 */
    public static final int EN_RESIDUE_X = 166, EN_RESIDUE_W = 86;
    /** 残留计数那一行的 x（右边留 8 px）。 */
    public static final int EN_RESIDUE_NOTE_X = 256;
    /** 搜索框宽。 */
    public static final int EN_SEARCH_W = 208;
    /** 「列出」/「清空」两个按钮的 x / 宽。 */
    public static final int EN_LIST_X = 244, EN_LIST_W = 64;
    public static final int EN_CLEAR_X = 312, EN_CLEAR_W = 60;
    /** 翻页四个控件的 x / 宽（上一个 / 页码 / 下一个 / 本页计数）。 */
    public static final int EN_PREV_X = 6, EN_PREV_W = 62;
    public static final int EN_PAGE_LBL_X = 72, EN_PAGE_LBL_W = 120;
    public static final int EN_NEXT_X = 196, EN_NEXT_W = 62;
    public static final int EN_PAGE_CNT_X = 264, EN_PAGE_CNT_W = 128;

    // ================================================================== 覆盖层（实体详情）

    /**
     * 覆盖层的版式（铺满 400×232，压在整页之上；模态）。
     *
     * <pre>
     *   y=3           标题（实体类型 + uuid）
     *   y=13 + 9*i    5 行"现在是什么"（服务端推来的字；第 5 行兼作反馈/删除预期）
     *   y=60 + 15*r   8 行 × 2 列 = 16 个输入框（每格 = 名字 + 输入框）
     *   y=181 + 9*i   3 行红字 dangers（在「写入」按钮正上方）
     *   y=211         [写入][删除这条][取消]
     * </pre>
     */
    public static final int OV_TITLE_Y = 3;
    public static final int OV_NOTE_Y0 = 13;
    public static final int OV_NOTE_STEP = 9;
    /** "现在是什么"的行数（第 0~3 行是读数，第 4 行兼作"删除预期 / 上一次操作结果"）。 */
    public static final int OV_NOTE_LINES = 5;
    public static final int OV_IN_Y0 = 60;
    public static final int OV_IN_STEP = 15;
    public static final int OV_IN_H = 14;
    public static final int OV_IN_LBL_W = 52;
    /** 左列：名字 x、输入框 x / 宽。 */
    public static final int OV_A_LBL_X = 6, OV_A_FLD_X = 60, OV_A_FLD_W = 126;
    /** 右列：名字 x、输入框 x / 宽。 */
    public static final int OV_B_LBL_X = 192, OV_B_FLD_X = 246, OV_B_FLD_W = 146;
    public static final int OV_DANGER_Y0 = 181;
    public static final int OV_DANGER_STEP = 9;
    /** dangers 最多显示几行（超过的进日志 + 在最后一行点名还有几条）。 */
    public static final int OV_DANGER_LINES = 3;
    public static final int OV_BTN_Y = 211, OV_BTN_H = 16;
    public static final int OV_WRITE_X = 6, OV_WRITE_W = 96;
    public static final int OV_DELETE_X = 108, OV_DELETE_W = 96;
    public static final int OV_CANCEL_X = 210, OV_CANCEL_W = 76;

    // ================================================================== 第 ⑤⑥ 页（成就 / 统计：列表 → 点一行 → 覆盖层）

    /**
     * <b>第 ⑤⑥ 两页共用同一套竖排节奏</b>（S2b：与第 ④ 页同构，只少"维度"那一行）。
     * <pre>
     *   y=28   说明（= {@code hintLine()}，客户端自己知道的字）
     *   y=44   过滤行：[标签][输入框 208] [列出 64] [清空 60]
     *   y=62   概况（服务端算好的那一行）
     *   y=74 + 16*i   7 行可点（每行一个 ButtonWidget，h=15）→ 最后一行 170..185
     *   y=188  [◀上一页] 第 N/M 页 [下一页▶] 共 X 条 · 本页 a-b   （188+16 = 204）
     *   y=206  提示一行（9 px → 215，不压 y=220 的状态行）
     * </pre>
     * 🔴 这两页的槽位表（{@link #advancementSlots()} / {@link #statsSlots()}）是几何的
     * <b>唯一</b>来源：面板照它建控件，控件数由它的长度算 —— 三处（槽位表 / PAGE5·6_WIDGETS /
     * selfcheck 的期望值）不可能各自漂移。
     *
     * <p>与第 ④ 页的区别（就三条，都是有理由的）：
     * <ol>
     *   <li>少一行"维度"（成就/统计按玩家存，不带维度）；</li>
     *   <li>多一行"提示"（⑤⑥ 的落盘口径与 ④ 不同，见 {@code hintLine()}）；</li>
     *   <li>行高/行距与 ④ 逐字一致（{@link #LP_ROW_STEP} 16 步长 + {@link #LP_ROW_H} 15 高），
     *       否则两个页的点击手感会不一样。</li>
     * </ol>
     */
    public static final int LP_FILTER_Y = 44;
    public static final int LP_SUM_Y = 62;
    public static final int LP_ROW_Y0 = 74;
    public static final int LP_ROW_STEP = 16;
    /**
     * 行高 <b>15</b>（原 14；与 {@link #EN_ROW_H} 逐字相同，理由见那里）。
     *
     * <p>几何读法：边框 2 + 内区 11 + 边框 2 = 15；字 9 px 居中在 11 px 内区里 ⇒ 上下各 1 px。
     * 行距仍 16 ⇒ 相邻两行间留 1 px。
     * <p>最后一行底 = {@code 74 + 6×16 + 15 = 185 ≤ 188}（翻页行 y）⇒
     * {@link #checkRowRhythm} 的"最后一行不压翻页行"仍然过，{@code LP_ROW_STEP} 不用动。
     */
    public static final int LP_ROW_H = 15;
    /** 每页可点的行数（= {@link #NEW_LINES}；面板、覆盖层、判据共用）。 */
    public static final int LP_ROWS = NEW_LINES;
    /** 过滤框宽。 */
    public static final int LP_FILTER_W = 208;
    /** 「列出」/「清空」两个按钮的 x / 宽。 */
    public static final int LP_LIST_X = 244, LP_LIST_W = 64;
    public static final int LP_CLEAR_X = 312, LP_CLEAR_W = 60;
    /** 翻页那一行（{@code 188+16 = 204} 正好给提示行让位）。 */
    public static final int LP_PAGER_Y = 188, LP_PAGER_H = 16;
    /** 翻页四个控件的 x / 宽（上一个 / 页码 / 下一个 / 本页计数）。 */
    public static final int LP_PREV_X = 6, LP_PREV_W = 62;
    public static final int LP_PAGE_LBL_X = 72, LP_PAGE_LBL_W = 120;
    public static final int LP_NEXT_X = 196, LP_NEXT_W = 62;
    public static final int LP_PAGE_CNT_X = 264, LP_PAGE_CNT_W = 128;
    /** 底部提示那一行 y（{@code 206+9 = 215 < 220}）。 */
    public static final int LP_HINT_Y = 206;

    // ================================================================== 覆盖层（成就 / 统计详情）

    /**
     * 第 ⑤⑥ 页的详情覆盖层版式（铺满 400×232，模态；与第 ④ 页的覆盖层同一条骨架）。
     *
     * <pre>
     *   y=3           标题（🔴 中文在前，客户端现拼）
     *   y=13 + 9*i    8 行读数（状态 / 条件清单 / 预期 / 反馈）
     *   y=92          第 ⑥ 页的"新值"输入行（第 ⑤ 页没有输入框，这一行空着）
     *   y=211         [写入][撤成就 | 动作：设值/清零][取消]
     * </pre>
     * 行数比第 ④ 页的 5 行多（8 行）：这两页要把 criteria 清单 / 分类+键+当前值念清楚。
     * 用独立常量而不是改 {@link #OV_NOTE_LINES} —— 那个数是第 ④ 页已验收的版式，不许动。
     */
    public static final int OV2_NOTE_LINES = 8;
    public static final int OV2_NOTE_Y0 = 13;
    public static final int OV2_NOTE_STEP = 9;
    /** 第 ⑥ 页覆盖层的输入行（"新值"）：标签 x / 输入框 x / 宽 / 动作按钮 x / 宽。 */
    public static final int OV2_IN_Y = 92;
    public static final int OV2_IN_LBL_X = 6, OV2_IN_LBL_W = 52;
    public static final int OV2_IN_FLD_X = 60, OV2_IN_FLD_W = 126;
    public static final int OV2_MODE_X = 192, OV2_MODE_W = 130;
    /** 覆盖层按钮那一行（与第 ④ 页同一个 y / 高）。 */
    public static final int OV2_BTN_Y = OV_BTN_Y;

    // ================================================================== 覆盖层（物品栏 41 格 / 末影箱）

    /**
     * 第 ① 页「物品栏」覆盖层的版式（铺满 400×232，模态）。
     *
     * <p>为什么必须走覆盖层：第 ① 页唯一的大空带只有 {@code y=188..220}（32 px 高 × 392 宽），
     * 而 41 格按 18 px 方格 + 2 px 间隙摆出来约 <b>198×72</b> —— <b>放不下</b>。
     * 所以格子区与「背包/末影箱」开关一起放进这一层（照第 ④⑤⑥ 页的范式）。
     *
     * <pre>
     *   y=3    标题（🔴 中文在前，客户端现拼：{@code 物品栏 · 背包 41 格}）
     *   y=14   说明（客户端自己知道的字）
     *   y=26   概况（服务端读盘后算好的那一行：41/27 格、有东西几格、末影箱范围未实测）
     *   y=40   格子区（见下）：
     *            · 侧边一竖排 x=8：护甲 4（头盔在最上、靴子在最下）+ 副手 1（y=120）
     *            · 主背包 27：x=28..，3 行 × 9 列（y=40/60/80）
     *            · 快捷栏 9：最后一行（y=104）
     *            · 🔴 末影箱 27 格复用<b>同一片</b>主背包区域（3 行 × 9 列），不另画一套
     *   y=40..160  右侧详情/动作列 x=216：选中/物品名/删除/数量/替换(手)/替换(列表)/取消选中
     *   y=146  左下角开关「背包 / 末影箱」（8..148）
     *   y=166  开关旁边的"现在是哪一侧"一行
     *   y=178  已暂存（服务端推字） · y=190 反馈（服务端推字）
     *   y=208  [关闭]
     * </pre>
     * 🔴 这里每一个数字都被 {@link #layoutProblems()} 断言过（41 格 / 27 格 / 开关 / 关闭按钮
     * 互不相交、且都在面板内）—— LDLib 不裁也不换行，两个控件叠在一起在游戏里就是"按钮盖住按钮"。
     */
    public static final int IV_TITLE_Y = 3;
    public static final int IV_SUB_Y = 14;
    /** 概况那一行（服务端推字）。 */
    public static final int IV_NOTE0_Y = 26;
    /** 格子边长 / 步长（18 + 2 px 间隙，与 {@code ShanhaiCreativeWidgets.IconCell.SIZE} 同一个尺寸）。 */
    public static final int IV_CELL = 18;
    public static final int IV_STEP = 20;
    /** 侧边一竖排（护甲 4 + 副手 1）的 x。 */
    public static final int IV_SIDE_X = 8;
    /** 主背包 / 快捷栏 / 末影箱那一块的 x。 */
    public static final int IV_MAIN_X = 28;
    /** 第 1 行的 y（主背包第 1 行与护甲第 1 格同一个 y）。 */
    public static final int IV_ROW_Y0 = 40;
    /** 护甲 4 格的第一格 y（往下 3 格）。 */
    public static final int IV_ARMOR_Y0 = 40;
    /** 副手那一格 y（侧边最下）。 */
    public static final int IV_OFFHAND_Y = 120;
    /** 快捷栏那一行 y（最后一行）。 */
    public static final int IV_HOTBAR_Y = 104;
    /** 每行几格（主背包 / 快捷栏 / 末影箱都是 9 列）。 */
    public static final int IV_COLS = 9;
    /** 右侧详情/动作列的 x / 宽。 */
    public static final int IV_DETAIL_X = 216, IV_DETAIL_W = 176;
    /** 右侧各行 y：选中 / 物品名 / 删除 / 数量行 / 用手上那件 / 从列表选 / 取消选中。 */
    public static final int IV_SEL_Y = 40;
    public static final int IV_ITEM_Y = 52;
    public static final int IV_DELETE_Y = 64;
    public static final int IV_COUNT_Y = 84;
    /** 数量输入框宽 / 「改成这个数量」按钮的 x / 宽。 */
    public static final int IV_COUNT_W = 100;
    public static final int IV_COUNT_BTN_X = 320, IV_COUNT_BTN_W = 72;
    public static final int IV_HAND_Y = 104;
    public static final int IV_PICKOPEN_Y = 124;
    public static final int IV_DESELECT_Y = 144;
    /** 左下角开关（用户原话："末影箱可以做成左下角一个开关"）。 */
    public static final int IV_SWITCH_X = 8, IV_SWITCH_Y = 146, IV_SWITCH_W = 140, IV_SWITCH_H = 18;
    /** 开关旁边"现在是哪一侧"那一行（左半边，不压右侧动作列）。 */
    public static final int IV_MODE_NOTE_Y = 166, IV_MODE_NOTE_W = 200;
    /** 「已暂存」那一行 y / 「反馈」那一行 y（都是服务端推字，整宽）。 */
    public static final int IV_STAGED_Y = 178;
    public static final int IV_FEEDBACK_Y = 190;
    /** 「关闭」按钮。 */
    public static final int IV_CLOSE_X = 300, IV_CLOSE_Y = 208, IV_CLOSE_W = 92, IV_CLOSE_H = 18;
    /** 覆盖层的三行"推字"（概况 / 已暂存 / 反馈）各在哪一行。 */
    public static final int[] IV_NOTE_Y = {IV_NOTE0_Y, IV_STAGED_Y, IV_FEEDBACK_Y};

    // ---- 选物器（"替换：从列表选"那一层，铺满同一个覆盖层，与主层互斥） ----

    /**
     * 选物器的版式（<b>与主层互斥</b>：{@code model.picking()} 为真时只画它）。
     *
     * <pre>
     *   y=3    标题
     *   y=14   说明（中文名或 id 都能搜）
     *   y=26   搜索[........240] [清除搜索 60] （右边留空）
     *   y=48   8 列 × 6 行 = 48 格物品网格（x=8..166）+ 右侧一列（已选 / 个数 / 翻页 / 接受 / 取消）
     *   y=170  底部提示（只有按「接受」才会写进这一格）
     * </pre>
     */
    public static final int IV_PICK_TITLE_Y = 3;
    public static final int IV_PICK_SUB_Y = 14;
    public static final int IV_PICK_SEARCH_Y = 26;
    public static final int IV_PICK_SEARCH_X = 8, IV_PICK_SEARCH_W = 240;
    public static final int IV_PICK_CLEAR_X = 252, IV_PICK_CLEAR_W = 60;
    public static final int IV_PICK_GRID_X = 8, IV_PICK_GRID_Y = 48;
    public static final int IV_PICK_INFO_X = 180, IV_PICK_INFO_W = 212;
    public static final int IV_PICK_SEL_Y = 48;
    public static final int IV_PICK_COUNT_Y = 62;
    public static final int IV_PICK_PREV_Y = 80;
    public static final int IV_PICK_PREV_X = 180, IV_PICK_PREV_W = 74;
    public static final int IV_PICK_NEXT_X = 258, IV_PICK_NEXT_W = 74;
    public static final int IV_PICK_ACCEPT_Y = 104;
    public static final int IV_PICK_CANCEL_Y = 104;
    public static final int IV_PICK_NOTE_Y = 170;

    // ================================================================== 文字预算（LDLib 不裁不换行）

    /** 说明行能占多宽。 */
    public static final int SUB_BUDGET_PX = W - 8;
    /** 状态行能占多宽。 */
    public static final int STATUS_BUDGET_PX = W - STATUS_VAL_X - 8;
    /** 摘要行能占多宽。 */
    public static final int SUMMARY_BUDGET_PX = W - SUM_X - 8;
    /** level.dat 当前值那一格能占多宽。 */
    public static final int CURRENT_BUDGET_PX = LV_CUR_W;
    /** 方块页当前值那一行能占多宽。 */
    public static final int REGION_CURRENT_BUDGET_PX = W - 40;
    /** 新三页"概况"那一行能占多宽（左边有「概况」两个字）。 */
    public static final int OUT_BUDGET_PX = W - 40 - 8;
    /** 新三页明细每行能占多宽。 */
    public static final int LINE_BUDGET_PX = W - 12;

    // ================================================================== 控件槽位（几何的唯一来源）

    /** 一个控件的<b>角色</b>：面板照它决定 new 哪一种 widget。 */
    public enum Kind {
        /** 一行字（说明 / 概况 / 名字 / 红字 dangers）。 */
        LABEL,
        /** 输入框（{@code TextFieldWidget}，🔴 一律不加 textSupplier）。 */
        FIELD,
        /** 按钮（含"打开覆盖层"这种带动作的）。 */
        BUTTON,
        /** 第 ④ 页的可点实体行（{@code ButtonWidget} 的专用子类，见面板里的 {@code EntityRowButton}）。 */
        ROW
    }

    /** 一个控件占的矩形（{@code w}/{@code h} 是<b>画得出来</b>的那一块，不是字数）。 */
    public record Rect(String name, int x, int y, int w, int h) {
        public int right() {
            return this.x + this.w;
        }

        public int bottom() {
            return this.y + this.h;
        }

        /** 两个矩形是否真的相交（<b>贴边不算</b>：半开区间）。 */
        public boolean overlaps(Rect other) {
            return other != null
                    && this.x < other.x + other.w && other.x < this.x + this.w
                    && this.y < other.y + other.h && other.y < this.y + this.h;
        }
    }

    /**
     * 一个槽位：{@code key} 说"这是干什么的"，{@link Kind} 说"是什么控件"，
     * {@code index} 说"第几个"（{@code -1} = 单品；行 / 输入框用它指向数组下标）。
     */
    public record Slot(String key, Kind kind, int index, int x, int y, int w, int h) {
        public Rect rect() {
            return new Rect(this.key + (this.index < 0 ? "" : "[" + this.index + "]"),
                    this.x, this.y, this.w, this.h);
        }
    }

    /**
     * 🔴 第 ④ 页的<b>控件清单</b>（面板照着它建控件 ⇒ 控件数不可能数错）。
     *
     * <p><b>22 个</b>：说明 1 + 维度 2 + 残留 2 + 搜索 2 + 两个按钮 2 + 概况 2 + 可点行 7 + 翻页 4。
     */
    public static List<Slot> entitySlots() {
        final List<Slot> out = new ArrayList<>();
        out.add(new Slot("subtitle", Kind.LABEL, -1, 6, SUB_Y, W - 14, 9));
        out.add(new Slot("dim.label", Kind.LABEL, -1, 6, EN_DIM_Y + 3, 24, 9));
        out.add(new Slot("dim.field", Kind.FIELD, -1, 32, EN_DIM_Y, EN_DIM_W, FIELD_H));
        out.add(new Slot("residue.button", Kind.BUTTON, -1, EN_RESIDUE_X, EN_DIM_Y, EN_RESIDUE_W, FIELD_H));
        out.add(new Slot("residue.note", Kind.LABEL, -1, EN_RESIDUE_NOTE_X, EN_DIM_Y + 3,
                W - EN_RESIDUE_NOTE_X - 8, 9));
        out.add(new Slot("search.label", Kind.LABEL, -1, 6, EN_SEARCH_Y + 3, 24, 9));
        out.add(new Slot("search.field", Kind.FIELD, -1, 32, EN_SEARCH_Y, EN_SEARCH_W, FIELD_H));
        out.add(new Slot("list.button", Kind.BUTTON, -1, EN_LIST_X, EN_SEARCH_Y, EN_LIST_W, FIELD_H));
        out.add(new Slot("clear.button", Kind.BUTTON, -1, EN_CLEAR_X, EN_SEARCH_Y, EN_CLEAR_W, FIELD_H));
        out.add(new Slot("summary.label", Kind.LABEL, -1, 6, EN_SUM_Y, 24, 9));
        out.add(new Slot("summary.value", Kind.LABEL, -1, 32, EN_SUM_Y, W - 32 - 8, 9));
        for (int i = 0; i < EN_ROWS; i++) {
            out.add(new Slot("row", Kind.ROW, i, 6, EN_ROW_Y0 + i * EN_ROW_STEP, W - 14, EN_ROW_H));
        }
        // 翻页那一行：上一页 / 页码 / 下一页 / 本页计数
        out.add(new Slot("page.prev", Kind.BUTTON, -1, EN_PREV_X, EN_PAGER_Y, EN_PREV_W, EN_PAGER_H));
        out.add(new Slot("page.label", Kind.LABEL, -1, EN_PAGE_LBL_X, EN_PAGER_Y + 4, EN_PAGE_LBL_W, 9));
        out.add(new Slot("page.next", Kind.BUTTON, -1, EN_NEXT_X, EN_PAGER_Y, EN_NEXT_W, EN_PAGER_H));
        out.add(new Slot("page.count", Kind.LABEL, -1, EN_PAGE_CNT_X, EN_PAGER_Y + 4, EN_PAGE_CNT_W, 9));
        return List.copyOf(out);
    }

    /**
     * 🔴 覆盖层的<b>控件清单</b>（8 行 × 2 列 = 16 个输入框，名字与输入框各算一个控件）。
     *
     * <p>44 个：标题 1 + 读数 5 + 输入 16×2 + dangers 3 + 按钮 3。
     */
    public static List<Slot> entityOverlaySlots() {
        final List<Slot> out = new ArrayList<>();
        out.add(new Slot("title", Kind.LABEL, -1, 6, OV_TITLE_Y, W - 14, 9));
        for (int i = 0; i < OV_NOTE_LINES; i++) {
            out.add(new Slot("note", Kind.LABEL, i, 6, OV_NOTE_Y0 + i * OV_NOTE_STEP, W - 14, 9));
        }
        for (int row = 0; row < 8; row++) {
            final int y = OV_IN_Y0 + row * OV_IN_STEP;
            out.add(new Slot("in.label", Kind.LABEL, row, OV_A_LBL_X, y + 3, OV_IN_LBL_W, 9));
            out.add(new Slot("in.field", Kind.FIELD, row, OV_A_FLD_X, y, OV_A_FLD_W, OV_IN_H));
            out.add(new Slot("in.label", Kind.LABEL, 8 + row, OV_B_LBL_X, y + 3, OV_IN_LBL_W, 9));
            out.add(new Slot("in.field", Kind.FIELD, 8 + row, OV_B_FLD_X, y, OV_B_FLD_W, OV_IN_H));
        }
        for (int i = 0; i < OV_DANGER_LINES; i++) {
            out.add(new Slot("danger", Kind.LABEL, i, 6, OV_DANGER_Y0 + i * OV_DANGER_STEP, W - 14, 9));
        }
        out.add(new Slot("write", Kind.BUTTON, -1, OV_WRITE_X, OV_BTN_Y, OV_WRITE_W, OV_BTN_H));
        out.add(new Slot("delete", Kind.BUTTON, -1, OV_DELETE_X, OV_BTN_Y, OV_DELETE_W, OV_BTN_H));
        out.add(new Slot("cancel", Kind.BUTTON, -1, OV_CANCEL_X, OV_BTN_Y, OV_CANCEL_W, OV_BTN_H));
        return List.copyOf(out);
    }

    /**
     * 第 ⑤ 页（成就）的控件清单（<b>19 个</b>：说明 1 + 过滤 2 + 按钮 2 + 概况 2 + 可点行 7 + 翻页 4 + 提示 1）。
     *
     * <p>S2b 改版：原来是「成就 id 输入框 + 4 个按钮 + 7 行只读明细」，现在照第 ④ 页换成
     * <b>可点的行 → 详情覆盖层</b>；列表、翻页、概况与第 ⑥ 页共用同一套槽位（{@link #listPageSlots(String)}）。
     */
    public static List<Slot> advancementSlots() {
        return listPageSlots("adv");
    }

    /** 第 ⑥ 页（统计）的控件清单（<b>19 个</b>，与第 ⑤ 页逐格同构）。 */
    public static List<Slot> statsSlots() {
        return listPageSlots("stats");
    }

    /**
     * 第 ⑤⑥ 两页<b>共用的那一套槽位</b>（几何只写一次，两页不可能画歪）。
     *
     * <p>角色配比：说明 1 + 过滤标签/输入框 2 + 列出/清空 2 + 概况标签/值 2 + 可点行 7 + 翻页 4 + 提示 1 = 19。
     *
     * @param prefix 只用于 {@code Slot.key()}（判据/日志里能一眼看出是哪一页的槽位），不影响几何
     */
    private static List<Slot> listPageSlots(String prefix) {
        final List<Slot> out = new ArrayList<>();
        out.add(new Slot("subtitle", Kind.LABEL, -1, 6, SUB_Y, W - 14, 9));
        out.add(new Slot("filter.label", Kind.LABEL, -1, 6, LP_FILTER_Y + 3, 24, 9));
        out.add(new Slot("filter.field", Kind.FIELD, -1, 32, LP_FILTER_Y, LP_FILTER_W, FIELD_H));
        out.add(new Slot("list.button", Kind.BUTTON, -1, LP_LIST_X, LP_FILTER_Y, LP_LIST_W, FIELD_H));
        out.add(new Slot("clear.button", Kind.BUTTON, -1, LP_CLEAR_X, LP_FILTER_Y, LP_CLEAR_W, FIELD_H));
        out.add(new Slot("summary.label", Kind.LABEL, -1, 6, LP_SUM_Y, 24, 9));
        out.add(new Slot("summary.value", Kind.LABEL, -1, 32, LP_SUM_Y, W - 32 - 8, 9));
        for (int i = 0; i < LP_ROWS; i++) {
            out.add(new Slot("row", Kind.ROW, i, 6, LP_ROW_Y0 + i * LP_ROW_STEP, W - 14, LP_ROW_H));
        }
        out.add(new Slot("page.prev", Kind.BUTTON, -1, LP_PREV_X, LP_PAGER_Y, LP_PREV_W, LP_PAGER_H));
        out.add(new Slot("page.label", Kind.LABEL, -1, LP_PAGE_LBL_X, LP_PAGER_Y + 4, LP_PAGE_LBL_W, 9));
        out.add(new Slot("page.next", Kind.BUTTON, -1, LP_NEXT_X, LP_PAGER_Y, LP_NEXT_W, LP_PAGER_H));
        out.add(new Slot("page.count", Kind.LABEL, -1, LP_PAGE_CNT_X, LP_PAGER_Y + 4, LP_PAGE_CNT_W, 9));
        out.add(new Slot("hint", Kind.LABEL, -1, 6, LP_HINT_Y, W - 8, 9));
        return List.copyOf(out);
    }

    /**
     * 第 ⑤ 页（成就）详情覆盖层的控件清单（<b>12 个</b>：标题 1 + 读数 8 + 按钮 3）。
     *
     * <p>没有输入框 —— "改一条成就"是<b>两个动作</b>（写入 = 给、撤成就），不是填值。
     */
    public static List<Slot> advancementOverlaySlots() {
        final List<Slot> out = new ArrayList<>();
        out.add(new Slot("title", Kind.LABEL, -1, 6, OV_TITLE_Y, W - 14, 9));
        for (int i = 0; i < OV2_NOTE_LINES; i++) {
            out.add(new Slot("note", Kind.LABEL, i, 6, OV2_NOTE_Y0 + i * OV2_NOTE_STEP, W - 14, 9));
        }
        out.add(new Slot("grant", Kind.BUTTON, -1, OV_WRITE_X, OV2_BTN_Y, OV_WRITE_W, OV_BTN_H));
        out.add(new Slot("revoke", Kind.BUTTON, -1, OV_DELETE_X, OV2_BTN_Y, OV_DELETE_W, OV_BTN_H));
        out.add(new Slot("cancel", Kind.BUTTON, -1, OV_CANCEL_X, OV2_BTN_Y, OV_CANCEL_W, OV_BTN_H));
        return List.copyOf(out);
    }

    /**
     * 第 ⑥ 页（统计）详情覆盖层的控件清单（<b>14 个</b>：标题 1 + 读数 8 + 新值 2 + 动作 1 + 按钮 2）。
     *
     * <p>比第 ⑤ 页多一行"新值 + 动作"：统计的"改"是<b>写一个整数</b>（设值 / 清零两种口径）。
     */
    public static List<Slot> statsOverlaySlots() {
        final List<Slot> out = new ArrayList<>();
        out.add(new Slot("title", Kind.LABEL, -1, 6, OV_TITLE_Y, W - 14, 9));
        for (int i = 0; i < OV2_NOTE_LINES; i++) {
            out.add(new Slot("note", Kind.LABEL, i, 6, OV2_NOTE_Y0 + i * OV2_NOTE_STEP, W - 14, 9));
        }
        out.add(new Slot("in.label", Kind.LABEL, -1, OV2_IN_LBL_X, OV2_IN_Y + 3, OV2_IN_LBL_W, 9));
        out.add(new Slot("in.field", Kind.FIELD, -1, OV2_IN_FLD_X, OV2_IN_Y, OV2_IN_FLD_W, OV_IN_H));
        out.add(new Slot("mode", Kind.BUTTON, -1, OV2_MODE_X, OV2_IN_Y, OV2_MODE_W, OV_IN_H));
        out.add(new Slot("write", Kind.BUTTON, -1, OV_WRITE_X, OV2_BTN_Y, OV_WRITE_W, OV_BTN_H));
        out.add(new Slot("cancel", Kind.BUTTON, -1, OV_CANCEL_X, OV2_BTN_Y, OV_CANCEL_W, OV_BTN_H));
        return List.copyOf(out);
    }

    /**
     * 物品栏覆盖层的<b>控件清单</b>（<b>16 个</b>：标题 1 + 说明 1 + 推字 3 + 选中/物品名 2 +
     * 五个关键按钮 + 数量输入 1 + 开关 1 + 开关旁读数 1 + 关闭 1）。
     *
     * <p>🔴 <b>格子本身不是控件</b>：它们由一个自绘的格子控件按
     * {@link #inventoryCellRects(boolean)} 逐格画/逐格判命中 —— 41 个控件只为画 41 个 18px 方格
     * 不划算，而且"末影箱要共用同一片格子区"这件事一旦拆成两套控件就又变成两套代码。
     * 格子的几何照样被 {@link #layoutProblems()} 逐格断言（见那里的四条）。
     */
    public static List<Slot> inventoryOverlaySlots() {
        final List<Slot> out = new ArrayList<>();
        out.add(new Slot("title", Kind.LABEL, -1, 6, IV_TITLE_Y, W - 14, 9));
        out.add(new Slot("subtitle", Kind.LABEL, -1, 6, IV_SUB_Y, W - 14, 9));
        for (int i = 0; i < IV_NOTE_Y.length; i++) {
            out.add(new Slot("note", Kind.LABEL, i, 6, IV_NOTE_Y[i], W - 14, 9));
        }
        out.add(new Slot("sel.label", Kind.LABEL, -1, IV_DETAIL_X, IV_SEL_Y, IV_DETAIL_W, 9));
        out.add(new Slot("item.label", Kind.LABEL, -1, IV_DETAIL_X, IV_ITEM_Y, IV_DETAIL_W, 9));
        out.add(new Slot("delete.button", Kind.BUTTON, -1, IV_DETAIL_X, IV_DELETE_Y, IV_DETAIL_W, FIELD_H));
        out.add(new Slot("count.field", Kind.FIELD, -1, IV_DETAIL_X, IV_COUNT_Y, IV_COUNT_W, FIELD_H));
        out.add(new Slot("count.button", Kind.BUTTON, -1, IV_COUNT_BTN_X, IV_COUNT_Y, IV_COUNT_BTN_W, FIELD_H));
        out.add(new Slot("hand.button", Kind.BUTTON, -1, IV_DETAIL_X, IV_HAND_Y, IV_DETAIL_W, FIELD_H));
        out.add(new Slot("pick.button", Kind.BUTTON, -1, IV_DETAIL_X, IV_PICKOPEN_Y, IV_DETAIL_W, FIELD_H));
        out.add(new Slot("deselect.button", Kind.BUTTON, -1, IV_DETAIL_X, IV_DESELECT_Y, IV_DETAIL_W, FIELD_H));
        out.add(new Slot("switch", Kind.BUTTON, -1, IV_SWITCH_X, IV_SWITCH_Y, IV_SWITCH_W, IV_SWITCH_H));
        out.add(new Slot("mode.note", Kind.LABEL, -1, IV_SWITCH_X, IV_MODE_NOTE_Y, IV_MODE_NOTE_W, 9));
        out.add(new Slot("close", Kind.BUTTON, -1, IV_CLOSE_X, IV_CLOSE_Y, IV_CLOSE_W, IV_CLOSE_H));
        return List.copyOf(out);
    }

    /**
     * 物品栏覆盖层里<b>格子</b>的矩形表（41 格 / 末影箱 27 格）。
     *
     * <p>顺序与 {@code ShanhaiSaveEditInventoryUi#slotAt(boolean, int)} 逐格一致（第 i 个元素就是第 i 格），
     * 所以判据能"格号 → 槽位 → 屏幕位置"三样一起对。
     *
     * <p>摆放按游戏里物品栏的视觉顺序：<b>快捷栏 9 放最后一行</b>、<b>护甲 4 + 副手 1 在侧边一竖排</b>；
     * 末影箱 27 格复用主背包那块 3×9 —— 换数据源不换版式。
     */
    public static List<Rect> inventoryCellRects(boolean ender) {
        final List<Rect> out = new ArrayList<>();
        final int n = ender ? ShanhaiPlayerDataEdit.ENDER_SLOTS : ShanhaiPlayerDataEdit.INVENTORY_GRID_SLOTS;
        for (int i = 0; i < n; i++) {
            final int[] xy = ender ? enderCellXY(i) : backpackCellXY(i);
            out.add(new Rect("格" + (i + 1) + "(" + ShanhaiSaveEditInventoryUi.cellName(ender, i) + "@"
                    + ShanhaiSaveEditInventoryUi.slotAt(ender, i) + ")", xy[0], xy[1], IV_CELL, IV_CELL));
        }
        return List.copyOf(out);
    }

    /** 背包第 {@code index} 格的左上角（按游戏里物品栏的视觉顺序）。 */
    private static int[] backpackCellXY(int index) {
        final int slot = ShanhaiPlayerDataEdit.inventorySlotAt(index);
        // 快捷栏（Slot 0..8）⇒ 最后一行
        if (slot >= 0 && slot < ShanhaiPlayerDataEdit.HOTBAR_SLOTS) {
            return new int[] {IV_MAIN_X + slot * IV_STEP, IV_HOTBAR_Y};
        }
        // 主背包（Slot 9..35）⇒ 3 行 × 9 列
        if (slot >= ShanhaiPlayerDataEdit.HOTBAR_SLOTS && slot < ShanhaiPlayerDataEdit.MAIN_SLOTS) {
            final int k = slot - ShanhaiPlayerDataEdit.HOTBAR_SLOTS;
            return new int[] {IV_MAIN_X + (k % IV_COLS) * IV_STEP, IV_ROW_Y0 + (k / IV_COLS) * IV_STEP};
        }
        // 护甲（Slot 100..103）⇒ 侧边一竖排：头盔(103) 在最上、靴子(100) 在最下
        if (slot >= ShanhaiPlayerDataEdit.ARMOR_BASE
                && slot < ShanhaiPlayerDataEdit.ARMOR_BASE + ShanhaiPlayerDataEdit.ARMOR_SLOTS) {
            final int k = ShanhaiPlayerDataEdit.ARMOR_BASE + ShanhaiPlayerDataEdit.ARMOR_SLOTS - 1 - slot;
            return new int[] {IV_SIDE_X, IV_ARMOR_Y0 + k * IV_STEP};
        }
        // 副手（-106）⇒ 侧边最下面那一格
        return new int[] {IV_SIDE_X, IV_OFFHAND_Y};
    }

    /** 末影箱第 {@code index} 格的左上角（复用主背包那块 3×9）。 */
    private static int[] enderCellXY(int index) {
        return new int[] {IV_MAIN_X + (index % IV_COLS) * IV_STEP, IV_ROW_Y0 + (index / IV_COLS) * IV_STEP};
    }

    /** 选物器里那 48 格的矩形表（8 列 × 6 行；空格子照样占位，判据才能逐格对）。 */
    public static List<Rect> pickerCellRects() {
        final List<Rect> out = new ArrayList<>();
        final int n = ShanhaiSaveEditInventoryUi.PICK_PER_PAGE;
        for (int i = 0; i < n; i++) {
            out.add(new Rect("选格" + (i + 1),
                    IV_PICK_GRID_X + (i % ShanhaiSaveEditInventoryUi.PICK_COLS) * IV_STEP,
                    IV_PICK_GRID_Y + (i / ShanhaiSaveEditInventoryUi.PICK_COLS) * IV_STEP,
                    IV_CELL, IV_CELL));
        }
        return List.copyOf(out);
    }

    /**
     * 选物器的控件清单（<b>11 个</b>：标题 1 + 说明 1 + 搜索 2 + 已选/个数 2 + 翻页 2 + 接受/取消 2 + 提示 1）。
     *
     * <p>🔴 与 {@link #inventoryOverlaySlots()} <b>互斥</b>（同一时刻只显示一层），所以两套几何
     * 各自判自己的重叠 —— 合成一张表会把"本来就是两屏"的控件判成重叠。
     */
    public static List<Slot> inventoryPickerSlots() {
        final List<Slot> out = new ArrayList<>();
        out.add(new Slot("pick.title", Kind.LABEL, -1, 6, IV_PICK_TITLE_Y, W - 14, 9));
        out.add(new Slot("pick.subtitle", Kind.LABEL, -1, 6, IV_PICK_SUB_Y, W - 14, 9));
        out.add(new Slot("pick.search", Kind.FIELD, -1, IV_PICK_SEARCH_X, IV_PICK_SEARCH_Y,
                IV_PICK_SEARCH_W, FIELD_H));
        out.add(new Slot("pick.clear", Kind.BUTTON, -1, IV_PICK_CLEAR_X, IV_PICK_SEARCH_Y,
                IV_PICK_CLEAR_W, FIELD_H));
        out.add(new Slot("pick.sel", Kind.LABEL, -1, IV_PICK_INFO_X, IV_PICK_SEL_Y, IV_PICK_INFO_W, 9));
        out.add(new Slot("pick.count", Kind.LABEL, -1, IV_PICK_INFO_X, IV_PICK_COUNT_Y, IV_PICK_INFO_W, 9));
        out.add(new Slot("pick.prev", Kind.BUTTON, -1, IV_PICK_PREV_X, IV_PICK_PREV_Y,
                IV_PICK_PREV_W, FIELD_H));
        out.add(new Slot("pick.next", Kind.BUTTON, -1, IV_PICK_NEXT_X, IV_PICK_PREV_Y,
                IV_PICK_NEXT_W, FIELD_H));
        out.add(new Slot("pick.accept", Kind.BUTTON, -1, IV_PICK_PREV_X, IV_PICK_ACCEPT_Y,
                IV_PICK_PREV_W, FIELD_H));
        out.add(new Slot("pick.cancel", Kind.BUTTON, -1, IV_PICK_NEXT_X, IV_PICK_CANCEL_Y,
                IV_PICK_NEXT_W, FIELD_H));
        out.add(new Slot("pick.note", Kind.LABEL, -1, 6, IV_PICK_NOTE_Y, W - 14, 9));
        return List.copyOf(out);
    }

    /** 把槽位表摊成矩形表（重叠判据的输入）。 */
    public static List<Rect> rects(List<Slot> slots) {
        final List<Rect> out = new ArrayList<>();
        if (slots != null) {
            for (Slot s : slots) {
                out.add(s.rect());
            }
        }
        return out;
    }

    /** 两张矩形表拼一张（"格子 + 控件"要一起判重叠时用）。 */
    public static List<Rect> concat(List<Rect> a, List<Rect> b) {
        final List<Rect> out = new ArrayList<>();
        if (a != null) {
            out.addAll(a);
        }
        if (b != null) {
            out.addAll(b);
        }
        return out;
    }

    /**
     * 🔴 <b>"同页任意两个控件不相交 + 不越界"的判据</b>（纯函数；负对照直接喂两个叠在一起的矩形）。
     *
     * <p>为什么必须单独一条：LDLib <b>不裁也不换行</b>，两个控件叠在一起在游戏里就是
     * "按钮盖住输入框"（点哪个看谁在上面），而编译期所有检查都是绿的。第五期往第 ④ 页
     * 加了一整排"可点的行"，行高 14 而原先的行距是 12 —— 这种数字不靠判据根本看不出来。
     *
     * @return 空表 = 这一页的控件互不相交、且都在面板内
     */
    public static List<String> rectOverlapProblems(String pageLabel, List<Rect> list) {
        final List<String> problems = new ArrayList<>();
        if (list == null) {
            return problems;
        }
        final String label = pageLabel == null ? "?" : pageLabel;
        for (Rect r : list) {
            if (r == null) {
                continue;
            }
            if (r.x() < 0 || r.y() < 0 || r.right() > W || r.bottom() > H) {
                problems.add(label + "控件越界 " + r.name() + " (" + r.x() + "," + r.y() + ","
                        + r.w() + "x" + r.h() + ") 面板 " + W + "x" + H);
            }
        }
        for (int i = 0; i < list.size(); i++) {
            for (int j = i + 1; j < list.size(); j++) {
                final Rect a = list.get(i);
                final Rect b = list.get(j);
                if (a != null && b != null && a.overlaps(b)) {
                    problems.add(label + "控件重叠 " + a.name() + " 与 " + b.name()
                            + " [" + a.x() + "," + a.y() + " " + a.w() + "x" + a.h() + "] vs ["
                            + b.x() + "," + b.y() + " " + b.w() + "x" + b.h() + "]");
                }
            }
        }
        return problems;
    }

    // ================================================================== 控件计数

    /**
     * 第 ① 页控件数：说明 1 + 按钮 2 + 摘要 3 + 输入 11 + 标签 11 + 提示 1 = <b>29</b>。
     *
     * <p>S2c：原来那个「清空槽位」输入框（{@code 64,172 176×16}）换成了「打开背包格」按钮
     * —— <b>1 换 1</b>，所以这一页的控件数<b>不变</b>（29），三处（本条 / {@code pageWidgetCount(0)} /
     * {@code ShanhaiSaveEditCommand} 的 selfcheck）依旧同一个数。41 格与末影箱开关不在这一页上
     * —— 它们在 {@link #inventoryOverlaySlots()} 那一层（模态覆盖层，不随切页显隐，也不进 pageWidgets）。
     */
    private static final int PAGE1_WIDGETS = 29;
    /** 第 ③ 页控件数：说明 1 + 行1 8 + 行2 4 + 当前 2 + 按钮 3 + BE NBT 2 + 提示 3 + 当前BE 2。 */
    private static final int PAGE3_WIDGETS = 25;
    /**
     * 第 ④ 页控件数：<b>由 {@link #entitySlots()} 的长度算</b>（19）。
     *
     * <p>第五期改版：26 → 19。去掉 4 个输入格（区X / 区Z / 类型 / UUID）、去掉它们 4 个名字标签、
     * 去掉 3 个旧按钮（列出 / 暂存删除 / 按：xxx）、去掉 1 行"如 0 / -1"提示与 7 行只读明细标签；
     * 加上：维度 2、残留开关 + 计数 2、搜索 2、列出 + 清空 2、概况 2、7 个<b>可点</b>的行按钮、提示 1。
     */
    private static final int PAGE4_WIDGETS = entitySlots().size();
    /**
     * 第 ⑤ 页控件数：<b>由 {@link #advancementSlots()} 的长度算</b>（19）。
     *
     * <p>S2b 改版：18 → 19。去掉「成就 id 输入框 + 4 个按钮 + 7 行只读明细 + 2 行提示」，
     * 换成第 ④ 页那一套：说明 1 + 过滤 2 + 列出/清空 2 + 概况 2 + <b>7 个可点的行</b> + 翻页 4 + 提示 1。
     */
    private static final int PAGE5_WIDGETS = advancementSlots().size();
    /** 第 ⑥ 页控件数：<b>由 {@link #statsSlots()} 的长度算</b>（19，与第 ⑤ 页逐格同构）。 */
    private static final int PAGE6_WIDGETS = statsSlots().size();
    /** 第 ⑦ 页：说明 1 + 摘要 1 + 清单 {@link #AU_LINES} + 选中 1 + 按钮 5 + 提示 1。 */
    private static final int PAGE7_WIDGETS = 1 + 1 + AU_LINES + 1 + 5 + 1;

    private ShanhaiSaveEditLayout() {}

    // ================================================================== 页名

    /** 页签上的字（{@code TAB_W} 只有 28 px，所以用"①②③ + 两字"，实测 27 px 放得下）。 */
    public static String tabName(int page) {
        switch (page) {
            case PAGE_PLAYER: return "①玩家";
            case PAGE_LEVEL: return "②世界";
            case PAGE_REGION: return "③方块";
            case PAGE_ENTITY: return "④实体";
            case PAGE_ADVANCEMENT: return "⑤成就";
            case PAGE_STATS: return "⑥统计";
            case PAGE_AUDIT: return "⑦审计";
            default: return "?";
        }
    }

    /** 页签上全部的字（自检用：必须与 {@link #PAGE_COUNT} 一样多）。 */
    public static List<String> tabNames() {
        final List<String> out = new ArrayList<>();
        for (int i = 0; i < PAGE_COUNT; i++) {
            out.add(tabName(i));
        }
        return out;
    }

    /** 七页的说明文字（装配时打进日志，方便对"面板上到底有什么"）。 */
    public static List<String> pageTitles() {
        return List.of("① 玩家", "② 世界(level.dat)", "③ 方块(region)",
                "④ 实体(entities)", "⑤ 成就(advancements)", "⑥ 统计(stats)",
                "⑦ 存档体检(双份登记)");
    }

    // ================================================================== 控件计数（分析式）

    /** 某一页的控件数（不依赖任何实例）。 */
    public static int pageWidgetCount(int page) {
        switch (page) {
            case PAGE_PLAYER: return PAGE1_WIDGETS;
            case PAGE_LEVEL: return 1 + ShanhaiLevelDataSpec.FIELDS.size() * 3;
            case PAGE_REGION: return PAGE3_WIDGETS;
            case PAGE_ENTITY: return PAGE4_WIDGETS;
            case PAGE_ADVANCEMENT: return PAGE5_WIDGETS;
            case PAGE_STATS: return PAGE6_WIDGETS;
            case PAGE_AUDIT: return PAGE7_WIDGETS;
            default: return 0;
        }
    }

    /** 控件总数（{@code 页签 + 两个按钮} + 每页之和）。 */
    public static int expectedWidgetCount() {
        int total = PAGE_COUNT + 2;
        for (int p = 0; p < PAGE_COUNT; p++) {
            total += pageWidgetCount(p);
        }
        return total;
    }

    // ================================================================== 版式自检（判据 c13）

    /**
     * 🔴 <b>版式的离线判据</b>（探针直接调它，不需要真开界面、也不需要 LDLib）。
     *
     * <p>逐条：
     * <ol>
     *   <li>页签全部在面板内、彼此不重叠、不压到「读当前值」；</li>
     *   <li>「读当前值」不压「确认」，「确认」不越界；</li>
     *   <li>状态行在面板内；</li>
     *   <li>各页的明细行不压状态行、起点不高于 40；</li>
     *   <li>🔴 <b>同页任意两个控件的矩形不相交、且都在面板内</b>（第 ④⑤⑥ 页 + 覆盖层；
     *       见 {@link #rectOverlapProblems(String, List)}，负对照直接喂两个叠在一起的矩形）；</li>
     *   <li>第 ④ 页的可点行"行距 ≥ 行高"，不压提示、不压状态行；</li>
     *   <li>第 ⑦ 页的清单/选中/按钮/提示互不重叠、按钮不越界；</li>
     *   <li>每一页都有控件（分析式计数 &gt; 0）。</li>
     * </ol>
     *
     * @return 空列表 = 版式自洽
     */
    public static List<String> layoutProblems() {
        return layoutProblems(PAGE_COUNT, TAB_W, TAB_STEP);
    }

    /**
     * 同 {@link #layoutProblems()}，但<b>页签几何可替换</b>（负对照专用）。
     *
     * <p>负对照：{@code layoutProblems(7, 70, 72)} 必须<b>非空</b> —— 七个 70 px 的页签一共 490 px，
     * 远超 400 px 的面板，必然越界、且第三个起就压到「读当前值」。这条如果也能全绿，
     * 说明判据根本没有在看页签几何。
     */
    public static List<String> layoutProblems(int pageCount, int tabW, int tabStep) {
        final List<String> problems = new ArrayList<>();
        final int tabs = Math.max(0, pageCount);
        final int w = Math.max(0, tabW);
        final int step = Math.max(0, tabStep);
        // ① 页签
        for (int i = 0; i < tabs; i++) {
            final int x = TAB_X0 + i * step;
            if (x < 0 || x + w > W) {
                problems.add("tab" + i + " 越界 x=" + x + " w=" + w);
            }
            if (i > 0) {
                final int prevEnd = TAB_X0 + (i - 1) * step + w;
                if (x < prevEnd) {
                    problems.add("tab" + i + " 与 tab" + (i - 1) + " 重叠");
                }
            }
            if (x + w >= READ_X) {
                problems.add("tab" + i + " 压到「读当前值」x=" + x + " end=" + (x + w));
            }
        }
        // ② 两个动作按钮
        if (READ_X + READ_W > CONFIRM_X) {
            problems.add("「读当前值」压到「确认」");
        }
        if (CONFIRM_X + CONFIRM_W > W) {
            problems.add("「确认」越界 end=" + (CONFIRM_X + CONFIRM_W));
        }
        // ③ 状态行
        if (STATUS_Y + 9 > H) {
            problems.add("状态行越界 y=" + STATUS_Y);
        }
        // ④ 各页的明细行
        checkLines(problems, "审计", AU_LINE_Y0, AU_LINE_STEP, AU_LINES);
        // ④' 第 ④⑤⑥ 页的可点行：行距必须 ≥ 行高（15 px 的按钮放进 16 px 的行距里正好留 1 px；
        //     放进 12 px 的行距里就会互相叠 —— 这类数字错了编译期全绿）
        checkRowRhythm(problems, "实体", EN_ROW_Y0, EN_ROW_STEP, EN_ROW_H, EN_ROWS,
                EN_PAGER_Y, EN_PAGER_H);
        checkRowRhythm(problems, "成就", LP_ROW_Y0, LP_ROW_STEP, LP_ROW_H, LP_ROWS,
                LP_PAGER_Y, LP_PAGER_H);
        checkRowRhythm(problems, "统计", LP_ROW_Y0, LP_ROW_STEP, LP_ROW_H, LP_ROWS,
                LP_PAGER_Y, LP_PAGER_H);
        if (EN_ROWS != NEW_LINES) {
            problems.add("实体页可点行数 " + EN_ROWS + " ≠ NEW_LINES=" + NEW_LINES);
        }
        if (LP_ROWS != NEW_LINES) {
            problems.add("成就/统计页可点行数 " + LP_ROWS + " ≠ NEW_LINES=" + NEW_LINES);
        }
        // ④'' 第 ⑤⑥ 页：翻页行 → 提示行 → 状态行 的纵向关系（三条都不许压）
        if (LP_PAGER_Y + LP_PAGER_H > LP_HINT_Y) {
            problems.add("成就/统计页翻页行压到提示行 end=" + (LP_PAGER_Y + LP_PAGER_H)
                    + " hint_y=" + LP_HINT_Y);
        }
        if (LP_HINT_Y + 9 > STATUS_Y) {
            problems.add("成就/统计页提示行压到状态行 end=" + (LP_HINT_Y + 9)
                    + " status_y=" + STATUS_Y);
        }
        // ⑤ 🔴 同页任意两个控件的矩形不相交（第 ④⑤⑥ 页 + 三个覆盖层）
        //    这一条是第五期补的：原来只查"页与页/行与状态行"的关系，同页内部控件叠在一起是查不出来的。
        problems.addAll(rectOverlapProblems("第④页", rects(entitySlots())));
        problems.addAll(rectOverlapProblems("第⑤页", rects(advancementSlots())));
        problems.addAll(rectOverlapProblems("第⑥页", rects(statsSlots())));
        problems.addAll(rectOverlapProblems("覆盖层", rects(entityOverlaySlots())));
        problems.addAll(rectOverlapProblems("成就覆盖层", rects(advancementOverlaySlots())));
        problems.addAll(rectOverlapProblems("统计覆盖层", rects(statsOverlaySlots())));
        // ⑤'' 第 ① 页的「物品栏 41 格 / 末影箱」覆盖层：控件、41 格、27 格、开关、关闭按钮
        //      全都要两两不相交、且都在面板内（末影箱那 27 格与背包那 41 格共用同一片区域 ⇒
        //      两者<b>分别</b>与控件比，不能把两张格表合成一张来比）。
        final List<Rect> invControls = rects(inventoryOverlaySlots());
        final List<Rect> invCells41 = inventoryCellRects(false);
        final List<Rect> invCells27 = inventoryCellRects(true);
        final List<Rect> pickControls = rects(inventoryPickerSlots());
        final List<Rect> pickCells = pickerCellRects();
        problems.addAll(rectOverlapProblems("物品栏覆盖层", invControls));
        problems.addAll(rectOverlapProblems("物品栏格子(背包41)", invCells41));
        problems.addAll(rectOverlapProblems("物品栏格子(末影箱27)", invCells27));
        problems.addAll(rectOverlapProblems("物品栏格子+开关(背包)",
                concat(invCells41, invControls)));
        problems.addAll(rectOverlapProblems("物品栏格子+开关(末影箱)",
                concat(invCells27, invControls)));
        problems.addAll(rectOverlapProblems("物品栏选物器", pickControls));
        problems.addAll(rectOverlapProblems("物品栏选物器格子", pickCells));
        problems.addAll(rectOverlapProblems("物品栏选物器+格子", concat(pickCells, pickControls)));
        if (invCells41.size() != ShanhaiPlayerDataEdit.INVENTORY_GRID_SLOTS) {
            problems.add("物品栏格子数 " + invCells41.size() + " ≠ 41");
        }
        if (invCells27.size() != ShanhaiPlayerDataEdit.ENDER_SLOTS) {
            problems.add("末影箱格子数 " + invCells27.size() + " ≠ 27");
        }
        if (ShanhaiSaveEditInventoryUi.SEGMENTS.length != 4) {
            problems.add("物品栏分段数 " + ShanhaiSaveEditInventoryUi.SEGMENTS.length + " ≠ 4");
        }
        // ⑤' 第 ⑤⑥ 覆盖层的读数区必须留得下 8 行（最后一行的底 ≤ 统计页输入行/按钮行）
        final int ov2LastBottom = OV2_NOTE_Y0 + (OV2_NOTE_LINES - 1) * OV2_NOTE_STEP + 9;
        if (ov2LastBottom > OV2_IN_Y) {
            problems.add("成就/统计覆盖层读数压到输入行 last=" + ov2LastBottom + " in_y=" + OV2_IN_Y);
        }
        if (OV2_IN_Y + OV_IN_H > OV2_BTN_Y) {
            problems.add("统计覆盖层输入行压到按钮行 end=" + (OV2_IN_Y + OV_IN_H)
                    + " btn_y=" + OV2_BTN_Y);
        }
        // ⑥ 第 ⑦ 页：清单 / 选中 / 按钮 / 提示 之间的纵向关系
        final int auLastBottom = AU_LINE_Y0 + (AU_LINES - 1) * AU_LINE_STEP + 9;
        if (auLastBottom >= AU_SEL_Y) {
            problems.add("审计页清单压到选中行 last=" + auLastBottom + " sel_y=" + AU_SEL_Y);
        }
        if (AU_SEL_Y + 9 >= AU_BTN_Y) {
            problems.add("审计页选中行压到按钮 y=" + AU_SEL_Y + " btn_y=" + AU_BTN_Y);
        }
        if (AU_BTN_Y + AU_BTN_H >= AU_HINT_Y0) {
            problems.add("审计页按钮压到底部提示 y=" + AU_BTN_Y + " hint_y=" + AU_HINT_Y0);
        }
        if (AU_HINT_Y0 + 9 >= STATUS_Y) {
            problems.add("审计页提示压到状态行 y=" + AU_HINT_Y0);
        }
        // ⑥ 第 ⑦ 页的五个按钮：不越界、不互相压
        checkButton(problems, "上一条", AU_PREV_X, AU_PREV_W);
        checkButton(problems, "下一条", AU_NEXT_X, AU_NEXT_W);
        checkButton(problems, "删区块里那份", AU_DROP_ENTITY_X, AU_DROP_ENTITY_W);
        checkButton(problems, "删旁挂那行", AU_DROP_SIDECAR_X, AU_DROP_SIDECAR_W);
        checkButton(problems, "重新扫描", AU_RESCAN_X, AU_RESCAN_W);
        if (AU_PREV_X + AU_PREV_W > AU_NEXT_X) {
            problems.add("审计页「上一条」压「下一条」");
        }
        if (AU_NEXT_X + AU_NEXT_W > AU_DROP_ENTITY_X) {
            problems.add("审计页「下一条」压「删区块里那份」");
        }
        if (AU_DROP_ENTITY_X + AU_DROP_ENTITY_W > AU_DROP_SIDECAR_X) {
            problems.add("审计页「删区块里那份」压「删旁挂那行」");
        }
        if (AU_DROP_SIDECAR_X + AU_DROP_SIDECAR_W > AU_RESCAN_X) {
            problems.add("审计页「删旁挂那行」压「重新扫描」");
        }
        // ⑦ 每页都有控件
        for (int p = 0; p < PAGE_COUNT; p++) {
            if (pageWidgetCount(p) <= 0) {
                problems.add("第 " + p + " 页一个控件都没有");
            }
        }
        return problems;
    }

    /** 一个按钮的几何自检（审计页五个按钮用）。 */
    private static void checkButton(List<String> problems, String name, int x, int w) {
        if (x < 0 || x + w > W) {
            problems.add("审计页按钮「" + name + "」越界 x=" + x + " w=" + w);
        }
    }

    /**
     * 一页"可点的行 + 翻页行"的竖排自检（第 ④⑤⑥ 页共用这一条）。
     *
     * <p>三条：① 行距 ≥ 行高（不然相邻两行会互相盖住）；② 最后一行不压翻页行；
     * ③ 翻页行不压状态行。S2b 把 ⑤⑥ 也改成这种版式之后，这一条必须三页都跑一遍。
     */
    private static void checkRowRhythm(List<String> problems, String name, int rowY0, int rowStep,
                                       int rowH, int rows, int pagerY, int pagerH) {
        if (rowStep < rowH) {
            problems.add(name + "页行距 " + rowStep + " < 行高 " + rowH + "（相邻两行会叠在一起）");
        }
        final int lastBottom = rowY0 + (Math.max(1, rows) - 1) * rowStep + rowH;
        if (lastBottom > pagerY) {
            problems.add(name + "页最后一行压到翻页行 last=" + lastBottom + " pager_y=" + pagerY);
        }
        if (pagerY + pagerH > STATUS_Y) {
            problems.add(name + "页翻页按钮压到状态行 end=" + (pagerY + pagerH)
                    + " status_y=" + STATUS_Y);
        }
    }

    private static void checkLines(List<String> problems, String name, int y0, int step, int lines) {
        final int last = y0 + (Math.max(1, lines) - 1) * step + 9;
        if (y0 < 40) {
            problems.add(name + "页明细起点太高 y=" + y0);
        }
        if (last >= STATUS_Y) {
            problems.add(name + "页明细压到状态行 last=" + last + " status_y=" + STATUS_Y);
        }
    }

    // ================================================================== 翻页（纯下标运算，⑤⑥ 共用）

    /**
     * 一共几页（至少 1 页）。
     *
     * <p>⚠️ 第 ④ 页在 {@code ShanhaiSaveEditEntityUi} 里有一份等价的实现（那个类已验收、不许动），
     * 判据会把两边逐值对一遍 —— 两份口径必须给出同一组数字，否则"翻页"这件事就有两种真相。
     */
    public static int pageCount(int matched, int rowsPerPage) {
        final int rows = Math.max(1, rowsPerPage);
        return matched <= 0 ? 1 : (matched + rows - 1) / rows;
    }

    /** 把页号夹进 {@code [0, pageCount-1]}。 */
    public static int clampPage(int page, int matched, int rowsPerPage) {
        return Math.max(0, Math.min(pageCount(matched, rowsPerPage) - 1, page));
    }

    /** 第 {@code page} 页的第一条在整份列表里的下标（0 起）。 */
    public static int pageFrom(int page, int matched, int rowsPerPage) {
        return clampPage(page, matched, rowsPerPage) * Math.max(1, rowsPerPage);
    }

    /**
     * 为了显示第 {@code page} 页，数据层至少要取多少条。
     *
     * <p>数据层只支持"取前 N 条"（{@code listFile(file, …, maxEntries)} 没有 offset 参数），
     * 所以翻页的做法是<b>取前 (page+1)×每页 条，再切最后一段</b> —— 不用改数据层，也不会漏行。
     */
    public static int fetchLimit(int page, int matched, int rowsPerPage) {
        return (clampPage(page, matched, rowsPerPage) + 1) * Math.max(1, rowsPerPage);
    }

    // ================================================================== 读数

    /** 版式一行读数（装配时进日志；也是"面板画不出来时"的替代物）。 */
    public static String layoutLine() {
        return "save_edit_panel " + W + "x" + H
                + " pages=" + PAGE_COUNT
                + " tabs=[(" + TAB_X0 + "," + TAB_Y + "," + TAB_W + ")x" + PAGE_COUNT
                + " step=" + TAB_STEP + "]"
                + " buttons=[read(" + READ_X + "," + READ_W + ") confirm(" + CONFIRM_X + "," + CONFIRM_W
                + ")] h=" + TAB_H
                + " page0=[stage(" + STAGE_X + "," + STAGE_Y + ") summary_y=" + SUM_Y0 + "+" + SUM_STEP + "x3"
                + " rows=" + ROW_A_Y + "/" + ROW_B_Y + "/" + ROW_C_Y + "/" + ROW_D_Y + " fields=12]"
                + " page1=[fields=" + ShanhaiLevelDataSpec.FIELDS.size() + " rows_y=" + LV_ROW_Y0
                + "+" + LV_ROW_STEP + "x11 cols=(A=" + LV_A_LBL + "/" + LV_A_CUR + "/" + LV_A_FLD
                + " B=" + LV_B_LBL + "/" + LV_B_CUR + "/" + LV_B_FLD + ")]"
                + " page2=[region_row_y=" + RG_ROW1_Y + "/" + RG_ROW2_Y + "/" + RG_ROW3_Y
                + " btns_y=" + RG_BTN_Y + " be_nbt_y=" + RG_ROW5_Y + " be_y=" + RG_BE_Y + "]"
                + " page3=[entity dim_y=" + EN_DIM_Y + " search_y=" + EN_SEARCH_Y
                + " summary_y=" + EN_SUM_Y
                + " rows=" + EN_ROWS + "x" + EN_ROW_STEP + "@" + EN_ROW_Y0 + " h=" + EN_ROW_H
                + " pager_y=" + EN_PAGER_Y + "/" + EN_PAGER_H + " slots=" + entitySlots().size() + "]"
                + " page3_overlay=[x=" + OV_A_LBL_X + "/" + OV_A_FLD_X + "/" + OV_B_LBL_X + "/" + OV_B_FLD_X
                + " y=" + OV_IN_Y0 + "+" + OV_IN_STEP + "x8 h=" + OV_IN_H
                + " notes=" + OV_NOTE_LINES + "@" + OV_NOTE_Y0 + " dangers=" + OV_DANGER_LINES
                + "@" + OV_DANGER_Y0 + " btns_y=" + OV_BTN_Y + " slots=" + entityOverlaySlots().size() + "]"
                + " page4=[adv filter_y=" + LP_FILTER_Y + " summary_y=" + LP_SUM_Y
                + " rows=" + LP_ROWS + "x" + LP_ROW_STEP + "@" + LP_ROW_Y0 + " h=" + LP_ROW_H
                + " pager_y=" + LP_PAGER_Y + "/" + LP_PAGER_H + " hint_y=" + LP_HINT_Y
                + " slots=" + advancementSlots().size() + "]"
                + " page4_overlay=[notes=" + OV2_NOTE_LINES + "@" + OV2_NOTE_Y0 + "+" + OV2_NOTE_STEP
                + " btns_y=" + OV2_BTN_Y + " slots=" + advancementOverlaySlots().size() + "]"
                + " page5=[stats filter_y=" + LP_FILTER_Y + " summary_y=" + LP_SUM_Y
                + " rows=" + LP_ROWS + "x" + LP_ROW_STEP + "@" + LP_ROW_Y0 + " h=" + LP_ROW_H
                + " pager_y=" + LP_PAGER_Y + "/" + LP_PAGER_H + " hint_y=" + LP_HINT_Y
                + " slots=" + statsSlots().size() + "]"
                + " page5_overlay=[notes=" + OV2_NOTE_LINES + "@" + OV2_NOTE_Y0 + "+" + OV2_NOTE_STEP
                + " in_y=" + OV2_IN_Y + "/" + OV_IN_H + " btns_y=" + OV2_BTN_Y
                + " slots=" + statsOverlaySlots().size() + "]"
                + " page6=[audit summary_y=" + AU_SUMMARY_Y
                + " lines=" + AU_LINES + "x" + AU_LINE_STEP + "@" + AU_LINE_Y0
                + " sel_y=" + AU_SEL_Y + " btns_y=" + AU_BTN_Y + "/" + AU_BTN_H
                + " btns_w=[" + AU_PREV_W + "," + AU_NEXT_W + "," + AU_DROP_ENTITY_W + ","
                + AU_DROP_SIDECAR_W + "," + AU_RESCAN_W + "] hint_y=" + AU_HINT_Y0 + "]"
                + " page0_inventory=[cells=" + inventoryCellRects(false).size() + "/"
                + inventoryCellRects(true).size() + " cell=" + IV_CELL + " step=" + IV_STEP
                + " side_x=" + IV_SIDE_X + " main_x=" + IV_MAIN_X + " rows_y=" + IV_ROW_Y0
                + "/" + IV_HOTBAR_Y + "/" + IV_OFFHAND_Y
                + " switch=(" + IV_SWITCH_X + "," + IV_SWITCH_Y + "," + IV_SWITCH_W + "x" + IV_SWITCH_H + ")"
                + " close=(" + IV_CLOSE_X + "," + IV_CLOSE_Y + "," + IV_CLOSE_W + "x" + IV_CLOSE_H + ")"
                + " slots=" + inventoryOverlaySlots().size()
                + " picker_slots=" + inventoryPickerSlots().size()
                + " picker_cells=" + pickerCellRects().size() + "]"
                + " status=(" + STATUS_LBL_X + "/" + STATUS_VAL_X + "," + STATUS_Y + ")"
                + " widgets=" + expectedWidgetCount()
                + (layoutProblems().isEmpty() ? "" : " LAYOUT_PROBLEMS=" + layoutProblems());
    }

    @Override
    public String toString() {
        return "ShanhaiSaveEditLayout[" + PAGE_COUNT + " pages " + W + "x" + H + "]";
    }
}
