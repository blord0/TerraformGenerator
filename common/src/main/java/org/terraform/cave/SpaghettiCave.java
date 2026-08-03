package org.terraform.cave;

import org.jetbrains.annotations.NotNull;
import org.terraform.data.MegaChunk;
import org.terraform.data.TerraformWorld;
import org.terraform.main.config.TConfig;
import org.terraform.utils.noise.FastNoise;
import org.terraform.utils.noise.NoiseCacheHandler;

/**
 * Thin, winding tunnels that thread between CheeseCave caverns, similar to
 * vanilla's spaghetti caves. Carves along the near-zero contour of a fractal
 * noise field - since that contour is a continuous curve in 3D space, tunnels
 * naturally snake for long distances and link separate cave pockets together,
 * instead of each pocket being an independent, disconnected blob.
 */
public class SpaghettiCave extends NoiseCaveAbstract {
    @Override
    public boolean canCarve(@NotNull TerraformWorld tw, int rawX, int y, int rawZ, double height, float surfaceFilter) {
        FastNoise tunnelNoise = NoiseCacheHandler.getNoise(
                tw,
                NoiseCacheHandler.NoiseCacheEntry.CAVE_SPAGHETTI_NOISE,
                world -> {
                    FastNoise n = new FastNoise((int) (tw.getSeed() + 461827));
                    n.SetNoiseType(FastNoise.NoiseType.SimplexFractal);
                    n.SetFrequency(TConfig.c.CAVES_SPAGHETTI_FREQUENCY);
                    n.SetFractalOctaves(2);
                    return n;
                }
        );

        // Squash Y so tunnels stay roughly level instead of coiling up vertically.
        float tunnel = tunnelNoise.GetNoise(rawX, y * 1.5f, rawZ);

        float thickness = TConfig.c.CAVES_SPAGHETTI_THICKNESS;

        // Shrink close to large structures so tunnels don't punch into them,
        // same reasoning as CheeseCave's structure suppression.
        var mc = new MegaChunk(rawX, y, rawZ);
        var center = mc.getCenterBiomeSectionBlockCoords();
        float distSquared = (float) center.distanceSquared(rawX, rawZ);
        if (distSquared <= 40000f) { //40000 = 200*200
            thickness *= (1 - TConfig.c.CAVES_STRUCTURE_SUPPRESSION_THRESHOLD)
                         + (TConfig.c.CAVES_STRUCTURE_SUPPRESSION_THRESHOLD) * (distSquared / 40000f);
        }

        return Math.abs(tunnel) < thickness * surfaceFilter;
    }
}
