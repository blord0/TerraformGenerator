package org.terraform.command;

import org.bukkit.ChatColor;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.terraform.biome.cavepopulators.cluster.CaveClusterRegistry;
import org.terraform.biome.cavepopulators.noisecluster.SulfurNoiseClusterPopulator;
import org.terraform.command.contants.InvalidArgumentException;
import org.terraform.command.contants.TerraCommand;
import org.terraform.command.contants.TerraCommandArgument;
import org.terraform.data.CoordPair;
import org.terraform.data.TerraformWorld;
import org.terraform.main.LangOpt;
import org.terraform.main.TerraformGeneratorPlugin;
import org.terraform.main.config.TConfig;
import org.terraform.structure.StructureLocator;
import org.terraform.structure.ancientcity.AncientCityPopulator;
import org.terraform.utils.GenUtils;
import org.terraform.utils.version.V_26_2;
import org.terraform.utils.version.Version;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.Stack;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Sulfur/dripstone/lush/crystalline caves and the deep dark are all painted
 * on post-population (or, for deep dark, carved as part of the Ancient City),
 * so none of them are BiomeBank entries and none can be found with
 * /terra locatebiome. This covers all five with the search strategy that
 * actually matches how each one decides where to spawn:
 *  - SULFUR: a noise-threshold pre-filter (SulfurNoiseClusterPopulator#canSpawnCluster)
 *    narrows candidates for free, then each candidate chunk is actually
 *    generated and its real biome data is read back to confirm a genuine
 *    sulfur cave is there before reporting it - see verifyChunkForSulfur.
 *  - DRIPSTONE/LUSH/CRYSTALLINE: a deterministic per-chunk grid of candidate
 *    points (the same math MasterCavePopulatorDistributor#calculateClusterLocations
 *    uses), via CaveClusterRegistry.
 *  - DEEP_DARK: it only ever appears inside Ancient Cities, so this just
 *    delegates to the structure locator for AncientCityPopulator.
 *
 * DRIPSTONE/LUSH/CRYSTALLINE/DEEP_DARK results are approximate: a "found"
 * location is one where the generator's own placement math says a cluster
 * is a candidate, not a guarantee that an explorable cave already exists
 * there - players still need to dig. SULFUR is the exception: its reported
 * location is verified against real generated world data.
 */
public class LocateCaveBiomeCommand extends TerraCommand {

    private static final int NOISE_SEARCH_RADIUS = 5000;
    private static final int NOISE_BLOCK_SKIP = 24;
    private static final int NOISE_Y_STEP = 12;

    private static final int MAX_VERIFY_ATTEMPTS = 20;
    private static final long VERIFY_TIMEOUT_SECONDS = 30;

    private static final int GRID_SEARCH_CHUNK_RADIUS = 400;

    private static final String APPROXIMATE_CAVEAT =
            " (unconfirmed, might not exist)";
    private static final String VERIFIED_CAVEAT =
            " (confirmed, guaranteed to exist)";

    private final SulfurNoiseClusterPopulator sulfurPopulator = new SulfurNoiseClusterPopulator();

    public LocateCaveBiomeCommand(TerraformGeneratorPlugin plugin, String... aliases) {
        super(plugin, aliases);
        this.parameters.add(new CaveBiomeTypeArgument("caveBiome", false));
    }

    @Override
    public @NotNull String getDefaultDescription() {
        return "Tries to locate a cave-interior biome (sulfur/dripstone/lush/crystalline/deep dark)";
    }

    @Override
    public boolean canConsoleExec() {
        return false;
    }

    @Override
    public boolean hasPermission(@NotNull CommandSender sender) {
        return sender.isOp() || sender.hasPermission("terraformgenerator.locatecavebiome");
    }

    @Override
    public void execute(@NotNull CommandSender sender, @NotNull Stack<String> args) throws InvalidArgumentException {
        Player p = (Player) sender;

        if (args.isEmpty()) {
            printUsage(sender);
            return;
        }

        CaveBiomeType type = (CaveBiomeType) this.parseArguments(sender, args).get(0);
        TerraformWorld tw = TerraformWorld.get(p.getWorld());

        switch (type) {
            case SULFUR -> locateSulfur(sender, tw, p);
            case DRIPSTONE -> locateGridCluster(sender, tw, p, CaveClusterRegistry.DRIPSTONE, "Dripstone Caves");
            case LUSH -> locateGridCluster(sender, tw, p, CaveClusterRegistry.LUSH, "Lush Caves");
            case CRYSTALLINE -> locateGridCluster(sender, tw, p, CaveClusterRegistry.CRYSTALLINE, "Crystalline Cluster");
            case DEEP_DARK -> locateDeepDark(sender, tw, p);
        }
    }

    private void printUsage(@NotNull CommandSender sender) {
        sender.sendMessage(LangOpt.COMMAND_LOCATEBIOME_INVALIDBIOME.parse());
        StringBuilder types = new StringBuilder();
        boolean b = true;
        for (CaveBiomeType type : CaveBiomeType.values()) {
            ChatColor col = b ? ChatColor.DARK_RED : ChatColor.RED;
            b = !b;
            types.append(col).append(type).append(' ');
        }
        sender.sendMessage(types.toString());
    }

    // =========================
    // SULFUR - noise cluster
    // =========================

    private void locateSulfur(@NotNull CommandSender sender, @NotNull TerraformWorld tw, @NotNull Player p) {
        if (!Version.VERSION.isAtLeast(Version.v26_2)
            || !TConfig.areCavesEnabled()
            || !TConfig.c.FEATURE_CAVEDECORATORS_ENABLED)
        {
            sender.sendMessage(LangOpt.COMMAND_LOCATECAVEBIOME_DISABLED.parse("%biome%", "Sulfur Caves"));
            return;
        }

        int startX = p.getLocation().getBlockX();
        int startZ = p.getLocation().getBlockZ();
        UUID uuid = p.getUniqueId();

        sender.sendMessage(LangOpt.COMMAND_LOCATE_SEARCHING.parse());

        World world = tw.getWorld();

        TerraformGeneratorPlugin.taskScheduler.execAsync(() -> {
            long startTime = System.currentTimeMillis();
            int[] loc = searchAndVerifySulfurCluster(tw, world, startX, startZ);
            reportResult(uuid,
                    "Sulfur Caves",
                    startTime,
                    loc == null ? null : new CoordPair(loc[0], loc[2]),
                    loc == null ? -1 : loc[1],
                    tw,
                    VERIFIED_CAVEAT
            );
        });
    }

    /**
     * Drives the outward spiral over the free noise pre-filter
     * (checkNoiseColumn), and for every column that passes it, actually
     * generates that one chunk and reads its real biome data back
     * (verifyChunkForSulfur) to confirm a genuine sulfur cave is there.
     * Noise false-positives (no real cave there, or a competing
     * dripstone/lush/crystalline cluster won that column during real
     * generation) are skipped in favor of the next candidate, up to
     * MAX_VERIFY_ATTEMPTS real chunk generations.
     */
    private int[] searchAndVerifySulfurCluster(@NotNull TerraformWorld tw, @NotNull World world, int centerX, int centerZ) {
        Set<Long> verifiedChunks = new HashSet<>();
        int[] attempts = {0};
        int[][] found = {null};

        spiralColumns(centerX, centerZ, (x, z) -> {
            int[] noiseHit = checkNoiseColumn(tw, x, z);
            if (noiseHit == null) {
                return false;
            }

            long chunkKey = ((long) (noiseHit[0] >> 4) << 32) | ((noiseHit[2] >> 4) & 0xFFFFFFFFL);
            if (!verifiedChunks.add(chunkKey)) {
                return false; // already tried this chunk this search
            }

            attempts[0]++;
            int[] verified = verifyChunkForSulfur(world, tw, noiseHit[0], noiseHit[2]);
            if (verified != null) {
                found[0] = verified;
                return true;
            }
            return attempts[0] >= MAX_VERIFY_ATTEMPTS;
        });

        return found[0];
    }

    /**
     * Forces generation of the chunk containing (x,z) via the correct
     * Folia-safe scheduler primitive, then reads its real generated biome
     * data back on the thread that owns it. Blocks the calling (background)
     * thread until that completes - safe since this always runs off the
     * async search task, never the main/region thread.
     */
    private int[] verifyChunkForSulfur(@NotNull World world, @NotNull TerraformWorld tw, int x, int z) {
        int chunkX = x >> 4;
        int chunkZ = z >> 4;
        CompletableFuture<int[]> future = new CompletableFuture<>();

        TerraformGeneratorPlugin.taskScheduler.execAsyncRegion(world, chunkX, chunkZ, () -> {
            try {
                future.complete(scanChunkForSulfurBiome(world, tw, chunkX, chunkZ));
            }
            catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });

        try {
            return future.get(VERIFY_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
        catch (Exception e) {
            TerraformGeneratorPlugin.logger.error(
                    "[LocateCaveBiome] Sulfur verification failed for chunk (" + chunkX + "," + chunkZ + "): " + e);
            return null;
        }
    }

    /**
     * Must only be called from the region thread owning (chunkX, chunkZ) -
     * i.e. from inside the execAsyncRegion callback in verifyChunkForSulfur.
     * Biomes are stored at 4-block (quart) resolution in modern Minecraft,
     * so stepping by 4 loses nothing - it's exactly the real storage
     * granularity, not an approximation.
     */
    private int[] scanChunkForSulfurBiome(@NotNull World world, @NotNull TerraformWorld tw, int chunkX, int chunkZ) {
        int baseX = chunkX << 4;
        int baseZ = chunkZ << 4;
        for (int y = tw.minY; y < tw.maxY; y += 4) {
            for (int dx = 0; dx < 16; dx += 4) {
                for (int dz = 0; dz < 16; dz += 4) {
                    if (world.getBiome(baseX + dx, y, baseZ + dz) == V_26_2.SULFUR_CAVES) {
                        return new int[]{baseX + dx, y, baseZ + dz};
                    }
                }
            }
        }
        return null;
    }

    @FunctionalInterface
    private interface ColumnVisitor {
        /**
         * @return true to stop spiraling (terminal result found, or a cap was hit)
         */
        boolean visit(int x, int z);
    }

    /**
     * Spirals outward from (centerX, centerZ) in NOISE_BLOCK_SKIP steps, up
     * to NOISE_SEARCH_RADIUS, calling visitor at each column until it
     * returns true or the radius is exhausted.
     */
    private void spiralColumns(int centerX, int centerZ, @NotNull ColumnVisitor visitor) {
        if (visitor.visit(centerX, centerZ)) {
            return;
        }

        int x = centerX;
        int z = centerZ;
        int iter = 2;

        while (Math.max(Math.abs(centerX - x), Math.abs(centerZ - z)) < NOISE_SEARCH_RADIUS) {
            for (int i = 0; i < iter / 2; i++) {
                switch (iter % 4) {
                    case 0 -> x += NOISE_BLOCK_SKIP;
                    case 1 -> z -= NOISE_BLOCK_SKIP;
                    case 2 -> x -= NOISE_BLOCK_SKIP;
                    case 3 -> z += NOISE_BLOCK_SKIP;
                }
            }

            if (visitor.visit(x, z)) {
                return;
            }
            iter++;
        }
    }

    private int[] checkNoiseColumn(@NotNull TerraformWorld tw, int x, int z) {
        int minY = tw.minY + 32; // matches the cutoff MasterCavePopulatorDistributor uses for DeepCavePopulator
        for (int y = minY; y < tw.maxY; y += NOISE_Y_STEP) {
            if (sulfurPopulator.canSpawnCluster(tw, x, y, z)) {
                return new int[]{x, y, z};
            }
        }
        return null;
    }

    // =========================
    // DRIPSTONE / LUSH / CRYSTALLINE - grid clusters
    // =========================

    private void locateGridCluster(@NotNull CommandSender sender,
                                   @NotNull TerraformWorld tw,
                                   @NotNull Player p,
                                   @NotNull CaveClusterRegistry type,
                                   @NotNull String displayName)
    {
        if (!TConfig.areCavesEnabled() || !TConfig.c.FEATURE_CAVECLUSTERS_ENABLED) {
            sender.sendMessage(LangOpt.COMMAND_LOCATECAVEBIOME_DISABLED.parse("%biome%", displayName));
            return;
        }

        int startChunkX = p.getLocation().getBlockX() >> 4;
        int startChunkZ = p.getLocation().getBlockZ() >> 4;
        UUID uuid = p.getUniqueId();

        sender.sendMessage(LangOpt.COMMAND_LOCATE_SEARCHING.parse());

        TerraformGeneratorPlugin.taskScheduler.execAsync(() -> {
            long startTime = System.currentTimeMillis();
            CoordPair loc = searchGridCluster(tw, type, startChunkX, startChunkZ);
            reportResult(uuid, displayName, startTime, loc, -1, tw);
        });
    }

    /**
     * Spirals outward chunk-by-chunk from the player, replaying the exact
     * grid-candidate math MasterCavePopulatorDistributor#calculateClusterLocations
     * uses to decide which chunks are eligible for a cluster of this type.
     * This only reproduces the candidate location, not whether an actual
     * cave exists in that chunk column, whether it wins out over an
     * overlapping candidate of another cluster type, or which Y it lands on -
     * all of that is resolved against real generated chunk data that this
     * command deliberately avoids touching (to stay fast and not force
     * chunk generation).
     */
    private CoordPair searchGridCluster(@NotNull TerraformWorld tw, @NotNull CaveClusterRegistry type, int centerChunkX, int centerChunkZ) {
        CoordPair hit = checkGridChunk(tw, type, centerChunkX, centerChunkZ);
        if (hit != null) {
            return hit;
        }

        int cx = centerChunkX;
        int cz = centerChunkZ;
        int iter = 2;

        while (Math.max(Math.abs(centerChunkX - cx), Math.abs(centerChunkZ - cz)) < GRID_SEARCH_CHUNK_RADIUS) {
            for (int i = 0; i < iter / 2; i++) {
                switch (iter % 4) {
                    case 0 -> cx += 1;
                    case 1 -> cz -= 1;
                    case 2 -> cx -= 1;
                    case 3 -> cz += 1;
                }
            }

            hit = checkGridChunk(tw, type, cx, cz);
            if (hit != null) {
                return hit;
            }
            iter++;
        }

        return null;
    }

    private CoordPair checkGridChunk(@NotNull TerraformWorld tw, @NotNull CaveClusterRegistry type, int chunkX, int chunkZ) {
        CoordPair[] positions = GenUtils.vectorRandomObjectPositions(
                tw.getHashedRand(chunkX, type.getHashSeed(), chunkZ).nextInt(9999999),
                chunkX,
                chunkZ,
                type.getSeparation(),
                type.getPertub()
        );
        return positions.length > 0 ? positions[0] : null;
    }

    // =========================
    // DEEP_DARK - only spawns inside Ancient Cities
    // =========================

    private void locateDeepDark(@NotNull CommandSender sender, @NotNull TerraformWorld tw, @NotNull Player p) {
        AncientCityPopulator populator = new AncientCityPopulator();
        if (!populator.isEnabled()) {
            sender.sendMessage(LangOpt.COMMAND_LOCATECAVEBIOME_DISABLED.parse("%biome%", "Deep Dark"));
            return;
        }

        int startX = p.getLocation().getBlockX();
        int startZ = p.getLocation().getBlockZ();
        UUID uuid = p.getUniqueId();

        sender.sendMessage(LangOpt.COMMAND_LOCATE_SEARCHING.parse());

        TerraformGeneratorPlugin.taskScheduler.execAsync(() -> {
            long startTime = System.currentTimeMillis();
            int[] coords = StructureLocator.locateSingleMegaChunkStructure(tw, startX, startZ, populator, -1);
            reportResult(uuid, "Deep Dark", startTime, coords == null ? null : new CoordPair(coords[0], coords[1]), -1, tw);
        });
    }

    // =========================
    // Shared reporting
    // =========================

    private void reportResult(@NotNull UUID uuid, @NotNull String displayName, long startTime, CoordPair loc, int knownY, @NotNull TerraformWorld tw) {
        reportResult(uuid, displayName, startTime, loc, knownY, tw, APPROXIMATE_CAVEAT);
    }

    private void reportResult(@NotNull UUID uuid,
                              @NotNull String displayName,
                              long startTime,
                              CoordPair loc,
                              int knownY,
                              @NotNull TerraformWorld tw,
                              @NotNull String caveat)
    {
        long timeTaken = System.currentTimeMillis() - startTime;
        syncSendMessage(uuid, "Locate " + displayName, LangOpt.COMMAND_LOCATE_COMPLETED_TASK.parse("%time%", timeTaken + ""));

        if (loc == null) {
            syncSendMessage(uuid,
                    "Locate " + displayName,
                    LangOpt.COMMAND_LOCATECAVEBIOME_NOT_FOUND.parse("%biome%", displayName)
            );
            return;
        }

        int y = knownY >= 0 ? knownY : getHighestY(tw, loc.x(), loc.z());

        syncSendMessageTP(uuid,
                "Locate " + displayName,
                LangOpt.COMMAND_LOCATE_LOCATE_COORDS.parse("%x%", loc.x() + "", "%z%", loc.z() + "") + caveat,
                loc.x(),
                y,
                loc.z()
        );
    }

    public enum CaveBiomeType {
        SULFUR,
        DRIPSTONE,
        LUSH,
        CRYSTALLINE,
        DEEP_DARK
    }

    private static class CaveBiomeTypeArgument extends TerraCommandArgument<CaveBiomeType> {
        public CaveBiomeTypeArgument(String name, boolean isOptional) {
            super(name, isOptional);
        }

        @Override
        public @NotNull CaveBiomeType parse(CommandSender sender, @NotNull String value) {
            return CaveBiomeType.valueOf(value.toUpperCase(Locale.ENGLISH));
        }

        @Override
        public @NotNull String validate(CommandSender sender, @NotNull String value) {
            try {
                parse(sender, value);
            }
            catch (IllegalArgumentException e) {
                return "That cave biome does not exist!";
            }
            return "";
        }

        @Override
        public @NotNull ArrayList<String> getTabOptions(String @NotNull [] args) {
            if (args.length != 2) {
                return new ArrayList<>();
            }
            ArrayList<String> values = new ArrayList<>();
            for (CaveBiomeType type : CaveBiomeType.values()) {
                if (type.name().startsWith(args[1].toUpperCase(Locale.ENGLISH))) {
                    values.add(type.name());
                }
            }
            return values;
        }
    }
}
