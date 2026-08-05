package org.terraform.structure.village.plains.forge;

import org.bukkit.Material;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Bisected.Half;
import org.jetbrains.annotations.NotNull;
import org.terraform.coregen.populatordata.PopulatorDataAbstract;
import org.terraform.data.SimpleBlock;
import org.terraform.data.Wall;
import org.terraform.structure.room.jigsaw.JigsawType;
import org.terraform.structure.village.plains.PlainsVillagePopulator;
import org.terraform.structure.village.plains.forge.PlainsVillageForgeWallPiece.PlainsVillageForgeWallType;
import org.terraform.utils.BlockUtils;
import org.terraform.utils.StairwayBuilder;
import org.terraform.utils.blockdata.OrientableBuilder;
import org.terraform.utils.blockdata.StairBuilder;

import java.util.AbstractMap.SimpleEntry;
import java.util.Random;

public class PlainsVillageForgeEntrancePiece extends PlainsVillageForgePiece {

    public PlainsVillageForgeEntrancePiece(PlainsVillagePopulator plainsVillagePopulator,
                                           int widthX,
                                           int height,
                                           int widthZ,
                                           JigsawType type,
                                           BlockFace[] validDirs)
    {
        super(plainsVillagePopulator, widthX, height, widthZ, type, validDirs);

    }

    @Override
    public void build(@NotNull PopulatorDataAbstract data, @NotNull Random rand) {
        SimpleEntry<Wall, Integer> entry = this.getRoom().getWall(data, getRotation().getOppositeFace(), 0);
        Wall w = entry.getKey().getDown();

        // Wall
        for (int i = 0; i < entry.getValue(); i++) {
            w.getDown().downUntilSolid(rand, plainsVillagePopulator.cobblestone, plainsVillagePopulator.mossyCobblestone);
            w = w.getLeft();
        }

        Wall core = new Wall(new SimpleBlock(
                data,
                this.getRoom().getX(),
                this.getRoom().getY() + 1,
                this.getRoom().getZ()
        ), this.getRotation());
        core = core.getRear(2);

        // Stairway down

        if (core.getFront().isSolid()) {
            new StairwayBuilder(plainsVillagePopulator.cobblestoneStairs, plainsVillagePopulator.mossyCobblestoneStairs).setAngled(true)
                                                                                               .setStopAtWater(true)
                                                                                               .setStairwayDirection(
                                                                                                       BlockFace.UP)
                                                                                               .build(core.getFront(3));
            core.getFront().Pillar(2, rand, Material.AIR);
            core.getFront(2).Pillar(3, rand, Material.AIR);
        }
        else {
            new StairwayBuilder(plainsVillagePopulator.cobblestoneStairs, plainsVillagePopulator.mossyCobblestoneStairs).setAngled(true)
                                                                                               .setStopAtWater(true)
                                                                                               .build(core.getFront()
                                                                                                          .getDown());
        }

    }

    @Override
    public void postBuildDecoration(@NotNull Random rand, @NotNull PopulatorDataAbstract data) {
        if (getWallType() == PlainsVillageForgeWallType.SOLID) { // Door entrance
            SimpleEntry<Wall, Integer> entry = this.getRoom().getWall(data, getRotation().getOppositeFace(), 0);
            Wall w = entry.getKey().getDown();
            for (int i = 0; i < entry.getValue(); i++) {
                w.getDown().downUntilSolid(rand, plainsVillagePopulator.cobblestone, plainsVillagePopulator.mossyCobblestone);
                w.RPillar(5, rand, plainsVillagePopulator.cobblestone, Material.ANDESITE, Material.STONE);
                w = w.getLeft();
            }
            Wall core = new Wall(new SimpleBlock(
                    data,
                    this.getRoom().getX(),
                    this.getRoom().getY() + 1,
                    this.getRoom().getZ()
            ), this.getRotation());
            core = core.getRear(2);
            core.getDown().setType(plainsVillagePopulator.chiseledStoneBrick);
            BlockUtils.placeDoor(
                    data,
                    plainsVillagePopulator.woodDoor,
                    core.getX(),
                    core.getY(),
                    core.getZ(),
                    core.getDirection().getOppositeFace()
            );

            // Door decor
            core.getUp(2).getFront().setType(plainsVillagePopulator.stoneBrickSlab);

            core.getUp(2).setType(plainsVillagePopulator.chiseledStoneBrick);
            core.getLeft().Pillar(2, rand, plainsVillagePopulator.chiseledStoneBrick);
            core.getRight().Pillar(2, rand, plainsVillagePopulator.chiseledStoneBrick);

            core.getLeft().getFront().setType(plainsVillagePopulator.stoneBrickWall);
            core.getRight().getFront().setType(plainsVillagePopulator.stoneBrickWall);

            core.getLeft().getFront().getDown().setType(plainsVillagePopulator.stoneBrick);
            core.getRight().getFront().getDown().setType(plainsVillagePopulator.stoneBrick);

            new StairBuilder(plainsVillagePopulator.stoneBrickStairs).setFacing(BlockUtils.getLeft(core.getDirection()))
                                                         .apply(core.getRight().getFront().getUp())
                                                         .setFacing(BlockUtils.getRight(core.getDirection()))
                                                         .apply(core.getLeft().getFront().getUp())
                                                         .setHalf(Half.TOP)
                                                         .apply(core.getRight().getUp(2))
                                                         .setFacing(BlockUtils.getLeft(core.getDirection()))
                                                         .apply(core.getLeft().getUp(2));
        }
        else // Just a hole in a fence
        {
            SimpleEntry<Wall, Integer> entry = this.getRoom().getWall(data, getRotation().getOppositeFace(), 0);
            Wall w = entry.getKey();
            for (int i = 0; i < entry.getValue(); i++) {
                w.getDown(2).downUntilSolid(rand, plainsVillagePopulator.cobblestone, plainsVillagePopulator.mossyCobblestone);

                if (i == 2) {
                    // Opening
                    w.getDown().setType(plainsVillagePopulator.chiseledStoneBrick);
                }
                else if (i == 1 || i == 3) {
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

}
