package org.terraform.structure.village.plains;

import org.bukkit.Axis;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.type.Slab;
import org.bukkit.block.data.type.Slab.Type;
import org.jetbrains.annotations.NotNull;
import org.terraform.biome.BiomeBank;
import org.terraform.coregen.bukkit.TerraformGenerator;
import org.terraform.data.SimpleBlock;
import org.terraform.data.TerraformWorld;
import org.terraform.data.Wall;
import org.terraform.main.config.TConfig;
import org.terraform.small_items.PlantBuilder;
import org.terraform.structure.room.CubeRoom;
import org.terraform.structure.room.PathPopulatorAbstract;
import org.terraform.structure.room.PathPopulatorData;
import org.terraform.utils.BlockUtils;
import org.terraform.utils.GenUtils;
import org.terraform.utils.StoneUtils;
import org.terraform.utils.StoneUtils.StoneType;
import org.terraform.utils.WoodUtils;
import org.terraform.utils.WoodUtils.WoodType;

import java.util.Collection;
import java.util.Random;

public class PlainsVillagePathPopulator extends PathPopulatorAbstract {
    final TerraformWorld tw;
    private final Random random;
    private final Collection<DirectionalCubeRoom> knownRooms;

    public PlainsVillagePathPopulator(TerraformWorld tw, Collection<DirectionalCubeRoom> collection, Random rand) {
        this.tw = tw;
        this.random = rand;
        this.knownRooms = collection;
    }

    public static void placeLamp(@NotNull Random rand, @NotNull SimpleBlock b) {
        BiomeBank biome = b.getPopData().getTerraformWorld().getBiomeBank(b.getX(), b.getZ());
        b.setType(GenUtils.randChoice(rand,
                StoneUtils.getStoneForBiome(biome, StoneType.BRICK),
                StoneUtils.getStoneForBiome(biome, StoneType.MOSSY_BRICK)
        ));
        b.getUp().setType(GenUtils.randChoice(rand,
                StoneUtils.getStoneForBiome(biome, StoneType.COBBLE_WALL),
                StoneUtils.getStoneForBiome(biome, StoneType.MOSSY_COBBLE_WALL)
        ));
        b.getUp(2).setType(GenUtils.randChoice(rand,
                StoneUtils.getStoneForBiome(biome, StoneType.COBBLE_WALL),
                StoneUtils.getStoneForBiome(biome, StoneType.MOSSY_COBBLE_WALL)
        ));
        b.getUp(3).setType(GenUtils.randChoice(rand,
                StoneUtils.getStoneForBiome(biome, StoneType.COBBLE),
                StoneUtils.getStoneForBiome(biome, StoneType.MOSSY_COBBLE)
        ));
        b.getUp(4).setType(Material.CAMPFIRE);
        b.getUp(5).setType(GenUtils.randChoice(rand,
                StoneUtils.getStoneForBiome(biome, StoneType.BRICK),
                StoneUtils.getStoneForBiome(biome, StoneType.MOSSY_BRICK)
        ));
        for (BlockFace face : BlockUtils.directBlockFaces) {
            Slab tSlab = (Slab) Bukkit.createBlockData(GenUtils.randChoice(
                    rand,
                    StoneUtils.getStoneForBiome(biome, StoneType.BRICK_SLAB),
                    StoneUtils.getStoneForBiome(biome, StoneType.MOSSY_BRICK_SLAB)
            ));
            tSlab.setType(Type.TOP);
            b.getRelative(face).getUp(3).setBlockData(tSlab);
            b.getRelative(face)
             .getUp(4)
             .setType(GenUtils.randChoice(rand,
                     StoneUtils.getStoneForBiome(biome, StoneType.COBBLE_WALL),
                     StoneUtils.getStoneForBiome(biome, StoneType.MOSSY_COBBLE_WALL)
             ));
            b.getRelative(face)
             .getUp(5)
             .setType(GenUtils.randChoice(rand,
                     StoneUtils.getStoneForBiome(biome, StoneType.BRICK_SLAB),
                     StoneUtils.getStoneForBiome(biome, StoneType.MOSSY_BRICK_SLAB)
             ));
        }
    }

    /**
     * Only checks if the target location has enough space to place a lamp,
     * and if the lamp is in water. Other
     * checks such as ground type etc must be done elsewhere
     *
     * @param target the block where the base of the lamp is.
     * @return whether or not the lamp has enough space to be placed here.
     */
    public static boolean canPlaceLamp(@NotNull SimpleBlock target) {

        if (target.getType() == Material.WATER) {
            return false;
        }
        for (BlockFace face : BlockUtils.xzPlaneBlockFaces) {
            for (int i = 0; i < 6; i++) {
                if (target.getRelative(face).getRelative(0, i, 0).isSolid()) {
                    return false;
                }
            }
        }

        return true;
    }

    @Override
    public void populate(@NotNull PathPopulatorData ppd) {

        // Find the ground level to place pathways
        ppd.base = new SimpleBlock(ppd.base.getPopData(),
                ppd.base.getX(),
                GenUtils.getHighestGround(ppd.base.getPopData(), ppd.base.getX(), ppd.base.getZ()),
                ppd.base.getZ()
        );

        // Path is on water. Place a solid wooden foundation, and then return.
        if (BlockUtils.isWet(ppd.base.getUp())) {

            Wall pathCore = new Wall(ppd.base, ppd.dir).getAtY(TerraformGenerator.seaLevel);

            if ((BlockUtils.getAxisFromBlockFace(ppd.dir) == Axis.X && ppd.base.getX() % 2 == 0)
                || (BlockUtils.getAxisFromBlockFace(ppd.dir) == Axis.Z && ppd.base.getZ() % 2 == 0))
            {
                BiomeBank biome = tw.getBiomeBank(ppd.base.getX(), ppd.base.getZ());
                pathCore.getDown().downLPillar(random, 50, WoodUtils.getWoodForBiome(biome, WoodType.LOG));
                pathCore.setType(StoneUtils.getStoneForBiome(biome, StoneType.CHISELED_BRICK));
            }
            return;
        }

        // Decorate the sides of the paths
        BiomeBank pathBiome = tw.getBiomeBank(ppd.base.getX(), ppd.base.getZ());
        Wall pathCore = new Wall(ppd.base, ppd.dir);
        for (BlockFace face : BlockUtils.getAdjacentFaces(ppd.dir)) {
            for (int i = 0; i < 4; i++) {
                Wall target = pathCore.getRelative(face, i).getGround();
                if (!target.getUp().isSolid() && target.getUp().getType() != Material.WATER && BlockUtils.isDirtLike(
                        target.getType()) && target.getType() != Material.DIRT_PATH)
                {
                    if (GenUtils.chance(2, 5)) { // Leaves
                        PlantBuilder.OAK_LEAVES.build(target.getUp());
                    }
                    else if (GenUtils.chance(1, 5)) { // Flowers
                        BlockUtils.pickTallFlower().build(target);
                    }
                    else if (GenUtils.chance(1, 10)) { // Small cobble walls with lanterns
                        target.getUp().setType(
                                StoneUtils.getStoneForBiome(pathBiome, StoneType.COBBLE_WALL),
                                StoneUtils.getStoneForBiome(pathBiome, StoneType.MOSSY_COBBLE_WALL)
                        );
                        if (TConfig.areDecorationsEnabled()) {
                            target.getUp(2).setType(Material.LANTERN);
                        }
                    }

                    break;
                }
            }
        }


        if (GenUtils.chance(random, 1, 15)) {
            BlockFace side = BlockUtils.getTurnBlockFace(random, ppd.dir);
            SimpleBlock target = new SimpleBlock(ppd.base.getPopData(),
                    ppd.base.getX() + side.getModX() * 3,
                    GenUtils.getHighestGround(ppd.base.getPopData(),
                            ppd.base.getX() + side.getModX() * 3,
                            ppd.base.getZ() + side.getModZ() * 3
                    ),
                    ppd.base.getZ() + side.getModZ() * 3
            );
            if (target.getType() == Material.DIRT_PATH) {
                return;
            }
            for (BlockFace face : BlockUtils.xzPlaneBlockFaces) {
                if (target.getRelative(face).getGround().getUp().isSolid()) {
                    return;
                }
            }

            for (CubeRoom room : knownRooms) {
                if (room.isPointInside(target)) {
                    return;
                }
            }

            placeLamp(random, target.getUp());
        }
    }

}
