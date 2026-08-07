package baritone.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

public final class BaritoneConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = FabricLoader.getInstance()
            .getConfigDir()
            .resolve("baritone-reborn.json");
    private static final BaritoneConfig INSTANCE = loadConfig();

    private boolean oreHighlights = true;
    private boolean pathHighlights = true;

    public static BaritoneConfig get() {
        return INSTANCE;
    }

    public boolean oreHighlights() {
        return oreHighlights;
    }

    public boolean pathHighlights() {
        return pathHighlights;
    }

    public void setOreHighlights(boolean enabled) {
        oreHighlights = enabled;
        save();
    }

    public void setPathHighlights(boolean enabled) {
        pathHighlights = enabled;
        save();
    }

    public void save() {
        try {
            Files.createDirectories(FILE.getParent());
            try (Writer writer = Files.newBufferedWriter(FILE)) {
                GSON.toJson(this, writer);
            }
        } catch (Exception ignored) {
        }
    }

    private static BaritoneConfig loadConfig() {
        if (!Files.isRegularFile(FILE)) return new BaritoneConfig();
        try (Reader reader = Files.newBufferedReader(FILE)) {
            BaritoneConfig config = GSON.fromJson(reader, BaritoneConfig.class);
            return config == null ? new BaritoneConfig() : config;
        } catch (Exception ignored) {
            return new BaritoneConfig();
        }
    }
}
