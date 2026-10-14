package com.dishanhai.gt_shanhai.client.generator;

import com.dishanhai.gt_shanhai.common.item.generator.PatternGeneratorFilter;
import com.lowdragmc.lowdraglib.side.fluid.FluidStack;

import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.locale.Language;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.material.Fluids;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Locale;

/** 在客户端把玩家看到的名字换成注册表序号，再把紧凑的位图发给服务器。 */
public final class PatternGeneratorNameSearch {
    private record Name(ResourceLocation id, boolean fluid, String text) {}

    private static List<Name> names = List.of();
    private static String language = "";
    private static Language loadedLanguage;

    private PatternGeneratorNameSearch() {}

    public static PatternGeneratorFilter.NameHits find(String query) {
        var words = PatternGeneratorFilter.keywords(query);
        var items = new BitSet();
        var fluids = new BitSet();
        if (words.isEmpty()) return new PatternGeneratorFilter.NameHits(items, fluids);
        String currentLanguage = Minecraft.getInstance().options.languageCode;
        if (names.isEmpty() || !language.equals(currentLanguage) || loadedLanguage != Language.getInstance()) {
            var rebuilt = new ArrayList<Name>();
            for (var item : BuiltInRegistries.ITEM) {
                rebuilt.add(new Name(BuiltInRegistries.ITEM.getKey(item), false,
                        item.getDescription().getString().toLowerCase(Locale.ROOT)));
            }
            for (var fluid : BuiltInRegistries.FLUID) {
                if (fluid == Fluids.EMPTY) continue;
                rebuilt.add(new Name(BuiltInRegistries.FLUID.getKey(fluid), true,
                        FluidStack.create(fluid, 1).getDisplayName().getString().toLowerCase(Locale.ROOT)));
            }
            names = List.copyOf(rebuilt);
            language = currentLanguage;
            loadedLanguage = Language.getInstance();
        }
        for (Name name : names) {
            if (!PatternGeneratorFilter.containsKeyword(words, name.text())) continue;
            int index = name.fluid()
                    ? BuiltInRegistries.FLUID.getId(BuiltInRegistries.FLUID.get(name.id()))
                    : BuiltInRegistries.ITEM.getId(BuiltInRegistries.ITEM.get(name.id()));
            if (index >= 0) (name.fluid() ? fluids : items).set(index);
        }
        return new PatternGeneratorFilter.NameHits(items, fluids);
    }
}
