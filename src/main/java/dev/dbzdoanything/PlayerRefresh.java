package dev.dbzdoanything;

import com.dragonminez.common.config.ConfigManager;
import com.dragonminez.common.config.FormConfig;
import com.dragonminez.common.init.MainEffects;
import com.dragonminez.common.network.NetworkHandler;
import com.dragonminez.common.network.S2C.ProgressionSyncS2C;
import com.dragonminez.common.network.S2C.StatsSyncS2C;
import com.dragonminez.common.stats.StatsCapability;
import com.dragonminez.common.stats.StatsData;
import com.dragonminez.common.stats.StatsProvider;
import com.dragonminez.common.util.FormRequisites;
import com.dragonminez.common.util.FusionForms;
import com.dragonminez.common.util.TransformationsHelper;
import com.dragonminez.server.events.players.StatsEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

final class PlayerRefresh {
    private PlayerRefresh() {}

    static List<String> forecast(ServerPlayer player, NativeCatalog.State next) {
        var losses = new ArrayList<String>();
        StatsProvider.get(StatsCapability.INSTANCE, player).ifPresent(data -> {
            var character = data.getCharacter();
            String race = character.getRaceName();
            String baseGroup = character.getActiveFormGroup();
            FormConfig.FormData retained = null;
            if (character.hasActiveForm()) {
                var candidate = nextForm(next, race, baseGroup, character.getActiveForm(), false);
                if (forecastAllowed(next, data, race, baseGroup, candidate, false)) retained = candidate;
                else losses.add(character.getActiveForm());
            }
            if (character.hasActiveStackForm()) {
                String group = character.getActiveStackFormGroup();
                var candidate = nextForm(next, race, group, character.getActiveStackForm(), true);
                if (!forecastAllowed(next, data, race, group, candidate, true)
                        || !canStack(retained, baseGroup, candidate, group)) losses.add(character.getActiveStackForm());
            }
        });
        return losses;
    }

    private static FormConfig.FormData nextForm(NativeCatalog.State next, String race, String group, String form, boolean stack) {
        var config = nextGroup(next, race, group, stack);
        return config == null ? null : config.getForm(form);
    }

    private static FormConfig nextGroup(NativeCatalog.State next, String race, String group, boolean stack) {
        if (group == null || group.isBlank()) return null;
        String qualified = FusionForms.qualifierRace(group);
        String baseGroup = FusionForms.baseGroup(group);
        return stack ? next.stacks().get(baseGroup) : next.forms()
                .getOrDefault(qualified == null ? race : qualified, java.util.Map.of()).get(baseGroup);
    }

    private static boolean forecastAllowed(NativeCatalog.State next, StatsData data, String race, String group,
                                           FormConfig.FormData form, boolean stack) {
        if (!allowed(data, group, form, stack)) return false;
        var character = data.getCharacter();
        if ((stack && character.getActiveStackFormItemDurationTicks() > 0)
                || (!stack && (character.getActiveFormItemDurationTicks() > 0
                || TransformationsHelper.isScriptedFormGroup(race, group)
                || "androidforms".equals(FusionForms.baseGroup(group))))) return true;
        var config = nextGroup(next, race, group, stack);
        String skill = TransformationsHelper.getSkillNameForType(config.getFormType());
        var skills = stack ? data.getSkills() : FusionForms.formSkillsFor(data, group);
        int level = form.getUnlockOnSkillLevel();
        if (!stack && TransformationsHelper.hasMutantLegendaryAccess(data, group)) level--;
        if (level > 0 && !skills.isUnlockedAtLevel(skill, level)) return false;
        if (data.getPlayer().isCreative() || form.getUnlockOnMastery() <= 0 || form.getFormRequisite().isBlank()) return true;
        boolean any = "any".equals(form.getFormRequisiteType());
        for (String requirement : form.getFormRequisite().split(",")) {
            String[] link = requirement.strip().split("\\.", 2);
            if (link.length != 2) continue;
            String linked = FusionForms.qualifyLinkedGroup(group, link[0]);
            boolean met = Math.max(character.getFormMasteries().getMastery(linked, link[1]),
                    character.getStackFormMasteries().getMastery(linked, link[1])) >= form.getUnlockOnMastery();
            if (met == any) return any;
        }
        return !any;
    }

    private static boolean allowed(StatsData data, String group, FormConfig.FormData form, boolean stack) {
        if (form == null) return false;
        var character = data.getCharacter();
        if (!stack && TransformationsHelper.isScriptedFormGroup(character.getRaceName(), group)) return true;
        if ((!stack && character.getActiveFormItemDurationTicks() > 0)
                || (stack && character.getActiveStackFormItemDurationTicks() > 0)) return true;
        return !FormRequisites.check(data, form, false).isLocked();
    }

    static void reconcile(ServerPlayer player, NativeCatalog.State before) {
        StatsProvider.get(StatsCapability.INSTANCE, player).ifPresent(data -> {
            if (!data.getStatus().isHasCreatedCharacter()) return;
            var character = data.getCharacter();
            String race = character.getRaceName();
            if (before != null && pricesChanged(before, race)) data.updateTransformationSkillLimits(race);

            String baseGroup = character.getActiveFormGroup();
            var active = character.getActiveFormData();
            var oldForm = before == null ? null : nextForm(before, race, baseGroup, character.getActiveForm(), false);
            if (character.hasActiveForm() && (active == null || (active != oldForm && (!allowed(data, baseGroup, active, false)
                    || !nativeUnlocked(data, race, baseGroup, active, false))))) {
                TransformationsHelper.revertToBaseForm(player, data);
                if (character.getActiveFormData() == null) character.clearActiveForm(player, false);
                if (!character.hasActiveForm()) player.removeEffect(MainEffects.TRANSFORMED.get());
                character.clearPreviousFormRecord();
                player.sendSystemMessage(Component.literal("Your transformation ended because its availability or requirements changed."));
            }

            String stackGroup = character.getActiveStackFormGroup();
            var stack = character.getActiveStackFormData();
            var oldStack = before == null ? null : nextForm(before, race, stackGroup, character.getActiveStackForm(), true);
            active = character.getActiveFormData();
            if (character.hasActiveStackForm() && (stack == null || (stack != oldStack && (!allowed(data, stackGroup, stack, true)
                    || !nativeUnlocked(data, race, stackGroup, stack, true)))
                    || !canStack(active, character.getActiveFormGroup(), stack, stackGroup))) {
                character.clearActiveStackForm(player, false);
                character.clearPreviousStackFormRecord();
                player.removeEffect(MainEffects.STACK_TRANSFORMED.get());
                player.sendSystemMessage(Component.literal("Your stack transformation ended because it is no longer available or compatible."));
            }

            if (character.getSelectedFormGroup() == null || character.getSelectedFormGroup().isBlank()
                    || !TransformationsHelper.isSelectableForm(data, character.getSelectedFormGroup(), character.getSelectedForm())) {
                character.setSelectedFormGroup(TransformationsHelper.getGroupWithFirstAvailableForm(data));
                character.setSelectedForm(TransformationsHelper.getFirstAvailableForm(data));
            }
            if (character.getSelectedStackFormGroup() == null || character.getSelectedStackFormGroup().isBlank()
                    || !TransformationsHelper.isSelectableStackForm(data, character.getSelectedStackFormGroup(), character.getSelectedStackForm())) {
                character.setSelectedStackFormGroup(TransformationsHelper.getGroupWithFirstAvailableStackForm(data));
                character.setSelectedStackForm(TransformationsHelper.getFirstAvailableStackForm(data));
            }

            player.refreshDimensions();
            StatsEvents.applyHealthBonus(player);
            player.setHealth(Math.min(player.getHealth(), player.getMaxHealth()));
            var resources = data.getResources();
            resources.setCurrentEnergy(Math.min(resources.getCurrentEnergy(), data.getMaxEnergy()));
            resources.setCurrentStamina(Math.min(resources.getCurrentStamina(), data.getMaxStamina()));
            NetworkHandler.sendToTrackingEntityAndSelf(new StatsSyncS2C(player), player);
            NetworkHandler.sendToTrackingEntityAndSelf(new ProgressionSyncS2C(player), player);
        });
    }

    private static boolean canStack(FormConfig.FormData active, String baseGroup, FormConfig.FormData stack, String stackGroup) {
        return active == null || (active.getFormStackable() && stack.getFormStackable()
                && TransformationsHelper.areFormsCompatible(active, baseGroup, stack, stackGroup));
    }

    private static boolean pricesChanged(NativeCatalog.State before, String race) {
        var current = ConfigManager.getRaceCharacter(race);
        var previous = before.characters().get(race);
        if (previous == null || !previous.getFormSkills().equals(current.getFormSkills())) return true;
        return current.getFormSkills().stream().anyMatch(skill ->
                !Arrays.equals(previous.getFormSkillTpCosts(skill), current.getFormSkillTpCosts(skill))
                        || previous.isFormSkillBuyFromMaster(skill) != current.isFormSkillBuyFromMaster(skill));
    }

    private static boolean nativeUnlocked(StatsData data, String race, String group, FormConfig.FormData form, boolean stack) {
        var character = data.getCharacter();
        if (stack && character.getActiveStackFormItemDurationTicks() > 0) return true;
        if (!stack && (character.getActiveFormItemDurationTicks() > 0
                || TransformationsHelper.isScriptedFormGroup(race, group)
                || "androidforms".equals(FusionForms.baseGroup(group)))) return true;
        var options = stack ? TransformationsHelper.getUnlockedStackForms(data, group)
                : TransformationsHelper.getUnlockedForms(data, race, group);
        return options.stream().anyMatch(value -> value.getName().equals(form.getName()));
    }
}
