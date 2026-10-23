package com.dishanhai.gt_shanhai.client.gui.generator;

import com.dishanhai.gt_shanhai.common.item.generator.*;
import com.dishanhai.gt_shanhai.network.PatternGeneratorActionPacket;
import com.dishanhai.gt_shanhai.network.ShanhaiNetwork;
import io.netty.buffer.Unpooled;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import java.util.function.Consumer;

/** 山海：伺服器快照與本地草稿分開保存；切頁與選取器往返不丟失草稿。 */
public final class PatternGeneratorClientState {
    public static final int TYPE=101, CIRCUIT=102, WHITE=103, CLEAR=104, PAGE=105, SELECT=106,
            SELECT_ALL=107, GENERATE=108, CANCEL=109, FILL=110, COLUMNS=111, INCLUDE_TAG=112,
            EXPORT_PRESET=113, APPLY_PRESET=114, INSPECT_PRESET=115, EXT=116, BUNDLE=117,
            SET_SLOT=118, CLOSE=119;
    PatternGeneratorState snapshot;
    final PatternGeneratorSettings settings = new PatternGeneratorSettings();
    String inputDraft, outputDraft, scaleDraft, outputScaleDraft, queryDraft;
    boolean whiteDirty, extDirty;
    boolean awaitingWhite, awaitingExt;
    private int whiteRequest,extRequest,mutationRequest,requestSequence;
    private long editAt, extAt;

    public PatternGeneratorClientState(PatternGeneratorState state) {
        snapshot = state;
        settings.read(state.settings());
        inputDraft = settings.inputWhite;
        outputDraft = settings.outputWhite;
        scaleDraft = Integer.toString(settings.scale);
        outputScaleDraft = Integer.toString(settings.outputScale);
        queryDraft = settings.recipeQuery;
        // 譯名索引只存在客戶端，重開後重新送出，包含套用預設後的首次解析。
        whiteDirty = !inputDraft.isBlank() || !outputDraft.isBlank();
    }

    void accept(PatternGeneratorState state) {
        if (!snapshot.sessionId().equals(state.sessionId())) return;
        snapshot = state;
        settings.read(state.settings());
        // 拒絕也會回傳同一個 request id；不以設定吻合猜測是否收到回覆。
        if (awaitingWhite && state.acknowledgedRequest()>=whiteRequest) awaitingWhite=false;
        if (awaitingExt && state.acknowledgedRequest()>=extRequest) awaitingExt=false;
        if (!whiteDirty && !awaitingWhite) {
            inputDraft = settings.inputWhite;
            outputDraft = settings.outputWhite;
            if (!state.ready() && (!inputDraft.isBlank() || !outputDraft.isBlank())) { whiteDirty=true; editAt=0; }
        }
        if (!extDirty && !awaitingExt) {
            scaleDraft = Integer.toString(settings.scale);
            outputScaleDraft = Integer.toString(settings.outputScale);
            queryDraft = settings.recipeQuery;
        }
    }

    int transfers() { return snapshot.settings().getInt("transferCount"); }
    boolean editable() { return snapshot.pendingCount() == 0 && transfers() == 0 && !snapshot.running(); }
    boolean ready() { return snapshot.ready() && !whiteDirty && !extDirty && !awaitingWhite && !awaitingExt
            && snapshot.acknowledgedRequest()>=mutationRequest && validScales(); }
    boolean validScales() { return parse(scaleDraft) != null && parse(outputScaleDraft) != null; }
    void white(boolean input, String value, long now) {
        if (input) inputDraft = PatternGeneratorSettings.limit(value);
        else outputDraft = PatternGeneratorSettings.limit(value);
        whiteDirty = true;
        editAt = now;
    }
    void extension(int field, String value, long now) {
        if (field == 0) scaleDraft = value;
        else if (field == 1) outputScaleDraft = value;
        else queryDraft = PatternGeneratorSettings.limit(value);
        extDirty = true;
        extAt = now;
    }
    void tick(long now) {
        if (whiteDirty && now - editAt >= 250) flushWhite();
        if (extDirty && now - extAt >= 250 && validScales()) flushExt();
    }
    boolean flush() {
        boolean dirty = whiteDirty || extDirty;
        if (whiteDirty) flushWhite();
        if (extDirty && validScales()) flushExt();
        return dirty;
    }
    private void flushWhite() {
        var input = PatternGeneratorClientHooks.get().find(inputDraft);
        var output = PatternGeneratorClientHooks.get().find(outputDraft);
        whiteRequest=send(WHITE, b -> {
            b.writeUtf(inputDraft,256); b.writeUtf(outputDraft,256);
            PatternGeneratorCodec.writeBits(b,input.items(),BuiltInRegistries.ITEM.size());
            PatternGeneratorCodec.writeBits(b,input.fluids(),BuiltInRegistries.FLUID.size());
            PatternGeneratorCodec.writeBits(b,output.items(),BuiltInRegistries.ITEM.size());
            PatternGeneratorCodec.writeBits(b,output.fluids(),BuiltInRegistries.FLUID.size());
        });
        whiteDirty = false;
        awaitingWhite=true;
    }
    private void flushExt() { sendExt(settings.minTier,settings.maxTier,settings.sort,settings.skipOwned); }
    void sendExt(int min, int max, int sort, boolean skip) {
        if (!validScales()) return;
        extRequest=send(EXT, b -> {
            b.writeVarInt(parse(scaleDraft)); b.writeVarInt(parse(outputScaleDraft));
            b.writeVarInt(min); b.writeVarInt(max); b.writeVarInt(sort); b.writeBoolean(skip);
            b.writeUtf(queryDraft,256);
        });
        extDirty = false;
        awaitingExt=true;
    }
    int revision(int action) { return send(action,b -> b.writeVarInt(snapshot.revision())); }
    int send(int action, Consumer<FriendlyByteBuf> writer) {
        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            writer.accept(buffer);
            byte[] bytes = new byte[buffer.readableBytes()];
            buffer.readBytes(bytes);
            int request=++requestSequence;
            for (var packet:PatternGeneratorActionPacket.split(snapshot.sessionId(),action,bytes,request)) ShanhaiNetwork.CHANNEL.sendToServer(packet);
            if (action==TYPE || action==CIRCUIT || action==WHITE || action==CLEAR || action==EXT || action==SET_SLOT
                    || action==INCLUDE_TAG || action==APPLY_PRESET || action==SELECT || action==SELECT_ALL) mutationRequest=request;
            return request;
        } finally { buffer.release(); }
    }
    static Integer parse(String text) {
        try { int value = Integer.parseInt(text.strip()); return value >= 1 && value <= PatternGeneratorScale.MAX_FACTOR ? value : null; }
        catch (NumberFormatException ex) { return null; }
    }
}
