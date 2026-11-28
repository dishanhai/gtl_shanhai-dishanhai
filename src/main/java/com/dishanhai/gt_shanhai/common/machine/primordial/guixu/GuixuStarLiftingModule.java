package com.dishanhai.gt_shanhai.common.machine.primordial.guixu;

import com.dishanhai.gt_shanhai.api.recipe.DShanhaiRecipeTypes;
import com.dishanhai.gt_shanhai.common.machine.primordial.PrimordialModuleRecipeLogic;
import com.dishanhai.gt_shanhai.common.machine.primordial.PrimordialThreadRecipeIdentity;
import com.dishanhai.gt_shanhai.common.machine.primordial.module.PrimordialParallelProcessingModuleBase;
import com.dishanhai.gt_shanhai.common.machine.starlifter.StellarResonanceLinks;
import com.gregtechceu.gtceu.api.machine.IMachineBlockEntity;
import com.gregtechceu.gtceu.api.machine.MetaMachine;
import com.gregtechceu.gtceu.api.recipe.GTRecipe;
import net.minecraft.ChatFormatting;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class GuixuStarLiftingModule extends PrimordialParallelProcessingModuleBase {
    private static final String TEXT = "gt_shanhai.machine.guixu_star_lifting_module.";
    private static final String CONSTRUCTION = "star_lifter_construction";

    public GuixuStarLiftingModule(IMachineBlockEntity holder, Object... args) {
        super(holder, args);
    }

    @Override
    public PrimordialModuleRecipeLogic createRecipeLogic(Object... args) {
        return new PrimordialModuleRecipeLogic(this) {
            @Override
            protected String paidBatchSourceFlag() {
                return CONSTRUCTION;
            }

            @Override
            protected Set<GTRecipe> lookupRecipeIterator() {
                Set<GTRecipe> recipes = super.lookupRecipeIterator();
                if (recipes.isEmpty() || linkReady()) return recipes;
                Set<GTRecipe> construction = new LinkedHashSet<>();
                for (GTRecipe recipe : recipes) {
                    if (recipe.data.getBoolean(CONSTRUCTION)) construction.add(recipe);
                }
                return construction;
            }
        };
    }

    @Override
    public boolean isValidHost(MetaMachine machine) {
        return machine instanceof GuixuVisionCoreMachine && super.isValidHost(machine);
    }

    @Override
    public boolean canWorkWithoutHost() {
        return false;
    }

    @Override
    protected boolean isGuixuHostBlocked() {
        return !(getHost() instanceof GuixuVisionCoreMachine host && host.isFormed());
    }

    @Override
    public boolean beforeWorking(GTRecipe recipe) {
        return (isConstructionBatch(recipe) || linkReady()) && super.beforeWorking(recipe);
    }

    @Override
    public boolean onWorking() {
        GTRecipe recipe = getRecipeLogic().getLastRecipe();
        return (isConstructionBatch(recipe) || linkReady()) && super.onWorking();
    }

    private boolean isConstructionBatch(GTRecipe recipe) {
        if (recipe == null) return false;
        if (recipe.data.contains(CONSTRUCTION)) return recipe.data.getBoolean(CONSTRUCTION);
        if (!recipe.data.contains(PrimordialThreadRecipeIdentity.KEY)
                || !(getLevel() instanceof ServerLevel level)) return false;

        // 旧批次曾丢失建造标记。仅用存档中已记录的扣料来源恢复豁免。
        var sources = PrimordialThreadRecipeIdentity.sourcesOf(recipe);
        if (sources.isEmpty()) return false;
        var recipes = level.getRecipeManager();
        for (var source : sources) {
            var id = ResourceLocation.tryParse(source.recipeId());
            if (id == null || !DShanhaiRecipeTypes.STAR_LIFTING.registryName.toString().equals(source.recipeType())
                    || !(recipes.byKey(id).orElse(null) instanceof GTRecipe original)
                    || original.recipeType != DShanhaiRecipeTypes.STAR_LIFTING
                    || !original.data.getBoolean(CONSTRUCTION)) return false;
        }
        recipe.data.putBoolean(CONSTRUCTION, true);
        return true;
    }

    private boolean linkReady() {
        if (!(getLevel() instanceof ServerLevel level)) return false;
        var server = level.getServer();
        var star = StellarResonanceLinks.get(server).starFor(GlobalPos.of(level.dimension(), getPos()));
        return star != null && StellarResonanceLinks.starReady(server, star);
    }

    @Override
    public void onMachineRemoved() {
        if (getLevel() instanceof ServerLevel level) {
            StellarResonanceLinks.get(level.getServer()).unlink(GlobalPos.of(level.dimension(), getPos()));
        }
        super.onMachineRemoved();
    }

    @Override
    protected void addHostStatusDisplay(List<Component> textList) {
        if (isGuixuHostBlocked()) {
            textList.add(Component.translatable(TEXT + "no_host").withStyle(ChatFormatting.RED));
        } else {
            super.addHostStatusDisplay(textList);
        }
        if (getLevel() instanceof ServerLevel level) {
            var server = level.getServer();
            var star = StellarResonanceLinks.get(server).starFor(GlobalPos.of(level.dimension(), getPos()));
            if (star == null) {
                textList.add(Component.translatable(TEXT + "no_star").withStyle(ChatFormatting.YELLOW));
            } else {
                boolean ready = StellarResonanceLinks.starReady(server, star);
                textList.add(Component.translatable(TEXT + (ready ? "star_ready" : "star_unavailable"),
                        star.dimension().location(), star.pos().toShortString())
                        .withStyle(ready ? ChatFormatting.AQUA : ChatFormatting.YELLOW));
            }
        }
    }

    @Override
    protected String getMachineNameKey() {
        return TEXT + "name";
    }

    @Override
    protected String getMachineModeKey() {
        return "gtceu.recipe_type.star_lifting";
    }
}
