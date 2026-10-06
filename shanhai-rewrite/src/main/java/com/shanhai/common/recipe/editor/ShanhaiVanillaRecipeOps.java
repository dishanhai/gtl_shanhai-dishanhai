package com.shanhai.common.recipe.editor;

import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import com.shanhai.ShanhaiMod;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeManager;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * <b>「改完立刻生效」在非 GT 配方上的落地</b>。
 *
 * <h2>1. 生效只需要动<b>一层</b>（和 GT 那三层完全不同）</h2>
 * <pre>
 *   GT   : ① GTRecipeType.getLookup() 那棵树（重建）② RecipeManager 两张表 ③ 覆盖文件
 *   原版 : ---------------------------------------- ② RecipeManager 两张表 ③ 覆盖文件
 * </pre>
 * 🔴 <b>原版配方没有索引要重建</b>（javap 实证，前置调查 §4.1）：合成台 {@code CraftingMenu}、
 * 熔炉 {@code AbstractFurnaceBlockEntity}、切石机 {@code StonecutterMenu}、锻造台 {@code SmithingMenu}
 * <b>每一次查询都现读 {@code RecipeManager.byType}</b>，没有任何缓存层。
 * ⇒ 把新对象写回那两张表，就是"立刻生效"。
 *
 * <h2>2. 写回走的是<b>公有 API 的整表重写</b>，不是反射</h2>
 * 公有 API 只有 {@code RecipeManager.replaceRecipes(Iterable)}，它<b>整份替换两张表</b>
 * （javap 原文见前置调查 §3.2）。本类<b>不</b>走"反射换字段 / 换可变 HashMap"那条快路：
 * 那条路只有离线微基准支撑、游戏内从未实测，而且一旦启用会让 SCE 那种"反射清缓存"的写法
 * 把整张配方表清空（风险见 {@code handoff/.../参考-四个配方编辑mod.md} §6 ❌ 第 1 条）。
 * 代价是每次保存约 64~150 ms 的服务端阻塞（69,481 条实测），本阶段接受。
 *
 * <p>🔴 为了<b>只重写一次</b>，本类不自己调 {@code replaceRecipes}，而是把"非 GT 那一段"
 * 挂进 {@link ShanhaiRecipeEditorOps#syncVanillaFromBase} 的同一个循环里
 * （见 {@link #applyLedger(List)}）—— 一次保存 = 一次整表重写，而不是两次。
 *
 * <h2>3. 落盘口径</h2>
 * 复用的就是 GT 那条通道：{@code config/shanhai/recipe_overrides.json} 里一条
 * {@code op=set} 的 entry，{@code fields} 里放<b>原版配方自己的键名</b>
 * （{@code result} / {@code cookingtime} / {@code experience}）。
 * 覆盖层脚本 {@code shanhai_recipe_overrides.js} 对 {@code op=set} 的处理是
 * "把 fields 里每个键交给 {@code recipe.set(name, value)}" ——
 * <b>原版配方走的也是同一个 {@code RecipeJS}</b>（它在 KubeJS 的 {@code originalRecipes} 里，
 * 和 GT 那些数据包配方同一条通道）⇒ <b>覆盖层脚本一个字节都不用改</b>。
 */
public final class ShanhaiVanillaRecipeOps {

    public static final String PREFIX = "[SHANHAI-EDIT] editor";

    private ShanhaiVanillaRecipeOps() {}

    // ---------------------------------------------------------------- 对外动作

    /**
     * 改一条非 GT 配方：<b>产物 / 烧炼时间 / 经验</b>（传 {@code null} 的那一项不动）。
     *
     * @param rebuildIndex 负对照用：{@code false} = 只写台账、<b>不写回 RecipeManager</b>
     *                     （应当看不到新值 ⇒ 证明"写回两张表"这一步是必须的）
     */
    public static ShanhaiRecipeEditorOps.Result applyEdits(MinecraftServer server, ResourceLocation id,
                                                           ItemStack result, Integer cookingTime,
                                                           Double experience,
                                                           boolean rebuildIndex, boolean persist) {
        return applyEdits(server, id, result, cookingTime, experience, null, rebuildIndex, persist);
    }

    /**
     * 🆕 阶段 2：<b>产物 / 烧炼时间 / 经验 ＋ 输入侧</b>一次写完。
     *
     * @param shape 输入侧的统一中间表示；{@code null} = 这次不动输入
     * @param rebuildIndex 负对照用：{@code false} = 只写台账、<b>不写回 RecipeManager</b>
     *                     （应当看不到新值 ⇒ 证明"写回两张表"这一步是必须的）
     */
    public static ShanhaiRecipeEditorOps.Result applyEdits(MinecraftServer server, ResourceLocation id,
                                                           ItemStack result, Integer cookingTime,
                                                           Double experience,
                                                           ShanhaiVanillaRecipeShape shape,
                                                           boolean rebuildIndex, boolean persist) {
        if (server == null || id == null) {
            return fail("目标配方不存在", "server/id == null");
        }
        ShanhaiVanillaRecipeTable.captureIfAbsent(server);
        if (ShanhaiVanillaRecipeTable.pristine(id) == null) {
            ShanhaiMod.LOGGER.error("{} vanilla_edit_no_base id={} -> 底本里没有这条（非 GT 底本建得太晚？）",
                    PREFIX, id);
            return fail("这条配方不在非 GT 底本里", "id=" + id);
        }
        // 🔴 输入那一份的闸门放在【写台账之前】：形状不合法（空列表槽 / 整张网格全空 / 单格空）
        //    就整条拒收 —— 绝不落一条"输入被清空"的编辑（那会让下一局的覆盖层重放写进一条
        //    游戏读不懂、或者干脆产不出东西的配方）。
        if (shape != null && shape.isDirty() && !shape.valid()) {
            ShanhaiMod.LOGGER.error("{} vanilla_edit_inputs_rejected id={} mode={} reason={}",
                    PREFIX, id, shape.mode(), shape.invalidReason());
            return fail(shape.invalidReason(), "inputs_invalid mode=" + shape.mode());
        }
        final ShanhaiVanillaRecipeTable.Edit before = ShanhaiVanillaRecipeTable.editOf(id);
        // 🔴 指纹必须在【改动之前】取（与 GT 那条线同一口径：它描述的是"源声明长什么样"）。
        //    🆕 第 12 刀：本条如果是本编辑器【新建】出来的（文件里那条是 op=add），
        //    这次保存会继续走 op=add —— 那条通道【不用 base_fp】。此时【不许】去算指纹：
        //    新建配方的指纹算不出来（它不在 KubeJS 的账上）⇒ 会白打一条
        //    `fp_unavailable` 的 ERROR，把"干净开机 [ERROR]=0"这条硬判据污染掉。
        final boolean keepAdd = existingAddEntry(id) != null;
        final String fp = keepAdd ? null : resolveBaseFp(server, id);

        if (result != null) {
            ShanhaiVanillaRecipeTable.setResult(id, result);
        }
        if (cookingTime != null) {
            ShanhaiVanillaRecipeTable.setCookingTime(id, cookingTime);
        }
        if (experience != null) {
            ShanhaiVanillaRecipeTable.setExperience(id, experience);
        }
        if (shape != null) {
            ShanhaiVanillaRecipeTable.setShape(id, shape);
        }

        // 🔴 先按底本 ＋ 台账造一次：造不出来就【整条拒收】，绝不落一条重建失败的编辑
        //    （那会让下一局的覆盖层重放写进一条游戏读不懂的配方）
        final Recipe<?> built = ShanhaiVanillaRecipeTable.finalRecipeOf(id);
        if (built == null) {
            // 逐字段回滚成"改之前那一份"（只还原 result 会把这次没改的时间/经验也丢掉）
            ShanhaiVanillaRecipeTable.restoreEdit(id, before);
            ShanhaiMod.LOGGER.error("{} vanilla_edit_rebuild_failed id={} -> 台账已回滚，本次没有改任何东西",
                    PREFIX, id);
            return fail("这条配方重建失败（本版不支持这个类型）", "rebuild -> null");
        }

        final int vanillaMs;
        final boolean written;
        if (rebuildIndex) {
            final long t0 = System.nanoTime();
            ShanhaiRecipeEditorOps.syncVanillaFromBase(server);
            vanillaMs = (int) ((System.nanoTime() - t0) / 1_000_000L);
            written = true;
        } else {
            vanillaMs = 0;
            written = false;
        }
        ShanhaiVanillaRecipeTable.invalidate();

        final PersistOutcome po = persist ? persistSet(server, id, result, cookingTime, experience,
                shape, fp) : PersistOutcome.OFF;

        final ShanhaiVanillaRecipeTable.Edit after = ShanhaiVanillaRecipeTable.editOf(id);
        ShanhaiMod.LOGGER.info("{} vanilla_edit_apply id={} type={} before=[{}] after=[{}] "
                        + "wrote_tables={} vanilla_ms={} persist={} base_fp_len={} inputs={}",
                PREFIX, id, ShanhaiVanillaRecipeTable.typeIdOf(
                        ShanhaiVanillaRecipeTable.pristine(id)),
                before == null ? "(none)" : before.describe(),
                after == null ? "(none)" : after.describe(),
                written, vanillaMs, po.note(), fp == null ? -1 : fp.length(),
                shape == null ? "(untouched)" : shape.statsLine());

        return new ShanhaiRecipeEditorOps.Result(true,
                "已保存（产物 " + describeShort(result) + " · 时间 "
                        + (cookingTime == null ? "未动" : cookingTime.toString()) + " · 经验 "
                        + (experience == null ? "未动" : experience.toString()) + " · 输入 "
                        + (shape == null || !shape.isDirty() ? "未动" : shape.describe()) + "）",
                "vanilla_ms=" + vanillaMs + " " + po.note(),
                0, vanillaMs, written, po.written(), fp);
    }

    /** 删掉一条非 GT 配方（台账 ＋ 原版两张表 ＋ 落盘）。 */
    public static ShanhaiRecipeEditorOps.Result removeRecipe(MinecraftServer server, ResourceLocation id,
                                                             boolean persist) {
        if (server == null || id == null) {
            return fail("目标配方不存在", "server/id == null");
        }
        ShanhaiVanillaRecipeTable.captureIfAbsent(server);
        // 🔴 第 12 刀（负对照）：删一条【不存在】的 id 必须报 MISSING，绝不许静默成功。
        //    改之前这条没有闸门：台账照标、Result 照回 ok=true，而文件里那条 op=remove 的
        //    指纹算不出来 ⇒ persist 被跳过 ⇒ 用户看到"已删除"，实际上什么都没发生，
        //    而且下一局他还会在列表里看见那条配方。宁可当场报错。
        final Recipe<?> base = ShanhaiVanillaRecipeTable.pristine(id);
        final Recipe<?> live = ShanhaiVanillaRecipeTable.byIdFromManager(server, id);
        if (base == null && live == null) {
            ShanhaiMod.LOGGER.error("{} vanilla_remove_missing id={} -> MISSING：非 GT 底本与活配方表里"
                            + "都没有这条（删除被拒；台账与覆盖文件一个字节都没动）", PREFIX, id);
            return fail("这条配方不存在，删不了", "MISSING no such recipe id: " + id);
        }
        final String typeStr = typeIdStringOf(base != null ? base : live);
        final boolean createdByEditor = ShanhaiVanillaRecipeTable.isNew(id);
        // 🔴 判据是【覆盖文件里那条 entry 的 op】，不是"活配方表里有没有它" —— 见
        //    existingAddEntry 的类注释（上一刀那条 P0 就是这么消失的）。
        final JsonObject addEntry = existingAddEntry(id);
        final String fp = (createdByEditor || addEntry != null) ? null : resolveBaseFp(server, id);
        ShanhaiVanillaRecipeTable.markRemoved(id);
        if (createdByEditor) {
            ShanhaiVanillaRecipeTable.forgetNew(id);
        }
        final long t0 = System.nanoTime();
        ShanhaiRecipeEditorOps.syncVanillaFromBase(server);
        final int vanillaMs = (int) ((System.nanoTime() - t0) / 1_000_000L);
        ShanhaiVanillaRecipeTable.invalidate();
        final PersistOutcome po;
        if (createdByEditor || addEntry != null) {
            // 本编辑器新建出来的 ⇒ 删除 = 把那条 op=add 抹掉，不写 op=remove
            //（写 op=remove 的话下一局开机那条配方压根不存在 ⇒ 覆盖层打一条吓人但无用的 MISSING）
            po = persist ? dropAddEntry(id, typeStr) : PersistOutcome.OFF;
        } else {
            po = persist ? persistRemove(server, id, typeStr, fp) : PersistOutcome.OFF;
        }
        ShanhaiMod.LOGGER.info("{} vanilla_edit_remove id={} type={} created_by_editor={} vanilla_ms={} "
                        + "persist={} still_in_table={}",
                PREFIX, id, typeStr, createdByEditor, vanillaMs, po.note(),
                readFromTable(server, id) != null);
        return new ShanhaiRecipeEditorOps.Result(true, "已删除 " + id,
                "vanilla_ms=" + vanillaMs + " " + po.note(), 0, vanillaMs, true, po.written(), fp);
    }

    // ================================================================= 🆕 第 12 刀：新建一条非 GT 配方

    /**
     * 🔴 <b>本版能"新建"的非 GT 配方类型 —— 白名单，不是"凡是能反序列化就建"。</b>
     *
     * <p>入参是<b>配方类型 id</b>（编辑器第一屏那一行，= {@code RecipeType} 的键），
     * 返回<b>新建时写进 JSON 的 {@code type}</b>（= 序列化器的键）。{@code null} = 本版不建。
     *
     * <h4>🔴 为什么 {@code minecraft:crafting} 落成 {@code crafting_shaped}</h4>
     * 1.20.1 里 {@code ShapedRecipe.getType()} 与 {@code ShapelessRecipe.getType()} <b>都是</b>
     * {@code RecipeType.CRAFTING}（键 {@code minecraft:crafting}）⇒ 编辑器那个类型下面
     * <b>有形状与无形状两种配方混在一起</b>，光看类型 id 分不出来。取舍：默认建<b>有形状</b>的
     * （工作台上最常见的那种，也是任务书点名的例子）；建完之后在第三屏里怎么摆都行
     * （形状会被 trim 成实际占的格子，1×1 也表达得出来）。<b>这一条是设计选择，写进报告的「待你确认」。</b>
     *
     * <h4>为什么不给别的类型也开</h4>
     * 其余 13 个 mod 的 37 个类型的字段形状根本不是"物品进 / 物品出"那一套
     * （{@code ammo/weight}、{@code terminalA/terminalB}、{@code fluid_input}…），
     * 硬塞一条模板进去只会造出一条各 mod 自己读不懂的配方。
     * 本版对它们<b>保持只读 ＋ 可见提示</b>（{@code ShanhaiVanillaRecipeShape.Mode.NONE} 那一屏）。
     */
    public static String newSerializerTypeFor(ResourceLocation typeId) {
        if (typeId == null) {
            return null;
        }
        final String t = typeId.toString();
        return switch (t) {
            case "minecraft:crafting" -> "minecraft:crafting_shaped";
            case "minecraft:smelting", "minecraft:blasting", "minecraft:smoking",
                 "minecraft:campfire_cooking", "minecraft:stonecutting" -> t;
            default -> null;
        };
    }

    /**
     * 新建配方用的<b>占位素材</b>。
     *
     * <p>原版<b>不许</b>一条"空配方"存在（{@code ShapedRecipe.Serializer} 会以
     * {@code Invalid pattern} 直接拒收）—— 所以新建时必须给一份形状合法、但<b>在生存里拿不到</b>
     * 的占位物：{@code minecraft:barrier}。
     * 这样"我刚点的新建、还没填"这条状态<b>物理上不可能被当成能用的配方</b>，
     * 而不是"靠用户记得去填"。面板上那句话会明说这件事。
     */
    public static final String PLACEHOLDER_ITEM = "minecraft:barrier";

    /** 一份形状合法的空壳配方 JSON（按序列化器类型给；不认的类型返回 {@code null}）。 */
    public static JsonObject newRecipeSkeleton(String serializerType) {
        if (serializerType == null) {
            return null;
        }
        final JsonObject j = new JsonObject();
        j.addProperty("type", serializerType);
        final JsonObject in = new JsonObject();
        in.addProperty("item", PLACEHOLDER_ITEM);
        final JsonObject result = new JsonObject();
        result.addProperty("item", PLACEHOLDER_ITEM);
        result.addProperty("count", 1);
        switch (serializerType) {
            case "minecraft:crafting_shaped" -> {
                final com.google.gson.JsonArray pat = new com.google.gson.JsonArray();
                pat.add("a");
                final JsonObject key = new JsonObject();
                key.add("a", in);
                j.add("pattern", pat);
                j.add("key", key);
                j.add("result", result);
            }
            case "minecraft:crafting_shapeless" -> {
                final com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
                arr.add(in);
                j.add("ingredients", arr);
                j.add("result", result);
            }
            case "minecraft:smelting", "minecraft:blasting", "minecraft:smoking",
                 "minecraft:campfire_cooking" -> {
                j.add("ingredient", in);
                j.add("result", result);
                j.addProperty("cookingtime", 200);
                j.addProperty("experience", 0.0d);
            }
            case "minecraft:stonecutting" -> {
                j.add("ingredient", in);
                j.add("result", result);
            }
            default -> {
                return null;
            }
        }
        return j;
    }

    /**
     * <b>这个 id 现在能不能用</b>（占用了就返回人话原因，能用返回 {@code null}）。
     *
     * <p>查三处：非 GT 底本、活配方表、<b>覆盖文件</b>。第三处最容易漏 ——
     * 覆盖文件里那条 {@code op=add} 说明"有一条同 id 的配方会在下次开机关机时被重放出来"，
     * 只查前两处在"本局还没重放"的场合会误判成可用 ⇒ 静默覆盖掉用户的新建。
     */
    public static String idTakenReason(MinecraftServer server, ResourceLocation id) {
        if (id == null) {
            return "id 为空";
        }
        if (ShanhaiVanillaRecipeTable.pristine(id) != null) {
            return "非 GT 底本里已经有这个 id";
        }
        if (ShanhaiVanillaRecipeTable.byIdFromManager(server, id) != null) {
            return "活配方表里已经有这个 id";
        }
        try {
            if (ShanhaiRecipeOverrideStore.findEntry(id) != null) {
                return "覆盖文件 recipe_overrides.json 里已经有一条同 id 的 entry";
            }
        } catch (Throwable t) {
            return "查覆盖文件时抛了：" + t;
        }
        return null;
    }

    /**
     * 找一个<b>不撞</b>的新 id：{@code shanhai:<类型路径>/new_recipe_N}（与 GT 那条口径逐字相同）。
     *
     * @return 可用的 id；5000 个候选全撞了返回 {@code null}（调用方报错，<b>绝不静默覆盖</b>）
     */
    public static ResourceLocation findFreeId(MinecraftServer server, ResourceLocation typeId) {
        if (typeId == null) {
            return null;
        }
        for (int i = 1; i <= 5000; i++) {
            final ResourceLocation cand = new ResourceLocation("shanhai", typeId.getPath() + "/new_recipe_" + i);
            if (idTakenReason(server, cand) == null) {
                return cand;
            }
        }
        return null;
    }

    /**
     * <b>按一份非 GT 配方 JSON 造一条新配方并让它立刻生效</b>（面板「新建配方」的后端）。
     *
     * <h4>六步，缺一不可</h4>
     * <pre>
     *   ① id 占用闸门（底本 / 活表 / 覆盖文件三处）—— 撞了当场失败，绝不静默覆盖
     *   ② {@code RecipeManager.fromJson(id, json)}（public static，javap 实证）——
     *      用原版自己的序列化器造对象；<b>形状不对它当场抛</b>，我们就不往下走
     *   ③ 类型闸门：造出来的那条必须落在用户选的那个配方类型里（否则白名单/模板对不上）
     *   ④ {@link ShanhaiVanillaRecipeTable#registerNew} —— 登记进非 GT 底本（否则下次重建索引时静默消失）
     *   ⑤ 写回 {@code RecipeManager}（整表重写，一次）—— 这就是"立刻生效"（合成台每次现读那张表）
     *   ⑥ 落盘 {@code op=add}（由调用方决定 persist）
     * </pre>
     *
     * @return 造出来的活配方；任何一步失败都返回 {@code null}（调用方据此报错，<b>不静默</b>）
     */
    public static Recipe<?> addRecipeFromJson(MinecraftServer server, ResourceLocation typeId,
                                              ResourceLocation id, JsonObject json, boolean persist,
                                              String[] outNote) {
        if (server == null || typeId == null || id == null || json == null) {
            if (outNote != null) {
                outNote[0] = "参数为空";
            }
            return null;
        }
        ShanhaiVanillaRecipeTable.captureIfAbsent(server);
        final String taken = idTakenReason(server, id);
        if (taken != null) {
            ShanhaiMod.LOGGER.error("{} vanilla_new_id_taken id={} type={} reason={} -> 本次新建被拒"
                    + "（绝不静默覆盖）", PREFIX, id, typeId, taken);
            if (outNote != null) {
                outNote[0] = "id 被占了：" + taken;
            }
            return null;
        }
        final Recipe<?> r;
        try {
            r = RecipeManager.fromJson(id, json);
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.error("{} vanilla_new_parse_failed id={} type={} err={} json={}",
                    PREFIX, id, typeId, t.toString(), json);
            if (outNote != null) {
                outNote[0] = "原版反序列化没过：" + t.getMessage();
            }
            return null;
        }
        if (r == null) {
            ShanhaiMod.LOGGER.error("{} vanilla_new_parse_null id={} type={}", PREFIX, id, typeId);
            if (outNote != null) {
                outNote[0] = "原版反序列化返回 null";
            }
            return null;
        }
        final ResourceLocation actualType = ShanhaiVanillaRecipeTable.typeIdOf(r);
        if (!typeId.equals(actualType)) {
            ShanhaiMod.LOGGER.error("{} vanilla_new_type_mismatch id={} want_type={} got_type={} class={} "
                            + "-> 造出来的不是这个类型 ⇒ 拒收（否则会把配方塞进错误的类型里）",
                    PREFIX, id, typeId, actualType, r.getClass().getName());
            if (outNote != null) {
                outNote[0] = "造出来的类型是 " + actualType + "（期望 " + typeId + "）";
            }
            return null;
        }
        if (!ShanhaiVanillaRecipeTable.registerNew(r)) {
            if (outNote != null) {
                outNote[0] = "登记底本失败（id 撞了）";
            }
            return null;
        }
        insertIntoTable(server, r);
        ShanhaiVanillaRecipeTable.invalidate();
        boolean written = false;
        if (persist) {
            try {
                final JsonObject e = ShanhaiRecipeOverrideStore.makeAddEntry(
                        ShanhaiRecipeOverrideStore.nextUid(), id.toString(), typeId.toString(), json);
                written = ShanhaiRecipeOverrideStore.upsert(e) >= 0;
            } catch (Throwable t) {
                ShanhaiMod.LOGGER.error("{} vanilla_new_persist_threw id={} err={}", PREFIX, id, t.toString());
            }
        }
        ShanhaiMod.LOGGER.info("{} vanilla_new_applied id={} type={} ser_type={} class={} persist={} "
                        + "in_live={} in_base={}",
                PREFIX, id, typeId, json.has("type") ? json.get("type").getAsString() : "?",
                r.getClass().getName(), written ? "op=add(written)" : (persist ? "FAILED" : "off"),
                readFromTable(server, id) != null, ShanhaiVanillaRecipeTable.pristine(id) != null);
        if (outNote != null) {
            outNote[0] = written ? "已写进 config/shanhai/recipe_overrides.json（op=add）"
                    : (persist ? "落盘失败（配方本局已生效，重启后会丢）" : "未落盘（persist=false）");
        }
        return r;
    }

    /** 把一条新配方单独插进 {@code RecipeManager}（纯新增的一小段，不碰任何既有分支）。 */
    private static boolean insertIntoTable(MinecraftServer server, Recipe<?> recipe) {
        if (recipe == null || recipe.getId() == null) {
            return false;
        }
        try {
            final RecipeManager rm = server.getRecipeManager();
            final List<Recipe<?>> all = new ArrayList<>(rm.getRecipes());
            for (Recipe<?> r : all) {
                if (recipe.getId().equals(r.getId())) {
                    ShanhaiMod.LOGGER.error("{} vanilla_new_already_in_table id={} class={} -> 拒收"
                                    + "（表里已经有一条同 id 的配方；绝不覆盖别人的）",
                            PREFIX, recipe.getId(), r.getClass().getName());
                    return false;
                }
            }
            all.add(recipe);
            rm.replaceRecipes(all);
            // 🆕 第 12 刀：与 syncVanillaFromBase 同一处纪律 —— 新建出来的这一条也必须
            //    真的出现在 byType 里（否则合成台那一侧查不到它，用户看到的是"新建了但用不了"）。
            ShanhaiRecipeTableHook.forceWriteBack(server, all);
            ShanhaiMod.LOGGER.info("{} vanilla_new_inserted id={} total={}", PREFIX, recipe.getId(), all.size());
            return true;
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.error("{} vanilla_new_insert_failed id={} err={}",
                    PREFIX, recipe.getId(), t.toString());
            return false;
        }
    }


    /** 恢复一条：台账清掉 ＋ 覆盖文件里那条去掉 ⇒ 原版表回到<b>底本原对象</b>。 */
    public static ShanhaiRecipeEditorOps.Result restoreOne(MinecraftServer server, ResourceLocation id,
                                                           boolean persist) {
        if (server == null || id == null) {
            return fail("目标配方不存在", "server/id == null");
        }
        ShanhaiVanillaRecipeTable.captureIfAbsent(server);
        ShanhaiVanillaRecipeTable.clearEdit(id);
        final long t0 = System.nanoTime();
        ShanhaiRecipeEditorOps.syncVanillaFromBase(server);
        final int vanillaMs = (int) ((System.nanoTime() - t0) / 1_000_000L);
        ShanhaiVanillaRecipeTable.invalidate();
        final int dropped = persist ? ShanhaiRecipeOverrideStore.removeEntryById(id) : -1;
        ShanhaiMod.LOGGER.info("{} vanilla_edit_restore id={} file_entries_dropped={} vanilla_ms={}",
                PREFIX, id, dropped, vanillaMs);
        return new ShanhaiRecipeEditorOps.Result(true, "已恢复 " + id,
                "file_entries_dropped=" + dropped + " vanilla_ms=" + vanillaMs,
                0, vanillaMs, true, dropped >= 0, null);
    }

    /** 一键恢复全部非 GT 编辑。 */
    public static ShanhaiRecipeEditorOps.Result restoreAll(MinecraftServer server) {
        if (server == null) {
            return fail("服务端为空", "server == null");
        }
        ShanhaiVanillaRecipeTable.captureIfAbsent(server);
        final Set<ResourceLocation> touched = ShanhaiVanillaRecipeTable.everTouchedSnapshot();
        final int ledgerBefore = ShanhaiVanillaRecipeTable.ledgerSize();
        int fileDropped = 0;
        for (ResourceLocation id : touched) {
            final int n = ShanhaiRecipeOverrideStore.removeEntryById(id);
            if (n > 0) {
                fileDropped += n;
            }
        }
        ShanhaiVanillaRecipeTable.clearAllEditsKeepTypes();
        ShanhaiVanillaRecipeTable.clearEverRemoved();
        // 🆕 第 12 刀：本编辑器【新建】出来的那些配方，文件里那条 op=add 刚被清掉 ⇒
        //    活表里也必须摘掉，否则"恢复全部"只对了一半（文件干净了、表里还多着两条）。
        //    手法与"删除一条新建配方"完全一致：标 removed ＋ 从底本里忘掉（否则尾部那圈
        //    "被删过又撤销就补插"会把它们复活）。必须在 clearAllEditsKeepTypes 之后做。
        final Set<ResourceLocation> created = ShanhaiVanillaRecipeTable.newIdsSnapshot();
        int createdDropped = 0;
        for (ResourceLocation id : created) {
            ShanhaiVanillaRecipeTable.markRemoved(id);
            ShanhaiVanillaRecipeTable.forgetNew(id);
            createdDropped++;
        }
        final long t0 = System.nanoTime();
        ShanhaiRecipeEditorOps.syncVanillaFromBase(server);
        final int vanillaMs = (int) ((System.nanoTime() - t0) / 1_000_000L);
        ShanhaiVanillaRecipeTable.invalidate();
        ShanhaiMod.LOGGER.info("{} VANILLA_RESTORE_ALL touched={} ledger_before={} file_entries_dropped={} "
                        + "created_dropped={} vanilla_ms={}",
                PREFIX, touched.size(), ledgerBefore, fileDropped, createdDropped, vanillaMs);
        return new ShanhaiRecipeEditorOps.Result(true,
                "已恢复全部非 GT 编辑（" + touched.size() + " 条）",
                "file_entries_dropped=" + fileDropped + " created_dropped=" + createdDropped
                        + " vanilla_ms=" + vanillaMs,
                0, vanillaMs, true, true, null);
    }

    // ---------------------------------------------------------------- 写回 RecipeManager

    /**
     * 🔴 <b>把非 GT 台账套进"要写回 RecipeManager 的那份全量列表"</b>（由
     * {@link ShanhaiRecipeEditorOps#syncVanillaFromBase} 在它的同一个循环之后调用）。
     *
     * <p>与 GT 那一段的规则逐条同构：
     * <ul>
     *   <li>没有台账条目的非 GT 配方 ⇒ <b>原样留着</b>（引用同一性也保住）；</li>
     *   <li>台账撤销了（我们动过、但现在没条目）⇒ <b>还原成底本原对象</b>
     *       （不还原的话表里留着的还是上一拍的改过副本 —— GT 那条线在冒烟里实测抓过这个真 bug）；</li>
     *   <li>台账说要删 ⇒ 从列表里摘掉，并记下"这条是我们删的"；</li>
     *   <li>台账说要改 ⇒ 用<b>底本</b>重建造一条新实例，替换掉列表里那一条。</li>
     * </ul>
     *
     * @return {@code int[]{replaced, reverted, dropped, reinserted, failed}}；台账为空时返回全 0
     *         <b>并且一个元素都不碰</b>（保证"没人在编辑原版配方"时行为与改动前逐字节相同）。
     */
    public static int[] applyLedger(List<Recipe<?>> all) {
        if (all == null) {
            return new int[]{0, 0, 0, 0, 0};
        }
        // 🔴 早退条件必须看【三个集合】。只看台账条数会漏掉"台账刚被清掉、表里还留着改过副本"
        //    那一拍 —— 那正是 restoreOne 走的顺序（`clearEdit` 之后才 sync）。
        //    实测（冒烟自检）：case=vanilla_restore ok=false expected_old=3 read=7 ledger_size=0。
        //    再加上 EVER_TOUCHED 之后，那一拍会正常走"还原成底本原对象"那一条分支。
        final boolean nothingToDo = ShanhaiVanillaRecipeTable.ledgerSize() == 0
                && ShanhaiVanillaRecipeTable.everRemovedCount() == 0
                && ShanhaiVanillaRecipeTable.everTouchedCount() == 0;
        if (nothingToDo) {
            // 完全没人在编辑原版配方 ⇒ 一个元素都不碰（保证行为与改动前逐字节相同）
            return new int[]{0, 0, 0, 0, 0};
        }
        int replaced = 0;
        int reverted = 0;
        int dropped = 0;
        int failed = 0;
        final Set<ResourceLocation> present = new HashSet<>();
        for (int i = 0; i < all.size(); i++) {
            final Recipe<?> r = all.get(i);
            if (!ShanhaiVanillaRecipeTable.isVanilla(r)) {
                continue;
            }
            final ResourceLocation id = r.getId();
            if (id == null) {
                continue;
            }
            final ShanhaiVanillaRecipeTable.Edit e = ShanhaiVanillaRecipeTable.editOf(id);
            if (e == null) {
                if (ShanhaiVanillaRecipeTable.everTouched(id)) {
                    final Recipe<?> base = ShanhaiVanillaRecipeTable.pristine(id);
                    if (base != null && !ShanhaiVanillaRecipeTable.isRemoved(id)) {
                        all.set(i, base);
                        present.add(id);
                        reverted++;
                        continue;
                    }
                }
                present.add(id);
                continue;
            }
            if (e.removed()) {
                all.remove(i);
                i--;
                dropped++;
                continue;
            }
            final Recipe<?> base = ShanhaiVanillaRecipeTable.pristine(id);
            if (base == null) {
                failed++;
                ShanhaiMod.LOGGER.error("{} vanilla_sync_missing_base id={} -> left as is", PREFIX, id);
                present.add(id);
                continue;
            }
            final Recipe<?> next = ShanhaiVanillaRecipeRebuild.rebuild(base, e.result(), e.cookingTime(),
                    e.experience(), e.shape());
            if (next == null) {
                failed++;
                ShanhaiMod.LOGGER.error("{} vanilla_sync_rebuild_failed id={} -> left as is（表里保留旧对象）",
                        PREFIX, id);
                present.add(id);
                continue;
            }
            all.set(i, next);
            present.add(id);
            replaced++;
        }
        // 「删掉再放回」：原版表是"改/删"语义，自己不会把条目插回来
        int reinserted = 0;
        for (ResourceLocation id : ShanhaiVanillaRecipeTable.allIds()) {
            if (present.contains(id) || !ShanhaiVanillaRecipeTable.everRemoved(id)
                    || ShanhaiVanillaRecipeTable.isRemoved(id)) {
                continue;
            }
            final Recipe<?> base = ShanhaiVanillaRecipeTable.pristine(id);
            if (base != null) {
                all.add(base);
                reinserted++;
            }
        }
        ShanhaiMod.LOGGER.info("{} vanilla_tables_apply replaced={} reverted={} dropped={} reinserted={} failed={}",
                PREFIX, replaced, reverted, dropped, reinserted, failed);
        return new int[]{replaced, reverted, dropped, reinserted, failed};
    }

    /** 从原版表里按 id 读回那一条（"立刻生效"的判据）。 */
    public static Recipe<?> readFromTable(MinecraftServer server, ResourceLocation id) {
        if (server == null || id == null) {
            return null;
        }
        final RecipeManager rm = server.getRecipeManager();
        for (Recipe<?> r : rm.getRecipes()) {
            if (ShanhaiVanillaRecipeTable.isVanilla(r) && id.equals(r.getId())) {
                return r;
            }
        }
        return null;
    }

    /** 按类型读回（判据：这条类型下有几条）。 */
    public static int countInTable(MinecraftServer server, ResourceLocation typeId) {
        if (server == null || typeId == null) {
            return -1;
        }
        int n = 0;
        for (Recipe<?> r : server.getRecipeManager().getRecipes()) {
            if (!ShanhaiVanillaRecipeTable.isVanilla(r)) {
                continue;
            }
            if (typeId.equals(ShanhaiVanillaRecipeTable.typeIdOf(r))) {
                n++;
            }
        }
        return n;
    }

    // ---------------------------------------------------------------- 落盘

    private record PersistOutcome(boolean written, String note) {
        static final PersistOutcome OFF = new PersistOutcome(false, "persist=off");
    }

    /**
     * 决定这次该写哪个 {@code base_fp} —— 与 GT 那条线
     * （{@code ShanhaiRecipeEditorOps.baseFpFor}）<b>同一套判据</b>：
     * 覆盖层这一局<b>套上了</b>就沿用文件里那份（它才是"源声明长什么样"），
     * 没套上就用缓存里那份。
     *
     * <p>🔴 判"套没套上"必须认得<b>原版配方的键名</b>。GT 那边的判据只认
     * {@code duration / data / inputs / outputs / tickInputs / conditions}，
     * 遇到 {@code result} 会走 default 分支判成"没套上" ⇒ 第二次保存就会写错指纹
     * ⇒ 下一局 STALE、不生效（GT 那条线在 duration 上踩过同一个坑）。
     * 所以这里自带一份 {@link #fieldsAppliedTo}。
     */
    static String resolveBaseFp(MinecraftServer server, ResourceLocation id) {
        final String cached = ShanhaiRecipeFingerprint.forRecipeId(id);
        try {
            final JsonObject existing = ShanhaiRecipeOverrideStore.findEntry(id);
            if (existing != null && existing.has("base_fp")
                    && existing.has("op") && "set".equals(existing.get("op").getAsString())) {
                final String want = existing.get("base_fp").getAsString();
                final JsonObject fields = existing.has("fields") && existing.get("fields").isJsonObject()
                        ? existing.getAsJsonObject("fields") : null;
                final boolean applied = fields != null && fieldsAppliedTo(id, fields);
                if (applied && want != null && !want.isEmpty()
                        && ShanhaiRecipeFingerprint.isCurrentVersion(want)) {
                    ShanhaiMod.LOGGER.info("{} vanilla_fp_source=file id={} reason=overlay_already_applied",
                            PREFIX, id);
                    return want;
                }
            }
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.warn("{} vanilla_fp_probe_threw id={} err={}", PREFIX, id, t.toString());
        }
        ShanhaiMod.LOGGER.info("{} vanilla_fp_source=cache id={} current_version={}",
                PREFIX, id, ShanhaiRecipeFingerprint.isCurrentVersion(cached));
        return cached;
    }

    /**
     * 文件里那份 {@code fields} 与<b>底本</b>当下的值是不是一致（＝覆盖层这一局确实套上了）。
     * 认不出的键一律判"没套上"（宁可走 cache 那一支，也不写一个可能对不上的指纹）。
     *
     * <p>🆕 阶段 2 加了输入侧四个键（{@code pattern} / {@code key} / {@code ingredients} /
     * {@code ingredient}）。<b>漏掉它们的后果是实打实的</b>：
     * 第二局开机时这一条会判成"没套上" ⇒ 走 cache 分支 ⇒ 写下一个<b>不是源声明</b>的指纹
     * ⇒ 第三局 STALE、不套用 = 用户眼里"重启就丢"。所以这里必须认全。
     */
    static boolean fieldsAppliedTo(ResourceLocation id, JsonObject fields) {
        if (fields == null || fields.size() == 0) {
            return false;
        }
        final Recipe<?> base = ShanhaiVanillaRecipeTable.pristine(id);
        if (base == null) {
            return false;
        }
        final ShanhaiVanillaRecipeView v = ShanhaiVanillaRecipeView.of(base);
        if (v == null) {
            return false;
        }
        for (var en : fields.entrySet()) {
            final String k = en.getKey();
            final var want = en.getValue();
            switch (k) {
                case "result" -> {
                    if (!want.isJsonObject()) {
                        return false;
                    }
                    final ItemStack w = parseResult(want.getAsJsonObject());
                    if (w.isEmpty()) {
                        return false;
                    }
                    if (v.result.isEmpty() || !w.getItem().equals(v.result.getItem())
                            || w.getCount() != v.result.getCount()) {
                        return false;
                    }
                }
                case "cookingtime" -> {
                    if (!want.isJsonPrimitive() || !want.getAsJsonPrimitive().isNumber()) {
                        return false;
                    }
                    if (v.cookingTime != want.getAsInt()) {
                        return false;
                    }
                }
                case "experience" -> {
                    if (!want.isJsonPrimitive() || !want.getAsJsonPrimitive().isNumber()) {
                        return false;
                    }
                    if (Math.abs(v.experience - want.getAsDouble()) > 1.0e-4) {
                        return false;
                    }
                }
                case "pattern", "key", "ingredients", "ingredient" -> {
                    if (!ShanhaiVanillaRecipeShape.shapeFieldsMatchBase(fields, base)) {
                        ShanhaiMod.LOGGER.warn("{} vanilla_fp_probe_inputs_mismatch id={} key={} "
                                        + "-> not_applied（这份 fields 描述的输入 ≠ 底本此刻的输入）",
                                PREFIX, id, k);
                        return false;
                    }
                }
                default -> {
                    ShanhaiMod.LOGGER.warn("{} vanilla_fp_probe_unknown_field id={} key={} -> not_applied",
                            PREFIX, id, k);
                    return false;
                }
            }
        }
        return true;
    }

    /** 覆盖层那份 {@code {"item":..,"count":..}} → ItemStack（解不出来给 EMPTY，绝不编）。 */
    public static ItemStack parseResult(JsonObject json) {
        if (json == null || json.size() == 0) {
            return ItemStack.EMPTY;
        }
        // ① 数据包那种写法（item / count / nbt）—— 我们写出去的就是这一种（见 resultJson 的注释）
        try {
            if (json.has("item")) {
                final net.minecraft.nbt.CompoundTag tag = new net.minecraft.nbt.CompoundTag();
                tag.putString("id", json.get("item").getAsString());
                tag.putInt("Count", json.has("count") ? json.get("count").getAsInt() : 1);
                if (json.has("nbt") && json.get("nbt").isJsonObject()) {
                    final var parsed = net.minecraft.nbt.CompoundTag.CODEC
                            .parse(JsonOps.INSTANCE, json.get("nbt"));
                    final var r = parsed.result();
                    if (r.isPresent()) {
                        tag.put("tag", r.get());
                    }
                }
                final ItemStack st = ItemStack.of(tag);
                if (!st.isEmpty()) {
                    return st;
                }
            }
        } catch (Throwable ignored) {
            // 落到下面那条
        }
        // ② {@code ItemStack.CODEC} 那种写法（id / Count）—— 兼容旧文件
        try {
            final var res = ItemStack.CODEC.parse(JsonOps.INSTANCE, json);
            final var either = res.result();
            if (either.isPresent()) {
                return either.get();
            }
        } catch (Throwable ignored) {
            // 交给调用方判空
        }
        return ItemStack.EMPTY;
    }

    private static PersistOutcome persistSet(MinecraftServer server, ResourceLocation id,
                                             ItemStack result, Integer cookingTime, Double experience,
                                             ShanhaiVanillaRecipeShape shape, String fp) {
        final Recipe<?> base = ShanhaiVanillaRecipeTable.pristine(id);
        final String typeId = base == null ? "minecraft:unknown"
                : ShanhaiVanillaRecipeTable.typeIdOf(base).toString();
        final JsonObject fields = new JsonObject();
        // 🔴 只带"这次真的改了的"键：文件的 upsert 是【按 id 整条替换】，
        //    但 GT 那边已经用"合并旧 entry 里没提到的键"把这个问题解决了 ——
        //    这里照做（见下面那段合并），否则"先改产物、再改时间"会把产物那一项从文件里抹掉。
        if (result != null) {
            fields.add("result", ShanhaiVanillaRecipeView.resultJson(result));
        }
        if (cookingTime != null) {
            fields.addProperty("cookingtime", cookingTime);
        }
        if (experience != null) {
            fields.addProperty("experience", experience);
        }
        // 🆕 阶段 2：输入侧那一段（pattern/key 或 ingredients 或 ingredient）。
        //    ⚠️ 空对象 = 这次没动输入 ⇒ 一个键都不加（旧条目行为逐字节不变）。
        if (shape != null && shape.isDirty()) {
            final JsonObject f = shape.fieldsJson();
            for (var en : f.entrySet()) {
                fields.add(en.getKey(), en.getValue());
            }
        }
        // 🔴🔴 第 12 刀（与 GT 侧第 11 刀那条 P0 逐字同构）：这条 id 如果【本来就是本编辑器
        //    新建出来的】（判据 = 覆盖文件里那条 entry 的 op 是 add），那么这次保存
        //    【必须继续沿用 op=add】，绝不能改写成 op=set。
        //    为什么绝不能拿"活配方表里有没有它"当判据：新建出来的那条在本局就在表里
        //    （本局刚造 / 上一局被 op=add 重放出来的），拿它当判据 ⇒ 第二次保存写 op=set
        //    ⇒ upsert 按 id 去重把 op=add 顶掉 ⇒ 下一局开机 `MISSING no such recipe id`
        //    ⇒ **配方消失**。op=add 条目本来就带整份 recipe JSON，把这次的字段并进去即可。
        final JsonObject passthrough = existingAddEntry(id);
        if (passthrough != null) {
            return persistStillAdd(id, typeId, fields, passthrough);
        }
        if (fp == null || fp.isEmpty() || ShanhaiRecipeFingerprint.UNKNOWN.equals(fp)) {
            ShanhaiMod.LOGGER.error("{} vanilla_persist_skipped id={} reason=base_fp_unavailable ({})",
                    PREFIX, id, ShanhaiRecipeFingerprint.lastDiagnosis());
            return new PersistOutcome(false, "persist=SKIPPED(fp_unavailable)");
        }
        try {
            final JsonObject existing = ShanhaiRecipeOverrideStore.findEntry(id);
            if (existing != null && existing.has("fields") && existing.get("fields").isJsonObject()) {
                final JsonObject old = existing.getAsJsonObject("fields");
                final List<String> merged = new ArrayList<>();
                for (var en : old.entrySet()) {
                    if (!fields.has(en.getKey())) {
                        fields.add(en.getKey(), en.getValue());
                        merged.add(en.getKey());
                    }
                }
                if (!merged.isEmpty()) {
                    ShanhaiMod.LOGGER.info("{} vanilla_persist_merged id={} keys={}", PREFIX, id, merged);
                }
            }
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.warn("{} vanilla_persist_merge_failed id={} err={}", PREFIX, id, t.toString());
        }
        final JsonObject e = ShanhaiRecipeOverrideStore.makeSetEntry(
                ShanhaiRecipeOverrideStore.nextUid(), id.toString(), typeId, fields, fp);
        return new PersistOutcome(ShanhaiRecipeOverrideStore.upsert(e) >= 0, "json_written");
    }

    /**
     * 🔴 <b>这条 id 是"本编辑器新建出来的"吗</b> —— 判据是<b>覆盖文件里那条 entry</b>的 op。
     *
     * <p>与 GT 侧 {@code ShanhaiRecipeEditorOps.existingAddEntry} 是同一个判据、同一段理由
     * （第 11 刀那条 P0 的现场记录见那里）。<b>第 12 刀为什么不复用同一份代码</b>：
     * GT 那份在 {@code ShanhaiRecipeEditorOps} 里是 {@code private}，且它的调用点绑着
     * {@code GTRecipeType}；非 GT 这一侧只要一个纯 JsonObject 的探针 ⇒ 在这里复制一份，
     * 口径逐字相同。
     *
     * @return 现有的那条 {@code op=add} 条目（没有就 {@code null}）
     */
    static JsonObject existingAddEntry(ResourceLocation id) {
        try {
            final JsonObject e = ShanhaiRecipeOverrideStore.findEntry(id);
            if (e != null && e.has("op") && "add".equals(e.get("op").getAsString())) {
                return e;
            }
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.warn("{} vanilla_add_passthrough_probe_failed id={} err={}"
                    + "（本次按原路写 op=set）", PREFIX, id, t.toString());
        }
        return null;
    }

    /**
     * 新建出来的非 GT 配方再次保存 ⇒ <b>继续写 {@code op=add}</b>，把这次的字段并进那份 recipe JSON。
     *
     * <p>🔴 非 GT 这一侧比 GT 那一侧更简单：{@code fields} 里的键（{@code result / cookingtime /
     * experience / pattern / key / ingredients / ingredient}）<b>本来就是原版配方 JSON 自己的键</b>
     * ⇒ 直接并进去即可，不需要任何形状转换。下一局覆盖层 {@code event.custom(recipe)} 原样重建 ⇒ 正好是最终状态。
     *
     * <p>本分支<b>不需要 base_fp</b>：{@code op=add} 的语义就是"从无到有"，没有底本可对。
     */
    private static PersistOutcome persistStillAdd(ResourceLocation id, String typeId, JsonObject fields,
                                                  JsonObject existing) {
        final JsonObject recipe = existing.has("recipe") && existing.get("recipe").isJsonObject()
                ? existing.getAsJsonObject("recipe").deepCopy() : new JsonObject();
        final List<String> merged = new ArrayList<>();
        for (var en : fields.entrySet()) {
            recipe.add(en.getKey(), en.getValue());
            merged.add(en.getKey());
        }
        final String typeStr = existing.has("type") && !existing.get("type").isJsonNull()
                ? existing.get("type").getAsString() : typeId;
        if (typeStr != null && !recipe.has("type")) {
            recipe.addProperty("type", typeStr);
        }
        final String uid = existing.has("uid") ? existing.get("uid").getAsString()
                : ShanhaiRecipeOverrideStore.nextUid();
        final JsonObject e = ShanhaiRecipeOverrideStore.makeAddEntry(uid, id.toString(), typeStr, recipe);
        final int n = ShanhaiRecipeOverrideStore.upsert(e);
        ShanhaiMod.LOGGER.info("{} vanilla_persist_kept_add id={} type={} merged_fields={} entries={} "
                        + "(这条配方是本编辑器【新建】出来的 ⇒ 继续沿用 op=add；"
                        + "改写成 op=set 会让它在下一局开机时被判定 MISSING 从而消失 —— 与 GT 侧那条 P0 同源)",
                PREFIX, id, typeStr, merged, n);
        return new PersistOutcome(n >= 0, "json_written_kept_add");
    }

    /** 新建出来的配方被"删除" ⇒ 把那条 {@code op=add} 抹掉（不写 {@code op=remove}）。 */
    private static PersistOutcome dropAddEntry(ResourceLocation id, String typeId) {
        final int n = ShanhaiRecipeOverrideStore.removeEntryById(id);
        ShanhaiMod.LOGGER.info("{} vanilla_persist_dropped_add id={} type={} entries={} "
                        + "(这条是本编辑器新建的 ⇒ 删除 = 把那条 op=add 抹掉，不写 op=remove)",
                PREFIX, id, typeId, n);
        return new PersistOutcome(n >= 0, "json_written_dropped_add");
    }

    private static PersistOutcome persistRemove(MinecraftServer server, ResourceLocation id,
                                                String typeId, String fp) {
        if (fp == null || fp.isEmpty() || ShanhaiRecipeFingerprint.UNKNOWN.equals(fp)) {
            ShanhaiMod.LOGGER.error("{} vanilla_persist_skipped id={} reason=base_fp_unavailable ({})",
                    PREFIX, id, ShanhaiRecipeFingerprint.lastDiagnosis());
            return new PersistOutcome(false, "persist=SKIPPED(fp_unavailable)");
        }
        final String t = typeId == null ? "minecraft:unknown" : typeId;
        final JsonObject e = ShanhaiRecipeOverrideStore.makeRemoveEntry(
                ShanhaiRecipeOverrideStore.nextUid(), id.toString(), t, fp);
        return new PersistOutcome(ShanhaiRecipeOverrideStore.upsert(e) >= 0, "json_written");
    }

    /** 一条非 GT 配方的类型 id 字符串（拿不到给 {@code minecraft:unknown}）。 */
    static String typeIdStringOf(Recipe<?> r) {
        if (r == null) {
            return "minecraft:unknown";
        }
        final ResourceLocation t = ShanhaiVanillaRecipeTable.typeIdOf(r);
        return t == null ? "minecraft:unknown" : t.toString();
    }

    // ---------------------------------------------------------------- 小工具

    private static ShanhaiRecipeEditorOps.Result fail(String msg, String detail) {
        return new ShanhaiRecipeEditorOps.Result(false, msg, detail, 0, 0, false, false, null);
    }

    private static String describeShort(ItemStack s) {
        if (s == null) {
            return "未动";
        }
        if (s.isEmpty()) {
            return "(空)";
        }
        final ResourceLocation k = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(s.getItem());
        return k + " x" + s.getCount();
    }
}
