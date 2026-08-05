package org.terraform.structure.village.plains.forge;

import org.bukkit.Material;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Bisected.Half;
import org.bukkit.block.data.type.Slab.Type;
import org.jetbrains.annotations.NotNull;
import org.terraform.coregen.populatordata.PopulatorDataAbstract;
import org.terraform.data.Wall;
import org.terraform.structure.room.jigsaw.JigsawType;
import org.terraform.structure.village.plains.PlainsVillagePopulator;
import org.terraform.utils.BlockUtils;
import org.terraform.utils.blockdata.OrientableBuilder;
import org.terraform.utils.blockdata.SlabBuilder;
import org.terraform.utils.blockdata.StairBuilder;

import java.util.AbstractMap.SimpleEntry;
import java.util.Random;

public class PlainsVillageForgeWallPiece extends PlainsVillageForgePiece {

    public PlainsVillageForgeWallPiece(PlainsVillagePopulator plainsVillagePopulator,
                                       int widthX,
                                       int height,
                                       int widthZ,
                                       JigsawType type,
                                       BlockFace[] validDirs)
    {
        super(plainsVillagePopulator, widthX, height, widthZ, type, validDirs);
    }


    @Override
    public void build(PopulatorDataAbstract data, Random rand) {
        // All handled in postBuildDecoration, as the type to build is only identified then.
    }

    @Override
    public void postBuildDecoration(@NotNull Random rand, @NotNull PopulatorDataAbstract data) {
        if (getWallType() == PlainsVillageForgeWallType.SOLID) { // Wall
            SimpleEntry<Wall, Integer> entry = this.getRoom().getWall(data, getRotation().getOppositeFace(), 0);
            Wall w = entry.getKey().getDown();
            for (int i = 0; i < entry.getValue(); i++) {
                w.getDown().downUntilSolid(rand, plainsVillagePopulator.cobblestone, plainsVillagePopulator.mossyCobblestone);
                w.RPillar(5, rand, plainsVillagePopulator.cobblestone, Material.ANDESITE, Material.STONE);

                // Handle window hole placement
                if (i == 2) { // Center
                    w.getUp(2).setType(Material.AIR);
                    new SlabBuilder(plainsVillagePopulator.cobblestoneSlab,
                            Material.ANDESITE_SLAB,
                            Material.STONE_SLAB,
                            plainsVillagePopulator.stoneBrickSlab).setType(Type.TOP).apply(w.getUp(3).getFront());

                    new StairBuilder(plainsVillagePopulator.cobblestoneStairs,
                            Material.ANDESITE_STAIRS,
                            Material.STONE_STAIRS,
                            plainsVillagePopulator.stoneBrickStairs).setFacing(w.getDirection().getOppositeFace())
                                                        .apply(w.getUp().getFront());

                    w.getFront().downUntilSolid(rand, plainsVillagePopulator.cobblestone, plainsVillagePopulator.mossyCobblestone);
                }
                else if (i == 1 || i == 3) { // Beside the opening
                    w.getUp(3)
                     .getFront()
                     .setType(plainsVillagePopulator.cobblestoneSlab,
                             Material.ANDESITE_SLAB,
                             Material.STONE_SLAB,
                             plainsVillagePopulator.stoneBrickSlab);
                    w.getUp(2)
                     .getFront()
                     .setType(plainsVillagePopulator.cobblestoneWall, plainsVillagePopulator.stoneBrickWall, Material.ANDESITE_WALL);

                    // Stair decor
                    if (i == 1) {
                        new StairBuilder(
                                plainsVillagePopulator.cobblestoneStairs,
                                Material.ANDESITE_STAIRS,
                                Material.STONE_STAIRS,
                                plainsVillagePopulator.stoneBrickStairs
                        ).setFacing(BlockUtils.getRight(w.getDirection()))
                         .apply(w.getUp(2))
                         .setFacing(BlockUtils.getLeft(w.getDirection()))
                         .setHalf(Half.TOP)
                         .apply(w.getUp().getFront());
                    }
                    else {
                        new StairBuilder(
                                plainsVillagePopulator.cobblestoneStairs,
                                Material.ANDESITE_STAIRS,
                                Material.STONE_STAIRS,
                                plainsVillagePopulator.stoneBrickStairs
                        ).setFacing(BlockUtils.getLeft(w.getDirection()))
                         .apply(w.getUp(2))
                         .setFacing(BlockUtils.getRight(w.getDirection()))
                         .setHalf(Half.TOP)
                         .apply(w.getUp().getFront());
                    }

                }

                w = w.getLeft();
            }
        }
        else // Fences
        {
            SimpleEntry<Wall, Integer> entry = this.getRoom().getWall(data, getRotation().getOppositeFace(), 0);
            Wall w = entry.getKey();
            for (int i = 0; i < entry.getValue(); i++) {
                w.getDown(2).downUntilSolid(rand, plainsVillagePopulator.cobblestone, plainsVillagePopulator.mossyCobblestone);

                if (i == 2) {
                    w.getDown().Pillar(2, rand, plainsVillagePopulator.woodLog);
                    w.getUp().setType(Material.STONE_SLAB, plainsVillagePopulator.cobblestoneSlab, Material.ANDESITE_SLAB);
                }
                else {
                    w.get().lsetType(plainsVillagePopulator.woodFence);
                    w.CorrectMultipleFacing(1);
                    new OrientableBuilder(plainsVillagePopulator.woodLog).setAxis(BlockUtils.getAxisFromBlockFace(
                            BlockUtils.getLeft(w.getDirection()))).apply(w.getDown());
                }

                w = w.getLeft();
            }
        }

    }

    public enum PlainsVillageForgeWallType {
        SOLID, FENCE
    }
}
