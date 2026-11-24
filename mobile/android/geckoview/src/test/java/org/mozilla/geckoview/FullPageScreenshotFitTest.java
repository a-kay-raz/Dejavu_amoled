/* Any copyright is dedicated to the Public Domain.
http://creativecommons.org/publicdomain/zero/1.0/ */

package org.mozilla.geckoview;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

// Dejavu: the size limits of full page screenshots, see GeckoDisplay.ScreenshotBuilder.
public class FullPageScreenshotFitTest {
  private static final int SIDE = GeckoDisplay.ScreenshotBuilder.MAX_FULL_PAGE_SIDE;
  private static final long PIXELS = GeckoDisplay.ScreenshotBuilder.MAX_FULL_PAGE_PIXELS;

  private static double fit(final int width, final int height) {
    return GeckoDisplay.ScreenshotBuilder.fullPageFit(width, height);
  }

  @Test
  public void pagesThatFitKeepTheirSize() {
    assertEquals(1.0, fit(1080, 5000), 0.0);
    assertEquals(1.0, fit(720, SIDE), 0.0);
  }

  @Test
  public void longPagesShrinkToTheLongestSide() {
    assertEquals(SIDE / 100_000.0, fit(720, 100_000), 1e-12);
    assertEquals(SIDE / 20_000.0, fit(20_000, 100), 1e-12);
  }

  @Test
  public void bigPagesShrinkToTheMostPixels() {
    assertEquals(Math.sqrt(PIXELS / (4096.0 * 8192)), fit(4096, 8192), 1e-12);
    assertEquals(0.25, fit(SIDE, SIDE), 1e-12);
  }

  @Test
  public void shrunkPagesAreDrawnInTilesOfWholePixels() {
    final float[] ratios = {1.75f, 2.625f, 2.75f, 3f, 1.3312f};
    final double[] fits = {0.0587, 0.3, 0.5, 0.999};
    for (final float dpr : ratios) {
      for (final double fit : fits) {
        final float scale = GeckoDisplay.ScreenshotBuilder.fullPageScale(dpr, fit);
        final float tile = GeckoDisplay.ScreenshotBuilder.FULL_PAGE_TILE * scale;
        final String message = dpr + " at " + fit + " -> " + scale;
        assertEquals(message, Math.rint(tile), tile, 0.0);
        assertTrue(message, scale <= dpr * fit && scale > dpr * fit - 1.0 / 1024);
      }
    }
  }

  @Test
  public void shrunkPagesStayWithinBothLimits() {
    final int[][] sizes = {
      {1440, 100_000}, {4096, 8192}, {1, 1_000_000}, {20_000, 100}, {SIDE, SIDE}
    };
    for (final int[] size : sizes) {
      final double fit = fit(size[0], size[1]);
      final long width = Math.max(1, (long) (size[0] * fit));
      final long height = Math.max(1, (long) (size[1] * fit));
      final String message = size[0] + "x" + size[1] + " -> " + width + "x" + height;
      assertTrue(message, width <= SIDE && height <= SIDE);
      assertTrue(message, width * height <= PIXELS);
    }
  }
}
