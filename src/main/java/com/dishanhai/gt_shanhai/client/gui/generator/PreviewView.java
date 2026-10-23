package com.dishanhai.gt_shanhai.client.gui.generator;

import com.dishanhai.gt_shanhai.common.item.generator.PatternGeneratorLayout;
import java.util.stream.IntStream;
import static com.dishanhai.gt_shanhai.client.gui.generator.PatternGeneratorScreen.*;
import static com.dishanhai.gt_shanhai.client.gui.generator.PatternGeneratorClientState.*;

/** 山海：選取與操作使用同一個伺服器 revision，不以本地猜測配方。 */
final class PreviewView implements GeneratorView {
    @Override public void layout(PatternGeneratorScreen s) {
        var state=s.state(); var settings=s.settings(); boolean ready=s.model.ready();
        s.label("step_preview");
        var generation=state.running()?tr("generating",state.generated(),state.pendingCount()):state.pendingCount()>0?tr("resume",state.pendingCount()):tr("generate_selected",state.selected());
        s.row(choice("generate",generation,!state.running()&&s.model.transfers()==0&&ready&&(state.selected()>0||state.pendingCount()>0),()->s.action("generate",GENERATE)),
                choice("fill",tr(s.model.transfers()>0?"fill_resume":"fill_target",state.availablePatterns()+(s.model.transfers()>0?s.model.transfers():state.selected())),
                        !state.running()&&state.pendingCount()==0&&ready&&(state.availablePatterns()>0||state.selected()>0||s.model.transfers()>0),()->s.action("fill",FILL)));
        s.button("bundle",tr("bundle",state.selected()),s.model.editable()&&ready&&state.selected()>0,()->s.action("bundle",BUNDLE));
        if (state.pendingCount()>0||s.model.transfers()>0) s.button("cancel",tr("cancel_pending"),true,()->s.model.send(CANCEL,b->{}));
        s.row(choice("previous",tr("previous_page"),ready&&state.page()>0,()->s.model.send(PAGE,b->{b.writeVarInt(state.revision());b.writeVarInt(-1);})),
                choice("select_all",tr(state.selected()==state.count()&&state.count()>0?"unselect_all":"select_all"),s.model.editable()&&ready&&state.count()>0,
                        ()->s.model.send(SELECT_ALL,b->{b.writeVarInt(state.revision());b.writeBoolean(state.selected()!=state.count());})),
                choice("next",tr("next_page"),ready&&state.page()+1<PatternGeneratorLayout.pages(state.count(),settings.previewColumns),()->s.model.send(PAGE,b->{b.writeVarInt(state.revision());b.writeVarInt(1);})));
        s.text(tr("page_status",state.count()==0?0:state.page()+1,PatternGeneratorLayout.pages(state.count(),settings.previewColumns),PatternGeneratorLayout.capacity(settings.previewColumns)));
        var columns=IntStream.rangeClosed(1,PatternGeneratorLayout.MAX_COLUMNS).mapToObj(i->tr("column_count",i)).toList();
        s.dropdown("columns",tr("column_count",settings.previewColumns),columns,settings.previewColumns-1,true,i->s.model.send(COLUMNS,b->b.writeVarInt(i+1)));
        s.recipeGrid(state.previewPage(),true);
    }
}
