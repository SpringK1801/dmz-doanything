package dev.dbzdoanything.mixin;

import com.dragonminez.common.network.C2S.IncreaseStatC2S;
import com.dragonminez.common.stats.StatsData;
import dev.dbzdoanything.SpecialGameplay;
import dev.dbzdoanything.SpecialRule;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = IncreaseStatC2S.class, remap = false)
abstract class StatSpendingRuleMixin {
    @Inject(method = "lambda$handle$0", at = @At("HEAD"), cancellable = true)
    private static void editorBeforeSpending(IncreaseStatC2S packet, ServerPlayer player, StatsData stats, CallbackInfo callback) {
        if (SpecialGameplay.restricted(player, SpecialRule.STAT_SPENDING)) {
            SpecialGameplay.statSpendingDenied(player);
            callback.cancel();
        }
    }
}
