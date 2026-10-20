package com.dishanhai.gt_shanhai.command;

import com.dishanhai.gt_shanhai.api.DShanhaiRecipeModifierAPI;
import com.dishanhai.gt_shanhai.common.recipe.RecipeRebuildService;
import com.dishanhai.gt_shanhai.common.recipe.editor.ShanhaiRecipeBase;
import com.dishanhai.gt_shanhai.common.recipe.editor.ShanhaiRecipeEditorOps;
import com.dishanhai.gt_shanhai.common.recipe.editor.ShanhaiRecipeFingerprint;
import com.dishanhai.gt_shanhai.common.recipe.editor.ShanhaiRecipeOverrideStore;
import com.dishanhai.gt_shanhai.common.recipe.editor.ShanhaiRecipeQuery;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Set;

/**
 * {@code /山海 配方編輯 ...} 子命令樹。
 *
 * <p>配方類型一律用 {@code string()}：Brigadier 的 unquoted word 只允許
 * {@code [0-9A-Za-z_.+-]}，{@code gtceu:assembler} 的 {@code :} 會被解析失敗。</p>
 */
public final class ShanhaiRecipeEditorCommand {

    private ShanhaiRecipeEditorCommand() {}

    public static LiteralArgumentBuilder<CommandSourceStack> create() {
        return Commands.literal("配方编辑")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("查询")
                        .then(Commands.argument("text", StringArgumentType.greedyString())
                                .executes(context -> query(
                                        context.getSource(),
                                        StringArgumentType.getString(context, "text")))))
                .then(Commands.literal("查看")
                        .then(Commands.argument("type", StringArgumentType.string())
                                .then(Commands.argument("recipeId", StringArgumentType.greedyString())
                                        .executes(context -> view(
                                                context.getSource(),
                                                StringArgumentType.getString(context, "type"),
                                                StringArgumentType.getString(context, "recipeId"))))))
                .then(Commands.literal("重建")
                        .executes(context -> rebuild(context.getSource(), ""))
                        .then(Commands.argument("type", StringArgumentType.string())
                                .executes(context -> rebuild(
                                        context.getSource(),
                                        StringArgumentType.getString(context, "type")))))
                .then(Commands.literal("回滚")
                        .then(Commands.argument("type", StringArgumentType.string())
                                .then(Commands.argument("recipeId", StringArgumentType.greedyString())
                                        .executes(context -> rollback(
                                                context.getSource(),
                                                StringArgumentType.getString(context, "type"),
                                                StringArgumentType.getString(context, "recipeId"))))))
                .then(Commands.literal("自检")
                        .executes(context -> selfcheck(context.getSource())));
    }

    private static int query(CommandSourceStack source, String text) {
        ShanhaiRecipeQuery.Result result = ShanhaiRecipeQuery.query("", text, 0, 20);
        source.sendSuccess(() -> Component.literal(
                "[山海] 配方查询命中 " + result.total() + " 条，revision=" + result.revision()), false);
        return result.total() > 0 ? 1 : 0;
    }

    private static int view(CommandSourceStack source, String type, String recipeId) {
        var base = ShanhaiRecipeQuery.get(type, recipeId);
        if (base.isEmpty()) {
            source.sendFailure(Component.literal("[山海] 找不到配方: " + type + "/" + recipeId));
            return 0;
        }
        ShanhaiRecipeBase value = base.get();
        source.sendSuccess(() -> Component.literal(
                "[山海] " + value.recipeTypeId() + "/" + value.recipeId()
                        + " duration=" + value.duration()
                        + " eut=" + value.eut()
                        + " temp=" + value.blastTemp()
                        + " fp=" + ShanhaiRecipeFingerprint.of(value)),
                false);
        return 1;
    }

    private static int rebuild(CommandSourceStack source, String type) {
        if (type == null || type.isEmpty()) {
            List<RecipeRebuildService.RebuildReport> reports = RecipeRebuildService.rebuildAll(
                    RecipeRebuildService.RebuildReason.RELOAD);
            source.sendSuccess(() -> Component.literal(
                    "[山海] 已重建 " + reports.size() + " 个配方类型，revision="
                            + DShanhaiRecipeModifierAPI.getRecipeRevision()), false);
            return 1;
        }
        RecipeRebuildService.RebuildReport report = RecipeRebuildService.rebuildType(
                type, RecipeRebuildService.RebuildReason.RELOAD);
        source.sendSuccess(() -> Component.literal(
                "[山海] 已重建 " + type
                        + " type=" + report.recipeTypeId()
                        + " kept=" + report.kept()
                        + " dropped=" + report.dropped()
                        + " replaced=" + report.replaced()
                        + " revision=" + report.revision()), false);
        return report.kept() > 0 || report.dropped() > 0 ? 1 : 0;
    }

    private static int rollback(CommandSourceStack source, String type, String recipeId) {
        ShanhaiRecipeEditorOps ops = new ShanhaiRecipeEditorOps(overrideStore());
        ShanhaiRecipeEditorOps.Result result = ops.rollback(type, recipeId, source.getServer());
        source.sendSuccess(() -> Component.literal(
                "[山海] 回滚 " + type + "/" + recipeId + " -> " + result.status()
                        + " (" + result.message() + ")"
                        + " revision=" + result.revision()), false);
        return result.status() == ShanhaiRecipeEditorOps.Result.Status.SUCCESS ? 1 : 0;
    }

    private static ShanhaiRecipeOverrideStore overrideStore() {
        return new ShanhaiRecipeOverrideStore(
                com.dishanhai.gt_shanhai.config.ShanhaiConfigPaths.ensureParent(
                        com.dishanhai.gt_shanhai.config.ShanhaiConfigPaths.recipeOverrides()));
    }

    /**
     * 自檢：revision、類型數、快照條目數、雙表一致性與本輪失敗階段。
     * 只看 revision 無法判斷規則有沒有真的落到兩張表上。
     */
    private static int selfcheck(CommandSourceStack source) {
        long revision = DShanhaiRecipeModifierAPI.getRecipeRevision();
        Set<String> ruleTypes = DShanhaiRecipeModifierAPI.getRuntimeRuleTypeIds();
        Set<String> overrideTypes = RecipeRebuildService.overrideTypeIds();
        int snapshotTypeCount = com.dishanhai.gt_shanhai.common.recipe.RecipeOriginalSnapshotStore
                .typeIds().size();
        int snapshotEntries = 0;
        for (String typeId : com.dishanhai.gt_shanhai.common.recipe.RecipeOriginalSnapshotStore
                .typeIds()) {
            snapshotEntries += com.dishanhai.gt_shanhai.common.recipe.RecipeOriginalSnapshotStore
                    .copiesOf(typeId).size();
        }
        int tableMismatch = 0;
        int tableChecked = 0;
        var server = source.getServer();
        for (String typeId : RecipeRebuildService.allTypeIds()) {
            tableChecked++;
            int vanilla = com.dishanhai.gt_shanhai.common.recipe.editor.ShanhaiVanillaRecipeTable
                    .read(server, typeId).size();
            int canonical = RecipeRebuildService.buildCanonicalList(
                    typeId, RecipeRebuildService.originalsOfType(typeId)).size();
            if (vanilla != canonical) tableMismatch++;
        }
        String failureStage = tableMismatch > 0 ? "vanilla-table" : "none";
        int mismatch = tableMismatch;
        int checked = tableChecked;
        int entries = snapshotEntries;
        source.sendSuccess(() -> Component.literal(
                "[山海] recipe-editor selfcheck revision=" + revision
                        + " ruleTypes=" + ruleTypes.size()
                        + " overrideTypes=" + overrideTypes.size()
                        + " snapshotTypes=" + snapshotTypeCount
                        + " snapshotEntries=" + entries
                        + " tableChecked=" + checked
                        + " tableMismatch=" + mismatch
                        + " failureStage=" + failureStage), false);
        return mismatch == 0 ? 1 : 0;
    }
}
