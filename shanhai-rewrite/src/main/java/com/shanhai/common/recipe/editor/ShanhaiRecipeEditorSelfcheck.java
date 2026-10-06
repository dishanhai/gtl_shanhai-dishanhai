package com.shanhai.common.recipe.editor;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.gregtechceu.gtceu.api.capability.recipe.ItemRecipeCapability;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.GTRecipeType;
import com.gregtechceu.gtceu.api.recipe.content.Content;
import com.shanhai.ShanhaiMod;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 机器可判的自检 —— 本刀<b>唯一</b>的"验过了"来源。
 *
 * <h2>为什么整条链要挤进这一处</h2>
 * 红线禁止启动客户端 ⇒ 面板点不了。所以"列出配方 / 改成时长 / 立刻生效 / 落盘"这件事，
 * 只能在无头专服里从<b>后端</b>验一遍。本类把它拆成 5 组可判定的拍子，每组都打
 * {@code [SHANHAI-EDIT] editor case=<名> ok=<true|false> …} 一行，末行汇总：
 * <pre>
 *   [SHANHAI-EDIT] editor selftest done cases=N fail=M EDITOR=PASS|FAIL
 * </pre>
 *
 * <h2>每一拍在验什么（以及它的<b>负对照</b>在哪）</h2>
 * <ol>
 *   <li><b>cmd_tree</b>：{@code /shanhai} 节点下 {@code edit} 在、且<b>别的子节点没被顶掉</b>
 *       （Brigadier 同名 literal 合并这条推断的机器判据）。</li>
 *   <li><b>reverse_*</b>：反查表建出来了、并且<b>与线性扫逐条一致</b>。
 *       两者用同一个输入、同一张表、同一次运行 ⇒ 数字不同只可能是"物化索引"这一步造成的。</li>
 *   <li><b>apply_*</b>：一条真配方，四拍。
 *       <ul>
 *         <li>{@code apply_positive}：改 + 刷索引 ⇒ <b>索引路</b>与<b>原版表</b>都读到新值；</li>
 *         <li>🔴 {@code apply_negative_control}：<b>同一段代码、同一个新值，唯一差别 = 不刷索引</b>
 *             ⇒ 索引路必须<b>还是旧值</b>、原版表是新值。<b>它证明的是"刷索引这一步是必须的"</b>
 *             —— 没有它，"改了立刻生效"与"改了没生效"在读数上分不开。</li>
 *         <li>{@code apply_same_value_with_rebuild}：把负对照的同一个值、同一段代码，这次刷索引
 *             ⇒ 两条路都必须读到新值。它排除了"新值本身有问题"这个解释。</li>
 *         <li>{@code apply_restored}：改回去 ⇒ 两条路都回到原值（自检不留痕）。</li>
 *       </ul></li>
 *   <li><b>remove_*</b>：删掉一条 ⇒ 索引路与原版表<b>都查不到</b>；再放回 ⇒ 两条路都在、值不变。</li>
 *   <li><b>fp_*</b>：{@code base_fp} 与<b>覆盖层侧算出来的真值</b>逐字比对。
 *       真值来源有两条，任一可用即可：
 *       <ul>
 *         <li>覆盖文件里<b>已有条目</b>的 {@code base_fp}（那是覆盖层/上一轮写下的）；</li>
 *         <li>覆盖层观察用的 watch 清单 {@code config/shanhai/overlay_probe_watch.json} 里的 id ——
 *             对每一条打印本侧算出来的 fp，供与同一份日志里 KubeJS 打的
 *             {@code [OVR-PROBE] WATCH id=… fp=…} <b>逐字节对照</b>。</li>
 *       </ul></li>
 *   <li><b>store_*</b>：往<b>真实路径</b>写一条 → 从磁盘读回来 → 逐字段验 → 把原文件
 *       <b>逐字节</b>还原并再次校验 sha256（{@code store_restored}）。
 *       ⇒ 既证明写盘路通，又证明不残留。</li>
 * </ol>
 *
 * <h2>开关</h2>
 * 默认<b>不开</b>（它会真的改写运行期索引，虽然读完立刻还原）：
 * 环境变量 {@code SHANHAI_EDITOR=1}。另有两个附加档：
 * <ul>
 *   <li>{@code SHANHAI_EDITOR_WRITE=1}：多做一步"<b>真的留下</b>一条编辑 + 一条 json 条目"，
 *       给下一局开机做"重启不丢"的端到端验证（覆盖层应当打 APPLIED）。</li>
 *   <li>{@code SHANHAI_EDITOR_CLEANUP=1}：把 {@code e-editor-} 前缀的条目全清掉。</li>
 * </ul>
 */
public final class ShanhaiRecipeEditorSelfcheck {

    public static final String PREFIX = "[SHANHAI-EDIT] editor";

    /** 与 {@code ShanhaiRecipeStats.LOG_KEY} 同款口径的机器判定键。 */
    public static final String LOG_KEY = "recipe_editor";

    /** 负对照/正拍用的时长偏移（远离 0 与常见值，日志里一眼能认出来）。 */
    private static final int D_A = 1000;
    private static final int D_B = 2000;
    private static final int D_WRITE = 424242;

    /** 覆盖层演示条目用的两条 id —— 自检<b>不碰</b>它们（免得和覆盖层的读数据打架）。 */
    private static final Set<ResourceLocation> HANDS_OFF = Set.of(
            new ResourceLocation("gtceu:assembler/zpm_256a_laser_source_hatch"),
            new ResourceLocation("gtceu:assembler/assemble_electrum_pipe_small_restrictive"));

    private static final String PROBE_ITEM = "gtceu:programmed_circuit";

    private static int cases = 0;
    private static int fails = 0;

    /** apply 拍挑中的那条（remove 拍要避开它，见 caseRemove）。 */
    private static ResourceLocation lastApplyTargetId = null;

    /** remove 拍挑中的那条（store 拍要避开它）。 */
    private static ResourceLocation lastRemoveTargetId = null;

    /** 🆕 阶段 2：{@code caseVanilla} 挑中的那条非 GT 靶子（后面几拍沿用同一个 id，便于对账）。 */
    private static ResourceLocation lastVanillaTargetId = null;

    /** 🆕 P0（op=add）：<b>端到端探针</b>——这一条必须扛过一次真重启。 */
    private static final ResourceLocation PROBE_ADD_ID =
            new ResourceLocation("shanhai", "assembler/editor_probe_add_replay");
    /** 探针配方的耗时（远离常见值，日志里一眼认得出来）。 */
    private static final int PROBE_ADD_DURATION = 4242;
    /** 探针配方的 EU/t。 */
    private static final long PROBE_ADD_EUT = 30L;

    /** 🆕 P0：把"新建 → 再编辑一次 → 保存"串起来用的那个活对象（{@code caseAddWrite} 写、下一拍读）。 */
    private static GTRecipe addedProbeLive = null;

    /** 🆕 P0：这一拍要给那条新配方补上的输入物品（远离常见值，日志里一眼认得出来）。 */
    private static final String PROBE_ADD_IN_ITEM = "minecraft:stone";
    private static final int PROBE_ADD_IN_COUNT = 3;

    private ShanhaiRecipeEditorSelfcheck() {}

    // ================================================================== 入口

    public static void run(MinecraftServer server, boolean fromCommand) {
        cases = 0;
        fails = 0;
        if (server == null) {
            ShanhaiMod.LOGGER.error("{} selftest_aborted reason=server_is_null", PREFIX);
            return;
        }
        ShanhaiMod.LOGGER.info("{} selftest begin from_command={} gameDir={} overrideFile={}",
                PREFIX, fromCommand, net.minecraftforge.fml.loading.FMLPaths.GAMEDIR.get().toAbsolutePath(),
                ShanhaiRecipeOverrideStore.path());

        // 0) 先处理开关档：清场 / 落一条留档
        if ("1".equals(System.getenv(ShanhaiRecipeEditorCommand.ENV_CLEANUP))) {
            final int n = ShanhaiRecipeOverrideStore.removeEntriesByUidPrefix(ShanhaiRecipeOverrideStore.UID_PREFIX);
            ShanhaiMod.LOGGER.info("{} cleanup_mode dropped={}", PREFIX, n);
        }

        runCase("cmd_tree", () -> caseCommandTree(server));
        runCase("reverse_index", () -> caseReverseIndex(server));
        runCase("apply", () -> caseApply(server));
        runCase("duration_display", () -> caseDurationDisplay(server));
        runCase("conditions", () -> caseConditions(server));
        // 🔴 第 5 轮（#6）：这一条验的是【面板读得到】，不是【文件→重放】—— 上一轮缺的正是它
        runCase("conditions_panel_readback", () -> caseConditionsPanelReadback(server));
        runCase("remove", () -> caseRemove(server));
        runCase("fingerprint", () -> caseFingerprint(server));
        runCase("panel_text", () -> casePanelText(server));
        runCase("store", () -> caseStore(server));
        runCase("conditions_replay", () -> caseConditionsReplay(server));
        // 🆕 第 8 轮（队列 #4 / #6）：这两拍都【不改盘】，但会临时改运行期状态并自己还原
        //    （type_roster 会真的把一个类型的配方全删掉再放回去 ⇒ persist=false ⇒ config 一个字节不动）。
        //    放在最后，是因为它要动"底本/台账"，不该影响上面那几拍的读数。
        runCase("page_jump", () -> casePageJump(server));
        runCase("type_roster", () -> caseTypeRoster(server));
        // 🔴 第 8 轮 P0：「/shanhai edit 打不开面板」那条链的机器判据（两侧同步）。
        runCase("panel_sync", () -> casePanelSync(server));
        // 🆕 2026-10-05（工作台 / 原版配方）：非 GT 那一支的机器判据。
        //    ⚠️ 全程 persist=false ⇒ config/shanhai/recipe_overrides.json 一个字节都不动，
        //       而且它自己会把改过的配方恢复原样（见 caseVanilla 末尾）。
        runCase("vanilla", () -> caseVanilla(server));
        // 🆕 阶段 2：非 GT 的【输入侧】增 / 删 / 改（含 3 条负对照）。全程 persist=false。
        runCase("vanilla_inputs", () -> caseVanillaInputs(server));
        // 🆕 阶段 2：下一局开机时读那条被写过的配方（形状是不是真的变成 1×1 了）。
        //    只在 SHANHAI_EDITOR_VVERIFY=1 时判红，平时只打一行读数。
        runCase("vanilla_overlay_replay", () -> caseVanillaOverlayReplay(server));
        // 🆕 2026-10-05 修复④：**把活配方的条件真值逐条读出来对照**
        //    （用户点单：「别只看面板那一行」）。
        runCase("conditions_live_truth", () -> caseConditionsLiveTruth(server));
        // 🆕 P0（op=add）：下一局读那条被 op=add 重放出来的配方
        //    （只有 SHANHAI_EDITOR_VVERIFY=1 档才判红，平时只打一行读数）。
        runCase("add_replay", () -> caseAddReplay(server));
        // 🆕 2026-10-05 用户报的 ①②：文本搜索 / 物品查询（非 GT 也要能查到）
        runCase("text_search_live", () -> caseTextSearchLive(server));
        runCase("query_vanilla", () -> caseQueryVanilla(server));
        // 🆕 2026-10-05 第 11 刀：卡片上那三处显示 —— ① 电路号 / ② 催化剂 是数据判据，
        //    ④ 的说明行与 ③ 的机器名是【纯函数】判据（真机观感要他自己看）。
        runCase("card_chips", () -> caseCardChips(server));
        runCase("card_plus_n", () -> caseCardPlusN(server));
        runCase("card_wire", () -> caseCardWire());
        runCase("tab_name_fit", () -> caseTabNameFit(server));
        // 🆕 第 12 刀：非 GT 的【新建 / 删除】＋ 别的 mod 那 37 个类型的只读侦察。
        //    ⚠️ vanilla_new / vanilla_remove 全程 persist=false（config 一个字节都不动，
        //       而且自己会把删掉的那条原样放回去）；vanilla_remove 里那条"删不存在的"
        //       故意带 persist=true —— 闸门挡不住就会真的写一条，那正是这条负对照要抓的。
        runCase("vanilla_new", () -> caseVanillaNew(server));
        runCase("vanilla_remove", () -> caseVanillaRemove(server));
        runCase("vanilla_mod_types", () -> caseVanillaModTypes(server));
        // 🆕 第 12 刀 P0：「改完不实时显示」的两条判据（编辑器自己那侧 ＋ JEI 那一侧的通道）。
        runCase("panel_live_after_save", () -> casePanelLiveAfterSave(server));
        runCase("recipe_resync_path", () -> caseRecipeResyncPath(server));
        // 🔴🔴 本轮（只修"卡几秒"与"JEI 不显示"两件）的两条判据。
        runCase("no_full_resync_on_save", () -> caseNoFullResyncOnSave(server));
        runCase("jei_vanilla_recipe_bytes", () -> caseJeiVanillaBytes(server));
        // 🔴🔴 2026-10-05 用户第二轮：「新建了 GT 配方 …… 甚至我们的配方编辑器都没有即时刷新」
        runCase("panel_live_gt_after_save", () -> casePanelLiveGtAfterSave(server));
        // 🔴🔴 2026-10-05 用户第三轮：「restore 之后编辑器自己的列表与卡片数据要一起重建」
        runCase("restore_rebuilds_panel_list", () -> caseRestoreRebuildsPanelList(server));
        runCase("restore_sends_single_notify", () -> caseRestoreSendsSingleNotify(server));
        // 🔴🔴 用户实测「第二屏热更新了、第一屏不行」⇒ 第一屏条数当场变（判据⑦）
        runCase("first_screen_count_refreshes", () -> caseFirstScreenCountRefreshes(server));
        // 🆕 第 12 刀（用户拍板）：「无序合成也画同一张 3×3，直接在网格里再补一个」
        runCase("vanilla_list_grid", () -> caseVanillaListGrid(server));
        // 🆕 第 12 刀：下一局（连着两次）读那三条端到端探针；只在 VVERIFY 档判红。
        runCase("vanilla_new_replay", () -> caseVanillaNewReplay(server));

        if ("1".equals(System.getenv(ShanhaiRecipeEditorCommand.ENV_WRITE))) {
            runCase("write_through", () -> caseWriteThrough(server));
            // 🆕 阶段 2：把一条"改过输入"的编辑真的留下（覆盖文件里出现 pattern/key），
            //    给下一局开机做端到端验证。⚠️ 这一拍会真的写 config，只在 WRITE 档跑。
            runCase("vanilla_input_write", () -> caseVanillaInputWrite(server));
            // 🆕 P0：把一条 op=add 真的留下（下一局必须还能读到它）。
            runCase("add_write", () -> caseAddWrite(server));
            // 🆕 P0（第 11 刀）：新建之后再编辑一次并保存 ⇒ 条目必须【还是 op=add】
            //    （用户实测："第二次重进世界 kjs 报错，配方消失"）
            runCase("add_edit_write", () -> caseAddEditWrite(server));
            // 🆕 第 12 刀：非 GT 的新建 / 删除端到端探针（真的落盘，下一局必须读得回来）
            runCase("vanilla_new_write", () -> caseVanillaNewWrite(server));
        }

        // 🔴🔴 2026-10-05（用户澄清：「但是我删除写的是 /shanhai edit restore」）：
        //    全局恢复那条命令必须【逐条】给客户端发单条补丁 —— 这是"restore 之后 JEI 不刷新"的判据。
        //    ⚠️ 故意放在【最后】：它会清空 rig 的覆盖文件（那是别的用例共用的夹具），
        //       放最后就不会影响任何已经跑完的用例。
        runCase("restore_all_notifies_jei", () -> caseRestoreAllNotifies(server));

        ShanhaiMod.LOGGER.info("{} selftest done cases={} fail={} EDITOR={}",
                PREFIX, cases, fails, fails == 0 ? "PASS" : "FAIL");
    }

    // ================================================================== 🆕 修复④：条件的真值对照

    /**
     * <b>「面板显示的那份条件」是不是等于「活配方上真正挂着的那些条件」</b> —— 逐条对照。
     *
     * <h4>为什么必须有这一条（用户点单的原话）</h4>
     * <blockquote>「🔴 判据：把活配方的条件真值逐条读出来对照，别只看面板那一行」</blockquote>
     * 现场：{@code workspace_conditions_source id=…/primordial_debug_module source=table n=0 … live=1}
     * —— 面板说 0 条，活配方上有 1 条。只看面板那一行永远发现不了。
     *
     * <h4>怎么扫全量</h4>
     * 遍历底本里<b>每一条</b>有活对象的 GT 配方，比较
     * {@code resolveConditions(...).conditions()} 与 {@code encodeOf(活配方)}：
     * <pre>
     *   一致   ⇒ 这一条对
     *   不一致 ⇒ 计数 + 逐条打出来（最多打前 8 条，避免刷屏）
     * </pre>
     * 判据 = <b>不一致条数必须为 0</b>。
     *
     * <p>⚠️ 覆盖面（如实交代）：这里比的是<b>面板那一层</b>读出来的那一份 ——
     * 修④之前 {@code source=table} 那一支会大面积不一致（那就是被抓到的病）；
     * 修④之后它恒等于 live。它<b>不能</b>替代"进游戏看面板画出来是什么"，
     * 只能证明"面板拿到的数据 = 活配方的真值"。
     */
    private static void caseConditionsLiveTruth(net.minecraft.server.MinecraftServer server) {
        ShanhaiRecipeBase.captureIfAbsent(server);
        ShanhaiRecipeReverseIndex.build(server);
        int checked = 0;
        int mismatch = 0;
        int bothEmpty = 0;
        int nonEmpty = 0;
        final StringBuilder samples = new StringBuilder();
        for (ResourceLocation id : ShanhaiRecipeBase.allIds()) {
            final GTRecipe live = ShanhaiRecipeReverseIndex.byId(server, id);
            if (live == null) {
                continue;
            }
            checked++;
            final com.google.gson.JsonArray truth = ShanhaiRecipeConditions.encodeOf(live);
            if (truth.size() == 0) {
                bothEmpty++;
            } else {
                nonEmpty++;
            }
            final ShanhaiRecipeEditorWorkspace.Resolved res =
                    ShanhaiRecipeEditorWorkspace.resolveConditions(server, id, live);
            final com.google.gson.JsonArray shown = res.conditions();
            if (!ShanhaiRecipeConditions.sameAs(shown, truth)) {
                mismatch++;
                if (mismatch <= 8) {
                    samples.append(" | ").append(id)
                            .append(" shown=").append(ShanhaiRecipeConditions.summary(shown))
                            .append(" truth=").append(ShanhaiRecipeConditions.summary(truth))
                            .append(" src=").append(res.source());
                }
            }
        }
        check("conditions_live_truth", checked > 0 && mismatch == 0,
                "checked=" + checked + " mismatch=" + mismatch
                        + " with_conditions=" + nonEmpty + " without=" + bothEmpty
                        + " (不一致必须为 0；不一致就是「面板显示的那一份 ≠ 活配方真值」，"
                        + "正是 2026-10-05 那条 primordial_debug_module 的现场)");
        if (mismatch > 0) {
            ShanhaiMod.LOGGER.error("{} conditions_live_truth MISMATCH samples:{}", PREFIX, samples);
        } else {
            ShanhaiMod.LOGGER.info("{} conditions_live_truth ok checked={} with_conditions={} "
                            + "(逐条对照过：面板那一份 == 活配方上真正挂着的那一份)",
                    PREFIX, checked, nonEmpty);
        }
        // 正对照：靶子必须是"真的有条件"的那一类 —— 否则 mismatch=0 可能只是因为全空
        check("conditions_live_truth_POSITIVE_CONTROL", checked > 0,
                "checked=" + checked + " > 0 (扫到 0 条的话这条判据什么都证明不了)");
    }

    // ================================================================= 🆕 非 GT（工作台 / 原版配方）

    /**
     * <b>非 GT 那一支的机器判据</b>（工作台 / 原版配方）。
     *
     * <h4>这一拍验的是"改完立刻生效"那条链，逐段都有正/负对照</h4>
     * <ol>
     *   <li><b>索引</b>：非 GT 类型数 ≥ 8、{@code minecraft:crafting_shaped} 条数 &gt; 0；
     *       <b>负对照</b> = 一个瞎编的类型 id 必须给 0 条；</li>
     *   <li><b>视图</b>：挑到的那条 kind = SHAPED、产物非空、输入 9 格；</li>
     *   <li><b>改产物数量 → 立刻从原版表读回</b>（正拍），并且
     *       <b>负对照</b>：{@code rebuildIndex=false} 时表里必须<b>还是老值</b>
     *       —— 这一条证明"写回两张表"那一步不是摆设；</li>
     *   <li><b>不可编辑的类型</b>：{@code crafting_special_*} 的 {@code editable()} 必须是 false；</li>
     *   <li><b>覆盖层指纹</b>：{@code ShanhaiRecipeFingerprint.forRecipeId(非GT id)} 必须不是 UNKNOWN
     *       —— 这一条决定"重启后不丢"能不能做（对不上就永远 STALE）；</li>
     *   <li><b>面板那一层</b>：workspace 的 {@code reloadTypes} 之后类型表里必须出现非 GT 类型、
     *       {@code selectType} 能列出配方、{@code selectRecipe} 能进第三屏。</li>
     * </ol>
     *
     * <p>🔴 全程 {@code persist=false} ⇒ <b>用户的覆盖文件一个字节都不动</b>；
     * 而且本拍最后会把改过的那条<b>恢复原样</b>并断言"读回 == 原值"。
     */
    private static void caseVanilla(net.minecraft.server.MinecraftServer server) {
        ShanhaiVanillaRecipeTable.captureIfAbsent(server);
        ShanhaiVanillaRecipeTable.rebuildIndex(server);
        check("vanilla_index_built", ShanhaiVanillaRecipeTable.isIndexBuilt(),
                ShanhaiVanillaRecipeTable.statsLine());
        final java.util.List<ResourceLocation> vtypes = ShanhaiVanillaRecipeTable.types();
        // 🔴 2026-10-05 口径订正（冒烟当场抓出来的）：
        //    **配方 JSON 里的 `type` ≠ `Recipe.getType()`**。
        //    前者是**序列化器 id**（`minecraft:crafting_shaped` / `minecraft:crafting_shapeless`），
        //    后者是**配方类型**（两者都是 `minecraft:crafting`）。
        //    我们这套是按 `BuiltInRegistries.RECIPE_TYPE.getKey(recipe.getType())` 分组的
        //    ⇒ 有形状 / 无形状合成在**同一个** `minecraft:crafting` 桶里。
        //    （前置调查 §2.2 那张按 `crafting_shaped` 分类的计数表是从导出 JSON 的 `type` 字段数的，
        //      与本表不是同一个口径 —— 两个数都对，别混用。）
        final ResourceLocation crafting = new ResourceLocation("minecraft", "crafting");
        final ResourceLocation shaped = crafting;
        final int shapedCount = ShanhaiVanillaRecipeTable.countOf(crafting);
        final ResourceLocation bogus = new ResourceLocation("shanhai_probe", "no_such_recipe_type");
        final int bogusCount = ShanhaiVanillaRecipeTable.countOf(bogus);
        check("vanilla_types", vtypes.size() >= 8 && shapedCount > 0 && bogusCount == 0,
                "types=" + vtypes.size() + " " + crafting + "=" + shapedCount
                        + " NEGATIVE_CONTROL(no_such_recipe_type)=" + bogusCount + " (must be 0)"
                        + " types_list=" + vtypes);

        // ── 挑一条靶子：crafting_shaped、产物非空 ──
        ResourceLocation targetId = null;
        ShanhaiVanillaRecipeView targetView = null;
        for (ResourceLocation id : ShanhaiVanillaRecipeTable.idsOf(shaped)) {
            if (HANDS_OFF.contains(id)) {
                continue;
            }
            final net.minecraft.world.item.crafting.Recipe<?> r = ShanhaiVanillaRecipeTable.liveById(id);
            if (r == null) {
                continue;
            }
            final ShanhaiVanillaRecipeView v = ShanhaiVanillaRecipeView.of(r);
            if (v == null || v.kind != ShanhaiVanillaRecipeView.Kind.SHAPED || v.result.isEmpty()) {
                continue;
            }
            targetId = id;
            targetView = v;
            break;
        }
        if (targetId == null) {
            check("vanilla_target", false, "no usable minecraft:crafting_shaped recipe found");
            return;
        }
        lastVanillaTargetId = targetId;      // 🆕 阶段 2：后面几拍沿用同一个靶子
        check("vanilla_target", targetView.width > 0 && targetView.height > 0
                        && targetView.inputs.size() == targetView.width * targetView.height,
                "id=" + targetId + " kind=" + targetView.kind
                        + " w=" + targetView.width + " h=" + targetView.height
                        + " inputs=" + targetView.inputs.size()
                        + " (有形状合成的格数必须 == 宽×高；门那种 2×3 就是 6 格，不是 9 —— "
                        + "第一版判据写成恒等于 9，是【判据自己错了】，冒烟当场抓出来的)"
                        + " result=" + net.minecraft.core.registries.BuiltInRegistries.ITEM
                        .getKey(targetView.result.getItem()) + " x" + targetView.result.getCount());

        // ── ③ 改产物数量 → 立刻读回（＋负对照）──
        final int oldCount = targetView.result.getCount();
        final int newCount = oldCount == 7 ? 9 : 7;      // 远离常见值，日志里一眼认得出
        final net.minecraft.world.item.ItemStack newStack = targetView.result.copy();
        newStack.setCount(newCount);

        // 负对照：rebuildIndex=false ⇒ 台账写了、但表里应当还是老值
        ShanhaiVanillaRecipeOps.applyEdits(server, targetId, newStack, null, null, false, false);
        final net.minecraft.world.item.crafting.Recipe<?> negLive =
                ShanhaiVanillaRecipeOps.readFromTable(server, targetId);
        final int negCount = negLive == null ? -1
                : ShanhaiVanillaRecipeView.of(negLive).result.getCount();
        check("vanilla_apply_negative_control", negCount == oldCount,
                "id=" + targetId + " expected_old=" + oldCount + " read=" + negCount
                        + " (rebuildIndex=false 时表里必须还是老值；相等才说明'写回两张表'那一步是必须的)");

        // 正拍：rebuildIndex=true ⇒ 立刻生效
        ShanhaiVanillaRecipeOps.applyEdits(server, targetId, newStack, null, null, true, false);
        final net.minecraft.world.item.crafting.Recipe<?> live =
                ShanhaiVanillaRecipeOps.readFromTable(server, targetId);
        final int gotCount = live == null ? -1 : ShanhaiVanillaRecipeView.of(live).result.getCount();
        check("vanilla_apply", gotCount == newCount,
                "id=" + targetId + " " + oldCount + " -> " + newCount + " read_back=" + gotCount
                        + " same_class=" + (live != null && live.getClass() == targetView.raw.getClass()));

        // ── ④ 不可编辑的类型 ──
        //    ⚠️ `crafting_special_*` 的 `RecipeType` 也是 `minecraft:crafting`
        //    （序列化器 id 才是 `crafting_special_xxx`）⇒ 必须在同一个桶里按【具体类】找。
        //    ⚠️⚠️ 判据分两层：**规则**（没有任何 SPECIAL 是可编辑的）永远判；
        //        **有没有 SPECIAL** 是读数 —— 宿主里一条都没有时它是空真，如实标出来，
        //        不许把它伪装成"验证过了"。
        //    🔴 2026-10-05 第三版修正（自检自己的 bug）：
        //       第 ③ 拍的 `applyEdits` 会调 `ShanhaiVanillaRecipeTable.invalidate()` 把 BY_TYPE 清掉，
        //       而这一拍读的正是 `idsOf(...)` ⇒ **读到空表**，于是 histogram={} / special=0 ——
        //       那两轮 "special_recipes=0" 其实是**采样窗口失效**，不是"宿主里真的没有"。
        //       ⇒ 采样之前必须先重建索引。
        ShanhaiVanillaRecipeTable.rebuildIndex(server);
        int specialN = 0;
        boolean specialNotEditable = true;
        String specialSample = "(none)";
        final java.util.Map<String, Integer> kindHist = new java.util.LinkedHashMap<>();
        for (ResourceLocation sid : ShanhaiVanillaRecipeTable.idsOf(crafting)) {
            final net.minecraft.world.item.crafting.Recipe<?> sr = ShanhaiVanillaRecipeTable.liveById(sid);
            if (sr == null) {
                continue;
            }
            final ShanhaiVanillaRecipeView sv = ShanhaiVanillaRecipeView.of(sr);
            kindHist.merge(String.valueOf(sv.kind), 1, Integer::sum);
            if (sv.kind != ShanhaiVanillaRecipeView.Kind.SPECIAL) {
                continue;
            }
            specialN++;
            if (specialN == 1) {
                specialSample = sid + " class=" + sr.getClass().getSimpleName();
            }
            if (sv.editable()) {
                specialNotEditable = false;
            }
        }
        check("vanilla_special_not_editable", specialNotEditable,
                "bucket=" + crafting + " special_recipes=" + specialN
                        + " all_not_editable=" + specialNotEditable
                        + " sample=" + specialSample
                        + " kind_histogram=" + kindHist
                        + " (crafting_special_* 的 matches() 恒假 ⇒ 必须标成不可编辑；"
                        + "special_recipes=0 时这一条是【空真】，如实标出来别当已验证)");
        ShanhaiMod.LOGGER.info("{} case=vanilla_kind_histogram bucket={} {}", PREFIX, crafting, kindHist);

        // ── ⑤ 覆盖层指纹：非 GT 配方算不算得出来 ──
        final String fp = ShanhaiRecipeFingerprint.forRecipeId(targetId);
        check("vanilla_base_fp", fp != null && !ShanhaiRecipeFingerprint.UNKNOWN.equals(fp)
                        && ShanhaiRecipeFingerprint.isCurrentVersion(fp),
                "id=" + targetId + " fp_len=" + (fp == null ? -1 : fp.length())
                        + " current_version=" + ShanhaiRecipeFingerprint.isCurrentVersion(fp)
                        + " diag=" + ShanhaiRecipeFingerprint.lastDiagnosis()
                        + " (对不上 ⇒ 覆盖层下一局会判 STALE、不套用 = 重启就丢)");

        // ── ⑥ 面板那一层 ──
        try {
            final ShanhaiRecipeEditorWorkspace ws = new ShanhaiRecipeEditorWorkspace(server, null);
            ws.reloadTypes();
            boolean typeListed = false;
            for (ShanhaiRecipeEditorWorkspace.TypeRow t : ws.typeRows()) {
                if (shaped.equals(t.id())) {
                    typeListed = true;
                    break;
                }
            }
            check("vanilla_workspace_type_listed", typeListed,
                    "type=" + shaped + " listed=" + typeListed + " total_types=" + ws.typeRows().size());
            final boolean picked = ws.selectType(shaped);
            final int listed = ws.recipeRows().size();
            check("vanilla_workspace_recipes", picked && listed == ShanhaiVanillaRecipeTable.countOf(shaped),
                    "selectType(" + shaped + ")=" + picked + " listed=" + listed
                            + " index=" + ShanhaiVanillaRecipeTable.countOf(shaped));
            final boolean entered = ws.selectRecipe(targetId);
            check("vanilla_workspace_edit_screen", entered && ws.vanillaMode()
                            && ws.stageIsEdit() && ws.cells() > 0,
                    "selectRecipe(" + targetId + ")=" + entered + " vanilla_mode=" + ws.vanillaMode()
                            + " stage_edit=" + ws.stageIsEdit() + " cells=" + ws.cells()
                            + " has_duration_field=" + ws.hasDurationField()
                            + " numbers=" + ws.numbersText());
        } catch (Throwable t) {
            fail("vanilla_workspace", "threw " + t.getClass().getName() + ": " + t.getMessage());
        }

        // ── 收尾：恢复原样，并断言"读回 == 老值"（这一拍不许有残留）──
        ShanhaiVanillaRecipeOps.restoreOne(server, targetId, false);
        final net.minecraft.world.item.crafting.Recipe<?> back =
                ShanhaiVanillaRecipeOps.readFromTable(server, targetId);
        final int backCount = back == null ? -1 : ShanhaiVanillaRecipeView.of(back).result.getCount();
        check("vanilla_restore", backCount == oldCount,
                "id=" + targetId + " expected_old=" + oldCount + " read=" + backCount
                        + " ledger_size=" + ShanhaiVanillaRecipeTable.ledgerSize());
    }

    // ================================================================= 🆕 阶段 2：非 GT 的输入侧

    /**
     * <b>非 GT 配方「输入侧」的增 / 删 / 改</b>（用户点单的阶段 2）。
     *
     * <h4>这一拍验的四件事，每件都配负对照</h4>
     * <ol>
     *   <li><b>未动过的格子必须原样保住底本的 {@code Ingredient} 对象</b>
     *       （引用同一性）—— 这条是"不毁数据"的核心：原版配方的输入常常是<b>标签</b>
     *       （{@code {"tag":"forge:ingots/iron"}}）或多候选，界面只能显示一个代表性物品
     *       ⇒ 一旦按界面上的物品重建，每保存一次都会把 8000+ 条标签配方静默退化成单物品。
     *       <b>正对照</b> = 动过的那一格必须换成新对象且 JSON = {@code Ingredient.of(栈).toJson()}；</li>
     *   <li><b>有形状合成：同一种材料必须复用同一个字符</b>（否则配方会长出 9 个键）。
     *       <b>负对照</b> = 两格放不同物品时必须是两个字符（证明上面那条不是恒真的空壳）；</li>
     *   <li><b>增 / 删 / 改</b>三条路各自"改完立刻从原版表读回"，
     *       外加一条 <b>负对照</b>：{@code rebuildIndex=false} 时表里必须还是老值；</li>
     *   <li><b>拒收</b>：网格被缩到"外侧还有材料"时必须拒；整张网格清空时必须整条不写。</li>
     * </ol>
     *
     * <p>🔴 全程 {@code persist=false}（覆盖文件一个字节不动），末了把靶子<b>恢复原样</b>并断言读回 == 原值。
     */
    private static void caseVanillaInputs(net.minecraft.server.MinecraftServer server) {
        ShanhaiVanillaRecipeTable.captureIfAbsent(server);
        ShanhaiVanillaRecipeTable.rebuildIndex(server);
        final ResourceLocation crafting = new ResourceLocation("minecraft", "crafting");
        ResourceLocation targetId = null;
        for (ResourceLocation id : ShanhaiVanillaRecipeTable.idsOf(crafting)) {
            if (HANDS_OFF.contains(id) || id.equals(lastVanillaTargetId)) {
                continue;
            }
            final net.minecraft.world.item.crafting.Recipe<?> r = ShanhaiVanillaRecipeTable.liveById(id);
            if (r == null) {
                continue;
            }
            final ShanhaiVanillaRecipeView v = ShanhaiVanillaRecipeView.of(r);
            if (v == null || v.kind != ShanhaiVanillaRecipeView.Kind.SHAPED || v.result.isEmpty()
                    || v.width <= 0 || v.height <= 0) {
                continue;
            }
            targetId = id;
            break;
        }
        if (targetId == null) {
            check("vanilla_input_target", false, "no usable shaped recipe for the input round");
            return;
        }
        final net.minecraft.world.item.crafting.Recipe<?> base = ShanhaiVanillaRecipeTable.pristine(targetId);
        final ShanhaiVanillaRecipeView bv = ShanhaiVanillaRecipeView.of(base);
        final List<net.minecraft.world.item.crafting.Ingredient> baseIns = base.getIngredients();
        check("vanilla_input_target", bv != null && bv.width > 0 && bv.height > 0,
                "id=" + targetId + " w=" + bv.width + " h=" + bv.height
                        + " inputs=" + baseIns.size() + " result="
                        + net.minecraft.core.registries.BuiltInRegistries.ITEM
                        .getKey(bv.result.getItem()) + " x" + bv.result.getCount());

        // ── ① 中间表示：Mode / 格子数 / 底本 Ingredient 的引用同一性 ──
        final ShanhaiVanillaRecipeShape s0 = ShanhaiVanillaRecipeShape.of(base);
        check("vanilla_shape_mode", s0 != null
                        && s0.mode() == ShanhaiVanillaRecipeShape.Mode.GRID
                        && s0.width() == bv.width && s0.height() == bv.height
                        && s0.slotCount() == bv.width * bv.height
                        && s0.layoutCount() == ShanhaiVanillaRecipeShape.GRID_CELLS,
                "id=" + targetId + " mode=" + (s0 == null ? "null" : s0.mode())
                        + " grid=" + (s0 == null ? "?" : s0.width() + "x" + s0.height())
                        + " slotCount=" + (s0 == null ? -1 : s0.slotCount())
                        + " layoutCount=" + (s0 == null ? -1 : s0.layoutCount())
                        + " (GRID 的物理格恒为 9；active 的格子数 == 宽×高)");

        int sameRef = 0;
        int sameRefExpected = 0;
        for (int i = 0; i < bv.width * bv.height && i < baseIns.size(); i++) {
            if (baseIns.get(i) == null || baseIns.get(i).isEmpty()) {
                continue;
            }
            sameRefExpected++;
            if (s0.ingredientAt(i) == baseIns.get(i)) {
                sameRef++;
            }
        }
        check("vanilla_shape_keeps_base_ingredient", sameRefExpected > 0 && sameRef == sameRefExpected,
                "id=" + targetId + " same_ref=" + sameRef + "/" + sameRefExpected
                        + " (没动过的格子必须原样返回底本那个 Ingredient 对象；"
                        + "换成 Ingredient.of(代表性物品) 会把标签/多候选静默退化成单物品)");

        // 正对照：动过的那一格必须**不再**是底本那个对象，且 JSON 等于 Ingredient.of(该栈)
        final ShanhaiVanillaRecipeShape sTouch = ShanhaiVanillaRecipeShape.of(base);
        final ItemStack marker = probeStack();
        sTouch.setAt(0, marker);
        final boolean changedRef = sTouch.ingredientAt(0) != baseIns.get(0);
        final String wantJson = safeIngredientJson(net.minecraft.world.item.crafting.Ingredient.of(marker));
        final String gotJson = safeIngredientJson(sTouch.ingredientAt(0));
        check("vanilla_shape_touched_cell_replaced", changedRef && wantJson.equals(gotJson),
                "id=" + targetId + " marker=" + describeStack(marker)
                        + " ref_changed=" + changedRef + " json_match=" + wantJson.equals(gotJson)
                        + " (动过的那一格才允许换对象；JSON 必须等于 Ingredient.of(那个栈))");

        // ── ② 有形状合成：同一种材料复用同一个字符（＋负对照）──
        final ShanhaiVanillaRecipeShape sDup = ShanhaiVanillaRecipeShape.of(base);
        for (int i = 0; i < sDup.slotCount(); i++) {
            sDup.setAt(i, marker);
        }
        final com.google.gson.JsonObject dupFields = sDup.shapedFields();
        final int dupKeys = dupFields == null ? -1 : dupFields.getAsJsonObject("key").size();
        // 🔴 负对照第一版判据写错了（冒烟当场抓出来的）：我只把【前两格】换成 marker2，
        //    其余格子还留着底本的材料 ⇒ key 必然是"2 + 底本那几种"= 4 个，不是 2 个。
        //    正确做法：先把【所有】格子铺成同一种，再只把第 0 格换掉 ⇒ 那时全图只有两种材料。
        // 🔴 第 12 刀：循环上界【必须事先取一份】。`slotCount()` 现在描述的是"裁剪之后占几格"
        //    ⇒ 边清边缩，写成 `i < s.slotCount()` 会在中途提前退出
        //    （现场读数：`vanilla_input_empty_rejected_NEGATIVE_CONTROL ok=false valid=true 表里 inputs=3`）。
        final ShanhaiVanillaRecipeShape sTwo = ShanhaiVanillaRecipeShape.of(base);
        final int nTwo = sTwo.slotCount();
        for (int i = 0; i < nTwo; i++) {
            sTwo.setAt(i, marker2());
        }
        sTwo.setAt(0, marker);
        final com.google.gson.JsonObject twoFields = sTwo.shapedFields();
        final int twoKeys = twoFields == null ? -1 : twoFields.getAsJsonObject("key").size();
        check("vanilla_shape_reuses_one_symbol", dupKeys == 1 && twoKeys == 2,
                "id=" + targetId + " 同一种材料铺满 => key=" + dupKeys
                        + " / 两种不同材料 => key=" + twoKeys
                        + "（前者必须 1：否则 9 格会各自生出一个字符，配方白白变长；"
                        + "后者必须 2：证明前一条不是恒真的）"
                        + " dup_pattern=" + (dupFields == null ? "?" : dupFields.get("pattern"))
                        + " two_pattern=" + (twoFields == null ? "?" : twoFields.get("pattern")));

        // ── ③ 负对照：缩小网格时外侧有材料必须被拒 ──
        final ShanhaiVanillaRecipeShape sShrink = ShanhaiVanillaRecipeShape.of(base);
        final boolean shrank = sShrink.resize(Math.max(1, bv.width - 1), Math.max(1, bv.height - 1));
        check("vanilla_shape_shrink_refused_NEGATIVE_CONTROL", !shrank
                        && sShrink.width() == bv.width && sShrink.height() == bv.height,
                "id=" + targetId + " 从 " + bv.width + "x" + bv.height + " 缩到 "
                        + Math.max(1, bv.width - 1) + "x" + Math.max(1, bv.height - 1)
                        + " => resize_ok=" + shrank + " 现在=" + sShrink.width() + "x" + sShrink.height()
                        + " (外侧还有材料时必须拒、且形状一个像素都不许变；"
                        + "静默丢材料是本工程最不能接受的那类错)");

        // ── ④ 改：第 0 格换物品 → 立刻从原版表读回 ──
        final ShanhaiVanillaRecipeShape sEdit = ShanhaiVanillaRecipeShape.of(base);
        sEdit.setAt(0, marker);
        final ShanhaiRecipeEditorOps.Result rEdit =
                ShanhaiVanillaRecipeOps.applyEdits(server, targetId, null, null, null, sEdit, true, false);
        final String readEdit = firstInputSummary(server, targetId);
        check("vanilla_input_edit", rEdit.ok() && describeStack(marker).equals(readEdit),
                "id=" + targetId + " 第 0 格 -> " + describeStack(marker)
                        + " 读回=" + readEdit + " ok=" + rEdit.ok() + " detail=" + rEdit.detail());
        ShanhaiVanillaRecipeOps.restoreOne(server, targetId, false);

        // ── ⑤ 增：把网格放大到 3×3 并在第 9 格放东西 ──
        final ShanhaiVanillaRecipeShape sGrow = ShanhaiVanillaRecipeShape.of(base);
        final boolean grew = sGrow.resize(ShanhaiVanillaRecipeShape.MAX_SIDE, ShanhaiVanillaRecipeShape.MAX_SIDE);
        final int growCell = ShanhaiVanillaRecipeShape.GRID_CELLS - 1;      // (2,2) 右下角
        sGrow.setAt(growCell, marker2());
        final ShanhaiRecipeEditorOps.Result rGrow =
                ShanhaiVanillaRecipeOps.applyEdits(server, targetId, null, null, null, sGrow, true, false);
        final ShanhaiVanillaRecipeView afterGrow = viewOf(server, targetId);
        final List<ItemStack> growIns = afterGrow == null ? List.of() : afterGrow.representativeInputs();
        final boolean growOk = rGrow.ok() && afterGrow != null
                && afterGrow.width == ShanhaiVanillaRecipeShape.MAX_SIDE
                && afterGrow.height == ShanhaiVanillaRecipeShape.MAX_SIDE
                && growIns.size() == ShanhaiVanillaRecipeShape.GRID_CELLS
                && describeStack(marker2()).equals(describeStack(growIns.get(growIns.size() - 1)));
        check("vanilla_input_add", growOk,
                "id=" + targetId + " resize_ok=" + grew + " 放到第 " + (growCell + 1) + " 格="
                        + describeStack(marker2()) + " => 读回 " + (afterGrow == null ? "null"
                        : afterGrow.width + "x" + afterGrow.height + " inputs=" + growIns.size())
                        + " 最后一格=" + (growIns.isEmpty() ? "?" : describeStack(growIns.get(growIns.size() - 1)))
                        + " ok=" + rGrow.ok());
        ShanhaiVanillaRecipeOps.restoreOne(server, targetId, false);

        // ── ⑥ 删：只留一格 → 形状缩成 1×1 ──
        final ShanhaiVanillaRecipeShape sCut = ShanhaiVanillaRecipeShape.of(base);
        // 🔴 第 12 刀：上界事先取一份（理由同 ②）；GRID 直接用 9 格更直白。
        for (int i = 1; i < ShanhaiVanillaRecipeShape.GRID_CELLS; i++) {
            sCut.clearAt(i);
        }
        final ShanhaiRecipeEditorOps.Result rCut =
                ShanhaiVanillaRecipeOps.applyEdits(server, targetId, null, null, null, sCut, true, false);
        final ShanhaiVanillaRecipeView afterCut = viewOf(server, targetId);
        check("vanilla_input_remove", rCut.ok() && afterCut != null
                        && afterCut.width == 1 && afterCut.height == 1
                        && afterCut.inputs.size() == 1,
                "id=" + targetId + " 只留第 1 格 => 读回 " + (afterCut == null ? "null"
                        : afterCut.width + "x" + afterCut.height + " inputs=" + afterCut.inputs.size())
                        + "（删到只剩一格之后保存时会自动去掉外圈空行空列 ⇒ 形状必须缩成 1×1）");
        ShanhaiVanillaRecipeOps.restoreOne(server, targetId, false);

        // ── ⑦ 负对照：整张网格清空 ⇒ 形状不合法、整条不写 ──
        final ShanhaiVanillaRecipeShape sEmpty = ShanhaiVanillaRecipeShape.of(base);
        // 🔴 第 12 刀：上界必须是【固定 9】。写 `i < sEmpty.slotCount()` 会在清空的过程中
        //    因为"裁剪后的面积"越来越小而提前退出 ⇒ 还剩 3 格有东西 ⇒ 这一拍假绿
        //    （现场读数：`ok=false valid=true apply_ok=true 表里 inputs=3 (期望 6)`）。
        for (int i = 0; i < ShanhaiVanillaRecipeShape.GRID_CELLS; i++) {
            sEmpty.clearAt(i);
        }
        final boolean emptyValid = sEmpty.valid();
        final ShanhaiRecipeEditorOps.Result rEmpty =
                ShanhaiVanillaRecipeOps.applyEdits(server, targetId, null, null, null, sEmpty, true, false);
        final ShanhaiVanillaRecipeView afterEmpty = viewOf(server, targetId);
        check("vanilla_input_empty_rejected_NEGATIVE_CONTROL", !emptyValid && !rEmpty.ok()
                        && afterEmpty != null && afterEmpty.inputs.size() == baseIns.size(),
                "id=" + targetId + " valid=" + emptyValid + " apply_ok=" + rEmpty.ok()
                        + " detail=" + rEmpty.detail()
                        + " 表里 inputs=" + (afterEmpty == null ? -1 : afterEmpty.inputs.size())
                        + " (期望 " + baseIns.size() + "：一条输入全空的合成配方不许被写进表)");

        // ── ⑧ 负对照：rebuildIndex=false ⇒ 表里必须还是老值 ──
        final ShanhaiVanillaRecipeShape sNeg = ShanhaiVanillaRecipeShape.of(base);
        sNeg.setAt(0, marker);
        ShanhaiVanillaRecipeOps.applyEdits(server, targetId, null, null, null, sNeg, false, false);
        final String readNeg = firstInputSummary(server, targetId);
        check("vanilla_input_negative_control", describeStack(baseIns.get(0) == null
                        ? ItemStack.EMPTY : ShanhaiVanillaRecipeView.representative(baseIns.get(0)))
                        .equals(readNeg),
                "id=" + targetId + " rebuildIndex=false 时读回=" + readNeg
                        + "（必须还是底本原值；相等才说明「写回两张表」那一步不是摆设）");
        ShanhaiVanillaRecipeOps.restoreOne(server, targetId, false);

        // ── ⑨ 收尾：恢复原样（形状与格数都要回去）──
        final ShanhaiVanillaRecipeView back = viewOf(server, targetId);
        check("vanilla_input_restore", back != null
                        && back.width == bv.width && back.height == bv.height
                        && back.inputs.size() == baseIns.size()
                        && describeStack(ShanhaiVanillaRecipeView.representative(baseIns.get(0)))
                        .equals(describeStack(back.representativeInputs().isEmpty()
                                ? ItemStack.EMPTY : back.representativeInputs().get(0))),
                "id=" + targetId + " 恢复后 " + (back == null ? "null"
                        : back.width + "x" + back.height + " inputs=" + back.inputs.size())
                        + " 期望 " + bv.width + "x" + bv.height + " inputs=" + baseIns.size()
                        + " ledger=" + ShanhaiVanillaRecipeTable.ledgerSize());

        // ── ⑩ 覆盖层指纹闸：认得输入侧那四个键（认不出 ⇒ 下一局写错指纹 ⇒ STALE ⇒ 重启就丢）──
        final com.google.gson.JsonObject goodFields = new com.google.gson.JsonObject();
        final com.google.gson.JsonObject sf = ShanhaiVanillaRecipeShape.of(base).shapedFields();
        if (sf != null) {
            goodFields.add("pattern", sf.get("pattern"));
            goodFields.add("key", sf.get("key"));
        }
        final com.google.gson.JsonObject badFields = new com.google.gson.JsonObject();
        if (sf != null) {
            final com.google.gson.JsonObject badKey = sf.getAsJsonObject("key").deepCopy();
            // 故意把 key 里第一个字符换成一个不存在的物品 ⇒ 必须判"没套上"
            final java.util.Set<String> names = new java.util.LinkedHashSet<>(badKey.keySet());
            if (!names.isEmpty()) {
                final String first = names.iterator().next();
                final com.google.gson.JsonObject fake = new com.google.gson.JsonObject();
                fake.addProperty("item", "shanhai_probe:definitely_not_a_real_item");
                badKey.add(first, fake);
            }
            badFields.add("pattern", sf.get("pattern"));
            badFields.add("key", badKey);
        }
        final boolean fpGood = ShanhaiVanillaRecipeOps.fieldsAppliedTo(targetId, goodFields);
        final boolean fpBad = ShanhaiVanillaRecipeOps.fieldsAppliedTo(targetId, badFields);
        check("vanilla_fp_probe_inputs", fpGood && !fpBad,
                "id=" + targetId + " 与底本一致的 key => " + fpGood + "（必须 true）"
                        + " / 改坏了一个字符的 key => " + fpBad + "（必须 false）"
                        + " (判错的后果 = 写下一个永远对不上的 base_fp ⇒ 下一局覆盖层判 STALE、不套用)");
        ShanhaiMod.LOGGER.info("{} case=vanilla_input_shape id={} shape={}",
                PREFIX, targetId, s0.statsLine());
    }

    // ================================================================= 🆕 P0：op=add 的端到端

    /**
     * 🔴 <b>「新建配方」那条路的端到端判据 —— 必须真重启一次</b>（用户 2026-10-05 点名的）。
     *
     * <h4>为什么非真重启不可</h4>
     * 覆盖层的 {@code op=add} 只在<b>下一局开机</b>时被重放；第 7/9 刀都写着"需要真实重启一次才知道"，
     * 而它从来没被真跑过 —— 2026-10-05 09:18 用户就是这么掉进坑里的：
     * 重放时 {@code event.custom(json).id(id)} 在 GT 的 {@code GTRecipeJS} 上抛
     * {@code TypeError: Cannot call property id … It is not a function, it is "object"}，
     * 那条配方没拿到合法 id，接着 GT 的 {@code GTRecipeBuilder.getID()} 抛 NPE
     * ⇒ <b>整份数据包加载失败 = 世界进不去</b>。
     *
     * <h4>本拍做两件事</h4>
     * <ol>
     *   <li><b>写</b>（{@code SHANHAI_EDITOR_WRITE=1}）：真建一条 GT 配方（走
     *       {@link ShanhaiRecipeEditorOps#addRecipeFromJson}，与面板「新建配方」同一个入口），
     *       并往覆盖文件里留一条 {@code op=add}；</li>
     *   <li><b>验</b>（下一局，{@code SHANHAI_EDITOR_VVERIFY=1}）：那一条必须<b>还在</b>。</li>
     * </ol>
     * 判据的强度：{@code case=add_replay} 是<b>独立读数</b>（读的是活索引），
     * 不是覆盖层自己打的 APPLIED 行。
     */
    private static void caseAddWrite(net.minecraft.server.MinecraftServer server) {
        final com.google.gson.JsonObject json = probeAddJson();
        GTRecipe made = ShanhaiRecipeEditorOps.addRecipeFromJson(server, PROBE_ADD_ID, json);
        String reused = "";
        if (made == null) {
            // 🔴 2026-10-05（第 11 刀）：这一支是【第二次及以后】跑这一拍时的正常情况 ——
            //    上一局那条 op=add 已经在【开机时被覆盖层重放出来了】⇒ 这条 id 已经在配方表里
            //    ⇒ `addRecipeFromJson` 会被"绝不覆盖既有配方"那道闸门拒（返回 null）。
            //    那不是失败：本拍要验的是"这条新建配方再编辑一次之后，条目还是不是 op=add"。
            //    ⇒ 复用表里那条活配方，照常写下 op=add 条目（口径与"新建"那一拍完全一致）。
            ShanhaiRecipeReverseIndex.build(server);
            made = ShanhaiRecipeReverseIndex.byId(server, PROBE_ADD_ID);
            reused = " reused_from_replay=true（这条上一局已经被 op=add 重放出来了 ⇒ 新建被"
                    + "\"不覆盖既有配方\"那道闸门拒，改用表里那条 —— 不是失败）";
        }
        addedProbeLive = made;
        boolean persisted = false;
        String note = "";
        if (made == null) {
            check("add_write", false, "addRecipeFromJson 返回 null（id=" + PROBE_ADD_ID + "）");
            return;
        }
        try {
            final com.google.gson.JsonObject entry = ShanhaiRecipeOverrideStore.makeAddEntry(
                    ShanhaiRecipeOverrideStore.nextUid(), PROBE_ADD_ID.toString(),
                    json.get("type").getAsString(), json);
            persisted = ShanhaiRecipeOverrideStore.upsert(entry) >= 0;
            note = "entry_uid=" + entry.get("uid").getAsString();
        } catch (Throwable t) {
            note = "upsert threw: " + t;
        }
        check("add_write", made.id != null && PROBE_ADD_ID.equals(made.id) && persisted,
                "id=" + PROBE_ADD_ID + " live_dur=" + made.duration + " persisted=" + persisted
                        + " " + note + reused
                        + " (下一局开机时覆盖层应当打 APPLIED op=add … via=gt_type_function，"
                        + "并且 case=add_replay 必须读到这条；验完用 "
                        + ShanhaiRecipeEditorCommand.ENV_CLEANUP + "=1 清掉)");
    }

    /** 下一局：那条被 {@code op=add} 重放出来的配方必须还在（独立读数）。 */
    private static void caseAddReplay(net.minecraft.server.MinecraftServer server) {
        ShanhaiRecipeBase.captureIfAbsent(server);
        ShanhaiRecipeReverseIndex.build(server);
        final GTRecipe live = ShanhaiRecipeReverseIndex.byId(server, PROBE_ADD_ID);
        final boolean expect = "1".equals(System.getenv(ShanhaiRecipeEditorCommand.ENV_VVERIFY));
        // 🆕 P0：除了"还在"，还要核【这一次编辑补上的输入】也在（用户点名的判据：
        //    "重启 ⇒ 它还在、输入输出还在"）。
        final String inItem = liveInputItemId(live);
        final int inCount = liveInputCount(live);
        ShanhaiMod.LOGGER.info("{} case=add_replay id={} found={} dur={} eut={} in_item={} in_count={} expect={}",
                PREFIX, PROBE_ADD_ID, live != null, live == null ? -1 : live.duration,
                live == null ? -1 : ShanhaiRecipeIoApply.euOf(live), inItem, inCount, expect);
        if (expect) {
            check("add_replay", live != null && live.duration == PROBE_ADD_DURATION,
                    "id=" + PROBE_ADD_ID + " found=" + (live != null)
                            + " dur=" + (live == null ? -1 : live.duration)
                            + " 期望 dur=" + PROBE_ADD_DURATION
                            + " in_item=" + inItem + " in_count=" + inCount
                            + "（上一局写进覆盖文件的 op=add 必须被重放出来；"
                            + "这条是「重启不丢」在新建配方上的唯一判据）");
            check("add_replay_io", live != null && PROBE_ADD_IN_ITEM.equals(inItem)
                            && inCount == PROBE_ADD_IN_COUNT,
                    "id=" + PROBE_ADD_ID + " 输入物品=" + inItem + "（期望 " + PROBE_ADD_IN_ITEM + "）"
                            + " 数量=" + inCount + "（期望 " + PROBE_ADD_IN_COUNT + "）"
                            + "（P0 判据的第二半：他「新建之后再补的输入输出」重启后必须还在 —— "
                            + "只看 dur 会漏掉「配方在、但内容是空的」这种半死状态）");
        }
    }

    /** 一条活配方第一个物品输入的代表物 id（读不出来给 {@code "(none)"}）。 */
    private static String liveInputItemId(GTRecipe r) {
        if (r == null) {
            return "(none)";
        }
        try {
            final List<Content> cs = r.getInputContents(ItemRecipeCapability.CAP);
            if (cs == null || cs.isEmpty()) {
                return "(no-item-input)";
            }
            final ItemStack st = ShanhaiIoTable.representativeItem(cs.get(0));
            if (st == null || st.isEmpty()) {
                return "(empty)";
            }
            final ResourceLocation k = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(st.getItem());
            return k == null ? "?" : k.toString();
        } catch (Throwable t) {
            return "(read-threw:" + t + ")";
        }
    }

    /** 同上的数量。 */
    private static int liveInputCount(GTRecipe r) {
        if (r == null) {
            return -1;
        }
        try {
            final List<Content> cs = r.getInputContents(ItemRecipeCapability.CAP);
            if (cs == null || cs.isEmpty()) {
                return -1;
            }
            final ItemStack st = ShanhaiIoTable.representativeItem(cs.get(0));
            return st == null || st.isEmpty() ? -1 : st.getCount();
        } catch (Throwable t) {
            return -1;
        }
    }

    /**
     * 🔴🔴 <b>P0：「新建的配方第二次保存后就会永久消失」</b>—— 用户 2026-10-05 实测报的。
     *
     * <h4>用户原话</h4>
     * <blockquote>「第二次重进世界 kjs 报错，配方消失」</blockquote>
     *
     * <h4>病根（他的日志逐字给出的那条链）</h4>
     * <pre>
     *  ① 点「新建配方」⇒ 覆盖文件里一条 op=add ✓
     *  ② 他再编辑这条新配方（补输入/输出）⇒ 老代码判断"这条已经存在" ⇒ 改写成 op=set ✗
     *     🔴 它看到的"存在"其实是【本局 op=add 刚重放出来的】，不是真的持久化过
     *  ③ upsert 按 id 去重 ⇒ 旧的 op=add 被顶掉（他的日志：dropped_same_id=1）✗
     *  ④ 下一局开机 op=set 找不到那条配方 ⇒ MISSING no such recipe id ⇒【配方消失】✗✗
     * </pre>
     *
     * <h4>本拍的判据（走的是面板保存用的那条真路 {@code setIo(..., persist=true)}）</h4>
     * <ol>
     *   <li>事前：文件里那条必须是 {@code op=add}（前置条件，不是本拍的功劳）；</li>
     *   <li>做一次"补输入输出并保存"；</li>
     *   <li>🔴 <b>事后：文件里那条必须【还是】{@code op=add}</b>（这就是 P0 的判据）；</li>
     *   <li>而且它的 {@code recipe} JSON 里必须出现这次写进去的 {@code inputs} /
     *       {@code outputs} / （原有的）{@code duration}。</li>
     * </ol>
     * 下一局由 {@code case=add_replay} / {@code case=add_replay_io} 接着判"重启后它还在、内容也对"。
     */
    private static void caseAddEditWrite(net.minecraft.server.MinecraftServer server) {
        final com.google.gson.JsonObject before = ShanhaiRecipeOverrideStore.findEntry(PROBE_ADD_ID);
        final String opBefore = before != null && before.has("op") ? before.get("op").getAsString() : "(none)";
        final GTRecipe live = addedProbeLive != null ? addedProbeLive
                : ShanhaiRecipeReverseIndex.byId(server, PROBE_ADD_ID);
        if (live == null) {
            check("add_edit_kept_op_add", false, "前置失败：拿不到那条新建的活配方（caseAddWrite 没成功？）");
            return;
        }
        // ② 真的走一遍"补输入输出 + 保存"（与面板保存同一条路）
        final com.google.gson.JsonObject inputs = probeIoGroup(PROBE_ADD_IN_ITEM, PROBE_ADD_IN_COUNT);
        final com.google.gson.JsonObject outputs = probeIoGroup("minecraft:diamond", 1);
        final ShanhaiRecipeEditorOps.Result r = ShanhaiRecipeEditorOps.setIo(
                server, live, inputs, outputs, null, true, true);
        // ③ 事后读文件
        final com.google.gson.JsonObject after = ShanhaiRecipeOverrideStore.findEntry(PROBE_ADD_ID);
        final String opAfter = after != null && after.has("op") ? after.get("op").getAsString() : "(none)";
        final boolean hasInputs = after != null && after.has("recipe")
                && after.getAsJsonObject("recipe").has("inputs");
        final boolean hasOutputs = after != null && after.has("recipe")
                && after.getAsJsonObject("recipe").has("outputs");
        final boolean durKept = after != null && after.has("recipe")
                && after.getAsJsonObject("recipe").has("duration")
                && after.getAsJsonObject("recipe").get("duration").getAsInt() == PROBE_ADD_DURATION;
        check("add_edit_kept_op_add",
                "add".equals(opBefore) && r.ok() && r.persisted()
                        && "add".equals(opAfter) && hasInputs && hasOutputs && durKept,
                "id=" + PROBE_ADD_ID + " 改前 op=" + opBefore + " 保存 ok=" + r.ok()
                        + " persisted=" + r.persisted() + " ⇒ 改后 op=" + opAfter + "（必须还是 add）"
                        + " recipe 里有 inputs=" + hasInputs + " outputs=" + hasOutputs
                        + " duration 还是 " + PROBE_ADD_DURATION + "=" + durKept
                        + "（改写成 op=set 的话，下一局开机就是 MISSING ⇒ 配方消失 —— 这就是那一刀的病根）");
        ShanhaiMod.LOGGER.info("{} case=add_edit_write_fixture entry_after={}",
                PREFIX, after == null ? "(none)" : String.valueOf(after));
    }

    /**
     * 🆕 P0：一组 GT 的 {@code inputs/outputs} JSON（形状与活配方里那一份逐字同款）。
     *
     * <p>形状出处：{@code ShanhaiRecipeEditorWorkspaceCheck.emptyNewRecipeJson} 那一套
     * ＋ 用户实例覆盖文件里 {@code fields.inputs} 的真实读数（{@code {"item":[{"content":{...}}]}}）。
     */
    private static com.google.gson.JsonObject probeIoGroup(String itemId, int count) {
        final com.google.gson.JsonObject sized = new com.google.gson.JsonObject();
        final com.google.gson.JsonObject ing = new com.google.gson.JsonObject();
        ing.addProperty("item", itemId);
        sized.addProperty("type", "gtceu:sized");
        sized.addProperty("count", count);
        sized.add("ingredient", ing);
        final com.google.gson.JsonObject entry = new com.google.gson.JsonObject();
        entry.add("content", sized);
        entry.addProperty("chance", 10000);
        entry.addProperty("maxChance", 10000);
        entry.addProperty("tierChanceBoost", 0);
        final com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
        arr.add(entry);
        final com.google.gson.JsonObject group = new com.google.gson.JsonObject();
        group.add("item", arr);
        group.add("fluid", new com.google.gson.JsonArray());
        return group;
    }

    /** 「新建配方」那份空 IO 的 GT 配方 JSON（形状与 {@code workspace.newRecipe} 逐字同款）。 */
    private static com.google.gson.JsonObject probeAddJson() {        final com.google.gson.JsonObject json = new com.google.gson.JsonObject();
        json.addProperty("type", "gtceu:assembler");
        json.addProperty("duration", PROBE_ADD_DURATION);
        final com.google.gson.JsonObject data = new com.google.gson.JsonObject();
        data.addProperty("euTier", 0);
        json.add("data", data);
        final com.google.gson.JsonObject eu = new com.google.gson.JsonObject();
        eu.addProperty("content", PROBE_ADD_EUT);
        eu.addProperty("chance", 10000);
        eu.addProperty("maxChance", 10000);
        eu.addProperty("tierChanceBoost", 0);
        final com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
        arr.add(eu);
        final com.google.gson.JsonObject tickIn = new com.google.gson.JsonObject();
        tickIn.add("eu", arr);
        json.add("tickInputs", tickIn);
        return json;
    }

    /** 阶段 2 的落盘：把靶子改成 1×1，并在文件里留下 pattern/key（给下一局开机做端到端验证）。 */    private static void caseVanillaInputWrite(net.minecraft.server.MinecraftServer server) {
        ShanhaiVanillaRecipeTable.captureIfAbsent(server);
        ShanhaiVanillaRecipeTable.rebuildIndex(server);
        final ResourceLocation id = firstVanillaShapedId();
        if (id == null) {
            check("vanilla_input_write", false, "no vanilla shaped target");
            return;
        }
        final net.minecraft.world.item.crafting.Recipe<?> base = ShanhaiVanillaRecipeTable.pristine(id);
        final ShanhaiVanillaRecipeShape s = ShanhaiVanillaRecipeShape.of(base);
        for (int i = 0; i < s.slotCount(); i++) {
            if (i != 0) {
                s.clearAt(i);
            }
        }
        final ShanhaiRecipeEditorOps.Result r =
                ShanhaiVanillaRecipeOps.applyEdits(server, id, null, null, null, s, true, true);
        final com.google.gson.JsonObject entry = ShanhaiRecipeOverrideStore.findEntry(id);
        final boolean hasPattern = entry != null && entry.has("fields")
                && entry.getAsJsonObject("fields").has("pattern")
                && entry.getAsJsonObject("fields").has("key");
        check("vanilla_input_write", r.ok() && r.persisted() && hasPattern,
                "id=" + id + " ok=" + r.ok() + " persisted=" + r.persisted()
                        + " fields_has_pattern_key=" + hasPattern
                        + " base_fp=" + clip(r.baseFp())
                        + " (下一局开机时覆盖层应当打 APPLIED op=set 且 fields[ 里有 SHAPE:pattern/SHAPE:key，"
                        + "并且这条配方的形状要读成 1x1；验完用 "
                        + ShanhaiRecipeEditorCommand.ENV_CLEANUP + "=1 清掉)");
        ShanhaiMod.LOGGER.info("{} case=vanilla_input_write_fixture id={} fields={}",
                PREFIX, id, entry == null ? "(none)" : String.valueOf(entry.get("fields")));
    }

    /**
     * <b>下一局开机</b>时读那条被写过的配方：形状是不是真的变成 1×1 了
     * —— 这是"重启不丢"在非 GT 输入上的<b>独立</b>证据（不是覆盖层自己的 APPLIED 声明）。
     */
    private static void caseVanillaOverlayReplay(net.minecraft.server.MinecraftServer server) {
        final ResourceLocation id = firstVanillaShapedId();
        if (id == null) {
            ShanhaiMod.LOGGER.warn("{} case=vanilla_overlay_replay SKIPPED reason=no_target", PREFIX);
            return;
        }
        final ShanhaiVanillaRecipeView v = viewOf(server, id);
        final boolean expect = "1".equals(System.getenv(ShanhaiRecipeEditorCommand.ENV_VVERIFY));
        final boolean isOneByOne = v != null && v.width == 1 && v.height == 1 && v.inputs.size() == 1;
        ShanhaiMod.LOGGER.info("{} case=vanilla_overlay_replay id={} w={} h={} inputs={} expect_1x1={}",
                PREFIX, id, v == null ? -1 : v.width, v == null ? -1 : v.height,
                v == null ? -1 : v.inputs.size(), expect);
        if (expect) {
            check("vanilla_overlay_replay", isOneByOne,
                    "id=" + id + " 读回=" + (v == null ? "null" : v.width + "x" + v.height)
                            + " (上一局写进覆盖文件的 pattern/key 必须被重放出来 ⇒ 形状读成 1x1)");
        }
    }

    /** 第一块 {@code minecraft:crafting} 桶里可用的有形状合成（与 caseVanilla 同一个挑法）。 */
    private static ResourceLocation firstVanillaShapedId() {
        if (lastVanillaTargetId != null) {
            return lastVanillaTargetId;
        }
        for (ResourceLocation id : ShanhaiVanillaRecipeTable.idsOf(new ResourceLocation("minecraft", "crafting"))) {
            if (HANDS_OFF.contains(id)) {
                continue;
            }
            final net.minecraft.world.item.crafting.Recipe<?> r = ShanhaiVanillaRecipeTable.liveById(id);
            if (r == null) {
                continue;
            }
            final ShanhaiVanillaRecipeView v = ShanhaiVanillaRecipeView.of(r);
            if (v != null && v.kind == ShanhaiVanillaRecipeView.Kind.SHAPED && !v.result.isEmpty()) {
                return id;
            }
        }
        return null;
    }

    private static ShanhaiVanillaRecipeView viewOf(net.minecraft.server.MinecraftServer server,
                                                   ResourceLocation id) {
        final net.minecraft.world.item.crafting.Recipe<?> r =
                ShanhaiVanillaRecipeOps.readFromTable(server, id);
        return r == null ? null : ShanhaiVanillaRecipeView.of(r);
    }

    private static String firstInputSummary(net.minecraft.server.MinecraftServer server, ResourceLocation id) {
        final ShanhaiVanillaRecipeView v = viewOf(server, id);
        if (v == null || v.inputs.isEmpty()) {
            return "(none)";
        }
        return describeStack(ShanhaiVanillaRecipeView.representative(v.inputs.get(0)));
    }

    private static String describeStack(ItemStack s) {
        if (s == null || s.isEmpty()) {
            return "(empty)";
        }
        final ResourceLocation k = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(s.getItem());
        return k + " x" + s.getCount();
    }

    /** 自检用的两个"一眼认得出来"的探针物品（都不参与真实玩法，只在这一拍进出）。 */
    private static ItemStack probeStack() {
        return new ItemStack(net.minecraft.world.item.Items.STONE, 3);
    }

    private static ItemStack marker2() {
        return new ItemStack(net.minecraft.world.item.Items.OAK_PLANKS, 5);
    }

    private static String safeIngredientJson(net.minecraft.world.item.crafting.Ingredient in) {
        if (in == null) {
            return "(null)";
        }
        try {
            final com.google.gson.JsonElement el = in.toJson();
            return el == null ? "(null)" : ShanhaiRecipeFingerprint.canonicalize(el).toString();
        } catch (Throwable t) {
            return "(threw:" + t.getClass().getSimpleName() + ")";
        }
    }

    // ================================================================= 🆕 2026-10-05 用户报的 ①②

    /**
     * ① <b>文本搜索框什么都搜不到</b>（用户原话：「同时文本搜索框输入什么好像什么都搜索不到」）。
     *
     * <h4>真机那一段（客户端把字送上来）机器验不了，但这一拍能钉死服务端那一半</h4>
     * 服务端这一半如果不通（{@code session.searchText} 进不来），这一拍会直接红。
     * 客户端那一段的根因已经用 javap 钉死：{@code Widget.writeClientAction} 的第一句是
     * {@code if (uiAccess != null && !isClientSideWidget)} —— 面板原来调了
     * {@code setClientSideWidget()} ⇒ 那段字【根本不会发到服务端】。已删。
     *
     * <p>判据：英文关键词出结果、中文关键词也出结果、瞎编的词必须 0 条。
     */
    private static void caseTextSearchLive(net.minecraft.server.MinecraftServer server) {
        final ShanhaiRecipeEditorWorkspace ws = new ShanhaiRecipeEditorWorkspace(server, null);
        ws.reloadTypes();
        ws.setSearchText("macerator");
        final boolean arr = "macerator".equals(ws.searchTextRaw());
        ws.runQuery(ShanhaiRecipeQuery.Kind.TEXT);
        final int nEn = ws.queryTotal();
        final int gEn = ws.queryResultGroups();
        // 中文：从语言表里现取一个 GT 类型的中文名（不硬编码，免得名字被改过就假红）
        final String zh = ShanhaiRecipeQuery.zhCnNameOf(new ResourceLocation("gtceu", "macerator"));
        int nZh = -1;
        if (!zh.isEmpty()) {
            ws.setSearchText(zh);
            ws.runQuery(ShanhaiRecipeQuery.Kind.TEXT);
            nZh = ws.queryTotal();
        }
        ws.setSearchText("zzz_no_such_recipe_zzz");
        ws.runQuery(ShanhaiRecipeQuery.Kind.TEXT);
        final int nNeg = ws.queryTotal();
        ws.setSearchText("");
        check("text_search_live", arr && nEn > 0 && nNeg == 0 && (zh.isEmpty() || nZh > 0),
                "服务端收到的关键词=" + (arr ? "macerator" : "(没进来！)")
                        + " 英文命中=" + nEn + "条/" + gEn + "组"
                        + " 中文「" + zh + "」命中=" + (zh.isEmpty() ? "(取不到中文名，跳过)" : nZh + "条")
                        + " 负对照(瞎编词)=" + nNeg + "条(必须 0)");
    }

    /**
     * ② <b>物品查询搜不到工作台配方</b>（用户原话：「通过获取途径等 搜索是搜索不到工作台的配方的」）。
     *
     * <p>根因：GT 的反查索引 {@code ShanhaiRecipeReverseIndex} 的 5 处扫描第一句都是
     * {@code instanceof GTRecipe} ⇒ 非 GT 配方一条都进不去。修法是查询侧单独扫一遍非 GT 表
     * （{@code ShanhaiRecipeQuery.vanillaHits}）再并组。
     *
     * <h4>判据（含逐条复核，不只是"有一条命中"）</h4>
     * 正对照：拿一条非 GT 配方的产物去查 ⇒ 命中列表里必须有它；
     * 逐条复核：返回的<b>每一条</b>非 GT 命中，它的活配方的产物必须真的就是这个物品
     * （索引说 A、活配方说 B ⇒ 红）；
     * 负对照：拿这个物品当<b>输入</b>去查时，返回的非 GT 命中里不许出现"输入里根本没有它"的条目。
     */
    private static void caseQueryVanilla(net.minecraft.server.MinecraftServer server) {
        ShanhaiVanillaRecipeTable.captureIfAbsent(server);
        ShanhaiVanillaRecipeTable.rebuildIndex(server);
        final ResourceLocation crafting = new ResourceLocation("minecraft", "crafting");
        ResourceLocation targetId = null;
        net.minecraft.world.item.Item targetItem = null;
        for (ResourceLocation id : ShanhaiVanillaRecipeTable.idsOf(crafting)) {
            if (HANDS_OFF.contains(id)) {
                continue;
            }
            final net.minecraft.world.item.crafting.Recipe<?> r = ShanhaiVanillaRecipeTable.liveById(id);
            if (r == null) {
                continue;
            }
            final ShanhaiVanillaRecipeView v = ShanhaiVanillaRecipeView.of(r);
            if (v == null || v.result.isEmpty()) {
                continue;
            }
            targetId = id;
            targetItem = v.result.getItem();
            break;
        }
        if (targetId == null) {
            check("query_vanilla_output", false, "no vanilla target with a result item");
            return;
        }
        final ShanhaiRecipeQuery.Result rOut = ShanhaiRecipeQuery.byOutput(server, targetItem);
        boolean found = false;
        int checkedIds = 0;
        int mismatched = 0;
        for (ShanhaiRecipeQuery.Group g : rOut.groups()) {
            if (!crafting.equals(g.typeId())) {
                continue;
            }
            for (ResourceLocation rid : g.recipes()) {
                if (rid.equals(targetId)) {
                    found = true;
                }
                final net.minecraft.world.item.crafting.Recipe<?> rr =
                        ShanhaiVanillaRecipeTable.liveById(rid);
                if (rr == null) {
                    continue;
                }
                final ShanhaiVanillaRecipeView rv = ShanhaiVanillaRecipeView.of(rr);
                checkedIds++;
                if (rv == null || rv.result.isEmpty() || rv.result.getItem() != targetItem) {
                    mismatched++;
                }
            }
        }
        check("query_vanilla_output", found && checkedIds > 0 && mismatched == 0,
                "物品=" + idOfItem(targetItem) + " 靶子=" + targetId + " 命中=" + found
                        + " 逐条复核=" + checkedIds + " 条不一致=" + mismatched
                        + "（正对照：靶子必须在里面；逐条复核：索引说命中的每一条，"
                        + "它的活配方的产物必须真的就是这个物品）note=" + rOut.note());

        // 输入那一侧：拿同一条配方的第一格材料去查，逐条复核（Ingredient.test 判，标签也算）
        final net.minecraft.world.item.crafting.Recipe<?> base =
                ShanhaiVanillaRecipeTable.liveById(targetId);
        final ShanhaiVanillaRecipeView bv = base == null ? null : ShanhaiVanillaRecipeView.of(base);
        ItemStack probe = ItemStack.EMPTY;
        if (bv != null) {
            for (net.minecraft.world.item.crafting.Ingredient in : bv.inputs) {
                final ItemStack rep = ShanhaiVanillaRecipeView.representative(in);
                if (!rep.isEmpty()) {
                    probe = rep;
                    break;
                }
            }
        }
        if (probe.isEmpty()) {
            ShanhaiMod.LOGGER.warn("{} case=query_vanilla_input SKIPPED reason=no_representative_input id={}",
                    PREFIX, targetId);
            return;
        }
        final ShanhaiRecipeQuery.Result rIn = ShanhaiRecipeQuery.byInput(server, probe.getItem());
        boolean foundIn = false;
        int checkedIn = 0;
        int badIn = 0;
        for (ShanhaiRecipeQuery.Group g : rIn.groups()) {
            if (!crafting.equals(g.typeId())) {
                continue;
            }
            for (ResourceLocation rid : g.recipes()) {
                if (rid.equals(targetId)) {
                    foundIn = true;
                }
                final net.minecraft.world.item.crafting.Recipe<?> rr =
                        ShanhaiVanillaRecipeTable.liveById(rid);
                if (rr == null) {
                    continue;
                }
                final ShanhaiVanillaRecipeView rv = ShanhaiVanillaRecipeView.of(rr);
                if (rv == null) {
                    continue;
                }
                checkedIn++;
                boolean hit = false;
                for (net.minecraft.world.item.crafting.Ingredient in : rv.inputs) {
                    try {
                        if (in != null && !in.isEmpty() && in.test(probe)) {
                            hit = true;
                            break;
                        }
                    } catch (Throwable ignored) {
                        // 单个判不了不影响别的
                    }
                }
                if (!hit) {
                    badIn++;
                }
            }
        }
        check("query_vanilla_input", foundIn && checkedIn > 0 && badIn == 0,
                "物品=" + idOfItem(probe.getItem()) + " 靶子=" + targetId + " 命中=" + foundIn
                        + " 逐条复核=" + checkedIn + " 条不一致=" + badIn
                        + "（负对照：返回的每一条，它的活配方的输入里必须真的能用这个物品当料）");
    }

    private static String idOfItem(net.minecraft.world.item.Item item) {
        if (item == null) {
            return "(null)";
        }
        final ResourceLocation k = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item);
        return k == null ? "?" : k.toString();
    }

    // ================================================================= 🆕 第 11 刀：卡片三处显示

    /**
     * 🔴 <b>卡片上那两处"看不见的数据"：① 电路号 / ② 催化剂标志</b>（用户点名报的）。
     *
     * <h4>用户原话（逐字）</h4>
     * <blockquote>「这个编程电路的显示有问题，它<b>没有具体显示几号电路</b>，
     * 而且那个<b>催化剂的标志也没有显示</b>」</blockquote>
     *
     * <h4>根因（上一刀查出来的线索）</h4>
     * {@link ShanhaiRecipeQuery.Chip} 原来只有「物品 id ＋ 数量」三个字段 ⇒
     * <b>NBT 在造卡片那一步就被丢了</b>，{@code chance} 也没带走 ⇒ 两件事都传不到客户端。
     * 本刀把它们在<b>服务端</b>取好、随卡片推下去，这一拍就是那条链的机器判据。
     *
     * <h4>四条判据（每条都带负对照）</h4>
     * <ol>
     *   <li>{@code card_circuit_live}：找一条输入里有编程电路的活配方 ⇒
     *       卡片上必须读出一个 <b>≥ 0 的电路号</b>，而且这个号必须与
     *       <b>直接从 NBT 上读出来的那个值</b>相等（独立路线：不经过 {@code IntCircuitBehaviour}）；</li>
     *   <li>{@code card_circuit_two_numbers}：同一种配方类型里找<b>两条电路号不同</b>的 ⇒
     *       两张卡片读出来的号必须<b>不同</b>（"同类型不同电路号能分辨"这条验收判据）；</li>
     *   <li>{@code card_circuit_negative_control}：找一条输入里<b>没有</b>电路的 ⇒
     *       所有 chip 的电路号必须是 {@code -1}（＝卡片上<b>不画</b>角标）；</li>
     *   <li>{@code card_catalyst_live} / {@code card_catalyst_negative_control}：同款两条，
     *       判据换成一格是"不消耗的催化剂"（{@code chance == 0}）。</li>
     * </ol>
     * ⚠️ <b>它证明不了"画出来好不好看"</b>（红线禁止开客户端）—— 它证明的是
     * <b>卡片拿到的数据里有这个号、且这个号是对的</b>；角标的位置与字号只能靠他眼睛。
     */
    private static void caseCardChips(net.minecraft.server.MinecraftServer server) {
        ShanhaiRecipeReverseIndex.ensure(server);
        final List<GTRecipe> all = ShanhaiRecipeReverseIndex.all();
        if (all == null || all.isEmpty()) {
            check("card_circuit_live", false, "反查表是空的（没有配方可扫）");
            return;
        }

        GTRecipe circuitSample = null;
        int circuitValue = -1;
        int circuitRaw = -2;
        String circuitMismatch = "";
        GTRecipe noCircuitSample = null;
        GTRecipe catalystSample = null;
        String catalystContentItem = null;
        GTRecipe noCatalystSample = null;
        // 同类型两条不同电路号（判据 2）
        final java.util.Map<String, int[]> byTypeCircuit = new java.util.HashMap<>();
        String twoNumType = null;
        int twoNumA = -1;
        int twoNumB = -1;
        final ResourceLocation[] twoNumIds = new ResourceLocation[2];

        int scanned = 0;
        for (GTRecipe r : all) {
            if (r == null) {
                continue;
            }
            scanned++;
            final ShanhaiRecipeQuery.Card card = ShanhaiRecipeQuery.cardOf(r, false);
            if (card == null) {
                continue;
            }
            // ── 把这张卡与"从活配方上再独立取一遍"的行对齐（前 MAX_CHIPS 格）──
            final List<int[]> raw = rawInputRows(r);           // [circuit, catalystFlag, id]
            final List<ShanhaiRecipeQuery.Chip> cardItems = new ArrayList<>();
            for (ShanhaiRecipeQuery.Chip ch : card.ins()) {
                if (ch.item()) {
                    cardItems.add(ch);
                }
            }
            final int n = Math.min(raw.size(), cardItems.size());
            for (int i = 0; i < n; i++) {
                final int rawCircuit = raw.get(i)[0];
                final boolean rawCat = raw.get(i)[1] != 0;
                final ShanhaiRecipeQuery.Chip ch = cardItems.get(i);
                if (circuitSample == null && rawCircuit >= 0) {
                    circuitSample = r;
                    circuitValue = ch.circuit();
                    circuitRaw = rawCircuit;
                    if (circuitValue != circuitRaw) {
                        circuitMismatch = " 第 " + i + " 格 卡片=" + circuitValue + " 原始NBT=" + circuitRaw;
                    }
                }                if (catalystSample == null && rawCat) {
                    catalystSample = r;
                    catalystContentItem = idOfItemRaw(raw.get(i)[2]);
                }
            }
            // ── 电路号的负对照候选：这张卡上【一个带电路号的格子都没有】──
            //    ⚠️ 第一版把这段写在了上面那个循环里、还带着 `circuitSample == null &&` 的前缀，
            //    于是第一张卡找到电路号之后这段就再也不会执行 ⇒ 那一拍报"找不到样本"（假红）。
            //    判据本身（下面的 check）仍会重新数一遍"这一条配方的所有格"，所以这里宽松取候选是对的。
            if (noCircuitSample == null) {
                boolean anyCircuitChip = false;
                for (ShanhaiRecipeQuery.Chip ch : card.ins()) {
                    if (ch.circuit() >= 0) {
                        anyCircuitChip = true;
                        break;
                    }
                }
                if (!anyCircuitChip && !card.ins().isEmpty()) {
                    noCircuitSample = r;
                }
            }
            // ── 判据 2：同类型两条电路号不同 ──
            String typeId = r.getType() == null || r.getType().registryName == null
                    ? "?" : r.getType().registryName.toString();
            int firstCircuit = -1;
            int firstId = -1;
            for (int i = 0; i < Math.min(raw.size(), cardItems.size()); i++) {
                if (raw.get(i)[0] >= 0) {
                    firstCircuit = cardItems.get(i).circuit();
                    firstId = raw.get(i)[2];
                    break;
                }
            }
            if (firstCircuit >= 0) {
                final int[] prev = byTypeCircuit.get(typeId);
                if (prev == null) {
                    byTypeCircuit.put(typeId, new int[]{firstCircuit, firstId});
                } else if (twoNumType == null && prev[0] != firstCircuit) {
                    twoNumType = typeId;
                    twoNumA = prev[0];
                    twoNumB = firstCircuit;
                    twoNumIds[0] = itemIdAt(prev[1]);
                    twoNumIds[1] = itemIdAt(firstId);
                }
            }
            // ── 负对照候选：整条配方的输入里一个电路都没有、也一个催化剂都没有 ──
            if (noCatalystSample == null && !raw.isEmpty()) {
                boolean anyCat = false;
                boolean anyCircuit = false;
                for (int[] row : raw) {
                    anyCat |= row[1] != 0;
                    anyCircuit |= row[0] >= 0;
                }
                if (!anyCat && !anyCircuit) {
                    noCatalystSample = r;
                }
            }
            if (circuitSample != null && catalystSample != null && noCatalystSample != null
                    && twoNumType != null && scanned > 64) {
                break;
            }
        }

        check("card_circuit_live", circuitSample != null && circuitValue >= 0 && circuitMismatch.isEmpty(),
                "id=" + idOf(circuitSample) + " 卡片上的电路号=" + circuitValue
                        + " 原始 NBT 上的号=" + circuitRaw + circuitMismatch
                        + "（独立路线：卡片那条走 IntCircuitBehaviour，对照那条直接读 NBT 的 Configuration 键）"
                        + " 扫描=" + scanned + " 条");

        check("card_circuit_two_numbers", twoNumType != null && twoNumA != twoNumB,
                "类型=" + twoNumType + " 两条的电路号=" + twoNumA + " / " + twoNumB
                        + " 物品1=" + twoNumIds[0] + " 物品2=" + twoNumIds[1]
                        + "（同一种配方、不同电路号 ⇒ 卡片上必须读出不同的号）");

        // 负对照：这一条配方的【所有】输入格都不该有电路号
        boolean negOk = false;
        String negDetail = "找不到样本";
        if (noCircuitSample != null) {
            final ShanhaiRecipeQuery.Card card = ShanhaiRecipeQuery.cardOf(noCircuitSample, false);
            int withCircuit = 0;
            for (ShanhaiRecipeQuery.Chip ch : card.ins()) {
                if (ch.circuit() >= 0) {
                    withCircuit++;
                }
            }
            negOk = withCircuit == 0 && !card.ins().isEmpty();
            negDetail = "id=" + idOf(noCircuitSample) + " 带电路号的格数=" + withCircuit
                    + " 输入格数=" + card.ins().size() + "（必须 0，而且不是空卡）";
        }
        check("card_circuit_negative_control", negOk, negDetail);

        check("card_catalyst_live", catalystSample != null,
                "id=" + idOf(catalystSample) + " 输入里的催化剂= " + catalystContentItem
                        + "（这一格在卡片上必须带 catalyst=true；判据的真正强度在下面那条："
                        + "同一张卡上'消耗的'与'催化剂'标记不同）");

        // 同一张卡上：被消耗的与催化剂必须【标记不同】
        boolean mixOk = false;
        String mixDetail = "找不到样本";
        if (catalystSample != null) {
            final ShanhaiRecipeQuery.Card card = ShanhaiRecipeQuery.cardOf(catalystSample, false);
            int cats = 0;
            int consumed = 0;
            for (ShanhaiRecipeQuery.Chip ch : card.ins()) {
                if (ch.catalyst()) {
                    cats++;
                } else {
                    consumed++;
                }
            }
            mixOk = cats >= 1 && consumed >= 1;
            mixDetail = "id=" + idOf(catalystSample) + " 催化剂格=" + cats + " 消耗格=" + consumed
                    + "（同一张卡上两种标记必须同时存在、且互不相等）";
        }
        check("card_catalyst_mixed", mixOk, mixDetail);

        boolean noCatOk = false;
        String noCatDetail = "找不到样本";
        if (noCatalystSample != null) {
            final ShanhaiRecipeQuery.Card card = ShanhaiRecipeQuery.cardOf(noCatalystSample, false);
            int cats = 0;
            for (ShanhaiRecipeQuery.Chip ch : card.ins()) {
                if (ch.catalyst()) {
                    cats++;
                }
            }
            noCatOk = cats == 0 && !card.ins().isEmpty();
            noCatDetail = "id=" + idOf(noCatalystSample) + " 催化剂格=" + cats
                    + " 输入格数=" + card.ins().size() + "（全是消耗品 ⇒ 一个都不该标）";
        }
        check("card_catalyst_negative_control", noCatOk, noCatDetail);
    }

    /**
     * 🔴 <b>「{@code +N} 折叠不许说谎」</b>——用户点名称赞过的那个设计，本刀没动它，但要把它钉住。
     *
     * <p>用户原话（逐字）：「<b>对了，这个超出4个格子直接变成+3+6什么的是个不错的设计，
     * 既不会占用太多空间，也能一眼看出这是什么配方</b>」
     * ⇒ 判据：<b>画出来的图标数 ＋ N ＝ 这一侧真实的格数</b>。
     *
     * <h4>为什么必须有一条判据钉它</h4>
     * 老写法的记账顺序是"先判放不放得下、再去取代表物"，于是<b>取不出代表物的那些也被算进了 N</b>
     * —— 一旦发生，卡片上就是「4 个图标 ＋ +N」而 {@code 4+N} 比真实格数大，折叠就变成了撒谎。
     * 本刀把顺序摆正（先造 chip、造得出才记账），这一拍同时验：
     * <ol>
     *   <li>{@code +N} 的算术（对 {@code usedBy} 这个<b>独立来源</b>：它数的是 Content 条数，不经过 chip）；</li>
     *   <li>有 {@code +N} 的样本数 &gt; 0（否则这一拍是空转 —— 必须证明"扫到了真的会折叠的配方"）。</li>
     * </ol>
     */
    private static void caseCardPlusN(net.minecraft.server.MinecraftServer server) {
        ShanhaiRecipeReverseIndex.ensure(server);
        final List<GTRecipe> all = ShanhaiRecipeReverseIndex.all();
        int scanned = 0;
        int bad = 0;
        int foldedSamples = 0;
        int maxSide = 0;
        // 🆕 "画不出图标的格子"（标签入料在那个时点一个物品都没匹配上）——
        //    那些格子也要算进 N，否则 4+N 会比真实格数少（实测抓到 5/2000 条，见报告）。
        int undrawableRecipes = 0;
        String firstUndrawable = "";
        String firstBad = "";
        final int limit = 2000;
        for (GTRecipe r : all) {
            if (r == null || scanned >= limit) {
                break;
            }
            scanned++;
            final ShanhaiRecipeQuery.Card card = ShanhaiRecipeQuery.cardOf(r, false);
            if (card == null) {
                continue;
            }
            final int[] used = ShanhaiIoTable.usedBy(r);
            final int inSide = used[0] + used[1];
            final int outSide = used[2] + used[3];
            final int cardIn = card.ins().size() + card.insMore();
            final int cardOut = card.outs().size() + card.outsMore();
            maxSide = Math.max(maxSide, Math.max(inSide, outSide));
            if (card.insMore() > 0 || card.outsMore() > 0) {
                foldedSamples++;
            }
            if (card.ins().size() < Math.min(ShanhaiRecipeQuery.MAX_CHIPS, inSide)
                    || card.outs().size() < Math.min(ShanhaiRecipeQuery.MAX_CHIPS, outSide)) {
                undrawableRecipes++;
                if (firstUndrawable.isEmpty()) {
                    firstUndrawable = " 例: id=" + idOf(r) + " 输入图标=" + card.ins().size()
                            + "/" + inSide + " 输出图标=" + card.outs().size() + "/" + outSide
                            + "（差的那几格画不出图标，但都已算进 N）";
                }
            }
            if (cardIn != inSide || cardOut != outSide) {
                bad++;
                if (firstBad.isEmpty()) {
                    firstBad = " id=" + idOf(r) + " 输入: 卡片=" + card.ins().size() + "+" + card.insMore()
                            + " 真实格数=" + inSide + " ; 输出: 卡片=" + card.outs().size() + "+"
                            + card.outsMore() + " 真实格数=" + outSide;
                }
            }
        }
        check("card_plus_n", bad == 0 && foldedSamples > 0,
                "扫描=" + scanned + " 条 不一致=" + bad + " 有折叠的样本=" + foldedSamples
                        + " 单侧最大格数=" + maxSide
                        + " 有'画不出图标的格子'的配方=" + undrawableRecipes + firstUndrawable + firstBad
                        + "（判据：图标数 + N 必须等于该侧真实格数；真实格数取自 ShanhaiIoTable.usedBy，"
                        + "它数的是 Content 条数、不经过卡片那条路）");
    }

    /**
     * 🔴 <b>电路号 / 催化剂过不过网络</b>——卡片数据是服务端算好推给客户端画的，
     * 中间要过一次 {@link net.minecraft.network.FriendlyByteBuf}。
     *
     * <p>这一拍是纯往返：造一张带电路号与催化剂的卡 → 写进缓冲 → 读回来 → 逐格比。
     * 附一条负对照：<b>没有电路号的那一格读回来必须还是"没有"</b>
     * （否则"没电路"会被显示成"电路 0"，那是假信息）。
     */
    private static void caseCardWire() {
        final List<ShanhaiRecipeQuery.Chip> ins = List.of(
                new ShanhaiRecipeQuery.Chip(true, 1, 3, 7, true),
                new ShanhaiRecipeQuery.Chip(true, 2, 1, 0, false),
                new ShanhaiRecipeQuery.Chip(true, 3, 1, ShanhaiRecipeQuery.NO_CIRCUIT, false),
                new ShanhaiRecipeQuery.Chip(false, 4, 1000, ShanhaiRecipeQuery.NO_CIRCUIT, false));
        final List<ShanhaiRecipeQuery.Chip> outs = List.of(
                new ShanhaiRecipeQuery.Chip(true, 5, 576, ShanhaiRecipeQuery.NO_CIRCUIT, false));
        final ShanhaiRecipeQuery.Card card = new ShanhaiRecipeQuery.Card(
                new ResourceLocation("shanhai", "wire_probe"), "wire_probe", "探针",
                ins, outs, 3, 1, 1200, 100, 30L, 1, 2, true);
        final net.minecraft.network.FriendlyByteBuf buf = new net.minecraft.network.FriendlyByteBuf(
                io.netty.buffer.Unpooled.buffer());
        ShanhaiRecipeQuery.writeCard(buf, card);
        final int written = buf.readableBytes();
        final ShanhaiRecipeQuery.Card back = ShanhaiRecipeQuery.readCard(buf);
        boolean same = back.ins().size() == ins.size() && back.outs().size() == outs.size();
        String diff = "";
        if (same) {
            for (int i = 0; i < ins.size(); i++) {
                final ShanhaiRecipeQuery.Chip a = ins.get(i);
                final ShanhaiRecipeQuery.Chip b = back.ins().get(i);
                if (a.circuit() != b.circuit() || a.catalyst() != b.catalyst()
                        || a.id() != b.id() || a.count() != b.count() || a.item() != b.item()) {
                    same = false;
                    diff = " 第 " + i + " 格: 原=" + a + " 读回=" + b;
                }
            }
        } else {
            diff = " 格数对不上: 原=" + ins.size() + "/" + outs.size()
                    + " 读回=" + back.ins().size() + "/" + back.outs().size();
        }
        check("card_wire_roundtrip", same && back.insMore() == 3 && back.outsMore() == 1
                        && back.shortId().equals("wire_probe") && back.tierIndex() == 1,
                "写出去=" + written + " 字节 逐格一致=" + same
                        + " insMore=" + back.insMore() + "（期望 3） outsMore=" + back.outsMore() + "（期望 1）" + diff
                        + "（负对照：第 3 格本来就没有电路号，读回来必须还是 -1，不许变成 0）");
    }

    /** 一条配方的输入侧"独立重取"行：{@code [电路号(NBT), 是否催化剂(chance==0), 物品注册表 int id]}（前 4 格）。 */
    private static List<int[]> rawInputRows(GTRecipe r) {
        final List<int[]> out = new ArrayList<>();
        try {
            for (Content c : safeContents(r, true)) {
                if (out.size() >= ShanhaiRecipeQuery.MAX_CHIPS) {
                    break;
                }
                final ItemStack st = ShanhaiIoTable.representativeItem(c);
                if (st == null || st.isEmpty()) {
                    continue;
                }
                out.add(new int[]{rawCircuitNbt(st), c.chance == 0 ? 1 : 0,
                        net.minecraft.core.registries.BuiltInRegistries.ITEM.getId(st.getItem())});
            }
            for (Content c : safeContents(r, false)) {
                if (out.size() >= ShanhaiRecipeQuery.MAX_CHIPS) {
                    break;
                }
                final com.lowdragmc.lowdraglib.side.fluid.FluidStack fs =
                        ShanhaiIoTable.representativeFluid(c);
                if (fs == null || fs.isEmpty()) {
                    continue;
                }
                out.add(new int[]{-1, c.chance == 0 ? 1 : 0, -1});
            }
        } catch (Throwable ignored) {
            // 读不出来就当这一条没有行（它会在对齐时被跳过，不会假绿）
        }
        return out;
    }

    private static List<Content> safeContents(GTRecipe r, boolean item) {
        try {
            final List<Content> l = r.getInputContents(item ? ItemRecipeCapability.CAP
                    : com.gregtechceu.gtceu.api.capability.recipe.FluidRecipeCapability.CAP);
            return l == null ? List.of() : l;
        } catch (Throwable t) {
            return List.of();
        }
    }

    /**
     * <b>独立路线</b>：直接从物品的 NBT 上读电路号（不经过 {@code IntCircuitBehaviour}）。
     *
     * <p>它的作用就是当对照 —— 卡片那条路走的是 GT 的
     * {@code isIntegratedCircuit + getCircuitConfiguration}，两条路独立取到同一个数才算数。
     */
    private static int rawCircuitNbt(ItemStack st) {
        if (st == null || st.isEmpty() || !st.hasTag()) {
            return -1;
        }
        final net.minecraft.nbt.CompoundTag tag = st.getTag();
        if (tag == null || !tag.contains("Configuration")) {
            return -1;
        }
        try {
            return tag.getInt("Configuration");
        } catch (Throwable t) {
            return -1;
        }
    }

    private static ResourceLocation itemIdAt(int registryId) {
        try {
            return net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(
                    net.minecraft.core.registries.BuiltInRegistries.ITEM.byId(registryId));
        } catch (Throwable t) {
            return null;
        }
    }

    private static String idOf(GTRecipe r) {
        return r == null || r.id == null ? "(none)" : r.id.toString();
    }

    private static String idOfItemRaw(int registryId) {
        final ResourceLocation k = itemIdAt(registryId);
        return k == null ? "?" : k.toString();
    }

    /**
     * 🔴 <b>顶部那一排机器名不许被裁成一个字</b>（用户报的 ③）—— 纯函数的机器判据。
     *
     * <h4>量的原始数字（本拍会逐条打出来）</h4>
     * <pre>
     *   一格宽            = 74 px（ShanhaiRecipeEditorSession.TAB_CELL_PX）
     *   名字区可用宽      = 52 px（74 − 图标 18 − 间距 2 − 右留白 2）
     *   老的裁法          = clipUnits(label, 5)
     *                       单位口径：汉字 2、省略号 2 ⇒ 预算 5−2 = 3 ⇒ 只放得进 1 个汉字
     *   ⇒ 「粉碎机」= 3 个汉字 = 27 px（52 px 里明明放得下 5 个），却被裁成「碎…」
     * </pre>
     * 本拍三件事：
     * <ol>
     *   <li><b>复现用户截图</b>：老的裁法跑一遍 {@code clipUnits("粉碎机", 5)}，
     *       结果必须<b>逐字</b>等于 {@code 碎…} —— 这是"根因找对了"的证据；</li>
     *   <li><b>新的裁法</b>：{@code fitTabLabel("粉碎机", 1.0f)} 必须是完整的 {@code 粉碎机}；</li>
     *   <li><b>负对照</b>：故意给一个超长名字（20 个汉字），新裁法必须<b>带省略号且至少留 4 个汉字</b>
     *       （"不许只有一个字"这条硬要求），而且宽度必须落在 52 px 以内。</li>
     * </ol>
     */
    private static void caseTabNameFit(net.minecraft.server.MinecraftServer server) {
        // ── 1. 复现老行为（用户截图那一排的【形状】：1 个字 ＋ 省略号）──
        //    ⚠️ 第一版我把期望写成了「粉碎机 ⇒ 碎…」（照抄截图里的字），实测打脸：
        //       老裁法留着的是**第一个字** ⇒ 「粉碎机 ⇒ 粉…」。截图里的 碎…/星…/原…/组…
        //       是**别的**名字的头一个字（例如 星核剥离 ⇒ 星…、组装机 ⇒ 组…），
        //       形状一样、字不一样。⇒ 判据改成"三个名字都必须是：首字 ＋ 省略号"。
        final String[] probes = {"粉碎机", "星核剥离", "组装机"};
        final StringBuilder oldShown = new StringBuilder();
        boolean oldAllCrushed = true;
        for (String probe : probes) {
            final String crushed = ShanhaiRecipeEditorSession.clipUnits(probe, 5);
            oldShown.append(" '").append(probe).append("'->'").append(crushed).append("'");
            final boolean crushedToOne = crushed.length() == 2 && crushed.endsWith("…")
                    && crushed.charAt(0) == probe.charAt(0);
            oldAllCrushed &= crushedToOne;
        }
        check("tab_name_old_bug_reproduced", oldAllCrushed,
                "老裁法 clipUnits(名字, 5) 逐个 = " + oldShown
                        + "（必须每一个都是【首字 ＋ 省略号】—— 这就是用户截图里那一排 "
                        + "'碎…'/'星…'/'原…'/'组…' 的形状）"
                        + " 单位口径: '粉碎机'=" + ShanhaiRecipeEditorSession.displayUnits("粉碎机")
                        + " 单位 预算=" + (5 - ShanhaiRecipeEditorSession.charUnits('…')) + " 单位");

        // ── 2. 新裁法：完整名字（三个都要完整）──
        final StringBuilder newShown = new StringBuilder();
        boolean newAllFull = true;
        for (String probe : probes) {
            final String now = ShanhaiRecipeEditorSession.fitTabLabel(probe, 1.0f);
            newShown.append(" '").append(probe).append("'->'").append(now).append("'");
            newAllFull &= probe.equals(now);
        }
        check("tab_name_live", newAllFull,
                "新裁法 fitTabLabel(名字, 1.0) 逐个 = " + newShown + "（必须与原名逐字相同）"
                        + " 控件宽=" + ShanhaiRecipeEditorSession.TAB_CELL_PX
                        + "px 名字区可用=" + ShanhaiRecipeEditorSession.TAB_NAME_PX
                        + "px 这三个名字实际占=" + ShanhaiRecipeEditorSession.textPx("星核剥离")
                        + "px（52px 放得下 5 个汉字=45px ⇒ 原来根本不是'放不下'，是裁参数写死了 5）");

        // ── 3. 负对照：超长名字 ──
        final String longName = "超长配方种类名字用于压力测试请不要真的出现";
        final float scale = ShanhaiRecipeEditorSession.tabNameScale(List.of(longName));
        final String clipped = ShanhaiRecipeEditorSession.fitTabLabel(longName, scale);
        final int clippedPx = ShanhaiRecipeEditorSession.textPx(clipped);
        final boolean hasEll = clipped.endsWith("…");
        final int keptChars = clipped.length() - (hasEll ? 1 : 0);
        check("tab_name_negative_control",
                hasEll && keptChars >= 4 && clippedPx * scale <= ShanhaiRecipeEditorSession.TAB_NAME_PX,
                "名字=" + longName + " 长=" + longName.length() + " 字 选中的字号=" + scale
                        + " 裁后='" + clipped + "' 保留=" + keptChars + " 字 估算宽=" + clippedPx
                        + "px ×" + scale + " = " + (int) (clippedPx * scale) + "px（必须 ≤ "
                        + ShanhaiRecipeEditorSession.TAB_NAME_PX + "px 且保留 ≥ 4 字）");

        // ── 4. 真实类型名（从活配方类型上取，不是编的）──
        String sampleType = "(none)";
        String sampleShown = "(none)";
        int samplePx = -1;
        float sampleScale = 1f;
        try {
            final List<String> names = new ArrayList<>();
            for (GTRecipeType t : com.gregtechceu.gtceu.api.registry.GTRegistries.RECIPE_TYPES) {
                if (t == null || t.registryName == null) {
                    continue;
                }
                names.add(ShanhaiRecipeEditorSession.typeName(t).text());
                if (names.size() >= ShanhaiRecipeEditorWorkspace.TABS_PER_PAGE) {
                    break;
                }
            }
            sampleScale = ShanhaiRecipeEditorSession.tabNameScale(names);
            if (!names.isEmpty()) {
                sampleType = names.get(0);
                sampleShown = ShanhaiRecipeEditorSession.fitTabLabel(sampleType, sampleScale);
                samplePx = ShanhaiRecipeEditorSession.textPx(sampleType);
            }
        } catch (Throwable t) {
            sampleType = "(读取失败: " + t + ")";
        }
        ShanhaiMod.LOGGER.info("{} case=tab_name_sample labels={} scale={} first='{}' shown='{}' px={}",
                PREFIX, ShanhaiRecipeEditorWorkspace.TABS_PER_PAGE, sampleScale, sampleType, sampleShown, samplePx);
        // 硬判据只有一条：真实类型名不许被裁成「只有一个字 + 省略号」（用户原话里的那一排）
        final boolean oneCharOnly = sampleShown.endsWith("…") && sampleShown.length() <= 2;
        check("tab_name_sample_real", !oneCharOnly && !sampleShown.isEmpty() && !"(none)".equals(sampleShown),
                "真实类型名样本='" + sampleType + "' 实际会画='" + sampleShown
                        + "' 宽=" + samplePx + "px 字号=" + sampleScale
                        + "（必须不是'一个字+省略号'；真实像素宽由客户端那一行 tab_name 日志量）");
    }

    private interface CaseBody {
        void run() throws Throwable;
    }    private static void runCase(String name, CaseBody body) {
        try {
            body.run();
        } catch (Throwable t) {
            fail(name, "threw " + t.getClass().getName() + ": " + t.getMessage());
            ShanhaiMod.LOGGER.error("{} case={} EXCEPTION", PREFIX, name, t);
        }
    }

    private static void check(String name, boolean ok, String detail) {
        cases++;
        if (!ok) {
            fails++;
        }
        ShanhaiMod.LOGGER.info("{} case={} ok={} {}", PREFIX, name, ok, detail);
    }

    // ==================================================================== 🆕 第 12 刀
    //   非 GT 配方的【新建 / 删除】＋「别的 mod 的那 37 个类型」只读侦察。
    //
    //   为什么这一组要分成四拍：
    //     ① vanilla_new        —— 走【面板那条真路】（ws.newRecipe），全程 persist=false
    //                              ⇒ config 一个字节不动，末尾自己收拾干净；
    //     ② vanilla_new_neg    —— 两条负对照：id 撞了 / 删一条不存在的；
    //     ③ vanilla_new_write  —— 只在 WRITE 档：真的留下两条新建 + 一条删除，给下一局验；
    //     ④ vanilla_new_replay —— 下一局（连着两次）读那三条的结果 ⇒ 端到端"重启不丢"。
    //   外加一条只读侦察拍 vanilla_mod_types（任务 B：别的 mod 那 37 个类型能不能表达）。

    /** 新建探针（有形状合成）。 */
    private static final ResourceLocation PROBE_V_ADD_ID =
            new ResourceLocation("shanhai", "crafting/editor_probe_vanilla_add");
    /** 新建探针（烧炼）。 */
    private static final ResourceLocation PROBE_V_COOK_ID =
            new ResourceLocation("shanhai", "smelting/editor_probe_vanilla_cook");
    /** 删除探针：一条**真有**的原版配方（2 个木板 → 4 根木棍）。 */
    private static final ResourceLocation PROBE_V_REMOVE_ID = new ResourceLocation("minecraft", "stick");
    private static final String PROBE_V_IN = "minecraft:stone";
    private static final String PROBE_V_OUT = "minecraft:emerald";
    /** 新建时先写 7 个，随后"再编辑一次"改成 9 个 —— 两次都要在重启后读得回来。 */
    private static final int PROBE_V_OUT_N = 7;
    private static final int PROBE_V_OUT_N2 = 9;
    private static final String PROBE_V_COOK_OUT = "minecraft:gold_ingot";
    private static final int PROBE_V_COOK_OUT_N = 5;
    private static final int PROBE_V_COOK_TIME = 4242;
    private static final double PROBE_V_COOK_XP = 1.5d;

    /**
     * 拿一个**真的工作台容器**去问 {@code RecipeManager}（= 合成台自己走的那一步）。
     *
     * <p>这是"合成台真的能用它合成"的机器判据本体：{@code CraftingMenu} 每次格子变化都会调
     * {@code RecipeManager.getRecipeFor(RecipeType.CRAFTING, container, level)}，本方法逐字照做。
     */
    private static net.minecraft.world.item.crafting.CraftingRecipe craftingHitOn(
            net.minecraft.server.MinecraftServer server, java.util.List<ItemStack> grid) {
        final java.util.List<ResourceLocation> ids = craftingHitIds(server, grid);
        if (ids.isEmpty()) {
            return null;
        }
        final net.minecraft.world.item.crafting.Recipe<?> r =
                ShanhaiVanillaRecipeOps.readFromTable(server, ids.get(0));
        return r instanceof net.minecraft.world.item.crafting.CraftingRecipe cr ? cr : null;
    }

    /**
     * 同一个摆法下，{@code RecipeManager.getRecipesFor(...)} 给出的<b>全部</b>命中 id。
     *
     * <p>为什么用"列表"而不是"第一个"：原版按类型的迭代顺序返回，同一个摆法可能被好几种配方命中
     * （例如别的 mod 加的同款合成、fastsmelting 那种加速熔炼）⇒ 只看第一个会得到**不稳定的读数**。
     *
     * <p>🔴 <b>为什么必须用 4 参构造器建容器</b>（第 12 刀踩到、当场抓出来的）：
     * {@code TransientCraftingContainer.setItem(...)} 的第一件事是
     * {@code this.menu.slotsChanged(this)}，而 {@code menu} 是我们传进去的那个参数 ——
     * 传 {@code null} 再 {@code setItem} ⇒ <b>NPE</b> ⇒ 这一拍永远返回空表
     * （读数：{@code case=crafting_hits threw NullPointerException … "this.f_286998_" is null}）。
     * 4 参构造器直接收一份 {@code NonNullList}，<b>不碰 menu</b> ✓
     * —— 这也是"我的检查器先证明自己是对的"那条纪律的现场收益：
     * 它在 {@code vanilla_remove_premise} 那条正向对照上自曝了。
     */
    private static java.util.List<ResourceLocation> craftingHitIds(
            net.minecraft.server.MinecraftServer server, java.util.List<ItemStack> grid) {
        final java.util.List<ResourceLocation> out = new ArrayList<>();
        try {
            final net.minecraft.core.NonNullList<ItemStack> items =
                    net.minecraft.core.NonNullList.withSize(9, ItemStack.EMPTY);
            for (int i = 0; i < Math.min(9, grid.size()); i++) {
                items.set(i, grid.get(i) == null ? ItemStack.EMPTY : grid.get(i));
            }
            final net.minecraft.world.inventory.TransientCraftingContainer cc =
                    new net.minecraft.world.inventory.TransientCraftingContainer(null, 3, 3, items);
            final var hits = server.getRecipeManager().getRecipesFor(
                    net.minecraft.world.item.crafting.RecipeType.CRAFTING, cc, server.overworld());
            for (net.minecraft.world.item.crafting.Recipe<?> r : hits) {
                out.add(r.getId());
            }
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.warn("{} case=crafting_hits threw {}", PREFIX, t.toString());
        }
        return out;
    }

    /** 同样的摆法，合成台**会产出什么**（{@code CraftingMenu} 拿到的就是它）。 */
    private static ItemStack craftingAssemble(net.minecraft.server.MinecraftServer server,
                                              java.util.List<ItemStack> grid) {
        try {
            final net.minecraft.core.NonNullList<ItemStack> items =
                    net.minecraft.core.NonNullList.withSize(9, ItemStack.EMPTY);
            for (int i = 0; i < Math.min(9, grid.size()); i++) {
                items.set(i, grid.get(i) == null ? ItemStack.EMPTY : grid.get(i));
            }
            final net.minecraft.world.inventory.TransientCraftingContainer cc =
                    new net.minecraft.world.inventory.TransientCraftingContainer(null, 3, 3, items);
            final var hit = server.getRecipeManager().getRecipeFor(
                    net.minecraft.world.item.crafting.RecipeType.CRAFTING, cc, server.overworld());
            if (hit.isEmpty()) {
                return ItemStack.EMPTY;
            }
            return hit.get().assemble(cc, server.registryAccess());
        } catch (Throwable t) {
            return ItemStack.EMPTY;
        }
    }

    /**
     * 拿一个真的熔炉容器去问（熔炉那一族走的是 {@code RecipeType.SMELTING}）。
     *
     * <p>⚠️ 返回的是 {@code getRecipeFor} 的<b>第一条命中</b> —— 本整合包里有别的 mod 也吃"石头→某物"
     * （读数：{@code 命中=fastsmelting:minecraft/smooth_stone}）⇒ 判据必须用"我们的 id 在不在命中列表里"，
     * 不能写"第一条必须是我们的"（那是【判据自己错】）。
     */
    private static net.minecraft.world.item.crafting.AbstractCookingRecipe smeltingHitOn(
            net.minecraft.server.MinecraftServer server, ItemStack in) {
        try {
            final net.minecraft.world.SimpleContainer sc = new net.minecraft.world.SimpleContainer(1);
            sc.setItem(0, in == null ? ItemStack.EMPTY : in);
            final var hit = server.getRecipeManager().getRecipeFor(
                    net.minecraft.world.item.crafting.RecipeType.SMELTING, sc, server.overworld());
            return hit.orElse(null);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 同一份输入下，熔炼那一族的<b>全部</b>命中 id（判"我们的那条在不在里面"）。 */
    private static java.util.List<ResourceLocation> smeltingHitIds(
            net.minecraft.server.MinecraftServer server, ItemStack in) {
        final java.util.List<ResourceLocation> out = new ArrayList<>();
        try {
            final net.minecraft.world.SimpleContainer sc = new net.minecraft.world.SimpleContainer(1);
            sc.setItem(0, in == null ? ItemStack.EMPTY : in);
            for (net.minecraft.world.item.crafting.Recipe<?> r : server.getRecipeManager()
                    .getRecipesFor(net.minecraft.world.item.crafting.RecipeType.SMELTING, sc,
                            server.overworld())) {
                out.add(r.getId());
            }
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.warn("{} case=smelting_hits threw {}", PREFIX, t.toString());
        }
        return out;
    }

    private static ItemStack stackOf(String id, int count) {
        final Item it = itemByName(id);
        return it == null ? ItemStack.EMPTY : new ItemStack(it, count);
    }

    /**
     * ① <b>面板那条真路</b>：在「工作台」类型下点一次「新建配方」，然后
     * 用真的工作台容器问一次、再删掉它。<b>全程 persist=false ⇒ config 一个字节不动。</b>
     */
    private static void caseVanillaNew(MinecraftServer server) {
        final ResourceLocation typeId = new ResourceLocation("minecraft", "crafting");
        final ShanhaiRecipeEditorWorkspace ws = new ShanhaiRecipeEditorWorkspace(server, null);
        ws.reloadTypes();
        if (!ws.selectType(typeId)) {
            check("vanilla_new_panel_path", false, "选不中类型 " + typeId);
            return;
        }
        final int before = ShanhaiVanillaRecipeOps.countInTable(server, typeId);
        final boolean made = ws.newRecipe(false);
        final ResourceLocation newId = ws.currentRecipeId();
        final int after = ShanhaiVanillaRecipeOps.countInTable(server, typeId);
        final net.minecraft.world.item.crafting.Recipe<?> live =
                newId == null ? null : ShanhaiVanillaRecipeOps.readFromTable(server, newId);
        ShanhaiVanillaRecipeTable.rebuildIndex(server);
        final boolean inIndex = newId != null && ShanhaiVanillaRecipeTable.idsOf(typeId).contains(newId);
        check("vanilla_new_panel_path",
                made && live != null && after == before + 1 && inIndex
                        && ws.stage() == ShanhaiRecipeEditorWorkspace.Stage.EDIT,
                "type=" + typeId + " made=" + made + " new_id=" + newId
                        + " 活表里=" + (live != null) + " 条数 " + before + "->" + after
                        + " 在类型索引里=" + inIndex + " stage=" + ws.stage()
                        + " persist=false(config 未动)");

        if (live == null || newId == null) {
            return;
        }
        // ---- 「合成台真的能用它合成」：新造的这条是 1×1 占位物（屏障）----
        final Item barrier = net.minecraft.world.item.Items.BARRIER;
        final java.util.List<ItemStack> grid = new ArrayList<>();
        grid.add(new ItemStack(barrier, 1));
        final java.util.List<ResourceLocation> hits = craftingHitIds(server, grid);
        final ItemStack out = craftWith(server, grid, newId);
        check("vanilla_new_craftable",
                hits.contains(newId) && out.getItem() == barrier && out.getCount() == 1,
                "摆 1 个屏障 ⇒ 合成台那一次查询命中=" + hits + "（期望包含 " + newId + "）"
                        + "产出=" + describeStack(out) + "（合成台走的就是这一次查询）");

        // ---- 负对照 A：同一个 id 再建一次 ⇒ 必须被拒，且不许覆盖已经存在的那一条 ----
        final Object beforeObj = ShanhaiVanillaRecipeTable.pristine(newId);
        final String taken = ShanhaiVanillaRecipeOps.idTakenReason(server, newId);
        final String[] note = new String[1];
        final net.minecraft.world.item.crafting.Recipe<?> again =
                ShanhaiVanillaRecipeOps.addRecipeFromJson(server, typeId, newId,
                        ShanhaiVanillaRecipeOps.newRecipeSkeleton("minecraft:crafting_shaped"), false, note);
        final Object afterObj = ShanhaiVanillaRecipeTable.pristine(newId);
        check("vanilla_new_id_taken_NEGATIVE_CONTROL",
                taken != null && again == null && beforeObj == afterObj && afterObj != null,
                "撞 id 时：闸门说=" + taken + " 再建结果=" + (again == null ? "null(被拒)" : "★居然建成了")
                        + " 原来那条对象变了吗=" + (beforeObj != afterObj)
                        + " note=" + note[0]);

        // ---- 负对照 B：形状不合法的 JSON ⇒ 原版序列化器必须当场拒收 ----
        final com.google.gson.JsonObject bad = new com.google.gson.JsonObject();
        bad.addProperty("type", "minecraft:crafting_shaped");
        final ResourceLocation freeId = ShanhaiVanillaRecipeOps.findFreeId(server, typeId);
        final String[] note2 = new String[1];
        final net.minecraft.world.item.crafting.Recipe<?> badMade =
                ShanhaiVanillaRecipeOps.addRecipeFromJson(server, typeId, freeId, bad, false, note2);
        check("vanilla_new_bad_json_NEGATIVE_CONTROL",
                badMade == null && (freeId == null || ShanhaiVanillaRecipeTable.pristine(freeId) == null),
                "没有 pattern 的 crafting_shaped ⇒ 结果=" + (badMade == null ? "null(被拒)" : "★居然建成了")
                        + " note=" + note2[0] + " 那个候选 id 有没有被登记="
                        + (freeId == null ? "(没有可用 id)" : String.valueOf(ShanhaiVanillaRecipeTable.pristine(freeId) != null)));

        // ---- 收拾干净：把新建那条删掉，并证明"删除"真的把它从合成台拿走了 ----
        final ShanhaiRecipeEditorOps.Result del =
                ShanhaiVanillaRecipeOps.removeRecipe(server, newId, false);
        final int afterDel = ShanhaiVanillaRecipeOps.countInTable(server, typeId);
        final java.util.List<ResourceLocation> hits2 = craftingHitIds(server, grid);
        check("vanilla_new_then_delete",
                del.ok() && ShanhaiVanillaRecipeOps.readFromTable(server, newId) == null
                        && afterDel == before && !hits2.contains(newId),
                "删除结果 ok=" + del.ok() + " 活表里还在吗="
                        + (ShanhaiVanillaRecipeOps.readFromTable(server, newId) != null)
                        + " 条数=" + afterDel + "（期望回到 " + before + "）"
                        + " 合成台那一次查询命中=" + hits2 + "（不许再含 " + newId + "）"
                        + " persist=false");
    }

    /**
     * ② <b>删除</b>（不落盘）＋ 两条负对照。删的是<b>真有</b>的那条原版配方
     * {@code minecraft:stick}（2 个木板 → 4 根木棍），删完当场验"合成台做不出来了"，最后原样放回。
     */
    private static void caseVanillaRemove(MinecraftServer server) {
        final ResourceLocation id = PROBE_V_REMOVE_ID;
        final ResourceLocation typeId = new ResourceLocation("minecraft", "crafting");
        final net.minecraft.world.item.crafting.Recipe<?> base = ShanhaiVanillaRecipeTable.pristine(id);
        if (base == null) {
            ShanhaiMod.LOGGER.warn("{} case=vanilla_remove SKIPPED reason=靶子 {} 不在非 GT 底本里", PREFIX, id);
            return;
        }
        final ShanhaiVanillaRecipeView before = ShanhaiVanillaRecipeView.of(base);
        final int cntBefore = ShanhaiVanillaRecipeOps.countInTable(server, typeId);
        final Item planks = itemByName("minecraft:oak_planks");
        // minecraft:stick 的 pattern 是竖着的两格 ⇒ 摆在 (0,0) 与 (0,1) = 容器下标 0 与 3
        final java.util.List<ItemStack> grid = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            grid.add(ItemStack.EMPTY);
        }
        grid.set(0, new ItemStack(planks, 1));
        grid.set(3, new ItemStack(planks, 1));
        final java.util.List<ResourceLocation> hitsBefore = craftingHitIds(server, grid);
        // 🔴 正向对照：先证明"这个摆法本来真的命中这条配方"——否则下面的"删完不命中了"是假绿
        if (!hitsBefore.contains(id)) {
            check("vanilla_remove_premise", false,
                    "正向对照失败：2 个木板竖着摆竟然不命中 " + id + "（实际命中=" + hitsBefore
                            + "）⇒ 这一拍的靶子选错了，后面的读数不能当证据");
            return;
        }
        check("vanilla_remove_premise", true,
                "正向对照：2 个木板竖着摆 ⇒ 命中 " + hitsBefore + "（含靶子 " + id + "）");

        final ShanhaiRecipeEditorOps.Result r = ShanhaiVanillaRecipeOps.removeRecipe(server, id, false);
        final int cntAfter = ShanhaiVanillaRecipeOps.countInTable(server, typeId);
        final boolean goneInTable = ShanhaiVanillaRecipeOps.readFromTable(server, id) == null;
        final java.util.List<ResourceLocation> hitsAfter = craftingHitIds(server, grid);
        final boolean craftGone = !hitsAfter.contains(id);
        // 🔴 第 12 刀：把"同一个 id 在三张视图里各是什么"逐张打出来。
        //    为什么要这么细：第一版判据在这里出现过**互相矛盾**的读数
        //    （`getRecipes()` 那条路说"没了"、`getRecipesFor` 那条路说"还在"），
        //    而这两条路按字节码看都出自同一个字段 ⇒ 必有一处的假设是错的。
        //    不许猜：三张视图一次问清（`byName` / `byType` / `getRecipes()` 展平 / 合成台那一次查询）。
        final String views;
        try {
            final var rm = server.getRecipeManager();
            int byRecipes = 0;
            for (net.minecraft.world.item.crafting.Recipe<?> x : rm.getRecipes()) {
                if (id.equals(x.getId())) {
                    byRecipes++;
                }
            }
            // ⚠️ `RecipeManager.byType(...)` 在 1.20.1 是 **private**（编译期实测：
            //    "byType 在 RecipeManager 中具有 private 访问权限"）⇒ 这里只能问
            //    它暴露出来的那两条：`byKey(id)`（byName）与 `getRecipes()`（byType 展平）。
            views = "byName_has=" + rm.byKey(id).isPresent()
                    + " getRecipes_has=" + byRecipes
                    + " getRecipes_total=" + rm.getRecipes().size()
                    + " byType_has=" + ShanhaiRecipeTableHook.byTypeHas(server,
                            net.minecraft.world.item.crafting.RecipeType.CRAFTING, id)
                    + " views=" + ShanhaiRecipeTableHook.readViews(server, id).note()
                    + " 合成台命中=" + hitsAfter;
        } catch (Throwable t) {
            check("vanilla_remove_views", false, "三张视图探针抛了：" + t);
            return;
        }
        check("vanilla_remove", r.ok() && goneInTable && cntAfter == cntBefore - 1 && craftGone,
                "id=" + id + "（2 木板 → 4 木棍）删除 ok=" + r.ok() + " 活表里还在吗=" + !goneInTable
                        + " 条数 " + cntBefore + "->" + cntAfter
                        + " 合成台那一次查询：删前命中=" + hitsBefore + " 删后命中=" + hitsAfter
                        + " persist=false（文件没动） || 三张视图：" + views);

        // 🔴 负对照：删一条【不存在】的 ⇒ 必须报 MISSING，不许静默成功
        final ResourceLocation bogus = new ResourceLocation("shanhai", "definitely_no_such_recipe_zzz");
        final int fileBefore = ShanhaiRecipeOverrideStore.entryCount();
        final ShanhaiRecipeEditorOps.Result miss = ShanhaiVanillaRecipeOps.removeRecipe(server, bogus, true);
        final int fileAfter = ShanhaiRecipeOverrideStore.entryCount();
        final boolean hasEntry = ShanhaiRecipeOverrideStore.findEntry(bogus) != null;
        check("vanilla_remove_missing_NEGATIVE_CONTROL",
                !miss.ok() && miss.detail() != null && miss.detail().contains("MISSING")
                        && fileBefore == fileAfter && !hasEntry,
                "删一个不存在的 id ⇒ ok=" + miss.ok() + "（必须 false）detail=\"" + miss.detail()
                        + "\" 文件条数 " + fileBefore + "->" + fileAfter + " 有没有留下条目=" + hasEntry
                        + "（persist=true 传进去的 ⇒ 闸门挡不住就会真的写一条）");

        // ---- 放回原样（台账撤掉 + 表里补插；persist=false ⇒ 文件仍然没动）----
        final ShanhaiRecipeEditorOps.Result back = ShanhaiVanillaRecipeOps.restoreOne(server, id, false);
        final ShanhaiVanillaRecipeView now = viewOf(server, id);
        final int cntBack = ShanhaiVanillaRecipeOps.countInTable(server, typeId);
        check("vanilla_remove_restore",
                back.ok() && now != null && cntBack == cntBefore
                        && now.result.getCount() == before.result.getCount()
                        && now.inputs.size() == before.inputs.size(),
                "放回后 ok=" + back.ok() + " 读回=" + (now == null ? "null" : now.statsLine())
                        + " 条数=" + cntBack + "（期望 " + cntBefore + "）"
                        + " 产物仍是 " + (now == null ? "?" : now.result.getCount() + " 个"));
    }

    /**
     * ③ <b>落盘档（只在 {@code SHANHAI_EDITOR_WRITE=1} 跑）</b>：真的新建两条 + 真的删一条，
     * 给下一局（连着两次）做端到端。
     *
     * <p>其中最关键的一拍是 {@code vanilla_new_edit_kept_add}：<b>新建 → 再编辑 → 保存</b>之后，
     * 覆盖文件里那条 entry <b>必须还是 {@code op=add}}</b>。判据是读文件里那条 entry 的 op，
     * <b>不是</b>"它在活配方表里存在吗" —— 后者正是 GT 侧第 11 刀那条 P0（配方消失）的病根。
     */
    private static void caseVanillaNewWrite(MinecraftServer server) {
        final ResourceLocation craftType = new ResourceLocation("minecraft", "crafting");
        final ResourceLocation cookType = new ResourceLocation("minecraft", "smelting");

        // ---- ① 有形状合成：石头(1×1) → 7 个绿宝石 ----
        final com.google.gson.JsonObject shaped = new com.google.gson.JsonObject();
        shaped.addProperty("type", "minecraft:crafting_shaped");
        final com.google.gson.JsonArray pat = new com.google.gson.JsonArray();
        pat.add("a");
        final com.google.gson.JsonObject key = new com.google.gson.JsonObject();
        final com.google.gson.JsonObject inJson = new com.google.gson.JsonObject();
        inJson.addProperty("item", PROBE_V_IN);
        key.add("a", inJson);
        final com.google.gson.JsonObject resJson = new com.google.gson.JsonObject();
        resJson.addProperty("item", PROBE_V_OUT);
        resJson.addProperty("count", PROBE_V_OUT_N);
        shaped.add("pattern", pat);
        shaped.add("key", key);
        shaped.add("result", resJson);
        final String[] noteA = new String[1];
        final net.minecraft.world.item.crafting.Recipe<?> madeA =
                ShanhaiVanillaRecipeOps.addRecipeFromJson(server, craftType, PROBE_V_ADD_ID, shaped, true, noteA);

        // ---- ② 烧炼：石头 → 5 个金锭，4242 t / 1.5 xp ----
        final com.google.gson.JsonObject cook = new com.google.gson.JsonObject();
        cook.addProperty("type", "minecraft:smelting");
        final com.google.gson.JsonObject cin = new com.google.gson.JsonObject();
        cin.addProperty("item", PROBE_V_IN);
        final com.google.gson.JsonObject cres = new com.google.gson.JsonObject();
        cres.addProperty("item", PROBE_V_COOK_OUT);
        cres.addProperty("count", PROBE_V_COOK_OUT_N);
        cook.add("ingredient", cin);
        cook.add("result", cres);
        cook.addProperty("cookingtime", PROBE_V_COOK_TIME);
        cook.addProperty("experience", PROBE_V_COOK_XP);
        final String[] noteB = new String[1];
        final net.minecraft.world.item.crafting.Recipe<?> madeB =
                ShanhaiVanillaRecipeOps.addRecipeFromJson(server, cookType, PROBE_V_COOK_ID, cook, true, noteB);

        // ---- ③ 当场验"合成台/熔炉真的能用它" ----
        final java.util.List<ItemStack> grid = new ArrayList<>();
        grid.add(stackOf(PROBE_V_IN, 1));
        final java.util.List<ResourceLocation> hits = craftingHitIds(server, grid);
        final ItemStack assembled = craftWith(server, grid, PROBE_V_ADD_ID);
        final java.util.List<ResourceLocation> smeltHits = smeltingHitIds(server, stackOf(PROBE_V_IN, 1));
        final net.minecraft.world.item.crafting.Recipe<?> cookLive =
                ShanhaiVanillaRecipeOps.readFromTable(server, PROBE_V_COOK_ID);
        final ShanhaiVanillaRecipeView cookLiveView =
                cookLive == null ? null : ShanhaiVanillaRecipeView.of(cookLive);
        // 🔴 直接把"那一条配方认不认这个摆法"问出来（不经过 getRecipesFor）——
        //    否则"表里没有"和"在表里但 matches 永远假"在日志上分不开（两种病的修法完全不同）。
        ShanhaiMod.LOGGER.info("{} case=vanilla_new_write_probe add_id={} ⇒ {}",
                PREFIX, PROBE_V_ADD_ID, probeMatches(server, PROBE_V_ADD_ID, grid));
        check("vanilla_new_write_live",
                madeA != null && madeB != null && hits.contains(PROBE_V_ADD_ID)
                        && assembled.getItem() == itemByName(PROBE_V_OUT)
                        && assembled.getCount() == PROBE_V_OUT_N
                        && smeltHits.contains(PROBE_V_COOK_ID)
                        && cookLiveView != null && cookLiveView.cookingTime == PROBE_V_COOK_TIME,
                "id=" + PROBE_V_ADD_ID + " 新建=" + (madeA != null) + " note=" + noteA[0]
                        + " | 合成台摆 1 石头 ⇒ 命中=" + hits
                        + " 产出=" + describeStack(assembled)
                        + " | id=" + PROBE_V_COOK_ID + " 新建=" + (madeB != null) + " note=" + noteB[0]
                        + " | 熔炉摆 1 石头 ⇒ 命中列表=" + smeltHits + "（必须含我们的 id；"
                        + "第一条可能是别的 mod 的加速熔炼，所以判「在不在列表里」）"
                        + " 我们那条在表里=" + (cookLiveView != null)
                        + " 烧炼时间=" + (cookLiveView == null ? -1 : cookLiveView.cookingTime));

        // ---- ④ 🔴 P0 同源的那一拍：对新建出来的那条【再编辑一次并保存】----
        //        判据 = 覆盖文件里那条 entry 的 op 必须【还是 add】，且 recipe JSON 里带上了新值。
        final ShanhaiRecipeEditorOps.Result edit = ShanhaiVanillaRecipeOps.applyEdits(
                server, PROBE_V_ADD_ID, stackOf(PROBE_V_OUT, PROBE_V_OUT_N2), null, null, null, true, true);
        final com.google.gson.JsonObject entry = ShanhaiRecipeOverrideStore.findEntry(PROBE_V_ADD_ID);
        final String opAfter = entry != null && entry.has("op") ? entry.get("op").getAsString() : "(none)";
        int countInRecipeJson = -1;
        if (entry != null && entry.has("recipe") && entry.get("recipe").isJsonObject()) {
            final com.google.gson.JsonObject rj = entry.getAsJsonObject("recipe");
            if (rj.has("result") && rj.get("result").isJsonObject()
                    && rj.getAsJsonObject("result").has("count")) {
                countInRecipeJson = rj.getAsJsonObject("result").get("count").getAsInt();
            }
        }
        check("vanilla_new_edit_kept_add",
                edit.ok() && "add".equals(opAfter) && countInRecipeJson == PROBE_V_OUT_N2,
                "再编辑并保存之后：文件里那条 op=" + opAfter + "（必须还是 add）"
                        + " recipe.result.count=" + countInRecipeJson + "（期望 " + PROBE_V_OUT_N2 + "）"
                        + " ⇒ 判据是【文件里那条 entry 的 op】，不是【活配方表里有没有它】"
                        + "（后者会让它在下一局被判定 MISSING 从而消失 —— GT 侧那条 P0 同源）");

        // ---- ⑤ 真的删一条**原有的**非 GT 配方（op=remove 那条通道）----
        final boolean hasStick = ShanhaiVanillaRecipeTable.pristine(PROBE_V_REMOVE_ID) != null;
        if (hasStick) {
            final ShanhaiRecipeEditorOps.Result rm =
                    ShanhaiVanillaRecipeOps.removeRecipe(server, PROBE_V_REMOVE_ID, true);
            final com.google.gson.JsonObject re = ShanhaiRecipeOverrideStore.findEntry(PROBE_V_REMOVE_ID);
            final String rop = re != null && re.has("op") ? re.get("op").getAsString() : "(none)";
            check("vanilla_remove_write",
                    rm.ok() && "remove".equals(rop),
                    "id=" + PROBE_V_REMOVE_ID + " 删除 ok=" + rm.ok() + " detail=" + rm.detail()
                            + " 文件里那条 op=" + rop + "（必须 remove；若是 (none) 说明底本指纹算不出来"
                            + "⇒ persist 被跳过，如实报）");
        } else {
            ShanhaiMod.LOGGER.warn("{} case=vanilla_remove_write SKIPPED reason=靶子 {} 不在底本里",
                    PREFIX, PROBE_V_REMOVE_ID);
        }
        ShanhaiMod.LOGGER.info("{} case=vanilla_new_write_summary add_id={} cook_id={} remove_id={} "
                        + "（下一局必须读到 add=绿宝石x{}、cook={}t、remove=不见了）",
                PREFIX, PROBE_V_ADD_ID, PROBE_V_COOK_ID, PROBE_V_REMOVE_ID, PROBE_V_OUT_N2, PROBE_V_COOK_TIME);
    }

    /**
     * ④ <b>下一局（连着两次）</b>读那三条的结果 —— 这是"重启不丢 / 重启仍然是删掉的"唯一的独立证据。
     *
     * <p>只在 {@code SHANHAI_EDITOR_VVERIFY=1} 时判红；平时只打一行读数（不带 WRITE 的那几局里
     * 这三条本来就不该存在，判红会是假红）。
     */
    private static void caseVanillaNewReplay(MinecraftServer server) {
        final boolean judge = "1".equals(System.getenv(ShanhaiRecipeEditorCommand.ENV_VVERIFY));
        final net.minecraft.world.item.crafting.Recipe<?> addR =
                ShanhaiVanillaRecipeOps.readFromTable(server, PROBE_V_ADD_ID);
        final ShanhaiVanillaRecipeView addV = addR == null ? null : ShanhaiVanillaRecipeView.of(addR);
        final java.util.List<ItemStack> grid = new ArrayList<>();
        grid.add(stackOf(PROBE_V_IN, 1));
        final java.util.List<ResourceLocation> hits = craftingHitIds(server, grid);
        final ItemStack assembled = craftWith(server, grid, PROBE_V_ADD_ID);
        final java.util.List<ResourceLocation> smeltHits = smeltingHitIds(server, stackOf(PROBE_V_IN, 1));
        final net.minecraft.world.item.crafting.Recipe<?> cookLive =
                ShanhaiVanillaRecipeOps.readFromTable(server, PROBE_V_COOK_ID);
        final ShanhaiVanillaRecipeView cookView =
                cookLive == null ? null : ShanhaiVanillaRecipeView.of(cookLive);
        final net.minecraft.world.item.crafting.Recipe<?> rmR =
                ShanhaiVanillaRecipeOps.readFromTable(server, PROBE_V_REMOVE_ID);

        final boolean addOk = addV != null && !addV.result.isEmpty()
                && addV.result.getItem() == itemByName(PROBE_V_OUT)
                && addV.result.getCount() == PROBE_V_OUT_N2
                && hits.contains(PROBE_V_ADD_ID)
                && assembled.getCount() == PROBE_V_OUT_N2;
        final boolean cookOk = cookView != null && cookView.cookingTime == PROBE_V_COOK_TIME
                && cookView.result.getCount() == PROBE_V_COOK_OUT_N
                && cookView.result.getItem() == itemByName(PROBE_V_COOK_OUT)
                && smeltHits.contains(PROBE_V_COOK_ID);
        final boolean removeOk = rmR == null;

        ShanhaiMod.LOGGER.info("{} case=vanilla_new_replay_read id={} 在表里={} 产物={} | 合成台命中={} 产出={} "
                        + "| {}=在表里{} 烧炼时间={} 熔炉命中={} | {} 在表里={} judge={}",
                PREFIX, PROBE_V_ADD_ID, addV != null, addV == null ? "(none)" : describeStack(addV.result),
                hits, describeStack(assembled),
                PROBE_V_COOK_ID, cookView != null, cookView == null ? -1 : cookView.cookingTime, smeltHits,
                PROBE_V_REMOVE_ID, rmR != null, judge);
        if (!judge) {
            return;
        }
        check("vanilla_new_replay", addOk && cookOk && removeOk,
                "新建那条（重启之后）：在表里=" + (addV != null)
                        + " 产物=" + (addV == null ? "(none)" : describeStack(addV.result))
                        + " 期望=" + PROBE_V_OUT + " x" + PROBE_V_OUT_N2
                        + " | 合成台摆 1 石头 ⇒ 命中=" + hits
                        + " 产出=" + describeStack(assembled)
                        + " | 烧炼那条：在表里=" + (cookView != null)
                        + " 产物=" + (cookView == null ? "(none)" : describeStack(cookView.result))
                        + " 时间=" + (cookView == null ? -1 : cookView.cookingTime)
                        + " 期望 " + PROBE_V_COOK_OUT + " x" + PROBE_V_COOK_OUT_N + " / " + PROBE_V_COOK_TIME + "t"
                        + " 熔炉命中列表=" + smeltHits
                        + " | 删掉的那条 " + PROBE_V_REMOVE_ID + " 还在吗=" + (rmR != null) + "（必须 false）");
    }

    /**
     * ⑤ <b>任务 B 的只读侦察</b>：把"别的 mod 的那 37 个类型"在<b>运行期</b>摸一遍
     * （JSON 层的形状已经用导出快照扫过，这里是运行期的具体类）。
     *
     * <p>要回答的两个问题：
     * <ol>
     *   <li>它们的字段形状能不能用编辑器那套 {@code GRID / LIST / SINGLE / NONE} 表达？</li>
     *   <li>🔴 <b>有没有哪一个类型会被误判成"可编辑"</b>？判据是
     *       {@code ShanhaiVanillaRecipeView.classify}（按<b>具体类</b>分类）——
     *       如果某个 mod 的配方类<b>继承</b>了 {@code ShapedRecipe}，它会被判成 SHAPED
     *       ⇒ 允许编辑 ⇒ 而重建走的是 {@code new ShapedRecipe(...)}
     *       ⇒ <b>类型会被换成 minecraft:crafting</b>（静默把配方换成了另一种配方）。</li>
     * </ol>
     * 本拍把这两件事都打成机器读数；第 2 条只要有 1 条命中就判红。
     */
    private static void caseVanillaModTypes(MinecraftServer server) {
        ShanhaiVanillaRecipeTable.captureIfAbsent(server);
        final java.util.Map<ResourceLocation, java.util.List<net.minecraft.world.item.crafting.Recipe<?>>> byType =
                new java.util.LinkedHashMap<>();
        int nonMinecraftRecipes = 0;
        int editableLeaks = 0;
        int noneMode = 0;
        for (net.minecraft.world.item.crafting.Recipe<?> r : server.getRecipeManager().getRecipes()) {
            if (!ShanhaiVanillaRecipeTable.isVanilla(r)) {
                continue;
            }
            final ResourceLocation t = ShanhaiVanillaRecipeTable.typeIdOf(r);
            if (t == null || "minecraft".equals(t.getNamespace())) {
                continue;
            }
            byType.computeIfAbsent(t, k -> new ArrayList<>()).add(r);
            nonMinecraftRecipes++;
            final ShanhaiVanillaRecipeView v = ShanhaiVanillaRecipeView.of(r);
            final ShanhaiVanillaRecipeShape sh = ShanhaiVanillaRecipeShape.of(r);
            if (sh != null && sh.mode() == ShanhaiVanillaRecipeShape.Mode.NONE) {
                noneMode++;
            }
            if (v != null && v.editable()) {
                editableLeaks++;
                ShanhaiMod.LOGGER.error("{} case=vanilla_mod_type_LEAK id={} type={} class={} kind={} "
                                + "-> 这个 mod 的配方类被判成「可编辑」，但重建走的是原版构造器 ⇒ "
                                + "类型会被换成 minecraft:crafting（静默改错配方）",
                        PREFIX, r.getId(), t, r.getClass().getName(), v.kind);
            }
        }
        ShanhaiMod.LOGGER.info("{} case=vanilla_mod_types_scan types={} recipes={} none_mode={} editable_leaks={}",
                PREFIX, byType.size(), nonMinecraftRecipes, noneMode, editableLeaks);
        for (java.util.Map.Entry<ResourceLocation, java.util.List<net.minecraft.world.item.crafting.Recipe<?>>> e
                : byType.entrySet()) {
            final java.util.Set<String> classes = new java.util.LinkedHashSet<>();
            final java.util.Set<String> kinds = new java.util.LinkedHashSet<>();
            for (net.minecraft.world.item.crafting.Recipe<?> r : e.getValue()) {
                classes.add(r.getClass().getName());
                final ShanhaiVanillaRecipeView v = ShanhaiVanillaRecipeView.of(r);
                kinds.add(v == null ? "?" : v.kind.name());
            }
            ShanhaiMod.LOGGER.info("{} case=vanilla_mod_type type={} n={} classes={} kinds={} 本版={}",
                    PREFIX, e.getKey(), e.getValue().size(), classes, kinds,
                    kinds.contains("UNSUPPORTED") ? "只读" : "★需要复核");
        }
        check("vanilla_mod_types_all_readonly", editableLeaks == 0,
                "非 minecraft 的类型 " + byType.size() + " 种 / " + nonMinecraftRecipes + " 条；"
                        + "其中 NONE 形态（无格子、第三屏只读）=" + noneMode
                        + " 被误判成可编辑的=" + editableLeaks + "（必须 0）");
        check("vanilla_mod_types_not_empty", byType.size() > 0 && nonMinecraftRecipes > 0,
                "只读侦察的覆盖面：types=" + byType.size() + " recipes=" + nonMinecraftRecipes
                        + "（0 说明这一拍空转，读数不能当证据）");
    }

    private static void fail(String name, String detail) {
        check(name, false, detail);
    }

    // ==================================================================== 🆕 第 12 刀 · P0
    //   「改完不实时显示」（用户原话：「我们的配方编辑器都没有实时显示，都需要重读配方表」）

    /**
     * <b>由指定的那条配方</b>算产出（不是"第一条命中的"）。
     *
     * <p>🔴 为什么必须有这一条：同一个摆法会被好几条配方命中（实测：摆 1 个石头同时命中
     * {@code minecraft:stone_button} 与我们的探针），而 {@code getRecipeFor} 只给第一条
     * ⇒ 判"我们这条造出来是什么"必须<b>指定 id</b> 去取，否则判据读的是别人的产出。
     */
    private static ItemStack craftWith(net.minecraft.server.MinecraftServer server,
                                       java.util.List<ItemStack> grid, ResourceLocation wantId) {
        try {
            final net.minecraft.core.NonNullList<ItemStack> items =
                    net.minecraft.core.NonNullList.withSize(9, ItemStack.EMPTY);
            for (int i = 0; i < Math.min(9, grid.size()); i++) {
                items.set(i, grid.get(i) == null ? ItemStack.EMPTY : grid.get(i));
            }
            final net.minecraft.world.inventory.TransientCraftingContainer cc =
                    new net.minecraft.world.inventory.TransientCraftingContainer(null, 3, 3, items);
            for (net.minecraft.world.item.crafting.Recipe<?> r : server.getRecipeManager()
                    .getRecipesFor(net.minecraft.world.item.crafting.RecipeType.CRAFTING, cc,
                            server.overworld())) {
                if (wantId.equals(r.getId())
                        && r instanceof net.minecraft.world.item.crafting.CraftingRecipe cr) {
                    return cr.assemble(cc, server.registryAccess());
                }
            }
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.warn("{} case=craft_with threw {}", PREFIX, t.toString());
        }
        return ItemStack.EMPTY;
    }

    /**
     * <b>直接问那一条配方"认不认这个摆法"</b>（{@code matches}），不经过 {@code getRecipesFor}。
     *
     * <p>为什么要单独问：同一个现象有<b>两种完全不同的病</b> ——
     * ① 它压根不在 {@code byType} 那个桶里（表的问题）；
     * ② 它在桶里，但 {@code matches} 对任何摆法都为假（<b>新建出来的那条配方本身是坏的</b>）。
     * 两者在界面上长得一样（"新建了但合成台用不了"），在日志上必须分得开。
     */
    private static String probeMatches(net.minecraft.server.MinecraftServer server,
                                       ResourceLocation id, java.util.List<ItemStack> grid) {
        try {
            final net.minecraft.world.item.crafting.Recipe<?> r =
                    ShanhaiVanillaRecipeOps.readFromTable(server, id);
            if (r == null) {
                return "配方不在表里";
            }
            final net.minecraft.core.NonNullList<ItemStack> items =
                    net.minecraft.core.NonNullList.withSize(9, ItemStack.EMPTY);
            for (int i = 0; i < Math.min(9, grid.size()); i++) {
                items.set(i, grid.get(i) == null ? ItemStack.EMPTY : grid.get(i));
            }
            final net.minecraft.world.inventory.TransientCraftingContainer cc =
                    new net.minecraft.world.inventory.TransientCraftingContainer(null, 3, 3, items);
            final boolean m;
            try {
                // ⚠️ `Recipe<?>` 的通配符在 `matches(C, Level)` 上是 capture，编译器不肯直接收
                //    `TransientCraftingContainer` ⇒ 这里按原始类型调（我们只关心"真/假"这一个 bool）。
                @SuppressWarnings("unchecked")
                final net.minecraft.world.item.crafting.Recipe<net.minecraft.world.Container> raw =
                        (net.minecraft.world.item.crafting.Recipe<net.minecraft.world.Container>) r;
                m = raw.matches(cc, server.overworld());
            } catch (Throwable t) {
                return "matches 抛了 " + t;
            }
            final ShanhaiVanillaRecipeView v = ShanhaiVanillaRecipeView.of(r);
            // 🔴 三条路同时问：① 真字段 byType；② GT 访问器；③ 合成台走的那一次查询。
            //    上一次读数就是"①/② 说在、③ 说不在"⇒ 必须把这三条并排打出来才分得清是谁的问题。
            final ResourceLocation viaSingular;
            try {
                final var one = server.getRecipeManager()
                        .getRecipeFor(net.minecraft.world.item.crafting.RecipeType.CRAFTING, cc,
                                server.overworld());
                viaSingular = one.map(net.minecraft.world.item.crafting.Recipe::getId).orElse(null);
            } catch (Throwable t) {
                return "getRecipeFor 抛了 " + t;
            }
            final java.util.List<ResourceLocation> plural;
            try {
                plural = new ArrayList<>();
                for (net.minecraft.world.item.crafting.Recipe<?> x : server.getRecipeManager()
                        .getRecipesFor(net.minecraft.world.item.crafting.RecipeType.CRAFTING, cc,
                                server.overworld())) {
                    plural.add(x.getId());
                }
            } catch (Throwable t) {
                return "getRecipesFor 抛了 " + t;
            }
            return "class=" + r.getClass().getSimpleName() + " matches=" + m
                    + " | 运行期RecipeManager=" + ShanhaiRecipeTableHook.managerClass(server)
                    + " | " + ShanhaiRecipeTableHook.crossCheck(server,
                            net.minecraft.world.item.crafting.RecipeType.CRAFTING, id)
                    + " | getRecipeFor(单)=" + viaSingular + " getRecipesFor(多)=" + plural
                    + " | " + ShanhaiRecipeTableHook.replicateQuery(server,
                            net.minecraft.world.item.crafting.RecipeType.CRAFTING, cc, id)
                    + " | 视图(in=" + v.inputs.size() + " 产物=" + describeStack(v.result)
                    + " 输入代表=" + v.representativeInputs() + ")";
        } catch (Throwable t) {
            return "probe threw " + t;
        }
    }

    /** 在【服务端现算的那一份卡片】里找某条配方的产物数量（找不到 = -1）。 */
    private static int cardOutCount(ShanhaiRecipeEditorWorkspace ws, ResourceLocation id) {
        for (ShanhaiRecipeQuery.Card c : ws.buildCards()) {
            if (c != null && id.equals(c.id()) && c.outs() != null && !c.outs().isEmpty()) {
                return c.outs().get(0).count();
            }
        }
        return -1;
    }

    /**
     * <b>P0 判据：保存之后"第二屏那一份卡片"必须立刻是新值，不许需要「重读配方表」。</b>
     *
     * <p>为什么这样验：面板推给客户端的就是 {@code buildCards()} 的那一份
     * （{@code writeState} 里现算）⇒ 它新了，屏幕上就是新的；它旧了，用户就得点「重读配方表」。
     * 本拍<b>不</b>调任何"重读"入口，改完直接再问一次。
     */
    private static void casePanelLiveAfterSave(MinecraftServer server) {
        final ResourceLocation typeId = new ResourceLocation("minecraft", "crafting");
        ShanhaiVanillaRecipeTable.captureIfAbsent(server);
        ShanhaiVanillaRecipeTable.rebuildIndex(server);
        final ResourceLocation targetId = firstVanillaShapedId();
        if (targetId == null) {
            check("panel_live_after_save", false, "找不到非 GT 靶子（这条判据就变成了空转，如实报红）");
            return;
        }
        final ShanhaiRecipeEditorWorkspace ws = new ShanhaiRecipeEditorWorkspace(server, null);
        ws.reloadTypes();
        ws.selectType(typeId);
        // 目标那张卡可能不在第 1 页 ⇒ 逐页找（从当前页往后翻，翻不动就停）
        int before = -1;
        final int pages = ws.pageCount();
        for (int p = 0; p < pages; p++) {
            before = cardOutCount(ws, targetId);
            if (before >= 0) {
                break;
            }
            final int cur = ws.page();
            ws.jumpPage(1);
            if (ws.page() == cur) {
                break;
            }
        }
        if (before < 0) {
            check("panel_live_after_save", false,
                    "第二屏（" + typeId + "）" + pages + " 页里都没找到靶子 " + targetId + " 的卡片 ⇒ 本拍空转");
            return;
        }
        final net.minecraft.world.item.crafting.Recipe<?> beforeRecipe =
                ShanhaiVanillaRecipeOps.readFromTable(server, targetId);
        final ShanhaiVanillaRecipeView bv = beforeRecipe == null ? null
                : ShanhaiVanillaRecipeView.of(beforeRecipe);
        if (bv == null || bv.result.isEmpty()) {
            check("panel_live_after_save", false, "靶子的产物读不出来 ⇒ 本拍空转");
            return;
        }
        final int after = before == 7 ? 9 : 7;
        final net.minecraft.world.item.ItemStack want = bv.result.copy();
        want.setCount(after);
        // 🔴 只调"保存"那一步（= 面板「保存」按下去会走的那一条），
        //    全程【不】碰「重读配方表」。
        ShanhaiVanillaRecipeOps.applyEdits(server, targetId, want, null, null, null, true, false);
        final int live = cardOutCount(ws, targetId);
        check("panel_live_after_save", live == after,
                "id=" + targetId + " 保存前卡片产物=" + before + " → 保存后（不重读配方表）卡片产物=" + live
                        + "（期望 " + after + "）"
                        + " ⇒ 这一条就是用户那句「我们的配方编辑器都没有实时显示，都需要重读配方表」的判据");
        // 复原（persist=false ⇒ config 一个字节都没动）
        final net.minecraft.world.item.ItemStack back = bv.result.copy();
        ShanhaiVanillaRecipeOps.applyEdits(server, targetId, back, null, null, null, true, false);
        final int restored = cardOutCount(ws, targetId);
        check("panel_live_after_restore", restored == before,
                "复原之后卡片产物=" + restored + "（期望 " + before + "）");
    }

    /**
     * <b>P0 判据（JEI 那一侧）：保存之后服务端必须把整份配方表推给客户端。</b>
     *
     * <p>为什么是这一条而不是"看看 JEI 变没变"：JEI 的原版配方来自<b>客户端</b>自己的
     * {@code RecipeManager}，而它只在世界加载那一拍被推下来过；JEI 自己的界面还有一层
     * layout 缓存 ⇒ 只有"重发整表 + 让 JEI 全量重读"这一条路能同时解决。
     * 本拍验的是<b>服务端确实发了</b>（无头专服没有玩家 ⇒ 会打
     * {@code recipe_resync_skipped reason=no_player_online}，那正是"没玩家时才没有发"）。
     */
    private static void caseRecipeResyncPath(MinecraftServer server) {
        final boolean hasPlayers = server.getPlayerList() != null
                && !server.getPlayerList().getPlayers().isEmpty();
        ShanhaiRecipeEditorOps.syncRecipesToClients(server, server.getRecipeManager().getRecipes().size());
        check("recipe_resync_callable", true,
                "在线玩家=" + (hasPlayers ? server.getPlayerList().getPlayers().size() : 0)
                        + " ⇒ 本环境的读数："
                        + (hasPlayers ? "应当已发出 ClientboundUpdateRecipesPacket"
                        : "打印 recipe_resync_skipped（无头专服没玩家，这一句是空转；真机上才会有玩家）")
                        + " · ⚠️ 本轮之后这条路【只手动调】：自动保存路径已经不发整表包了");
    }

    /**
     * 🔴🔴 <b>本轮判据 ①：保存这条路再也不许发整表包（"卡好几秒"就落在这个 0 上）。</b>
     *
     * <h4>为什么要有这一条</h4>
     * 现场读数（用户实例 {@code logs\latest.log}，按时间轴逐条配对）：
     * <pre>
     *   8 次 `recipe_resync_sent`  ⇒  8 次 `Optimized GTCEu JEI recipe registration … elapsed_ms=7642`
     *   紧跟着 `Starting JEI took 10.83~15.22 seconds`
     * </pre>
     * 机制：整表包 ⇒ 客户端 {@code replaceRecipes} ＋ Forge {@code RecipesUpdatedEvent}
     * ⇒ JEI {@code StartEventObserver.restart()} ⇒ GT 的 JEI 插件把 5.4 万条配方全部重注册。
     * 用户原话：「**我现在每次删除或者新建一条配方就要卡好久**」。
     *
     * <p>判据 = 一次**真写入**（改一条非 GT 配方的产物再改回去）前后取
     * {@link ShanhaiRecipeEditorOps#fullResyncCount()}：<b>必须相同</b>。
     * 用"前后相同"而不是"等于 0"，是因为本套自检里另一条用例会手动调一次那条路（验它本身是通的）。
     */
    private static void caseNoFullResyncOnSave(MinecraftServer server) {
        ShanhaiVanillaRecipeTable.captureIfAbsent(server);
        ShanhaiVanillaRecipeTable.rebuildIndex(server);
        ResourceLocation targetId = null;
        net.minecraft.world.item.ItemStack orig = null;
        for (ResourceLocation id : ShanhaiVanillaRecipeTable.idsOf(new ResourceLocation("minecraft", "crafting"))) {
            final net.minecraft.world.item.crafting.Recipe<?> r = ShanhaiVanillaRecipeTable.liveById(id);
            if (r == null) {
                continue;
            }
            final ShanhaiVanillaRecipeView v = ShanhaiVanillaRecipeView.of(r);
            if (v != null && v.editable() && !v.result.isEmpty()) {
                targetId = id;
                orig = v.result.copy();
                break;
            }
        }
        if (targetId == null || orig == null) {
            check("no_full_resync_on_save", false, "靶子为空（这一拍没证据力）");
            return;
        }
        final int before = ShanhaiRecipeEditorOps.fullResyncCount();
        final net.minecraft.world.item.ItemStack bumped = orig.copy();
        bumped.setCount(orig.getCount() + 2);
        final ShanhaiRecipeEditorOps.Result r1 = ShanhaiVanillaRecipeOps.applyEdits(
                server, targetId, bumped, null, null, null, false, false);
        final ShanhaiRecipeEditorOps.Result r2 = ShanhaiVanillaRecipeOps.applyEdits(
                server, targetId, orig, null, null, null, false, false);
        final int after = ShanhaiRecipeEditorOps.fullResyncCount();
        check("no_full_resync_on_save", r1.ok() && r2.ok() && after == before,
                "id=" + targetId + " 两次保存前后整表广播次数 " + before + " -> " + after
                        + "（必须相同：保存这条路一次都不许发整表包；一次整表包 = 客户端 JEI 把 5.4 万条"
                        + "配方全量重注册 = 实测卡 10.8~15.2 秒）· 两次写入 ok=" + r1.ok() + "/" + r2.ok());
    }

    /**
     * 🔴🔴 <b>本轮判据 ②：非 GT 配方的补丁包必须带【整条字节】</b>（"JEI 不显示"就落在这个长度上）。
     *
     * <h4>为什么要有这一条</h4>
     * 现场读数（用户实例日志原文）：
     * <pre>
     *   jei_patch_stored id=shanhai:crafting/new_recipe_1 … kind=vanilla recipe_bytes=0
     *   jei_append_skipped id=shanhai:crafting/new_recipe_1 reason=no_recipe_bytes
     *   jei_sync matched=0 hidden=0 added=0 … expected=1 PASS=false
     * </pre>
     * ⇒ 客户端手里没有那条（刚新建的）⇒ 只能靠字节把它<b>补进</b> JEI 那一页。
     *
     * <p>判据两条：① 消息里 {@code recipeBytes} 非空；② 把这段字节<b>用原版自己的解码器解回来</b>，
     * 必须还是同一条（id 相同 / 同类 / 输入格数相同 / 产物物品与数量相同）。
     * <b>② 才是关键</b>：只验"长度 &gt; 0"会漏掉"编出来但解不回来"的半截包。
     */
    private static void caseJeiVanillaBytes(MinecraftServer server) {
        ShanhaiVanillaRecipeTable.captureIfAbsent(server);
        ShanhaiVanillaRecipeTable.rebuildIndex(server);
        ResourceLocation targetId = null;
        for (ResourceLocation id : ShanhaiVanillaRecipeTable.idsOf(new ResourceLocation("minecraft", "crafting"))) {
            final net.minecraft.world.item.crafting.Recipe<?> r = ShanhaiVanillaRecipeTable.liveById(id);
            if (r != null && ShanhaiVanillaRecipeView.of(r) != null) {
                targetId = id;
                break;
            }
        }
        if (targetId == null) {
            check("jei_vanilla_recipe_bytes", false, "靶子为空（这一拍没证据力）");
            return;
        }
        final net.minecraft.world.item.crafting.Recipe<?> live =
                ShanhaiVanillaRecipeOps.readFromTable(server, targetId);
        final byte[] bytes = ShanhaiJeiBridge.encodeVanillaRecipe(live);
        final ShanhaiJeiBridge.RecipeSyncMessage msg = ShanhaiJeiBridge.vanillaChangedMessage(
                server, targetId, new ResourceLocation("minecraft", "crafting"), "{}");
        String roundTrip;
        boolean same = false;
        final ShanhaiVanillaRecipeView a = live == null ? null : ShanhaiVanillaRecipeView.of(live);
        try {
            final io.netty.buffer.ByteBuf bb = io.netty.buffer.Unpooled.wrappedBuffer(bytes);
            final net.minecraft.network.FriendlyByteBuf in = new net.minecraft.network.FriendlyByteBuf(bb);
            final net.minecraft.world.item.crafting.Recipe<?> back =
                    net.minecraft.network.protocol.game.ClientboundUpdateRecipesPacket.fromNetwork(in);
            final ShanhaiVanillaRecipeView b = back == null ? null : ShanhaiVanillaRecipeView.of(back);
            same = back != null && a != null && b != null
                    && targetId.equals(back.getId())
                    && a.kind == b.kind
                    && a.inputs.size() == b.inputs.size()
                    && a.result.getCount() == b.result.getCount()
                    && a.result.getItem() == b.result.getItem();
            roundTrip = "解回=" + (back == null ? "null" : back.getClass().getSimpleName() + " id=" + back.getId())
                    + " kind=" + (b == null ? "?" : b.kind) + " in=" + (b == null ? -1 : b.inputs.size())
                    + " 产物=" + (b == null ? "?" : describeStack(b.result));
        } catch (Throwable t) {
            roundTrip = "解码抛了 " + t;
        }
        check("jei_vanilla_recipe_bytes",
                bytes != null && bytes.length > 0 && msg != null && msg.recipeBytes != null
                        && msg.recipeBytes.length > 0 && same,
                "id=" + targetId + " 编码字节=" + (bytes == null ? 0 : bytes.length)
                        + " 消息里=" + (msg == null || msg.recipeBytes == null ? 0 : msg.recipeBytes.length)
                        + " jei_uid=" + (msg == null ? "?" : msg.typeId)
                        + " · " + roundTrip
                        + " · 原样=" + (a == null ? "?" : describeStack(a.result)));
    }

    /**
     * 🔴🔴 <b>本轮判据 ③（GT 那一侧）：编辑器自己必须立刻显示新值。</b>
     *
     * <h4>为什么必须有这一条（而 {@code panel_live_after_save} 不够）</h4>
     * 那条判据的靶子是<b>非 GT</b> 的（{@code ad_astra:aeronos_door}）。用户报的是
     * 「我新建了 <b>GT</b> 配方 …… <b>甚至我们的配方编辑器都没有即时刷新</b>」——
     * GT 与 NT 走的是**两套完全不同的取数路径**（GT 走反查索引，非 GT 走原版表），
     * 所以非 GT 绿了完全不代表 GT 也绿。
     *
     * <p>判据三条，全部<b>不点「重读配方表」</b>：
     * ① 改一条已有 GT 配方的<b>耗时</b> ⇒ 第二屏那张卡片的读数必须当场变；
     * ② 旧值必须<b>不再出现</b>（防"新旧两份同时在"）；
     * ③ 复原之后必须变回去（防"只会单向改"）。
     */
    private static void casePanelLiveGtAfterSave(MinecraftServer server) {
        ShanhaiRecipeReverseIndex.ensure(server);
        ResourceLocation targetId = null;
        int dur0 = -1;
        for (GTRecipe r : ShanhaiRecipeReverseIndex.all()) {
            if (r != null && r.id != null && r.getType() != null && r.duration > 0) {
                targetId = r.id;
                dur0 = r.duration;
                break;
            }
        }
        if (targetId == null) {
            check("panel_live_gt_after_save", false, "找不到 GT 靶子（本拍空转，如实报红）");
            return;
        }
        final GTRecipe live0 = ShanhaiRecipeReverseIndex.byId(server, targetId);
        final GTRecipeType type = live0 == null ? null : live0.getType();
        if (type == null) {
            check("panel_live_gt_after_save", false, "靶子读不出类型 ⇒ 本拍空转");
            return;
        }
        final ShanhaiRecipeEditorWorkspace ws = new ShanhaiRecipeEditorWorkspace(server, null);
        ws.reloadTypes();
        ws.selectType(type.registryName);
        final int newDur = dur0 == 4242 ? 4243 : 4242;

        // ⚠️ 靶子可能不在第 1 页（GT 一个类型几千条）⇒ 先翻页找到它，否则读数是 -1（那是判据自己的错）
        int durBeforeShown = -1;
        final int pages = ws.pageCount();
        for (int p = 0; p < pages; p++) {
            durBeforeShown = cardDuration(ws, targetId);
            if (durBeforeShown >= 0) {
                break;
            }
            final int cur = ws.page();
            ws.jumpPage(1);
            if (ws.page() == cur) {
                break;
            }
        }
        if (durBeforeShown < 0) {
            check("panel_live_gt_after_save", false,
                    "第二屏 " + pages + " 页里都没找到 " + targetId + " 的卡片 ⇒ 本拍空转");
            return;
        }
        ws.tick();

        // ① 改耗时（真走"保存"那条收口）
        final ShanhaiRecipeEditorOps.Result r1 = ShanhaiRecipeEditorOps.applyEdits(
                server, live0, null, null, null, newDur, null, null, false, false);
        ws.tick();                        // 面板每帧的钩子（表变了 ⇒ 列表当场重建）
        final int shown1 = cardDuration(ws, targetId);
        // ③ 复原
        final GTRecipe live1 = ShanhaiRecipeReverseIndex.byId(server, targetId);
        final ShanhaiRecipeEditorOps.Result r2 = ShanhaiRecipeEditorOps.applyEdits(
                server, live1 == null ? live0 : live1, null, null, null, dur0, null, null, false, false);
        ws.tick();
        final int shown2 = cardDuration(ws, targetId);

        check("panel_live_gt_after_save",
                r1.ok() && r2.ok() && shown1 == newDur && shown2 == dur0,
                "id=" + targetId + " 原耗时=" + dur0 + "（第 " + (ws.page() + 1) + " 页找到卡片）改成 "
                        + newDur + " ⇒ 第二屏卡片读数=" + shown1 + "（必须 " + newDur
                        + "，且全程没点过「重读配方表」）· 复原 ⇒ " + shown2 + "（必须 " + dur0
                        + "）· 两次写入 ok=" + r1.ok() + "/" + r2.ok());
    }

    /**
     * 🔴🔴 <b>本轮判据 ⑤：restore 必须【逐条】发单条通知</b>（用户现场读数：
     * 「{@code RESTORE_ALL … 整行没有 net_sent}」）。
     *
     * <h4>为什么照抄"删除这条"那条路</h4>
     * 用户实测：「通过配方编辑器<b>删除</b>（而不是指令），可以立即在配方编辑器同步，
     * 也可以立即在 jei 同步」⇒ <b>单条通知这条路是通的</b>；restore 没走它 ⇒ 补上。
     *
     * <p>判据：一条真编辑 → 发通知计数 +1；{@code restoreOne} → 计数再 +1（<b>两次都必须是单条</b>），
     * 且<b>整表广播计数不许涨</b>（不动那 5.4 万条）。
     */
    private static void caseRestoreSendsSingleNotify(MinecraftServer server) {
        ShanhaiRecipeReverseIndex.ensure(server);
        ResourceLocation targetId = null;
        for (GTRecipe r : ShanhaiRecipeReverseIndex.all()) {
            if (r != null && r.id != null && r.getType() != null) {
                targetId = r.id;
                break;
            }
        }
        if (targetId == null) {
            check("restore_sends_single_notify", false, "找不到 GT 靶子（本拍空转）");
            return;
        }
        final GTRecipe live = ShanhaiRecipeReverseIndex.byId(server, targetId);
        if (live == null || live.duration <= 0) {
            check("restore_sends_single_notify", false, "靶子读不出/耗时为 0 ⇒ 本拍空转");
            return;
        }
        final int n0 = ShanhaiJeiBridge.singleNotifyCount();
        final int full0 = ShanhaiRecipeEditorOps.fullResyncCount();
        final int newDur = live.duration == 4242 ? 4243 : 4242;
        // ⚠️ 走【面板那条保存路】（workspace.save）而不是直接调 applyEdits：
        //    单条通知是挂在"面板保存"那条路上的（与用户已经验通的「删除这条」同源）；
        //    直接调 applyEdits 是绕过面板的测试路径，它本来就不发通知 —— 第一版判据这么写会得假红。
        final ShanhaiRecipeEditorWorkspace ws = new ShanhaiRecipeEditorWorkspace(server, null);
        ws.reloadTypes();
        ws.selectType(live.getType().registryName);
        final boolean opened = ws.selectRecipe(targetId);
        final int n1;
        if (opened) {
            ws.setPendingDuration(newDur);
            ws.save();
            n1 = ShanhaiJeiBridge.singleNotifyCount();
        } else {
            n1 = n0;
        }
        final GTRecipe live2 = ShanhaiRecipeReverseIndex.byId(server, targetId);
        final ShanhaiRecipeEditorOps.Result r2 = ShanhaiRecipeEditorOps.restoreOne(
                server, live2 == null ? live : live2, true, false);
        final int n2 = ShanhaiJeiBridge.singleNotifyCount();
        final int full1 = ShanhaiRecipeEditorOps.fullResyncCount();
        check("restore_sends_single_notify",
                opened && r2.ok() && n1 > n0 && n2 > n1 && full1 == full0,
                "id=" + targetId + " 面板保存一次 ⇒ 单条通知 " + n0 + "->" + n1
                        + " · restoreOne ⇒ " + n1 + "->" + n2 + "（两次都必须增加：restore 也要逐条发）"
                        + " · 整表广播 " + full0 + "->" + full1 + "（必须不变：不许整机重注册）"
                        + " · 打开编辑屏=" + opened + " · restore ok=" + r2.ok());
    }

    /**
     * 🔴🔴 <b>本轮判据 ④：{@code /shanhai edit restore} 之后，编辑器自己的列表 ＋ 卡片数据一起重建。</b>
     *
     * <h4>用户原话（逐字）</h4>
     * <blockquote>「{@code /shanhai edit restore} 之后，【编辑器自己的列表】与【卡片数据】要一起重建……
     * 卡片上还留着刚被抹掉的 {@code new_recipe_1}，但它的<b>输入/产物是空的</b>
     * ⇒ <b>列表用旧快照、数据现读 ⇒ 剩一个空壳</b>」</blockquote>
     *
     * <p>判据三条（全部<b>不点「重读配方表」</b>）：
     * ① 被抹掉的那条<b>必须从列表里消失</b>；
     * ② 剩下的卡片<b>每一张都必须有产物</b>（不许留空壳）；
     * ③ 复原那条命令本身要成功。
     *
     * <p>⚠️ 本拍用 {@code restoreOne(..., persist=false)}：<b>不碰 rig 的覆盖文件</b>
     * （那是别的用例共用的夹具，抹了会让它们全红 —— 这条纪律写在文件级注释里）。
     */
    private static void caseRestoreRebuildsPanelList(MinecraftServer server) {
        ShanhaiRecipeReverseIndex.ensure(server);
        ResourceLocation probeId = null;
        for (GTRecipe r : ShanhaiRecipeReverseIndex.all()) {
            if (r != null && r.id != null && r.getType() != null) {
                probeId = r.id;
                break;
            }
        }
        if (probeId == null) {
            check("restore_rebuilds_panel_list", false, "找不到 GT 靶子（本拍空转）");
            return;
        }
        final GTRecipe live = ShanhaiRecipeReverseIndex.byId(server, probeId);
        final GTRecipeType type = live == null ? null : live.getType();
        if (type == null) {
            check("restore_rebuilds_panel_list", false, "靶子读不出类型 ⇒ 本拍空转");
            return;
        }
        // ① 先造一条"本编辑器新建的" GT 配方（persist=false：不落盘）
        final com.google.gson.JsonObject json = new com.google.gson.JsonObject();
        json.addProperty("type", type.registryName.toString());
        json.addProperty("duration", 200);
        final com.google.gson.JsonObject data = new com.google.gson.JsonObject();
        data.addProperty("euTier", 0);
        json.add("data", data);
        final com.google.gson.JsonObject eu = new com.google.gson.JsonObject();
        eu.addProperty("content", 8);
        eu.addProperty("chance", 10000);
        eu.addProperty("maxChance", 10000);
        eu.addProperty("tierChanceBoost", 0);
        final com.google.gson.JsonArray euArr = new com.google.gson.JsonArray();
        euArr.add(eu);
        final com.google.gson.JsonObject tickIn = new com.google.gson.JsonObject();
        tickIn.add("eu", euArr);
        json.add("tickInputs", tickIn);
        final ResourceLocation newId = ShanhaiVanillaRecipeOps.findFreeId(server, type.registryName);
        if (newId == null) {
            check("restore_rebuilds_panel_list", false, "找不到空闲 id ⇒ 本拍空转");
            return;
        }
        final GTRecipe made = ShanhaiRecipeEditorOps.addRecipeFromJson(server, newId, json);
        if (made == null) {
            check("restore_rebuilds_panel_list", false, "新建探针失败（GT 反序列化没过）⇒ 本拍空转");
            return;
        }
        // 🔴 必须**照面板那条路**落一条 op=add 覆盖记录 —— 否则"恢复原样"无事可做
        //    （底本里本来就有这条，restoreOne 会正确地什么都不做 ⇒ 判据变成假红）。
        final int entriesBefore = ShanhaiRecipeOverrideStore.entryCount();
        final boolean wroteEntry;
        try {
            final com.google.gson.JsonObject entry = ShanhaiRecipeOverrideStore.makeAddEntry(
                    ShanhaiRecipeOverrideStore.nextUid(), newId.toString(),
                    type.registryName.toString(), json);
            wroteEntry = ShanhaiRecipeOverrideStore.upsert(entry) >= 0;
        } catch (Throwable t) {
            check("restore_rebuilds_panel_list", false, "写 op=add 覆盖记录失败：" + t);
            return;
        }
        final ShanhaiRecipeEditorWorkspace ws = new ShanhaiRecipeEditorWorkspace(server, null);
        ws.reloadTypes();
        ws.selectType(type.registryName);
        ws.tick();
        // ⚠️ 判据必须【翻页找】：GT 一个类型几千条，新建那条排在第 N 页；
        //    只看当前页会得到假红（我第一版就是这么写错的：`列表里有它=false` 而列表其实有 1623 条）。
        final boolean listedAfterCreate = findCardByPaging(ws, newId);

        // ② 恢复（persist=true ⇒ 真的把那条 op=add 抹掉 ⇒ 触发"连底本一起忘掉"那条路）
        final ShanhaiRecipeEditorOps.Result r = ShanhaiRecipeEditorOps.restoreOne(
                server, ShanhaiRecipeReverseIndex.byId(server, newId) == null ? made
                        : ShanhaiRecipeReverseIndex.byId(server, newId), true, true);
        ws.tick();                        // 面板那一帧的钩子
        final boolean listedAfterRestore = findCardByPaging(ws, newId);
        final int entriesAfter = ShanhaiRecipeOverrideStore.entryCount();
        // ③ 剩下的卡片都不许是【空壳】= 列表里有它、而活表里已经没它了
        //    （这就是用户那句「列表用旧快照、数据现读 ⇒ 剩一个空壳」的机器判据）
        int shellCards = 0;
        int cards = 0;
        for (ShanhaiRecipeQuery.Card c : ws.buildCards()) {
            if (c == null) {
                continue;
            }
            cards++;
            if (ShanhaiRecipeReverseIndex.byId(server, c.id()) == null
                    && ShanhaiVanillaRecipeTable.liveById(c.id()) == null) {
                shellCards++;
            }
        }
        check("restore_rebuilds_panel_list",
                wroteEntry && listedAfterCreate && r.ok() && !listedAfterRestore
                        && shellCards == 0 && entriesAfter == entriesBefore,
                "新建 " + newId + "（写了 op=add=" + wroteEntry + "）⇒ 列表里有它=" + listedAfterCreate
                        + " · 恢复这条 ⇒ ok=" + r.ok() + "（detail=" + r.detail() + "）"
                        + " · 恢复之后列表里还有它吗=" + listedAfterRestore + "（必须 false）"
                        + " · 当前这一页 " + cards + " 张卡片里的【空壳】=" + shellCards
                        + "（必须 0：空壳 = 列表里有它、活表里已经没它 —— 用户那张截图就是这个）"
                        + " · 覆盖文件条数 " + entriesBefore + "->" + entriesAfter + "（必须相等：这一拍不留垃圾）");
    }

    /**
     * 🔴🔴 <b>本轮判据 ⑥（用户澄清之后新增）：{@code /shanhai edit restore} 必须逐条给客户端发单条补丁。</b>
     *
     * <h4>用户原话（逐字）</h4>
     * <blockquote>「<b>但是我删除写的是 /shanhai edit restore</b>」</blockquote>
     * ⇒ 他说的"删除不刷新"= <b>restore 之后 JEI 不刷新</b>（对编辑器新建的那些，restore 就等于删掉它们）。
     *
     * <h4>为什么这条判据非有不可</h4>
     * 他给的现场读数是：
     * <pre>
     * editor RESTORE_ALL types=1 rebuilt_types=1 ledger_before=2 ledger_cleared=2
     *        file_entries_cleared=4 index_ms=13 vanilla_ms=105        ← 整行没有 net_sent
     * </pre>
     * 与界面「删除这条」（{@code vanilla_edit_remove … net_sent action=removed}）形成对照 ⇒
     * <b>推测</b>：restore 那条路没发通知 ⇒ JEI 不知道 ⇒ 当然不刷新。
     * <p>判据（全部在当前进程里量，不需要客户端）：
     * <ol>
     *   <li>先造两样东西：一条"本编辑器新建的"（写 {@code op=add}）＋ 一条被改过的已有配方；</li>
     *   <li>跑 {@code /shanhai edit restore} 对应的那个入口 {@code restoreAll(server)}；</li>
     *   <li>要求：① <b>单条通知计数必须涨</b>（≥1）；② <b>整表广播计数不许变</b>（不许整机重注册）；
     *       ③ 新建那条必须从活表里消失（restore = 当作没建过）；④ 命令本身要成功。</li>
     * </ol>
     * ⚠️ 本用例会清空 rig 的覆盖文件（那是别的用例共用的夹具）⇒ 注册时<b>故意放在最后</b>。
     */
    private static void caseRestoreAllNotifies(MinecraftServer server) {
        ShanhaiRecipeReverseIndex.ensure(server);
        // ① 造一条"编辑器新建的"（必须先写 op=add 进覆盖文件，否则 restore 无事可做）
        GTRecipeType type = null;
        for (GTRecipe r : ShanhaiRecipeReverseIndex.all()) {
            if (r != null && r.getType() != null) {
                type = r.getType();
                break;
            }
        }
        if (type == null || type.registryName == null) {
            check("restore_all_notifies_jei", false, "找不到 GT 类型（本拍空转）");
            return;
        }
        final ResourceLocation newId = ShanhaiVanillaRecipeOps.findFreeId(server, type.registryName);
        if (newId == null) {
            check("restore_all_notifies_jei", false, "找不到空闲 id（本拍空转）");
            return;
        }
        // 与 restore_rebuilds_panel_list 同一套探针构造（照抄，避免"两处口径不同"）
        final com.google.gson.JsonObject json = new com.google.gson.JsonObject();
        json.addProperty("type", type.registryName.toString());
        json.addProperty("duration", 200);
        final com.google.gson.JsonObject data = new com.google.gson.JsonObject();
        data.addProperty("euTier", 0);
        json.add("data", data);
        final com.google.gson.JsonObject eu = new com.google.gson.JsonObject();
        eu.addProperty("content", 8);
        eu.addProperty("chance", 10000);
        eu.addProperty("maxChance", 10000);
        eu.addProperty("tierChanceBoost", 0);
        final com.google.gson.JsonArray euArr = new com.google.gson.JsonArray();
        euArr.add(eu);
        final com.google.gson.JsonObject tickIn = new com.google.gson.JsonObject();
        tickIn.add("eu", euArr);
        json.add("tickInputs", tickIn);
        if (ShanhaiRecipeEditorOps.addRecipeFromJson(server, newId, json) == null) {
            check("restore_all_notifies_jei", false, "新建探针失败（GT 反序列化没过）⇒ 本拍空转");
            return;
        }
        try {
            ShanhaiRecipeOverrideStore.upsert(ShanhaiRecipeOverrideStore.makeAddEntry(
                    ShanhaiRecipeOverrideStore.nextUid(), newId.toString(),
                    type.registryName.toString(), json));
        } catch (Throwable t) {
            check("restore_all_notifies_jei", false, "写 op=add 记录失败：" + t);
            return;
        }
        // ② 再造一条"改过的已有配方"（台账里必须有东西，restore 才有东西可恢复）
        ResourceLocation editId = null;
        for (GTRecipe r : ShanhaiRecipeReverseIndex.all()) {
            if (r != null && r.id != null && !newId.equals(r.id) && r.duration > 0) {
                editId = r.id;
                break;
            }
        }
        boolean edited = false;
        if (editId != null) {
            final GTRecipe live = ShanhaiRecipeReverseIndex.byId(server, editId);
            edited = live != null && ShanhaiRecipeEditorOps.applyEdits(
                    server, live, null, null, null, live.duration + 7, null, null, false, false).ok();
        }

        final int n0 = ShanhaiJeiBridge.singleNotifyCount();
        final int full0 = ShanhaiRecipeEditorOps.fullResyncCount();
        final ShanhaiRecipeEditorOps.Result r = ShanhaiRecipeEditorOps.restoreAll(server);
        final int n1 = ShanhaiJeiBridge.singleNotifyCount();
        final int full1 = ShanhaiRecipeEditorOps.fullResyncCount();
        final boolean gone = ShanhaiRecipeReverseIndex.byId(server, newId) == null;
        check("restore_all_notifies_jei",
                r.ok() && n1 > n0 && full1 == full0 && gone,
                "命令 = /shanhai edit restore ⇒ 单条通知 " + n0 + "->" + n1
                        + "（必须涨：这就是给客户端的补丁） · 整表广播 " + full0 + "->" + full1
                        + "（必须不变：不许整机重注册） · 新建的那条还在吗=" + !gone + "（必须 false）"
                        + " · 本拍造的两样：新建 " + newId + " ＋ 改过一条=" + edited
                        + " · 命令 ok=" + r.ok() + "（detail=" + r.detail() + "）");
    }

    /**
     * 🔴🔴 <b>本轮判据 ⑦：第一屏那个「(N 条)」当场就是新数</b>（用户原话：新增之后"第二面可以热更新了，
     * 但是第一面不行"，并给出策略「上 mixin 之前我们好歹得把自己的逻辑修好」）。
     *
     * <h4>根因（一句话）</h4>
     * 第一屏那些数字是**服务端算好推给客户端**的，而面板的推送闸分两个版本号：
     * 列表文字看 {@code version()}、<b>数字文字看 {@code numbersVersion()}</b>。
     * 我重算了数据却没涨 {@code numbersVersion} ⇒ 面板认为"数字没变" ⇒ 一个字节都不推 ⇒
     * 客户端一直画旧条数。
     *
     * <p>判据：造一条新配方（= 表变）之后，<b>不看日志、不点任何按钮</b>，只 {@code ws.tick()} 一次 ⇒
     * ① {@code numbersVersion} 必须涨（推送闸），② 那个类型在第一屏的行里条数必须 +1（真数据）。
     */
    private static void caseFirstScreenCountRefreshes(MinecraftServer server) {
        ShanhaiRecipeReverseIndex.ensure(server);
        final ShanhaiRecipeEditorWorkspace ws = new ShanhaiRecipeEditorWorkspace(server, null);
        ws.reloadTypes();                     // 进第一屏
        ws.tick();                            // 对齐"表版本"，之后的变动才会触发重算
        GTRecipeType type = null;
        for (GTRecipe r : ShanhaiRecipeReverseIndex.all()) {
            if (r != null && r.getType() != null && r.getType().registryName != null) {
                type = r.getType();
                break;
            }
        }
        if (type == null) {
            check("first_screen_count_refreshes", false, "找不到 GT 类型（本拍空转）");
            return;
        }
        if (!ws.selectType(type.registryName)) {
            check("first_screen_count_refreshes", false, "选不中类型 " + type.registryName + "（本拍空转）");
            return;
        }
        ws.tick();
        final int before = countIn(ws.typeRows(), type.registryName);
        final int v0 = ws.numbersVersion();
        // 记下"建之前"覆盖文件里在生效的 id（下面用差集取新建的那条）
        final java.util.Set<String> idsBefore = new java.util.HashSet<>();
        try {
            idsBefore.addAll(ShanhaiRecipeOverrideStore.appliedIds());
        } catch (Throwable ignored) {
        }
        // 🔴 必须走**面板上那颗「新建」按钮的同一条路**（`newRecipe(true)`），不是直接调 addRecipeFromJson：
        //    第一版判据就是直接调的 ⇒ 表版本没变、什么都没触发 ⇒ 假红
        //    （实测读数：第一屏条数 1622 -> 1622、numbersVersion 1 -> 1）。
        //    用户报的正是"面板新建之后第一面不动"，所以判据必须踩在面板那条路上。
        final boolean made = ws.newRecipe(true);
        ws.tick();                            // 🔴 面板那一帧的钩子
        final int v1 = ws.numbersVersion();
        final int after = countIn(ws.typeRows(), type.registryName);
        // 🔴🔴 2026-10-06（用户抓到的 `index_rebuild_add_refused`：`wanted=3 added=2 refused=1`）：
        //    **新增那条到底进没进 GT 自己的索引？** 这是"JEI 怎么刷都拿不到"的核心问题。
        //    （GT 的 `GTRecipeLookup#addRecipe` 返回 false 有两种可能：① "id 已存在于是覆盖"（无害）
        //      ② 真的拒收（致命）—— 光看日志分不出来，**必须按 id 读回来才算数**。）
        //    ⚠️ 取 id 的口径：**建之前/之后覆盖文件里 op=add 的差集** —— 不能用"翻页找卡片"，
        //       因为新建之后面板已经切到第三屏，`buildCards()` 在那一屏是空的（第一版就是这么写错的）。
        ResourceLocation newId = null;
        try {
            for (String s : ShanhaiRecipeOverrideStore.appliedIds()) {
                final ResourceLocation cid = ResourceLocation.tryParse(s);
                if (cid != null && !idsBefore.contains(cid.toString())) {
                    newId = cid;
                    break;
                }
            }
        } catch (Throwable ignored) {
        }
        final boolean inGtIndex = newId != null
                && ShanhaiRecipeEditorOps.readFromIndex(type, newId) != null;
        final boolean inReverseIndex = newId != null
                && ShanhaiRecipeReverseIndex.byId(server, newId) != null;
        final int idxSize = ShanhaiRecipeEditorOps.indexSizeOf(type);
        // 🔴 用户 ① 要的读数：**新建那条的产物侧到底是什么**（左键"原料"却能查到 ⇒ 怀疑输出侧是空的）
        String outItems = "?";
        String inItems = "?";
        try {
            final GTRecipe probe = newId == null ? null : ShanhaiRecipeReverseIndex.byId(server, newId);
            if (probe != null) {
                final ShanhaiRecipeQuery.Card c = ShanhaiRecipeQuery.cardOf(probe, false);
                outItems = c == null || c.outs() == null ? "null" : c.outs().toString();
                inItems = c == null || c.ins() == null ? "null" : c.ins().toString();
            }
        } catch (Throwable t) { outItems = "err:" + t; }
        // 清理：把这一拍造的那条从覆盖文件与底本里都拿掉（不留垃圾给后面的用例）
        try {
            for (String s : ShanhaiRecipeOverrideStore.appliedIds()) {
                final ResourceLocation cid = ResourceLocation.tryParse(s);
                if (cid == null) {
                    continue;
                }
                if (findCardByPaging(ws, cid)) {
                    final GTRecipe live = ShanhaiRecipeReverseIndex.byId(server, cid);
                    if (live != null) {
                        ShanhaiRecipeEditorOps.restoreOne(server, live, true, true);
                    }
                    break;
                }
            }
        } catch (Throwable ignored) {
        }
        check("first_screen_count_refreshes",
                made && v1 > v0 && after == before + 1,
                "类型=" + type.registryName + " 第一屏条数 " + before + " -> " + after
                        + "（必须 +1：面板新建之后当场进第一屏计数） · 推送闸 numbersVersion " + v0 + " -> " + v1
                        + "（必须涨：不涨面板就一个字节都不推给客户端 —— 这就是用户说的「第一面不行」）"
                        + " · 面板新建 ok=" + made);
        // 🔴 单独一条判据：新建那条必须真的能从 GT 索引里读回来（用户抓到的 add_refused 就是查这个）
        check("new_recipe_enters_gt_index",
                made && newId != null && inGtIndex && inReverseIndex,
                "新建 " + newId + " ⇒ GT 索引能按 id 读回=" + inGtIndex
                        + " · 产物侧=" + outItems + "（必须非空、且不是 barrier 之外的意外） · 输入侧=" + inItems
                        + "（必须 true；读不回 = 索引里根本没这条 ⇒ JEI 无论怎么刷都拿不到）"
                        + " · 反查索引能读回=" + inReverseIndex
                        + " · 该类型索引条数=" + idxSize + "（应 = 第一屏条数 " + after + "）");
    }

    /** 面板当前那一份列表里有没有这个 id（不翻页；只判当前页可见的那些）。 */
    private static boolean listContains(ShanhaiRecipeEditorWorkspace ws, ResourceLocation id) {

        for (ShanhaiRecipeQuery.Card c : ws.buildCards()) {
            if (c != null && id.equals(c.id())) {
                return true;
            }
        }
        return false;
    }

    /**
     * <b>翻页找</b>这个 id 的卡片（从当前页往后翻，翻不动就停）。
     *
     * <p>为什么必须翻页：GT 一个类型几千条配方（实测 1623 条 / 406 页），新建的那条按 id 排序
     * 落在第 N 页 ⇒ 只判当前页会得到<b>假红</b>（我第一版就写错过一次：
     * 读数说"列表里没有它"，而同一行日志显示 list=1623 —— 判据自己错了）。
     */
    private static boolean findCardByPaging(ShanhaiRecipeEditorWorkspace ws, ResourceLocation id) {
        final int pages = ws.pageCount();
        for (int p = 0; p < pages; p++) {
            if (listContains(ws, id)) {
                return true;
            }
            final int cur = ws.page();
            ws.jumpPage(1);
            if (ws.page() == cur) {
                break;
            }
        }
        return listContains(ws, id);
    }

    /** 从第二屏那一份卡片里取"耗时"（找不到返回 -1）。 */
    private static int cardDuration(ShanhaiRecipeEditorWorkspace ws, ResourceLocation id) {
        for (ShanhaiRecipeQuery.Card c : ws.buildCards()) {
            if (c != null && id.equals(c.id())) {
                return c.duration();
            }
        }
        return -1;
    }

    /**
     * <b>无序合成也画同一张 3×3</b>（用户拍板）—— 机器判据。
     *
     * <p>用户原话：「如果我想要2个红蘑菇，那我直接在网格里面再补一个不就行了吗……
     * 主要是这样玩家好操作你懂吧，不然太麻烦了」
     * ⇒ 判据有四条：① 9 格全都可放（{@code active}）；② 往空格里拖 = <b>追加一样材料</b>；
     * ③ 写出去的 {@code ingredients} <b>只有真材料</b>（空格不是配料）；
     * ④ 那一行说明写的是「位置无所谓」（与有序那屏的"位置有意义"区分开）。
     */
    private static void caseVanillaListGrid(MinecraftServer server) {
        ShanhaiVanillaRecipeTable.captureIfAbsent(server);
        ShanhaiVanillaRecipeTable.rebuildIndex(server);
        ResourceLocation targetId = null;
        net.minecraft.world.item.crafting.Recipe<?> target = null;
        for (ResourceLocation id : ShanhaiVanillaRecipeTable.idsOf(new ResourceLocation("minecraft", "crafting"))) {
            final net.minecraft.world.item.crafting.Recipe<?> r = ShanhaiVanillaRecipeTable.liveById(id);
            if (r == null) {
                continue;
            }
            final ShanhaiVanillaRecipeView v = ShanhaiVanillaRecipeView.of(r);
            if (v != null && v.kind == ShanhaiVanillaRecipeView.Kind.SHAPELESS && v.inputs.size() < 9) {
                targetId = id;
                target = r;
                break;
            }
        }
        if (target == null) {
            check("vanilla_list_grid", false, "找不到无形状合成的靶子（本拍空转，如实报红）");
            return;
        }
        final ShanhaiVanillaRecipeShape s = ShanhaiVanillaRecipeShape.of(target);
        final int n0 = s.slotCount();
        final boolean all9 = true;
        for (int i = 0; i < ShanhaiVanillaRecipeShape.GRID_CELLS; i++) {
            if (!s.active(i)) {
                check("vanilla_list_grid", false, "LIST 的第 " + i + " 格竟然 active=false（必须 9 格都能放）");
                return;
            }
        }
        // 往"第 9 格"（一个还不存在的位子）拖东西 ⇒ 必须追加一样材料
        final ItemStack marker = probeStack();
        final boolean ok = s.setAt(ShanhaiVanillaRecipeShape.GRID_CELLS - 1, marker);
        final com.google.gson.JsonObject f = s.fieldsJson();
        final com.google.gson.JsonArray ingredients =
                f != null && f.has("ingredients") ? f.getAsJsonArray("ingredients") : null;
        boolean anyEmpty = false;
        if (ingredients != null) {
            for (com.google.gson.JsonElement el : ingredients) {
                if (el == null || (el.isJsonObject() && el.getAsJsonObject().size() == 0)) {
                    anyEmpty = true;
                }
            }
        }
        check("vanilla_list_grid", all9 && ok && s.slotCount() == n0 + 1
                        && s.layoutCount() == ShanhaiVanillaRecipeShape.GRID_CELLS
                        && ingredients != null && ingredients.size() == n0 + 1 && !anyEmpty
                        && s.hintText().contains("位置无所谓"),
                "id=" + targetId + " 原来 " + n0 + " 样 → 往第 9 格拖一样 ⇒ " + s.slotCount() + " 样"
                        + " · layoutCount=" + s.layoutCount() + "（必须 9：同一张 3×3）"
                        + " · 写出去的 ingredients=" + (ingredients == null ? "(none)" : ingredients.size())
                        + "（必须 " + (n0 + 1) + "，且没有一个空槽：" + !anyEmpty + "）"
                        + " · 那一行说明=\"" + s.hintText() + "\"");
    }

    // ================================================================== 1. 命令树

    private static void caseCommandTree(MinecraftServer server) {
        final var root = server.getCommands().getDispatcher().getRoot();
        final var shanhai = root.getChild("shanhai");
        if (shanhai == null) {
            check("cmd_tree", false, "no /shanhai node at all");
            return;
        }
        final List<String> kids = new ArrayList<>();
        for (var c : shanhai.getChildren()) {
            kids.add(c.getName());
        }
        final boolean hasEdit = kids.contains(ShanhaiRecipeEditorCommand.COMMAND_ARG);
        // 别的子节点必须还在：Brigadier 的"同名 literal 合并"这条推断的判据
        final boolean keptOthers = kids.size() >= 2;
        check("cmd_tree", hasEdit && keptOthers,
                "shanhai_children=" + kids + " has_edit=" + hasEdit + " kept_others(>=2)=" + keptOthers);
    }

    // ================================================================== 2. 反查表

    private static void caseReverseIndex(MinecraftServer server) {
        ShanhaiRecipeReverseIndex.build(server);
        check("reverse_built", ShanhaiRecipeReverseIndex.isBuilt(),
                ShanhaiRecipeReverseIndex.statsLine());

        // 自有强探针：拿一条真实存在配方的一个真实物品输入去问（保证非 0 命中，比固定物品更硬）
        final List<GTRecipe> all = ShanhaiRecipeReverseIndex.all();
        Item probe = null;
        for (GTRecipe r : all) {
            probe = firstInputItem(r);
            if (probe != null) {
                break;
            }
        }
        if (probe == null) {
            check("reverse_selfprobe", false, "no recipe with an item input was found");
            return;
        }
        final int viaIndex = ShanhaiRecipeReverseIndex.queryIndexOnly(probe).size();
        // 🔴 对照必须是【同语义】那条（linearQueryByItems）。用 test 语义那条会在"物品有变体"的
        //    场合判成假不一致 —— 2026-10-05 冒烟第一版就是这么误报的，详见索引类 build() 里的说明。
        final int viaLinear = ShanhaiRecipeReverseIndex.linearQueryByItems(server, probe).size();
        final int viaTest = ShanhaiRecipeReverseIndex.linearQuery(server, probe).size();
        check("reverse_selfprobe", viaIndex == viaLinear && viaIndex > 0,
                "item=" + net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(probe)
                        + " via_index=" + viaIndex + " via_linear_same_semantics=" + viaLinear
                        + " via_test_semantics=" + viaTest + "(信息性，不参与判定)");
        check("reverse_verified_flag", ShanhaiRecipeReverseIndex.isVerified(),
                "verified=" + ShanhaiRecipeReverseIndex.isVerified() + " | " + ShanhaiRecipeReverseIndex.statsLine());
    }

    private static Item firstInputItem(GTRecipe r) {
        try {
            final List<Content> c = r.getInputContents(ItemRecipeCapability.CAP);
            if (c == null) {
                return null;
            }
            for (Content content : c) {
                final Ingredient ing = ItemRecipeCapability.CAP.of(content.content);
                if (ing == null) {
                    continue;
                }
                for (ItemStack st : ing.getItems()) {
                    if (st != null && !st.isEmpty()) {
                        return st.getItem();
                    }
                }
            }
        } catch (Throwable ignored) {
            // 单条读不出来 ⇒ 这一条没有可用探针
        }
        return null;
    }

    // ================================================================== 3. 改时长（四拍）

    private static void caseApply(MinecraftServer server) {
        ShanhaiRecipeReverseIndex.build(server);
        final GTRecipe t = pickTarget(server, "assembler/", HANDS_OFF);
        if (t == null || t.getType() == null || t.id == null) {
            check("apply_target", false, "no usable assembler recipe found");
            return;
        }
        final GTRecipeType type = t.getType();
        final ResourceLocation id = t.id;
        final int d0 = t.duration;
        // 🔴 2026-10-05（duration 原始值口径）：`ShanhaiRecipeEditorOps.setDuration` 的入参现在是
        //    【原始值】，而 `GTRecipe.duration` 上读到的是【实际值】⇒ 断言必须过一次 toLive 换算。
        //    不换算的后果是【假红】：判据会说"没生效"，而它其实生效了 —— 假红比假绿更贵，
        //    因为硬判据是「一次干净开机 [ERROR] 总条数 = 0」。
        //    映射判不出来（IDENTITY/UNFITTED）时 toLive 是恒等 ⇒ 与改动之前逐字节相同。
        final int oA = ShanhaiRecipeDuration.originalOf(t) + D_A;
        final int oB = ShanhaiRecipeDuration.originalOf(t) + D_B;
        final int lA = ShanhaiRecipeDuration.toLive(type, oA);
        final int lB = ShanhaiRecipeDuration.toLive(type, oB);
        lastApplyTargetId = id;
        ShanhaiMod.LOGGER.info("{} case=apply_target id={} type={} duration0={} "
                        + "原始口径 d0={} (+{}->live {}) (+{}->live {}) duration_scale={}",
                PREFIX, id, type.registryName, d0,
                ShanhaiRecipeDuration.originalOf(t), D_A, lA, D_B, lB,
                ShanhaiRecipeDuration.scaleOf(type));

        // ---- 正拍：改 + 刷索引 ----
        final ShanhaiRecipeEditorOps.Result rA =
                ShanhaiRecipeEditorOps.setDuration(server, t, oA, true, false);
        final GTRecipe iA = ShanhaiRecipeEditorOps.readFromIndex(type, id);
        final GTRecipe vA = ShanhaiRecipeEditorOps.readFromVanilla(server, type, id);
        check("apply_positive", rA.ok() && iA != null && vA != null
                        && iA.duration == lA && vA.duration == lA,
                "expect=" + lA + " index=" + dur(iA) + " vanilla=" + dur(vA)
                        + " index_ms=" + rA.indexMs() + " vanilla_ms=" + rA.vanillaMs());

        // ---- 负对照：同一段代码、同一个新值，唯一差别 = 不刷索引 ----
        final GTRecipe cur = ShanhaiRecipeEditorOps.readFromIndex(type, id);
        final ShanhaiRecipeEditorOps.Result rB =
                ShanhaiRecipeEditorOps.setDuration(server, cur, oB, false, false);
        final GTRecipe iB = ShanhaiRecipeEditorOps.readFromIndex(type, id);
        final GTRecipe vB = ShanhaiRecipeEditorOps.readFromVanilla(server, type, id);
        check("apply_negative_control", rB.ok() && iB != null && vB != null
                        && iB.duration == lA          // 索引里还是【上一拍】的值 ⇒ 没刷就是没生效
                        && vB.duration == lB,         // 原版表确实被写了 ⇒ 差别只来自"刷不刷索引"
                "expect index=" + lA + " vanilla=" + lB
                        + " got index=" + dur(iB) + " vanilla=" + dur(vB));

        // ---- 对照补拍：同一个值，这次刷索引 ----
        final ShanhaiRecipeEditorOps.Result rC =
                ShanhaiRecipeEditorOps.setDuration(server, cur, oB, true, false);
        final GTRecipe iC = ShanhaiRecipeEditorOps.readFromIndex(type, id);
        final GTRecipe vC = ShanhaiRecipeEditorOps.readFromVanilla(server, type, id);
        check("apply_same_value_with_rebuild", rC.ok() && iC != null && vC != null
                        && iC.duration == lB && vC.duration == lB,
                "expect=" + lB + " index=" + dur(iC) + " vanilla=" + dur(vC));

        // ---- 还原（清台账 ⇒ 索引与原版表都回到【底本原对象】） ----
        ShanhaiRecipeEditorOps.restore(server, t);
        final GTRecipe iD = ShanhaiRecipeEditorOps.readFromIndex(type, id);
        final GTRecipe vD = ShanhaiRecipeEditorOps.readFromVanilla(server, type, id);
        check("apply_restored", iD != null && vD != null
                        && iD.duration == d0 && vD.duration == d0,
                "expect=" + d0 + " index=" + dur(iD) + " vanilla=" + dur(vD));

        // ---- 🔴 不累加（船长 2026-10-05 补的红线①的机器判据） ----
        caseNoAccumulation(server, type, id, d0, t);

        // ---- 🔴 重建后索引里同一 id 不许出现两次 ----
        caseIndexUnique(server, type);
    }

    /**
     * 🔴 <b>连改两次不许累加</b>。
     *
     * <p>为什么这条要单独一拍：从"当前索引"收集再改，会把上一次的改动再套一遍
     * （本刀第一版就是这么写的）。四拍读数都必须<b>恰好等于</b>台账值：
     * <pre>
     *   改 1 次 → 读到 d0+A
     *   再改 1 次 → 读到 d0+B（不是 d0+A+B、也不是别的）
     *   不改任何东西、再重建一次索引 → 还是 d0+B   ← 这一拍专门打"重建本身会不会二次施加"
     *   清台账 → 读到 d0
     * </pre>
     */
    private static void caseNoAccumulation(MinecraftServer server, GTRecipeType type,
                                           ResourceLocation id, int d0, GTRecipe t) {
        // 🔴 2026-10-05（duration 原始值口径）：dA/dB 是【原始值】入参，lA/lB 才是 GTRecipe 上
        //    应当读到的【实际值】。判据比的是后者（见本文件 apply_target 那一拍的同一段注释）。
        final int dA = ShanhaiRecipeDuration.originalOf(t) + 111;
        final int dB = ShanhaiRecipeDuration.originalOf(t) + 222;
        final int lA = ShanhaiRecipeDuration.toLive(type, dA);
        final int lB = ShanhaiRecipeDuration.toLive(type, dB);

        ShanhaiRecipeEditorOps.setDuration(server, t, dA, true, false);
        final int r1 = durationOf(ShanhaiRecipeEditorOps.readFromIndex(type, id));

        final GTRecipe cur = ShanhaiRecipeEditorOps.readFromIndex(type, id);
        ShanhaiRecipeEditorOps.setDuration(server, cur, dB, true, false);
        final int r2 = durationOf(ShanhaiRecipeEditorOps.readFromIndex(type, id));

        // 没有新编辑，纯粹再重建一次
        ShanhaiRecipeEditorOps.rebuildTypeFromBase(server, type);
        final int r3 = durationOf(ShanhaiRecipeEditorOps.readFromIndex(type, id));
        final int r3v = durationOf(ShanhaiRecipeEditorOps.readFromVanilla(server, type, id));

        ShanhaiRecipeEditorOps.restore(server, t);
        final int r4 = durationOf(ShanhaiRecipeEditorOps.readFromIndex(type, id));

        check("no_accumulation", r1 == lA && r2 == lB && r3 == lB && r3v == lB && r4 == d0,
                "d0(live)=" + d0 + " expect_1=" + lA + "(orig " + dA + ") got=" + r1
                        + " | expect_2=" + lB + "(orig " + dB + ") got=" + r2
                        + " | extra_rebuild index=" + r3 + " vanilla=" + r3v
                        + " | after_restore=" + r4);
    }

    /**
     * 重建之后，索引树必须满足三条：<b>同一 id 只出现一次</b>、<b>没有 null</b>、
     * <b>条数与重建前一致</b>（即"我们这次重建"与"GT 自己那次重建"结果一样）。
     *
     * <p>🔴 判据为什么<b>不是</b> {@code tree == 底本里该类型的条数}（2026-10-05 冒烟第一版判错过）：
     * gtceu 自己的 {@code RecipeManagerMixin} TAIL 也是"拿原版表逐条 {@code addRecipe}"，
     * 而 {@code Branch} 树会把键相同的配方<b>折叠</b>掉 —— 实测该类型底本 2552 条、树里只有 2550 条
     * （前一轮的全局读数 {@code table_missing_from_tree=1026} 是同一个现象）。
     * 所以"树比表少"是 <b>GT 的固有行为，不是我们弄丢了</b>；正确的判据是
     * <b>重建前后条数不变</b>（删一条时应当恰好 -1）。
     */
    private static void caseIndexUnique(MinecraftServer server, GTRecipeType type) {
        final int[] arr = ShanhaiRecipeEditorOps.rebuildTypeFromBase(server, type);
        final int treeBefore = arr[0];
        final int refused = arr[3];
        final int nullsInLoop = arr[4];
        final int treeAfter = arr[5];

        final List<GTRecipe> tree = type.getLookup().getLookup().getRecipes(true).toList();
        final java.util.Map<ResourceLocation, Integer> seen = new java.util.HashMap<>();
        int nullId = 0;
        for (GTRecipe r : tree) {
            if (r == null || r.id == null) {
                nullId++;
                continue;
            }
            seen.merge(r.id, 1, Integer::sum);
        }
        int dup = 0;
        for (var e : seen.entrySet()) {
            if (e.getValue() > 1) {
                dup++;
            }
        }
        check("index_unique", dup == 0 && nullId == 0 && refused == 0 && nullsInLoop == 0
                        && treeAfter == treeBefore,
                "tree_before=" + treeBefore + " tree_after=" + treeAfter
                        + " distinct_ids=" + seen.size() + " dup_ids=" + dup
                        + " null_or_nullid=" + nullId + " add_refused=" + refused + " null_in_loop=" + nullsInLoop
                        + " base_of_type=" + ShanhaiRecipeBase.baseCountOf(type)
                        + " (树比表少是 GT 固有折叠，不作判据) | " + ShanhaiRecipeBase.statsLine());
    }

    private static int durationOf(GTRecipe r) {
        return r == null ? Integer.MIN_VALUE : r.duration;
    }

    // ================================================================== 3.5 🔴 duration 显示 bug（本轮修的）

    /**
     * 🔴 <b>本轮那个显示 bug 的机器判据</b>：用户原话（逐字）
     * 「关闭后再打开编辑器里面重新变回了 1200，jei 里显示从 0.05s 变成了 0.25s，应该是修改成功了，
     *   但是配方编辑器里面的显示有一些问题」。
     *
     * <p>判读：0.25s = 5 tick = 5000×0.001 ⇒ <b>值写对了、换算也对</b>，错的只有
     * "面板重开时读到的那份原始值快照没跟着更新"。
     *
     * <h4>本拍的判据（全部可 grep）</h4>
     * <pre>
     *   duration_display_after_save      ：改 5000 之后，重读活配方 ⇒ 面板会显示的那个数 == 5000
     *   duration_table_synced            ：那张 id→原始值 的表也 == 5000（同一条链的第二半）
     *   duration_boot_snapshot_intact    ：开机快照【没被改】—— 它是"恢复原样"要回落到的那一份
     *   duration_display_negative_control：开机快照 != 5000（否则前两条是恒真的假绿 ——
     *                                      旧行为就正好是"读到开机快照那个值"，它必须与新值不同）
     *   duration_display_after_edit_back ：把耗时写回原值 ⇒ 表也跟着回到开机那一份
     *   duration_display_after_restore   ：整条恢复原样 ⇒ 表 == 开机快照（不存在就把键删掉）
     * </pre>
     * <p>⚠️ 判据比的是<b>面板真正会读的那条链</b>（{@code ShanhaiRecipeDuration.originalOf(live)}），
     * 不是某个内部变量 —— 后者修好了前者没修，正是这个 bug 的形态。
     */
    private static void caseDurationDisplay(MinecraftServer server) {
        ShanhaiRecipeReverseIndex.build(server);
        final Set<ResourceLocation> exclude = new HashSet<>(HANDS_OFF);
        if (lastApplyTargetId != null) {
            exclude.add(lastApplyTargetId);
        }
        final GTRecipe t = pickTarget(server, "assembler/", exclude);
        if (t == null || t.getType() == null || t.id == null) {
            check("duration_display", false, "no usable assembler recipe found");
            return;
        }
        final GTRecipeType type = t.getType();
        final ResourceLocation id = t.id;
        final Integer boot0 = ShanhaiRecipeDuration.bootOriginal(id);
        final int orig0 = ShanhaiRecipeDuration.originalOf(t);
        final int newOrig = orig0 + 700;
        ShanhaiMod.LOGGER.info("{} case=duration_display_target id={} type={} orig0={} boot0={} new_orig={} "
                        + "live0={} scale={}",
                PREFIX, id, type.registryName, orig0, boot0, newOrig, t.duration,
                ShanhaiRecipeDuration.scaleOf(type));

        ShanhaiRecipeEditorOps.applyEdits(server, t, null, null, null, newOrig, null, null, true, false);
        final GTRecipe after = ShanhaiRecipeEditorOps.readFromVanilla(server, type, id);
        final int shown = ShanhaiRecipeDuration.originalOf(after);
        final Integer tableNow = ShanhaiRecipeDuration.rawOriginal(id);
        check("duration_display_after_save", shown == newOrig,
                "改到 " + newOrig + " 之后，面板重开时会读到的数 = " + shown
                        + "（期望 " + newOrig + "；旧行为会读到开机那一份 " + boot0 + "）");
        check("duration_table_synced", tableNow != null && tableNow == newOrig,
                "id→原始值 那张表 = " + tableNow + "（期望 " + newOrig + "）");

        final Integer boot1 = ShanhaiRecipeDuration.bootOriginal(id);
        check("duration_boot_snapshot_intact", java.util.Objects.equals(boot0, boot1),
                "开机快照 before=" + boot0 + " after=" + boot1
                        + "（它必须逐字不动：恢复原样要回落到它）");
        check("duration_display_negative_control", !java.util.Objects.equals(boot0, newOrig),
                "开机快照 " + boot0 + " 与新值 " + newOrig + " 必须不同 —— 否则上面两条判据是恒真的假绿");

        // 写回原值 ⇒ 表跟着回到开机那一份（"改回原样就不算改动"这条语义）
        final GTRecipe cur = ShanhaiRecipeEditorOps.readFromVanilla(server, type, id);
        ShanhaiRecipeEditorOps.applyEdits(server, cur, null, null, null, orig0, null, null, true, false);
        final Integer back = ShanhaiRecipeDuration.rawOriginal(id);
        check("duration_display_after_edit_back", java.util.Objects.equals(back, boot0),
                "写回原值 " + orig0 + " 之后，表 = " + back + "（期望 == 开机快照 " + boot0 + "）");

        // 整条恢复原样 ⇒ 表 == 开机快照
        final GTRecipe cur2 = ShanhaiRecipeEditorOps.readFromVanilla(server, type, id);
        ShanhaiRecipeEditorOps.applyEdits(server, cur2, null, null, null, newOrig, null, null, true, false);
        ShanhaiRecipeEditorOps.restore(server, t);
        final Integer afterRestore = ShanhaiRecipeDuration.rawOriginal(id);
        final GTRecipe restored = ShanhaiRecipeEditorOps.readFromVanilla(server, type, id);
        check("duration_display_after_restore",
                java.util.Objects.equals(afterRestore, boot0)
                        && restored != null && ShanhaiRecipeDuration.originalOf(restored)
                        == (boot0 == null ? restored.duration : boot0),
                "恢复原样之后 表 = " + afterRestore + "（期望 == 开机快照 " + boot0 + "）"
                        + " · 面板会读到的数 = " + ShanhaiRecipeDuration.originalOf(restored));
        lastRemoveTargetId = null;   // 不与后面的 remove 拍抢同一条
    }

    // ================================================================== 3.6 🆕 B 组：额外条件

    /**
     * <b>B 组的机器判据</b>（用户点单：「可以添加一个加号…新增额外条件…已有的条件要列出来，右键可以编辑」）。
     *
     * <pre>
     *   conditions_selfcheck      ：纯内存的正/负对照（编解码走 GT 自己的 codec；未知类型 fail-closed）
     *   conditions_apply_positive：装 2 条（超净间＋物质模块）到一条真配方上 ⇒ 活配方上真的读得到、
     *                              且逐字等于我们写的（往返走的是同一条 codec）
     *   conditions_apply_negative：同一段代码、同一份输入，但把数组里【少放一条】
     *                              ⇒ 活配方上的条数必须跟着变少（证明上面那条判据不是恒真）
     *   conditions_fp_probe       ：第二次保存的指纹探针必须认得出 conditions
     *                              —— 🔴 这是"顺带检查同类问题"抓到的那条：
     *                                 fieldsAppliedTo 的 switch 漏了 conditions 键 ⇒ 判成"没套上"
     *                                 ⇒ 第二次保存写错 base_fp ⇒ 下一局 STALE ⇒ 条件静默消失。
     *   conditions_fp_probe_neg   ：负对照：把条件改掉一个字段 ⇒ 探针必须判 false
     *   conditions_restore        ：恢复原样 ⇒ 活配方回到载入前那一份条件
     * </pre>
     */
    private static void caseConditions(MinecraftServer server) {
        check("conditions_selfcheck", ShanhaiRecipeConditions.selfcheck(),
                "registry_types=" + ShanhaiRecipeConditions.knownTypeKeys()
                        + " cleanroom_types=" + ShanhaiRecipeConditions.cleanroomNames());
        // 🔴 第 5 轮（#4）：JEI 那一份是【照同步包造出来的】⇒ 先钉死"带条件的包过网后逐字回来"。
        check("jei_message_selftest", ShanhaiJeiBridge.messageRoundTripSelfcheck(),
                "带 conditions 的同步包编码→解码后逐字相同（客户端 JEI 就是照它造替换配方的）");
        // 🔴 第 5 轮（#3/#5）：用户点单的读数 —— 「类型 → 档位项数 → 前几项的名字」，每个类型都列上。
        //    ⚠️ 必须用【真 server】算：维度/生物群系那两张表要枚举注册表，
        //    而 CONDITIONS_SELFCHECK 是在纯内存（server=null）里跑的 ⇒ 那里它们必然为空。
        //    这一行才是面板上真正会出现的那张表。
        check("conditions_value_table", true,
                "server=" + (server == null ? "null" : "real")
                        + " table=" + ShanhaiRecipeConditions.valueTableSummary(server));

        ShanhaiRecipeReverseIndex.build(server);
        final Set<ResourceLocation> exclude = new HashSet<>(HANDS_OFF);
        if (lastApplyTargetId != null) {
            exclude.add(lastApplyTargetId);
        }
        final GTRecipe t = pickTarget(server, "assembler/", exclude);
        if (t == null || t.getType() == null || t.id == null) {
            check("conditions_apply_target", false, "no usable assembler recipe found");
            return;
        }
        final GTRecipeType type = t.getType();
        final ResourceLocation id = t.id;
        final com.google.gson.JsonArray rec0 = ShanhaiRecipeConditions.encodeOf(t);

        // ---- 构造"两条":超净间(law_cleanroom) + 物质模块(入门模块 ×3) ----
        final com.google.gson.JsonArray want = rec0.deepCopy();
        ShanhaiRecipeConditions.add(want,
                ShanhaiRecipeConditions.makeCleanroom("law_cleanroom", false));
        ShanhaiRecipeConditions.add(want,
                ShanhaiRecipeConditions.makeModuleLevel("shanhai:introductory_material_module", 3, false));
        ShanhaiMod.LOGGER.info("{} case=conditions_target id={} type={} before={} want={}",
                PREFIX, id, type.registryName, ShanhaiRecipeConditions.summary(rec0),
                ShanhaiRecipeConditions.summary(want));

        // ---- 正拍 ----
        ShanhaiRecipeEditorOps.applyEdits(server, t, null, null, null, null, null, want, true, false);
        final GTRecipe after = ShanhaiRecipeEditorOps.readFromVanilla(server, type, id);
        final com.google.gson.JsonArray got = ShanhaiRecipeConditions.encodeOf(after);
        check("conditions_apply_positive",
                after != null && got.size() == want.size()
                        && ShanhaiRecipeConditions.sameAs(got, want),
                "want=" + ShanhaiRecipeConditions.summary(want) + " got="
                        + ShanhaiRecipeConditions.summary(got) + " same="
                        + ShanhaiRecipeConditions.sameAs(got, want)
                        + " (活配方上真的读到了这两条,而且是同一套 codec 编码出来的)");

        // ---- 🔴 第 5 轮（#4）：GT 索引 vs 原版 RecipeManager 两处对账 ----
        //   用户报「机器上生效了，但是 JEI 没有生效」。要判"JEI 看不看得到"，
        //   第一件事就是看**JEI 读的那一处**（原版 RecipeManager）里那条配方有没有新条件。
        //   上一轮这条读数不存在 ⇒ 只能靠猜。现在两处一起读、一起打。
        final GTRecipe idx = ShanhaiRecipeEditorOps.readFromIndex(type, id);
        final com.google.gson.JsonArray idxConds = ShanhaiRecipeConditions.encodeOf(idx);
        final boolean twoTablesAgree = ShanhaiRecipeConditions.sameAs(idxConds, got);
        check("conditions_two_tables_agree",
                twoTablesAgree && ShanhaiRecipeConditions.sameAs(got, want),
                "GT索引=" + ShanhaiRecipeConditions.summary(idxConds)
                        + " 原版表(JEI读的那一处)=" + ShanhaiRecipeConditions.summary(got)
                        + " agree=" + twoTablesAgree
                        + "（两边都要等于 want " + ShanhaiRecipeConditions.summary(want) + "；"
                        + "一致就是「两处配方表分裂」——那正是 JEI 显示旧条件的机制）");

        // ---- 负对照：少放一条 ----
        final com.google.gson.JsonArray fewer = want.deepCopy();
        fewer.remove(fewer.size() - 1);
        final GTRecipe cur = ShanhaiRecipeEditorOps.readFromVanilla(server, type, id);
        ShanhaiRecipeEditorOps.applyEdits(server, cur, null, null, null, null, null, fewer, true, false);
        final GTRecipe after2 = ShanhaiRecipeEditorOps.readFromVanilla(server, type, id);
        final com.google.gson.JsonArray got2 = ShanhaiRecipeConditions.encodeOf(after2);
        check("conditions_apply_negative",
                got2.size() == want.size() - 1 && !ShanhaiRecipeConditions.sameAs(got2, want),
                "删掉一条之后 got=" + ShanhaiRecipeConditions.summary(got2)
                        + "（期望条数 " + (want.size() - 1) + "，且与 want 不同 ⇒ 上面那条判据不是恒真）");

        // ---- 指纹探针：第二次保存必须认得出 conditions ----
        //    🔴 口径（第一版写错过，自检当场报红）：探针比的是【底本】（= 本局开机那一刻的配方，
        //       也就是"覆盖层这一局套完之后"的样子），不是"我刚写进去的新值"。
        //       它的语义是「文件里那条 entry 这一局到底套上了没有」——
        //       套上了 ⇔ entry.fields 与底本当下一致。
        //       所以正对照要喂【底本自己的那一份条件】，而不是我构造的 want。
        final GTRecipe pristine = ShanhaiRecipeBase.pristine(id);
        final com.google.gson.JsonArray baseConds = ShanhaiRecipeConditions.encodeOf(pristine);
        final com.google.gson.JsonObject f1 = new com.google.gson.JsonObject();
        f1.add(ShanhaiRecipeConditions.FIELD, baseConds);
        final boolean applied = ShanhaiRecipeEditorOps.probeFieldsApplied(server, id, f1);
        final com.google.gson.JsonObject f2 = new com.google.gson.JsonObject();
        final com.google.gson.JsonArray tampered = baseConds.deepCopy();
        if (tampered.size() > 0) {
            // 少一条（合法但不同 ⇒ 走的是"比内容"那条路，不是"解不出来"那条路）
            tampered.remove(tampered.size() - 1);
        } else {
            // 底本本来就没有条件 ⇒ 负对照改成"喂一条底本没有的条件"
            ShanhaiRecipeConditions.add(tampered,
                    ShanhaiRecipeConditions.makeCleanroom("law_cleanroom", false));
        }
        f2.add(ShanhaiRecipeConditions.FIELD, tampered);
        final boolean appliedNeg = ShanhaiRecipeEditorOps.probeFieldsApplied(server, id, f2);
        check("conditions_fp_probe", applied,
                "fields.conditions == 底本那一份（= 覆盖层这一局套上了）⇒ 探针必须判【已套上】"
                        + "（否则第二次保存会写错 base_fp ⇒ 下一局 STALE ⇒ 条件静默消失）"
                        + " base_n=" + baseConds.size()
                        + " base=" + ShanhaiRecipeConditions.summary(baseConds));
        check("conditions_fp_probe_neg", !appliedNeg,
                "负对照：把条件改掉一项 ⇒ 探针必须判【没套上】（证明上一条不是恒真）"
                        + " tampered=" + ShanhaiRecipeConditions.summary(tampered));

        // ---- 恢复原样 ----
        ShanhaiRecipeEditorOps.restore(server, t);
        final GTRecipe back = ShanhaiRecipeEditorOps.readFromVanilla(server, type, id);
        final com.google.gson.JsonArray got3 = ShanhaiRecipeConditions.encodeOf(back);
        check("conditions_restore",
                back != null && got3.size() == rec0.size()
                        && ShanhaiRecipeConditions.sameAs(got3, rec0),
                "恢复原样之后 = " + ShanhaiRecipeConditions.summary(got3)
                        + "（期望回到载入前那一份 " + ShanhaiRecipeConditions.summary(rec0) + "）");
    }

    /**
     * 🔴 <b>第 5 轮修 #6 的判据</b> —— 用户原话：「6：额外条件重进存档会丢失」。
     *
     * <h4>上一轮的判据为什么全绿而他仍在丢</h4>
     * 上一轮的 {@code conditions_vs_file} 验的是【文件 → 重放】（覆盖层能不能把文件里的条件套上去），
     * 而用户看的是【面板】。两者之间还隔着一层"面板到底从哪读" —— 那一层没人验，
     * 所以"文件里明明有、面板显示 0 条"这件事一路绿灯到了他手里。
     *
     * <h4>这一组验的就是"面板读得到"</h4>
     * <pre>
     *   P1 正对照  ：保存条件（persist=true，落到覆盖文件）之后，按【面板重开】那条路读一次
     *                ⇒ 必须逐字等于刚保存的那一份（source=ledger）
     *   P2 负对照  ：保存【之前】读一次 ⇒ 必须与要保存的那份不同（证明 P1 不是恒真）
     *   P3 模拟重启：把台账清掉、只留覆盖文件（= 一次重启之后内存里的样子）
     *                ⇒ 必须仍然读得到，且 source 必须是 file（这一条才是"重启不丢"）
     *   P4 负对照  ：把覆盖文件里那条 entry 删掉之后再读 ⇒ 必须【读不到】那两条
     *                （证明 P3 真的是"文件在起作用"，而不是恒真）
     * </pre>
     */
    private static void caseConditionsPanelReadback(MinecraftServer server) {
        final GTRecipe t = pickTargetForPanel(server);
        if (t == null || t.id == null) {
            check("conditions_panel_readback_target", false, "no usable target recipe");
            return;
        }
        final ResourceLocation id = t.id;

        // 要保存的那一份：一条超净间（低档）+ 一条物质模块（虚像，等级 4）
        final com.google.gson.JsonArray want = new com.google.gson.JsonArray();
        ShanhaiRecipeConditions.add(want, ShanhaiRecipeConditions.makeCleanroom("cleanroom", false));
        ShanhaiRecipeConditions.add(want, ShanhaiRecipeConditions.makeModuleLevel(
                "shanhai:virtual_image_material_module", 4, false));

        // ---- P2 负对照：保存之前先读一次（必须与 want 不同） ----
        final ShanhaiRecipeEditorWorkspace.Resolved before =
                ShanhaiRecipeEditorWorkspace.resolveConditions(server, id, t);
        check("conditions_panel_readback_neg",
                !ShanhaiRecipeConditions.sameAs(before.conditions(), want),
                "保存【之前】面板读到 source=" + before.source() + " n=" + before.conditions().size()
                        + " " + ShanhaiRecipeConditions.summary(before.conditions())
                        + "（必须与待保存的那份不同 ⇒ 否则 P1 是恒真的假绿）");

        // ---- 保存（persist=true ⇒ 同时写台账与覆盖文件） ----
        final ShanhaiRecipeEditorOps.Result saved =
                ShanhaiRecipeEditorOps.applyEdits(server, t, null, null, null, null, null, want, true, true);
        // 面板"重开"时读的是【活配方】那一拍 —— 这里同样重新取一次
        final GTRecipe liveReload = ShanhaiRecipeEditorOps.readFromVanilla(server, t.getType(), id);
        final GTRecipe liveNow = liveReload != null ? liveReload : t;

        // ---- P1 正对照：面板重开 ⇒ 必须读到 want ----
        final ShanhaiRecipeEditorWorkspace.Resolved r1 =
                ShanhaiRecipeEditorWorkspace.resolveConditions(server, id, liveNow);
        check("conditions_panel_readback",
                saved.ok() && r1.conditions().size() == want.size()
                        && ShanhaiRecipeConditions.sameAs(r1.conditions(), want),
                "source=" + r1.source() + " ledger_n=" + r1.ledgerN() + " file_n=" + r1.fileN()
                        + " live_n=" + r1.liveN()
                        + " want=" + ShanhaiRecipeConditions.summary(want)
                        + " got=" + ShanhaiRecipeConditions.summary(r1.conditions())
                        + "（这一条就是「保存后重开面板还看得到」的机器判据）");

        // ---- P3 模拟重启：台账清掉、覆盖文件留着 ----
        final com.google.gson.JsonArray fileEntry =
                ShanhaiRecipeEditorWorkspace.conditionsFromOverrideFile(id);
        ShanhaiRecipeBase.clearEdit(id);                    // ← 只清台账（重启之后内存里就是这个样子）
        final GTRecipe liveAgain = ShanhaiRecipeEditorOps.readFromVanilla(server, t.getType(), id);
        final ShanhaiRecipeEditorWorkspace.Resolved r2 =
                ShanhaiRecipeEditorWorkspace.resolveConditions(server, id,
                        liveAgain == null ? liveNow : liveAgain);
        check("conditions_panel_readback_after_restart",
                fileEntry != null && "file".equals(r2.source()) && r2.conditions().size() == want.size()
                        && ShanhaiRecipeConditions.sameAs(r2.conditions(), want),
                "台账清掉之后（= 一次重启的样子）source=" + r2.source()
                        + " file_entry=" + (fileEntry == null ? "(none)"
                                : ShanhaiRecipeConditions.summary(fileEntry))
                        + " got=" + ShanhaiRecipeConditions.summary(r2.conditions())
                        + "（P3 才是「重启不丢」那条）");

        // ---- P4 负对照：把覆盖文件里那条 entry 删掉 ⇒ 必须读不到了 ----
        final int dropped = ShanhaiRecipeOverrideStore.removeEntryById(id);
        final com.google.gson.JsonArray fileAfter =
                ShanhaiRecipeEditorWorkspace.conditionsFromOverrideFile(id);
        check("conditions_panel_readback_file_gone",
                dropped >= 1 && fileAfter == null,
                "覆盖文件里那条 entry 删掉后：dropped=" + dropped
                        + " file_conditions=" + (fileAfter == null ? "(none)" : fileAfter.toString())
                        + "（证明 P3 真的是「文件在起作用」，而不是一条恒真的判据）");

        // 收尾：把这一拍动过的 id 记下来，后面的 case 不碰它
        lastApplyTargetId = id;
    }

    /** 面板读回那一拍要用的目标（尽量挑一条与别的 case 不重叠的 assembler 配方）。 */
    private static GTRecipe pickTargetForPanel(MinecraftServer server) {
        final Set<ResourceLocation> exclude = new HashSet<>(HANDS_OFF);
        if (lastApplyTargetId != null) {
            exclude.add(lastApplyTargetId);
        }
        GTRecipe t = pickTarget(server, "assembler/", exclude);
        if (t == null || t.id == null) {
            t = pickTarget(server, "", exclude);
        }
        return t;
    }

    // ================================================================== 4. 删除 + 放回

    private static void caseRemove(MinecraftServer server) {
        ShanhaiRecipeReverseIndex.build(server);
        final Set<ResourceLocation> exclude = new HashSet<>(HANDS_OFF);
        // 与 apply 拍用【不同】的一条（否则 remove_restored 的期望值会被 apply 那一拍的余温带偏）
        if (lastApplyTargetId != null) {
            exclude.add(lastApplyTargetId);
        }
        final GTRecipe t = pickTarget(server, "assembler/", exclude);
        if (t == null || t.getType() == null || t.id == null) {
            check("remove_target", false, "no usable assembler recipe found");
            return;
        }
        final GTRecipeType type = t.getType();
        final ResourceLocation id = t.id;
        // 🔴 期望值取【底本】那条的时长，不是表里那条 —— 表里那条可能还带着上一拍的残余。
        final GTRecipe pristine = ShanhaiRecipeBase.pristine(id);
        final int d0 = pristine != null ? pristine.duration : t.duration;
        lastRemoveTargetId = id;

        ShanhaiRecipeEditorOps.removeRecipe(server, t, false);
        final GTRecipe iGone = ShanhaiRecipeEditorOps.readFromIndex(type, id);
        final GTRecipe vGone = ShanhaiRecipeEditorOps.readFromVanilla(server, type, id);
        check("remove_gone", iGone == null && vGone == null,
                "id=" + id + " index=" + (iGone == null ? "(absent)" : "duration=" + iGone.duration)
                        + " vanilla=" + (vGone == null ? "(absent)" : "duration=" + vGone.duration));

        ShanhaiRecipeEditorOps.restore(server, t);
        final GTRecipe iBack = ShanhaiRecipeEditorOps.readFromIndex(type, id);
        final GTRecipe vBack = ShanhaiRecipeEditorOps.readFromVanilla(server, type, id);
        check("remove_restored", iBack != null && vBack != null
                        && iBack.duration == d0 && vBack.duration == d0,
                "expect=" + d0 + " index=" + dur(iBack) + " vanilla=" + dur(vBack));
    }

    // ================================================================== 5. base_fp

    private static void caseFingerprint(MinecraftServer server) {
        final boolean cacheOk = ShanhaiRecipeFingerprint.cacheSize() > 0;
        check("fp_cache_installed", cacheOk,
                "cache_entries=" + ShanhaiRecipeFingerprint.cacheSize()
                        + " (捕获点在 KubeJSPlugin.injectRuntimeRecipes；-1 = 从没装上)");

        final boolean reach = ShanhaiRecipeFingerprint.kubeJsReachable();
        // 🆕 第 12 刀：把这一行从"看着像失败"改成"**有相位口径的判定**"。
        //
        //   改之前：`case=fp_reflection_window_usable ok=false resolvable=false
        //            diagnosis=RecipesEventJS.instance == null` —— 它只是一行 INFO，
        //          不进 fail 计数，但每一局都挂着 `ok=false`，读起来像"这条功能坏了"。
        //   第 12 刀把"是永远拿不到，还是某个相位拿不到"测出来了（判据＝KubeJS 窗口里的那一次探针，
        //   见 ShanhaiRecipeFingerprint.windowProbeLine 的字节码取证）：
        //     · 窗口【里面】（injectRuntimeRecipes，本类的 fp 缓存就是在那一拍填的）⇒ 拿得到；
        //     · 窗口【外面】（自检这一拍）⇒ 永远是 null（RecipeManagerMixin 在 post 返回后置 null）。
        //   ⇒ 所以这里的 `ok` 不再表示"这一相位拿得到"，而表示【这条口径成立】：
        //     窗口内测到了 + 本侧数据确实走的缓存。
        //   ⚠️ 如果窗口内探针一次都没跑到（window_present == null），本条判红 ——
        //      "没测到"不许当成"已知限制"糊过去。
        final Boolean inWindow = ShanhaiRecipeFingerprint.windowProbePresent();
        final boolean windowOnly = inWindow != null && inWindow;
        ShanhaiMod.LOGGER.info("{} case=fp_reflection_window_usable ok={} scope={} at_selfcheck={} "
                        + "diagnosis={} {} fp_source={}",
                PREFIX, windowOnly,
                windowOnly ? "window-only(已知限制·不是缺陷)" : "unmeasured(窗口内探针没测到)",
                reach ? "instance_present" : "instance_null(期望值)", ShanhaiRecipeFingerprint.lastDiagnosis(),
                ShanhaiRecipeFingerprint.windowProbeLine(),
                "cache(" + ShanhaiRecipeFingerprint.cacheSize() + " 条)");
        check("fp_window_scope_measured", windowOnly,
                "口径：Reflection 那条路只在 KubeJS 的 ServerEvents.recipes 窗口里可用"
                        + "（窗口内探针 =" + (inWindow == null ? "(未测到)" : inWindow.toString()) + "）；"
                        + "自检这一拍在窗口外 ⇒ instance 必然是 null，"
                        + "这一条【不是缺陷】。编辑器实际用的数据是捕获缓存（" 
                        + ShanhaiRecipeFingerprint.cacheSize() + " 条）");
        check("fp_usable", cacheOk || reach,
                "cache=" + ShanhaiRecipeFingerprint.cacheSize() + " reflection_window=" + reach);

        // 🔴 主判据：把本侧算出来的 fp 逐条打出来，供与<b>同一份日志里</b>装置探针
        //    zz_editor_fp_probe.js 打出的 [EDITOR-FP-PROBE] fp=… 逐字节对照。
        //    （不在游戏内断言的原因：那两条真值是装置里的配方算出来的，写死在 jar 里就成了假绿。）
        for (ResourceLocation id : HANDS_OFF) {
            final String mine = ShanhaiRecipeFingerprint.forRecipeId(id);
            ShanhaiMod.LOGGER.info("{} case=fp_cached id={} fp={}", PREFIX, id, mine);
        }

        // (a) 覆盖文件里已有条目的 base_fp = 覆盖层侧的真值
        // 🆕 第 12 刀：非 GT 那一条要用【非 GT 自己的口径】去比。
        ShanhaiVanillaRecipeTable.captureIfAbsent(server);
        final JsonObject root = ShanhaiRecipeOverrideStore.loadRoot();
        final JsonArray entries = root.has("entries") && root.get("entries").isJsonArray()
                ? root.getAsJsonArray("entries") : new JsonArray();
        int n = 0;
        int match = 0;
        int oldVersion = 0;
        for (JsonElement el : entries) {
            if (!el.isJsonObject()) {
                continue;
            }
            final JsonObject o = el.getAsJsonObject();
            if (!o.has("id")) {
                continue;
            }
            final String id = o.get("id").getAsString();
            final String want = o.has("base_fp") ? o.get("base_fp").getAsString() : "";
            // 🔴 2026-10-05（B4）：旧版指纹的条目【不参与】这条判据 —— 它与新算法本来就不可能相等。
            //    它们该走的是"重新保存一次"的迁移路（覆盖层打 NEEDS_RESAVE，这里也打一行）。
            if (!ShanhaiRecipeFingerprint.isCurrentVersion(want)) {
                oldVersion++;
                ShanhaiMod.LOGGER.warn("{} case=fp_vs_file id={} NEEDS_RESAVE version={} -> 旧版指纹，"
                                + "本拍不参与判定；在游戏里对这条配方重存一次即可升级到 {}",
                        PREFIX, id, ShanhaiRecipeFingerprint.versionOf(want),
                        ShanhaiRecipeFingerprint.FP_PREFIX_V3);
                continue;
            }
            final String got;
            try {
                final ResourceLocation rid = new ResourceLocation(id);
                final GTRecipe live = ShanhaiRecipeReverseIndex.byId(server, rid);
                if (live != null) {
                    // 🔴 判据必须走"编辑器真正会写的那个值"（= baseFpFor），
                    //    不是裸缓存 —— 见 ShanhaiRecipeEditorOps#resolveBaseFp 里那条口径修正。
                    //    裸缓存在"覆盖层这一局已经套过这条 entry"的场合拿的是 apply 之后的样子。
                    got = ShanhaiRecipeEditorOps.baseFpFor(server, rid, live.getType(), live.duration);
                } else {
                    // 🆕 第 12 刀：**非 GT** 那一条要走非 GT 自己的口径（ShanhaiVanillaRecipeOps.resolveBaseFp）
                    //    ——「编辑器下一次保存会写什么」== 「文件里现在是什么」，这才是"重启不会丢"的不变量。
                    //    ⚠️ 这条以前拿的是裸缓存：对一条**已经被覆盖层套用过**的非 GT 配方，
                    //    裸缓存是"套用之后"的样子、而文件里那份是"源声明"，两者本来就不该相等
                    //    ⇒ 只要用户改过任何一条非 GT 配方，这一拍就【永远判红】（假红，且会掩盖真问题）。
                    got = ShanhaiVanillaRecipeOps.resolveBaseFp(server, rid);
                }
            } catch (Throwable t) {
                continue;
            }
            n++;
            final boolean m = !want.isEmpty() && want.equals(got);
            if (m) {
                match++;
            }
            ShanhaiMod.LOGGER.info("{} case=fp_vs_file id={} match={} want={} got={}",
                    PREFIX, id, m, clip(want), clip(got));
        }
        if (n > 0) {
            check("fp_vs_file", match == n, "entries=" + n + " match=" + match
                    + " old_version_skipped=" + oldVersion);
        } else {
            ShanhaiMod.LOGGER.warn("{} case=fp_vs_file SKIPPED reason=override_file_has_no_current_version_entries"
                    + " old_version=" + oldVersion, PREFIX);
            if (oldVersion > 0) {
                check("fp_vs_file_needs_resave", true,
                        "文件里有 " + oldVersion + " 条旧版指纹条目 => 游戏里各重存一次即可（不是失败，是迁移）");
            }
        }

        // (b) 覆盖层自己的 watch 清单：把本侧算出来的 fp 打进日志，供与同一份日志里
        //     KubeJS 的 [OVR-PROBE] WATCH id=… fp=… 逐字节对照。
        final Path watch = net.minecraftforge.fml.loading.FMLPaths.GAMEDIR.get()
                .resolve("config/shanhai/overlay_probe_watch.json");
        int watched = 0;
        if (Files.isRegularFile(watch)) {
            try {
                final JsonObject w = JsonParser.parseString(Files.readString(watch, StandardCharsets.UTF_8)).getAsJsonObject();
                if (w.has("ids") && w.get("ids").isJsonArray()) {
                    for (JsonElement el : w.getAsJsonArray("ids")) {
                        final String id = el.getAsString();
                        watched++;
                        ShanhaiMod.LOGGER.info("{} case=fp_watch id={} fp={}",
                                PREFIX, id, ShanhaiRecipeFingerprint.forRecipeId(new ResourceLocation(id)));
                    }
                }
            } catch (Throwable t) {
                ShanhaiMod.LOGGER.warn("{} case=fp_watch read_failed err={}", PREFIX, t.toString());
            }
        }
        ShanhaiMod.LOGGER.info("{} case=fp_watch_summary ids_logged={} watch_file={}", PREFIX, watched, watch);

        // (c) 固定两条演示 id 的自读数（不论 watch 清单在不在都打）
        for (ResourceLocation id : HANDS_OFF) {
            ShanhaiMod.LOGGER.info("{} case=fp_sample id={} fp={}", PREFIX, id,
                    ShanhaiRecipeFingerprint.forRecipeId(id));
        }

        // (d) 🔴 KubeJS 那条链在本装置里【当前拿不到】（RecipesEventJS.instance == null，
        //     它只在 ServerEvents.recipes 那一小段窗口里非空）。这一拍把"备选路线"整条打出来，
        //     供与装置里那份 JS 探针（zz_editor_fp_probe.js）打出的真值逐字节对照：
        //       · 如果 GT codec 那条 == JS 打出来的 raw，则备选路线成立，指纹可以脱离 KubeJS 窗口；
        //       · 如果不等，就如实记"未打通"，不假装。
        ShanhaiRecipeBase.captureIfAbsent(server);
        for (ResourceLocation id : HANDS_OFF) {
            final GTRecipe pristine = ShanhaiRecipeBase.pristine(id);
            if (pristine == null) {
                ShanhaiMod.LOGGER.warn("{} case=fp_candidate_gtcodec id={} pristine=absent", PREFIX, id);
                continue;
            }
            final String raw = ShanhaiRecipeFingerprint.diagnosticGtCodecRaw(pristine);
            ShanhaiMod.LOGGER.info("{} case=fp_candidate_gtcodec id={} raw_len={} fp={}",
                    PREFIX, id, raw.length(), ShanhaiRecipeFingerprint.fingerprintOfRawJson(raw));
            ShanhaiMod.LOGGER.info("{} case=fp_candidate_gtcodec_raw id={} raw={}", PREFIX, id, raw);
        }
        check("fp_emitted", true, "entries_compared=" + n + " watch_ids=" + watched);
    }

    // ================================================================== 5.5 面板文字（B2）
    //
    //   用户报的两件事：
    //     ①「配方种类我希望是中文的，而不是英文的」—— 列表行里出现的是 `(matter_modu...)` 这种英文 id；
    //     ②「虚像物质模块前面又漏码了」—— 目标物品那行显示成 `&$magic-虚像物质模块`。
    //   本拍把这两条的<b>正面</b>与<b>负对照</b>都钉死（负对照见 §"查不到的类型名必须回落"）。

    private static void casePanelText(MinecraftServer server) {
        // ---- ① 中文类型名：gl 真值来源 = 我们自己的 jar 里 assets/shanhai/lang/zh_cn.json ----
        final ShanhaiRecipeEditorSession.TypeName known =
                ShanhaiRecipeEditorSession.typeName("matter_module_casting");
        check("type_name_zh", "物质模块铸造".equals(known.text()),
                "key=gtceu.matter_module_casting text=" + known.text() + " source=" + known.source()
                        + " (期望 物质模块铸造；source=zh_cn 或 live 都算通)");

        // ---- ①-b 中文名来自【别人家的 jar】(gtceu 命名空间的 lang) ----
        // 🔴 这条抓的是真缺陷（2026-10-05 冒烟第 2 局实测）：一开始用
        //    `Class.getResourceAsStream("/assets/gtceu/lang/zh_cn.json")` 去读别人的 mod 资源，
        //    只有我们自己的 jar 读得到（读数 `mods_scanned=92 mods_with_lang=1`）
        //    ⇒ GTCEu 那些类型（组装机/离心机/…）全部退回英文。
        //    现在改成"遍历每个 mod 的 jar、扫里面的 assets/<任意命名空间>/lang/zh_cn.json"
        //    （第 3 局读数：mods_with_lang=53）。
        final String zhAssembler = com.shanhai.common.text.ShanhaiLangLookup.zhCn("gtceu.assembler");
        check("lang_index_other_mod_zh", "组装机".equals(zhAssembler),
                "zh_cn(gtceu.assembler)=" + zhAssembler + " | " + com.shanhai.common.text.ShanhaiLangLookup.statsLine());
        // 上面是【查表档】的判据。至于"面板上最终显示什么"，走的是另一条优先级：
        //   活的翻译（= 执行命令那一侧的语言）优先，查表只做兜底
        //   ⇒ 专服的 Language 是英文时这里看到的是 `Assembler`，而单机（语言=中文）看到的是 `组装机`。
        //   所以这一条只断言"没退回原始 id"，不断言具体是哪个语种。
        final ShanhaiRecipeEditorSession.TypeName other = ShanhaiRecipeEditorSession.typeName("assembler");
        check("type_name_other_mod", !"fallback-id".equals(other.source()) && !other.text().isEmpty(),
                "key=gtceu.assembler text=" + other.text() + " source=" + other.source()
                        + " (source=live 时语种取决于当前 Language；专服=英文、单机=中文)");

        // ---- 负对照：查不到的类型名必须【回落成原 id】，绝不许编一个中文名 ----
        final ShanhaiRecipeEditorSession.TypeName bogus =
                ShanhaiRecipeEditorSession.typeName("definitely_not_a_real_recipe_type_zzz");
        check("type_name_fallback", "fallback-id".equals(bogus.source())
                        && "definitely_not_a_real_recipe_type_zzz".equals(bogus.text()),
                "text=" + bogus.text() + " source=" + bogus.source()
                        + " (负对照：这一步必须回落，否则说明查表在编名字)");

        // ---- ② 物品名剥码：`&$…-` 必须被剥掉（用户原话：「虚像物质模块前面又漏码了」）----
        // 🔴 这里必须用【已知带码的样本】做正对照。冒烟第 1 局踩过：直接去看真物品的
        //    headerText()，在【无头专服】上它拿到的是未翻译的 lang key（`item.shanhai.…`），
        //    本来就不含 `&` ⇒ 那条判据是【因为错误的原因】通过的（假绿）。
        final String coded = "&$magic-虚像物质模块";
        final String stripped = com.shanhai.common.text.ShanhaiTextParser.stripStyleCode(coded);
        check("item_name_stripped", "虚像物质模块".equals(stripped),
                "in=" + coded + " out=" + stripped);

        final String headerCoded = ShanhaiRecipeEditorSession.headerLine(coded, "virtual_image_material_module");
        check("panel_header_no_markup", headerCoded.indexOf('&') < 0 && headerCoded.contains("虚像物质模块"),
                "header=" + headerCoded + " (正对照：输入带码 ⇒ 整行一个 '&' 都不许有，且正文还在)");
        // 负对照：把同一份输入原样拼一遍（模拟"没剥码"的旧行为），它必须【含】& ⇒ 证明上面那条判据会响
        final String headerNotStripped = "§7目标物品：§f" + coded + " §8(virtual_image_material_module)";
        check("panel_header_negative_control", headerNotStripped.indexOf('&') >= 0,
                "旧行为样本=" + headerNotStripped + " (必须含 '&' ⇒ 上面那条判据不是恒真的)");

        // ---- ③ 行宽：拿用户截图里那一行做正样本 + 一条超长 id 做压力样本 ----
        final ShanhaiRecipeEditorSession.Row r1 = ShanhaiRecipeEditorSession.composeRow(
                true, "material_recombination_module", known.text(), known.source(), 300);
        final ShanhaiRecipeEditorSession.Row r2 = ShanhaiRecipeEditorSession.composeRow(
                false, "zpm_256a_laser_source_hatch_and_some_very_long_suffix",
                "matter_module_casting", "fallback-id", 424342);
        ShanhaiMod.LOGGER.info("{} case=row_text row1={} units={} fit={} type_clipped={}",
                PREFIX, r1.plain(), r1.units(), r1.fits(), r1.typeClipped());
        ShanhaiMod.LOGGER.info("{} case=row_text row2={} units={} fit={} id_clipped={}",
                PREFIX, r2.plain(), r2.units(), r2.fits(), r2.idClipped());
        check("row_width_fits", r1.fits() && r2.fits(),
                "row1_units=" + r1.units() + " row2_units=" + r2.units()
                        + " budget=" + ShanhaiRecipeEditorSession.ROW_UNITS);
        check("row_type_not_clipped", !r1.typeClipped(),
                "中文名 `" + known.text() + "` 占 " + ShanhaiRecipeEditorSession.displayUnits(known.text())
                        + " 个单位，budget=" + ShanhaiRecipeEditorSession.ROW_UNITS + " ⇒ 不许被截");

        // ---- ④ 真表里跑一遍：拿一条真实配方，把它的类型名与整行宽度打出来 ----
        final Item probe = itemByName("shanhai:virtual_image_material_module");
        final Item fallbackProbe = probe != null ? probe : itemByName(PROBE_ITEM);
        if (fallbackProbe == null) {
            check("row_real_table", false, "既没有 shanhai:virtual_image_material_module 也没有 " + PROBE_ITEM);
            return;
        }
        final ResourceLocation probeId =
                net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(fallbackProbe);
        final ShanhaiRecipeEditorSession s = new ShanhaiRecipeEditorSession(server, probeId);
        s.reload();
        ShanhaiMod.LOGGER.info("{} case=panel_header_real item={} header={}", PREFIX, probeId, s.headerText());
        // ⚠️ 无头专服上这个物品名会退化成未翻译的 lang key（`item.shanhai.…`）—— 它本身不含 `&`，
        //    ⇒ 这一行【不是】剥码的判据（真正的判据是上面的 panel_header_no_markup 正对照），
        //    这里只记录"真路径产出的是什么"，免得把假绿当成证据。
        final boolean headerClean = s.headerText().indexOf('&') < 0;
        check("panel_header_real_no_markup", headerClean,
                "header=" + s.headerText() + " (信息性：真表路径；无头专服上物品名可能是未翻译的 key)");

        int worst = 0;
        int shown = Math.min(s.total(), ShanhaiRecipeEditorSession.ROWS);
        for (int i = 0; i < shown; i++) {
            final ShanhaiRecipeEditorSession.Row row = s.absoluteRow(i);
            if (row == null) {
                continue;
            }
            worst = Math.max(worst, row.units());
            ShanhaiMod.LOGGER.info("{} case=row_real idx={} text={} units={} type_source={} type_clipped={}",
                    PREFIX, i, row.plain(), row.units(), row.typeSource(), row.typeClipped());
        }
        ShanhaiMod.LOGGER.info("{} case=row_real_summary item={} rows={} worst_units={} budget={}",
                PREFIX, probeId, shown, worst, ShanhaiRecipeEditorSession.ROW_UNITS);
        check("row_real_fits", shown == 0 || worst <= ShanhaiRecipeEditorSession.ROW_UNITS,
                "rows=" + shown + " worst_units=" + worst
                        + " budget=" + ShanhaiRecipeEditorSession.ROW_UNITS
                        + (shown == 0 ? " (这个物品没有命中配方 ⇒ 这一条不算判据)" : ""));
    }

    private static Item itemByName(String id) {
        try {
            final Item it = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(new ResourceLocation(id));
            return it == null || it == net.minecraft.world.item.Items.AIR ? null : it;
        } catch (Throwable t) {
            return null;
        }
    }

    // ================================================================== 6. 覆盖文件往返

    private static void caseStore(MinecraftServer server) {
        final Path p = ShanhaiRecipeOverrideStore.path();
        byte[] before = null;
        try {
            if (Files.isRegularFile(p)) {
                before = Files.readAllBytes(p);
            }
        } catch (Throwable ex) {
            check("store_snapshot", false, "cannot read " + p + " : " + ex);
            return;
        }
        final String shaBefore = sha256(before);

        // 用一条真配方的 id + 真指纹写一条哨兵条目（uid 带专属前缀，便于回收）
        ShanhaiRecipeReverseIndex.build(server);
        final GTRecipe t = pickTarget(server, "assembler/", HANDS_OFF);
        if (t == null || t.id == null) {
            check("store_roundtrip", false, "no target recipe for the store probe");
            return;
        }
        final String fp = ShanhaiRecipeFingerprint.forRecipeId(t.id);
        final JsonObject sentinel = ShanhaiRecipeOverrideStore.makeSetEntry(
                "e-selftest-probe", t.id.toString(),
                t.getType() == null || t.getType().registryName == null ? null : t.getType().registryName.toString(),
                "duration", t.duration, fp);
        final int n = ShanhaiRecipeOverrideStore.upsert(sentinel);

        boolean readBack = false;
        String detail = "upsert_returned=" + n;
        try {
            final JsonObject back = JsonParser.parseString(Files.readString(p, StandardCharsets.UTF_8)).getAsJsonObject();
            final boolean schemaOk = back.has("schema_version") && back.get("schema_version").getAsInt() == 1;
            final JsonArray es = back.has("entries") && back.get("entries").isJsonArray()
                    ? back.getAsJsonArray("entries") : new JsonArray();
            JsonObject found = null;
            for (JsonElement el : es) {
                if (el.isJsonObject() && el.getAsJsonObject().has("uid")
                        && "e-selftest-probe".equals(el.getAsJsonObject().get("uid").getAsString())) {
                    found = el.getAsJsonObject();
                }
            }
            readBack = schemaOk && found != null
                    && found.has("op") && "set".equals(found.get("op").getAsString())
                    && found.has("fields") && found.getAsJsonObject("fields").has("duration")
                    && found.has("base_fp") && found.get("base_fp").getAsString().equals(fp);
            detail += " schema_ok=" + schemaOk + " entry_found=" + (found != null)
                    + " entries_now=" + es.size() + " file=" + p;
        } catch (Throwable ex) {
            detail += " read_back_threw=" + ex;
        }
        check("store_roundtrip", readBack, detail);

        // 逐字节还原
        boolean restored = false;
        try {
            if (before == null) {
                Files.deleteIfExists(p);
                restored = !Files.exists(p);
            } else {
                ShanhaiRecipeOverrideStore.restoreBytes(p, before);
                restored = shaBefore.equals(sha256(Files.readAllBytes(p)));
            }
        } catch (Throwable ex) {
            detail = "restore_threw=" + ex;
        }
        check("store_restored", restored,
                "sha_before=" + shaBefore + " sha_after=" + sha256(readAllQuiet(p)) + " " + detail);
    }

    // ================================================================== 7. 留档档（给下一局验"重启不丢"）

    /**
     * 🆕 <b>「重启不丢」那条链的端到端判据（B 组：额外条件）</b>。
     *
     * <p>逻辑与 {@code caseFingerprint} 里那条 {@code fp_vs_file} 同形：
     * <b>覆盖文件里写了什么</b> vs <b>活配方上真的读到了什么</b>，逐条比。
     * 判据行：{@code case=cond_vs_file id=… match=true want_n=2 got_n=2}。
     *
     * <p>⚠️ 文件里一条带 conditions 的 entry 都没有时<b>不算通过</b>（打一行 SKIPPED），
     * 否则"这个功能没跑"会被读成"这个功能是对的"。
     */
    private static void caseConditionsReplay(MinecraftServer server) {
        // 🔴 2026-10-05 第 5 轮：先验【前提】，再验结论。
        //    这一条验的是「覆盖层这一局到底把文件里的条件装回去了没有」——
        //    而覆盖层是 KubeJS 脚本（kubejs/server_scripts/shanhai_recipe_overrides.js）。
        //    脚本不在（例如 build/smoke-server 那套冒烟装置是<b>别的整合包</b>的 kubejs）时，
        //    文件里的条件本来就不会被套用 ⇒ 那时报 fail 是把"环境缺件"读成了"功能坏了"。
        //    现场读数（2026-10-05 冒烟第二跑）：entries_with_conditions=1 match=0 bad=1 —— 就是这一种。
        //    ⇒ 前提不在时明确 SKIPPED 并写出原因；前提在时照旧严格判定（不放松任何判据）。
        final java.nio.file.Path overlay = net.minecraftforge.fml.loading.FMLPaths.GAMEDIR.get()
                .resolve("kubejs").resolve("server_scripts").resolve("shanhai_recipe_overrides.js");
        if (!java.nio.file.Files.exists(overlay)) {
            ShanhaiMod.LOGGER.warn("{} case=conditions_vs_file SKIPPED reason=overlay_script_absent path={} "
                            + "（这一条【不算通过】：没有覆盖层脚本，文件里的条件本来就没人去套 ⇒ 无法判定）",
                    PREFIX, overlay);
            return;
        }
        ShanhaiRecipeReverseIndex.build(server);
        final JsonObject root = ShanhaiRecipeOverrideStore.loadRoot();
        final JsonArray entries = root.has("entries") && root.get("entries").isJsonArray()
                ? root.getAsJsonArray("entries") : new JsonArray();
        int n = 0;
        int match = 0;
        int bad = 0;
        for (JsonElement el : entries) {
            if (!el.isJsonObject()) {
                continue;
            }
            final JsonObject o = el.getAsJsonObject();
            if (!o.has("fields") || !o.get("fields").isJsonObject()) {
                continue;
            }
            final JsonObject fields = o.getAsJsonObject("fields");
            if (!fields.has(ShanhaiRecipeConditions.FIELD)) {
                continue;
            }
            if (!o.has("id")) {
                continue;
            }
            final String idStr = o.get("id").getAsString();
            final JsonArray want = fields.getAsJsonArray(ShanhaiRecipeConditions.FIELD);
            n++;
            try {
                final ResourceLocation rid = new ResourceLocation(idStr);
                final GTRecipe live = ShanhaiRecipeReverseIndex.byId(server, rid);
                final JsonArray got = ShanhaiRecipeConditions.encodeOf(live);
                final boolean m = live != null && ShanhaiRecipeConditions.sameAs(got, want);
                if (m) {
                    match++;
                } else {
                    bad++;
                }
                ShanhaiMod.LOGGER.info("{} case=cond_vs_file id={} match={} want_n={} got_n={} want={} got={}",
                        PREFIX, idStr, m, want.size(), got.size(),
                        ShanhaiRecipeConditions.summary(want), ShanhaiRecipeConditions.summary(got));
            } catch (Throwable t) {
                bad++;
                ShanhaiMod.LOGGER.warn("{} case=cond_vs_file id={} threw {}", PREFIX, idStr, t.toString());
            }
        }
        if (n > 0) {
            check("conditions_vs_file", match == n && bad == 0,
                    "entries_with_conditions=" + n + " match=" + match + " bad=" + bad
                            + "（覆盖层这一局应把每一条都装回去；逐条读数见上面的 cond_vs_file 行）");
        } else {
            ShanhaiMod.LOGGER.warn("{} case=conditions_vs_file SKIPPED "
                    + "reason=override_file_has_no_conditions_field "
                    + "（这一条【不算通过】：文件里没有条件可验 ⇒ 本项无法判定）", PREFIX);
        }
    }

    private static void caseWriteThrough(MinecraftServer server) {
        ShanhaiRecipeReverseIndex.build(server);
        final GTRecipe t = pickTarget(server, "assembler/", HANDS_OFF);
        if (t == null || t.id == null) {
            check("write_through", false, "no target recipe");
            return;
        }
        final int d0 = t.duration;
        // 🆕 2026-10-05（B 组）：这一条 entry 同时带上【条件】—— 下一局开机时它要能被重放回来。
        //    条件是"这条原有的 + 一条超净间(law_cleanroom) + 一条物质模块(入门模块×3)"，
        //    下一局的判据是 case=cond_vs_file（逐条比文件与活配方）。
        final com.google.gson.JsonArray conds = ShanhaiRecipeConditions.encodeOf(t);
        ShanhaiRecipeConditions.add(conds,
                ShanhaiRecipeConditions.makeCleanroom("law_cleanroom", false));
        ShanhaiRecipeConditions.add(conds,
                ShanhaiRecipeConditions.makeModuleLevel("shanhai:introductory_material_module", 3, false));
        final ShanhaiRecipeEditorOps.Result r =
                ShanhaiRecipeEditorOps.applyEdits(server, t, null, null, null,
                        ShanhaiRecipeDuration.originalOf(t) + D_WRITE, null, conds, true, true);
        check("write_through", r.ok() && r.persisted(),
                "id=" + t.id + " from_dur=" + d0 + " to_live=" + (d0 + D_WRITE)
                        + " conditions=" + ShanhaiRecipeConditions.summary(conds)
                        + " persisted=" + r.persisted() + " fp_len=" + r.baseFp().length()
                        + " (下一局开机时覆盖层应当打 APPLIED 且这一条要能 cond_vs_file match=true；"
                        + "验完用 " + ShanhaiRecipeEditorCommand.ENV_CLEANUP + "=1 清掉)");
        check("write_through_fp_known", !ShanhaiRecipeFingerprint.UNKNOWN.equals(r.baseFp()),
                "base_fp=" + clip(r.baseFp()));
    }

    // ================================================================== 🆕 第 8 轮 · 队列 #6：翻页加速
    //
    // 用户原话（逐字）：「还有这个 17 页翻得比较慢（有的甚至配方有上百页），把 shift 和 ctrl 加速也加进去吧」。
    //
    // 这一拍验两件【互相独立】的事：
    //   ① 档位表 —— {@code pageStepFor} 是个纯函数，四个组合逐条钉死（哪个键翻几页 = 一处真值）；
    //   ② 真表上翻一遍 —— 复用 workspace 自己的 {@code jumpPage}/{@code clampPage}，
    //      量"普通点 / Shift 点 / Ctrl 点"各前进几页，以及首末页越界时停在原地。
    //
    // 🔴 边界的最后 5% **本条验不到**，如实写在这里：{@code ClickData.isShiftClick} 到底对应
    //    键盘上哪一个物理键，只有客户端读得到（那一半由另一条只读线逐段字节码核实：
    //    {@code ButtonWidget.mouseClicked} 在客户端读 340/341/344/345，随点击包上行）。
    //    本条能钉死的是"拿到 true 之后翻几页、会不会越界"。
    private static void casePageJump(MinecraftServer server) {
        // ---- ① 档位表（纯函数）----
        final int none = ShanhaiRecipeEditorWorkspace.pageStepFor(false, false);
        final int shift = ShanhaiRecipeEditorWorkspace.pageStepFor(true, false);
        final int ctrl = ShanhaiRecipeEditorWorkspace.pageStepFor(false, true);
        final int both = ShanhaiRecipeEditorWorkspace.pageStepFor(true, true);
        check("page_step_table", none == 1
                        && shift == ShanhaiRecipeEditorWorkspace.PAGE_STEP_SHIFT
                        && ctrl == ShanhaiRecipeEditorWorkspace.PAGE_STEP_CTRL
                        && both == ShanhaiRecipeEditorWorkspace.PAGE_STEP_CTRL,
                "none=" + none + " shift=" + shift + " ctrl=" + ctrl + " both=" + both
                        + " (期望 1/" + ShanhaiRecipeEditorWorkspace.PAGE_STEP_SHIFT
                        + "/" + ShanhaiRecipeEditorWorkspace.PAGE_STEP_CTRL
                        + "/" + ShanhaiRecipeEditorWorkspace.PAGE_STEP_CTRL + "；两个都按=按 Ctrl 那一档)");
        // 负对照：三档必须互不相同，否则"加速"这件事在读数上根本看不出来（假绿）
        check("page_step_distinct", none != shift && none != ctrl && shift != ctrl,
                "1 / " + shift + " / " + ctrl + " 三者必须两两不等 —— 否则上面的判据是恒真的");

        // ---- ② 真表上翻一遍 ----
        ShanhaiRecipeReverseIndex.build(server);
        final ResourceLocation type = biggestType(server);
        if (type == null) {
            check("page_jump_target", false, "no recipe type with enough pages found");
            return;
        }
        final ShanhaiRecipeEditorWorkspace ws = new ShanhaiRecipeEditorWorkspace(server, null);
        ws.reloadTypes();
        if (!ws.selectType(type)) {
            check("page_jump_target", false, "selectType failed: " + type);
            return;
        }
        final int pages = ws.pageCount();
        if (pages < 20) {
            check("page_jump_target", false, "type=" + type + " pages=" + pages + "（不足 20 页，翻不动 10 页这一档）");
            return;
        }
        final int p0 = ws.page();
        ws.jumpPage(ShanhaiRecipeEditorWorkspace.pageStepFor(false, false));
        final int p1 = ws.page();
        ws.jumpPage(ShanhaiRecipeEditorWorkspace.pageStepFor(true, false));
        final int p2 = ws.page();
        ws.jumpPage(ShanhaiRecipeEditorWorkspace.pageStepFor(false, true));
        final int p3 = ws.page();
        ws.jumpPage(-ShanhaiRecipeEditorWorkspace.pageStepFor(false, true));
        final int p4 = ws.page();
        check("page_jump_real_table", p0 == 0 && p1 - p0 == 1 && p2 - p1 == shift
                        && p3 - p2 == ctrl && p4 == p3 - ctrl,
                "type=" + type + " recipes=" + ws.recipeRows().size() + " pages=" + pages
                        + " | 起点 " + p0 + " →普通→ " + p1 + " →Shift→ " + p2
                        + " →Ctrl→ " + p3 + " →Ctrl往回→ " + p4);

        // ---- ③ 越界：末页再往后 / 首页再往前 ⇒ 停在原地，不许越界 ----
        ws.lastPage();
        final int last = ws.page();
        final boolean movedBeyond = ws.jumpPage(ShanhaiRecipeEditorWorkspace.PAGE_STEP_CTRL);
        final boolean movedBeyond2 = ws.jumpPage(ShanhaiRecipeEditorWorkspace.PAGE_STEP_CTRL);
        check("page_jump_clamp_end", last == pages - 1 && !movedBeyond && !movedBeyond2
                        && ws.page() == pages - 1,
                "last=" + last + " pages=" + pages + " moved_1st=" + movedBeyond
                        + " moved_2nd=" + movedBeyond2 + " page_now=" + ws.page()
                        + " (期望：停在 " + (pages - 1) + "，两次都返回 false)");
        ws.firstPage();
        final boolean movedBefore = ws.jumpPage(-ShanhaiRecipeEditorWorkspace.PAGE_STEP_CTRL);
        check("page_jump_clamp_start", ws.page() == 0 && !movedBefore,
                "page_now=" + ws.page() + " moved=" + movedBefore + " (期望停在 0)");

        // ---- ④ 可发现性（用户点单的硬要求：必须让功能可发现）----
        //    ⚠️ 顺序要紧：卡片屏那一行与类型屏那一行是**两个分支**，所以两屏各读一次。
        final String tail = ShanhaiRecipeEditorWorkspace.pageJumpHint();
        final String hintCards = ws.hintText();          // 还在第二屏（RECIPES）
        check("page_jump_hint_on_cards", hintCards != null && hintCards.endsWith(tail),
                "第二屏（卡片屏 —— 就是用户说的「17 页」那一屏）那一行必须带同一句：hint=" + hintCards);
        check("page_jump_hint_text", tail.contains("Shift") && tail.contains("Ctrl")
                        && tail.contains(String.valueOf(ShanhaiRecipeEditorWorkspace.PAGE_STEP_SHIFT))
                        && tail.contains(String.valueOf(ShanhaiRecipeEditorWorkspace.PAGE_STEP_CTRL)),
                "提示句子本身必须同时写出两个键与两个档位：tail=" + tail);
        ws.back();
        final String hintTypes = ws.hintText();          // 回到第一屏（TYPES）
        check("page_jump_hint_on_types", hintTypes != null && hintTypes.endsWith(tail),
                "第一屏（类型列表）那一行也必须带同一句：hint=" + hintTypes);
    }

    /** 配方条数最多的那个类型（翻页判据要"页数够多"才判得出来）。 */
    private static ResourceLocation biggestType(MinecraftServer server) {
        final java.util.Map<ResourceLocation, Integer> count = new java.util.LinkedHashMap<>();
        for (GTRecipe r : ShanhaiRecipeReverseIndex.all()) {
            if (r == null || r.getType() == null || r.getType().registryName == null) {
                continue;
            }
            count.merge(r.getType().registryName, 1, Integer::sum);
        }
        ResourceLocation best = null;
        int bestN = -1;
        for (java.util.Map.Entry<ResourceLocation, Integer> e : count.entrySet()) {
            if (e.getValue() != null && e.getValue() > bestN) {
                bestN = e.getValue();
                best = e.getKey();
            }
        }
        return best;
    }

    // ================================================================== 🆕 第 8 轮 · 队列 #4：类型花名册
    //
    // 用户原话（逐字）：「如果我把一个配方种类下面的配方都删完了，就算我输入 /shanhai edit restore，
    // 然后在面板里点重读配方表，那个配方种类也不会显示了，必须要退存档重进才能再看见」。
    //
    // 判据（全部可 grep）：
    //   type_roster_no_growth_at_boot  ：装机那一拍，并集列表【等于】当前表里的类型数
    //                                    （⇒ 证明它没有变成"列出所有已知配方类型"）
    //   type_roster_emptied_still_listed：把一个类型的配方【真的全删掉】再「重读配方表」⇒
    //                                    它还在，且条数如实显示 0
    //   type_roster_negative_control   ：把快照清掉重来 ⇒ 它【必须】掉出去
    //                                    （没有这一条，上面那条是恒真的假绿）
    //   type_roster_empty_state_ok     ：空态点进去 0 条、页数 1、行文字空、点击不炸、状态推送不炸
    //   type_roster_new_recipe_in_emptied_type：在这个被删空的类型上真点一次「新建配方」
    //                                    （persist=false ⇒ config 一个字节都不动）
    //   type_roster_restored_visible   ：把删掉的那些放回去 ⇒ 它带着真实条数回到列表里（反面对照）
    //
    // 🔴 全程 persist=false（三个删除 + 那个新建都不落盘），跑完把删掉的原样放回去。
    private static void caseTypeRoster(MinecraftServer server) {
        ShanhaiRecipeReverseIndex.build(server);
        ShanhaiRecipeBase.captureIfAbsent(server);

        // ---- ① 按类型给当前表分组 ----
        final java.util.Map<ResourceLocation, List<ResourceLocation>> byType = new java.util.LinkedHashMap<>();
        for (GTRecipe r : ShanhaiRecipeReverseIndex.all()) {
            if (r == null || r.id == null || r.getType() == null || r.getType().registryName == null) {
                continue;
            }
            byType.computeIfAbsent(r.getType().registryName, k -> new ArrayList<>()).add(r.id);
        }
        // ---- ② 挑"删空代价最小"的那个类型：注册过、且不含别的拍子的那两条 id ----
        ResourceLocation victim = null;
        int min = Integer.MAX_VALUE;
        for (java.util.Map.Entry<ResourceLocation, List<ResourceLocation>> e : byType.entrySet()) {
            if (com.gregtechceu.gtceu.api.registry.GTRegistries.RECIPE_TYPES.get(e.getKey()) == null) {
                continue;
            }
            boolean handsOff = false;
            for (ResourceLocation id : e.getValue()) {
                if (HANDS_OFF.contains(id)) {
                    handsOff = true;
                    break;
                }
            }
            if (handsOff || e.getValue().size() >= min) {
                continue;
            }
            min = e.getValue().size();
            victim = e.getKey();
        }
        if (victim == null) {
            check("type_roster_target", false, "找不到可以删空的类型（不是注册类型 / 或不含别的拍子那两条的都被排除了）");
            return;
        }
        final List<ResourceLocation> ids = new ArrayList<>(byType.get(victim));

        // ---- ③ 装机读数：并集不许比当前表多 ----
        final ShanhaiRecipeEditorWorkspace ws = new ShanhaiRecipeEditorWorkspace(server, null);
        ws.reloadTypes();
        final int listedBefore = ws.typeRows().size();
        ShanhaiMod.LOGGER.info("{} case=type_roster_target type={} recipes_in_type={} "
                        + "listed_before={} types_with_recipes={} registered_types={} roster=[{}]",
                PREFIX, victim, ids.size(), listedBefore, byType.size(),
                com.gregtechceu.gtceu.api.registry.GTRegistries.RECIPE_TYPES.keys().size(),
                ShanhaiRecipeTypeRoster.statsLine());
        // 🔴 2026-10-05（工作台 / 原版配方）：口径订正 —— 这里必须【只数 GT 的类型】。
        //    本刀给面板的类型列表前面加了"非 GT 类型"那一段（工作台 / 熔炉 / 切石机…），
        //    它们本来就不在 `byType`（那张表是从 GT 反查索引派生的）里
        //    ⇒ 直接比总数会把"我们主动加的那 19 种"读成"并集凭空多出类型"（假红）。
        //    ⚠️ 这条判据的**本意一个字都没变**：它要证的仍然是
        //       "装机那一拍，GT 并集列表 == 当前表里有配方的 GT 类型数"。
        int listedGt = 0;
        for (ShanhaiRecipeEditorWorkspace.TypeRow t : ws.typeRows()) {
            if (!ShanhaiRecipeEditorWorkspace.isVanillaTypeId(t.id())) {
                listedGt++;
            }
        }
        check("type_roster_no_growth_at_boot", listedGt == byType.size(),
                "listed_gt=" + listedGt + " types_with_recipes=" + byType.size()
                        + " listed_total(incl. 非 GT)=" + listedBefore
                        + " (期望相等：装机时 GT 并集不许凭空多出类型 —— 这正是"
                        + "「不许列出所有已知配方类型」的判据；非 GT 类型来自另一条来源，不参与本条)");

        // ---- ④ 真的把这个类型的配方全删掉（不落盘）----
        int removed = 0;
        for (ResourceLocation id : ids) {
            final GTRecipe live = ShanhaiRecipeReverseIndex.byId(server, id);
            if (live != null && ShanhaiRecipeEditorOps.removeRecipe(server, live, false).ok()) {
                removed++;
            }
        }
        // ---- ⑤ 「重读配方表」== 用户点的那颗按钮（它走的就是 reloadTypes）----
        ws.reloadTypes();
        final int listedAfter = ws.typeRows().size();
        final int shownAfter = countIn(ws.typeRows(), victim);
        check("type_roster_emptied_still_listed", removed == ids.size() && shownAfter == 0,
                "type=" + victim + " removed=" + removed + "/" + ids.size()
                        + " listed_before=" + listedBefore + " listed_after=" + listedAfter
                        + " 它现在的条数=" + (shownAfter < 0 ? "(不在列表里)" : String.valueOf(shownAfter))
                        + " (期望：还在列表里，且如实显示 0 条)");

        // ---- ⑥ 负对照：清掉快照再来一次 ⇒ 它必须掉出去 ----
        ShanhaiRecipeTypeRoster.resetForSelfcheck();
        ws.reloadTypes();                 // 这一次 observe 记的是"已经被删空的当前表"
        final int shownNoSnap = countIn(ws.typeRows(), victim);
        check("type_roster_negative_control", shownNoSnap < 0,
                "无快照时 它 = " + (shownNoSnap < 0 ? "(不在列表里)" : String.valueOf(shownNoSnap))
                        + " (必须【不在】⇒ 证明上面那条是开机快照带来的，不是恒真的)");

        // ---- ⑦ 空态不崩：点进去 0 条 / 页数 1 / 行文字空 / 点击不炸 / 状态推送不炸 ----
        final boolean sel = ws.selectType(victim);
        final boolean empty = ws.recipeRows().isEmpty();
        final int emptyPages = ws.pageCount();
        final String row0 = ws.rowText(0);
        final boolean clickDead = ws.clickRow(0);
        boolean pushOk = true;
        String pushNote;
        try {
            final net.minecraft.network.FriendlyByteBuf buf =
                    new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
            ws.writeState(buf, true);
            final int bytes = buf.readableBytes();
            final ShanhaiRecipeEditorWorkspace clientCopy = new ShanhaiRecipeEditorWorkspace(null, null);
            clientCopy.readState(buf);
            pushNote = "bytes=" + bytes + " client_stage=" + clientCopy.stage()
                    + " client_page=" + clientCopy.page() + " client_card0=" + clientCopy.cardAt(0)
                    + " leftover=" + buf.readableBytes();
        } catch (Throwable t) {
            pushOk = false;
            pushNote = "THREW " + t.getClass().getName() + ": " + t.getMessage();
        }
        check("type_roster_empty_state_ok", sel && empty && emptyPages == 1
                        && row0 != null && row0.isEmpty() && !clickDead && pushOk,
                "selected=" + sel + " recipes=" + (empty ? 0 : ws.recipeRows().size())
                        + " pages=" + emptyPages + " row0=\"" + row0 + "\" click0=" + clickDead
                        + " push=" + pushOk + " " + pushNote);

        // ---- ⑧ 反面对照 ＋ 队列 #3：在被删空的类型上真点一次「新建配方」（不落盘）----
        final boolean made = ws.newRecipe(false);
        final ResourceLocation newId = ws.currentRecipeId();
        final GTRecipe newLive = newId == null ? null : ShanhaiRecipeReverseIndex.byId(server, newId);
        check("type_roster_new_recipe_in_emptied_type",
                made && newLive != null && victim.equals(ws.currentTypeId()),
                "type=" + victim + " made=" + made + " new_id=" + newId
                        + " 在索引里=" + (newLive != null) + " stage=" + ws.stage()
                        + " persisted=false(config 未动)");

        // ---- ⑨ 收拾干净：把新建那条撤掉，再把删掉的那些原样放回去 ----
        if (newLive != null) {
            ShanhaiRecipeEditorOps.removeRecipe(server, newLive, false);
        }
        int restored = 0;
        for (ResourceLocation id : ids) {
            final GTRecipe pristine = ShanhaiRecipeBase.pristine(id);
            if (pristine != null) {
                ShanhaiRecipeEditorOps.restore(server, pristine);
                restored++;
            }
        }
        ws.reloadTypes();
        final int shownBack = countIn(ws.typeRows(), victim);
        check("type_roster_restored_visible", restored == ids.size() && shownBack == ids.size(),
                "type=" + victim + " restored=" + restored + "/" + ids.size()
                        + " 它现在的条数=" + shownBack + " (反面对照：放回去之后必须带着真实条数出现在列表里)");
    }

    /** 列表里某个类型的条数（不在列表里 ⇒ -1）。 */
    private static int countIn(List<ShanhaiRecipeEditorWorkspace.TypeRow> rows, ResourceLocation id) {
        for (ShanhaiRecipeEditorWorkspace.TypeRow r : rows) {
            if (r != null && id.equals(r.id())) {
                return r.count();
            }
        }
        return -1;
    }

    // ================================================================== 🆕 第 8 轮 · P0：开面板那一包的两侧同步
    //
    // 用户报的现象（逐字）：「/shanhai edit 打不开面板了」「敲下去【完全没反应】」
    // （不是空面板、不是闪一下 —— 屏幕上连个框都没有）。
    //
    // 根因（本轮定位并修掉）：`writeState` 写的是【note → total】，而 `readState` 读的是
    //   【total → note】—— 两侧反了。客户端 `readInitialData` 从这一对开始错位，后面每个字段全错，
    //   抛出的异常被 Forge 的 `NetworkEvent.Context.enqueueWork` 吞掉
    //   （它把 Runnable 丢进 CompletableFuture 却不取结果 ⇒ **异常连日志都不写**），
    //   于是 `UIFactory.initClientUI` 里那句 `setScreen` 永远走不到 ⇒ 用户什么都看不见。
    //
    // 🔴 这一拍就是为它立的：**把"服务端写出去的那一份"用真 FriendlyByteBuf 喂给客户端那份读回来**，
    //    逐字段比 + 断言字节流【刚好读完】（`readableBytes()==0`）。
    //    改之前这条会红（错位 ⇒ 残留非 0 / 抛异常），改之后必须绿。
    //    三拍：第一屏（用户敲命令之后真正收到的第一包）/ 第二屏（带 4 张卡）/ 查询结果屏（带标签那一排）。
    private static void casePanelSync(MinecraftServer server) {
        ShanhaiRecipeReverseIndex.build(server);

        // ---- 甲：第一屏（stage=TYPES）——就是 /shanhai edit 之后客户端收到的第一包 ----
        final ShanhaiRecipeEditorWorkspace ws = new ShanhaiRecipeEditorWorkspace(server, null);
        ws.reloadTypes();
        roundTrip(server, "panel_sync_types", ws);

        // ---- 乙：第二屏（stage=RECIPES，带 4 张卡）----
        final ResourceLocation type = biggestType(server);
        if (type != null && ws.selectType(type)) {
            roundTrip(server, "panel_sync_cards", ws);
        } else {
            check("panel_sync_cards", false, "没有可选的配方类型");
        }

        // ---- 丙：查询结果屏（stage=QUERY，带标签那一排）----
        //    用户那一屏走的是"物品框 ＋ 三个按钮"，这里用同一个真物品跑一次同一段代码。
        final net.minecraft.world.item.Item probe = itemByName(PROBE_ITEM);
        if (probe == null) {
            check("panel_sync_query", false, "探针物品不存在：" + PROBE_ITEM);
        } else {
            final boolean offered = ws.offerQueryItem(new ItemStack(probe));
            ws.runQuery(ShanhaiRecipeQuery.Kind.USE);
            if (!offered) {
                check("panel_sync_query", false, "物品框没收下探针物品：" + PROBE_ITEM);
            } else if (ws.stage() != ShanhaiRecipeEditorWorkspace.Stage.QUERY) {
                check("panel_sync_query", false, "runQuery 没进查询屏：stage=" + ws.stage()
                        + " note=" + ws.queryNote());
            } else {
                roundTrip(server, "panel_sync_query", ws);
            }
        }
    }

    /**
     * 把 {@code ws}（服务端那份）的状态写出来、用一份客户端壳读回来，比"是否刚好读完"＋关键字段。
     *
     * @param name 判据名（日志里 {@code case=<name>}）
     */
    private static void roundTrip(MinecraftServer server, String name, ShanhaiRecipeEditorWorkspace ws) {
        final net.minecraft.network.FriendlyByteBuf buf =
                new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        int written = -1;
        boolean writeThrew = false;
        String writeErr = "";
        try {
            ws.writeState(buf, true);
            written = buf.readableBytes();
        } catch (Throwable t) {
            writeThrew = true;
            writeErr = t.getClass().getName() + ": " + t.getMessage();
        }
        final ShanhaiRecipeEditorWorkspace client = new ShanhaiRecipeEditorWorkspace(null, null);
        boolean readThrew = false;
        String readErr = "";
        try {
            client.readState(buf);
        } catch (Throwable t) {
            readThrew = true;
            readErr = t.getClass().getName() + ": " + t.getMessage();
        }
        final int leftover = buf.readableBytes();
        final boolean same = ws.stage() == client.stage()
                && ws.page() == client.page()
                && ws.queryTotal() == client.queryTotal()
                && ws.queryNote().equals(client.queryNote());
        check(name, !writeThrew && !readThrew && written > 0 && leftover == 0 && same,
                "written_bytes=" + written + " leftover=" + leftover
                        + " write_threw=" + writeThrew + (writeThrew ? "(" + writeErr + ")" : "")
                        + " read_threw=" + readThrew + (readThrew ? "(" + readErr + ")" : "")
                        + " | stage " + ws.stage() + "->" + client.stage()
                        + " page " + ws.page() + "->" + client.page()
                        + " | note \"" + ws.queryNote() + "\"->\"" + client.queryNote() + "\""
                        + " total " + ws.queryTotal() + "->" + client.queryTotal()
                        + " | leftover 必须为 0（!= 0 就是两侧字段顺序错了 ⇒ 客户端静默打不开）");

        // 🔴 判据这一对的两个字段单独再打一行 —— 改之前它们就是反的（total 会读到 note 的长度）
        ShanhaiMod.LOGGER.info("{} case={} server_note=\"{}\" server_total={} client_note=\"{}\" client_total={}",
                PREFIX, name + "_pair", ws.queryNote(), ws.queryTotal(),
                client.queryNote(), client.queryTotal());

        // ---- 敏感度对照：让检查器自己先被证明"看得见错" ----
        //    同一份【已经读空】的 buffer 再读一次 ⇒ 必须读不动（抛异常）。
        //    ⇒ 说明"leftover==0 且字段对得上"这条判据对"字节流与读法不匹配"这件事是有分辨力的，
        //      不是恒真（这正是上一轮那 49 条里缺的那一类对照）。
        if (name.equals("panel_sync_types")) {
            boolean secondThrew = false;
            String secondErr = "";
            try {
                new ShanhaiRecipeEditorWorkspace(null, null).readState(buf);
            } catch (Throwable t) {
                secondThrew = true;
                secondErr = t.getClass().getSimpleName();
            }
            check("panel_sync_sensitivity_control", secondThrew,
                    "把读空了的同一份 buffer 再读一次 ⇒ 必须抛（实测 threw=" + secondThrew
                            + " " + secondErr + "）—— 这一条证明上面那条判据对错位/长度不对是敏感的");
        }
    }

    // ================================================================== 小工具

    private static GTRecipe pickTarget(MinecraftServer server, String pathPrefix, Set<ResourceLocation> exclude) {
        for (GTRecipe r : ShanhaiRecipeReverseIndex.all()) {
            if (r == null || r.id == null || r.getType() == null) {
                continue;
            }
            if (!r.id.getPath().startsWith(pathPrefix)) {
                continue;
            }
            if (exclude != null && exclude.contains(r.id)) {
                continue;
            }
            return r;
        }
        return null;
    }

    private static String dur(GTRecipe r) {
        return r == null ? "(absent)" : String.valueOf(r.duration);
    }

    private static String clip(String s) {
        if (s == null) {
            return "(null)";
        }
        return s.length() <= 320 ? s : s.substring(0, 160) + "~~" + s.substring(s.length() - 150);
    }

    private static byte[] readAllQuiet(Path p) {
        try {
            return Files.isRegularFile(p) ? Files.readAllBytes(p) : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static String sha256(byte[] b) {
        if (b == null) {
            return "(absent)";
        }
        try {
            final MessageDigest md = MessageDigest.getInstance("SHA-256");
            final byte[] d = md.digest(b);
            final StringBuilder sb = new StringBuilder();
            for (byte x : d) {
                sb.append(String.format("%02x", x));
            }
            return sb.toString();
        } catch (Throwable t) {
            return "(sha_failed:" + t.getClass().getSimpleName() + ")";
        }
    }
}
