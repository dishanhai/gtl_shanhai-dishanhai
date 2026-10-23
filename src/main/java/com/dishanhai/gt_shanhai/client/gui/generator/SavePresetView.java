package com.dishanhai.gt_shanhai.client.gui.generator;

import net.minecraft.network.chat.Component;
import static com.dishanhai.gt_shanhai.client.gui.generator.PatternGeneratorScreen.*;

/** 山海：匯出先取得伺服器驗證後內容，檔案路徑由客戶端固定。 */
final class SavePresetView implements GeneratorView {
    @Override public void layout(PatternGeneratorScreen s) {
        s.label("save_preset");
        s.field("preset_name","preset_name",s.presetName,48,value->s.presetName=value,s.model.editable());
        s.label("preset_save_hint"); s.text(tr("selected_total",s.state().selected(),s.state().count()));
        if (!s.presetMessage.isEmpty()) s.text(Component.literal(s.presetMessage));
        s.row(choice("save_local",tr("save_local"),s.model.editable()&&s.model.ready()&&!s.presetName.isBlank()&&s.state().selected()>0&&!s.saveRequested,s::exportPreset),
                choice("open_folder",tr("open_folder"),true,s::openDirectory));
    }
}
