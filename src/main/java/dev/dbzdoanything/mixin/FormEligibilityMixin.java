package dev.dbzdoanything.mixin;

import com.dragonminez.common.config.FormConfig;
import com.dragonminez.common.stats.StatsData;
import com.dragonminez.common.util.FormRequisites;
import com.dragonminez.common.util.FusionForms;
import com.dragonminez.common.util.TransformationsHelper;
import dev.dbzdoanything.EditorClient;
import dev.dbzdoanything.WorldEditor;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

@Mixin(value = TransformationsHelper.class, remap = false, priority = 500)
abstract class FormEligibilityMixin {
    @Inject(method = "getNextAvailableForm", at = @At("RETURN"), cancellable = true)
    private static void editorCheckActivation(StatsData data, CallbackInfoReturnable<FormConfig.FormData> callback) {
        var form = callback.getReturnValue();
        if (form != null && editorLocked(data, data.getCharacter().getRaceName(),
                TransformationsHelper.getTransformTargetGroup(data), form, false)) callback.setReturnValue(null);
    }

    @Inject(method = "getNextAvailableStackForm", at = @At("RETURN"), cancellable = true)
    private static void editorCheckStackActivation(StatsData data, CallbackInfoReturnable<FormConfig.FormData> callback) {
        var form = callback.getReturnValue();
        var character = data.getCharacter();
        String group = character.hasActiveStackForm() ? character.getActiveStackFormGroup() : character.getSelectedStackFormGroup();
        if (form != null && editorLocked(data, character.getRaceName(), group, form, true)) callback.setReturnValue(null);
    }

    @Inject(method = "getUnlockedForms", at = @At("RETURN"), cancellable = true)
    private static void editorFilterRaceForms(StatsData data, String race, String group,
                                              CallbackInfoReturnable<List<FormConfig.FormData>> callback) {
        editorFilter(data, race, group, false, callback);
    }

    @Inject(method = "getUnlockedStackForms", at = @At("RETURN"), cancellable = true)
    private static void editorFilterStackForms(StatsData data, String group,
                                               CallbackInfoReturnable<List<FormConfig.FormData>> callback) {
        editorFilter(data, data.getCharacter().getRaceName(), group, true, callback);
    }

    private static void editorFilter(StatsData data, String race, String group, boolean stack,
                                     CallbackInfoReturnable<List<FormConfig.FormData>> callback) {
        var forms = callback.getReturnValue();
        var filtered = forms.stream().filter(form -> !editorLocked(data, race, group, form, stack)).toList();
        if (filtered.size() != forms.size()) callback.setReturnValue(new java.util.ArrayList<>(filtered));
    }

    private static boolean editorLocked(StatsData data, String race, String group, FormConfig.FormData form, boolean stack) {
        var player = data.getPlayer();
        if (player == null || player.isCreative() || group == null || group.isBlank()) return false;
        String qualifier = FusionForms.qualifierRace(group);
        String path = (stack ? "forms/" : "races/" + (qualifier == null ? race : qualifier) + "/forms/")
                + FusionForms.baseGroup(group);
        boolean overridden = player.level().isClientSide()
                ? Boolean.TRUE.equals(DistExecutor.unsafeCallWhenOn(Dist.CLIENT, () -> () -> EditorClient.overrides(path)))
                : WorldEditor.overrides(path);
        return overridden && FormRequisites.check(data, form, player.level().isClientSide()).isLocked();
    }
}
