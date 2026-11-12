package dev.dbzdoanything.mixin;

import com.dragonminez.common.network.C2S.UpdateSkillC2S;
import com.dragonminez.common.stats.StatsData;
import dev.dbzdoanything.SpecialGameplay;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = UpdateSkillC2S.class, remap = false)
abstract class SkillPurchaseRuleMixin {
    @Shadow @Final private String skillName;
    @Shadow @Final private UpdateSkillC2S.SkillAction action;

    @Inject(method = "lambda$handle$0", at = @At("HEAD"), cancellable = true)
    private void editorBeforeBuyingForm(ServerPlayer player, StatsData stats, CallbackInfo callback) {
        if (action != UpdateSkillC2S.SkillAction.TOGGLE && SpecialGameplay.formUnlockRestricted(player, skillName)) {
            SpecialGameplay.formDenied(player);
            callback.cancel();
        }
    }
}
