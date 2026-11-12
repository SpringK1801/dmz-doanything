package dev.dbzdoanything.mixin;

import com.dragonminez.common.config.ConfigManager;
import dev.dbzdoanything.WorldEditor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = ConfigManager.class, remap = false, priority = 500)
abstract class ConfigReloadMixin {
    @Inject(method = "reload()V", at = @At("RETURN"))
    private static void editorAfterReload(CallbackInfo callback) {
        WorldEditor.nativeReload(null);
    }

    @Inject(method = "reloadSpecificConfig(Ljava/lang/String;)V", at = @At("RETURN"))
    private static void editorAfterSpecificReload(String path, CallbackInfo callback) {
        WorldEditor.nativeReload(path);
    }
}
