package dev.autoschematicpro.build;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtSizeTracker;
import net.minecraft.registry.Registries;
import net.minecraft.state.property.Property;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class SchematicLoader {
    public static final int MAX_BLOCKS = 2_000_000;
    public static final int MAX_PALETTE = 20_000;
    private static final long MAX_NBT_BYTES = 64L * 1024L * 1024L;

    private SchematicLoader() {}

    public static Path schematicDirectory() {
        return FabricLoader.getInstance().getGameDir().resolve("schematics");
    }

    public static List<SchematicPlan> loadAll() throws IOException {
        Path directory = schematicDirectory();
        Files.createDirectories(directory);
        try (var paths = Files.list(directory)) {
            List<Path> files = paths
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase().endsWith(".schem"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString().toLowerCase()))
                    .toList();

            List<SchematicPlan> plans = new ArrayList<>();
            for (Path file : files) {
                try {
                    plans.add(load(file));
                } catch (Exception ignored) {
                    // A bad file should not hide the other available schematics.
                }
            }
            return plans;
        }
    }

    private static SchematicPlan load(Path file) throws IOException {
        NbtCompound root = NbtIo.readCompressed(file, NbtSizeTracker.of(MAX_NBT_BYTES));
        int width = dimension(root, "Width");
        int height = dimension(root, "Height");
        int length = dimension(root, "Length");
        long volumeLong = (long) width * height * length;
        if (width < 1 || height < 1 || length < 1 || volumeLong > MAX_BLOCKS) {
            throw new IOException("Invalid or oversized schematic dimensions");
        }
        int volume = (int) volumeLong;

        NbtCompound blocksTag = root.getCompoundOrEmpty("Blocks");
        NbtCompound paletteTag = root.contains("Palette")
                ? root.getCompoundOrEmpty("Palette")
                : blocksTag.getCompoundOrEmpty("Palette");
        byte[] encodedBlocks = root.getByteArray("BlockData")
                .orElseGet(() -> blocksTag.getByteArray("Data").orElse(new byte[0]));
        if (paletteTag.isEmpty() || encodedBlocks.length == 0) {
            throw new IOException("Schematic is missing its palette or block data");
        }

        Map<Integer, BlockState> palette = new HashMap<>();
        Map<Integer, String> paletteNames = new HashMap<>();
        int maxPaletteIndex = -1;
        for (String stateName : paletteTag.getKeys()) {
            int paletteIndex = paletteTag.getInt(stateName, -1);
            if (paletteIndex >= 0 && paletteIndex < MAX_PALETTE) {
                palette.put(paletteIndex, parseBlockState(stateName));
                paletteNames.put(paletteIndex, stateName);
                maxPaletteIndex = Math.max(maxPaletteIndex, paletteIndex);
            }
        }
        List<String> paletteList = new ArrayList<>();
        for (int i = 0; i <= maxPaletteIndex; i++) {
            paletteList.add(paletteNames.getOrDefault(i, "minecraft:air"));
        }

        List<SchematicPlan.Placement> placements = new ArrayList<>();
        int[] cursor = {0};
        for (int index = 0; index < volume; index++) {
            int paletteIndex = readVarInt(encodedBlocks, cursor);
            BlockState state = palette.get(paletteIndex);
            if (state == null) {
                throw new IOException("Block data refers to an unknown palette entry");
            }
            if (!state.isAir() && state.getBlock().asItem() != net.minecraft.item.Items.AIR) {
                int x = index % width;
                int z = (index / width) % length;
                int y = index / (width * length);
                placements.add(new SchematicPlan.Placement(new BlockPos(x, y, z), state, paletteIndex));
            }
        }
        if (placements.isEmpty()) {
            throw new IOException("Schematic contains no placeable blocks");
        }

        placements.sort(Comparator
                .comparingInt((SchematicPlan.Placement placement) -> placement.relativePos().getY())
                .thenComparingInt(placement -> placement.relativePos().getZ())
                .thenComparingInt(placement -> placement.relativePos().getX()));
        return new SchematicPlan(file.getFileName().toString(), width, height, length, paletteList, placements);
    }

    private static int dimension(NbtCompound root, String name) {
        return root.getInt(name, root.getShort(name, (short) 0));
    }

    private static int readVarInt(byte[] data, int[] cursor) throws IOException {
        int result = 0;
        int shift = 0;
        while (shift < 35) {
            if (cursor[0] >= data.length) {
                throw new IOException("Truncated schematic block data");
            }
            int current = data[cursor[0]++] & 0xFF;
            result |= (current & 0x7F) << shift;
            if ((current & 0x80) == 0) {
                return result;
            }
            shift += 7;
        }
        throw new IOException("Invalid schematic varint");
    }

    static BlockState parseBlockState(String input) throws IOException {
        int bracket = input.indexOf('[');
        String blockName = bracket < 0 ? input : input.substring(0, bracket);
        Identifier id = Identifier.tryParse(blockName);
        if (id == null || !Registries.BLOCK.containsId(id)) {
            throw new IOException("Schematic refers to an unknown block: " + blockName);
        }
        Block block = Registries.BLOCK.get(id);
        BlockState state = block.getDefaultState();
        if (bracket < 0 || !input.endsWith("]")) {
            return state;
        }

        String properties = input.substring(bracket + 1, input.length() - 1);
        if (properties.isBlank()) {
            return state;
        }
        for (String part : properties.split(",")) {
            String[] pair = part.split("=", 2);
            if (pair.length != 2) {
                continue;
            }
            Property<?> property = block.getStateManager().getProperty(pair[0].trim());
            if (property != null) {
                state = applyProperty(state, property, pair[1].trim());
            }
        }
        return state;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static BlockState applyProperty(BlockState state, Property property, String valueName) {
        return (BlockState) property.parse(valueName)
                .map(value -> state.with(property, (Comparable) value))
                .orElse(state);
    }
}
