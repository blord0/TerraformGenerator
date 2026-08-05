package org.terraform.structure.village.plains;

import org.bukkit.Material;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.type.Slab.Type;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.terraform.coregen.bukkit.TerraformGenerator;
import org.terraform.data.SimpleBlock;
import org.terraform.data.SimpleLocation;
import org.terraform.data.Wall;
import org.terraform.main.TerraformGeneratorPlugin;
import org.terraform.structure.room.CubeRoom;
import org.terraform.structure.room.PathPopulatorAbstract;
import org.terraform.structure.room.PathPopulatorData;
import org.terraform.structure.room.RoomPopulatorAbstract;
import org.terraform.utils.BlockUtils;
import org.terraform.utils.GenUtils;
import org.terraform.utils.blockdata.SlabBuilder;

import java.util.*;
import java.util.function.Consumer;

public class PlainsPathRecursiveSpawner {

	private static final int MIN_PATH_LENGTH = 10;                 // Shortest road segment
	private static final int MAX_PATH_LENGTH = 24;                 // Longest road segment
	private static final int MIN_BRANCH_DISTANCE = 6;             // Minimum spacing between branch attempts
	private static final int MAX_BRANCH_DISTANCE = 14;             // Maximum spacing between branch attempts
	private static final int MAX_CROSSROADS = 180;                  // Maximum number of junctions
	private static final int MAX_PATH_BLOCKS = 12000;               // Maximum total road blocks
	private static final int MAX_ROOMS = 350;                      // Maximum building plots
	private static final int BRANCH_CHANCE_PERCENT = 85;           // Chance a branch attempt succeeds
	private static final int T_JUNCTION_CHANCE_PERCENT = 45;       // Chance to branch on both sides
	private static final int MEANDER_CHANCE_PERCENT = 35;          // Chance roads wobble sideways
	private static final int CONTINUE_STRAIGHT_CHANCE_PERCENT = 60;// Chance roads stay straight after a segment
    private final int range;
    private final ArrayList<RoomPopulatorAbstract> validRooms = new ArrayList<>();
    private final @NotNull SimpleBlock core;
    private final HashMap<SimpleLocation, DirectionalCubeRoom> rooms = new HashMap<>();
    private final HashMap<SimpleLocation, BlockFace> path = new HashMap<>();
    private final HashMap<SimpleLocation, CrossRoad> crossRoads = new HashMap<>();
    private int minRoomWidth = 15;
    private int maxRoomWidth = 20;
    /**
     * 1 for max room density, 0 for no rooms.
     */
    private double villageDensity = 1;
    private PathPopulatorAbstract pathPop;
    /**
     * Invoked with each room right before that room is populated, so callers can
     * react to a room's specific location (e.g. re-theme materials to its biome)
     * even though a village may span multiple biomes.
     */
    private @Nullable Consumer<CubeRoom> preRoomPopulateHook;

    public PlainsPathRecursiveSpawner(@NotNull SimpleBlock core, int range, BlockFace... faces) {
        SimpleLocation start = new SimpleLocation(core.getX(), 0, core.getZ());
        crossRoads.put(start, new CrossRoad(start, faces));
        this.range = range;
        this.core = core;
    }


    private void advanceCrossRoad(@NotNull Random random,
                                  @NotNull CrossRoad target,
                                  @NotNull BlockFace direction)
    {
        target.satisfiedFaces.add(direction);

        SimpleLocation lastPlaced = new SimpleLocation(target.loc);
        SimpleLocation loc = new SimpleLocation(target.loc).getRelative(direction);
        int branchCountdown = GenUtils.randInt(random, MIN_BRANCH_DISTANCE, MAX_BRANCH_DISTANCE);
        int meanderCountdown = GenUtils.randInt(random, 4, 9);
        int segmentCount = 0;

        while (segmentCount < 10 && path.size() < MAX_PATH_BLOCKS) {
            int segmentLength = GenUtils.randInt(random, MIN_PATH_LENGTH, MAX_PATH_LENGTH);
            boolean blocked = false;

            for (int i = 0; i < segmentLength && path.size() < MAX_PATH_BLOCKS; i++) {
                if (!isLocationValid(loc)) {
                    blocked = true;
                    break;
                }

                path.put(new SimpleLocation(loc), direction);
                lastPlaced = new SimpleLocation(loc);

                branchCountdown--;
                if (branchCountdown <= 0) {
                    maybeAddNaturalBranch(random, loc, direction);
                    branchCountdown = GenUtils.randInt(random, MIN_BRANCH_DISTANCE, MAX_BRANCH_DISTANCE);
                }
                
                tryRegisterRoom(random, loc, direction);

                meanderCountdown--;
                if (meanderCountdown <= 0
                    && GenUtils.chance(random, MEANDER_CHANCE_PERCENT, 100))
                {
                    BlockFace[] sideFaces = BlockUtils.getAdjacentFaces(direction);
                    BlockFace side = sideFaces[random.nextInt(sideFaces.length)];
                    SimpleLocation shifted = new SimpleLocation(loc).getRelative(side);

                    if (isLocationValid(shifted)) {
                        path.put(new SimpleLocation(shifted), side);
                        lastPlaced = new SimpleLocation(shifted);
                        tryRegisterRoom(random, shifted, direction);
                        loc = new SimpleLocation(shifted).getRelative(direction);
                    }
                    else {
                        loc = new SimpleLocation(loc).getRelative(direction);
                    }

                    meanderCountdown = GenUtils.randInt(random, 4, 9);
                }
                else {
                    loc = new SimpleLocation(loc).getRelative(direction);
                }
            }

            if (blocked) {
                break;
            }

            segmentCount++;

            if (!GenUtils.chance(random, CONTINUE_STRAIGHT_CHANCE_PERCENT, 100)) {
                direction = BlockUtils.getTurnBlockFace(random, direction);
            }

            loc = new SimpleLocation(lastPlaced).getRelative(direction);
        }
    }

    private void maybeAddNaturalBranch(@NotNull Random random,
                                       @NotNull SimpleLocation loc,
                                       @NotNull BlockFace direction)
    {
        if (crossRoads.size() >= MAX_CROSSROADS
            || !GenUtils.chance(random, BRANCH_CHANCE_PERCENT, 100))
        {
            return;
        }

        BlockFace[] sides = BlockUtils.getAdjacentFaces(direction);
        BlockFace chosen = chooseOutwardBranch(random, loc, sides);
        SimpleLocation junction = new SimpleLocation(loc);

        if (GenUtils.chance(random, T_JUNCTION_CHANCE_PERCENT, 100)) {
            crossRoads.putIfAbsent(junction, new CrossRoad(junction, sides));
        }
        else {
            crossRoads.putIfAbsent(junction, new CrossRoad(junction, new BlockFace[]{chosen}));
        }
    }

    private @NotNull BlockFace chooseOutwardBranch(@NotNull Random random,
                                                    @NotNull SimpleLocation loc,
                                                    @NotNull BlockFace[] sides)
    {
        if (random.nextInt(100) < 70) {
            SimpleLocation first = new SimpleLocation(loc).getRelative(sides[0]);
            SimpleLocation second = new SimpleLocation(loc).getRelative(sides[1]);

            double firstDistance = first.distanceSqr(core.getX(), core.getY(), core.getZ());
            double secondDistance = second.distanceSqr(core.getX(), core.getY(), core.getZ());

            if (firstDistance != secondDistance) {
                return firstDistance > secondDistance ? sides[0] : sides[1];
            }
        }

        return sides[random.nextInt(sides.length)];
    }

    private void tryRegisterRoom(@NotNull Random random,
                                 @NotNull SimpleLocation loc,
                                 @NotNull BlockFace direction)
    {
        if (rooms.size() >= MAX_ROOMS
            || random.nextInt(3) != 0
            || !GenUtils.chance(random, (int) (villageDensity * 10000), 10000))
        {
            return;
        }

        BlockFace[] adjacentFaces = BlockUtils.getAdjacentFaces(direction);
        BlockFace adjacentDirection = adjacentFaces[random.nextInt(adjacentFaces.length)];
        SimpleLocation adjacent = new SimpleLocation(loc).getRelative(adjacentDirection);

        if (!isLocationValid(adjacent)) {
            return;
        }

        int roomMinWidth = this.minRoomWidth;
        int roomMaxWidth = this.maxRoomWidth;

        if (GenUtils.chance(random, 18, 100)) {
            roomMinWidth = 7;
            roomMaxWidth = 10;
        }

        int roomWidthX = GenUtils.randInt(random, roomMinWidth, roomMaxWidth);
        int roomWidthZ = GenUtils.randInt(random, roomMinWidth, roomMaxWidth);

        DirectionalCubeRoom room = new DirectionalCubeRoom(
                adjacentDirection.getOppositeFace(),
                roomWidthX,
                roomWidthZ,
                20,
                loc.getX() + adjacentDirection.getModX() * (2 + roomWidthX / 2),
                loc.getY(),
                loc.getZ() + adjacentDirection.getModZ() * (2 + roomWidthZ / 2)
        );

        registerRoom(room);
    }

    public void registerRoomPopulator(RoomPopulatorAbstract roomPop) {
        validRooms.add(roomPop);
    }


    /**
     * @return whether or not a location can hold a cuberoom (no overlaps, not too far etc)
     */
    private boolean isLocationValid(@NotNull SimpleLocation loc) {
        for (DirectionalCubeRoom room : rooms.values()) {
            if (room.isPointInside(loc)) {
                // TerraformGeneratorPlugin.logger.info("Point was inside room. Dying.");
                return false;
            }
        }
        if (loc.distanceSqr(core.getX(), core.getY(), core.getZ()) > Math.pow(range, 2)) {
            // TerraformGeneratorPlugin.logger.info("Point was beyond range. Dying.");
            return false;
        }
        // TerraformGeneratorPlugin.logger.info("Point already exists in the path registry");
        return !path.containsKey(loc);
    }

    public boolean registerRoom(@NotNull DirectionalCubeRoom room) {
        // Cannot be below sea level
        if (core.getPopData()
                .getType(room.getX(),
                        GenUtils.getHighestGround(core.getPopData(), room.getX(), room.getZ()) + 1,
                        room.getZ()) == Material.WATER)
        {
            return false;
        }

        // Cannot overlap another room
        for (DirectionalCubeRoom other : rooms.values()) {
            if (other.isOverlapping(room)) {
                return false;
            }
        }

        // Don't be inside a pathway
        for (SimpleLocation loc : path.keySet()) {
            if (room.isPointInside(loc)) {
                return false;
            }
        }
        rooms.put(new SimpleLocation(room.getX(), 0, room.getZ()), room);
        return true;
    }

    public void forceRegisterRoom(@NotNull DirectionalCubeRoom room) {
        rooms.put(new SimpleLocation(room.getX(), 0, room.getZ()), room);
    }

    public void generate(@NotNull Random random) {
        while (getFirstUnsatisfiedCrossRoad() != null) {
            CrossRoad target = getFirstUnsatisfiedCrossRoad();
            BlockFace direction = target.getFirstUnsatisfiedDirection();
            advanceCrossRoad(random, target, direction);
        }
    }

    @SuppressWarnings("unchecked")
    public void build(@NotNull Random random) {

        // place pathways
        for (SimpleLocation loc : path.keySet()) {
            Wall w = new Wall(new SimpleBlock(core.getPopData(), loc.getX(), loc.getY(), loc.getZ()), path.get(loc));
            w = w.getGround();
            if (BlockUtils.isWet(w.getUp().get())) {

                // Paths underwater are wood planks.            	
                if (BlockUtils.isWet(w.getAtY(TerraformGenerator.seaLevel))) {
                    w = w.getAtY(TerraformGenerator.seaLevel);
                }
                else {
                    w = w.getGroundOrDry().getDown();
                }

                new SlabBuilder(Material.OAK_SLAB).setWaterlogged(true)
                                                  .setType(Type.TOP)
                                                  .apply(w)
                                                  .lapply(w.getRelative(0, 0, 1))
                                                  .lapply(w.getRelative(0, 0, -1))
                                                  .lapply(w.getRelative(1, 0, 1))
                                                  .lapply(w.getRelative(1, 0, -1))
                                                  .lapply(w.getRelative(-1, 0, 1))
                                                  .lapply(w.getRelative(-1, 0, -1))
                                                  .lapply(w.getRelative(1, 0, 0))
                                                  .lapply(w.getRelative(-1, 0, 0));
                // Bukkit.getLogger().info("Underwater path at " + w.get().getVector() + " of type " + w.getType().toString());

                continue;
            }

            // Remove foilage before placement
            if (!w.getUp(2).isSolid() && w.getUp(2).getType() != Material.AIR) {
                w.getUp(2).setType(Material.AIR);
            }

            if (!w.getUp().isSolid() && w.getUp().getType() != Material.AIR) {
                w.getUp().setType(Material.AIR);
            }


            w.setType(Material.DIRT_PATH);

            for (BlockFace face : BlockUtils.xzPlaneBlockFaces) {
                Wall target = w.getRelative(face).getGround();
                // Remove foilage before placement
                if (random.nextInt(3) != 0) {
                    if (!target.getUp(2).isSolid() && target.getUp(2).getType() != Material.AIR) {
                        target.getUp(2).setType(Material.AIR);
                    }

                    if (!target.getUp().isSolid() && target.getUp().getType() != Material.AIR) {
                        target.getUp().setType(Material.AIR);
                    }

                    target.setType(Material.DIRT_PATH);
                }
            }
        }
        if (validRooms.isEmpty()) {
            return;
        }

        // Allocate room populators
        Iterator<RoomPopulatorAbstract> it = validRooms.iterator();
        while (it.hasNext()) {
            RoomPopulatorAbstract pops = it.next();
            if (pops.isForceSpawn()) {
                for (CubeRoom room : rooms.values()) {
                    if (room.getPop() == null && pops.canPopulate(room)) {
                        room.setRoomPopulator(pops);
                        if (pops.isUnique()) {
                            it.remove();
                        }
                        break;
                    }
                }
            }
        }

        if (validRooms.isEmpty()) {
            return;
        }

        // Apply room populators
        for (CubeRoom room : rooms.values()) {
            if (room.getPop() == null) {
                List<RoomPopulatorAbstract> shuffled = (List<RoomPopulatorAbstract>) validRooms.clone();
                Collections.shuffle(shuffled, random);
                for (RoomPopulatorAbstract roomPop : shuffled) {
                    if (roomPop.canPopulate(room)) {
                        room.setRoomPopulator(roomPop);
                        if (roomPop.isUnique()) {
                            validRooms.remove(roomPop);
                        }

                        break;
                    }
                }
            }
            if (room.getPop() != null) {
                TerraformGeneratorPlugin.logger.info("Registered: "
                                                     + room.getPop().getClass().getName()
                                                     + " at "
                                                     + room.getX()
                                                     + " "
                                                     + room.getY()
                                                     + " "
                                                     + room.getZ()
                                                     + " in a room of size "
                                                     + room.getWidthX()
                                                     + "x"
                                                     + room.getWidthZ());
                if (preRoomPopulateHook != null) {
                    preRoomPopulateHook.accept(room);
                }
                room.populate(core.getPopData());
            }
        }

        // Populate pathways
        for (SimpleLocation loc : path.keySet()) {
            if (pathPop != null) {
                pathPop.populate(new PathPopulatorData(new SimpleBlock(
                        core.getPopData(),
                        loc.getX(),
                        loc.getY(),
                        loc.getZ()
                ), path.get(loc), 3, false));
            }
        }

    }

    private @Nullable CrossRoad getFirstUnsatisfiedCrossRoad() {
        for (CrossRoad road : crossRoads.values()) {
            if (!road.isSatisfied()) {
                return road;
            }
        }
        return null;
    }

    public int getMinRoomWidth() {
        return minRoomWidth;
    }

    public void setMinRoomWidth(int minRoomWidth) {
        this.minRoomWidth = minRoomWidth;
    }

    public int getMaxRoomWidth() {
        return maxRoomWidth;
    }

    public void setMaxRoomWidth(int maxRoomWidth) {
        this.maxRoomWidth = maxRoomWidth;
    }

    public PathPopulatorAbstract getPathPop() {
        return pathPop;
    }

    public void setPathPop(PathPopulatorAbstract pathPop) {
        this.pathPop = pathPop;
    }

    public void setPreRoomPopulateHook(@Nullable Consumer<CubeRoom> preRoomPopulateHook) {
        this.preRoomPopulateHook = preRoomPopulateHook;
    }

    public @NotNull HashMap<SimpleLocation, DirectionalCubeRoom> getRooms() {
        return rooms;
    }

    public double getVillageDensity() {
        return villageDensity;
    }

    public void setVillageDensity(double villageDensity) {
        this.villageDensity = villageDensity;
    }

    private static class CrossRoad {
        public final SimpleLocation loc;
        public final BlockFace[] faces;
        public final @NotNull ArrayList<BlockFace> satisfiedFaces = new ArrayList<>();

        public CrossRoad(SimpleLocation loc, BlockFace[] faces) {
            this.loc = loc;
            this.faces = faces;
        }

        public boolean isSatisfied() {
            return getFirstUnsatisfiedDirection() == null;
        }

        public @Nullable BlockFace getFirstUnsatisfiedDirection() {
            for (BlockFace face : faces) {
                if (!satisfiedFaces.contains(face)) {
                    return face;
                }
            }
            return null;
        }
    }

}
