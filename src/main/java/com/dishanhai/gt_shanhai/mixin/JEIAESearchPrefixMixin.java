package com.dishanhai.gt_shanhai.mixin;

import mezz.jei.api.helpers.IColorHelper;
import mezz.jei.api.runtime.IIngredientManager;
import mezz.jei.common.config.IIngredientFilterConfig;
import mezz.jei.core.search.PrefixInfo;
import mezz.jei.core.search.SearchMode;
import mezz.jei.core.search.suffixtree.GeneralizedSuffixTree;
import mezz.jei.gui.ingredients.IListElement;
import mezz.jei.gui.ingredients.IListElementInfo;
import mezz.jei.gui.search.ElementPrefixParser;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

@Mixin(targets = "mezz.jei.gui.search.ElementPrefixParser", remap = false)
public class JEIAESearchPrefixMixin {
    @Shadow
    private void addPrefix(PrefixInfo<IListElementInfo<?>, IListElement<?>> prefixInfo) {}

    @Inject(method = "<init>", at = @At("TAIL"), remap = false)
    private void gtShanhai$addAeSearchPrefixes(IIngredientManager ingredientManager, IIngredientFilterConfig filterConfig,
            IColorHelper colorHelper, CallbackInfo ci) {
        addPrefix(new PrefixInfo<>('*', () -> SearchMode.REQUIRE_PREFIX,
                JEIAESearchPrefixMixin::getIdSearchStrings, GeneralizedSuffixTree::new));
        addPrefix(new PrefixInfo<>('#', () -> SearchMode.REQUIRE_PREFIX,
                info -> getTagSearchStrings(info, ingredientManager), GeneralizedSuffixTree::new));
    }

    private static Collection<String> getIdSearchStrings(IListElementInfo<?> info) {
        ResourceLocation id = info.getResourceLocation();
        if (id == null) return List.of();
        return List.of(id.toString(), id.getPath());
    }

    private static Collection<String> getTagSearchStrings(IListElementInfo<?> info, IIngredientManager ingredientManager) {
        Collection<String> tagStrings = info.getTagStrings(ingredientManager);
        List<String> result = new ArrayList<>(tagStrings.size() + 8);
        result.addAll(tagStrings);
        info.getTagIds(ingredientManager).forEach(id -> {
            result.add(id.toString());
            result.add(id.getPath());
        });
        return result;
    }
}
