package dev.dbzdoanything.compat;

import com.google.gson.JsonObject;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.FMLPaths;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.Set;

public final class UltraCompatibility implements EditorCompatibility.Adapter {
    private final Method forgetScale;

    private UltraCompatibility(Method forgetScale) {
        this.forgetScale = forgetScale;
    }

    public static void registerIfSupported() {
        var ultra = ModList.get().getModContainerById("dmzultra");
        if (ultra.isEmpty() || !ultra.get().getModInfo().getVersion().toString().equals("0.4.1")) return;
        try {
            var type = Class.forName("com.dmzultra.form.OverhaulScale", false, UltraCompatibility.class.getClassLoader());
            EditorCompatibility.register("dmzultra", new UltraCompatibility(type.getMethod("forget")));
        } catch (ReflectiveOperationException | LinkageError error) {
            throw new IllegalStateException("The pinned DMZUltra cache integration is unavailable.", error);
        }
    }

    @Override public String name() { return "DMZUltra"; }

    @Override
    public boolean owns(String path, JsonObject effective) {
        var file = FMLPaths.CONFIGDIR.get().resolve("dragonminez").resolve(path + ".json.dmzultra_hash");
        return Files.isRegularFile(file) || (path.endsWith("/stats")
                && Files.isRegularFile(file.resolveSibling("character.json.dmzultra_hash")));
    }

    @Override
    public void refresh(Set<String> publishedPaths) {
        if (publishedPaths.isEmpty()) return;
        try {
            forgetScale.invoke(null);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Could not refresh DMZUltra form scaling.", error);
        }
    }
}
