package dev.dbzdoanything.mixin;

import com.dragonminez.common.racial.RacialAbility;
import com.dragonminez.common.racial.RacialRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

@Mixin(value = RacialRegistry.class, remap = false)
public interface RacialAccess {
    @Accessor("ABILITIES")
    static Map<String, RacialAbility> editorAbilities() { throw new AssertionError(); }
}
