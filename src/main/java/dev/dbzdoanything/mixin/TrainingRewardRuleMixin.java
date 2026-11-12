package dev.dbzdoanything.mixin;

import com.dragonminez.common.stats.StatsData;
import com.dragonminez.common.training.MinigameSessionManager;
import dev.dbzdoanything.SpecialGameplay;
import dev.dbzdoanything.SpecialRule;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = MinigameSessionManager.class, remap = false)
abstract class TrainingRewardRuleMixin {
    @Inject(method = "computeReward", at = @At("RETURN"), cancellable = true)
    private static void editorTrainingReward(StatsData stats, @Coerce Object session, CallbackInfoReturnable<Double> callback) {
        if (stats.getPlayer() instanceof ServerPlayer player && SpecialGameplay.restricted(player, SpecialRule.TP_GAIN)) {
            callback.setReturnValue(0.0);
        }
    }

    @Inject(method = "finish", at = @At(value = "INVOKE",
            target = "Lcom/dragonminez/common/training/MinigameSessionManager;computeReward(Lcom/dragonminez/common/stats/StatsData;Lcom/dragonminez/common/training/MinigameSessionManager$Session;)D"))
    private static void editorTrainingResult(ServerPlayer player, @Coerce Object session, boolean interrupted,
                                             CallbackInfo callback) {
        if (SpecialGameplay.restricted(player, SpecialRule.TP_GAIN)) SpecialGameplay.trainingTpDenied(player);
    }
}
