package dev.dbzdoanything;

import com.dragonminez.common.hair.HairPresets;
import net.minecraftforge.fml.ModList;

import java.nio.file.Files;
import java.util.List;

final class BuilderAppearancePresets {
    static final List<String> TWO_D_AURAS = List.of("kakarot", "smooth", "superperfect", "ultra", "kicontrol");
    static final List<String> THREE_D_AURAS = List.of("userPreference", "sparking", "god");
    private static final String HUMAN = "humansaiyan/";

    private BuilderAppearancePresets() {}

    static boolean supports(String key, int id) {
        if (id < 0) return false;
        if (id == 0) return true;
        return switch (key) {
            case "defaultHairType" -> HairPresets.ids().contains(id);
            case "defaultBodyType" -> texture(HUMAN + "bodytype_male_" + id + "_layer1")
                    && texture(HUMAN + "bodytype_female_" + id + "_layer1");
            case "defaultEyesType" -> texture(HUMAN + "faces/humansaiyan_eye_" + id + "_0");
            case "defaultNoseType" -> texture(HUMAN + "faces/humansaiyan_nose_" + id);
            case "defaultMouthType" -> texture(HUMAN + "faces/humansaiyan_mouth_" + id);
            case "defaultTattooType" -> texture("tattoos/tattoo_" + id);
            default -> false;
        };
    }

    private static boolean texture(String path) {
        String file = "textures/entity/races/" + path + ".png";
        return ModList.get().getModFiles().stream().anyMatch(mod ->
                Files.isRegularFile(mod.getFile().findResource("assets", "dragonminez", file)));
    }
}
