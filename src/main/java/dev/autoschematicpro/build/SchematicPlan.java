package dev.autoschematicpro.build;

import net.minecraft.block.BlockState;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A parsed schematic. {@code palette} holds the original block-state strings (e.g. "minecraft:oak_stairs[facing=north]")
 * so a plan can be sent over the network and re-parsed by the server, which has no access to the client's files.
 * Placements are sorted by (y, z, x), i.e. ascending linear index.
 */
public record SchematicPlan(String name, int width, int height, int length, List<String> palette, List<Placement> placements) {
    public SchematicPlan {
        palette = List.copyOf(palette);
        placements = List.copyOf(placements);
    }

    public Map<Item, Integer> materialCounts() {
        Map<Item, Integer> counts = new LinkedHashMap<>();
        for (Placement placement : placements) {
            Item item = placement.state().getBlock().asItem();
            if (item != Items.AIR) {
                counts.merge(item, 1, Integer::sum);
            }
        }
        return counts;
    }

    public record Placement(BlockPos relativePos, BlockState state, int paletteIndex) {}
}
