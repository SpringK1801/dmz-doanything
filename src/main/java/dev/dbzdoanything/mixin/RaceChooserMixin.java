package dev.dbzdoanything.mixin;

import com.dragonminez.client.gui.character.RaceSelectionScreen;
import dev.dbzdoanything.EditorClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

@Mixin(value = RaceSelectionScreen.class, remap = false, priority = 500)
abstract class RaceChooserMixin {
    @Inject(method = "getAvailableRaces()Ljava/util/List;", at = @At("RETURN"), cancellable = true)
    private void editorHideRetired(CallbackInfoReturnable<List<String>> callback) {
        var races = callback.getReturnValue();
        if (races.stream().anyMatch(EditorClient::retired)) {
            callback.setReturnValue(races.stream().filter(race -> !EditorClient.retired(race)).toList());
        }
    }
}
