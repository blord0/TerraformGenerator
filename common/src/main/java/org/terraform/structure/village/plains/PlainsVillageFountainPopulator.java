package org.terraform.structure.village.plains;

import org.bukkit.Axis;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.jetbrains.annotations.NotNull;
import org.terraform.coregen.populatordata.PopulatorDataAbstract;
import org.terraform.data.SimpleBlock;
import org.terraform.data.Wall;
import org.terraform.main.TerraformGeneratorPlugin;
import org.terraform.schematic.SchematicParser;
import org.terraform.schematic.TerraSchematic;
import org.terraform.structure.room.CubeRoom;
import org.terraform.structure.room.RoomPopulatorAbstract;
import org.terraform.utils.BlockUtils;
import org.terraform.utils.GenUtils;

import java.io.FileNotFoundException;
import java.util.Locale;
import java.util.Random;

public class PlainsVillageFountainPopulator extends RoomPopulatorAbstract {

    private static final String[] villageFountainSchems = new String[] {
            "plainsvillage-fountain1", "plainsvillage-fountain2"
    };

    private final PlainsVillagePopulator plainsVillagePopulator;

    public PlainsVillageFountainPopulator(PlainsVillagePopulator plainsVillagePopulator,
                                          Random rand,
                                          boolean forceSpawn,
                                          boolean unique)
    {
        super(rand, forceSpawn, unique);
        this.plainsVillagePopulator = plainsVillagePopulator;
    }

    @Override
    public void populate(@NotNull PopulatorDataAbstract data, @NotNull CubeRoom room) {

        int x = room.getX();
        int z = room.getZ();
        int y = GenUtils.getHighestGround(data, x, z);
        BlockFace roomDir = ((DirectionalCubeRoom) room).getDirection();

        try {
            SimpleBlock core = new SimpleBlock(data, x, y + 1, z);
            TerraSchematic schem = TerraSchematic.load(
                    villageFountainSchems[rand.nextInt(villageFountainSchems.length)],
                    core.getDown()
            );
            schem.parser = new PlainsVillageFountainSchematicParser();
            schem.apply();

            for (int nx = -3; nx <= 3; nx++) {
                for (int nz = -3; nz <= 3; nz++) {
                    Wall target = new Wall(core.getRelative(nx, 0, nz));
                    if (target.getType() == plainsVillagePopulator.cobblestone || target.getType() == plainsVillagePopulator.mossyCobblestone) {
                        target.getDown().downUntilSolid(rand, plainsVillagePopulator.cobblestone, plainsVillagePopulator.mossyCobblestone);
                    }
                    else if (target.getType() == Material.POLISHED_ANDESITE) {
                        target.getDown().downUntilSolid(rand, Material.POLISHED_ANDESITE);
                    }
                }
            }

            Wall w = new Wall(core.getRelative(roomDir, 3), roomDir);

            int pathLength = room.getWidthX() / 2;
            if (BlockUtils.getAxisFromBlockFace(roomDir) == Axis.Z) {
                pathLength = room.getWidthZ() / 2;
            }

            for (int i = 0; i < pathLength - 1; i++) {
                w.getGround().setType(Material.DIRT_PATH);
                w.getLeft().getGround().setType(Material.DIRT_PATH);
                w.getRight().getGround().setType(Material.DIRT_PATH);

                if (GenUtils.chance(rand, 1, 10)) {
                    BlockFace lampFace = BlockUtils.getTurnBlockFace(rand, roomDir);
                    SimpleBlock target = w.getRelative(lampFace, 2).getGround().getUp().get();
                    if (target.getDown().getType() != Material.DIRT_PATH && PlainsVillagePathPopulator.canPlaceLamp(
                            target))
                    {
                        PlainsVillagePathPopulator.placeLamp(rand, target);
                    }
                }

                w = w.getRelative(roomDir);
            }

        }
        catch (FileNotFoundException e) {
            TerraformGeneratorPlugin.logger.stackTrace(e);
        }

    }

    @Override
    public boolean canPopulate(@NotNull CubeRoom room) {
        return room.getWidthX() <= 10;
    }

    private class PlainsVillageFountainSchematicParser extends SchematicParser {
        @Override
        public void applyData(@NotNull SimpleBlock block, @NotNull BlockData data) {
            String name = data.getMaterial().toString();
            if (name.contains("COBBLESTONE")) {
                boolean mossy = GenUtils.chance(rand, 1, 4);
                Material replacement;
                // Pick the shape-matching palette field. Biome stone families (e.g. sandstone)
                // don't have a full 1:1 shape lineup with cobblestone (no smooth_sandstone_wall,
                // no "mossy" sandstone at all), so the replacement material must be resolved per
                // shape rather than via a blind "cobblestone" substring swap, or the result can
                // be a nonexistent block (e.g. smooth_sandstone_wall / mossy_sandstone_stairs).
                if (name.endsWith("_WALL")) {
                    replacement = mossy ? plainsVillagePopulator.mossyCobblestoneWall : plainsVillagePopulator.cobblestoneWall;
                }
                else if (name.endsWith("_STAIRS")) {
                    replacement = mossy ? plainsVillagePopulator.mossyCobblestoneStairs : plainsVillagePopulator.cobblestoneStairs;
                }
                else if (name.endsWith("_SLAB")) {
                    replacement = mossy ? plainsVillagePopulator.mossyCobblestoneSlab : plainsVillagePopulator.cobblestoneSlab;
                }
                else {
                    replacement = mossy ? plainsVillagePopulator.mossyCobblestone : plainsVillagePopulator.cobblestone;
                }
                data = Bukkit.createBlockData(data.getAsString().replaceAll(name.toLowerCase(Locale.ENGLISH),
                        replacement.toString().toLowerCase(Locale.ENGLISH)
                ));
                super.applyData(block, data);
            }
            else if (name.startsWith("OAK_")) {
                data = Bukkit.createBlockData(data.getAsString().replaceAll("oak_", plainsVillagePopulator.wood));
                super.applyData(block, data);
            }
            else {
                super.applyData(block, data);
            }
        }
    }
}
