package com.dishanhai.gt_shanhai.client.gui.generator;

import static com.dishanhai.gt_shanhai.client.gui.generator.PatternGeneratorScreen.*;
import static com.dishanhai.gt_shanhai.client.gui.generator.PatternGeneratorClientState.*;

/** 山海：四組七槽、譯名白名單與逐槽標籤。 */
final class FilterView implements GeneratorView {
    @Override public void layout(PatternGeneratorScreen s) {
        s.label("filters_optional");
        for (boolean input:new boolean[]{true,false}) {
            s.field(input?"input_white":"output_white",input?"input_white":"output_white",input?s.model.inputDraft:s.model.outputDraft,256,
                    value->s.model.white(input,value,now()),s.model.editable());
            s.ghostGroup(input,false); s.ghostGroup(input,true);
        }
        s.label("multi_filter_rule"); s.label("white_hint");
        s.row(choice("clear_filters",tr("clear_filters"),s.model.editable(),()->{
            s.model.inputDraft=""; s.model.outputDraft=""; s.model.whiteDirty=false; s.model.send(CLEAR,b->{});
        }),choice("matches",tr("view_matches",s.state().count()),true,()->s.show(Page.PREVIEW)));
    }
}
