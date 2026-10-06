package com.shanhai.common.recipe.editor;

import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import com.shanhai.ShanhaiMod;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.crafting.Recipe;

import java.util.ArrayList;
import java.util.List;

/**
 * 配方编辑器 · <b>「id 口径」自检</b>（2026-10-05 新增，用户点单第一优先项）。
 *
 * <h2>要断言的是什么</h2>
 * 判据只有一条，但必须用<b>shanhai: 命名空间</b>的配方当靶子：
 * <pre>
 *   编辑器写进覆盖文件的那个 id  ==  那条配方自己的真 id（{@code recipe.getId()}）
 * </pre>
 * ⚠️ 靶子为什么<b>不能</b>只挑 {@code gtceu:} 的：GTCEu 原版类型的配方 id 本来就是
 * {@code <ns>:<类型路径>/<path>}，而我们（以及 KubeJS 加成）的 shanhai: 配方走的是
 * GTCEu 的 {@code GTRecipeJS.id()}（字节码：{@code "%s/%s".formatted(typePath, path)}）
 * —— 只在 KubeJS 加成的那一类上，"id 该长什么样"才有判别力。
 * 这条纪律是本工程明确的血账：<b>靶子选错 ⇒ 拼错了也看不出来 ⇒ 假绿。</b>
 *
 * <h2>🔴 负对照（没有它这条断言就没有判别力）</h2>
 * 同时构造一个<b>故意拼错</b>的 id（在真实 id 的路径前再插一段同名段，复现
 * "多插了一段类型名"那个历史形状），并断言编辑器那条查找路<b>查不到它</b>。
 * 两条一起看才成立：
 * <pre>
 *   POSITIVE 真 id  → 查得到（found=true）      ← 断言本身成立
 *   NEGATIVE 错 id  → 查不到（found=false）     ← 证明这条断言认得出错
 * </pre>
 *
 * <h2>⚠️ 这份自检【不能】证明的事（如实交代）</h2>
 * 它证明的是"编辑器这一侧 id 口径自洽且可判别"。它<b>不能</b>证明"覆盖层脚本下一步一定套用得上" ——
 * 因为那取决于覆盖层脚本<b>在哪一相位查表</b>：KubeJS 自己加的配方在
 * {@code ServerEvents.recipes} 事件里<b>不在</b> forEachRecipe/countRecipes 的集合里
 * （本工程无头专服实测，原文见
 * {@code temp\smoke-rig\server\logs\kubejs\server.log} 的 {@code [IDPROBE]} 行：
 * {@code countRecipes_all=51000 shanhai_hits=0} ↔ {@code addedRecipes size=12950 shanhai_hits=4154}）。
 * 那一半由覆盖层脚本自己的 {@code overlay_idcheck}（同款正/负对照）负责，两条一起才覆盖整条链。
 */
public final class ShanhaiRecipeEditorIdCheck {

    private static final String TAG = "[SHANHAI-EDIT] idcheck";

    private ShanhaiRecipeEditorIdCheck() {}

    /** 跑一次，返回逐条原始读数（调用方直接打日志）。 */
    public static List<String> run(MinecraftServer server) {
        final List<String> out = new ArrayList<>();
        if (server == null) {
            out.add(TAG + " SKIPPED server=null");
            return out;
        }

        // ---- 找靶子：优先 shanhai: 命名空间（判据要求），找不到才退回任意一条 GT 配方并响亮标注 ----
        GTRecipe target = null;
        boolean shanhaiNamespace = false;
        int scanned = 0;
        for (Recipe<?> r : server.getRecipeManager().getRecipes()) {
            if (!(r instanceof GTRecipe gt) || gt.id == null) {
                continue;
            }
            scanned++;
            if ("shanhai".equals(gt.id.getNamespace())) {
                target = gt;
                shanhaiNamespace = true;
                break;
            }
            if (target == null) {
                target = gt;
            }
        }

        out.add(TAG + " scan recipes_with_id=" + scanned + " target_ns_shanhai=" + shanhaiNamespace);
        if (target == null) {
            out.add(TAG + " FAIL 没有任何带 id 的 GT 配方 ⇒ 这条自检无法判定（不算通过）");
            return out;
        }

        final ResourceLocation real = target.getId();
        final String realId = real.toString();

        // ---- POSITIVE：编辑器那条查找路必须按这个真 id 找回同一条配方 ----
        final GTRecipe viaIndex = ShanhaiRecipeReverseIndex.byId(server, real);
        final boolean found = viaIndex != null && viaIndex.getId() != null && viaIndex.getId().equals(real);
        // ---- 再核一遍"这个 id 就是配方管理器里的键"（而不是我们记在别处的影子值）----
        boolean isManagerKey = false;
        for (Recipe<?> r : server.getRecipeManager().getRecipes()) {
            if (r instanceof GTRecipe gt && real.equals(gt.getId())) {
                isManagerKey = true;
                break;
            }
        }
        out.add(TAG + " POSITIVE id=" + realId + " editor_lookup=" + found
                + " is_recipe_manager_key=" + isManagerKey + " expected=true PASS=" + (found && isManagerKey)
                + (shanhaiNamespace ? " (靶子=shanhai: 命名空间，符合判据要求)"
                : " ⚠️ 靶子不是 shanhai: 命名空间（本机没找到），这条断言的判别力下降，如实标注"));

        // ---- NEGATIVE CONTROL：故意把 id 拼错（多插一段路径名）⇒ 必须查不到 ----
        final String path = real.getPath();
        final int slash = path.indexOf('/');
        final String extra = slash > 0 ? path.substring(0, slash) : "typo_negative_control";
        final ResourceLocation typo = ResourceLocation.tryParse(
                real.getNamespace() + ":" + extra + "/" + path);
        final boolean typoFound = typo != null && ShanhaiRecipeReverseIndex.byId(server, typo) != null;
        out.add(TAG + " NEGATIVE_CONTROL id=" + (typo == null ? "(unparsable)" : typo.toString())
                + " editor_lookup=" + typoFound + " expected=false PASS=" + (!typoFound)
                + " (故意多插一段路径名；必须查不到 —— 否则这条断言没有判别力)");

        // ---- 覆盖层账本里那一条（如果他编辑过这条）与真 id 对不对得上 ----
        final var entry = ShanhaiRecipeOverrideStore.findEntry(real);
        if (entry != null) {
            final String entryId = entry.has("id") ? entry.get("id").getAsString() : "";
            out.add(TAG + " OVERLAY_ENTRY entry_id=" + entryId + " recipe_id=" + realId
                    + " equal=" + realId.equals(entryId) + " expected=true PASS=" + realId.equals(entryId));
        } else {
            out.add(TAG + " OVERLAY_ENTRY 账本里没有这一条（本轮没编辑过它）⇒ 这一项不作为判据");
        }

        ShanhaiMod.LOGGER.info("{} summary target={} shanhai_ns={} positive_pass={} negative_pass={}",
                TAG, realId, shanhaiNamespace, found && isManagerKey, !typoFound);
        return out;
    }
}
