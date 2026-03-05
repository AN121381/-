package org.example;

import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;

public final class WhiteFrameDetector {

    private WhiteFrameDetector() {
    }

    public static List<Rectangle> findWhiteFrames(Path png, Rectangle searchRect, Options options) throws IOException {
        try (ImageInputStream iis = ImageIO.createImageInputStream(png.toFile())) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
            if (!readers.hasNext()) {
                throw new IOException("无法读取 PNG: " + png);
            }
            ImageReader reader = readers.next();
            reader.setInput(iis);

            int fullW = reader.getWidth(0);
            int fullH = reader.getHeight(0);
            Rectangle bounded = clampRect(searchRect, fullW, fullH);
            if (bounded.isEmpty()) {
                return new ArrayList<>();
            }

            int targetDim = options.analysisMaxDim <= 0 ? 4096 : options.analysisMaxDim;
            int scale = 1;
            while (bounded.width / scale > targetDim || bounded.height / scale > targetDim) {
                scale *= 2;
            }

            ImageReadParam param = reader.getDefaultReadParam();
            param.setSourceRegion(bounded);
            param.setSourceSubsampling(scale, scale, 0, 0);
            BufferedImage img = reader.read(0, param);

            int w = img.getWidth();
            int h = img.getHeight();
            if (w <= 1 || h <= 1) {
                return new ArrayList<>();
            }

            boolean[][] mask = new boolean[h][w];
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int argb = img.getRGB(x, y);
                    int a = (argb >>> 24) & 0xFF;
                    if (a <= options.alphaMin) {
                        continue;
                    }
                    int r = (argb >> 16) & 0xFF;
                    int g = (argb >> 8) & 0xFF;
                    int b = argb & 0xFF;

                    int max = Math.max(r, Math.max(g, b));
                    int min = Math.min(r, Math.min(g, b));
                    if (max < options.grayThreshold) {
                        continue;
                    }
                    if (Math.abs(r - g) > options.channelDelta || Math.abs(g - b) > options.channelDelta
                            || Math.abs(r - b) > options.channelDelta) {
                        continue;
                    }
                    if (min < options.grayThreshold - options.channelDelta) {
                        continue;
                    }
                    mask[y][x] = true;
                }
            }

            boolean[][] visited = new boolean[h][w];
            List<Candidate> candidates = new ArrayList<>();
            int minDim = Math.max(2, options.minBoxDim / scale);

            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    if (!mask[y][x] || visited[y][x]) {
                        continue;
                    }
                    BBox box = flood(mask, visited, x, y);
                    int bw = box.maxX - box.minX + 1;
                    int bh = box.maxY - box.minY + 1;
                    if (bw < minDim || bh < minDim) {
                        continue;
                    }
                    double aspect = (double) bw / (double) bh;
                    if (aspect < options.minAspect || aspect > options.maxAspect) {
                        continue;
                    }

                    int band = Math.max(1, Math.min(options.borderBand, Math.min(bw, bh) / 10));
                    double top = bandCoverage(mask, box.minX, box.minY, bw, band);
                    double bottom = bandCoverage(mask, box.minX, box.maxY - band + 1, bw, band);
                    double left = bandCoverageVertical(mask, box.minX, box.minY, band, bh);
                    double right = bandCoverageVertical(mask, box.maxX - band + 1, box.minY, band, bh);

                    double minSide = Math.min(Math.min(top, bottom), Math.min(left, right));
                    if (minSide < options.minSideCoverage) {
                        continue;
                    }

                    if (options.maxInnerCoverage < 1.0) {
                        int innerMinX = box.minX + band;
                        int innerMinY = box.minY + band;
                        int innerW = bw - band * 2;
                        int innerH = bh - band * 2;
                        if (innerW > 0 && innerH > 0) {
                            double inner = bandCoverage(mask, innerMinX, innerMinY, innerW, innerH);
                            if (inner > options.maxInnerCoverage) {
                                continue;
                            }
                        }
                    }
                    double avg = (top + bottom + left + right) / 4.0;
                    candidates.add(new Candidate(box, avg));
                }
            }

            candidates.sort(
                    Comparator.<Candidate>comparingDouble(c -> c.score).reversed().thenComparingInt(c -> -c.area()));

            List<Rectangle> result = new ArrayList<>();
            for (Candidate c : candidates) {
                Rectangle r = new Rectangle(bounded.x + c.box.minX * scale, bounded.y + c.box.minY * scale,
                        (c.box.maxX - c.box.minX + 1) * scale, (c.box.maxY - c.box.minY + 1) * scale);
                r = expand(r, options.frameMarginPx);
                r = clampRect(r, fullW, fullH);
                if (r.isEmpty()) {
                    continue;
                }
                boolean overlapped = false;
                for (Rectangle existing : result) {
                    if (iou(existing, r) >= options.maxIouToKeepBoth) {
                        overlapped = true;
                        break;
                    }
                }
                if (overlapped) {
                    continue;
                }
                result.add(r);
                if (result.size() >= options.maxFrames) {
                    break;
                }
            }

            result.sort(Comparator.comparingInt((Rectangle rr) -> rr.y).thenComparingInt(rr -> rr.x));
            return result;
        }
    }

    private static BBox flood(boolean[][] mask, boolean[][] visited, int sx, int sy) {
        int h = mask.length;
        int w = mask[0].length;
        int minX = sx;
        int maxX = sx;
        int minY = sy;
        int maxY = sy;

        Deque<int[]> dq = new ArrayDeque<>();
        dq.add(new int[] { sx, sy });
        visited[sy][sx] = true;

        while (!dq.isEmpty()) {
            int[] p = dq.removeFirst();
            int x = p[0];
            int y = p[1];
            if (x < minX)
                minX = x;
            if (x > maxX)
                maxX = x;
            if (y < minY)
                minY = y;
            if (y > maxY)
                maxY = y;

            if (x > 0 && mask[y][x - 1] && !visited[y][x - 1]) {
                visited[y][x - 1] = true;
                dq.add(new int[] { x - 1, y });
            }
            if (x + 1 < w && mask[y][x + 1] && !visited[y][x + 1]) {
                visited[y][x + 1] = true;
                dq.add(new int[] { x + 1, y });
            }
            if (y > 0 && mask[y - 1][x] && !visited[y - 1][x]) {
                visited[y - 1][x] = true;
                dq.add(new int[] { x, y - 1 });
            }
            if (y + 1 < h && mask[y + 1][x] && !visited[y + 1][x]) {
                visited[y + 1][x] = true;
                dq.add(new int[] { x, y + 1 });
            }
        }
        return new BBox(minX, minY, maxX, maxY);
    }

    private static double bandCoverage(boolean[][] mask, int x, int y, int w, int h) {
        int H = mask.length;
        int W = mask[0].length;
        int x0 = Math.max(0, x);
        int y0 = Math.max(0, y);
        int x1 = Math.min(W, x + w);
        int y1 = Math.min(H, y + h);
        if (x1 <= x0 || y1 <= y0) {
            return 0.0;
        }
        int on = 0;
        int total = (x1 - x0) * (y1 - y0);
        for (int yy = y0; yy < y1; yy++) {
            for (int xx = x0; xx < x1; xx++) {
                if (mask[yy][xx]) {
                    on++;
                }
            }
        }
        return total <= 0 ? 0.0 : (double) on / (double) total;
    }

    private static double bandCoverageVertical(boolean[][] mask, int x, int y, int w, int h) {
        return bandCoverage(mask, x, y, w, h);
    }

    private static Rectangle clampRect(Rectangle r, int w, int h) {
        int x0 = Math.max(0, r.x);
        int y0 = Math.max(0, r.y);
        int x1 = Math.min(w, r.x + r.width);
        int y1 = Math.min(h, r.y + r.height);
        int ww = Math.max(0, x1 - x0);
        int hh = Math.max(0, y1 - y0);
        return new Rectangle(x0, y0, ww, hh);
    }

    private static Rectangle expand(Rectangle r, int px) {
        if (px <= 0) {
            return r;
        }
        return new Rectangle(r.x - px, r.y - px, r.width + px * 2, r.height + px * 2);
    }

    private static double iou(Rectangle a, Rectangle b) {
        int x0 = Math.max(a.x, b.x);
        int y0 = Math.max(a.y, b.y);
        int x1 = Math.min(a.x + a.width, b.x + b.width);
        int y1 = Math.min(a.y + a.height, b.y + b.height);
        int iw = Math.max(0, x1 - x0);
        int ih = Math.max(0, y1 - y0);
        long inter = (long) iw * (long) ih;
        long ua = (long) a.width * (long) a.height + (long) b.width * (long) b.height - inter;
        return ua <= 0 ? 0.0 : (double) inter / (double) ua;
    }

    private static final class BBox {
        final int minX;
        final int minY;
        final int maxX;
        final int maxY;

        private BBox(int minX, int minY, int maxX, int maxY) {
            this.minX = minX;
            this.minY = minY;
            this.maxX = maxX;
            this.maxY = maxY;
        }
    }

    private static final class Candidate {
        final BBox box;
        final double score;

        private Candidate(BBox box, double score) {
            this.box = box;
            this.score = score;
        }

        int area() {
            int w = box.maxX - box.minX + 1;
            int h = box.maxY - box.minY + 1;
            return Math.max(0, w) * Math.max(0, h);
        }
    }

    public static final class Options {
        public int maxFrames = 3;
        public int analysisMaxDim = 4096;
        public int grayThreshold = 160;
        public int channelDelta = 40;
        public int alphaMin = 0;
        public int minBoxDim = 200;
        public double minAspect = 0.2;
        public double maxAspect = 5.0;
        public int borderBand = 3;
        public double minSideCoverage = 0.12;
        public double maxInnerCoverage = 0.06;
        public double maxIouToKeepBoth = 0.35;
        public int frameMarginPx = 20;
    }
}
