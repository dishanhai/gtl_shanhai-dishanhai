package com.dishanhai.gt_shanhai.client.gui.generator;

import com.gregtechceu.gtceu.api.GTValues;
import net.minecraft.network.chat.Component;
import java.util.ArrayList;
import java.util.List;
import static com.dishanhai.gt_shanhai.client.gui.generator.PatternGeneratorScreen.*;

/** 山海：倍率、電壓、排序、查詢與略過已有樣板。 */
final class ExtensionView implements GeneratorView {
    @Override public void layout(PatternGeneratorScreen s) {
        var c=s.model; var settings=s.settings(); boolean enabled=c.editable();
        s.label("extensions");
        s.field("scale","scale",c.scaleDraft,2,value->c.extension(0,value,now()),enabled);
        s.field("output_scale","output_scale",c.outputScaleDraft,2,value->c.extension(1,value,now()),enabled);
        if (!c.validScales()) s.label("scale_invalid");
        s.label("scale_hint"); s.label("output_scale_hint");
        var tiers=new ArrayList<Component>(); tiers.add(tr("tier_any")); for (String name:GTValues.VN) tiers.add(Component.literal(name));
        s.label("min_tier");
        s.dropdown("min_tier",tiers.get(settings.minTier+1),tiers,settings.minTier+1,enabled&&c.validScales(),
                i->c.sendExt(i-1,settings.maxTier,settings.sort,settings.skipOwned));
        s.label("max_tier");
        s.dropdown("max_tier",tiers.get(settings.maxTier+1),tiers,settings.maxTier+1,enabled&&c.validScales(),
                i->c.sendExt(settings.minTier,i-1,settings.sort,settings.skipOwned));
        var sorts=List.of(tr("sort_id"),tr("sort_eut_asc"),tr("sort_eut_desc"),tr("sort_duration"));
        s.label("sort");
        s.dropdown("sort",sorts.get(settings.sort),sorts,settings.sort,enabled&&c.validScales(),i->c.sendExt(settings.minTier,settings.maxTier,i,settings.skipOwned));
        s.field("query","recipe_query",c.queryDraft,256,value->c.extension(2,value,now()),enabled);
        s.label("recipe_query_hint");
        s.row(choice("skip_owned",tr(settings.skipOwned?"skip_on":"skip_off"),enabled&&c.validScales(),()->c.sendExt(settings.minTier,settings.maxTier,settings.sort,!settings.skipOwned)),
                choice("reset_extensions",tr("reset_extensions"),enabled,()->{ c.scaleDraft="1"; c.outputScaleDraft="1"; c.queryDraft=""; c.sendExt(-1,-1,0,false); }));
        s.label("extensions_hint");
    }
}
