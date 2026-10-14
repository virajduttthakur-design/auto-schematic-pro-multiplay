package dev.autoschematicpro.build;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Server-side settings, stored in config/auto_schematic_pro.json of whichever side hosts the world.
 * Dedicated servers default to a locked-down setup (nobody allowed until listed); integrated servers
 * (single-player / Open to LAN) default to permissive.
 */
public final class BuildConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** If true, every player may use the builder. Otherwise only names/UUIDs in allowedPlayers. */
    public boolean allowEveryone;
    /** Player names (case-insensitive) or UUIDs permitted to build when allowEveryone is false. */
    public List<String> allowedPlayers = new ArrayList<>();
    /** Largest schematic (in placed blocks) the server accepts. */
    public int maxBlocks = 500_000;
    /** The build origin must be within this many blocks (horizontally) of the player when starting. */
    public int maxOriginDistance = 128;
    /** The supply chest must be within this many blocks of the player when starting. */
    public int maxChestDistance = 16;
    /** Server ticks between block placements (20 ticks = 1 second). Minimum 1. */
    public int placeIntervalTicks = 2;
    /** Maximum simultaneous builds on the server. */
    public int maxConcurrentBuilds = 4;
    /** If false, only air/replaceable blocks are built into; existing blocks are skipped. Block entities are never overwritten. */
    public boolean overwriteExistingBlocks;

    public static BuildConfig load(boolean dedicated) {
        Path file = FabricLoader.getInstance().getConfigDir().resolve("auto_schematic_pro.json");
        BuildConfig config = null;
        if (Files.isRegularFile(file)) {
            try (Reader reader = Files.newBufferedReader(file)) {
                config = GSON.fromJson(reader, BuildConfig.class);
            } catch (Exception ignored) {
                // fall through to defaults
            }
        }
        if (config == null) {
            config = new BuildConfig();
            config.allowEveryone = !dedicated;
            config.overwriteExistingBlocks = !dedicated;
            config.save(file);
        }
        if (config.allowedPlayers == null) {
            config.allowedPlayers = new ArrayList<>();
        }
        config.maxBlocks = Math.max(1, Math.min(config.maxBlocks, SchematicLoader.MAX_BLOCKS));
        config.placeIntervalTicks = Math.max(1, config.placeIntervalTicks);
        config.maxConcurrentBuilds = Math.max(1, config.maxConcurrentBuilds);
        config.maxOriginDistance = Math.max(1, config.maxOriginDistance);
        config.maxChestDistance = Math.max(1, config.maxChestDistance);
        return config;
    }

    private void save(Path file) {
        try {
            Files.createDirectories(file.getParent());
            try (Writer writer = Files.newBufferedWriter(file)) {
                GSON.toJson(this, writer);
            }
        } catch (IOException ignored) {
            // not fatal; defaults stay in memory
        }
    }

    public boolean isAllowed(UUID uuid, String name) {
        if (allowEveryone) {
            return true;
        }
        for (String entry : allowedPlayers) {
            if (entry.equalsIgnoreCase(name) || entry.equalsIgnoreCase(uuid.toString())) {
                return true;
            }
        }
        return false;
    }
}
