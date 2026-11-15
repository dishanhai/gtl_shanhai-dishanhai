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
        boolean materialIo = true;
        boolean hubAbility = true;
        boolean parallel = true;
        for (PartAbility ability : abilities) {
            materialIo &= gtShanhai$isMaterialIo(ability);
            hubAbility &= gtShanhai$isHubAbility(ability);
            parallel &= ability == PartAbility.PARALLEL_HATCH;
        }
        gtShanhai$markPredicates(cir.getReturnValue(), materialIo, hubAbility, parallel);
    }

    @Inject(method = "ability", at = @At("RETURN"), remap = false)
    private static void gtShanhai$markTieredIoAbility(PartAbility ability, int[] tiers,
                                                     CallbackInfoReturnable<TraceabilityPredicate> cir) {
        gtShanhai$markPredicates(cir.getReturnValue(), gtShanhai$isMaterialIo(ability),
                gtShanhai$isHubAbility(ability), ability == PartAbility.PARALLEL_HATCH);
    }

    @Unique
    private static boolean gtShanhai$isMaterialIo(PartAbility ability) {
        return ability == PartAbility.IMPORT_ITEMS || ability == PartAbility.EXPORT_ITEMS
                || ability == PartAbility.IMPORT_FLUIDS || ability == PartAbility.EXPORT_FLUIDS;
    }

    @Unique
    private static boolean gtShanhai$isHubAbility(PartAbility ability) {
        return ability == PartAbility.INPUT_ENERGY || ability == PartAbility.OUTPUT_ENERGY
                || ability == PartAbility.SUBSTATION_INPUT_ENERGY || ability == PartAbility.SUBSTATION_OUTPUT_ENERGY
                || ability == PartAbility.INPUT_LASER || ability == PartAbility.OUTPUT_LASER
                || ability == PartAbility.PARALLEL_HATCH
                || ability == PartAbility.COMPUTATION_DATA_RECEPTION
                || ability == PartAbility.COMPUTATION_DATA_TRANSMISSION
                || ability == PartAbility.OPTICAL_DATA_RECEPTION
                || ability == PartAbility.OPTICAL_DATA_TRANSMISSION
                || "thread_modifier".equals(ability.getName());
    }

    @Unique
    private static void gtShanhai$markPredicates(TraceabilityPredicate predicate, boolean materialIo,
                                               boolean hubAbility, boolean parallel) {
        if (!materialIo && !hubAbility) return;
        for (var simple : predicate.common) {
            gtShanhai$markPredicate((ShanhaiHubIoPredicate) simple, materialIo, hubAbility, parallel);
        }
        for (var simple : predicate.limited) {
            gtShanhai$markPredicate((ShanhaiHubIoPredicate) simple, materialIo, hubAbility, parallel);
        }
    }

    @Unique
    private static void gtShanhai$markPredicate(ShanhaiHubIoPredicate predicate, boolean materialIo,
                                              boolean hubAbility, boolean parallel) {
        if (materialIo) predicate.gtShanhai$setIoAdmissionOnly(true);
        if (hubAbility) predicate.gtShanhai$markHubAbility();
        if (parallel) predicate.gtShanhai$markParallelAbility();
    }
}
