package com.dishanhai.gt_shanhai.mixin;

import com.dishanhai.gt_shanhai.api.ftbq.FtbqCacheRevision;
import dev.ftb.mods.ftbquests.quest.Quest;
import dev.ftb.mods.ftbquests.quest.QuestObject;
import dev.ftb.mods.ftbquests.quest.TeamData;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = Quest.class, remap = false)
public class FtbQuestDependencyCacheMixin {

    @Unique
    private TeamData shanhai$dependencyTeamData;
    @Unique
    private long shanhai$dependencyRevision = Long.MIN_VALUE;
    @Unique
    private boolean shanhai$dependenciesComplete;

    @Inject(method = "areDependenciesComplete", at = @At("HEAD"), cancellable = true)
    private void shanhai$getCachedDependenciesComplete(TeamData teamData, CallbackInfoReturnable<Boolean> cir) {
        long revision = FtbqCacheRevision.get(teamData);
        if (this.shanhai$dependencyTeamData == teamData && this.shanhai$dependencyRevision == revision) {
            cir.setReturnValue(Boolean.valueOf(this.shanhai$dependenciesComplete));
        }
    }

    @Inject(method = "areDependenciesComplete", at = @At("RETURN"))
    private void shanhai$storeCachedDependenciesComplete(TeamData teamData, CallbackInfoReturnable<Boolean> cir) {
        this.shanhai$dependencyTeamData = teamData;
        this.shanhai$dependencyRevision = FtbqCacheRevision.get(teamData);
        this.shanhai$dependenciesComplete = cir.getReturnValue().booleanValue();
    }

    @Inject(method = "readData", at = @At("RETURN"))
    private void shanhai$clearDependencyCacheAfterRead(CompoundTag nbt, CallbackInfo ci) {
        this.shanhai$clearDependencyCache();
    }

    @Inject(method = "readNetData", at = @At("RETURN"))
    private void shanhai$clearDependencyCacheAfterNetRead(FriendlyByteBuf buffer, CallbackInfo ci) {
        this.shanhai$clearDependencyCache();
    }

    @Inject(method = {"addDependency", "removeDependency"}, at = @At("RETURN"))
    private void shanhai$clearDependencyCacheAfterDependencyChange(QuestObject dependency, CallbackInfo ci) {
        this.shanhai$clearDependencyCache();
    }

    @Inject(method = {"removeInvalidDependencies", "clearDependencies"}, at = @At("RETURN"))
    private void shanhai$clearDependencyCacheAfterBulkDependencyChange(CallbackInfo ci) {
        this.shanhai$clearDependencyCache();
    }

    @Inject(method = "clearCachedData", at = @At("RETURN"))
    private void shanhai$clearDependencyCacheAfterClearCachedData(CallbackInfo ci) {
        this.shanhai$clearDependencyCache();
    }

    @Unique
    private void shanhai$clearDependencyCache() {
        this.shanhai$dependencyTeamData = null;
        this.shanhai$dependencyRevision = Long.MIN_VALUE;
    }
}
