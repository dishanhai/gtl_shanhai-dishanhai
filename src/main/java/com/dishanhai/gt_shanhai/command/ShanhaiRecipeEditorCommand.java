package com.dishanhai.gt_shanhai.command;

import com.dishanhai.gt_shanhai.api.DShanhaiRecipeModifierAPI;
import com.dishanhai.gt_shanhai.common.recipe.RecipeRebuildService;
import com.dishanhai.gt_shanhai.common.recipe.editor.ShanhaiRecipeEditorOps;
import com.dishanhai.gt_shanhai.common.recipe.editor.ShanhaiRecipeOverrideStore;
import com.dishanhai.gt_shanhai.common.recipe.editor.ShanhaiRecipeQuery;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraftforge.fml.loading.FMLPaths;

import java.util.ArrayList;
import java.util.List;

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
                        .then(Commands.argument("type", StringArgumentType.word())
                                .then(Commands.argument("recipeId", StringArgumentType.greedyString())
                                        .executes(context -> view(
                                                context.getSource(),
                                                StringArgumentType.getString(context, "type"),
                                                StringArgumentType.getString(context, "recipeId"))))))
                .then(Commands.literal("重建")
                        .executes(context -> rebuild(context.getSource(), ""))
                        .then(Commands.argument("type", StringArgumentType.word())
                                .executes(context -> rebuild(
                                        context.getSource(),
                                        StringArgumentType.getString(context, "type")))))
                .then(Commands.literal("回滚")
                        .then(Commands.argument("recipeId", StringArgumentType.greedyString())
                                .executes(context -> rollback(
                                        context.getSource(),
                                        StringArgumentType.getString(context, "recipeId")))))
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
        var value = base.get();
        source.sendSuccess(() -> Component.literal(
                "[山海] " + value.recipeId() + " duration=" + value.duration()
                        + " eut=" + value.eut()
                        + " fp=" + com.dishanhai.gt_shanhai.common.recipe.editor.ShanhaiRecipeFingerprint.of(value)),
                false);
        return 1;
    }

    private static int rebuild(CommandSourceStack source, String type) {
        if (type == null || type.isEmpty()) {
            List<RecipeRebuildService.RebuildReport> reports = RecipeRebuildService.rebuildAll(
                    RecipeRebuildService.RebuildReason.RELOAD);
            RecipeRebuildService.rebuildVanillaManager(
                    source.getServer(),
                    new java.util.LinkedHashSet<>(DShanhaiRecipeModifierAPI.getRuntimeRuleTypeIds()));
            source.sendSuccess(() -> Component.literal(
                    "[山海] 已重建 " + reports.size() + " 个配方类型，revision="
                            + DShanhaiRecipeModifierAPI.getRecipeRevision()), false);
            return 1;
        }
        RecipeRebuildService.RebuildReport report = RecipeRebuildService.rebuildType(
                type, RecipeRebuildService.RebuildReason.RELOAD);
        RecipeRebuildService.rebuildVanillaManager(
                source.getServer(), java.util.Set.of(type));
        source.sendSuccess(() -> Component.literal(
                "[山海] 已重建 " + type + " kept=" + report.kept()
                        + " dropped=" + report.dropped()
                        + " revision=" + report.revision()), false);
        return 1;
    }

    private static int rollback(CommandSourceStack source, String recipeId) {
        ShanhaiRecipeEditorOps ops = new ShanhaiRecipeEditorOps(
                new ShanhaiRecipeOverrideStore(FMLPaths.GAMEDIR.get()
                        .resolve("config/gt_shanhai/recipe_overrides.json")));
        ShanhaiRecipeEditorOps.Result result = ops.rollback(recipeId);
        source.sendSuccess(() -> Component.literal(
                "[山海] 回滚 " + recipeId + " -> " + result.status()
                        + " revision=" + result.revision()), false);
        return result.status() == ShanhaiRecipeEditorOps.Result.Status.SUCCESS ? 1 : 0;
    }

    private static int selfcheck(CommandSourceStack source) {
        source.sendSuccess(() -> Component.literal(
                "[山海] recipe-editor selfcheck revision="
                        + DShanhaiRecipeModifierAPI.getRecipeRevision()
                        + " types=" + DShanhaiRecipeModifierAPI.getRuntimeRuleTypeIds().size()),
                false);
        return 1;
    }
}
