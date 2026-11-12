package dev.dbzdoanything.mixin;

import com.dragonminez.common.stats.character.Resources;
import dev.dbzdoanything.SpecialGameplay;
import dev.dbzdoanything.SpecialRule;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = Resources.class, remap = false)
abstract class TrainingPointsRuleMixin {
    @Shadow public abstract Player getPlayer();

    @Inject(method = "addTrainingPoints(DZZ)V", at = @At("HEAD"), cancellable = true)
    private void editorBeforeTpAward(double amount, boolean share, boolean boosts, CallbackInfo callback) {
        if (amount > 0 && getPlayer() instanceof ServerPlayer player && SpecialGameplay.restricted(player, SpecialRule.TP_GAIN)) {
            callback.cancel();
        }
    }
}
