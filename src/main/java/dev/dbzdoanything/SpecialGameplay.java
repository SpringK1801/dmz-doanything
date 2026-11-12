package dev.dbzdoanything;

import com.dragonminez.common.config.ConfigManager;
import com.dragonminez.common.events.DMZEvent;
import com.dragonminez.common.quest.rewards.SkillReward;
import com.dragonminez.common.quest.rewards.TPSReward;
import com.dragonminez.common.quest.rewards.TransformationReward;
import com.dragonminez.common.wish.Wish;
import com.dragonminez.common.wish.wishes.SkillWish;
import com.dragonminez.common.wish.wishes.TPSWish;
import dev.dbzdoanything.mixin.SkillWishAccess;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.IEventBus;

import java.util.List;
import java.util.Locale;

public final class SpecialGameplay {
    private SpecialGameplay() {}

    static void register(IEventBus events) {
        events.addListener(EventPriority.LOWEST, SpecialGameplay::questReward);
        events.addListener(EventPriority.LOWEST, SpecialGameplay::kiCharge);
    }

    public static boolean restricted(ServerPlayer player, SpecialRule rule) {
        return WorldEditor.restricts(player, rule);
    }

    public static boolean formUnlockRestricted(ServerPlayer player, String skill) {
        return restricted(player, SpecialRule.FORM_UNLOCKS) && isFormSkill(skill);
    }

    private static boolean isFormSkill(String skill) {
        if (skill == null) return false;
        var config = ConfigManager.getSkillsConfig();
        String id = skill.toLowerCase(Locale.ROOT);
        return config.getFormSkills().contains(id) || config.getStackSkills().contains(id);
    }

    public static void formDenied(ServerPlayer player) {
        notice(player, "This world's Special Settings prohibit new form unlocks.");
    }

    public static void statSpendingDenied(ServerPlayer player) {
        notice(player, "This world's Special Settings prohibit spending stat points.");
    }

    public static void trainingTpDenied(ServerPlayer player) {
        notice(player, "Training finished. This world's Special Settings prohibit TP rewards.");
    }

    private static void notice(ServerPlayer player, String message) {
        player.displayClientMessage(Component.literal(message), true);
    }

    private static void questReward(DMZEvent.QuestRewardClaimEvent event) {
        var reward = event.getQuest().getRewards().get(event.getRewardIndex());
        var player = event.getPlayer();
        if (reward instanceof TPSReward tp && tp.getAmount() > 0 && restricted(player, SpecialRule.TP_GAIN)) {
            event.setCanceled(true);
            notice(player, "The TP reward is still available when this world allows TP gains again.");
        } else if ((reward instanceof TransformationReward && restricted(player, SpecialRule.FORM_UNLOCKS))
                || (reward instanceof SkillReward skill && formUnlockRestricted(player, skill.getSkill()))) {
            event.setCanceled(true);
            formDenied(player);
        }
    }

    private static void kiCharge(DMZEvent.KiChargeEvent event) {
        if (event.getPlayer() instanceof ServerPlayer player && restricted(player, SpecialRule.KI_CHARGING)) {
            event.setCanceled(true);
        }
    }

    public static boolean rejectWishes(ServerPlayer player, List<Wish> wishes) {
        for (var wish : wishes) {
            if (wish instanceof TPSWish && restricted(player, SpecialRule.TP_GAIN)) {
                notice(player, "This world prohibits TP gains. Choose a different wish.");
                return true;
            }
            if (wish instanceof SkillWish && formUnlockRestricted(player, ((SkillWishAccess) wish).editorSkill())) {
                formDenied(player);
                return true;
            }
        }
        return false;
    }
}
