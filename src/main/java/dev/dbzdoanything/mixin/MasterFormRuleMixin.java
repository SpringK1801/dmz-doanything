package dev.dbzdoanything.mixin;

import com.dragonminez.common.network.C2S.NPCActionC2S;
import com.dragonminez.common.stats.StatsData;
import dev.dbzdoanything.SpecialGameplay;
import dev.dbzdoanything.SpecialRule;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = NPCActionC2S.class, remap = false)
abstract class MasterFormRuleMixin {
    @Inject(method = "handleGero", at = @At("HEAD"), cancellable = true)
    private static void editorBeforeAndroidUpgrade(ServerPlayer player, StatsData stats, int action,
                                                    CallbackInfoReturnable<Boolean> callback) {
        if (action == 1 && SpecialGameplay.restricted(player, SpecialRule.FORM_UNLOCKS)) {
            SpecialGameplay.formDenied(player);
            callback.setReturnValue(false);
        }
    }

    @Redirect(method = "grantOldKaiChallengeReward", at = @At(value = "INVOKE",
            target = "Lcom/dragonminez/common/network/C2S/NPCActionC2S;meetsOldKaiRequirements(Lcom/dragonminez/common/stats/StatsData;)Z"))
    private static boolean editorBeforeUltimateGrant(StatsData stats, ServerPlayer player, StatsData ignored) {
        if (!NPCActionC2S.meetsOldKaiRequirements(stats)) return false;
        if (!SpecialGameplay.restricted(player, SpecialRule.FORM_UNLOCKS)) return true;
        SpecialGameplay.formDenied(player);
        return false;
    }
}
