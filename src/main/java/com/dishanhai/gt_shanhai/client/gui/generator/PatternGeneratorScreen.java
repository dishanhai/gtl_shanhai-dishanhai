package com.dishanhai.gt_shanhai.client.gui.generator;

import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import com.dishanhai.gt_shanhai.GTDishanhaiMod;
import com.dishanhai.gt_shanhai.client.gui.glass.*;
import com.dishanhai.gt_shanhai.client.gui.scaled.*;
import com.dishanhai.gt_shanhai.client.gui.shop.*;
import com.dishanhai.gt_shanhai.client.shop.ClientShopUiSettings;
import com.dishanhai.gt_shanhai.common.item.generator.*;
import com.dishanhai.gt_shanhai.common.recipe.editor.ShanhaiRecipeEditorAnimation;
import io.netty.buffer.Unpooled;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.nbt.TagParser;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.InteractionHand;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import static com.dishanhai.gt_shanhai.client.gui.generator.PatternGeneratorClientState.*;

/** 山海：手持樣板生成器。七頁共用可換行幾何、穩定命中區與商店動效。 */
public final class PatternGeneratorScreen extends ScaledScreen {
    public enum Page { MAIN, PREVIEW, FILTER, CIRCUIT, EXTENSION, PRESET, SAVE_PRESET }
    public static final String BUNDLE_BUTTON_ID="shanhai_pattern_generator.bundle";
    private static final String PREFIX="gt_shanhai.gui.pattern_generator.";
    private static final int PANEL_W=460, PANEL_H=300, CONTENT_W=436;
    // 畫布比面板大一圈：面板約佔視窗 2/3，整體縮放隨之變小。
    private static final int TARGET_W=700, TARGET_H=460;
    final PatternGeneratorClientState model;
    private final InteractionHand hand;
    private final EnumMap<Page,GeneratorView> views=new EnumMap<>(Page.class);
    private Page page=Page.MAIN;
    private final List<Element> elements=new ArrayList<>();
    private final Map<String,EditBox> fields=new HashMap<>();
    private Map<String,EditBox> reusableFields=Map.of();
    private final List<GeneratorGhostSlot> ghosts=new ArrayList<>();
    private final Spring opening=new Spring(Spring.PANEL);
    private final Spring scroll=new Spring(Spring.SCROLL);
    private final Spring tooltipOpening=new Spring(Spring.PANEL);
    private final SpringTable<String> hover=new SpringTable<>(Spring.HOVER);
    private final SpringTable<String> selection=new SpringTable<>(Spring.HOVER);
    private final SpringTable<String> stagger=new SpringTable<>(Spring.STAGGER);
    private final ButtonFeedback feedback=new ButtonFeedback();
    private final UiSoundThrottle sounds=new UiSoundThrottle();
    private final GlassPanelTexture root=GlassPanelTexture.root(), display=GlassPanelTexture.display();
    private final GlassPanelTexture pageTexture=GlassPanelTexture.page();
    private final ShanhaiRecipeEditorAnimation transition=new ShanhaiRecipeEditorAnimation();
    private List<Element> leaving=List.of();
    private int leavingScroll;
    private int direction=1,stage;
    private long lastFrame;
    private float velocity;
    private int left,top,cursor,contentHeight,scrollTarget;
    private boolean layoutRequested,warmed,suspended,closed;
    private GeneratorDropdown dropdown;
    private List<Component> tooltip=List.of();
    private String tooltipKey="";
    private long pendingAction;
    private int pendingRequest;
    private int pendingKind;
    private String lastGrid="";
    private int hoveredX,hoveredY;
    final int[] selectedFilterSlots=new int[4];
    String presetName="",presetMessage="",inspectedMachine="",inspectedMode="";
    int inspectedAvailable,inspectedMissing,presetRequest;
    boolean saveRequested;
    List<PatternGeneratorPresetFiles.Preset> presetFiles=List.of();
    PatternGeneratorPresetFiles.Preset chosenPreset;
    List<PatternGeneratorPreview> presetSamples=List.of();

    private static final class Element {
        String key; int x,y,w,h; Component text; boolean enabled; Runnable click;
        GeneratorGhostSlot ghost; PatternGeneratorPreview recipe; EditBox field; int index; boolean selectable;
        Element(String key,int x,int y,int w,int h,Component text,boolean enabled,Runnable click) {
            this.key=key; this.x=x; this.y=y; this.w=w; this.h=h; this.text=text; this.enabled=enabled; this.click=click;
        }
    }
    record Choice(String key,Component text,boolean enabled,Runnable action) {}
    public record GhostTarget(Rect2i area,Consumer<GenericStack> accept) {}

    public PatternGeneratorScreen(InteractionHand hand,PatternGeneratorState state) {
        super(tr("title")); this.hand=hand; model=new PatternGeneratorClientState(state);
        targetWidth=TARGET_W; targetHeight=TARGET_H; renderRenderablesOnly=true;
        views.put(Page.MAIN,new MainView()); views.put(Page.PREVIEW,new PreviewView());
        views.put(Page.FILTER,new FilterView()); views.put(Page.CIRCUIT,new CircuitView());
        views.put(Page.EXTENSION,new ExtensionView()); views.put(Page.PRESET,new PresetView());
        views.put(Page.SAVE_PRESET,new SavePresetView());
        opening.setTarget(1,now()); transition.reset(0,now());
    }
    UUID sessionId() { return model.snapshot.sessionId(); }
    static long now() { return ShanhaiRecipeEditorAnimation.nowMs(); }
    static Component tr(String key,Object... args) { return Component.translatable(PREFIX+key,args); }
    static Choice choice(String key,Component text,boolean enabled,Runnable action) { return new Choice(key,text,enabled,action); }
    PatternGeneratorSettings settings() { return model.settings; }
    PatternGeneratorState state() { return model.snapshot; }
    int innerWidth() { return CONTENT_W; }
    public Rect2i panelBounds() { return new Rect2i(Math.round(left*guiScale)+offsetX,Math.round(top*guiScale)+offsetY,Math.round(PANEL_W*guiScale),Math.round(PANEL_H*guiScale)); }
    private int viewportHeight() { return PANEL_H-90; }
    private int contentX() { return left+12; }
    private int contentY() { return top+42; }
    boolean reduced() { return ClientShopUiSettings.reduceMotion(); }
    private float spring(Spring value,float target,long at) {
        if (reduced()) value.snap(target); else { value.setTarget(target,at); value.update(at); }
        return value.value();
    }
    private float follow(SpringTable<String> table,String key,float target,long at) {
        if (reduced()) { table.get(key,at).snap(target); return target; }
        return table.follow(key,target,at);
    }

    @Override protected void initScaled() {
        suspended=false; left=(vWidth-PANEL_W)/2; top=(vHeight-PANEL_H)/2;
        rebuildLayout();
        restartGridIfChanged();
    }
    private void rebuildLayout() {
        String focus="";
        for (var entry:fields.entrySet()) if (entry.getValue().isFocused()) {
            focus=entry.getKey();
        }
        reusableFields=new HashMap<>(fields);
        setFocused(null);
        clearWidgets(); elements.clear(); fields.clear(); ghosts.clear(); cursor=0;
        if (!state().feedback().getString().isBlank()) text(state().feedback());
        views.get(page).layout(this); contentHeight=cursor;
        scrollTarget=Math.min(scrollTarget,Math.max(0,contentHeight-viewportHeight()));
        if (scroll.value()>Math.max(0,contentHeight-viewportHeight())) scroll.snap(scrollTarget);
        EditBox focused=fields.get(focus);
        if (focused!=null && focused.active) { setFocused(focused); focused.setFocused(true); }
        reusableFields=Map.of();
        layoutRequested=false;
    }
    void show(Page next) {
        if (next==page) return;
        model.flush(); dropdown=null; feedback.cancel(now());
        leaving=List.copyOf(elements); leavingScroll=Math.round(scroll.value());
        direction=views.get(next).depth()>=views.get(page).depth()?1:-1;
        if (next==Page.MAIN) direction=-1;
        page=next; scrollTarget=0; scroll.snap(0);
        transition.advance(++stage,now());
        if (reduced()) { transition.reset(stage,now()); leaving=List.of(); }
        rebuildLayout();
    }
    void text(Component value) {
        int h=Math.max(font.lineHeight,font.split(value,CONTENT_W).size()*font.lineHeight);
        elements.add(new Element("text."+elements.size(),0,cursor,CONTENT_W,h,value,false,null)); cursor+=h+6;
    }
    void label(String key) { text(tr(key)); }
    void gap(int height) { cursor+=height; }
    void button(String key,Component title,boolean enabled,Runnable action) { row(choice(key,title,enabled,action)); }
    void row(Choice... choices) {
        int w=(CONTENT_W-(choices.length-1)*6)/choices.length,h=22;
        for (Choice value:choices) h=Math.max(h,font.split(value.text(),Math.max(1,w-12)).size()*font.lineHeight+10);
        for (int i=0;i<choices.length;i++) {
            Choice value=choices[i]; elements.add(new Element(value.key(),i*(w+6),cursor,w,h,value.text(),value.enabled(),value.action()));
        }
        cursor+=h+6;
    }
    void field(String key,String label,String value,int maximum,Consumer<String> changed,boolean enabled) {
        label(label);
        EditBox box=reusableFields.get(key);
        if (box==null) box=new AnimatableEditBox(font,contentX(),contentY()+cursor,CONTENT_W,20,tr(label));
        box.setResponder(text->{});
        box.setMaxLength(maximum);
        if (!box.getValue().equals(value)) box.setValue(value);
        box.active=enabled; box.setTextColor(GlassPalette.TEXT);
        box.setResponder(text->{ changed.accept(text); layoutRequested=true; });
        fields.put(key,box); addWidget(box);
        var element=new Element(key,0,cursor,CONTENT_W,20,Component.empty(),enabled,null); element.field=box; elements.add(element); cursor+=26;
        // 輸入框保留游標捲動，完整草稿另以相同欄寬換行展示。
        if (font.width(value)>CONTENT_W-12) text(Component.literal(value));
    }
    void machineSlot() {
        var slot=new GeneratorGhostSlot("machine",0,cursor,true,
                ()->settings().machine.isEmpty()?null:new GenericStack(AEItemKey.of(settings().machine),1),
                stack->model.send(SET_SLOT,b->{ b.writeBoolean(true); b.writeNbt(stack==null?null:GenericStack.writeTag(stack)); }),()->{},()->!settings().machine.isEmpty());
        addGhost(slot); cursor+=26;
        text(settings().machine.isEmpty()?tr("machine_empty"):settings().machine.getHoverName());
    }
    void ghostGroup(boolean input,boolean exclude) {
        int group=(input?0:2)+(exclude?1:0);
        label((input?"input_":"output_")+(exclude?"black":"include"));
        for (int i=0;i<PatternGeneratorSettings.FILTER_SLOTS;i++) {
            int slot=i;
            addGhost(new GeneratorGhostSlot("filter."+group+"."+i,i*24,cursor,false,
                    ()->settings().filterSlots(input,exclude)[slot],stack->model.send(SET_SLOT,b->{
                        b.writeBoolean(false); b.writeBoolean(input); b.writeBoolean(exclude); b.writeVarInt(slot);
                        b.writeNbt(stack==null?null:GenericStack.writeTag(stack));
                    }),()->{ selectedFilterSlots[group]=slot; layoutRequested=true; },()->selectedFilterSlots[group]==slot));
        }
        cursor+=26;
        int index=selectedFilterSlots[group];
        var tags=PatternGeneratorSettings.tags(settings().filterSlots(input,exclude)[index]);
        String selected=settings().filterTags(input,exclude)[index];
        var options=new ArrayList<Component>(); options.add(tr("include_exact")); tags.forEach(tag->options.add(Component.literal("#"+tag)));
        dropdown("tag."+group,tr("filter_slot_label",index+1,selected.isEmpty()?tr("include_exact").getString():"#"+selected),options,
                selected.isEmpty()?0:tags.indexOf(selected)+1,model.editable(),choice->model.send(INCLUDE_TAG,b->{
                    b.writeBoolean(input); b.writeBoolean(exclude); b.writeVarInt(index); b.writeUtf(choice==0?"":tags.get(choice-1),256);
                }));
    }
    private void addGhost(GeneratorGhostSlot slot) {
        ghosts.add(slot); var element=new Element(slot.key,slot.x,slot.y,20,20,Component.empty(),model.editable(),null);
        element.ghost=slot; elements.add(element);
    }
    void dropdown(String key,Component title,List<Component> options,int selected,boolean enabled,IntConsumer choose) {
        button(key,title.copy().append(" ▾"),enabled,()-> {
            setFocused(null);
            int x=contentX(),y=Math.max(top+42,Math.min(hoveredY+24,top+PANEL_H-190));
            GuiRect anchor=new GuiRect(hoveredX,hoveredY,CONTENT_W,22);
            dropdown=new GeneratorDropdown(font,x,y,CONTENT_W,Math.max(36,top+PANEL_H-10-y),anchor,options,selected,choose,now());
        });
    }
    void recipeGrid(List<PatternGeneratorPreview> values,boolean selectable) {
        if (values.isEmpty()) { text(tr(model.ready()?"no_matches":"filtering")); return; }
        int columns=selectable?settings().previewColumns:Math.min(3,Math.max(1,(values.size()+PatternGeneratorLayout.ROWS-1)/PatternGeneratorLayout.ROWS));
        int w=(CONTENT_W-(columns-1)*4)/columns;
        int rowCount=selectable?PatternGeneratorLayout.ROWS:(values.size()+columns-1)/columns;
        int[] heights=new int[rowCount]; Arrays.fill(heights,24);
        for (int i=0;i<values.size();i++) {
            int row=selectable?PatternGeneratorLayout.row(i):i/columns; Component name=recipeName(values.get(i));
            heights[row]=Math.max(heights[row],font.split(name,recipeNameWidth(w)).size()*font.lineHeight+10);
        }
        int[] starts=new int[heights.length]; int total=0;
        for (int r=0;r<heights.length;r++) { starts[r]=total; total+=heights[r]+4; }
        for (int i=0;i<values.size();i++) {
            var value=values.get(i); int row=selectable?PatternGeneratorLayout.row(i):i/columns,column=selectable?PatternGeneratorLayout.column(i):i%columns;
            if (column>=columns) break;
            var element=new Element("recipe."+value.id(),column*(w+4),cursor+starts[row],w,heights[row],recipeName(value),selectable&&model.editable()&&model.ready(),
                    ()->model.send(SELECT,b->{ b.writeVarInt(state().revision()); b.writeUtf(value.id(),256); }));
            element.recipe=value; element.index=i; element.selectable=selectable; elements.add(element);
        }
        int rows=selectable?Math.min(PatternGeneratorLayout.ROWS,values.size()):rowCount; cursor+=starts[rows-1]+heights[rows-1]+6;
    }
    private Component recipeName(PatternGeneratorPreview value) { return value.outputs().isEmpty()?Component.literal(value.id()):value.outputs().get(0).what().getDisplayName(); }
    private static int recipeNameWidth(int cellWidth) { return Math.max(1,cellWidth-29); }
    private List<Component> details(PatternGeneratorPreview value) {
        var lines=new ArrayList<Component>();
        lines.add(tr("preview_info",value.tier(),value.eut(),value.circuit()==0?tr("circuit_none").getString():tr("circuit_value",value.circuit()).getString(),value.duration()));
        if (settings().scale!=1 || settings().outputScale!=1) lines.add(tr("lore_scale",settings().scale,PatternGeneratorScale.outputFactor(settings().scale,settings().outputScale)));
        if (value.overflow()) lines.add(tr("scale_overflow"));
        lines.add(tr("preview_inputs")); for (var stack:value.inputs()) lines.add(stack.what().getDisplayName().copy().append(" × "+stack.amount()));
        lines.add(tr("preview_outputs")); for (var stack:value.outputs()) lines.add(stack.what().getDisplayName().copy().append(" × "+stack.amount()));
        lines.add(Component.literal(value.id())); return lines;
    }

    @Override public void tick() {
        super.tick(); if (closed) return;
        if (!warmed) { warmed=true; if (!reduced()) GlassBlur.warmUp(); }
        model.tick(now()); fields.values().forEach(EditBox::tick);
        if (layoutRequested) rebuildLayout();
    }
    @Override protected void renderScaledBackground(GuiGraphics g,int mx,int my,float pt) {
        long at=now(); ShopGaussBlur.clear(); ShopGaussBlur.clearTopLayer(); tooltip=List.of();
        float open=spring(opening,1,at);
        float previous=scroll.value(); spring(scroll,scrollTarget,at);
        velocity=lastFrame==0 || at<=lastFrame?0:(scroll.value()-previous)*1000f/(at-lastFrame); lastFrame=at;
        feedback.pointer(mx,my,at); feedback.tick(at); hover.sweep(at); selection.sweep(at);
        transition.advance(stage,at);
        if (reduced()) { transition.reset(stage,at); leaving=List.of(); }
        GuiRenderUtil.withPanelMotion(g,left+PANEL_W/2f,top+PANEL_H/2f,open,()-> {
            root.draw(g,mx,my,left,top,PANEL_W,PANEL_H);
            wrap(g,tr("title"),left+12,top+10,PANEL_W-66,GlassPalette.TEXT_HEAD);
            drawButton(g,"close",left+PANEL_W-40,top+8,28,22,Component.literal("×"),true,mx,my);
            drawButton(g,"back",left+12,top+PANEL_H-30,90,22,tr("back_main"),page!=Page.MAIN,mx,my);
            wrap(g,tr("selected_total",state().selected(),state().count()),left+110,top+PANEL_H-31,PANEL_W-122,GlassPalette.TEXT_MUTED);
            display.draw(g,mx,my,contentX()-3,contentY()-3,CONTENT_W+6,viewportHeight()+6);
            if (contentHeight>viewportHeight()) {
                int thumb=Math.max(14,viewportHeight()*viewportHeight()/contentHeight);
                int thumbY=contentY()+Math.round((viewportHeight()-thumb)*scroll.value()/Math.max(1,contentHeight-viewportHeight()));
                g.fill(contentX()+CONTENT_W+2,contentY(),contentX()+CONTENT_W+4,contentY()+viewportHeight(),GlassPalette.DISPLAY_EDGE);
                g.fill(contentX()+CONTENT_W+2,thumbY,contentX()+CONTENT_W+4,thumbY+thumb,GlassPalette.ACCENT);
            }
            scissor(g,contentX(),contentY(),CONTENT_W,viewportHeight());
            try {
                if (!reduced() && transition.moving(at) && !leaving.isEmpty()) {
                    float p=transition.progress(at);
                    renderPage(g,mx,my,pt,leaving,leavingScroll,-direction*p*CONTENT_W,false);
                } else leaving=List.of();
                renderPage(g,mx,my,pt,elements,Math.round(scroll.value()),slideOffset(),true);
            } finally { g.disableScissor(); }
            if (!reduced()) {
                GuiRect viewport=new GuiRect(contentX(),contentY(),CONTENT_W,viewportHeight());
                if (transition.moving(at)) GlassBlur.requestPage(g,viewport,GlassMotion.pageSlide(transition.linear(at)));
                else if (page==Page.PREVIEW && PatternGeneratorMotion.scrollBlur(velocity)>0.01f)
                    GlassBlur.requestPage(g,viewport,new GlassMotion.Sample(PatternGeneratorMotion.scrollBlur(velocity),8,0,1));
            }
        });
    }
    private float slideOffset() { return reduced()?0:direction*(1-transition.progress(now()))*CONTENT_W; }
    private void renderPage(GuiGraphics g,int mx,int my,float pt,List<Element> rows,int scrollY,float slide,boolean active) {
        g.pose().pushPose();
        try {
            g.pose().translate(slide,0,0);
            if (active) views.get(page).render(this,g,mx,my,pt);
            else drawElements(g,mx,my,pt,rows,scrollY,false);
        } finally { g.pose().popPose(); }
    }
    void renderElements(GuiGraphics g,int mx,int my,float pt) { drawElements(g,mx,my,pt,elements,Math.round(scroll.value()),dropdown==null); }
    private void drawElements(GuiGraphics g,int mx,int my,float pt,List<Element> rows,int scrollY,boolean interactive) {
        for (Element e:rows) {
            int x=contentX()+e.x,y=contentY()+e.y-scrollY;
            if (y+e.h<contentY() || y>contentY()+viewportHeight()) continue;
            boolean over=interactive && hit(mx,my,x,y,e.w,e.h) && hit(mx,my,contentX(),contentY(),CONTENT_W,viewportHeight());
            if (e.field!=null) { e.field.setX(x); e.field.setY(y); e.field.render(g,mx,my,pt); continue; }
            if (e.ghost!=null) {
                g.pose().pushPose(); g.pose().translate(contentX(),contentY()-scrollY,0);
                float selected=follow(selection,e.key,e.ghost.isSelected()?1:0,now());
                e.ghost.draw(g,over,selected); g.pose().popPose();
                if (over) {
                    var lines=new ArrayList<Component>(); var stack=e.ghost.value();
                    if (stack!=null) {
                        lines.add(stack.what().getDisplayName());
                        ItemStackDetails.append(lines,stack);
                        PatternGeneratorSettings.tags(stack).forEach(tag->lines.add(Component.literal("#"+tag)));
                    }
                    lines.add(tr(e.key.equals("machine")?"machine_hint":"multi_filter_hint")); setTooltip(e.key,lines);
                }
            } else if (e.recipe!=null) {
                float appear=reduced()?1:stagger.valueOr(e.key,1,now());
                GuiRenderUtil.withPanelMotion(g,x+e.w/2f,y+e.h/2f,appear,()-> {
                    g.pose().pushPose(); g.pose().translate(0,(1-appear)*8,0);
                    drawButton(g,e.key,x,y,e.w,e.h,Component.empty(),e.enabled,mx,my);
                    float checked=follow(selection,e.key,e.recipe.checked()?1:0,now());
                    GlassCell.draw(g,x+3,y+3,20,GlassPalette.ROW_EDGE,checked,over);
                    var icon=GeneratorGhostSlot.display(e.recipe.outputs().isEmpty()?null:e.recipe.outputs().get(0));
                    if (!icon.isEmpty()) g.renderItem(icon,x+5,y+5);
                    wrap(g,e.text,x+27,y+5,recipeNameWidth(e.w),GlassPalette.TEXT); g.pose().popPose();
                });
                if (over) setTooltip(e.key,details(e.recipe));
            } else if (e.click!=null) drawButton(g,e.key,x,y,e.w,e.h,e.text,e.enabled,mx,my);
            else wrap(g,e.text,x,y,e.w,GlassPalette.TEXT_MUTED);
        }
    }
    void drawButton(GuiGraphics g,String key,int x,int y,int w,int h,Component text,boolean enabled,int mx,int my) {
        long at=now(); float hot=follow(hover,key,hit(mx,my,x,y,w,h)?1:0,at);
        long id=key.hashCode(); var visual=feedback.visual(id,id,enabled,at,reduced());
        GuiRenderUtil.withButtonMotion(g,x,y,w,h,visual.scale(),visual.shakeX(),()-> {
            pageTexture.draw(g,mx,my,x,y,w,h);
            g.fill(x+1,y+1,x+w-1,y+h-1,ButtonFeedback.shade(GuiMotion.lerpColor(GlassPalette.BUTTON_FILL,GlassPalette.BUTTON_FILL_HOVER,hot),visual));
            g.renderOutline(x,y,w,h,ButtonFeedback.tint(GuiMotion.lerpColor(GlassPalette.BUTTON_EDGE,GlassPalette.ACCENT,hot),visual));
            wrap(g,text,x+6,y+5,w-12,enabled?GlassPalette.TEXT:GlassPalette.TEXT_OFF);
            if (visual.pending()) GuiRenderUtil.drawPendingSweep(g,x,y,w,h,visual.pendingPhase(),GlassPalette.ACCENT);
        });
    }
    void wrap(GuiGraphics g,Component text,int x,int y,int width,int color) {
        for (var line:font.split(text,Math.max(1,width))) { g.drawString(font,line,x,y,color,false); y+=font.lineHeight; }
    }
    void scissor(GuiGraphics g,int x,int y,int w,int h) {
        var matrix=g.pose().last().pose();
        g.enableScissor((int)Math.floor(x*matrix.m00()+matrix.m30()),(int)Math.floor(y*matrix.m11()+matrix.m31()),
                (int)Math.ceil((x+w)*matrix.m00()+matrix.m30()),(int)Math.ceil((y+h)*matrix.m11()+matrix.m31()));
    }
    private static boolean hit(double mx,double my,int x,int y,int w,int h) { return mx>=x && mx<x+w && my>=y && my<y+h; }
    private void setTooltip(String key,List<Component> value) {
        if (!key.equals(tooltipKey)) { tooltipKey=key; tooltipOpening.snap(0); tooltipOpening.setTarget(1,now()); }
        tooltip=value;
    }
    @Override protected void renderTooltips(GuiGraphics g,int smx,int smy,int mx,int my) {
        if (dropdown!=null && !reduced()) {
            g.pose().pushPose(); g.pose().translate(offsetX,offsetY,0); g.pose().scale(guiScale,guiScale,1);
            GlassBlur.requestPopup(g,dropdown.hole(),Math.min(1,dropdown.opening.value())); g.pose().popPose();
        }
        g.flush();
        ShopGaussBlur.deferTopLayer(this,topGraphics -> {
            if (dropdown!=null) {
                topGraphics.pose().pushPose();
                try {
                    topGraphics.pose().translate(offsetX,offsetY,500); topGraphics.pose().scale(guiScale,guiScale,1);
                    topGraphics.fill(left,top+36,left+PANEL_W,top+PANEL_H-36,0x99101928);
                    dropdown.render(this,topGraphics,smx,smy,now(),reduced());
                } finally { topGraphics.pose().popPose(); }
            } else if (!tooltip.isEmpty()) drawTooltip(topGraphics,mx,my);
            else { tooltipKey=""; tooltipOpening.snap(0); }
        });
    }
    private void drawTooltip(GuiGraphics g,int mx,int my) {
        var lines=new ArrayList<FormattedCharSequence>();
        int maxWidth=Math.max(20,Math.min(360,width-20));
        for (Component line:tooltip) lines.addAll(font.split(line,maxWidth));
        int longest=lines.stream().mapToInt(font::width).max().orElse(20),fullHeight=lines.size()*font.lineHeight+10;
        float fit=Math.min(1f,(height-12f)/Math.max(1,fullHeight));
        int x=Math.max(4,Math.min(mx+12,width-Math.round((longest+12)*fit)-4));
        int y=Math.max(4,Math.min(my+12,height-Math.round(fullHeight*fit)-4));
        float open=spring(tooltipOpening,1,now());
        g.pose().pushPose();
        try {
            g.pose().translate(x,y,600); g.pose().scale(fit,fit,1);
            GuiRenderUtil.withPanelMotion(g,(longest+12)/2f,fullHeight/2f,open,()-> {
                g.fill(0,0,longest+12,fullHeight,GlassPalette.PANEL_FILL); g.renderOutline(0,0,longest+12,fullHeight,GlassPalette.ACCENT);
                int lineY=5; for (var line:lines) { g.drawString(font,line,6,lineY,GlassPalette.TEXT,false); lineY+=font.lineHeight; }
            });
        } finally { g.pose().popPose(); }
    }
    @Override protected boolean universalMouseClicked(double mx,double my,int button) {
        if (dropdown!=null) { var popup=dropdown; dropdown=null; if (button==0) popup.click(mx,my); layoutRequested=true; return true; }
        if (hit(mx,my,left+PANEL_W-40,top+8,28,22)) { onClose(); return true; }
        if (page!=Page.MAIN && hit(mx,my,left+12,top+PANEL_H-30,90,22)) { show(Page.MAIN); return true; }
        return views.get(page).mouseClicked(this,mx,my,button);
    }
    boolean clickElements(double mx,double my,int button) {
        if (!hit(mx,my,contentX(),contentY(),CONTENT_W,viewportHeight())) return false;
        int scrollY=Math.round(scroll.value());
        for (Element e:elements) {
            int x=contentX()+e.x,y=contentY()+e.y-scrollY;
            if (!hit(mx,my,x,y,e.w,e.h)) continue;
            hoveredX=x; hoveredY=y;
            if (e.field!=null) {
                fields.values().forEach(field->field.setFocused(false));
                e.field.setX(x); e.field.setY(y); setFocused(e.field); return e.field.mouseClicked(mx,my,button);
            }
            if (!e.enabled) { feedback.nudge(e.key.hashCode(),now()); ShopUiSounds.limit(); return true; }
            if (e.ghost!=null) { if (button==1) e.ghost.accept(null); else if (button==0) e.ghost.open(this); return true; }
            if (button==0 && e.click!=null) {
                feedback.press(e.key.hashCode(),x,y,e.w,e.h,true,now());
                if (sounds.allow("click",40,now())) ShopUiSounds.click(); e.click.run(); return true;
            }
        }
        return false;
    }
    @Override protected boolean universalMouseReleased(double mx,double my,int button) { feedback.release(now()); return super.universalMouseReleased(mx,my,button); }
    @Override protected boolean universalMouseScrolled(double mx,double my,double amount) {
        if (dropdown!=null) { dropdown.scroll(amount); return true; }
        if (hit(mx,my,contentX(),contentY(),CONTENT_W,viewportHeight())) return views.get(page).mouseScrolled(this,amount);
        return false;
    }
    boolean scrollContent(double amount) { scrollTarget=Math.max(0,Math.min(Math.max(0,contentHeight-viewportHeight()),scrollTarget-(int)(amount*36))); return true; }
    @Override public boolean keyPressed(int key,int scan,int mods) {
        if (dropdown!=null) {
            if (key==256) dropdown=null;
            else if (key==265) dropdown.moveSelection(-1);
            else if (key==264) dropdown.moveSelection(1);
            else if (key==257 || key==335) { var popup=dropdown; dropdown=null; popup.confirm(); layoutRequested=true; }
            return true;
        }
        if (key==256) {
            if (getFocused() instanceof EditBox edit && edit.isFocused()) { edit.setFocused(false); setFocused(null); return true; }
            if (page!=Page.MAIN) { show(Page.MAIN); return true; }
        }
        return views.get(page).keyPressed(this,key,scan,mods)||super.keyPressed(key,scan,mods);
    }
    @Override public boolean charTyped(char character,int modifiers) { return dropdown!=null || super.charTyped(character,modifiers); }
    public List<GhostTarget> ghostTargets(GenericStack ingredient) {
        if (closed || suspended || dropdown!=null || !model.editable()) return List.of();
        var result=new ArrayList<GhostTarget>();
        for (GeneratorGhostSlot ghost:ghosts) {
            int y=contentY()+ghost.y-Math.round(scroll.value());
            if (!ghost.accepts(ingredient) || y<contentY() || y+20>contentY()+viewportHeight()) continue;
            int x0=Math.round((contentX()+ghost.x)*guiScale)+offsetX,y0=Math.round(y*guiScale)+offsetY;
            result.add(new GhostTarget(new Rect2i(x0,y0,Math.max(1,Math.round(20*guiScale)),Math.max(1,Math.round(20*guiScale))),ghost::accept));
        }
        return result;
    }
    void openPicker(Screen picker) { suspended=true; model.flush(); Minecraft.getInstance().setScreen(picker); }
    void action(String key,int action) {
        if (model.flush() || !model.ready()) { feedback.nudge(key.hashCode(),now()); ShopUiSounds.busy(); return; }
        long id=key.hashCode(); if (!feedback.tryBegin(id,now())) { ShopUiSounds.busy(); return; }
        pendingAction=id; pendingKind=action; pendingRequest=model.revision(action);
        feedback.pending(id,pendingRequest,now(),120000);
    }
    void receive(PatternGeneratorState value) {
        if (!sessionId().equals(value.sessionId())) return;
        model.accept(value); layoutRequested=true;
        restartGridIfChanged();
        if (pendingRequest!=0 && pendingKind!=EXPORT_PRESET && !value.running()
                && value.acknowledgedRequest()>=pendingRequest) {
            var tone=tone(value.feedback());
            feedback.resolve(pendingRequest,tone,now()); pendingRequest=0;
            if (tone==ButtonFeedback.Tone.FAILED) ShopUiSounds.fail();
            else if (tone==ButtonFeedback.Tone.PARTIAL) ShopUiSounds.partial();
            else if (tone==ButtonFeedback.Tone.SUCCESS) ShopUiSounds.success(false);
            else ShopUiSounds.busy();
        }
    }
    private void restartGridIfChanged() {
        String grid=state().page()+":"+settings().previewColumns+":"+state().previewPage().stream().map(PatternGeneratorPreview::id).toList();
        if (!grid.equals(lastGrid)) {
            lastGrid=grid; stagger.clear(); scrollTarget=0; scroll.snap(0);
            for (int i=0;i<state().previewPage().size();i++) stagger.start("recipe."+state().previewPage().get(i).id(),reduced()?1:0,1,now(),PatternGeneratorMotion.staggerDelay(i));
        }
    }
    private static ButtonFeedback.Tone tone(Component value) {
        if (!(value.getContents() instanceof TranslatableContents text)) return ButtonFeedback.Tone.NEUTRAL;
        String key=text.getKey();
        if (key.endsWith("cancelled")) return ButtonFeedback.Tone.NEUTRAL;
        if (key.endsWith("result_failed") && text.getArgs().length>0 && text.getArgs()[0] instanceof Number generated && generated.intValue()==0)
            return ButtonFeedback.Tone.FAILED;
        if (key.endsWith("result_skipped") || key.endsWith("result_failed")) return ButtonFeedback.Tone.PARTIAL;
        return key.endsWith(".result") || key.endsWith("bundle_result") || key.endsWith("preset_saved")
                ?ButtonFeedback.Tone.SUCCESS:ButtonFeedback.Tone.FAILED;
    }
    void openPresets() {
        chosenPreset=null; presetSamples=List.of(); inspectedAvailable=0; inspectedMissing=0; inspectedMachine=""; inspectedMode=""; presetMessage=""; presetRequest++;
        try { var listing=PatternGeneratorClientHooks.get().list(); presetFiles=listing.presets(); if (listing.unreadable()>0) presetMessage=tr("preset_invalid",listing.unreadable()).getString(); }
        catch (Exception ex) { presetError(ex); }
        show(Page.PRESET); layoutRequested=true;
    }
    void openDirectory() { try { PatternGeneratorClientHooks.get().openDirectory(); } catch (Exception ex) { presetError(ex); } }
    private void presetError(Exception error) { presetMessage=tr("preset_error").getString(); layoutRequested=true; GTDishanhaiMod.LOGGER.warn("Pattern generator preset operation failed",error); }
    void submitPreset(int action) {
        if (chosenPreset==null) return;
        layoutRequested=true;
        try {
            var config=TagParser.parseTag(chosenPreset.configuration()); int request=++presetRequest;
            model.send(action,b->{ b.writeVarInt(request); b.writeNbt(config); PatternGeneratorCodec.writeIds(b,chosenPreset.recipes()); });
            if (action==APPLY_PRESET) { model.whiteDirty=false; model.extDirty=false; show(Page.PREVIEW); }
        } catch (Exception ex) { presetError(ex); }
    }
    void exportPreset() {
        if (model.flush()) return;
        String key="save_local"; if (!feedback.tryBegin(key.hashCode(),now())) return;
        saveRequested=true; pendingKind=EXPORT_PRESET; pendingAction=key.hashCode();
        pendingRequest=model.send(EXPORT_PRESET,b->{ b.writeVarInt(state().revision()); b.writeUtf(presetName.strip(),48); });
        feedback.pending(pendingAction,pendingRequest,now()); layoutRequested=true;
    }
    void receivePreset(int kind,byte[] payload) {
        var b=new FriendlyByteBuf(Unpooled.wrappedBuffer(payload));
        try {
            if (kind==120) {
                boolean requested=saveRequested; saveRequested=false;
                if (!b.readBoolean()) { if (requested) presetMessage=tr("preset_save_rejected").getString(); resolveSave(false); return; }
                String name=b.readUtf(48),author=b.readUtf(64),uuid=b.readUtf(64),date=b.readUtf(64);
                var config=b.readNbt(); var ids=PatternGeneratorCodec.readIds(b);
                if (!requested || config==null) return;
                var safe=new PatternGeneratorSettings(); safe.applyConfiguration(config);
                PatternGeneratorClientHooks.get().save(new PatternGeneratorPresetFiles.Preset(name,author,uuid,date,safe.configuration().toString(),ids));
                presetMessage=tr("preset_saved").getString(); resolveSave(true);
            } else if (kind==121) {
                int request=b.readVarInt(); boolean valid=b.readBoolean();
                if (!valid) { if (request==presetRequest) { inspectedAvailable=0; presetMessage=tr("preset_unavailable").getString(); } return; }
                var machine=b.readItem(); String mode=b.readUtf(256); int available=b.readVarInt(),missing=b.readVarInt(),size=PatternGeneratorCodec.readSize(b,150);
                var samples=new ArrayList<PatternGeneratorPreview>(); for (int i=0;i<size;i++) samples.add(PatternGeneratorCodec.readPreview(b));
                if (request!=presetRequest || chosenPreset==null) return;
                inspectedMachine=machine.getHoverName().getString(); inspectedMode=Component.translatable(mode).getString();
                inspectedAvailable=available; inspectedMissing=missing; presetSamples=List.copyOf(samples);
                presetMessage=available>samples.size()?tr("preset_sample",samples.size()).getString():missing>0?tr("preset_missing").getString():"";
            }
        } catch (Exception ex) { saveRequested=false; resolveSave(false); presetError(ex); }
        finally { b.release(); layoutRequested=true; }
    }
    private void resolveSave(boolean success) {
        if (pendingRequest!=0) feedback.resolve(pendingRequest,success?ButtonFeedback.Tone.SUCCESS:ButtonFeedback.Tone.FAILED,now()); pendingRequest=0;
        if (success) ShopUiSounds.success(false); else ShopUiSounds.fail();
    }
    void serverClosed() { closed=true; PatternGeneratorScreenOpener.forget(this); if (minecraft!=null) minecraft.setScreen(null); }
    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { finish(); super.onClose(); }
    private void finish() {
        if (closed) return;
        if (minecraft!=null && minecraft.getConnection()!=null) { model.flush(); model.send(CLOSE,b->{}); }
        closed=true; PatternGeneratorScreenOpener.forget(this);
    }
    @Override public void removed() {
        feedback.cancel(now()); ShopGaussBlur.clear(); ShopGaussBlur.clearTopLayer();
        if (!suspended) finish(); super.removed();
    }
    /** NBT 與所有 tag 均完整列入提示，不縮短識別內容。 */
    private static final class ItemStackDetails {
        static void append(List<Component> lines,GenericStack stack) {
            var tag=GenericStack.writeTag(stack); if (tag!=null) lines.add(Component.literal(tag.toString()));
        }
    }
}
