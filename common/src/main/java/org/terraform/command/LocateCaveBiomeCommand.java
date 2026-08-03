package org.terraform.command;

import org.bukkit.ChatColor;
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
import org.terraform.utils.version.Version;

import java.util.ArrayList;
import java.util.Locale;
import java.util.Stack;
import java.util.UUID;

/**
 * Sulfur/dripstone/lush/crystalline caves and the deep dark are all painted
 * on post-population (or, for deep dark, carved as part of the Ancient City),
 * so none of them are BiomeBank entries and none can be found with
 * /terra locatebiome. This covers all five with the search strategy that
 * actually matches how each one decides where to spawn:
 *  - SULFUR: a noise-threshold check (SulfurNoiseClusterPopulator#canSpawnCluster).
 *  - DRIPSTONE/LUSH/CRYSTALLINE: a deterministic per-chunk grid of candidate
 *    points (the same math MasterCavePopulatorDistributor#calculateClusterLocations
 *    uses), via CaveClusterRegistry.
 *  - DEEP_DARK: it only ever appears inside Ancient Cities, so this just
 *    delegates to the structure locator for AncientCityPopulator.
 *
 * All of these are approximate: a "found" location is one where the
 * generator's own placement math says a cluster/pocket is a candidate, not
 * a guarantee that an explorable cave already exists there. Players still
 * need to dig.
 */
public class LocateCaveBiomeCommand extends TerraCommand {

    private static final int NOISE_SEARCH_RADIUS = 5000;
    private static final int NOISE_BLOCK_SKIP = 24;
    private static final int NOISE_Y_STEP = 12;

    private static final int GRID_SEARCH_CHUNK_RADIUS = 400;

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

        TerraformGeneratorPlugin.taskScheduler.execAsync(() -> {
            long startTime = System.currentTimeMillis();
            int[] loc = searchNoiseCluster(tw, startX, startZ);
            reportResult(uuid, "Sulfur Caves", startTime, loc == null ? null : new CoordPair(loc[0], loc[2]), loc == null ? -1 : loc[1], tw);
        });
    }

    /**
     * Spirals outward from the player, sampling the same noise field the
     * populator checks against at world-gen time. Purely noise-based (no
     * chunk access), so this doesn't force generation and doesn't guarantee
     * an actual open cave exists at the reported spot - just that the
     * sulfur-cave noise threshold is met there.
     */
    private int[] searchNoiseCluster(@NotNull TerraformWorld tw, int centerX, int centerZ) {
        int[] hit = checkNoiseColumn(tw, centerX, centerZ);
        if (hit != null) {
            return hit;
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

            hit = checkNoiseColumn(tw, x, z);
            if (hit != null) {
                return hit;
            }
            iter++;
        }

        return null;
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
                LangOpt.COMMAND_LOCATE_LOCATE_COORDS.parse("%x%", loc.x() + "", "%z%", loc.z() + "")
                + " (approximate candidate - dig in, this doesn't guarantee an open cave)",
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
