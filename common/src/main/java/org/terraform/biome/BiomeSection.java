package org.terraform.biome;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.terraform.data.SimpleLocation;
import org.terraform.data.TerraformWorld;
import org.terraform.main.TerraformGeneratorPlugin;
import org.terraform.main.config.TConfig;
import org.terraform.utils.GenUtils;
import org.terraform.utils.noise.FastNoise;
import org.terraform.utils.noise.FastNoise.NoiseType;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class BiomeSection {
    // A BiomeSection is 128 blocks wide (Default of bitshift 7).
    public static final int bitshifts = TConfig.c.BIOME_SECTION_BITSHIFTS;
    public static final int sectionWidth = 1 << bitshifts;
    public static final int minSize = sectionWidth;
    public static final int dominanceThreshold = (int) (0.35 * sectionWidth);
    public static final int dominanceThresholdSquared = dominanceThreshold*dominanceThreshold;
    private final int x;
    private final int z;
    private final TerraformWorld tw;
    private float temperature;
    private float moisture;
    private int radius;
    private @Nullable BiomeBank biome;
    private FastNoise shapeNoise;

    // Macro warp bends the climate/ocean/mountain sampling fields without moving
    // the physical BiomeSection used by SectionBlurCache.
    private static final float MACRO_WARP_FREQUENCY = 0.0007f;
    private static final double MACRO_WARP_STRENGTH = 320.0;

    // Detail warp breaks up smaller local patterns.
    private static final float DETAIL_WARP_FREQUENCY = 0.0025f;
    private static final double DETAIL_WARP_STRENGTH = 30.0;

    // Shift section dominance centres only a limited amount. Keeping this below
    // half a section ensures only nearby physical sections can dominate.
    private static final double DOMINANCE_CENTER_SHIFT_FACTOR = 0.32;

    private static final ConcurrentHashMap<String, BiomeWarpNoise> WARP_NOISE =
            new ConcurrentHashMap<>();

    /**
     * Block x and z
     */
    protected BiomeSection(TerraformWorld tw, int x, int z) {
        this.x = x >> bitshifts;
        this.z = z >> bitshifts;
        this.tw = tw;
    }

    protected BiomeSection(TerraformWorld tw, int x, int z, boolean useSectionCoords) {
        this.x = x;
        this.z = z;
        this.tw = tw;
    }

    private static final class BiomeWarpNoise {
        private final FastNoise macroX;
        private final FastNoise macroZ;
        private final FastNoise detailX;
        private final FastNoise detailZ;
        private final FastNoise centerX;
        private final FastNoise centerZ;

        private BiomeWarpNoise(long seed) {
            macroX = createNoise((int) (seed ^ 0x4F1BBCDCL), MACRO_WARP_FREQUENCY, 2);
            macroZ = createNoise((int) (seed ^ 0x7A2D8E91L), MACRO_WARP_FREQUENCY, 2);
            detailX = createNoise((int) (seed ^ 0x19C6F35BL), DETAIL_WARP_FREQUENCY, 3);
            detailZ = createNoise((int) (seed ^ 0x63E8A7D2L), DETAIL_WARP_FREQUENCY, 3);

            // Section-centre movement uses broader noise so neighbouring centres
            // drift coherently rather than jittering independently.
            centerX = createNoise((int) (seed ^ 0x31A54C79L), 0.0025f, 2);
            centerZ = createNoise((int) (seed ^ 0x6D2B79F1L), 0.0025f, 2);
        }
    }

    private static @NotNull FastNoise createNoise(int seed, float frequency, int octaves) {
        FastNoise noise = new FastNoise(seed);
        noise.SetNoiseType(NoiseType.SimplexFractal);
        noise.SetFrequency(frequency);
        noise.SetFractalOctaves(octaves);
        return noise;
    }

    private static @NotNull BiomeWarpNoise getWarpNoise(@NotNull TerraformWorld tw) {
        String key = tw.getName() + ":" + tw.getSeed();
        return WARP_NOISE.computeIfAbsent(key, ignored -> new BiomeWarpNoise(tw.getSeed()));
    }

    /**
     * Returns warped block coordinates used only when sampling climate,
     * ocean and mountain noise. Physical section coordinates are unchanged.
     */
    private int @NotNull [] getWarpedSampleBlockCoords() {
        BiomeWarpNoise noise = getWarpNoise(tw);
        SimpleLocation physicalCenter = getCenter();
        int rawX = physicalCenter.getX();
        int rawZ = physicalCenter.getZ();

        double offsetX =
                noise.macroX.GetNoise(rawX, rawZ) * MACRO_WARP_STRENGTH
                + noise.detailX.GetNoise(rawX, rawZ) * DETAIL_WARP_STRENGTH;

        double offsetZ =
                noise.macroZ.GetNoise(rawX, rawZ) * MACRO_WARP_STRENGTH
                + noise.detailZ.GetNoise(rawX, rawZ) * DETAIL_WARP_STRENGTH;

        return new int[] {
                rawX + (int) Math.round(offsetX),
                rawZ + (int) Math.round(offsetZ)
        };
    }

    /**
     * Returns a safely shifted centre used only for dominance comparisons.
     * Bounds and cache ownership still use the physical section.
     */
    private @NotNull SimpleLocation getDominanceCenter() {
        BiomeWarpNoise noise = getWarpNoise(tw);
        SimpleLocation center = getCenter();
        double maxShift = sectionWidth * DOMINANCE_CENTER_SHIFT_FACTOR;

        int shiftedX = center.getX()
                + (int) Math.round(noise.centerX.GetNoise(center.getX(), center.getZ()) * maxShift);
        int shiftedZ = center.getZ()
                + (int) Math.round(noise.centerZ.GetNoise(center.getX(), center.getZ()) * maxShift);

        return new SimpleLocation(shiftedX, 0, shiftedZ);
    }

    /**
     * @return the width * width closest biome sections to this block point.
     */
    public static @NotNull Collection<BiomeSection> getSurroundingSections(TerraformWorld tw,
                                                                           int width,
                                                                           int blockX,
                                                                           int blockZ)
    {
        BiomeSection homeSection = BiomeBank.getBiomeSectionFromBlockCoords(tw, blockX, blockZ);
        Collection<BiomeSection> sections = new ArrayList<>();

        SimpleLocation center = homeSection.getCenter();
        int startX, startZ;
        if (width % 2 == 1) {
            startX = startZ = -width / 2;
        }
        else {
            startX = blockX >= center.getX() ? -width / 2 - 1 : -width / 2;
            startZ = blockZ >= center.getZ() ? -width / 2 - 1 : -width / 2;
        }

        for (int rx = startX; rx < startX + width; rx++) {
            for (int rz = startZ; rz < startZ + width; rz++) {
                sections.add(homeSection.getRelative(rx, rz));
            }
        }

        if (sections.size() != width * width) {
            TerraformGeneratorPlugin.logger.error("Section size was not " + (width * width) + ".");
        }

        return sections;
    }

    /**
     * @return the four closest biome sections to this block point
     */
    public static @NotNull Collection<BiomeSection> getSurroundingSections(TerraformWorld tw, int blockX, int blockZ) {
        Collection<BiomeSection> sections = new ArrayList<>();

        BiomeSection homeBiome = BiomeBank.getBiomeSectionFromBlockCoords(tw, blockX, blockZ);
        sections.add(homeBiome);

        SimpleLocation center = homeBiome.getCenter();
        if (blockX >= center.getX()) {
            if (blockZ >= center.getZ()) {
                sections.add(homeBiome.getRelative(1, 0));
                sections.add(homeBiome.getRelative(1, 1));
                sections.add(homeBiome.getRelative(0, 1));
            }
            else {
                sections.add(homeBiome.getRelative(1, 0));
                sections.add(homeBiome.getRelative(1, -1));
                sections.add(homeBiome.getRelative(0, -1));
            }
        }
        else {
            if (blockZ >= center.getZ()) {
                sections.add(homeBiome.getRelative(-1, 0));
                sections.add(homeBiome.getRelative(-1, 1));
                sections.add(homeBiome.getRelative(0, 1));
            }
            else {
                sections.add(homeBiome.getRelative(-1, 0));
                sections.add(homeBiome.getRelative(-1, -1));
                sections.add(homeBiome.getRelative(0, -1));
            }

        }
        return sections;
    }

    public static @NotNull BiomeSection getMostDominantSection(@NotNull TerraformWorld tw, int x, int z) {

        double dither = TConfig.c.BIOME_DITHER;
        Random locationBasedRandom = new Random(Objects.hash(tw.getSeed(), x, z));
        SimpleLocation target = new SimpleLocation(x, 0, z);
        BiomeSection homeSection = BiomeBank.getBiomeSectionFromBlockCoords(tw, x, z);

        // Use the safely shifted dominance centre for this optimisation.
        if (target.distanceSqr(homeSection.getDominanceCenter()) <= dominanceThresholdSquared) {
            return homeSection;
        }

        Collection<BiomeSection> sections = BiomeSection.getSurroundingSections(tw, x, z);
        BiomeSection mostDominant = homeSection;

        for (BiomeSection sect : sections) {
            float dom = (float) (sect.getDominance(target) + GenUtils.randDouble(locationBasedRandom, -dither, dither));

            if (dom > mostDominant.getDominance(target) + GenUtils.randDouble(locationBasedRandom, -dither, dither)) {
                mostDominant = sect;
            }
        }

        return mostDominant;
    }

    protected void doCalculations() {
        this.radius = GenUtils.randInt(getSectionRandom(), minSize / 2, 5 * minSize / 4);
        this.shapeNoise = new FastNoise(Objects.hash(tw.getSeed(), x, z));
        shapeNoise.SetNoiseType(NoiseType.SimplexFractal);
        shapeNoise.SetFractalOctaves(3);
        shapeNoise.SetFrequency(0.01f);
        this.biome = this.parseBiomeBank();
    }

    public @NotNull Random getSectionRandom() {
        return new Random(Objects.hash(tw.getSeed(), x, z));
    }

    public @NotNull Random getSectionRandom(int multiplier) {
        return new Random((long) multiplier * Objects.hash(tw.getSeed(), x, z));
    }

    public @NotNull BiomeSection getRelative(int x, int z) {
        return BiomeBank.getBiomeSectionFromSectionCoords(this.tw, this.x + x, this.z + z, true);
    }

    public @NotNull BiomeSection getRelative(BiomeSubSection subSect) {
        return getRelative(subSect.relX, subSect.relZ);
    }

    public @NotNull BiomeBank getBiomeBank() {
        assert biome != null;
        return biome;
    }

    private @NotNull BiomeBank parseBiomeBank() {
        int[] warped = getWarpedSampleBlockCoords();
        int sampleSectionX = warped[0] >> bitshifts;
        int sampleSectionZ = warped[1] >> bitshifts;

        temperature = 3f * 2.5f * tw.getTemperatureOctave().GetNoise(sampleSectionX, sampleSectionZ);
        moisture = 3f * 2.5f * tw.getMoistureOctave().GetNoise(sampleSectionX, sampleSectionZ);

        return BiomeBank.selectBiome(
                this,
                temperature,
                moisture
        );
    }

    /**
     * Will be used to calculate which biome section has dominance in a certain
     * block
     */
    public float getDominance(@NotNull SimpleLocation target) {
        return getDominanceBasedOnRadius(target.getX(), target.getZ());
    }

    public float getDominanceBasedOnRadius(int blockX, int blockZ) {
        SimpleLocation center = this.getDominanceCenter();

        int xOffset = center.getX() - blockX;
        int zOffset = center.getZ() - blockZ;

        double equationResult = Math.pow(xOffset, 2) / Math.pow(radius, 2)
                                + Math.pow(zOffset, 2) / Math.pow(radius, 2)
                                + 0.9 * shapeNoise.GetNoise(xOffset, zOffset);

        return (float) (1 - equationResult);
    }

    public @NotNull SimpleLocation getCenter() {
        int x = ((this.x << bitshifts)) + sectionWidth / 2;
        int z = ((this.z << bitshifts)) + sectionWidth / 2;
        return new SimpleLocation(x, 0, z);
    }

    /**
     * @return Block coords of lowest coord pair in the section's square
     */
    public @NotNull SimpleLocation getLowerBounds() {
        int x = ((this.x << bitshifts));
        int z = ((this.z << bitshifts));
        return new SimpleLocation(x, 0, z);
    }

    /**
     * @return Block coords of highest coord pair in the section's square
     */
    public @NotNull SimpleLocation getUpperBounds() {
        int x = ((this.x << bitshifts)) + sectionWidth;
        int z = ((this.z << bitshifts)) + sectionWidth;
        return new SimpleLocation(x, 0, z);
    }

    /**
     * @param radius in biomesection coords
     * @return surrounding biome sections at radius distance away
     */
    public @NotNull Collection<BiomeSection> getRelativeSurroundingSections(int radius) {
        if (radius == 0) {
            BiomeSection target = this;
            return List.of(target);
        }
        //     xxxxx
        // xxx  x   x
        // xox  x o x
        // xxx  x   x
        //     xxxxx
        ArrayList<BiomeSection> candidates = new ArrayList<>();

        // Lock rX, iterate rZ
        for (int rx : new int[] {-radius, radius}) {
            for (int rz = -radius; rz <= radius; rz++) {
                candidates.add(this.getRelative(rx, rz));
            }
        }

        // Lock rZ, iterate rX
        for (int rz : new int[] {-radius, radius}) {
            for (int rx = 1 - radius; rx <= radius - 1; rx++) {
                candidates.add(this.getRelative(rx, rz));
            }
        }

        return candidates;
    }

    /**
     * @return the subsection within this biome section that the coordinates belong in.
     * Works even if the coords are outside the biome section.
     *
     * 12/6/2025 WHAT THE FUCK IS THIS
     */
    public @NotNull BiomeSubSection getSubSection(int rawX, int rawZ) {
        // if(new BiomeSection(tw, rawX, rawZ).equals(this)) {
        SimpleLocation sectionCenter = this.getCenter();
        int relXFromCenter = rawX - sectionCenter.getX();
        int relZFromCenter = rawZ - sectionCenter.getZ();

        if (relXFromCenter > 0) {
            if (relXFromCenter >= Math.abs(relZFromCenter)) {
                return BiomeSubSection.POSITIVE_X;
            }
        }

        if (relXFromCenter <= 0) {
            if (Math.abs(relXFromCenter) >= Math.abs(relZFromCenter)) {
                return BiomeSubSection.NEGATIVE_X;
            }
        }

        if (relZFromCenter > 0) {
            if (relZFromCenter >= Math.abs(relXFromCenter)) {
                return BiomeSubSection.POSITIVE_Z;
            }
        }

        if (relZFromCenter <= 0) {
            if (Math.abs(relZFromCenter) >= Math.abs(relXFromCenter)) {
                return BiomeSubSection.NEGATIVE_Z;
            }
        }

        return BiomeSubSection.NONE;

        // }

    }

    @Override
    public int hashCode() {
        int prime = 13;
        int result = 5;

        result = prime * result + x;
        result = prime * result + z;
        result = prime * result + tw.getName().hashCode();

        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof BiomeSection other) {
            return this.tw.getName().equals(other.tw.getName())
                   && this.x == other.x
                   && this.z == other.z;
        }
        return false;
    }

    public int getX() {
        return x;
    }

    public int getZ() {
        return z;
    }

    @Override
    public @NotNull String toString() {
        return "(" + x + "," + z + ")";
    }

    public @NotNull BiomeClimate getClimate() {
        return BiomeClimate.selectClimate(temperature, moisture);
    }

    public float getTemperature() {
        return temperature;
    }

    public float getMoisture() {
        return moisture;
    }

    public TerraformWorld getTw() {
        return tw;
    }

    public double getOceanLevel() {
        int[] warped = getWarpedSampleBlockCoords();
        return tw.getOceanicNoise().GetNoise(
                warped[0] >> bitshifts,
                warped[1] >> bitshifts
        ) * 50.0;
    }

    public double getMountainLevel() {
        int[] warped = getWarpedSampleBlockCoords();
        return tw.getMountainousNoise().GetNoise(
                warped[0] >> bitshifts,
                warped[1] >> bitshifts
        ) * 50.0;
    }
}
