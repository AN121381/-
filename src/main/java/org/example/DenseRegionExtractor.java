package org.example;

import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.List;

public final class DenseRegionExtractor {

    private DenseRegionExtractor() {
    }

    public static void extractDenseRegions(Path inputPng, Path outputMergedPng, Options options) throws IOException {
        try (ImageInputStream iis = ImageIO.createImageInputStream(inputPng.toFile())) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
            if (!readers.hasNext()) {
                throw new IOException("无法读取 PNG: " + inputPng);
            }
            ImageReader reader = readers.next();
            reader.setInput(iis);

            int width = reader.getWidth(0);
            int height = reader.getHeight(0);

            // 1. 计算缩放比例，使处理图像不超过 2048x2048，避免 OOM
            int targetDim = 4096;
            int scale = 1;
            while (width / scale > targetDim || height / scale > targetDim) {
                scale *= 2;
            }

            // 2. 读取降采样后的图像用于分析
            ImageReadParam param = reader.getDefaultReadParam();
            param.setSourceSubsampling(scale, scale, 0, 0);
            BufferedImage smallImage = reader.read(0, param);

            // 3. 调整参数以适应缩小后的图像
            int smallWidth = smallImage.getWidth();
            int smallHeight = smallImage.getHeight();
            int bg = estimateBackgroundColor(smallImage);

            // 计算网格
            int cellSize = Math.max(16,
                    Math.min(smallWidth, smallHeight) / Math.max(32, options.gridTargetCellsAcross));
            int cellsX = (smallWidth + cellSize - 1) / cellSize;
            int cellsY = (smallHeight + cellSize - 1) / cellSize;

            // 积分图计算
            int[][] integral = buildInkIntegral(smallImage, bg, options.bgDelta);

            double[] densities = new double[cellsX * cellsY];
            for (int cy = 0; cy < cellsY; cy++) {
                for (int cx = 0; cx < cellsX; cx++) {
                    int x0 = cx * cellSize;
                    int y0 = cy * cellSize;
                    int x1 = Math.min(smallWidth, x0 + cellSize);
                    int y1 = Math.min(smallHeight, y0 + cellSize);
                    int ink = rectSum(integral, x0, y0, x1, y1);
                    int area = (x1 - x0) * (y1 - y0);
                    densities[cy * cellsX + cx] = area <= 0 ? 0.0 : ((double) ink) / (double) area;
                }
            }

            double threshold = Math.max(options.minCellFill, percentile(densities, options.percentile));
            boolean[][] dense = new boolean[cellsY][cellsX];
            for (int cy = 0; cy < cellsY; cy++) {
                for (int cx = 0; cx < cellsX; cx++) {
                    dense[cy][cx] = densities[cy * cellsX + cx] >= threshold;
                }
            }

            // 4. 在小图上查找区域
            // 注意：minRegionPixels 需要根据 scale 进行调整
            Options scaledOptions = new Options();
            scaledOptions.minRegionPixels = Math.max(1, options.minRegionPixels / (scale * scale));
            scaledOptions.marginCells = options.marginCells;

            List<Region> smallRegions = findAndMergeRegions(dense, densities, cellsX, cellsY, cellSize, smallWidth,
                    smallHeight, scaledOptions);

            // 5. 映射回原图坐标
            List<Region> originalRegions = new ArrayList<>();
            for (Region r : smallRegions) {
                originalRegions.add(new Region(r.x * scale, r.y * scale, r.w * scale, r.h * scale, r.score));
            }

            originalRegions.sort(Comparator.comparingDouble((Region r) -> r.score).reversed());

            // 智能过滤：根据分数阈值筛选有效区域，同时处理“忽略空白”和“去除杂项”的需求
            if (!originalRegions.isEmpty()) {
                double maxScore = originalRegions.get(0).score;
                // 阈值设为最大分数的一定比例，低于此分数的区域被视为噪点或不重要内容
                double scoreThreshold = maxScore * options.scoreThresholdRatio;

                List<Region> filtered = new ArrayList<>();
                for (Region r : originalRegions) {
                    if (r.score >= scoreThreshold) {
                        filtered.add(r);
                    }
                }

                // 如果过滤后为空（理论上不会，因为至少有maxScore），则保留top1
                if (filtered.isEmpty()) {
                    filtered.add(originalRegions.get(0));
                }

                // 限制最大数量
                if (filtered.size() > options.maxRegions) {
                    filtered = new ArrayList<>(filtered.subList(0, options.maxRegions));
                }
                originalRegions = filtered;
            }

            if (originalRegions.isEmpty()) {
                Files.copy(inputPng, outputMergedPng, StandardCopyOption.REPLACE_EXISTING);
                return;
            }

            // 6. 生成紧凑布局（去除区域间的巨大空白）
            // 简单策略：按垂直方向堆叠，或保持相对位置但紧缩？
            // 鉴于 CAD 图纸通常是横向或纵向排列，我们采用“垂直堆叠”策略来合并区域，中间保留少量间隙
            // 这样可以彻底消除“图画之间的空白”

            int gap = 50; // 区域间隙
            int totalWidth = 0;
            int totalHeight = 0;

            // 计算新画布尺寸
            // 这里采用简单的垂直排列：宽度取最大区域宽度，高度为所有区域高度之和
            for (Region r : originalRegions) {
                totalWidth = Math.max(totalWidth, r.w);
                totalHeight += r.h + gap;
            }
            // 减去最后一个多余的间隙
            totalHeight -= gap;

            // 如果只有一个区域，直接使用其尺寸
            if (originalRegions.size() == 1) {
                totalWidth = originalRegions.get(0).w;
                totalHeight = originalRegions.get(0).h;
            }

            // 边界检查
            if (totalWidth <= 0 || totalHeight <= 0) {
                Files.copy(inputPng, outputMergedPng, StandardCopyOption.REPLACE_EXISTING);
                return;
            }

            // 7. 生成最终图片
            BufferedImage merged = new BufferedImage(totalWidth, totalHeight, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = merged.createGraphics();
            try {
                // 背景透明 (TYPE_INT_ARGB 默认)

                int currentY = 0;
                ImageReadParam chunkParam = reader.getDefaultReadParam();

                // 为了视觉上的连贯性，可以先按 Y 坐标排序，再按 X 坐标排序
                originalRegions.sort(Comparator.comparingInt((Region r) -> r.y).thenComparingInt(r -> r.x));

                for (Region r : originalRegions) {
                    // 裁剪区域
                    Rectangle sourceRect = new Rectangle(r.x, r.y, r.w, r.h);
                    // 确保不越界
                    sourceRect = sourceRect.intersection(new Rectangle(0, 0, width, height));
                    if (sourceRect.isEmpty()) {
                        continue;
                    }

                    chunkParam.setSourceRegion(sourceRect);
                    chunkParam.setSourceSubsampling(1, 1, 0, 0);
                    BufferedImage chunk = reader.read(0, chunkParam);

                    // 绘制到新画布，水平居中
                    int drawX = (totalWidth - r.w) / 2;
                    g.drawImage(chunk, drawX, currentY, null);

                    currentY += r.h + gap;
                }
            } finally {
                g.dispose();
            }

            ImageIO.write(merged, "png", outputMergedPng.toFile());
        }
    }

    private static List<Region> findAndMergeRegions(boolean[][] dense, double[] densities, int cellsX, int cellsY,
            int cellSize, int imgW, int imgH, Options options) {
        boolean[][] visited = new boolean[cellsY][cellsX];
        List<Region> regions = new ArrayList<>();

        for (int cy = 0; cy < cellsY; cy++) {
            for (int cx = 0; cx < cellsX; cx++) {
                if (!dense[cy][cx] || visited[cy][cx]) {
                    continue;
                }

                int minCx = cx;
                int maxCx = cx;
                int minCy = cy;
                int maxCy = cy;
                double score = 0.0;

                Deque<int[]> dq = new ArrayDeque<>();
                dq.add(new int[] { cx, cy });
                visited[cy][cx] = true;

                while (!dq.isEmpty()) {
                    int[] p = dq.removeFirst();
                    int x = p[0];
                    int y = p[1];
                    double d = densities[y * cellsX + x];
                    score += d;

                    if (x < minCx) {
                        minCx = x;
                    }
                    if (x > maxCx) {
                        maxCx = x;
                    }
                    if (y < minCy) {
                        minCy = y;
                    }
                    if (y > maxCy) {
                        maxCy = y;
                    }

                    if (x > 0 && dense[y][x - 1] && !visited[y][x - 1]) {
                        visited[y][x - 1] = true;
                        dq.add(new int[] { x - 1, y });
                    }
                    if (x + 1 < cellsX && dense[y][x + 1] && !visited[y][x + 1]) {
                        visited[y][x + 1] = true;
                        dq.add(new int[] { x + 1, y });
                    }
                    if (y > 0 && dense[y - 1][x] && !visited[y - 1][x]) {
                        visited[y - 1][x] = true;
                        dq.add(new int[] { x, y - 1 });
                    }
                    if (y + 1 < cellsY && dense[y + 1][x] && !visited[y + 1][x]) {
                        visited[y + 1][x] = true;
                        dq.add(new int[] { x, y + 1 });
                    }
                }

                minCx = Math.max(0, minCx - options.marginCells);
                minCy = Math.max(0, minCy - options.marginCells);
                maxCx = Math.min(cellsX - 1, maxCx + options.marginCells);
                maxCy = Math.min(cellsY - 1, maxCy + options.marginCells);

                int x0 = minCx * cellSize;
                int y0 = minCy * cellSize;
                int x1 = Math.min(imgW, (maxCx + 1) * cellSize);
                int y1 = Math.min(imgH, (maxCy + 1) * cellSize);
                int w = Math.max(1, x1 - x0);
                int h = Math.max(1, y1 - y0);
                if (w * h >= options.minRegionPixels) {
                    regions.add(new Region(x0, y0, w, h, score));
                }
            }
        }

        return mergeRegions(regions, cellSize * Math.max(1, options.marginCells));
    }

    private static List<Region> mergeRegions(List<Region> regions, int gapPx) {
        List<Region> current = new ArrayList<>(regions);
        boolean merged;
        do {
            merged = false;
            outer: for (int i = 0; i < current.size(); i++) {
                for (int j = i + 1; j < current.size(); j++) {
                    Region a = current.get(i);
                    Region b = current.get(j);
                    if (!overlapsOrNear(a, b, gapPx)) {
                        continue;
                    }
                    Region u = union(a, b);
                    current.remove(j);
                    current.remove(i);
                    current.add(u);
                    merged = true;
                    break outer;
                }
            }
        } while (merged);
        return current;
    }

    private static boolean overlapsOrNear(Region a, Region b, int gapPx) {
        int ax0 = a.x - gapPx;
        int ay0 = a.y - gapPx;
        int ax1 = a.x + a.w + gapPx;
        int ay1 = a.y + a.h + gapPx;
        int bx0 = b.x;
        int by0 = b.y;
        int bx1 = b.x + b.w;
        int by1 = b.y + b.h;
        return ax0 < bx1 && ax1 > bx0 && ay0 < by1 && ay1 > by0;
    }

    private static Region union(Region a, Region b) {
        int x0 = Math.min(a.x, b.x);
        int y0 = Math.min(a.y, b.y);
        int x1 = Math.max(a.x + a.w, b.x + b.w);
        int y1 = Math.max(a.y + a.h, b.y + b.h);
        return new Region(x0, y0, x1 - x0, y1 - y0, a.score + b.score);
    }

    private static int[][] buildInkIntegral(BufferedImage image, int bg, int bgDelta) {
        int w = image.getWidth();
        int h = image.getHeight();
        int[][] integral = new int[h + 1][w + 1];

        int bgR = (bg >> 16) & 0xFF;
        int bgG = (bg >> 8) & 0xFF;
        int bgB = bg & 0xFF;

        for (int y = 1; y <= h; y++) {
            int rowSum = 0;
            for (int x = 1; x <= w; x++) {
                int argb = image.getRGB(x - 1, y - 1);
                int a = (argb >>> 24) & 0xFF;
                int r = (argb >> 16) & 0xFF;
                int g = (argb >> 8) & 0xFF;
                int b = argb & 0xFF;
                int dist = Math.abs(r - bgR) + Math.abs(g - bgG) + Math.abs(b - bgB);
                int ink = (a > 0 && dist >= bgDelta) ? 1 : 0;
                rowSum += ink;
                integral[y][x] = integral[y - 1][x] + rowSum;
            }
        }
        return integral;
    }

    private static int rectSum(int[][] integral, int x0, int y0, int x1, int y1) {
        return integral[y1][x1] - integral[y0][x1] - integral[y1][x0] + integral[y0][x0];
    }

    private static double percentile(double[] values, double p) {
        double[] copy = Arrays.copyOf(values, values.length);
        Arrays.sort(copy);
        if (copy.length == 0) {
            return 0.0;
        }
        double clamped = Math.max(0.0, Math.min(1.0, p));
        int idx = (int) Math.floor(clamped * (copy.length - 1));
        return copy[Math.max(0, Math.min(copy.length - 1, idx))];
    }

    private static int estimateBackgroundColor(BufferedImage image) {
        int w = image.getWidth();
        int h = image.getHeight();
        int sampleSize = Math.max(8, Math.min(48, Math.min(w, h) / 50));
        int[] samples = new int[sampleSize * sampleSize * 4];
        int n = 0;

        n = sampleCorner(image, 0, 0, sampleSize, samples, n);
        n = sampleCorner(image, w - sampleSize, 0, sampleSize, samples, n);
        n = sampleCorner(image, 0, h - sampleSize, sampleSize, samples, n);
        n = sampleCorner(image, w - sampleSize, h - sampleSize, sampleSize, samples, n);

        Arrays.sort(samples, 0, n);
        int bestColor = samples[0];
        int bestCount = 1;
        int curColor = samples[0];
        int curCount = 1;
        for (int i = 1; i < n; i++) {
            if (samples[i] == curColor) {
                curCount++;
                if (curCount > bestCount) {
                    bestCount = curCount;
                    bestColor = curColor;
                }
            } else {
                curColor = samples[i];
                curCount = 1;
            }
        }
        return bestColor;
    }

    private static int sampleCorner(BufferedImage image, int sx, int sy, int size, int[] out, int offset) {
        int w = image.getWidth();
        int h = image.getHeight();
        int x0 = Math.max(0, Math.min(w - 1, sx));
        int y0 = Math.max(0, Math.min(h - 1, sy));
        int x1 = Math.max(0, Math.min(w, sx + size));
        int y1 = Math.max(0, Math.min(h, sy + size));
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                out[offset++] = image.getRGB(x, y);
            }
        }
        return offset;
    }

    public static final class Options {
        // 最大提取区域数量
        public int maxRegions = 12;
        // 密度阈值百分位
        public double percentile = 0.90;
        // 最小单元格填充率
        public double minCellFill = 0.0025;
        // 最小区域像素数
        public int minRegionPixels = 40_000;
        // 区域合并时的边距
        public int marginCells = 2;
        // 背景色判定容差
        public int bgDelta = 25;
        // 网格划分目标横向单元格数
        public int gridTargetCellsAcross = 128;
        // 区域得分阈值比例
        public double scoreThresholdRatio = 0.23;
    }

    public static final class Region {
        public final int x;
        public final int y;
        public final int w;
        public final int h;
        public final double score;

        public Region(int x, int y, int w, int h, double score) {
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
            this.score = score;
        }
    }
}
