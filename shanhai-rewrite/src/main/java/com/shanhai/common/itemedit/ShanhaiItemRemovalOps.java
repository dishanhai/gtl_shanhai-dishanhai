package com.shanhai.common.itemedit;

import com.google.gson.JsonObject;
import com.gregtechceu.gtceu.api.capability.recipe.ItemRecipeCapability;
import com.gregtechceu.gtceu.api.capability.recipe.RecipeCapability;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.gregtechceu.gtceu.api.recipe.GTRecipeType;
import com.gregtechceu.gtceu.api.recipe.content.Content;
import com.gregtechceu.gtceu.api.registry.GTRegistries;
import com.shanhai.ShanhaiMod;
import com.shanhai.common.recipe.editor.ShanhaiIoTable;
import com.shanhai.common.recipe.editor.ShanhaiJeiBridge;
import com.shanhai.common.recipe.editor.ShanhaiRecipeBase;
import com.shanhai.common.recipe.editor.ShanhaiRecipeEditorOps;
import com.shanhai.common.recipe.editor.ShanhaiRecipeIoApply;
import com.shanhai.common.recipe.editor.ShanhaiVanillaRecipeTable;
import com.shanhai.common.recipe.editor.ShanhaiVanillaRecipeView;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 山海重构 · <b>「删除/抹除物品」的执行面（后端，无 GUI）</b>。
 *
 * <h2>1. 四个执行面（用户规格，第一版只有这四个）</h2>
 * <pre>
 *   ① 藏        ：hidden ⇒ BuildCreativeModeTabContentsEvent 里摘掉 + 本项目创造栏 fill() 过滤
 *                  （JEI 的物品表是从创造栏 displayItems 建的 ⇒ 一起看不到，见视觉证据处注释）
 *   ② 抹实例    ：removed ⇒ 每 5 tick 扫玩家背包/副手/盔甲/已打开的容器/地面掉落物（AE 网络不扫）
 *   ③ 摘配方产出：removed ⇒ 该 id 从所有配方的 outputs 里摘掉；只产这一样的 ⇒ 整条停用并快照原 JSON
 *   ④ 拾取即销毁：removed ⇒ EntityItemPickupEvent 拦下并 discard
 * </pre>
 * 🔴 <b>方块连带删已整条移除</b>（用户 2026-10-17 拍板）：不扫 {@code LevelChunkSection}、
 * 不替换方块、不挂区块加载钩子、不碰 region 文件。世界里的已放置方块<b>一律不动</b>。
 *
 * <h2>2. 为什么"摘配方产出"必须走现成那套重建</h2>
 * GT 侧：{@link ShanhaiRecipeBase}（底本+台账）＋ {@link ShanhaiRecipeEditorOps#rebuildTypeFromBase}
 * ＋ {@link ShanhaiRecipeEditorOps#syncVanillaFromBase}；
 * 原版侧：{@link ShanhaiVanillaRecipeTable#markRemoved} ＋ syncVanillaFromBase（applyLedger 摘掉）。
 * <b>不发明第二套重建</b>。
 *
 * <h2>3. 🔴 GT 那一侧是"就地改"还是"重建"（这是个真取舍，写清楚）</h2>
 * 就地改 {@code GTRecipe.outputs}（public final Map，内容可换）：索引树里放的就是这些**同一个对象**，
 * 所以改完立刻生效、不用重建；而"从底本重建"会把我们刚改的那份丢掉（底本里没有这条改动）
 * ⇒ <b>v1 只就地改，不重建 GT 索引</b>。代价：{@code /reload} 或重启后 GT 侧从 JSON 重读
 * ⇒ 需要靠台账重放（v1 <b>没做</b>自动重放，如实记在报告"已知边界"）。
 */
public final class ShanhaiItemRemovalOps {

    public static final String PREFIX = ShanhaiItemOverrideTable.PREFIX;

    private ShanhaiItemRemovalOps() {}

    // ================================================================= 影响面（只读）

    /** 影响面报告（命令 {@code /shanhai item impact <id>} 的全部数字）。 */
    public record Impact(String id, boolean isBlock, boolean registered,
                         int outVanilla, int outGt, List<String> outSample,
                         int inVanilla, int inGt, List<String> inSample,
                         int soleOutput, List<String> soleSample) {

        public String oneLine() {
            return "id=" + id + " registered=" + registered + " isBlock=" + isBlock
                    + " out_vanilla=" + outVanilla + " out_gt=" + outGt
                    + " in_vanilla=" + inVanilla + " in_gt=" + inGt
                    + " sole_output=" + soleOutput;
        }
    }

    /**
     * 算影响面（<b>纯只读</b>：不改任何配方、不落盘）。
     *
     * <p>两张表分开数（用户点名）：{@code RecipeManager.getRecipes()} 里的非 GT 配方 vs GT（{@code GTRecipe}）。
     */
    public static Impact impact(MinecraftServer server, ResourceLocation id) {
        final List<String> outSample = new ArrayList<>();
        final List<String> inSample = new ArrayList<>();
        final List<String> soleSample = new ArrayList<>();
        int outVanilla = 0;
        int outGt = 0;
        int inVanilla = 0;
        int inGt = 0;
        int sole = 0;
        final Item item = BuiltInRegistries.ITEM.get(id);
        final boolean registered = BuiltInRegistries.ITEM.containsKey(id);
        try {
            if (server != null) {
                for (Recipe<?> r : server.getRecipeManager().getRecipes()) {
                    if (r instanceof GTRecipe gt) {
                        final int[] o = countItemContents(gt.outputs, item);
                        final int[] i = countItemContents(gt.inputs, item);
                        final int outCount = countAllItemContents(gt.outputs);
                        if (o[0] > 0) {
                            outGt++;
                            if (outSample.size() < 20) {
                                outSample.add(String.valueOf(gt.id));
                            }
                            if (outCount == o[0] && soleSample.size() < 20) {
                                sole++;
                                soleSample.add(String.valueOf(gt.id) + " (gt)");
                            }
                        }
                        if (i[0] > 0) {
                            inGt++;
                            if (inSample.size() < 20) {
                                inSample.add(String.valueOf(gt.id));
                            }
                        }
                    } else {
                        final ShanhaiVanillaRecipeView v = ShanhaiVanillaRecipeView.of(r);
                        if (v == null) {
                            continue;
                        }
                        if (!v.result.isEmpty() && v.result.getItem() == item) {
                            outVanilla++;
                            if (outSample.size() < 20) {
                                outSample.add(String.valueOf(v.id));
                            }
                            sole++;
                            if (soleSample.size() < 20) {
                                soleSample.add(String.valueOf(v.id) + " (原版)");
                            }
                        }
                        if (ingredientsUse(v.inputs, item)) {
                            inVanilla++;
                            if (inSample.size() < 20) {
                                inSample.add(String.valueOf(v.id));
                            }
                        }
                    }
                }
            }
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.error("{} impact_failed id={} err={}", PREFIX, id, t.toString());
        }
        final boolean isBlock = registered && item != null
                && item instanceof net.minecraft.world.item.BlockItem;
        return new Impact(id.toString(), isBlock, registered,
                outVanilla, outGt, outSample, inVanilla, inGt, inSample, sole, soleSample);
    }

    private static int[] countItemContents(Map<RecipeCapability<?>, List<Content>> table, Item item) {
        final int[] n = new int[1];
        if (table == null || item == null) {
            return n;
        }
        final List<Content> list = table.get(ItemRecipeCapability.CAP);
        if (list == null) {
            return n;
        }
        for (Content c : list) {
            final ItemStack st = ShanhaiIoTable.representativeItem(c);
            if (st != null && !st.isEmpty() && st.getItem() == item) {
                n[0]++;
            }
        }
        return n;
    }

    private static int countAllItemContents(Map<RecipeCapability<?>, List<Content>> table) {
        if (table == null) {
            return 0;
        }
        final List<Content> list = table.get(ItemRecipeCapability.CAP);
        return list == null ? 0 : list.size();
    }

    private static boolean ingredientsUse(List<Ingredient> ins, Item item) {
        if (ins == null || item == null) {
            return false;
        }
        for (Ingredient in : ins) {
            if (in == null) {
                continue;
            }
            try {
                for (ItemStack st : in.getItems()) {
                    if (!st.isEmpty() && st.getItem() == item) {
                        return true;
                    }
                }
            } catch (Throwable ignored) {
                // 某个模组的 Ingredient 实现抛异常 ⇒ 当作没用到（读侧绝不许炸）
            }
        }
        return false;
    }

    /** 影响面报告的多行文本（聊天栏 + 日志共用同一份文案）。 */
    public static List<String> impactLines(Impact im) {
        final List<String> out = new ArrayList<>();
        out.add("§e① 作为产出（原版配方表）：§f" + im.outVanilla() + " §7条");
        out.add("§e① 作为产出（GT 配方表）  ：§f" + im.outGt() + " §7条（两张表分开数）");
        if (!im.outSample().isEmpty()) {
            out.add("§8   前 20 条：" + String.join(", ", im.outSample()));
        }
        out.add("§e② 作为输入（原版配方表）：§f" + im.inVanilla() + " §7条");
        out.add("§e② 作为输入（GT 配方表）  ：§f" + im.inGt() + " §7条");
        if (!im.inSample().isEmpty()) {
            out.add("§8   前 20 条：" + String.join(", ", im.inSample()));
        }
        out.add("§e③ 只产这一样的配方：§f" + im.soleOutput() + " §7条（摘掉产出后会变成空产出 ⇒ 要整条停用）");
        if (!im.soleSample().isEmpty()) {
            out.add("§8   " + String.join(", ", im.soleSample()));
        }
        out.add("§e④ 世界里已放置的方块：§7" + (im.isBlock()
                ? "是方块；§f本版只统计不删 §7（第一版不含「方块连带删」）"
                : "不是方块（BlockItem = false）"));
        out.add("§e⑤ Java 代码里有没有直接引用：§7未查（本版不做静态分析）");
        return out;
    }

    // ================================================================= 🆕 ④ 方块数量（只统计，绝不删）

    /** 一次方块计数的读数。{@code chunks} = 真正读到的已加载区块数；{@code partial} = 撞到时间上限提前收工。 */
    public record BlockCount(int total, int chunks, int radius, boolean partial, long ms) {}

    /** 计数的方格预算（毫秒）。超了就停，并把 {@code partial=true} 如实报出去。 */
    public static final long BLOCK_COUNT_BUDGET_MS = 250L;

    /** 默认半径（区块）。用户规格里给的口径就是"玩家 8 个区块半径内"。 */
    public static final int BLOCK_COUNT_RADIUS = 8;

    /**
     * 数一数"已加载区块里有多少个这种方块"（<b>只读</b>：不加载区块、不改任何方块）。
     *
     * <h4>为什么这么写</h4>
     * <ul>
     *   <li>只用 {@code getChunkSource().getChunk(cx,cz,FULL,false)}（{@code false} = <b>绝不加载</b>）</li>
     *   <li>每段先用 {@code LevelChunkSection#maybeHas(predicate)} 做<b>调色板级</b>预判，
     *       只有命中的段才做 4096 次精确比较 ⇒ 便宜</li>
     *   <li>以每个玩家所在区块为中心的半径内去重计（同一区块只数一次）</li>
     *   <li>有毫秒预算，超时写 {@code partial}，绝不假装数完</li>
     * </ul>
     *
     * @return {@code null} = 这个物品不是方块（调用方写 {@code not_a_block}）
     */
    public static BlockCount countBlocks(MinecraftServer server, Item item) {
        if (server == null || !(item instanceof net.minecraft.world.item.BlockItem bi)) {
            return null;
        }
        final net.minecraft.world.level.block.Block block = bi.getBlock();
        final long t0 = System.nanoTime();
        int total = 0;
        int chunks = 0;
        boolean partial = false;
        final Set<Long> seen = new java.util.HashSet<>();
        outer:
        for (net.minecraft.server.level.ServerPlayer p : server.getPlayerList().getPlayers()) {
            final net.minecraft.server.level.ServerLevel level = p.serverLevel();
            final net.minecraft.world.level.ChunkPos center = p.chunkPosition();
            for (int dx = -BLOCK_COUNT_RADIUS; dx <= BLOCK_COUNT_RADIUS; dx++) {
                for (int dz = -BLOCK_COUNT_RADIUS; dz <= BLOCK_COUNT_RADIUS; dz++) {
                    final int cx = center.x + dx;
                    final int cz = center.z + dz;
                    final long key = (((long) cx) << 32) ^ (cz & 0xFFFFFFFFL);
                    if (!seen.add(key)) {
                        continue;
                    }
                    if ((System.nanoTime() - t0) / 1_000_000L > BLOCK_COUNT_BUDGET_MS) {
                        partial = true;
                        break outer;
                    }
                    final net.minecraft.world.level.chunk.LevelChunk chunk;
                    try {
                        final net.minecraft.world.level.chunk.ChunkAccess ca = level.getChunkSource()
                                .getChunk(cx, cz, net.minecraft.world.level.chunk.ChunkStatus.FULL, false);
                        chunk = ca instanceof net.minecraft.world.level.chunk.LevelChunk lc ? lc : null;
                    } catch (Throwable t) {
                        continue;
                    }
                    if (chunk == null) {
                        continue;         // 未加载 ⇒ 跳过（绝不为了计数去加载区块）
                    }
                    chunks++;
                    final net.minecraft.world.level.chunk.LevelChunkSection[] secs = chunk.getSections();
                    for (int i = 0; i < secs.length; i++) {
                        final net.minecraft.world.level.chunk.LevelChunkSection sec = secs[i];
                        if (sec == null || sec.hasOnlyAir()) {
                            continue;
                        }
                        if (!sec.maybeHas(st -> st.getBlock() == block)) {
                            continue;     // 调色板里根本没有这个方块 ⇒ 整段跳过
                        }
                        for (int y = 0; y < 16; y++) {
                            for (int z = 0; z < 16; z++) {
                                for (int x = 0; x < 16; x++) {
                                    if (sec.getBlockState(x, y, z).getBlock() == block) {
                                        total++;
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        final long ms = (System.nanoTime() - t0) / 1_000_000L;
        return new BlockCount(total, chunks, BLOCK_COUNT_RADIUS, partial, ms);
    }

    /** ④ 那一行的文案（{@code bc == null} ⇒ not_a_block）。 */
    public static String blockCountLine(MinecraftServer server, ResourceLocation id) {
        final Item item = BuiltInRegistries.ITEM.get(id);
        final BlockCount bc = countBlocks(server, item);
        final String logLine = bc == null
                ? "impact_block_count=not_a_block"
                : "impact_block_count=" + bc.total() + " chunks=" + bc.chunks() + " radius=" + bc.radius()
                        + " partial=" + bc.partial() + " ms=" + bc.ms();
        ShanhaiMod.LOGGER.info("{} {}", PREFIX, logLine);
        if (bc == null) {
            return "§e④+ 已加载区块里的数量：§7不是方块（not_a_block）";
        }
        return "§e④+ 已加载区块里的数量：§f" + bc.total() + " §7个"
                + " §8（仅统计：玩家半径 " + bc.radius() + " 区块内 / 读到 " + bc.chunks() + " 个已加载区块"
                + " / " + bc.ms() + "ms" + (bc.partial() ? " / §c撞到预算提前收工" : "") + "；只统计不删）";
    }

    // ================================================================= 🆕 开机重放（①）

    /** 重放读数：{@code stripped} = 这次重新摘掉的配方数；{@code stopped} = 这次重新整条停用的数。 */
    public record ReplayOutcome(int entries, int stripped, int stopped, boolean ok) {}

    /**
     * <b>开机/`/reload` 之后把 {@code removed} 重新落一遍</b>（用户规格：否则重启后功能等于失效）。
     *
     * <h4>为什么只要重放"配方"这两面</h4>
     * 隐藏（创造栏/JEI 过滤）与抹实例（5 tick 扫）本来就是<b>每次读表</b>的
     * ⇒ 重启后自动生效，不需要重放。只有"运行期改过配方表"的那部分会被 `/reload` / 重启洗掉。
     *
     * <h4>🔴 幂等性怎么保证（连跑两次结果一样）</h4>
     * <ul>
     *   <li>原版侧：第一次就把那几条 {@code markRemoved} 并从活表摘掉 ⇒ 第二次
     *       {@code getRecipes()} 里<strong>已经没有它们</strong> ⇒ 命中 0 条（no-op）；</li>
     *   <li>GT 多产物：第一次就把那一条 {@code Content} 从 {@code outputs} 摘了 ⇒ 第二次扫不到；</li>
     *   <li>GT 整条停用：{@code markRemoved} 是置标志（重复置同值无害），重建索引后它不再出现在索引里
     *       ⇒ 第二次扫不到；</li>
     *   <li>台账：只有真的命中（{@code stripped>0}）才 {@code upsert}，且按 id <b>整条替换</b>
     *       ⇒ 重复跑不会累积，也不会把上一局的快照弄丢。</li>
     * </ul>
     */
    public static ReplayOutcome replayAll(MinecraftServer server) {
        if (server == null) {
            ShanhaiMod.LOGGER.error("{} del_replay entries=0 stripped=0 stopped=0 ok=false reason=no_server",
                    PREFIX);
            return new ReplayOutcome(0, 0, 0, false);
        }
        int entries = 0;
        int strippedTotal = 0;
        int stopped = 0;
        boolean ok = true;
        try {
            ShanhaiItemOverrideTable.refreshNow();
            ShanhaiRemovedItemsLedger.reloadFromDisk();
            for (ShanhaiItemOverrideTable.Entry e : ShanhaiItemOverrideTable.entries()) {
                if (!e.removed()) {
                    continue;
                }
                entries++;
                final ResourceLocation id = ResourceLocation.tryParse(e.id());
                if (id == null) {
                    ok = false;
                    ShanhaiMod.LOGGER.error("{} del_replay_bad_id raw=\"{}\" -> 跳过（这条抹除不会生效）",
                            PREFIX, e.id());
                    continue;
                }
                final StripOutcome so = stripOutputs(server, id);
                final int n = so.stripped().size();
                if (n > 0) {
                    if (!ShanhaiRemovedItemsLedger.upsert(id, true, true, so.wantedBy(), so.stripped())) {
                        ok = false;
                        ShanhaiMod.LOGGER.error("{} del_replay_ledger_failed id={} stripped={} "
                                + "（配方已重新摘掉，但台账没写进去 ⇒ restore 会缺证据）", PREFIX, id, n);
                    }
                }
                strippedTotal += n;
                stopped += so.disabled();
            }
        } catch (Throwable t) {
            ok = false;
            ShanhaiMod.LOGGER.error("{} del_replay FAILED err={}", PREFIX, t.toString(), t);
        }
        if (ok) {
            ShanhaiMod.LOGGER.info("{} del_replay entries={} stripped={} stopped={} ok=true",
                    PREFIX, entries, strippedTotal, stopped);
        } else {
            ShanhaiMod.LOGGER.error("{} del_replay entries={} stripped={} stopped={} ok=false "
                            + "（上面每一处失败都有单独的 ERROR 行，逐条看）",
                    PREFIX, entries, strippedTotal, stopped);
        }
        return new ReplayOutcome(entries, strippedTotal, stopped, ok);
    }

    /**
     * 整条 GT 配方的完整 JSON（台账 {@code full} 字段的来源）。
     *
     * <p>⚠️ 拿不到就返回 {@code null} 并打 WARN —— <b>绝不因为快照失败而让 remove 失败</b>
     * （那会把"能不能删"绑在"能不能序列化"上）；代价是 restore 那条会走
     * {@code del_restore_gt_no_json} 的 ERROR 分支，如实暴露。
     */
    private static JsonObject fullJson(GTRecipe recipe) {
        try {
            // 🔴 GT 的序列化器没有 SERIALIZER.toJson(...) 这个方法（那是猜的，编译不过）。
            //    仓库里已验证的路子是它自己的 codec：见 ShanhaiRecipeFingerprint#diagnosticGtCodecRaw。
            final com.mojang.serialization.DataResult<com.google.gson.JsonElement> dr =
                    com.gregtechceu.gtceu.api.recipe.GTRecipeSerializer.CODEC.encodeStart(
                            com.mojang.serialization.JsonOps.INSTANCE, recipe);
            final com.google.gson.JsonElement el = dr.result().orElse(null);
            if (el == null || !el.isJsonObject()) {
                ShanhaiMod.LOGGER.warn("{} del_snapshot_full_failed id={} err=codec_encode_empty"
                                + " -> 台账里没有 full，restore 那条可能恢复不了（会打 ERROR，不静默）",
                        PREFIX, recipe == null ? "?" : recipe.id);
                return null;
            }
            return el.getAsJsonObject();
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.warn("{} del_snapshot_full_failed id={} err={} -> 台账里没有 full，"
                            + "restore 那条可能恢复不了（会打 ERROR，不静默）",
                    PREFIX, recipe == null ? "?" : recipe.id, t.toString());
            return null;
        }
    }

    // ================================================================= 藏 / 抹

    /** {@code hide <id> on|off}：只动 {@code hidden} 标志（removed 不受影响）。 */
    public static boolean hide(MinecraftServer server, ResourceLocation id, boolean on) {
        final boolean ok = ShanhaiItemOverrideTable.setFlags(id, on, null);
        log("del_hide", id, ok, on);
        return ok;
    }

    /**
     * {@code remove <id>}：先落表（hidden+removed）→ 再摘配方产出 → 台账。
     *
     * @param impactOk 调用方必须先跑过 {@code impact}（规格：没跑过就拒绝）。这里只做记录。
     */
    public static Result remove(MinecraftServer server, ResourceLocation id, boolean impactOk) {
        if (!impactOk) {
            return new Result(false, "必须先跑 /shanhai item impact " + id, 0, 0);
        }
        final boolean tableOk = ShanhaiItemOverrideTable.setFlags(id, true, true);
        if (!tableOk) {
            return new Result(false, "写 item_overrides.json 失败（看日志 del_remove_table_failed）", 0, 0);
        }
        final StripOutcome so = stripOutputs(server, id);
        final boolean ledgerOk = ShanhaiRemovedItemsLedger.upsert(id, true, true,
                so.wantedBy(), so.stripped());
        ShanhaiMod.LOGGER.info("{} del_remove id={} table_ok={} ledger_ok={} stripped={} disabled_recipes={} "
                        + "vanilla_changed={} gt_changed={}（❌ 方块部分：本版不做，世界里已放置的不动）",
                PREFIX, id, tableOk, ledgerOk, so.stripped().size(), so.disabled(), so.vanilla(), so.gt());
        return new Result(true, "已抹除 " + id,
                so.stripped().size(), so.disabled());
    }

    /** {@code restore <id>}：取消两个标志 → 按台账恢复配方产出 → 台账删条。 */
    public static Result restore(MinecraftServer server, ResourceLocation id) {
        final ShanhaiRemovedItemsLedger.RemovedEntry le = ShanhaiRemovedItemsLedger.get(id);
        final boolean tableOk = ShanhaiItemOverrideTable.setFlags(id, false, false);
        final int restored = restoreRecipes(server, le);
        final boolean ledgerOk = ShanhaiRemovedItemsLedger.remove(id);
        ShanhaiMod.LOGGER.info("{} del_restore id={} table_ok={} ledger_entry={} restored_recipes={} ledger_ok={}"
                        + "（方块：本版不删 ⇒ 不需要恢复；世界里已放置的东西一直在原处）",
                PREFIX, id, tableOk, le != null, restored, ledgerOk);
        return new Result(tableOk, "已撤回 " + id + "（配方恢复 " + restored + " 条）", restored, 0);
    }

    /** 命令回执用的小结果。 */
    public record Result(boolean ok, String message, int stripped, int disabled) {}

    /**
     * 🆕 补丁 3 ③：<b>一键恢复</b>（{@code /shanhai item restoreall}）。
     *
     * <p>逐个走<b>现有的</b> {@link #restore(MinecraftServer, ResourceLocation)} —— 这里不写第二套恢复逻辑，
     * 只做"遍历 + 计数 + 汇总一行"。用户口径：他要"一键"，**不加二次确认**。
     *
     * <p>返回值沿用 {@link Result}：{@code stripped} = 成功条数，{@code disabled} = 失败条数。
     */
    public static Result restoreAll(MinecraftServer server) {
        if (server == null) {
            ShanhaiMod.LOGGER.error("{} del_restore_all entries=0 ok=0 failed=0 reason=no_server", PREFIX);
            return new Result(false, "没有服务端实例", 0, 0);
        }
        ShanhaiItemOverrideTable.refreshNow();
        ShanhaiRemovedItemsLedger.reloadFromDisk();
        final List<ShanhaiItemOverrideTable.Entry> all = ShanhaiItemOverrideTable.suppressed();
        int ok = 0;
        int failed = 0;
        for (ShanhaiItemOverrideTable.Entry e : all) {
            final ResourceLocation id = ResourceLocation.tryParse(e.id());
            if (id == null) {
                failed++;
                ShanhaiMod.LOGGER.error("{} del_restore_all_bad_id raw=\"{}\"", PREFIX, e.id());
                continue;
            }
            try {
                final Result r = restore(server, id);
                if (r.ok()) {
                    ok++;
                } else {
                    failed++;
                    ShanhaiMod.LOGGER.error("{} del_restore_all_item_failed id={} msg={}", PREFIX, id, r.message());
                }
            } catch (Throwable t) {
                failed++;
                ShanhaiMod.LOGGER.error("{} del_restore_all_threw id={} err={}", PREFIX, id, t.toString(), t);
            }
        }
        // 判据行：entries 必须等于 ok+failed（不许有"既没成功也没失败"的静默漏项）
        ShanhaiMod.LOGGER.info("{} del_restore_all entries={} ok={} failed={}",
                PREFIX, all.size(), ok, failed);
        return new Result(failed == 0,
                "一键恢复完成：成功 " + ok + " 条" + (failed > 0 ? "，失败 " + failed + " 条（见日志）" : ""),
                ok, failed);
    }

    // ================================================================= 摘配方产出

    private record StripOutcome(int vanilla, int gt, int disabled,
                                List<String> wantedBy,
                                List<ShanhaiRemovedItemsLedger.Stripped> stripped) {}

    /**
     * 把 {@code id} 从所有配方的输出里摘掉。
     *
     * <ul>
     *   <li><b>原版</b>：产物就是这一个 ⇒ {@link ShanhaiVanillaRecipeTable#markRemoved}（整条停用）
     *       ＋ {@link ShanhaiRecipeEditorOps#syncVanillaFromBase}（现成重建通路，applyLedger 负责摘掉）；</li>
     *   <li><b>GT</b>：就地改 {@code outputs} 里的那一条 {@code Content}；若它本来是多产物之一 ⇒ 只摘这一条，
     *       若只剩它一条 ⇒ {@link ShanhaiRecipeBase#markRemoved} 整条停用 ＋ 重建该类型的索引。</li>
     * </ul>
     */
    private static StripOutcome stripOutputs(MinecraftServer server, ResourceLocation id) {
        final List<String> wantedBy = new ArrayList<>();
        final List<ShanhaiRemovedItemsLedger.Stripped> stripped = new ArrayList<>();
        int vanilla = 0;
        int gt = 0;
        int disabled = 0;
        // 🆕 补丁 3 ②：JEI 侧必须逐条通知（它的配方表是自己建的，不问它就不会变）
        int jeiAdded = 0;
        int jeiRemoved = 0;
        final List<ResourceLocation> gtDisabled = new ArrayList<>();
        final List<ResourceLocation> gtDisabledTypes = new ArrayList<>();
        final List<ResourceLocation> vanillaDisabled = new ArrayList<>();
        final List<ResourceLocation> vanillaDisabledTypes = new ArrayList<>();
        final Item item = BuiltInRegistries.ITEM.get(id);
        if (server == null) {
            return new StripOutcome(0, 0, 0, wantedBy, stripped);
        }
        try {
            ShanhaiRecipeBase.captureIfAbsent(server);
            ShanhaiVanillaRecipeTable.captureIfAbsent(server);
            final Set<GTRecipeType> touchedTypes = new LinkedHashSet<>();
            final List<String> vanillaToDisable = new ArrayList<>();

            for (Recipe<?> r : new ArrayList<>(server.getRecipeManager().getRecipes())) {
                if (r instanceof GTRecipe recipe) {
                    final List<Content> list = recipe.outputs == null ? null
                            : recipe.outputs.get(ItemRecipeCapability.CAP);
                    if (list == null || list.isEmpty()) {
                        continue;
                    }
                    final List<Content> keep = new ArrayList<>(list.size());
                    boolean hit = false;
                    for (Content c : list) {
                        final ItemStack st = ShanhaiIoTable.representativeItem(c);
                        if (st != null && !st.isEmpty() && st.getItem() == item) {
                            hit = true;
                        } else {
                            keep.add(c);
                        }
                    }
                    if (!hit) {
                        continue;
                    }
                    gt++;
                    wantedBy.add(String.valueOf(recipe.id));
                    stripped.add(new ShanhaiRemovedItemsLedger.Stripped("gt",
                            String.valueOf(recipe.id),
                            recipe.getType() == null || recipe.getType().registryName == null
                                    ? "" : recipe.getType().registryName.toString(),
                            ShanhaiRecipeIoApply.tableJson(recipe, "outputs"),
                            // 🆕 整条配方的完整 JSON：整条停用后索引里就没有它了，restore 要靠这份重造
                            fullJson(recipe)));
                    if (keep.isEmpty()) {
                        // 只产这一样 ⇒ 整条停用（走现成台账 + 重建）
                        ShanhaiRecipeBase.markRemoved(recipe.id);
                        ShanhaiRecipeBase.markTypeTouched(recipe.getType());
                        touchedTypes.add(recipe.getType());
                        disabled++;
                        gtDisabled.add(recipe.id);
                        gtDisabledTypes.add(recipe.getType() == null ? null : recipe.getType().registryName);
                    } else {
                        // 多产物 ⇒ 就地摘掉这一条（索引树里是同一个对象 ⇒ 立刻生效）
                        list.clear();
                        list.addAll(keep);
                        // 🆕 补丁 3 ②：这条配方还在、只是产物少了一样 ⇒ 让 JEI 换掉它自己那份
                        //    走现成通路：changed 包 → 客户端 hide 旧那份 + 从字节重建新的（绝不 add 第二份）
                        ShanhaiJeiBridge.broadcastRecipeChanged(server, recipe);
                        jeiAdded++;
                    }
                } else {
                    final ShanhaiVanillaRecipeView v = ShanhaiVanillaRecipeView.of(r);
                    if (v == null || v.result.isEmpty() || v.result.getItem() != item) {
                        continue;
                    }
                    vanilla++;
                    wantedBy.add(String.valueOf(v.id));
                    final JsonObject snap = new JsonObject();
                    snap.addProperty("id", String.valueOf(v.id));
                    snap.addProperty("type", String.valueOf(v.typeId));
                    snap.add("result", ShanhaiVanillaRecipeView.resultJson(v.result));
                    stripped.add(new ShanhaiRemovedItemsLedger.Stripped("vanilla",
                            String.valueOf(v.id), String.valueOf(v.typeId), snap));
                    vanillaToDisable.add(String.valueOf(v.id));
                    vanillaDisabled.add(v.id);
                    vanillaDisabledTypes.add(v.typeId);
                    disabled++;
                }
            }

            for (String s : vanillaToDisable) {
                final ResourceLocation rid = ResourceLocation.tryParse(s);
                if (rid != null) {
                    ShanhaiVanillaRecipeTable.markRemoved(rid);
                }
            }
            if (!vanillaToDisable.isEmpty()) {
                // 现成通路：syncVanillaFromBase → ShanhaiVanillaRecipeOps.applyLedger 摘掉 removed 的
                ShanhaiRecipeEditorOps.syncVanillaFromBase(server);
            }
            for (GTRecipeType t : touchedTypes) {
                ShanhaiRecipeEditorOps.rebuildTypeFromBase(server, t);
            }
            // 🆕 补丁 3 ②：整条停用的那几条 ⇒ 逐条通知 JEI「这条没了」
            //    ⚠️ 必须在 rebuild/sync **之后**发：客户端会按 id 去 JEI 里 hide 它那份旧条目
            for (int i = 0; i < gtDisabled.size(); i++) {
                // typeId 为 null 时 bridge 会走到"非 GT"那条分流里 ⇒ 宁可不发也不能传 null
                if (gtDisabledTypes.get(i) == null) {
                    ShanhaiMod.LOGGER.warn("{} jei_skip_removed_no_type id={}（拿不到类型 id ⇒ 这条不通知 JEI）",
                            PREFIX, gtDisabled.get(i));
                    continue;
                }
                ShanhaiJeiBridge.broadcastRecipeRemoved(server, gtDisabled.get(i), gtDisabledTypes.get(i));
                jeiRemoved++;
            }
            for (int i = 0; i < vanillaDisabled.size(); i++) {
                // 非 GT：bridge 内部按 ns 分流（minecraft ⇒ broadcastVanillaRemoved 那条）
                ShanhaiJeiBridge.broadcastRecipeRemoved(server, vanillaDisabled.get(i),
                        vanillaDisabledTypes.get(i));
                jeiRemoved++;
            }
            ShanhaiMod.LOGGER.info("{} jei_recipe_refresh id={} type=mixed added={} hidden={} "
                            + "（added=产物被摘但配方还在；hidden=整条停用）",
                    PREFIX, id, jeiAdded, jeiRemoved);
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.error("{} del_strip_failed id={} err={}", PREFIX, id, t.toString(), t);
        }
        ShanhaiMod.LOGGER.info("{} impact_strip id={} vanilla={} gt={} disabled={} wanted_by={}",
                PREFIX, id, vanilla, gt, disabled, wantedBy.size());
        return new StripOutcome(vanilla, gt, disabled, wantedBy, stripped);
    }

    /** 按台账把被摘的配方产出放回去（原样恢复 JSON 里那一段）。 */
    private static int restoreRecipes(MinecraftServer server, ShanhaiRemovedItemsLedger.RemovedEntry le) {
        if (le == null || le.stripped().isEmpty() || server == null) {
            return 0;
        }
        int restored = 0;
        int gtRebuilt = 0;
        int gtFailed = 0;
        // 底本必须在动台账之前抓到（pristine(id) 是"整条停用"那一类的恢复入口）
        ShanhaiRecipeBase.captureIfAbsent(server);
        boolean vanillaTouched = false;
        final Set<GTRecipeType> gtTypes = new LinkedHashSet<>();
        for (ShanhaiRemovedItemsLedger.Stripped s : le.stripped()) {
            try {
                final ResourceLocation rid = ResourceLocation.tryParse(s.recipeId());
                if (rid == null) {
                    continue;
                }
                if ("vanilla".equals(s.kind())) {
                    ShanhaiVanillaRecipeTable.clearEdit(rid);
                    vanillaTouched = true;
                    restored++;
                } else {
                    // 🆕 整条停用的 GT 配方：三条恢复路，全部落在既有重建通路上
                    //   ⓪ 配方还在索引里（= 当初只是"多产物里摘掉这一条"，就地改的那份）
                    //      ⇒ 把台账里那份 outputs 表整体写回去（applyTable 是编辑器在用的同一套）
                    final GTRecipe live = findGt(server, s.typeId(), rid);
                    if (live != null && s.json() != null) {
                        ShanhaiRecipeIoApply.applyTable(live, "outputs", s.json());
                        gtTypes.add(live.getType());
                        gtRebuilt++;
                        restored++;
                        continue;
                    }
                    //   ① 底本里还有那份原对象（= 整条停用）⇒ 清台账（clearEdit 会清掉 removed）＋ 重建该类型
                    final GTRecipe base = ShanhaiRecipeBase.pristine(rid);
                    if (base != null) {
                        gtTypes.add(base.getType());
                        ShanhaiRecipeBase.clearEdit(rid);
                        gtRebuilt++;
                        restored++;
                        continue;
                    }
                    //   ② 底本里也没有（跨重启 / 底本没抓到）⇒ 用台账里那份【整条 JSON】重造
                    if (s.full() != null) {
                        final GTRecipe made = ShanhaiRecipeEditorOps.addRecipeFromJson(server, rid, s.full());
                        if (made != null) {
                            gtRebuilt++;
                            restored++;
                        } else {
                            gtFailed++;
                            ShanhaiMod.LOGGER.error("{} del_restore_gt_rebuild_failed id={} recipe={} type={} "
                                            + "（台账里有整条 JSON，但 addRecipeFromJson 返回 null ⇒ 看它上一行"
                                            + "的 add_recipe_parse_failed / add_recipe_parse_null）",
                                    PREFIX, le.id(), rid, s.typeId());
                        }
                    } else {
                        gtFailed++;
                        ShanhaiMod.LOGGER.error("{} del_restore_gt_no_json id={} recipe={} type={} "
                                        + "（台账里没有 full 快照：这条是旧版台账写的，恢复不了 ⇒ "
                                        + "删掉 removed_items.json 里这条或重启让 JSON 重新加载）",
                                PREFIX, le.id(), rid, s.typeId());
                    }
                }
            } catch (Throwable t) {
                ShanhaiMod.LOGGER.error("{} del_restore_one_failed id={} recipe={} err={}",
                        PREFIX, le.id(), s.recipeId(), t.toString());
            }
        }
        try {
            if (vanillaTouched) {
                ShanhaiRecipeEditorOps.syncVanillaFromBase(server);
            }
            for (GTRecipeType t : gtTypes) {
                ShanhaiRecipeEditorOps.rebuildTypeFromBase(server, t);
            }
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.error("{} del_restore_sync_failed id={} err={}", PREFIX, le.id(), t.toString());
        }
        // 判据行：rebuilt 必须等于台账里 kind=gt 的条数（不等就是有没恢复的，逐条已在上面打过 ERROR）
        ShanhaiMod.LOGGER.info("{} del_restore_gt rebuilt={} failed={} wanted={}",
                PREFIX, gtRebuilt, gtFailed, le.stripped().size());
        // 🆕 补丁 3 ②：恢复之后同样要通知 JEI（否则它手里还是"被摘掉"的那一份）
        notifyJeiAfterRestore(server, le);
        return restored;
    }

    /**
     * 🆕 补丁 3 ②：<b>恢复之后把"现在长什么样"逐条推给 JEI</b>。
     *
     * <p>判据与既有 restore 通知同一条：活表里读得到 ⇒ {@code changed}（带整条字节）；
     * 读不到 ⇒ {@code removed}。**只走 {@link ShanhaiJeiBridge} 既有那四句，不注入 JEI 内部类。**
     */
    private static void notifyJeiAfterRestore(MinecraftServer server,
                                              ShanhaiRemovedItemsLedger.RemovedEntry le) {
        if (server == null || le == null || le.stripped().isEmpty()) {
            return;
        }
        int added = 0;
        int hidden = 0;
        for (ShanhaiRemovedItemsLedger.Stripped s : le.stripped()) {
            try {
                final ResourceLocation rid = ResourceLocation.tryParse(s.recipeId());
                if (rid == null) {
                    continue;
                }
                final ResourceLocation tid = s.typeId() == null || s.typeId().isEmpty()
                        ? null : ResourceLocation.tryParse(s.typeId());
                if ("vanilla".equals(s.kind())) {
                    // 非 GT：现读活表 ⇒ 读得到发 changed（整条字节），读不到发 removed
                    ShanhaiJeiBridge.broadcastVanillaNow(server, rid, tid);
                    added++;
                } else {
                    final GTRecipe live = findGt(server, s.typeId(), rid);
                    if (live != null) {
                        ShanhaiJeiBridge.broadcastRecipeChanged(server, live);
                        added++;
                    } else {
                        if (tid != null) {
                            ShanhaiJeiBridge.broadcastRecipeRemoved(server, rid, tid);
                        }
                        hidden++;
                        ShanhaiMod.LOGGER.warn("{} jei_restore_recipe_still_missing id={} recipe={} type={} "
                                        + "（恢复后索引里仍然没有它 ⇒ 让 JEI 把这条拿掉，别显示错的）",
                                PREFIX, le.id(), rid, s.typeId());
                    }
                }
            } catch (Throwable t) {
                ShanhaiMod.LOGGER.error("{} jei_restore_notify_failed id={} recipe={} err={}",
                        PREFIX, le.id(), s.recipeId(), t.toString());
            }
        }
        ShanhaiMod.LOGGER.info("{} jei_recipe_refresh id={} type=mixed added={} hidden={} phase=restore",
                PREFIX, le.id(), added, hidden);
    }

    private static GTRecipe findGt(MinecraftServer server, String typeId, ResourceLocation recipeId) {
        try {
            final ResourceLocation tid = typeId == null || typeId.isEmpty() ? null
                    : ResourceLocation.tryParse(typeId);
            final GTRecipeType type = tid == null ? null : GTRegistries.RECIPE_TYPES.get(tid);
            if (type != null) {
                final GTRecipe r = ShanhaiRecipeEditorOps.readFromIndex(type, recipeId);
                if (r != null) {
                    return r;
                }
            }
            for (GTRecipeType t : GTRegistries.RECIPE_TYPES) {
                final GTRecipe r = ShanhaiRecipeEditorOps.readFromIndex(t, recipeId);
                if (r != null) {
                    return r;
                }
            }
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.warn("{} del_find_gt_failed recipe={} err={}", PREFIX, recipeId, t.toString());
        }
        return null;
    }

    // ================================================================= 抹实例（执行面 ②）

    /**
     * 把 {@code removed} 物品的实例从世界里抹掉：玩家背包/副手/盔甲、已打开的容器、地面掉落物。
     *
     * <p>⚠️ 已知边界：<b>AE 网络不扫</b>（用户拍板）。
     *
     * @return 抹掉的格数（日志与命令回执用）
     */
    public static int wipeInstances(MinecraftServer server) {
        if (server == null || !ShanhaiItemOverrideTable.active()) {
            return 0;
        }
        int wiped = 0;
        try {
            for (net.minecraft.server.level.ServerPlayer p : server.getPlayerList().getPlayers()) {
                wiped += wipeInventory(p);
                wiped += wipeOpenContainer(p);
                wiped += wipeDrops(p);
            }
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.warn("{} del_wipe_failed err={}（下一次 tick 再试）", PREFIX, t.toString());
        }
        return wiped;
    }

    private static int wipeInventory(net.minecraft.server.level.ServerPlayer p) {
        int n = 0;
        final net.minecraft.world.entity.player.Inventory inv = p.getInventory();
        n += wipeList(inv.items);
        n += wipeList(inv.armor);
        n += wipeList(inv.offhand);
        return n;
    }

    private static int wipeList(List<ItemStack> list) {
        int n = 0;
        for (int i = 0; i < list.size(); i++) {
            final ItemStack st = list.get(i);
            if (!st.isEmpty() && ShanhaiItemOverrideTable.isRemoved(st.getItem())) {
                list.set(i, ItemStack.EMPTY);
                n++;
            }
        }
        return n;
    }

    private static int wipeOpenContainer(net.minecraft.server.level.ServerPlayer p) {
        int n = 0;
        try {
            final net.minecraft.world.inventory.AbstractContainerMenu menu = p.containerMenu;
            if (menu == null) {
                return 0;
            }
            if (!menu.getCarried().isEmpty() && ShanhaiItemOverrideTable.isRemoved(menu.getCarried().getItem())) {
                menu.setCarried(ItemStack.EMPTY);
                n++;
            }
            for (net.minecraft.world.inventory.Slot s : menu.slots) {
                final ItemStack st = s.getItem();
                if (!st.isEmpty() && ShanhaiItemOverrideTable.isRemoved(st.getItem())) {
                    s.set(ItemStack.EMPTY);
                    n++;
                }
            }
            if (n > 0) {
                menu.broadcastChanges();
            }
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.warn("{} del_wipe_container_failed player={} err={}", PREFIX, p.getName(), t.toString());
        }
        return n;
    }

    /** 地面掉落物：用一个覆盖全世界的 AABB 取（不枚举区块，零存档风险）。 */
    private static int wipeDrops(net.minecraft.server.level.ServerPlayer p) {
        final net.minecraft.server.level.ServerLevel level = p.serverLevel();
        return wipeDropsInLevel(level);
    }

    private static final net.minecraft.world.phys.AABB ALL = new net.minecraft.world.phys.AABB(
            -3.0E7, -4096.0, -3.0E7, 3.0E7, 4096.0, 3.0E7);

    public static int wipeDropsInLevel(net.minecraft.server.level.ServerLevel level) {
        int n = 0;
        try {
            for (net.minecraft.world.entity.item.ItemEntity e
                    : level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, ALL)) {
                final ItemStack st = e.getItem();
                if (!st.isEmpty() && ShanhaiItemOverrideTable.isRemoved(st.getItem())) {
                    e.discard();
                    n++;
                }
            }
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.warn("{} del_wipe_drops_failed level={} err={}",
                    PREFIX, level.dimension().location(), t.toString());
        }
        return n;
    }

    // ================================================================= 小工具

    /** 在创造栏里该不该藏（客户端/服务端都能调；表是 common 包）。 */
    public static boolean shouldHide(ItemStack st) {
        if (st == null || st.isEmpty()) {
            return false;
        }
        return ShanhaiItemOverrideTable.isHidden(BuiltInRegistries.ITEM.getKey(st.getItem()));
    }

    private static void log(String tag, ResourceLocation id, boolean ok, boolean on) {
        final ShanhaiItemOverrideTable.Entry now = ShanhaiItemOverrideTable.get(id);
        ShanhaiMod.LOGGER.info("{} {} id={} ok={} want={} before_after={}", PREFIX, tag, id, ok, on,
                now == null ? "<no-entry>" : now.signature());
    }
}
