package com.dishanhai.gt_shanhai.mixin;

import com.dishanhai.gt_shanhai.api.machine.part.ShanhaiHubIoPredicate;
import com.gregtechceu.gtceu.api.machine.multiblock.PartAbility;
import com.gregtechceu.gtceu.api.pattern.Predicates;
import com.gregtechceu.gtceu.api.pattern.TraceabilityPredicate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 山海：在工厂保留能力来源，不通过候选列表猜测显式方块谓词。 */
@Mixin(value = Predicates.class, remap = false)
public class ShanhaiHubAbilityPredicateMixin {
    @Inject(method = "abilities", at = @At("RETURN"), remap = false)
    private static void gtShanhai$markIoAbilities(PartAbility[] abilities,
                                                 CallbackInfoReturnable<TraceabilityPredicate> cir) {
        if (abilities.length == 0) return;
        for (PartAbility ability : abilities) {
            if (!gtShanhai$isMaterialIo(ability)) return;
        }
        gtShanhai$markIoPredicates(cir.getReturnValue());
    }

    @Inject(method = "ability", at = @At("RETURN"), remap = false)
    private static void gtShanhai$markTieredIoAbility(PartAbility ability, int[] tiers,
                                                     CallbackInfoReturnable<TraceabilityPredicate> cir) {
        if (gtShanhai$isMaterialIo(ability)) gtShanhai$markIoPredicates(cir.getReturnValue());
    }

    @Unique
    private static boolean gtShanhai$isMaterialIo(PartAbility ability) {
        return ability == PartAbility.IMPORT_ITEMS || ability == PartAbility.EXPORT_ITEMS
                || ability == PartAbility.IMPORT_FLUIDS || ability == PartAbility.EXPORT_FLUIDS;
    }

    @Unique
    private static void gtShanhai$markIoPredicates(TraceabilityPredicate predicate) {
        for (var simple : predicate.common) {
            ((ShanhaiHubIoPredicate) simple).gtShanhai$setIoAdmissionOnly(true);
        }
        for (var simple : predicate.limited) {
            ((ShanhaiHubIoPredicate) simple).gtShanhai$setIoAdmissionOnly(true);
        }
    }
}
