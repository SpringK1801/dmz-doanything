package dev.dbzdoanything.mixin;

import com.dragonminez.common.config.ConfigManager;
import com.dragonminez.common.network.C2S.CreateCharacterC2S;
import dev.dbzdoanything.WorldEditor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(value = CreateCharacterC2S.class, remap = false)
abstract class CreateCharacterMixin {
    @Redirect(method = "lambda$handle$2", at = @At(value = "INVOKE",
            target = "Lcom/dragonminez/common/config/ConfigManager;isRaceLoaded(Ljava/lang/String;)Z"))
    private static boolean editorAllowCreation(String race) {
        return ConfigManager.isRaceLoaded(race) && !WorldEditor.isRetired(race);
    }
}
