package com.dishanhai.gt_shanhai.common.machine.part;

import com.dishanhai.gt_shanhai.common.item.PatternRecipeIdMatchPolicy;
import com.dishanhai.gt_shanhai.common.item.PatternRecipeTypeHelper;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import java.util.List;

/** 强匹配拒绝原因。星律红框、卡死告警、终端和 Jade 都用同一套文案。 */
public final class StellarPatternStrongMatchMessages {

    public enum Kind {
        NONE(""),
        MISSING("gt_shanhai.stellar.strong_match.missing"),
        PREFIX("gt_shanhai.stellar.strong_match.prefix"),
        UNRESOLVED("gt_shanhai.stellar.strong_match.unresolved"),
        IDENTITY("gt_shanhai.stellar.strong_match.identity");

        final String key;

        Kind(String key) {
            this.key = key;
        }
    }

    public static final class Report {
        public static final Report NONE = new Report(Kind.NONE, "");

        private final Kind kind;
        private final String detail;

        public Report(Kind kind, String detail) {
            this.kind = kind == null ? Kind.NONE : kind;
            this.detail = detail == null ? "" : detail;
        }

        public boolean failed() {
            return kind != Kind.NONE;
        }

        public Kind kind() {
            return kind;
        }

        public String detail() {
            return detail;
        }

        public Component headline() {
            return Component.translatable(kind.key);
        }

        public String line(int maxDetail) {
            if (!failed()) return "";
            String head = headline().getString();
            String one = detail.replace('\n', ' ').replace('\r', ' ').trim();
            if (one.isEmpty()) return head;
            if (maxDetail > 0 && one.length() > maxDetail) {
                one = one.substring(0, maxDetail);
            }
            return head + "：" + one;
        }

        public void addTooltip(List<Component> lines) {
            if (!failed() || lines == null) return;
            lines.add(headline().copy().withStyle(ChatFormatting.RED));
            String one = detail.replace('\n', ' ').replace('\r', ' ').trim();
            if (one.isEmpty()) return;
            if (one.length() > 180) {
                one = one.substring(0, 180);
            }
            lines.add(Component.literal(one).withStyle(ChatFormatting.GRAY));
        }
    }

    private StellarPatternStrongMatchMessages() {}

    public static Report explain(Level level, ItemStack stack) {
        if (stack == null || stack.isEmpty()) return Report.NONE;
        String typeId = PatternRecipeTypeHelper.readRecipeTypeId(stack);
        String recipeId = PatternRecipeTypeHelper.readRecipeId(stack);
        if (typeId.isEmpty() || recipeId.isEmpty()) {
            return new Report(Kind.MISSING, "");
        }
        if (!PatternRecipeIdMatchPolicy.hasTypePrefix(typeId, recipeId)) {
            return new Report(Kind.PREFIX, recipeId);
        }
        GTRecipe recipe = PatternRecipeTypeHelper.resolveRecipeForPattern(level, typeId, recipeId);
        if (recipe == null) {
            return new Report(Kind.UNRESOLVED,
                    PatternRecipeTypeHelper.describeResolveMiss(level, typeId, recipeId));
        }
        if (!PatternRecipeTypeHelper.storedRecipeFitsPattern(stack, level, recipe)) {
            return new Report(Kind.IDENTITY,
                    PatternRecipeTypeHelper.explainStoredRecipeMismatch(stack, level, recipe));
        }
        return Report.NONE;
    }
}
