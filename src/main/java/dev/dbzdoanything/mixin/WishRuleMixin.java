package dev.dbzdoanything.mixin;

import com.dragonminez.common.dragonball.DragonDefinition;
import com.dragonminez.common.init.entities.dragon.DragonWishEntity;
import com.dragonminez.common.network.C2S.GrantWishC2S;
import com.dragonminez.common.wish.Wish;
import dev.dbzdoanything.SpecialGameplay;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.network.NetworkEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.LocalCapture;

import java.util.List;
import java.util.function.Supplier;

@Mixin(value = GrantWishC2S.class, remap = false)
abstract class WishRuleMixin {
    @Inject(method = "lambda$handle$0", at = @At(value = "INVOKE",
            target = "Lcom/dragonminez/common/init/entities/dragon/DragonWishEntity;setGrantedWish(Z)V"),
            locals = LocalCapture.CAPTURE_FAILHARD, cancellable = true)
    private void editorBeforeConsumingWish(Supplier<NetworkEvent.Context> context, CallbackInfo callback,
                                           ServerPlayer player, ServerLevel level, Entity entity, DragonWishEntity dragon,
                                           DragonDefinition definition, List<Wish> allWishes, int maxWishes,
                                           List<Wish> wishesToGrant) {
        if (SpecialGameplay.rejectWishes(player, wishesToGrant)) callback.cancel();
    }
}
