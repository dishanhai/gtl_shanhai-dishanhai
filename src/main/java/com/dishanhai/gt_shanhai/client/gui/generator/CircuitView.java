package com.dishanhai.gt_shanhai.client.gui.generator;

import java.util.ArrayList;
import net.minecraft.network.chat.Component;
import static com.dishanhai.gt_shanhai.client.gui.generator.PatternGeneratorScreen.*;
import static com.dishanhai.gt_shanhai.client.gui.generator.PatternGeneratorClientState.*;

/** 山海：電路選擇以同一個玻璃彈層呈現，保留任意／無電路／1–32。 */
final class CircuitView implements GeneratorView {
    @Override public int depth() { return 2; }
    @Override public void layout(PatternGeneratorScreen s) {
        s.label("choose_circuit");
        var options=new ArrayList<Component>(); options.add(tr("circuit_any")); options.add(tr("circuit_none"));
        for (int i=1;i<=32;i++) options.add(tr("circuit_value",i));
        int value=s.settings().circuit;
        s.dropdown("choose_circuit",value<0?tr("circuit_any"):value==0?tr("circuit_none"):tr("circuit_value",value),options,value+1,s.model.editable(),i->{
            s.model.send(CIRCUIT,b->b.writeVarInt(i-1)); s.show(Page.MAIN);
        });
        for (int row=0;row<4;row++) {
            var choices=new Choice[8];
            for (int col=0;col<8;col++) { int circuit=row*8+col+1; choices[col]=choice("circuit."+circuit,Component.literal((circuit==value?"✓ ":"")+circuit),s.model.editable(),()->{
                s.model.send(CIRCUIT,b->b.writeVarInt(circuit)); s.show(Page.MAIN);
            }); }
            s.row(choices);
        }
        s.label("circuit_hint");
    }
}
