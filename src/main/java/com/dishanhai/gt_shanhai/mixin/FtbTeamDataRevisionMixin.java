package com.dishanhai.gt_shanhai.mixin;

import com.dishanhai.gt_shanhai.api.ftbq.FtbqCacheRevision;
import com.dishanhai.gt_shanhai.api.ftbq.FtbqSubmitterToastSuppressor;
import dev.ftb.mods.ftbquests.quest.QuestObject;
import dev.ftb.mods.ftblibrary.snbt.SNBTCompoundTag;
import dev.ftb.mods.ftbquests.quest.TeamData;
import dev.ftb.mods.ftbquests.quest.task.Task;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Collection;
import java.util.Collections;
import java.util.Date;

@Mixin(value = TeamData.class, remap = false)
public class FtbTeamDataRevisionMixin {

    @Inject(method = "clearCachedProgress", at = @At("RETURN"))
    private void shanhai$bumpAfterClearCachedProgress(CallbackInfo ci) {
        FtbqCacheRevision.bump((TeamData) (Object) this);
    }

    @Inject(method = {"resetProgress", "markTaskCompleted"}, at = @At("RETURN"))
    private void shanhai$bumpAfterTaskMutation(Task task, CallbackInfo ci) {
        FtbqCacheRevision.bump((TeamData) (Object) this);
    }

    @ModifyArg(
            method = "markTaskCompleted",
            at = @At(
                    value = "INVOKE",
                    target = "Ldev/ftb/mods/ftbquests/events/QuestProgressEventData;<init>(Ljava/util/Date;Ldev/ftb/mods/ftbquests/quest/TeamData;Ldev/ftb/mods/ftbquests/quest/QuestObject;Ljava/util/Collection;Ljava/util/Collection;)V"
            ),
            index = 4
    )
    private Collection<ServerPlayer> shanhai$suppressSubmitterCompletionToast(Collection<ServerPlayer> notifiedPlayers) {
        return FtbqSubmitterToastSuppressor.isSuppressing() ? Collections.emptyList() : notifiedPlayers;
    }

    @Inject(method = {"mergeData", "copyData"}, at = @At("RETURN"))
    private void shanhai$bumpAfterTeamMutation(TeamData teamData, CallbackInfo ci) {
        FtbqCacheRevision.bump((TeamData) (Object) this);
    }

    @Inject(method = {"setProgress", "addProgress"}, at = @At("RETURN"))
    private void shanhai$bumpAfterProgressMutation(Task task, long value, CallbackInfo ci) {
        FtbqCacheRevision.bump((TeamData) (Object) this);
    }

    @Inject(method = {"setStarted", "setCompleted"}, at = @At("RETURN"))
    private void shanhai$bumpAfterTimeMutation(long id, Date date, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValue().booleanValue()) {
            FtbqCacheRevision.bump((TeamData) (Object) this);
        }
    }

    @Inject(method = "deserializeNBT", at = @At("RETURN"))
    private void shanhai$bumpAfterDeserialize(SNBTCompoundTag nbt, CallbackInfo ci) {
        FtbqCacheRevision.bump((TeamData) (Object) this);
    }

    @Inject(method = "read", at = @At("RETURN"))
    private void shanhai$bumpAfterRead(FriendlyByteBuf buffer, boolean self, CallbackInfo ci) {
        FtbqCacheRevision.bump((TeamData) (Object) this);
    }
}
