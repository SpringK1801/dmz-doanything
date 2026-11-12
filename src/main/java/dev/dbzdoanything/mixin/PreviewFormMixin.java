package dev.dbzdoanything.mixin;

import com.dragonminez.common.config.FormConfig;
import com.dragonminez.common.stats.character.Character;
import dev.dbzdoanything.PreviewFormAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = Character.class, remap = false)
abstract class PreviewFormMixin implements PreviewFormAccess {
    @Unique private FormConfig.FormData editor$previewForm;

    @Override
    public void editor$setPreviewForm(FormConfig.FormData data) {
        editor$previewForm = data;
    }

    @Inject(method = "getActiveFormData", at = @At("HEAD"), cancellable = true)
    private void editor$previewForm(CallbackInfoReturnable<FormConfig.FormData> callback) {
        if (editor$previewForm != null) callback.setReturnValue(editor$previewForm);
    }
}
