package dev.dbzdoanything.mixin;

import dev.dbzdoanything.EditorClient;
import net.minecraft.client.resources.language.ClientLanguage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = ClientLanguage.class, priority = 1600)
abstract class LanguageMixin {
    @Inject(method = {"getOrDefault(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;",
            "m_118919_(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;"}, at = @At("HEAD"),
            cancellable = true, remap = false, require = 1, allow = 1)
    private void editorName(String key, String fallback, CallbackInfoReturnable<String> callback) {
        String name = EditorClient.name(key);
        if (name != null) callback.setReturnValue(name);
    }

    @Inject(method = {"has(Ljava/lang/String;)Z", "m_6722_(Ljava/lang/String;)Z"}, at = @At("HEAD"),
            cancellable = true, remap = false, require = 1, allow = 1)
    private void editorHasName(String key, CallbackInfoReturnable<Boolean> callback) {
        if (EditorClient.name(key) != null) callback.setReturnValue(true);
    }
}
