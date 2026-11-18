package com.dishanhai.gt_shanhai.common.recipe.editor;

import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** Chooses whether a single recipe can be restored without changing broad rules. */
public final class ShanhaiRecipeRestoreRules {
    public enum Decision { RESTORE, NOT_DISABLED, BLOCKED }

    private ShanhaiRecipeRestoreRules() {}

    public static boolean exact(String rule, String id) {
        return rule != null && id != null && (rule.equals(Pattern.quote(id))
                || (rule.equals(id) && id.chars().noneMatch(c -> "\\.[]{}()*+?^$|".indexOf(c) >= 0)));
    }

    public static Decision decide(String id, List<String> rules, boolean disabledToggle) {
        if (id == null || id.isEmpty()) return Decision.NOT_DISABLED;
        boolean exact = false;
        for (String rule : rules) {
            if (exact(rule, id)) {
                exact = true;
            } else {
                try {
                    if (rule == null || Pattern.compile(rule).matcher(id).find()) return Decision.BLOCKED;
                } catch (PatternSyntaxException e) {
                    return Decision.BLOCKED;
                }
            }
        }
        return exact || disabledToggle ? Decision.RESTORE : Decision.NOT_DISABLED;
    }
}
