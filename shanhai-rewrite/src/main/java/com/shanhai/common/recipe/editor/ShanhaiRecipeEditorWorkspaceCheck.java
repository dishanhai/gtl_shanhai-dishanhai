package com.shanhai.common.recipe.editor;

import com.gregtechceu.gtceu.api.capability.recipe.FluidRecipeCapability;
import com.gregtechceu.gtceu.api.capability.recipe.ItemRecipeCapability;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.shanhai.ShanhaiMod;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * <b>大工作区面板的机器可判自检</b>（无头专服里跑；红线禁止开客户端，所以"渲染与手感"验不了，
 * 但"面板上那三种手势在服务端会做什么"这一半<b>能</b>验）。
 *
 * <h2>为什么这一套值得存在</h2>
 * 面板上的三种手势最终都只落到两件事上：
 * <pre>
 *   拖进来 / 右键删 / 中键改数量  ⇒ {@link ShanhaiRecipeEditorWorkspace#offerIngredient} 等缓冲动作
 *   点保存（并退出）              ⇒ {@link ShanhaiRecipeEditorOps#applyEdits}（一次调用改完 IO+电压+耗时）
 * </pre>
 * 这两件事<b>全部与控件无关</b> ⇒ 可以直接在一个有真 {@code MinecraftServer} 的进程里按顺序调一遍，
 * 再从 <b>GT 活的索引</b>与<b>原版两张表</b>里读回来对账。
 *
 * <h2>🔴 落盘那一拍是可选开关</h2>
 * 自检里"真的写一次覆盖文件"这一段只在 {@code SHANHAI_EDITOR_WRITE=1} 时跑（与第一刀同一条纪律：
 * 正常的开机绝不动 config）。跑完那一段会自己 {@code restore} 回去。
 *
 * <h2>🔴 2026-10-05 这一套当场抓到过一个真 bug</h2>
 * 第一轮读数 {@code WS_GESTURE accepted=true … json_has_probe=false} ＋ 一行
 * {@code io_cell_edited} 都没打 ⇒ 根因是 {@code ShanhaiIoTable.copyFrom} 用了
 * {@code cells.set(i, 新对象)}，把"面板看的那批"与"回写 JSON 看的那批"<b>拆成了两批</b>。
 * 那正是"界面显示拖进去了、保存却什么都没发生"这一族最难发现的错 —— 而它在无头自检里是<b>可判的</b>。
 */
public final class ShanhaiRecipeEditorWorkspaceCheck {

    private static final String PREFIX = "[SHANHAI-EDIT] editor";

    /** 自检用的目标类型（第一刀与第二刀都验过它在真机上存在）。 */
    private static final ResourceLocation TARGET_TYPE = new ResourceLocation("gtceu", "assembler");

    /** 自检往输入里塞的那个物品（第一刀用它跑通过 {@code edit_io in=6 → in=7}）。 */
    private static final ResourceLocation PROBE_ITEM = new ResourceLocation("minecraft", "dirt");

    /** 自检要切到的电压档（下拉里的第 4 档 = HV 512）。 */
    private static final int PROBE_TIER = 3;

    private ShanhaiRecipeEditorWorkspaceCheck() {}

    /** 跑一遍。任何异常都只打日志，绝不让服务端起不来。 */
    public static void run(MinecraftServer server, boolean allowWrite) {
        // 🆕 2026-10-05：B6/B7（催化剂 / 概率 / 随电压递增）那条落盘链的纯内存自检。
        //    它不需要 server、不需要客户端、不写盘 —— 放在最前面，任何环境下都会跑。
        try {
            ShanhaiIoTable.selfcheckChanceCatalyst();
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.error("{} IO_CHANCE_SELFCHECK_CRASHED_OUTER: {}", PREFIX, t.toString());
        }
        // 🆕 2026-10-05（A7）：LDLib 文本格式坑（裸 % ⇒ 整行变 "Format error: …"）的纯内存自检。
        //    同样不需要 server / 客户端 / 写盘 —— 判据复刻的是 I18n.get 里那句 String.format 本身。
        try {
            ShanhaiLdlText.selfcheck();
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.error("{} LDLABEL_SELFCHECK_CRASHED_OUTER: {}", PREFIX, t.toString());
        }
        // 🆕 2026-10-05（duration 原始值）：换算函数与 gtlcore 同形的纯内存自检。
        try {
            ShanhaiRecipeDuration.selfcheck();
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.error("{} DURATION_SELFCHECK_CRASHED_OUTER: {}", PREFIX, t.toString());
        }
        // 🆕 2026-10-05（第 6 轮）：**「一格能填多大」**那条链的纯内存自检。
        //    用户报的是「他这个不让我输入超过64的数字」。它判四件事：缓冲 / 落盘+读回 /
        //    两侧同步（真 FriendlyByteBuf）/ 上限，外加三条负对照（其中 IC6 专门证明这条检查器
        //    看得见【改动前】的行为 —— 检查器自己先被证明是对的，才允许相信它的 ❌）。
        //    同样不需要 server / 客户端 / 写盘。
        try {
            ShanhaiIoTable.selfcheckItemCountAbove64();
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.error("{} IO_ITEMCOUNT_SELFCHECK_CRASHED_OUTER: {}", PREFIX, t.toString());
        }
        if (server == null) {
            return;
        }
        // 🆕 2026-10-05（第 7 轮）：功能 A（物品查询面板）/ 功能 B（文本搜索）/ 队列 #5（时长口径）
        //    那一整组机器可判读数。放在 run0 之前跑，因为它只读不写（不改任何配方）。
        try {
            runQueryChecks(server);
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.error("{} WS_Q_SELFCHECK_CRASHED (server keeps running): {}", PREFIX, t.toString(), t);
        }
        // 🆕 第 7 轮（队列 #3）：新建配方那条链（会写盘 ⇒ 只在 allowWrite 时跑）
        if (allowWrite) {
            try {
                runNewRecipeCheck(server);
            } catch (Throwable t) {
                ShanhaiMod.LOGGER.error("{} WS_NEW_SELFCHECK_CRASHED (server keeps running): {}",
                        PREFIX, t.toString(), t);
            }
        }
        try {
            run0(server, allowWrite);
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.error("{} WS_SELFCHECK_CRASHED (server keeps running): {}", PREFIX, t.toString(), t);
        }
    }

    private static void run0(MinecraftServer server, boolean allowWrite) {
        // ── A. 三段式的数据面：类型列表 ⇒ 该类型的配方 ──
        final long t0 = System.nanoTime();
        final ShanhaiRecipeEditorWorkspace ws = new ShanhaiRecipeEditorWorkspace(server, null);
        ws.reloadTypes();
        final int ms = (int) ((System.nanoTime() - t0) / 1_000_000L);
        ShanhaiMod.LOGGER.info("{} WS_TYPES types={} stage={} ms={} (第一屏就是类型列表)",
                PREFIX, ws.typeRows().size(), ws.stage(), ms);
        if (ws.typeRows().isEmpty()) {
            ShanhaiMod.LOGGER.error("{} WS_ABORT reason=no_recipe_types", PREFIX);
            return;
        }

        final boolean typeOk = ws.selectType(TARGET_TYPE);
        ShanhaiMod.LOGGER.info("{} WS_SELECT_TYPE type={} ok={} recipes={} stage={}",
                PREFIX, TARGET_TYPE, typeOk, ws.recipeRows().size(), ws.stage());
        if (!typeOk || ws.recipeRows().isEmpty()) {
            final ShanhaiRecipeEditorWorkspace.TypeRow top = ws.typeRows().get(0);
            ws.selectType(top.id());
            ShanhaiMod.LOGGER.info("{} WS_SELECT_TYPE_FALLBACK type={} recipes={}",
                    PREFIX, top.id(), ws.recipeRows().size());
        }
        if (ws.recipeRows().isEmpty()) {
            ShanhaiMod.LOGGER.error("{} WS_ABORT reason=no_recipes_in_type", PREFIX);
            return;
        }

        // ── B. 挑一条【输入物品最多】的配方（这样"加一条/读回对账"最有区分度） ──
        final ResourceLocation targetId = pickRichest(server, ws.recipeRows());
        if (targetId == null) {
            ShanhaiMod.LOGGER.error("{} WS_ABORT reason=no_readable_recipe", PREFIX);
            return;
        }
        final boolean loadOk = ws.selectRecipe(targetId);
        final GTRecipe before = ShanhaiRecipeReverseIndex.byId(server, targetId);
        final int liveInItems = contentsCount(before, "inputs", true);
        final int liveOutItems = contentsCount(before, "outputs", true);
        ShanhaiMod.LOGGER.info("{} WS_PICK id={} live_item_inputs={} live_item_outputs={} "
                        + "(在 {} 条里挑输入最多的那条)",
                PREFIX, targetId, liveInItems, liveOutItems, ws.recipeRows().size());
        ShanhaiMod.LOGGER.info("{} WS_LOAD id={} ok={} buf_in_items={} buf_out_items={} "
                        + "buf_empty_mask={} dur={} eu={} euTier={}",
                PREFIX, targetId, loadOk,
                ws.ioTable().countItem("inputs"), ws.ioTable().countItem("outputs"),
                ws.ioTable().emptyMask(),
                before == null ? -1 : before.duration,
                before == null ? -1 : ShanhaiRecipeIoApply.euOf(before),
                before == null ? -1 : before.data.getInt("euTier"));

        // 判据①：打开一条配方什么都没动 ⇒ 缓冲里必须一处 dirty 都没有
        final boolean dirtyOnLoad = ws.ioTable().anyDirty();
        ShanhaiMod.LOGGER.info("{} WS_UNTOUCHED_ON_LOAD dirty={} mask={} PASS={} "
                        + "(没有任何一处 dirty ⇒ 原样保存不会改动配方语义)",
                PREFIX, dirtyOnLoad, ws.ioTable().dirtyMask(), !dirtyOnLoad);

        // ── C. 三种手势各来一遍（它们对应的正是面板上那三种操作） ──
        //   右键删  ⇒ clearCell        左键从 JEI 拖进来 ⇒ offerIngredient
        //   中键改数量 ⇒ openCountEditor + pendingCount + confirmCountEditor
        //   挑一个"现在有东西"的物品输入格，先右键清空，再拖 dirt 进去，最后把数量改成 7
        //   —— 这样"清空"「拖入」「改数量」三条路都真的走过，而且保存后能从索引读回对账。
        int cellIdx = -1;
        for (int i = 0; i < ShanhaiIoTable.ITEM_IN; i++) {
            final ShanhaiIoTable.Cell c = ws.cell(i);
            if (c != null && !c.empty()) {
                cellIdx = i;
                break;
            }
        }
        if (cellIdx < 0) {
            for (int i = 0; i < ShanhaiIoTable.ITEM_IN; i++) {
                if (ws.cell(i) != null && ws.cell(i).empty()) {
                    cellIdx = i;
                    break;
                }
            }
        }
        final String wasBefore = cellIdx >= 0 ? ws.cell(cellIdx).describe() : "(none)";
        final boolean clearOk = cellIdx >= 0 && ws.clearCell(cellIdx);
        ShanhaiMod.LOGGER.info("{} WS_GESTURE_CLEAR cell={} was={} ok={} now={} (右键删这一个格子)",
                PREFIX, cellIdx, wasBefore, clearOk,
                cellIdx >= 0 ? ws.cell(cellIdx).describe() : "(none)");

        final ItemStack probe = new ItemStack(BuiltInRegistries.ITEM.get(PROBE_ITEM), 3);
        final boolean dragOk = cellIdx >= 0 && ws.offerIngredient(cellIdx, probe);
        ShanhaiMod.LOGGER.info("{} WS_GESTURE_DRAG cell={} itemKind={} accepted={} probe={} probe_empty={} "
                        + "now={} dirty_mask={}",
                PREFIX, cellIdx, cellIdx >= 0 && ws.cell(cellIdx) != null && ws.cell(cellIdx).itemKind,
                dragOk, stackName(probe), probe.isEmpty(),
                cellIdx >= 0 ? ws.cell(cellIdx).describe() : "(none)", ws.ioTable().dirtyMask());

        final boolean openOk = cellIdx >= 0 && ws.openCountEditor(cellIdx);
        ws.setPendingCount(7);
        final boolean countOk = openOk && ws.confirmCountEditor();
        ShanhaiMod.LOGGER.info("{} WS_GESTURE_COUNT cell={} opened={} set=7 confirmed={} now={} (中键改数量)",
                PREFIX, cellIdx, openOk, countOk,
                cellIdx >= 0 ? ws.cell(cellIdx).describe() : "(none)");

        final String json = ws.ioTable().json("inputs").toString();
        ShanhaiMod.LOGGER.info("{} WS_GESTURE_JSON len={} has_probe={} has_count7={} json={}",
                PREFIX, json.length(), json.contains("minecraft:dirt"), json.contains("\"count\":7"),
                clip(json, 220));

        // ── C2. 手势二/三：耗时框里敲数 ＋ 电压下拉选一档 ──
        final int durBefore = before == null ? -1 : before.duration;
        final int durWant = durBefore + 2;
        final long euWant = ShanhaiRecipeEditorWorkspace.TIER_VOLTAGE[PROBE_TIER];
        ws.setPendingDuration(durWant);
        ws.setTierByName(ShanhaiRecipeEditorWorkspace.TIER_NAMES[PROBE_TIER]);
        ShanhaiMod.LOGGER.info("{} WS_NUMBERS dur {} -> {} ; eu {} -> {} (下拉第 {} 档 = {})",
                PREFIX, durBefore, ws.pendingDuration(),
                before == null ? -1 : ShanhaiRecipeIoApply.euOf(before), ws.pendingEut(),
                PROBE_TIER + 1, ShanhaiRecipeEditorWorkspace.TIER_NAMES[PROBE_TIER]);

        // ── D. 保存 ⇒ 同一拍从【活的 GT 索引】与【原版两张表】各读一次对账 ──
        if (!allowWrite) {
            ShanhaiMod.LOGGER.info("{} WS_SAVE_SKIPPED reason=env_write_off "
                    + "(要跑落盘那一拍就设 SHANHAI_EDITOR_WRITE=1)", PREFIX);
            return;
        }
        final ShanhaiRecipeEditorOps.Result saved = ws.save();
        final GTRecipe inIndex = ShanhaiRecipeReverseIndex.byId(server, targetId);
        final String indexJson = inIndex == null ? "" : ShanhaiRecipeIoApply.tableJson(inIndex, "inputs").toString();
        final GTRecipe inVanilla = ShanhaiRecipeEditorOps.readFromVanilla(
                server, before == null ? null : before.getType(), targetId);
        final String vanillaJson = inVanilla == null ? ""
                : ShanhaiRecipeIoApply.tableJson(inVanilla, "inputs").toString();

        ShanhaiMod.LOGGER.info("{} WS_SAVE ok={} persisted={} index_ms={} vanilla_ms={} detail={}",
                PREFIX, saved.ok(), saved.persisted(), saved.indexMs(), saved.vanillaMs(), saved.detail());
        ShanhaiMod.LOGGER.info("{} WS_READBACK_INDEX id={} dur={} (期望 {}) eu={} (期望 {}) euTier={} "
                        + "in_items={} contains_probe={} probe_count7={}",
                PREFIX, targetId,
                inIndex == null ? -1 : inIndex.duration, durWant,
                inIndex == null ? -1 : ShanhaiRecipeIoApply.euOf(inIndex), euWant,
                inIndex == null ? -1 : inIndex.data.getInt("euTier"),
                ws.ioTable().countItem("inputs"), indexJson.contains("minecraft:dirt"),
                indexJson.contains("\"count\":7"));
        ShanhaiMod.LOGGER.info("{} WS_READBACK_VANILLA id={} present={} dur={} eu={} contains_probe={}",
                PREFIX, targetId, inVanilla != null,
                inVanilla == null ? -1 : inVanilla.duration,
                inVanilla == null ? -1 : ShanhaiRecipeIoApply.euOf(inVanilla),
                vanillaJson.contains("minecraft:dirt"));

        // ── E. 恢复原样（自检自己收拾干净），并核对三样都退回去了 ──
        final ShanhaiRecipeEditorOps.Result restored = ws.restoreSelected();
        final GTRecipe reverted = ShanhaiRecipeReverseIndex.byId(server, targetId);
        final String revertedJson = reverted == null ? "" : ShanhaiRecipeIoApply.tableJson(reverted, "inputs").toString();
        ShanhaiMod.LOGGER.info("{} WS_RESTORE ok={} persisted={} detail={}", PREFIX,
                restored.ok(), restored.persisted(), restored.detail());
        ShanhaiMod.LOGGER.info("{} WS_AFTER_RESTORE id={} contains_probe={} dur={} (期望 {}) eu={} (期望 {}) PASS={}",
                PREFIX, targetId, revertedJson.contains("minecraft:dirt"),
                reverted == null ? -1 : reverted.duration, durBefore,
                reverted == null ? -1 : ShanhaiRecipeIoApply.euOf(reverted),
                before == null ? -1 : ShanhaiRecipeIoApply.euOf(before),
                reverted != null && !revertedJson.contains("minecraft:dirt")
                        && reverted.duration == durBefore);

        // ══════════════════════════════════════════════════════════════════════════════════
        // F. 🆕 2026-10-05（第 6 轮）对用户那句判据的【端到端】复现
        //    「把那一格填成 99999 ⇒ 保存后读回来还是 99999（不是 64）」
        //
        //    为什么单独做这一拍（而不是改上面那段 set=7）：
        //      上一轮的 10 条验收全部建立在"这一格 = 7"的读数上（WS_GESTURE_JSON 的
        //      has_count7、WS_READBACK_INDEX 的 probe_count7）⇒ 动它 = 推翻已通过的验收。
        //      本拍【纯新增】，跑在这一段的最后，走一次独立的 改 → 存 → 从活索引读回 → 恢复。
        //
        //    判据（可 grep）：
        //      WS_BIG_COUNT_BUF    … 缓冲里就是 99999
        //      WS_BIG_COUNT_LIVE   … 保存后【活的 GT 索引】里那条输入 json 的 count == 99999
        //      WS_BIG_COUNT_NEG    … 负对照：旧公式 min(99999, maxStackSize) == 64
        //                            （证明上面两条不是恒真；这一条挂了说明检查器写错了）
        //      WS_BIG_COUNT_PASS   … 三条一起
        // ══════════════════════════════════════════════════════════════════════════════════
        final int bigWant = 99999;
        final ItemStack bigProbe = new ItemStack(BuiltInRegistries.ITEM.get(PROBE_ITEM));
        final int bigOldFormula = Math.min(bigWant, bigProbe.getMaxStackSize() <= 0 ? 64
                : bigProbe.getMaxStackSize());
        boolean bigOpen = false;
        boolean bigConfirm = false;
        if (cellIdx >= 0) {
            ws.offerIngredient(cellIdx, new ItemStack(BuiltInRegistries.ITEM.get(PROBE_ITEM), 1));
            bigOpen = ws.openCountEditor(cellIdx);
            ws.setPendingCount(bigWant);
            bigConfirm = ws.confirmCountEditor();
        }
        final int bigInBuffer = cellIdx >= 0 && ws.cell(cellIdx) != null ? ws.cell(cellIdx).shownCount() : -1;
        final String bigJsonBuf = ws.ioTable().json("inputs").toString();
        final ShanhaiRecipeEditorOps.Result bigSaved = ws.save();
        final GTRecipe bigLive = ShanhaiRecipeReverseIndex.byId(server, targetId);
        final String bigJsonLive = bigLive == null ? ""
                : ShanhaiRecipeIoApply.tableJson(bigLive, "inputs").toString();
        final boolean bigBufOk = bigInBuffer == bigWant && bigJsonBuf.contains("\"count\":" + bigWant);
        final boolean bigLiveOk = bigJsonLive.contains("\"count\":" + bigWant);
        final boolean bigNegOk = bigOldFormula == 64;
        ShanhaiMod.LOGGER.info("{} WS_BIG_COUNT_BUF cell={} opened={} confirmed={} buffer={} (期望 {}) "
                        + "json_has={} PASS={} (改这一格的数量之后，缓冲层就已经是 {} ；"
                        + "改动前这一层会把它压成 {})",
                PREFIX, cellIdx, bigOpen, bigConfirm, bigInBuffer, bigWant,
                bigJsonBuf.contains("\"count\":" + bigWant), bigBufOk, bigWant, bigOldFormula);
        ShanhaiMod.LOGGER.info("{} WS_BIG_COUNT_LIVE id={} save_ok={} persisted={} live_json_has_{}={} PASS={}",
                PREFIX, targetId, bigSaved.ok(), bigSaved.persisted(), bigWant, bigLiveOk, bigLiveOk);
        ShanhaiMod.LOGGER.info("{} WS_BIG_COUNT_NEG 旧公式 min({}, maxStackSize)={} (期望 64) PASS={} "
                        + "(负对照：本条挂了 ⇒ 上面的正对照没有判别力，检查器自己错了)",
                PREFIX, bigWant, bigOldFormula, bigNegOk);
        ShanhaiMod.LOGGER.info("{} WS_BIG_COUNT_PASS buffered={} live={} neg={} PASS={}",
                PREFIX, bigBufOk, bigLiveOk, bigNegOk, bigBufOk && bigLiveOk && bigNegOk);
        final ShanhaiRecipeEditorOps.Result bigRestored = ws.restoreSelected();
        final GTRecipe bigReverted = ShanhaiRecipeReverseIndex.byId(server, targetId);
        final String bigJsonReverted = bigReverted == null ? ""
                : ShanhaiRecipeIoApply.tableJson(bigReverted, "inputs").toString();
        ShanhaiMod.LOGGER.info("{} WS_BIG_COUNT_RESTORE ok={} persisted={} live_has_{}={} (期望 false) PASS={}",
                PREFIX, bigRestored.ok(), bigRestored.persisted(), bigWant,
                bigJsonReverted.contains("\"count\":" + bigWant),
                !bigJsonReverted.contains("\"count\":" + bigWant));
    }

    /** 取"输入物品条数最多"的那一条（读不出来就退化成第一条）。 */
    private static ResourceLocation pickRichest(MinecraftServer server, List<ResourceLocation> ids) {
        ResourceLocation best = null;
        int bestCount = -1;
        for (int i = 0; i < ids.size(); i++) {
            final GTRecipe r = ShanhaiRecipeReverseIndex.byId(server, ids.get(i));
            if (r == null) {
                continue;
            }
            final int n = contentsCount(r, "inputs", true);
            if (n > bestCount) {
                bestCount = n;
                best = ids.get(i);
            }
        }
        return best != null ? best : (ids.isEmpty() ? null : ids.get(0));
    }

    private static int contentsCount(GTRecipe r, String which, boolean item) {
        if (r == null) {
            return -1;
        }
        final java.util.Map<com.gregtechceu.gtceu.api.capability.recipe.RecipeCapability<?>,
                List<com.gregtechceu.gtceu.api.recipe.content.Content>> t =
                "inputs".equals(which) ? r.inputs : r.outputs;
        if (t == null) {
            return 0;
        }
        final List<com.gregtechceu.gtceu.api.recipe.content.Content> list = t.get(item
                ? ItemRecipeCapability.CAP : FluidRecipeCapability.CAP);
        return list == null ? 0 : list.size();
    }

    private static String stackName(ItemStack stack) {
        if (stack == null) {
            return "null";
        }
        if (stack.isEmpty()) {
            return "(空)";
        }
        return BuiltInRegistries.ITEM.getKey(stack.getItem()) + " x" + stack.getCount();
    }

    private static String clip(String s, int n) {
        if (s == null) {
            return "";
        }
        return s.length() <= n ? s : s.substring(0, n) + "…";
    }

    // ================================================================= 🆕 第 7 轮：查询与卡片

    /**
     * <b>第 7 轮新增功能的机器可判自检</b>（用户点单的功能 A / 功能 B / 队列 #3 / 队列 #5）。
     *
     * <p>判据分四组（每一行都自带 PASS 字段，且每组都配了负对照 —— 检查器自己先被证明是对的）：
     * <pre>
     *   WS_Q_SOURCE    获取途径    —— 这个物品【怎么来的】
     *   WS_Q_USE       作为物品的用处 —— 它【被谁当材料吃】
     *   WS_Q_MACHINE   作为机器的用处 —— 它【当机器时能跑哪些配方】
     *   WS_Q_TEXT      文本搜索    —— 配方 id / 种类中文名 / 种类 id 三条口径都要能命中
     *   WS_Q_TEXT_NEG  负对照      —— 一个绝不可能命中的乱码关键词必须 0 命中
     *   WS_CARD_BUILD  卡片内容    —— 图标数、耗时、电压档、电流、id 都在
     *   WS_CARD_WIRE   两侧同步    —— 卡片走一遍真 FriendlyByteBuf 之后逐字段不变
     *   WS_TAB         顶部那一排  —— 分组数、图标、标签
     *   WS_DUR_REAL    真实耗时    —— 实际 = 原始 × k，且"原始"就是面板上那个数
     * </pre>
     *
     * <p>⚠️ 卡片<b>长什么样</b>验不了（红线禁止开客户端）—— 这里验的是"卡片上有什么数据"。
     */
    public static void runQueryChecks(MinecraftServer server) {
        // 探针物品：gtceu:programmed_circuit —— 输入侧实测命中 4110 条（上一轮就有真值来源）
        final net.minecraft.world.item.Item probe =
                BuiltInRegistries.ITEM.get(ShanhaiRecipeReverseIndex.PROBE_ITEM);
        if (probe == null) {
            ShanhaiMod.LOGGER.error("{} WS_Q_ABORT reason=probe_item_not_registered ({})",
                    PREFIX, ShanhaiRecipeReverseIndex.PROBE_ITEM);
            return;
        }
        ShanhaiRecipeReverseIndex.ensure(server);

        // ── ① 三条按物品查 ──────────────────────────────────────────────
        final ShanhaiRecipeQuery.Result src = ShanhaiRecipeQuery.byOutput(server, probe);
        final ShanhaiRecipeQuery.Result use = ShanhaiRecipeQuery.byInput(server, probe);
        final int linearOut = ShanhaiRecipeReverseIndex.linearQueryByOutputItems(server, probe).size();
        final int linearIn = ShanhaiRecipeReverseIndex.linearQueryByItems(server, probe).size();
        final boolean srcOk = src.total() == linearOut && src.total() > 0;
        final boolean useOk = use.total() == linearIn && use.total() > 0;
        ShanhaiMod.LOGGER.info("{} WS_Q_SOURCE item={} total={} groups={} linear_control={} PASS={} "
                        + "（获取途径：哪些配方的【输出】里有它；对照路 = 独立实现的线性扫）",
                PREFIX, ShanhaiRecipeReverseIndex.PROBE_ITEM, src.total(), src.groups().size(),
                linearOut, srcOk);
        ShanhaiMod.LOGGER.info("{} WS_Q_USE item={} total={} groups={} linear_control={} PASS={} "
                        + "（作为物品的用处：哪些配方的【输入】里有它）",
                PREFIX, ShanhaiRecipeReverseIndex.PROBE_ITEM, use.total(), use.groups().size(),
                linearIn, useOk);
        ShanhaiMod.LOGGER.info("{} WS_Q_SOURCE_NEG 拿一个没被任何配方产出的物品当负对照 "
                        + "（期望 total=0）在 WS_Q_SOURCE_EMPTY 那一行",
                PREFIX);

        // 负对照：一个几乎不可能有输出的物品（空气）⇒ 必须 0 命中
        final ShanhaiRecipeQuery.Result emptySrc = ShanhaiRecipeQuery.byOutput(
                server, net.minecraft.world.item.Items.AIR);
        ShanhaiMod.LOGGER.info("{} WS_Q_SOURCE_EMPTY item=minecraft:air total={} PASS={} "
                        + "（负对照：空气不该有任何获取途径；这一条挂了说明检查器把什么都算命中）",
                PREFIX, emptySrc.total(), emptySrc.total() == 0);

        // ② 作为机器的用处：拿一个真正的 GT 机器方块试
        final net.minecraft.world.item.Item machineProbe = pickMachineProbe();
        final ShanhaiRecipeQuery.Result mach = ShanhaiRecipeQuery.asMachine(
                server, machineProbe == null ? probe : machineProbe);
        ShanhaiMod.LOGGER.info("{} WS_Q_MACHINE item={} total={} groups={} picked_a_real_machine={} PASS={}",
                PREFIX, machineProbe == null ? ShanhaiRecipeReverseIndex.PROBE_ITEM
                        : BuiltInRegistries.ITEM.getKey(machineProbe),
                mach.total(), mach.groups().size(), machineProbe != null,
                machineProbe != null && mach.total() > 0 && mach.groups().size() > 0);

        // ── ③ 文本搜索：三条口径各来一个正对照 ──────────────────────────
        //   ① 配方 id 片段（英文）
        final String idFrag = pickIdFragment(server);
        final ShanhaiRecipeQuery.Result t1 = ShanhaiRecipeQuery.byText(server, idFrag);
        //   ② 配方种类 id 片段（英文）
        final String typeFrag = pickTypeFragment(server);
        final ShanhaiRecipeQuery.Result t2 = ShanhaiRecipeQuery.byText(server, typeFrag);
        //   ③ 配方种类【中文名】—— 这一条是用户点名的"中文匹配要能吃语言文件里的中文名"
        //      两条并集：① 活语言表（单机 = 客户端语言，中文）；② 直接读 assets/*/lang/zh_cn.json
        //      ⚠️ 无头专服没有客户端语言 ⇒ ①给出的是英文（冒烟第一版读数 zh_type_name=Macerator）
        //         ⇒ 真正能在专服上验中文的是 ②，所以下面两条分别打。
        final String liveType = ShanhaiRecipeEditorSession.typeName("macerator").text();
        final String fileZh = ShanhaiRecipeQuery.zhCnNameOf(new ResourceLocation("gtceu", "macerator"));
        final ShanhaiRecipeQuery.Result t3 = ShanhaiRecipeQuery.byText(server, liveType);
        final ShanhaiRecipeQuery.Result t3zh = fileZh.isEmpty() ? null
                : ShanhaiRecipeQuery.byText(server, fileZh);
        final ShanhaiRecipeQuery.Result neg = ShanhaiRecipeQuery.byText(server, "zzz_绝对不存在的关键词_qqq");
        ShanhaiMod.LOGGER.info("{} WS_Q_TEXT id_frag={} hits={} PASS={}", PREFIX, idFrag, t1.total(),
                t1.total() > 0);
        ShanhaiMod.LOGGER.info("{} WS_Q_TEXT type_frag={} hits={} PASS={}", PREFIX, typeFrag, t2.total(),
                t2.total() > 0);
        ShanhaiMod.LOGGER.info("{} WS_Q_TEXT_LIVE live_type_name={} hits={} PASS={} "
                        + "（活语言表那条路；无头专服上给的是英文，单机上才是中文）",
                PREFIX, liveType, t3.total(), t3.total() > 0 && t3.groups().size() > 0);
        ShanhaiMod.LOGGER.info("{} WS_Q_TEXT_ZH zh_cn_file_name={} hits={} PASS={} "
                        + "（🔴 这一条才是中文匹配的硬判据：名字从 assets/gtceu/lang/zh_cn.json 现读，专服也吃）",
                PREFIX, fileZh.isEmpty() ? "(读不到)" : fileZh,
                t3zh == null ? -1 : t3zh.total(),
                t3zh != null && t3zh.total() > 0 && t3zh.groups().size() > 0);
        ShanhaiMod.LOGGER.info("{} WS_Q_TEXT_NEG query=zzz_绝对不存在的关键词_qqq hits={} PASS={} "
                        + "（负对照：乱码关键词必须一条都不命中）",
                PREFIX, neg.total(), neg.total() == 0);

        // ── ④ 卡片内容（服务端算出来的那一份）──────────────────────────
        final GTRecipe cardRecipe = use.total() > 0 ? firstRecipeOf(server, use) : null;
        if (cardRecipe == null) {
            ShanhaiMod.LOGGER.error("{} WS_CARD_ABORT reason=no_recipe_for_card", PREFIX);
        } else {
            final ShanhaiRecipeQuery.Card card = ShanhaiRecipeQuery.cardOf(cardRecipe, false);
            final boolean hasIns = !card.ins().isEmpty();
            final boolean hasOuts = !card.outs().isEmpty();
            final boolean hasId = card.id() != null && !card.shortId().isEmpty();
            // 🔴 冒烟第一版这里判红了：gtceu:packer/hay_block 的 EU/t = 2，比 GT 最低一档
            //    ULV = 8V 还小 ⇒ tierOf 反查出 -1。那不是错，是个真边界
            //    （卡片显示时归到 ULV，见 ShanhaiRecipeCardWidget#displayTier）。
            final boolean tierOk = card.tierIndex() >= 0 || card.eut() < 8L;
            final boolean ampsOk = card.amperage() >= 1;
            ShanhaiMod.LOGGER.info("{} WS_CARD_BUILD id={} ins={} outs={} ins_more={} outs_more={} "
                            + "original={} live_dur={} eut={} tier={} amps={} type_name={} "
                            + "sub_ulv={} PASS={}",
                    PREFIX, card.id(), card.ins().size(), card.outs().size(), card.insMore(), card.outsMore(),
                    card.original(), card.duration(), card.eut(), card.tierIndex(), card.amperage(),
                    card.typeName(), card.eut() < 8L, hasIns && hasOuts && hasId && tierOk && ampsOk);

            // ── ⑤ 两侧同步：真 FriendlyByteBuf 走一圈 ──
            final io.netty.buffer.ByteBuf buf = io.netty.buffer.Unpooled.buffer();
            final net.minecraft.network.FriendlyByteBuf fbb =
                    new net.minecraft.network.FriendlyByteBuf(buf);
            ShanhaiRecipeQuery.writeCard(fbb, card);
            final int wireBytes = fbb.readableBytes();
            final ShanhaiRecipeQuery.Card back = ShanhaiRecipeQuery.readCard(fbb);
            final boolean wireOk = back.id() != null && back.id().equals(card.id())
                    && back.ins().size() == card.ins().size()
                    && back.outs().size() == card.outs().size()
                    && back.duration() == card.duration()
                    && back.original() == card.original()
                    && back.eut() == card.eut()
                    && back.tierIndex() == card.tierIndex()
                    && back.amperage() == card.amperage()
                    && sameChips(back.ins(), card.ins())
                    && sameChips(back.outs(), card.outs());
            ShanhaiMod.LOGGER.info("{} WS_CARD_WIRE wire_bytes={} id_back={} ins_back={} outs_back={} "
                            + "dur_back={} eu_back={} PASS={}",
                    PREFIX, wireBytes, back.id(), back.ins().size(), back.outs().size(),
                    back.duration(), back.eut(), wireOk);
            // 负对照：把数量改一位再比 ⇒ 必须判不等（证明 sameChips 真的在看数量）
            final java.util.List<ShanhaiRecipeQuery.Chip> tampered = new java.util.ArrayList<>(card.ins());
            if (!tampered.isEmpty()) {
                final ShanhaiRecipeQuery.Chip c0 = tampered.get(0);
                tampered.set(0, new ShanhaiRecipeQuery.Chip(c0.item(), c0.id(), c0.count() + 1));
            }
            ShanhaiMod.LOGGER.info("{} WS_CARD_WIRE_NEG tampered_count_detected={} PASS={} "
                            + "（负对照：把某个图标的数量 +1 之后必须判不等）",
                    PREFIX, !sameChips(tampered, card.ins()), !sameChips(tampered, card.ins()));
        }

        // ── ⑥ 顶部那一排（按配方种类分组）──
        final ShanhaiRecipeQuery.Result tabSrc = use.total() > 0 ? use : src;
        final ShanhaiRecipeQuery.Tab tab0 = tabSrc.groups().isEmpty() ? null
                : ShanhaiRecipeQuery.tabOf(tabSrc.groups().get(0), true);
        ShanhaiMod.LOGGER.info("{} WS_TAB groups={} tab0_label={} tab0_icon={} tab0_count={} tab0_selected={} PASS={} "
                        + "（标签用中文名；图标 = 该类型的机器方块，拿不到就 hasIcon=false 画灰框）",
                PREFIX, tabSrc.groups().size(), tab0 == null ? "(none)" : tab0.label(),
                tab0 == null ? "(none)" : String.valueOf(tab0.hasIcon()),
                tab0 == null ? -1 : tab0.count(),
                tab0 == null ? "(none)" : String.valueOf(tab0.selected()),
                tab0 != null && tab0.count() > 0);

        // 两侧同步：标签页走一遍真 buf
        if (tab0 != null) {
            final io.netty.buffer.ByteBuf tb = io.netty.buffer.Unpooled.buffer();
            final net.minecraft.network.FriendlyByteBuf tf = new net.minecraft.network.FriendlyByteBuf(tb);
            ShanhaiRecipeQuery.writeTab(tf, tab0);
            final ShanhaiRecipeQuery.Tab tb2 = ShanhaiRecipeQuery.readTab(tf);
            ShanhaiMod.LOGGER.info("{} WS_TAB_WIRE label_back={} icon_back={} count_back={} PASS={}",
                    PREFIX, tb2.label(), tb2.iconId(), tb2.count(),
                    tb2.label().equals(tab0.label()) && tb2.count() == tab0.count()
                            && tb2.hasIcon() == tab0.hasIcon());
        }

        // ── ⑦ 队列 #5：原始耗时 vs 真实耗时 ─────────────────────────────
        final GTRecipe durProbe = pickDurationProbe(server);
        if (durProbe == null) {
            ShanhaiMod.LOGGER.error("{} WS_DUR_ABORT reason=no_recipe", PREFIX);
        } else {
            final int original = ShanhaiRecipeDuration.originalOf(durProbe);
            final int live = durProbe.duration;
            ShanhaiMod.LOGGER.info("{} WS_DUR_REAL id={} original={} live={} same={} PASS={} "
                            + "（第 2/3 屏显示的两条时间就是这个口径：原始 = 面板可改的那个数，"
                            + "实际 = 机器真正跑的那个数）",
                    PREFIX, durProbe.id, original, live, original == live, true);
            ShanhaiMod.LOGGER.info("{} WS_DUR_NEG 负对照：原始值与实际值【不相等】的配方存在吗 = {} "
                            + "（若全相等，则上面那条读数没有判别力）",
                    PREFIX, original != live);
        }
        ShanhaiMod.LOGGER.info("{} WS_Q_SELFTEST_DONE", PREFIX);
    }

    /**
     * 🆕 第 7 轮（队列 #3）：「新建配方」那条链的端到端自检。
     *
     * <h4>为什么值得单独跑</h4>
     * {@code op=add} 那条覆盖层通道<b>在本轮之前从来没被走过</b>（脚本里早就写着那一支，
     * 但编辑器从来没写过这个 op）⇒ 它是本轮最"看起来像好了其实没验过"的一段。
     * 这里把四步逐条打出来：造 → 进底本 → 进 GT 索引 → 进原版表 → 落盘条目形状对不对。
     *
     * <p>⚠️ <b>只在 {@code allowWrite} 时跑</b>（它会真的造一条配方并写一条 op=add 条目），
     * 跑完自己把落盘条目撤掉。内存里那条新配方<b>收不回来</b>（底本没有"删一条底本"的出口）
     * —— 但它只活在这一次进程里，冒烟装置本来就是一次性的。
     */
    public static void runNewRecipeCheck(MinecraftServer server) {
        final ResourceLocation typeId = new ResourceLocation("gtceu", "assembler");
        final com.gregtechceu.gtceu.api.recipe.GTRecipeType type =
                com.gregtechceu.gtceu.api.registry.GTRegistries.RECIPE_TYPES.get(typeId);
        if (type == null) {
            ShanhaiMod.LOGGER.error("{} WS_NEW_ABORT reason=type_not_registered type={}", PREFIX, typeId);
            return;
        }
        ShanhaiRecipeReverseIndex.ensure(server);
        ResourceLocation newId = null;
        for (int i = 1; i <= 50; i++) {
            final ResourceLocation cand = new ResourceLocation("shanhai",
                    typeId.getPath() + "/new_recipe_" + i);
            if (ShanhaiRecipeReverseIndex.byId(server, cand) == null
                    && ShanhaiRecipeBase.pristine(cand) == null) {
                newId = cand;
                break;
            }
        }
        if (newId == null) {
            ShanhaiMod.LOGGER.error("{} WS_NEW_ABORT reason=no_free_id", PREFIX);
            return;
        }
        final int dur = ShanhaiRecipeEditorWorkspace.DEFAULT_NEW_DURATION;
        final long eut = ShanhaiRecipeEditorWorkspace.DEFAULT_NEW_EUT;
        final com.google.gson.JsonObject json = emptyNewRecipeJson(typeId.toString(), dur, eut);

        final int indexBefore = ShanhaiRecipeEditorOps.indexSizeOf(type);
        final GTRecipe made = ShanhaiRecipeEditorOps.addRecipeFromJson(server, newId, json);
        final int indexAfter = ShanhaiRecipeEditorOps.indexSizeOf(type);
        final GTRecipe backIndex = made == null ? null
                : ShanhaiRecipeEditorOps.readFromIndex(type, newId);
        final GTRecipe backVanilla = ShanhaiRecipeEditorOps.readFromVanilla(server, type, newId);
        final long eutBack = made == null ? -1 : ShanhaiRecipeIoApply.euOf(made);
        // 🔴 冒烟第一版把"索引里必须有"当成了硬判据，结果判红 —— 那不是 bug：
        //    GT 的 GTRecipeLookup 是一棵【按原料建的树】，一条**没有任何输入**的配方
        //    本来就插不进那棵树（读数：wanted=2553 added=2553 refused=0 而 tree_after 仍是 2550）。
        //    空配方的可达通道是【原版配方表】（第二屏的列表就是从它建的）⇒
        //    真正的硬判据是"原版表里读得到"，索引那一条改成"有 IO 之后必须能进索引"（见下面第二段）。
        final boolean ok = made != null && backVanilla != null && eutBack == eut;
        ShanhaiMod.LOGGER.info("{} WS_NEW_CREATE id={} built={} index_before={} index_after={} "
                        + "read_from_index={} read_from_vanilla={} eut={} eut_back={} dur={} PASS={} "
                        + "（空配方：进原版表即算成功 —— 列表就是从那建出来的；"
                        + "GT 索引那棵树要等它有输入之后才装得进去）",
                PREFIX, newId, made != null, indexBefore, indexAfter,
                backIndex != null, backVanilla != null, eut, eutBack, dur, ok);

        // 🔴 第二段（这才是"能跑"的硬判据）：造一条**有输入输出**的新配方 ⇒ 必须进 GT 索引
        ResourceLocation ioId = null;
        for (int i = 1; i <= 50; i++) {
            final ResourceLocation cand = new ResourceLocation("shanhai",
                    typeId.getPath() + "/new_recipe_io_" + i);
            if (ShanhaiRecipeReverseIndex.byId(server, cand) == null
                    && ShanhaiRecipeBase.pristine(cand) == null) {
                ioId = cand;
                break;
            }
        }
        if (ioId != null) {
            final com.google.gson.JsonObject ioJson = emptyNewRecipeJson(typeId.toString(), 100, 30L);
            final com.google.gson.JsonObject inItem = new com.google.gson.JsonObject();
            final com.google.gson.JsonObject inIng = new com.google.gson.JsonObject();
            inIng.addProperty("item", "minecraft:dirt");
            inItem.addProperty("type", "gtceu:sized");
            inItem.addProperty("count", 1);
            inItem.add("ingredient", inIng);
            final com.google.gson.JsonObject inEntry = new com.google.gson.JsonObject();
            inEntry.add("content", inItem);
            inEntry.addProperty("chance", 10000);
            inEntry.addProperty("maxChance", 10000);
            inEntry.addProperty("tierChanceBoost", 0);
            final com.google.gson.JsonArray inArr = new com.google.gson.JsonArray();
            inArr.add(inEntry);
            final com.google.gson.JsonObject inGroups = new com.google.gson.JsonObject();
            inGroups.add("item", inArr);
            ioJson.add("inputs", inGroups);
            ioJson.add("outputs", inGroups.deepCopy());
            final int before2 = ShanhaiRecipeEditorOps.indexSizeOf(type);
            final GTRecipe made2 = ShanhaiRecipeEditorOps.addRecipeFromJson(server, ioId, ioJson);
            final int after2 = ShanhaiRecipeEditorOps.indexSizeOf(type);
            final GTRecipe back2 = made2 == null ? null
                    : ShanhaiRecipeEditorOps.readFromIndex(type, ioId);
            ShanhaiMod.LOGGER.info("{} WS_NEW_CREATE_IO id={} built={} index_before={} index_after={} "
                            + "read_from_index={} PASS={} （有输入输出的新配方必须进 GT 索引 —— "
                            + "这才是机器能跑它的判据）",
                    PREFIX, ioId, made2 != null, before2, after2, back2 != null,
                    made2 != null && back2 != null && after2 == before2 + 1);
        }

        // 🔴 负对照：同一个 id 再建一次【必须失败】（底本里已有 ⇒ registerNew 拒收，绝不覆盖）
        final GTRecipe again = ShanhaiRecipeEditorOps.addRecipeFromJson(server, newId, json);
        ShanhaiMod.LOGGER.info("{} WS_NEW_DUP id={} second_attempt_null={} PASS={} "
                        + "（负对照：同 id 再建必须被拒 —— 否则就是静默覆盖既有配方）",
                PREFIX, newId, again == null, again == null);

        boolean entryOk = false;
        try {
            final com.google.gson.JsonObject e = ShanhaiRecipeOverrideStore.makeAddEntry(
                    ShanhaiRecipeOverrideStore.nextUid(), newId.toString(), typeId.toString(), json);
            entryOk = "add".equals(e.has("op") ? e.get("op").getAsString() : "")
                    && e.has("recipe") && e.get("recipe").isJsonObject()
                    && e.getAsJsonObject("recipe").has("type");
            ShanhaiMod.LOGGER.info("{} WS_NEW_ENTRY op=add recipe_present={} shape_ok={} PASS={} "
                            + "（这条 entry 的形状 = 覆盖层脚本 op=add 那一支读的形状）",
                    PREFIX, e.has("recipe"), entryOk, entryOk);
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.error("{} WS_NEW_ENTRY_FAILED err={}", PREFIX, t.toString());
        }

        try {
            final int dropped = ShanhaiRecipeOverrideStore.removeEntryById(newId);
            ShanhaiMod.LOGGER.info("{} WS_NEW_CLEANUP op_add_entry_dropped={} (期望 >= 0，-1 = 文件里本来就没有)",
                    PREFIX, dropped);
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.warn("{} WS_NEW_CLEANUP_FAILED err={}", PREFIX, t.toString());
        }
        ShanhaiMod.LOGGER.info("{} WS_NEW_SELFTEST_DONE create_ok={} entry_ok={} PASS={}",
                PREFIX, ok, entryOk, ok && entryOk);
    }

    /**
     * 一份"空 IO"的新配方 JSON（形状逐字抄自用户实例的导出文件
     * {@code local/kubejs/export/recipes/ad_astra/assembler/calorite_engine.json}）。
     *
     * <p>🔴 冒烟第一版漏了 {@code tickInputs.eu} ⇒ 读数 {@code add_recipe_applied eut=0}：
     * 只有 {@code data.euTier} 而没有真实的 EU ⇒ 耗能是 0。
     */
    private static com.google.gson.JsonObject emptyNewRecipeJson(String type, int dur, long eut) {
        final com.google.gson.JsonObject json = new com.google.gson.JsonObject();
        json.addProperty("type", type);
        json.addProperty("duration", dur);
        final com.google.gson.JsonObject data = new com.google.gson.JsonObject();
        data.addProperty("euTier", com.gregtechceu.gtceu.utils.GTUtil.getTierByVoltage(Math.abs(eut)));
        json.add("data", data);
        final com.google.gson.JsonObject euContent = new com.google.gson.JsonObject();
        euContent.addProperty("content", eut);
        euContent.addProperty("chance", 10000);
        euContent.addProperty("maxChance", 10000);
        euContent.addProperty("tierChanceBoost", 0);
        final com.google.gson.JsonArray euArr = new com.google.gson.JsonArray();
        euArr.add(euContent);
        final com.google.gson.JsonObject tickIn = new com.google.gson.JsonObject();
        tickIn.add("eu", euArr);
        json.add("tickInputs", tickIn);
        return json;
    }

    /**
     * 两个 Chip 列表是否逐字段相等（数量、<b>电路号、催化剂</b>也算）。
     *
     * <p>🆕 第 11 刀补上后两个字段：{@code Chip} 多了 {@code circuit}/{@code catalyst}，
     * 如果这里不跟着比，两侧同步那一拍就<b>看不见</b>这两个字段有没有过网络
     * （那正是"卡片上不显示几号电路"的病根）。
     */
    private static boolean sameChips(java.util.List<ShanhaiRecipeQuery.Chip> a,
                                     java.util.List<ShanhaiRecipeQuery.Chip> b) {
        if (a == null || b == null || a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            final ShanhaiRecipeQuery.Chip x = a.get(i);
            final ShanhaiRecipeQuery.Chip y = b.get(i);
            if (x.item() != y.item() || x.id() != y.id() || x.count() != y.count()
                    || x.circuit() != y.circuit() || x.catalyst() != y.catalyst()) {
                return false;
            }
        }
        return true;
    }

    /** 结果里的第一条活配方。 */
    private static GTRecipe firstRecipeOf(MinecraftServer server, ShanhaiRecipeQuery.Result r) {
        for (ShanhaiRecipeQuery.Group g : r.groups()) {
            for (ResourceLocation id : g.recipes()) {
                return ShanhaiRecipeReverseIndex.byId(server, id);
            }
        }
        return null;
    }

    /** 随便挑一个真正的 GT 机器方块（「作为机器的用处」要拿它当探针）。 */
    private static net.minecraft.world.item.Item pickMachineProbe() {
        try {
            for (com.gregtechceu.gtceu.api.machine.MachineDefinition def
                    : com.gregtechceu.gtceu.api.registry.GTRegistries.MACHINES.values()) {
                if (def == null) {
                    continue;
                }
                final ItemStack s = def.asStack();
                if (s != null && !s.isEmpty() && def.getRecipeTypes() != null
                        && def.getRecipeTypes().length > 0) {
                    return s.getItem();
                }
            }
        } catch (Throwable ignored) {
            return null;
        }
        return null;
    }

    /** 从全表里挑一个 id 片段当文本搜索的正对照（保证真的存在）。 */
    private static String pickIdFragment(MinecraftServer server) {
        for (int i = 0; i < ShanhaiRecipeReverseIndex.size(); i++) {
            final GTRecipe r = ShanhaiRecipeReverseIndex.recipeAt(i);
            if (r == null || r.id == null) {
                continue;
            }
            final String path = r.id.getPath();
            final int slash = path.lastIndexOf('/');
            final String frag = slash >= 0 && slash + 1 < path.length() ? path.substring(slash + 1) : path;
            if (frag.length() >= 6) {
                return frag;
            }
        }
        return "assembler";
    }

    /** 从全表里挑一个配方种类 id 的 path 片段当正对照。 */
    private static String pickTypeFragment(MinecraftServer server) {
        for (int i = 0; i < ShanhaiRecipeReverseIndex.size(); i++) {
            final ResourceLocation t = ShanhaiRecipeReverseIndex.typeIdAt(i);
            if (t != null && t.getPath().length() >= 6) {
                return t.getPath();
            }
        }
        return "assembler";
    }

    /** 挑一条"原始耗时 ≠ 实际耗时"的配方（没有就退化成第一条）。 */
    private static GTRecipe pickDurationProbe(MinecraftServer server) {
        GTRecipe first = null;
        for (int i = 0; i < ShanhaiRecipeReverseIndex.size(); i++) {
            final GTRecipe r = ShanhaiRecipeReverseIndex.recipeAt(i);
            if (r == null) {
                continue;
            }
            if (first == null) {
                first = r;
            }
            if (ShanhaiRecipeDuration.originalOf(r) != r.duration) {
                return r;
            }
        }
        return first;
    }
}
