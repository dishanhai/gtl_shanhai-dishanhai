package com.dishanhai.gt_shanhai.client.gui.generator;

import net.minecraft.network.chat.Component;
import java.util.List;
import static com.dishanhai.gt_shanhai.client.gui.generator.PatternGeneratorScreen.*;
import static com.dishanhai.gt_shanhai.client.gui.generator.PatternGeneratorClientState.*;

/** 山海：本地列表與伺服器配方有效性檢查各自負責原有邊界。 */
final class PresetView implements GeneratorView {
    @Override public void layout(PatternGeneratorScreen s) {
        s.label("presets"); s.label("preset_share_hint");
        s.row(choice("refresh_presets",tr("refresh_presets"),true,s::openPresets),choice("open_folder",tr("open_folder"),true,s::openDirectory));
        if (s.presetFiles.isEmpty()) s.label("preset_empty");
        for (int i=0;i<s.presetFiles.size();i++) {
            var preset=s.presetFiles.get(i);
            s.button("preset."+i,Component.literal((preset==s.chosenPreset?"✓ ":"")+preset.name()),true,()->{
                s.chosenPreset=preset; s.inspectedAvailable=0; s.inspectedMissing=0; s.inspectedMachine=""; s.inspectedMode=""; s.presetMessage=""; s.presetSamples=List.of(); s.submitPreset(INSPECT_PRESET);
            });
        }
        if (s.chosenPreset!=null) {
            s.text(Component.literal(s.chosenPreset.name())); s.text(tr("preset_author",s.chosenPreset.author()));
            s.text(Component.literal(s.chosenPreset.savedAt())); s.text(Component.literal(s.inspectedMachine)); s.text(Component.literal(s.inspectedMode));
            s.text(tr("preset_available",s.inspectedAvailable,s.inspectedMissing));
            s.button("apply_preset",tr("apply_preview"),s.model.editable()&&s.inspectedAvailable>0,()->s.submitPreset(APPLY_PRESET));
            s.recipeGrid(s.presetSamples,false);
        }
        if (!s.presetMessage.isEmpty()) s.text(Component.literal(s.presetMessage));
    }
}
