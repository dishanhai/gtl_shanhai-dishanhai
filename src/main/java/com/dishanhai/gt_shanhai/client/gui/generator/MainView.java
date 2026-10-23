package com.dishanhai.gt_shanhai.client.gui.generator;

import com.dishanhai.gt_shanhai.common.item.generator.*;
import net.minecraft.network.chat.Component;
import static com.dishanhai.gt_shanhai.client.gui.generator.PatternGeneratorScreen.*;
import static com.dishanhai.gt_shanhai.client.gui.generator.PatternGeneratorClientState.*;

/** 山海：機器／配方類型與各頁入口。 */
final class MainView implements GeneratorView {
    @Override public int depth() { return 0; }
    @Override public void layout(PatternGeneratorScreen s) {
        s.label("step_machine"); s.machineSlot(); s.label("picker_hint");
        var settings=s.settings(); var types=PatternGeneratorRecipes.machineTypes(settings.machine);
        var labels=types.stream().map(type->Component.translatable(PatternGeneratorRecipes.typeTranslation(type))
                .append(" ("+s.state().typeCounts().getOrDefault(type.registryName.toString(),0)+") · "+type.registryName)).map(c->(Component)c).toList();
        int selected=0; for (int i=0;i<types.size();i++) if (types.get(i).registryName.toString().equals(settings.recipeType)) selected=i;
        s.label("step_recipe");
        s.dropdown("type",labels.isEmpty()?tr(settings.machine.isEmpty()?"type_wait_machine":"type_unavailable"):labels.get(selected),labels,selected,
                s.model.editable()&&!types.isEmpty(),i->s.model.send(TYPE,b->b.writeUtf(types.get(i).registryName.toString(),256)));
        Component circuit=settings.circuit<0?tr("circuit_any"):settings.circuit==0?tr("circuit_none"):tr("circuit_value",settings.circuit);
        s.row(choice("circuit",circuit,s.model.editable(),()->s.show(Page.CIRCUIT)),
                choice("filters",tr(settings.filterCount()==0?"filters_optional":"filters_active",settings.filterCount()),true,()->s.show(Page.FILTER)));
        s.row(choice("extensions",tr(settings.extensionsActive()?"extensions_active":"extensions"),true,()->s.show(Page.EXTENSION)),
                choice("preview",tr("open_preview",s.state().count()),!types.isEmpty(),()->s.show(Page.PREVIEW)));
        s.row(choice("presets",tr("presets"),true,s::openPresets),choice("save_preset",tr("save_preset"),true,()->s.show(Page.SAVE_PRESET)));
        s.button("fill",tr(s.model.transfers()>0?"fill_resume":"fill_target",s.state().availablePatterns()+(s.model.transfers()>0?s.model.transfers():s.state().selected())),
                !s.state().running()&&s.state().pendingCount()==0&&s.model.ready()&&(s.state().availablePatterns()>0||s.state().selected()>0||s.model.transfers()>0),()->s.action("fill",FILL));
        s.label("fill_hint");
    }
}
