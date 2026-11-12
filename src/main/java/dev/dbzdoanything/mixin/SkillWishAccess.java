package dev.dbzdoanything.mixin;

import com.dragonminez.common.wish.wishes.SkillWish;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = SkillWish.class, remap = false)
public interface SkillWishAccess {
    @Accessor("skill")
    String editorSkill();
}
