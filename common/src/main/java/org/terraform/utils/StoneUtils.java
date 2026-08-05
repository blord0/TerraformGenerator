package org.terraform.utils;

import org.bukkit.Material;
import org.jetbrains.annotations.NotNull;
import org.terraform.biome.BiomeBank;

public class StoneUtils {

    public static @NotNull Material getStoneForBiome(@NotNull BiomeBank biome, @NotNull StoneType type) {
        return switch (biome) {
            case DESERT, DESERT_MOUNTAINS, DESERT_RIVER -> type.sandstone;
            case BADLANDS, BADLANDS_RIVER, BADLANDS_BEACH, BADLANDS_CANYON,
                 BADLANDS_CANYON_PEAK -> type.redSandstone;
            default -> type.stone;
        };
    }

    public enum StoneType {
        BRICK(Material.STONE_BRICKS, Material.SANDSTONE, Material.RED_SANDSTONE),
        MOSSY_BRICK(Material.MOSSY_STONE_BRICKS, Material.SMOOTH_SANDSTONE, Material.SMOOTH_RED_SANDSTONE),
        CRACKED_BRICK(Material.CRACKED_STONE_BRICKS, Material.CUT_SANDSTONE, Material.CUT_RED_SANDSTONE),
        CHISELED_BRICK(Material.CHISELED_STONE_BRICKS, Material.CHISELED_SANDSTONE, Material.CHISELED_RED_SANDSTONE),
        BRICK_STAIRS(Material.STONE_BRICK_STAIRS, Material.SANDSTONE_STAIRS, Material.RED_SANDSTONE_STAIRS),
        MOSSY_BRICK_STAIRS(Material.MOSSY_STONE_BRICK_STAIRS, Material.SMOOTH_SANDSTONE_STAIRS,
                Material.SMOOTH_RED_SANDSTONE_STAIRS),
        BRICK_SLAB(Material.STONE_BRICK_SLAB, Material.SANDSTONE_SLAB, Material.RED_SANDSTONE_SLAB),
        MOSSY_BRICK_SLAB(Material.MOSSY_STONE_BRICK_SLAB, Material.SMOOTH_SANDSTONE_SLAB,
                Material.SMOOTH_RED_SANDSTONE_SLAB),
        BRICK_WALL(Material.STONE_BRICK_WALL, Material.SANDSTONE_WALL, Material.RED_SANDSTONE_WALL),
        MOSSY_BRICK_WALL(Material.MOSSY_STONE_BRICK_WALL, Material.SANDSTONE_WALL, Material.RED_SANDSTONE_WALL),
        COBBLE(Material.COBBLESTONE, Material.SMOOTH_SANDSTONE, Material.SMOOTH_RED_SANDSTONE),
        MOSSY_COBBLE(Material.MOSSY_COBBLESTONE, Material.SANDSTONE, Material.RED_SANDSTONE),
        COBBLE_STAIRS(Material.COBBLESTONE_STAIRS, Material.SMOOTH_SANDSTONE_STAIRS,
                Material.SMOOTH_RED_SANDSTONE_STAIRS),
        MOSSY_COBBLE_STAIRS(Material.MOSSY_COBBLESTONE_STAIRS, Material.SANDSTONE_STAIRS,
                Material.RED_SANDSTONE_STAIRS),
        COBBLE_SLAB(Material.COBBLESTONE_SLAB, Material.SMOOTH_SANDSTONE_SLAB, Material.SMOOTH_RED_SANDSTONE_SLAB),
        MOSSY_COBBLE_SLAB(Material.MOSSY_COBBLESTONE_SLAB, Material.SANDSTONE_SLAB, Material.RED_SANDSTONE_SLAB),
        COBBLE_WALL(Material.COBBLESTONE_WALL, Material.SANDSTONE_WALL, Material.RED_SANDSTONE_WALL),
        MOSSY_COBBLE_WALL(Material.MOSSY_COBBLESTONE_WALL, Material.SANDSTONE_WALL, Material.RED_SANDSTONE_WALL),
        ACCENT(Material.WHITE_TERRACOTTA, Material.ORANGE_TERRACOTTA, Material.RED_TERRACOTTA),
        ;

        final Material stone;
        final Material sandstone;
        final Material redSandstone;

        StoneType(Material stone, Material sandstone, Material redSandstone) {
            this.stone = stone;
            this.sandstone = sandstone;
            this.redSandstone = redSandstone;
        }
    }

}
