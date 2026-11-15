package com.shanhai.common.recipe.editor;

import com.google.gson.JsonObject;
import com.shanhai.ShanhaiMod;
import dev.latvian.mods.kubejs.recipe.RecipesEventJS;
import dev.latvian.mods.kubejs.recipe.RecipeJS;
import dev.latvian.mods.kubejs.util.JsonIO;
import net.minecraft.resources.ResourceLocation;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * 在 KubeJS 的<b>一次性窗口</b>里把每条配方的 {@code base_fp} 算好并缓存起来。
 *
 * <h2>为什么单独一个类</h2>
 * 本类<b>直接引用 KubeJS 的类型</b>（{@code RecipesEventJS} / {@code RecipeJS} / {@code JsonIO}）。
 * 把它和 {@link ShanhaiRecipeFingerprint} 分开，是为了让后者在任何时候都能被安全加载：
 * 只要没有 KubeJS，本类就不会被类加载器碰（调用点在 KubeJS 自己的插件回调里）。
 *
 * <h2>抓法与代价</h2>
 * 对每个 {@code RecipeJS}：{@code 'v2.' + src + ':' + 截断(JsonIO.toString(js.json))} —— <b>逐字复刻</b>
 * 覆盖层脚本 104-138 行的算法（见 {@link ShanhaiRecipeFingerprint} 的类注释）。
 * 算完只留<b>字符串</b>，不留 {@code RecipeJS} 引用：后者会把整批配方对象图钉死在内存里。
 *
 * <h2>数据源有三个，全都要看（少一个就会静默漏配方）</h2>
 * <ul>
 *   <li>{@code takenIds}：id → RecipeJS（KubeJS 用来防撞 id 的那张表，覆盖面最广）；</li>
 *   <li>{@code originalRecipes}：id → RecipeJS（事件开始时已存在的那些）；</li>
 *   <li>{@code addedRecipes}：脚本新增的那批（没有 id 键，靠 {@code kjs$getOrCreateId()} 回落）。</li>
 * </ul>
 * 三个源的条数都打进日志 —— 这样"某个源是空的"不会被读成"没有配方"。
 */
public final class ShanhaiRecipeFingerprintCapture {

    public static final String PREFIX = "[SHANHAI-EDIT] editor";

    private ShanhaiRecipeFingerprintCapture() {}

    /** 诊断用的固定探针 id（两条演示 id + 一条<b>多输入能力</b>的 + 用户实测那条）。 */
    private static final String[] PROBE_IDS = {
            "gtceu:assembler/zpm_256a_laser_source_hatch",
            "gtceu:assembler/assemble_electrum_pipe_small_restrictive",
            "gtceu:assembler/cover_steel_wire_gt_octal_rubber",
            "shanhai:pf/worldline_residual_fragment"};

    private static String safeFp(com.google.gson.JsonObject o) {
        try {
            final String fp = ShanhaiRecipeFingerprint.fingerprint(o);
            return fp == null ? "(unavailable)" : fp;
        } catch (Throwable t) {
            return "<canon-threw:" + t.getClass().getSimpleName() + ">";
        }
    }

    /**
     * <b>旧算法</b>（{@code v2}，没有规范化）在同一份 JSON 上会算出什么。
     *
     * <p>留着它是为了<b>让"键序不稳"这件事在日志里可见</b>：同一条配方跨两次开机，
     * {@code fp_v3=} 必须逐字节相同，而 {@code fp_v2=} 可以不同 —— 那个不同就是用户踩到的 B4。
     */
    private static String safeFpV2(com.google.gson.JsonObject o) {
        try {
            final String fp = ShanhaiRecipeFingerprint.fingerprintV2(o);
            return fp == null ? "(unavailable)" : fp;
        } catch (Throwable t) {
            return "<v2-threw:" + t.getClass().getSimpleName() + ">";
        }
    }

    /** 在 {@code KubeJSPlugin.injectRuntimeRecipes} 里被调用。 */
    public static void captureFrom(RecipesEventJS event) {
        final long t0 = System.nanoTime();
        // 🆕 第 12 刀：先把"这个相位到底拿不拿得到 RecipesEventJS.instance"测下来。
        //    本方法就跑在那个窗口【里面】（javap 实证：RecipesEventJS.post 内部调
        //    KubeJSPlugin.injectRuntimeRecipes，而 RecipeManagerMixin 在 post 返回之后才把
        //    那个静态字段置 null）⇒ 这里如果读到非 null，就证明"拿不到"只是【相位】问题，
        //    不是"永远拿不到"。
        // 🔴 读的就是**那个静态字段本身**（不是"参数非空"这种同义反复 —— 那样测出来的
        //    永远是 true，属于假绿）。
        boolean present = false;
        boolean sameInstance = false;
        String probeErr = "";
        try {
            final RecipesEventJS inst = RecipesEventJS.instance;
            present = inst != null;
            sameInstance = inst == event;
        } catch (Throwable t) {
            probeErr = " (" + t.getClass().getSimpleName() + ": " + t.getMessage() + ")";
        }
        ShanhaiRecipeFingerprint.noteWindowProbe("KubeJSPlugin.injectRuntimeRecipes", present);
        if (!probeErr.isEmpty() || !sameInstance) {
            ShanhaiMod.LOGGER.warn("{} fp_window_probe_detail instance==event={} err={}",
                    PREFIX, sameInstance, probeErr.isEmpty() ? "(none)" : probeErr);
        }
        final Map<ResourceLocation, String> out = new HashMap<>(65536);
        // 🆕 2026-10-05（duration 原始值）：同一个窗口【顺手】抓第二张表 —— id → 源声明里的 duration。
        //    为什么必须在这个窗口抓：这是全工程唯一能同时看到 (a) 每条配方的 id (b) 它"源声明长什么样"
        //    的地方；出了这个窗口，RecipeJS 会被丢弃，而运行期的 GTRecipe.duration 已经被
        //    gtlcore 的乘数改过了（见 ShanhaiRecipeDuration 的类注释）。
        //    代价：6.5 万条 × (Integer + 装箱) ≈ 几 MB，与已有的指纹缓存（24.5 MB 字符串）比可忽略。
        final Map<ResourceLocation, Integer> orig = new HashMap<>(65536);

        int fromTaken = harvest(event.takenIds, out, orig);
        int fromOriginal = harvest(event.originalRecipes, out, orig);
        int fromAdded = 0;
        try {
            final Collection<RecipeJS> added = event.addedRecipes;
            if (added != null) {
                for (RecipeJS js : added) {
                    if (js == null) {
                        continue;
                    }
                    final ResourceLocation id = safeId(js);
                    if (id != null && !out.containsKey(id)) {
                        final String fp = fpOf(js);
                        if (fp != null) {
                            out.put(id, fp);
                            fromAdded++;
                        }
                    }
                    if (id != null && !orig.containsKey(id)) {
                        final Integer d = durOf(js);
                        if (d != null) {
                            orig.put(id, d);
                        }
                    }
                }
            }
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.warn("{} fp_capture addedRecipes_failed err={}", PREFIX, t.toString());
        }

        long chars = 0;
        for (String s : out.values()) {
            chars += s.length();
        }
        final long ms = (System.nanoTime() - t0) / 1_000_000L;
        ShanhaiMod.LOGGER.info("{} fp_capture from_takenIds={} from_originalRecipes={} from_addedRecipes={} "
                        + "unique_ids={} total_chars={} approx_MB={} ms={}",
                PREFIX, fromTaken, fromOriginal, fromAdded, out.size(), chars,
                String.format("%.1f", chars / 1048576.0), ms);

        // 🔴 诊断拍（2026-10-05 第 6 局加，第 7 局改成 v3）：
        //    同一份 `recipe.json` 的两份读数一起打：
        //      fp_v3 = 规范化（键字典序）之后的指纹 —— 这是【当前口径】，跨开机必须逐字节相同；
        //      fp_v2 = 老算法（原样序列化）的指纹 —— 留着是为了让"键序跨开机不稳"这件事在日志里可见。
        for (String s : PROBE_IDS) {
            try {
                final ResourceLocation rid = new ResourceLocation(s);
                final RecipeJS js = event.takenIds == null ? null : event.takenIds.get(rid);
                if (js == null) {
                    ShanhaiMod.LOGGER.info("{} fp_probe id={} absent_from_takenIds", PREFIX, s);
                    continue;
                }
                ShanhaiMod.LOGGER.info("{} fp_probe id={} from_json={}", PREFIX, s, safeFp(js.json));
                ShanhaiMod.LOGGER.info("{} fp_probe_v2 id={} from_json={}", PREFIX, s, safeFpV2(js.json));
            } catch (Throwable t) {
                ShanhaiMod.LOGGER.warn("{} fp_probe id={} threw {}", PREFIX, s, t.toString());
            }
        }

        if (out.isEmpty()) {
            ShanhaiMod.LOGGER.error("{} fp_capture EMPTY -> 指纹缓存为空，编辑器将拒绝落盘（宁可没写，不写对不上的）", PREFIX);
        }
        ShanhaiRecipeFingerprint.installCache(out);
        // 原地装进 duration 缓存表（同一窗口的第二张表）。落在这里而不是上面，
        // 是为了让"指纹为空"那条 error 仍然按原样先打出来。
        ShanhaiRecipeDuration.installOriginals(orig);
        // 🆕 「已删除配方」列表的产物补份（见方法注释）。
        backfillRemovedSnapshots(event);
    }

    /**
     * 🆕 <b>给"已经被删掉的配方"补一份原样快照</b>（{@code config/shanhai/removed_recipe_snapshots.json}）。
     *
     * <h4>为什么必须在这个窗口里做</h4>
     * 覆盖文件里 {@code op=remove} 的条目<b>只有 id/type/base_fp</b>，<b>没有产物</b>
     * （{@link ShanhaiRecipeOverrideStore#makeRemoveEntry}）；而"删掉之后主产物是什么"这件事
     * <b>只在 KubeJS 的配方事件里还看得到</b>：那条被删的配方仍然躺在
     * {@code event.takenIds / originalRecipes / addedRecipes} 里
     * （本机字节码实证：{@code RecipesEventJS.remove} 走 {@code IDFilter} 那一支时只调
     * {@code RecipeJS.remove()} ⇒ 只把 {@code removed} 置 true，<b>不</b>从 map 里摘掉；
     * 现场见 {@code temp\editor-restore\ev\RecipesEventJS.javap.txt:1347} 与
     * {@code RecipeJS.javap.txt:1100}）。出了这个窗口 {@code RecipeJS} 就被丢弃了。
     *
     * <h4>🔴 2026-11-07 修的两个真因（用户实测「全是主产物未知，点进去输入输出为空」）</h4>
     * <ol>
     *   <li><b>漏了 {@code addedRecipes} 这个源</b>：用户那 17 条 {@code op=remove} 里，
     *       16 条是 <b>KubeJS 脚本新增</b>的配方 —— 覆盖层日志逐条写着
     *       {@code lookup id=… source=addedRecipes targets=1}，它们只存在于 {@code event.addedRecipes}，
     *       而老实现只查 {@code takenIds} + {@code originalRecipes} ⇒ 当场被判 {@code missing}。
     *       实测读数：{@code snapshot_backfill wanted=17 wrote=1 missing=16}（latest.log:3591）
     *       ⇒ 列表 {@code with_main=1/17}（latest.log:13041）。</li>
     *   <li><b>脚本新增配方的 {@code RecipeJS.json} 是个 18 字符的空壳</b>
     *       {@code {"type":"unknown"}}。字节码根因：{@code RecipeJS.createRecipe()} 先判
     *       {@code if (removed) return null;}，而"把组件值写进 json"的 {@code serialize()} 在那之后
     *       才调用（{@code RecipeJS.javap.txt} 里 {@code createRecipe} 偏移 0-8 / 23-27）
     *       ⇒ <b>被删的脚本配方永远不会被 serialize</b>，{@code json} 一直是空壳；
     *       {@code save()} 只置 {@code changed=true}，不写 json（同文件 498-505）。
     *       旁证：{@code duration_orig_installed unique_ids=52703}（latest.log:3589）明显小于
     *       指纹缓存 69231 —— 少的那一万多条正是 addedRecipes，它们连 {@code duration} 都没有。
     *       ⇒ 光把 {@code addedRecipes} 加进查找还不够，必须<b>主动 {@code serialize()} 一次再读</b>，
     *       而且读出来仍不可用就<b>不写</b>（写空壳等于把"缺快照"伪装成"有快照但未知"，
     *       并且让下次补份永远跳过它）。</li>
     * </ol>
     *
     * <h4>代价与安全</h4>
     * 只对覆盖文件里<b>确实有</b> remove/disable 条目的 id 干活（通常是几条到几十条），
     * 每条存一份 json；已经存过<b>可用</b>的一份直接跳过。任何一条失败都只打一条 warn，
     * <b>绝不影响</b>上面那两条既有缓存（指纹 / 原始时长）。
     */
    private static void backfillRemovedSnapshots(RecipesEventJS event) {
        final long t0 = System.nanoTime();
        int wanted = 0;
        int missing = 0;
        int repaired = 0;
        int unusable = 0;
        int fromTaken = 0;
        int fromOriginal = 0;
        int fromAdded = 0;
        final java.util.List<ShanhaiRemovedRecipeSnapshot.Pending> pending = new java.util.ArrayList<>();
        try {
            final java.util.List<String> removed = ShanhaiRecipeOverrideStore.removedIds();
            wanted = removed.size();
            if (wanted == 0) {
                ShanhaiMod.LOGGER.info("{} snapshot_backfill wanted=0（覆盖层里没有 op=remove/disable 条目）", PREFIX);
                return;
            }
            // 已有**可用**的那些先筛掉（一次读全表，别每条都读一遍文件）。
            // 🔴 注意口径：判的是"已有那份能不能用"，不是"id 在不在表里" ——
            //    旧版本写进去的空壳必须能被打回重做，否则永远不会被修好。
            final java.util.Map<String, JsonObject> have = ShanhaiRemovedRecipeSnapshot.allJson();
            final java.util.Set<String> wantedIds = new java.util.LinkedHashSet<>(removed);

            // 源 ③：addedRecipes（KubeJS 脚本新增的那批）。**单次线性扫**，只收 wanted 里的 id。
            //   为什么不用 takenIds：脚本新增的配方不在 takenIds 里（实测 missing=16 就是这么来的）。
            final java.util.Map<String, RecipeJS> addedById = new java.util.HashMap<>();
            try {
                final Collection<RecipeJS> added = event.addedRecipes;
                if (added != null) {
                    for (RecipeJS js : added) {
                        if (js == null) {
                            continue;
                        }
                        String id = null;
                        try {
                            final ResourceLocation rid = js.kjs$getOrCreateId();
                            id = rid == null ? null : rid.toString();
                        } catch (Throwable ignored) {
                            id = null;
                        }
                        if (id != null && wantedIds.contains(id)) {
                            addedById.put(id, js);
                        }
                    }
                }
            } catch (Throwable t) {
                ShanhaiMod.LOGGER.warn("{} snapshot_backfill addedRecipes_scan_failed err={}", PREFIX, t.toString());
            }

            for (String rid : removed) {
                if (rid == null || rid.isEmpty()) {
                    continue;
                }
                if (ShanhaiDeletedRecipeList.isUsableRecipeJson(have.get(rid))) {
                    continue;                          // 已经有好的那份 ⇒ 不动
                }
                final ResourceLocation id = ResourceLocation.tryParse(rid);
                if (id == null) {
                    missing++;
                    continue;
                }
                RecipeJS js = null;
                String from = "takenIds";
                try {
                    js = event.takenIds == null ? null : event.takenIds.get(id);
                    if (js != null) {
                        fromTaken++;
                    }
                } catch (Throwable ignored) {
                    js = null;
                }
                if (js == null && event.originalRecipes != null) {
                    try {
                        js = event.originalRecipes.get(id);
                        if (js != null) {
                            fromOriginal++;
                            from = "originalRecipes";
                        }
                    } catch (Throwable ignored) {
                        js = null;
                    }
                }
                if (js == null) {
                    js = addedById.get(rid);
                    if (js != null) {
                        fromAdded++;
                        from = "addedRecipes";
                    }
                }
                if (js == null) {
                    missing++;
                    continue;
                }
                final RecipeJS found = js;
                // type 的真值来源 = KubeJS 自己（不是我们猜的）：它既写进条目，也在
                // "json 是空壳"时用来把 type 补回 json（见 ShanhaiDeletedRecipeList#withTypeFrom）。
                String type = null;
                try {
                    final ResourceLocation t = found.kjs$getType();
                    type = t == null ? null : t.toString();
                } catch (Throwable ignored) {
                    type = null;
                }
                final JsonObject cur = safeJson(found);
                // 🔴 三段修复链（见 ShanhaiDeletedRecipeList#repairRecipeJson 的注释）：
                //    ① 现有 json 就够 ⇒ 不动它；② serialize()（值缺失会抛 RecipeExceptionJS，纯核里兜住）；
                //    ③ 翻 removed 借 createRecipe() 把 type/组件补齐（finally 必还原）。
                //    返回值（Recipe<?>）直接丢弃、不注册；newRecipe 一个字节都不动。
                final ShanhaiDeletedRecipeList.Repair rep = ShanhaiDeletedRecipeList.repairRecipeJson(
                        cur, type, new ShanhaiDeletedRecipeList.RepairSource() {
                            @Override
                            public JsonObject peekJson() {
                                return safeJson(found);
                            }

                            @Override
                            public JsonObject serializeJson() {
                                found.serialize();          // 值缺失 ⇒ throw RecipeExceptionJS（纯核里 catch）
                                return safeJson(found);
                            }

                            @Override
                            public JsonObject flipAndCreateJson() {
                                return ShanhaiDeletedRecipeList.flipAndCreate(
                                        found.removed,
                                        v -> found.removed = Boolean.TRUE.equals(v),
                                        () -> found.createRecipe(),
                                        () -> safeJson(found));
                            }
                        });
                final JsonObject json = rep == null ? null : rep.json();
                if (json == null) {
                    unusable++;
                    ShanhaiMod.LOGGER.warn("{} snapshot_backfill_unusable id={} from={} type={} "
                                    + "cur_chars={} cur_keys={} why={}"
                                    + "（补不到完整内容 ⇒ 不写半成品；列表会显示「缺快照」）",
                            PREFIX, rid, from, type, lenOf(cur), keysOf(cur),
                            rep == null ? "(null)" : rep.why());
                    continue;
                }
                if (!"raw".equals(rep.via())) {
                    repaired++;
                    ShanhaiMod.LOGGER.info("{} snapshot_backfill_repaired id={} from={} type={} snapshot_via={} "
                                    + "keys={} why={}",
                            PREFIX, rid, from, type, rep.via(), keysOf(json), rep.why());
                }
                pending.add(new ShanhaiRemovedRecipeSnapshot.Pending(rid, type, json));
            }
            // 一次读 + 一次写（见 ShanhaiRemovedRecipeSnapshot#putAllIfMissingOrUnusable 的理由）
            final int wrote = ShanhaiRemovedRecipeSnapshot.putAllIfMissingOrUnusable(pending);
            ShanhaiMod.LOGGER.info("{} snapshot_backfill wanted={} wrote={} missing={} repaired={} unusable={} "
                            + "sources[takenIds={} originalRecipes={} addedRecipes={}] ms={} "
                            + "（missing = 三个源里都没有它；unusable = 找到了但拿不到真内容。"
                            + "两者都 ⇒ 列表里显示「缺快照」，不会假装有）",
                    PREFIX, wanted, wrote, missing, repaired, unusable,
                    fromTaken, fromOriginal, fromAdded, (System.nanoTime() - t0) / 1_000_000L);
        } catch (Throwable t) {
            ShanhaiMod.LOGGER.warn("{} snapshot_backfill_failed wanted={} pending={} err={}（不影响指纹/时长那两张表）",
                    PREFIX, wanted, pending.size(), t.toString());
        }
    }

    /** 读 {@code RecipeJS.json}（不许抛：这个字段是 KubeJS 的公共字段，但读的时候可能被改成 null）。 */
    private static JsonObject safeJson(RecipeJS js) {
        try {
            return js == null ? null : js.json;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 一份 json 的字符数（日志用）。 */
    private static int lenOf(JsonObject o) {
        try {
            return o == null ? -1 : o.toString().length();
        } catch (Throwable t) {
            return -1;
        }
    }

    /** 一份 json 的键名（日志用，最多 10 个 —— 只为看清"是不是空壳"）。 */
    private static String keysOf(JsonObject o) {
        if (o == null) {
            return "(null)";
        }
        try {
            final StringBuilder sb = new StringBuilder("[");
            int n = 0;
            for (String k : o.keySet()) {
                if (n > 0) {
                    sb.append(',');
                }
                if (n >= 10) {
                    sb.append('…');
                    break;
                }
                sb.append(k);
                n++;
            }
            return sb.append(']').toString();
        } catch (Throwable t) {
            return "(?)";
        }
    }

    private static int harvest(Map<ResourceLocation, RecipeJS> source, Map<ResourceLocation, String> out,
                               Map<ResourceLocation, Integer> orig) {
        if (source == null) {
            return 0;
        }
        int n = 0;
        for (Map.Entry<ResourceLocation, RecipeJS> e : source.entrySet()) {
            if (e.getKey() == null || e.getValue() == null) {
                continue;
            }
            if (!orig.containsKey(e.getKey())) {
                final Integer d = durOf(e.getValue());
                if (d != null) {
                    orig.put(e.getKey(), d);
                }
            }
            if (out.containsKey(e.getKey())) {
                continue;
            }
            final String fp = fpOf(e.getValue());
            if (fp != null) {
                out.put(e.getKey(), fp);
                n++;
            }
        }
        return n;
    }

    /**
     * 这条 {@link RecipeJS} 在<b>源声明</b>里写的 {@code duration}。
     *
     * <p>读的就是 {@code js.json.duration}。这条在读法上是<b>有实测背书</b>的 ——
     * 冒烟日志 {@code [EDITOR-FP-PROBE-RAW] id=gtceu:assembler/zpm_256a_laser_source_hatch}
     * 打出的整份 json 里 {@code "duration":300} 与 {@code gtlcore.yaml} 那份乘数无关。
     *
     * <p>拿不到就返回 {@code null}（<b>绝不</b>用"实际值 ÷ 乘数"之类的反推兜底）。
     */
    private static Integer durOf(RecipeJS js) {
        try {
            final JsonObject json = js.json;
            if (json != null && json.has("duration") && json.get("duration").isJsonPrimitive()) {
                return json.get("duration").getAsInt();
            }
        } catch (Throwable ignored) {
            // 单条读不出来 ⇒ 这一条没有原始值（界面上会显示实际值并标注）
        }
        return null;
    }

    private static ResourceLocation safeId(RecipeJS js) {
        try {
            return js.kjs$getOrCreateId();
        } catch (Throwable t) {
            return null;
        }
    }

    /** 复刻覆盖层脚本的 {@code shanhaiOvrContent} + {@code shanhaiOvrFingerprint}（v3 = 规范化之后）。 */
    private static String fpOf(RecipeJS js) {
        JsonObject json = null;
        try {
            json = js.json;
        } catch (Throwable ignored) {
            json = null;
        }
        if (json != null) {
            try {
                final String fp = ShanhaiRecipeFingerprint.fingerprint(json);
                if (fp != null) {
                    return fp;
                }
            } catch (Throwable ignored) {
                // 落到下面那条"这条不给缓存"的告警（与脚本的降级口径一致：绝不产出一个假的）
            }
        }
        // 脚本的第二分支是 String(recipe)（Rhino 的 toString）。Java 侧复刻不了
        // （RecipeJS.toString() 走 KubeJS 的 recipeToString，两者不是同一个东西），
        // 所以这里【不产出】一个"看着像"的串 —— 宁可少一条，不可假一条。
        ShanhaiMod.LOGGER.warn("{} fp_capture no_json id={} -> 这条不给缓存（脚本会落到 fallback-tostring 分支，本侧不复刻）",
                PREFIX, safeId(js));
        return null;
    }
}
