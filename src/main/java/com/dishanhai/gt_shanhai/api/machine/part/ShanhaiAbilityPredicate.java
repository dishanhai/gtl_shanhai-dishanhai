package com.dishanhai.gt_shanhai.api.machine.part;

/**
 * Ability-factory provenance, independent of hub admission/counting rules.
 * A candidate block can implement more abilities than the predicate asks for.
 */
public interface ShanhaiAbilityPredicate {
    boolean gtShanhai$hasNonMaterialAbility();

    void gtShanhai$markNonMaterialAbility();
}
