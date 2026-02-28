package org.example;

import com.opendesign.drawings.td_dbcoreintegrated.*;
import com.opendesign.kernel.td_rootintegrated.*;
import teigha.OdActivationInfo;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.FileInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

public class DwgToPng {
    private static final String ODA_NATIVE_PATH = "E:\\ODA\\ODAToolkit\\exe\\vc16_amd64dll";

    static {
        setLibraryPath(ODA_NATIVE_PATH);
    }

    public static void main(String[] args) {
        System.setProperty("java.awt.headless", "true");

        // Self-relaunch check: Ensure ODA_NATIVE_PATH is in system PATH
        if (!isPathConfigured(ODA_NATIVE_PATH)) {
            System.out.println("配置环境并重新启动...");
            relaunchWithCorrectPath(ODA_NATIVE_PATH, args);
            return;
        }

        // 加载配置文件
        Properties config = new Properties();
        try (InputStreamReader reader = new InputStreamReader(new FileInputStream("config.properties"),
                StandardCharsets.UTF_8)) {
            config.load(reader);
            System.out.println("已加载配置文件: config.properties");
        } catch (IOException ex) {
            System.out.println("未找到配置文件 config.properties，将使用默认值。");
        }

        String defaultSrc = config.getProperty("input_path",
                "D:\\123\\input\\S24-040（20240305）新昌合美置业有限公司（文德华府）供水安装工程.dwg");
        String defaultDst = config.getProperty("output_path",
                "E:\\123\\output\\S24-040（20240305）新昌合美置业有限公司（文德华府）供水安装工程.png");
        int defaultWidth = Integer.parseInt(config.getProperty("width", "50000"));
        int defaultHeight = Integer.parseInt(config.getProperty("height", "50000"));
        int defaultMaxRegions = Integer.parseInt(config.getProperty("max_regions", "12"));
        int maxResolutionLimit = Integer.parseInt(config.getProperty("max_resolution_limit", "90000"));

        String srcFileName = args != null && args.length >= 1 ? args[0] : defaultSrc;
        String dstFileName = args != null && args.length >= 2 ? args[1] : defaultDst;
        int width = args != null && args.length >= 3 ? Integer.parseInt(args[2]) : defaultWidth;
        int height = args != null && args.length >= 4 ? Integer.parseInt(args[3]) : defaultHeight;
        int maxRegions = args != null && args.length >= 5 ? Integer.parseInt(args[4]) : defaultMaxRegions;

        MemoryManager memoryManager = MemoryManager.GetMemoryManager();
        boolean initialized = false;
        try {
            CustomSystemServices systemServices = new CustomSystemServices();
            ExHostAppServices hostApp = new ExHostAppServices();
            hostApp.disableOutput(true);

            TD_DbCoreIntegrated_Globals.odInitialize(systemServices);
            initialized = true;

            java.io.File inputPath = new java.io.File(srcFileName);
            java.io.File outputPath = new java.io.File(dstFileName);
            java.util.List<java.io.File> filesToProcess = new java.util.ArrayList<>();

            if (inputPath.isDirectory()) {
                java.io.File[] files = inputPath.listFiles((dir, name) -> name.toLowerCase().endsWith(".dwg"));
                if (files != null) {
                    java.util.Collections.addAll(filesToProcess, files);
                }
                if (!outputPath.exists()) {
                    outputPath.mkdirs();
                }
            } else {
                filesToProcess.add(inputPath);
            }

            if (filesToProcess.isEmpty()) {
                System.out.println("未找到需要处理的DWG文件: " + srcFileName);
                return;
            }

            int totalFiles = filesToProcess.size();
            System.out.println("共找到 " + totalFiles + " 个文件待处理。");

            for (int i = 0; i < totalFiles; i++) {
                java.io.File srcFile = filesToProcess.get(i);
                String currentSrcPath = srcFile.getAbsolutePath();
                String currentDstPath;

                if (inputPath.isDirectory()) {
                    // 如果输入是目录，输出到输出目录，保持文件名
                    String fileName = srcFile.getName();
                    String baseName = fileName.substring(0, fileName.lastIndexOf('.'));
                    currentDstPath = new java.io.File(outputPath, baseName + ".png").getAbsolutePath();
                } else {
                    // 如果输入是单个文件
                    if (outputPath.isDirectory()) {
                        String fileName = srcFile.getName();
                        String baseName = fileName.substring(0, fileName.lastIndexOf('.'));
                        currentDstPath = new java.io.File(outputPath, baseName + ".png").getAbsolutePath();
                    } else {
                        currentDstPath = outputPath.getAbsolutePath();
                    }
                }

                System.out.println("[" + (i + 1) + "/" + totalFiles + "] 正在转换文件: " + srcFile.getName());

                MemoryTransaction transaction = memoryManager.StartTransaction();
                try {
                    processSingleFile(hostApp, currentSrcPath, currentDstPath, width, height, maxResolutionLimit,
                            maxRegions, config);
                } catch (Exception e) {
                    System.err.println("文件处理失败 [" + srcFile.getName() + "]: " + e.getMessage());
                    e.printStackTrace();
                } finally {
                    memoryManager.StopTransaction(transaction);
                }
                System.out.println("--------------------------------------------------");
            }

        } catch (OdError error) {
            System.out.println(error.description());
        } catch (Error error) {
            System.out.println(error);
        } catch (Exception error) {
            System.out.println(error);
        } finally {
            if (initialized) {
                TD_DbCoreIntegrated_Globals.odUninitialize();
            }
        }
    }

    private static void processSingleFile(ExHostAppServices hostApp, String srcFileName, String dstFileName,
            int width, int height, int maxResolutionLimit, int maxRegions, Properties config) {
        if (hostApp.findFile(srcFileName).isEmpty()) {
            System.out.println("找不到.dwg文件: " + srcFileName);
            return;
        }

        try {
            OdDbDatabase database = hostApp.readFile(srcFileName);

            // 1. 生成低分辨率代理图用于分析
            System.out.println("正在生成代理图像进行分析...");

            // 先进行空间分析获取准确的边界 (过滤隐藏层)
            SpatialAnalyzer.Result vectorAnalysis = SpatialAnalyzer.analyze(database, 10);
            OdGeExtents3d proxyExtents = vectorAnalysis.totalExtents;

            if (proxyExtents == null) {
                System.out.println("无法获取有效边界，尝试默认全图渲染...");
            } else {
                System.out.println("使用空间分析计算的边界进行代理渲染...");
            }

            // 计算代理图像尺寸 (最大边长 8192) - 提高分辨率以捕捉细节
            int proxyMaxDim = 8192;
            int proxyW, proxyH;

            // 根据实际内容的宽高比来计算代理图尺寸，避免产生巨大的黑边导致坐标映射偏差
            if (proxyExtents != null) {
                OdGePoint3d min = proxyExtents.minPoint();
                OdGePoint3d max = proxyExtents.maxPoint();
                double worldW = Math.abs(max.getX() - min.getX());
                double worldH = Math.abs(max.getY() - min.getY());

                if (worldW >= worldH) {
                    proxyW = proxyMaxDim;
                    proxyH = (int) Math.max(1, Math.round(proxyMaxDim * (worldH / worldW)));
                } else {
                    proxyH = proxyMaxDim;
                    proxyW = (int) Math.max(1, Math.round(proxyMaxDim * (worldW / worldH)));
                }
                System.out.println("根据内容范围计算代理图尺寸: " + proxyW + "x" + proxyH + " (原始宽高比: " + (worldW / worldH) + ")");
            } else {
                // Fallback if extents are missing
                int[] proxySize = normalizeRenderSize(width, height, proxyMaxDim);
                proxyW = proxySize[0];
                proxyH = proxySize[1];
            }

            java.io.File dstFile = new java.io.File(dstFileName);
            java.io.File parentDir = dstFile.getParentFile();
            if (parentDir != null && !parentDir.exists()) {
                parentDir.mkdirs();
            }

            String proxyFileName = "proxy_" + System.currentTimeMillis() + ".png";
            java.io.File proxyFile = new java.io.File(parentDir, proxyFileName);

            // 渲染全图到代理文件
            // 如果 proxyExtents 不为空，使用它；否则传 null (使用 zoomExtents)
            renderRegionToImage(database, proxyFile.getAbsolutePath(), proxyW, proxyH, proxyMaxDim, proxyExtents);

            if (!proxyFile.exists()) {
                throw new RuntimeException("代理图像生成失败");
            }

            // 2. 分析代理图像提取区域
            System.out.println("正在分析像素分布...");
            BufferedImage proxyImage = ImageIO.read(proxyFile);

            DenseRegionExtractor.Options options = new DenseRegionExtractor.Options();
            options.maxRegions = Math.max(1, maxRegions);
            if (config.containsKey("region_percentile"))
                options.percentile = Double.parseDouble(config.getProperty("region_percentile"));
            if (config.containsKey("min_cell_fill"))
                options.minCellFill = Double.parseDouble(config.getProperty("min_cell_fill"));
            // Scale min pixels relative to proxy size vs original size?
            // The options are usually tuned for 2000-4000px images, so defaults should work
            // for 4096 proxy.
            if (config.containsKey("min_region_pixels"))
                options.minRegionPixels = Integer.parseInt(config.getProperty("min_region_pixels"));
            if (config.containsKey("margin_cells"))
                options.marginCells = Integer.parseInt(config.getProperty("margin_cells"));
            if (config.containsKey("bg_delta"))
                options.bgDelta = Integer.parseInt(config.getProperty("bg_delta"));
            if (config.containsKey("grid_target_cells_across"))
                options.gridTargetCellsAcross = Integer.parseInt(config.getProperty("grid_target_cells_across"));
            if (config.containsKey("score_threshold_ratio"))
                options.scoreThresholdRatio = Double.parseDouble(config.getProperty("score_threshold_ratio"));
            if (config.containsKey("min_region_density"))
                options.minRegionDensity = Double.parseDouble(config.getProperty("min_region_density"));
            if (config.containsKey("min_region_fill_ratio"))
                options.minRegionFillRatio = Double.parseDouble(config.getProperty("min_region_fill_ratio"));

            System.out.println("提取参数: 密度阈值=" + options.minRegionDensity + ", 填充率阈值=" + options.minRegionFillRatio
                    + ", 分数比例=" + options.scoreThresholdRatio);

            List<DenseRegionExtractor.Region> pixelRegions = DenseRegionExtractor.extractRegions(proxyImage, options);

            // Delete proxy file
            proxyFile.delete();

            if (pixelRegions.isEmpty()) {
                System.out.println("未找到明显密集区域，将执行全图渲染...");
                renderRegionToImage(database, dstFileName, width, height, maxResolutionLimit, null);
                return;
            }

            System.out.println("找到 " + pixelRegions.size() + " 个密集区域，准备高分辨率渲染...");

            // SpatialAnalyzer.Result vectorAnalysis = SpatialAnalyzer.analyze(database,
            // 10);
            // 之前已经分析过了，直接使用结果
            if (vectorAnalysis.totalExtents == null) {
                // Fallback (虽然前面已经处理过，但为了逻辑完整)
                renderRegionToImage(database, dstFileName, width, height, maxResolutionLimit, null);
                return;
            }

            OdGePoint3d worldMin = vectorAnalysis.totalExtents.minPoint();
            OdGePoint3d worldMax = vectorAnalysis.totalExtents.maxPoint();
            double worldW = worldMax.getX() - worldMin.getX();
            double worldH = worldMax.getY() - worldMin.getY();

            // 4. 渲染分块
            // 为了避免 OOM，我们不将所有 BufferedImage 保存在内存中
            // 而是记录每个分块的路径和位置信息，最后使用 Graphics2D 逐个绘制到大图（如果大图太大，可能需要分片写文件）

            // 记录分块信息
            class ImagePart {
                File file;
                int x, y, w, h;

                ImagePart(File f, int x, int y, int w, int h) {
                    this.file = f;
                    this.x = x;
                    this.y = y;
                    this.w = w;
                    this.h = h;
                }
            }
            List<ImagePart> parts = new ArrayList<>();

            int totalMergedHeight = 0;
            int maxMergedWidth = 0;
            int gap = 50;

            // [新增优化] 4.1 过滤离群区域 (Outlier Filtering - Clustering Strategy)
            // 采用聚类分析：将距离相近的区域归为一类，最后只保留“分数最高”的那一类区域。
            // 这能有效去除距离主图极远的噪点，解决“内容极小”的问题。
            if (!pixelRegions.isEmpty()) {

                // [智能过滤] 先去除明显是“大而空”的背景边框/噪点
                // 策略：如果一个区域面积远大于中位数 (例如 > 3倍)，且密度远低于最大密度 (例如 < 0.6倍)，则视为无效边框
                if (pixelRegions.size() > 1) {
                    double maxDensity = 0;
                    List<Double> areas = new ArrayList<>();
                    // 先计算 maxDensity 和 medianArea
                    for (DenseRegionExtractor.Region r : pixelRegions) {
                        double d = (double) r.score / (r.w * r.h);
                        if (d > maxDensity)
                            maxDensity = d;
                        areas.add((double) r.w * r.h);
                    }

                    areas.sort(Double::compareTo);
                    double medianArea = areas.get(areas.size() / 2);

                    List<DenseRegionExtractor.Region> filteredPreCluster = new ArrayList<>();
                    int removedBigSparse = 0;

                    for (DenseRegionExtractor.Region r : pixelRegions) {
                        double area = r.w * r.h;
                        double density = r.score / area;

                        // 条件：面积 > 中位数 * 3 AND 密度 < 最大密度 * 0.6
                        // 这种区域通常是巨大的空心圆或边框
                        boolean isHuge = area > medianArea * 3.0;
                        boolean isSparse = density < maxDensity * 0.6;

                        if (isHuge && isSparse) {
                            System.out.println(
                                    "忽略巨大稀疏区域: 尺寸=" + r.w + "x" + r.h + ", 密度=" + String.format("%.4f", density)
                                            + " (Max: " + String.format("%.4f", maxDensity) + ")");
                            removedBigSparse++;
                            continue;
                        }
                        filteredPreCluster.add(r);
                    }

                    if (removedBigSparse > 0) {
                        if (filteredPreCluster.isEmpty()) {
                            // 如果全被过滤了，那说明全是垃圾？或者误判了。保留原始的吧。
                            System.out.println("警告：所有区域都被判定为稀疏噪点，取消过滤，保留原始结果。");
                        } else {
                            pixelRegions = filteredPreCluster;
                            System.out.println("预过滤去除了 " + removedBigSparse + " 个巨大稀疏噪点。");
                        }
                    }
                }

                // 1. 初始化聚类
                List<List<DenseRegionExtractor.Region>> clusters = new ArrayList<>();
                for (DenseRegionExtractor.Region r : pixelRegions) {
                    List<DenseRegionExtractor.Region> c = new ArrayList<>();
                    c.add(r);
                    clusters.add(c);
                }

                // 2. 合并聚类
                // 阈值设为代理图尺寸的 ~20% (例如 8192 * 0.2 = 1600)
                // 如果两个区域的间距超过此值，视为不相关
                int mergeThreshold = Math.max(proxyW, proxyH) / 5;
                boolean merged;
                do {
                    merged = false;
                    for (int i = 0; i < clusters.size(); i++) {
                        if (clusters.get(i).isEmpty())
                            continue;
                        for (int j = i + 1; j < clusters.size(); j++) {
                            if (clusters.get(j).isEmpty())
                                continue;

                            if (shouldMergeClusters(clusters.get(i), clusters.get(j), mergeThreshold)) {
                                clusters.get(i).addAll(clusters.get(j));
                                clusters.get(j).clear(); // 标记为空
                                merged = true;
                            }
                        }
                    }
                    // 移除空聚类
                    clusters.removeIf(List::isEmpty);
                } while (merged);

                // 3. 找出所有显著的聚类 (分数 > 10% MaxScore 且 面积 > 2% MaxArea)
                // 之前的策略只保留 Top 1，导致误删有效内容。
                // 现在的策略：结合“分数”和“空间面积”双重过滤。
                // 噪点通常具有：低分数 OR (高密度但极小面积)。
                double maxClusterScore = 0;
                double maxClusterArea = 0;

                // 预计算每个簇的属性
                List<Double> clusterScores = new ArrayList<>();
                List<Double> clusterAreas = new ArrayList<>();

                for (List<DenseRegionExtractor.Region> cluster : clusters) {
                    double score = 0;
                    double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE;
                    double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;

                    for (DenseRegionExtractor.Region r : cluster) {
                        score += r.score;
                        minX = Math.min(minX, r.x);
                        minY = Math.min(minY, r.y);
                        maxX = Math.max(maxX, r.x + r.w);
                        maxY = Math.max(maxY, r.y + r.h);
                    }
                    double area = (maxX - minX) * (maxY - minY);

                    clusterScores.add(score);
                    clusterAreas.add(area);

                    if (score > maxClusterScore)
                        maxClusterScore = score;
                    if (area > maxClusterArea)
                        maxClusterArea = area;
                }

                List<DenseRegionExtractor.Region> keptRegions = new ArrayList<>();
                System.out.println("发现 " + clusters.size() + " 个独立的区域簇 (最大分: " + String.format("%.2f", maxClusterScore)
                        + ", 最大面积: " + String.format("%.0f", maxClusterArea) + "):");

                for (int i = 0; i < clusters.size(); i++) {
                    List<DenseRegionExtractor.Region> cluster = clusters.get(i);
                    double score = clusterScores.get(i);
                    double area = clusterAreas.get(i);

                    // 阈值判定
                    // 1. 分数至少为最大簇的 10% (保留主要内容)
                    // 2. 面积至少为最大簇的 2% (过滤高密度但微小的噪点/图章)
                    boolean enoughScore = score > maxClusterScore * 0.1;
                    boolean enoughArea = area > maxClusterArea * 0.02;

                    // 特殊情况：如果只有一个簇，无条件保留
                    boolean keep = (clusters.size() == 1) || (enoughScore && enoughArea);

                    String status = keep ? "保留" : "过滤";
                    String reason = "";
                    if (!keep) {
                        if (!enoughScore)
                            reason += "[分数过低] ";
                        if (!enoughArea)
                            reason += "[面积过小] ";
                    }

                    System.out.println(
                            "  簇 " + (i + 1) + ": 包含 " + cluster.size() + " 个区域, 总分=" + String.format("%.2f", score) +
                                    ", 面积=" + String.format("%.0f", area) + " ("
                                    + String.format("%.1f", (maxClusterArea > 0 ? (area / maxClusterArea) * 100 : 0))
                                    + "%) -> " + status + " " + reason);

                    if (keep) {
                        keptRegions.addAll(cluster);
                    }
                }

                // 4. 应用过滤
                if (!keptRegions.isEmpty()) {
                    int removedCount = pixelRegions.size() - keptRegions.size();
                    if (removedCount > 0) {
                        System.out.println(
                                ">>> 过滤了 " + removedCount + " 个离群/噪点区域，保留了 " + keptRegions.size() + " 个有效区域 <<<");
                        pixelRegions = keptRegions;
                    }
                }
            }

            // [新增优化] 计算所有密集区域的世界坐标包围盒
            // 如果图纸有极远的噪点，直接用 worldW/worldH 会导致 scale 极小。
            // 我们应该只根据“有效密集区域”的范围来计算缩放比例。
            double denseMinX = Double.MAX_VALUE;
            double denseMinY = Double.MAX_VALUE;
            double denseMaxX = -Double.MAX_VALUE;
            double denseMaxY = -Double.MAX_VALUE;

            for (DenseRegionExtractor.Region r : pixelRegions) {
                double normX = (double) r.x / proxyW;
                double normY = (double) r.y / proxyH;
                double normW = (double) r.w / proxyW;
                double normH = (double) r.h / proxyH;

                double rMinX = worldMin.getX() + normX * worldW;
                double rMaxX = rMinX + normW * worldW;

                // Y axis flip
                double rMaxY = worldMax.getY() - normY * worldH;
                double rMinY = rMaxY - normH * worldH;

                if (rMinX < denseMinX)
                    denseMinX = rMinX;
                if (rMinY < denseMinY)
                    denseMinY = rMinY;
                if (rMaxX > denseMaxX)
                    denseMaxX = rMaxX;
                if (rMaxY > denseMaxY)
                    denseMaxY = rMaxY;
            }

            double denseWorldW = denseMaxX - denseMinX;
            double denseWorldH = denseMaxY - denseMinY;

            // 防止除零或无效区域
            if (denseWorldW <= 0 || denseWorldH <= 0) {
                denseWorldW = worldW;
                denseWorldH = worldH;
            }

            // 计算全局缩放比例 (基于密集区域的包围盒)
            double scaleX = (double) width / denseWorldW;
            double scaleY = (double) height / denseWorldH;
            double globalScale = Math.min(scaleX, scaleY);

            System.out.println("有效内容范围: " + denseWorldW + "x" + denseWorldH + " (原始范围: " + worldW + "x" + worldH + ")");
            System.out.println("全局缩放比例: " + String.format("%.4f", globalScale));

            for (int i = 0; i < pixelRegions.size(); i++) {
                DenseRegionExtractor.Region r = pixelRegions.get(i);

                // Map Pixel (r.x, r.y, r.w, r.h) from Proxy (proxyW, proxyH) to World
                // Image Origin (0,0) is usually Top-Left.
                // World Y is usually Bottom-Up.
                // So Pixel Y maps to World Y: worldMaxY - (y / pH) * worldH

                double normX = (double) r.x / proxyW;
                double normY = (double) r.y / proxyH;
                double normW = (double) r.w / proxyW;
                double normH = (double) r.h / proxyH;

                double regMinX = worldMin.getX() + normX * worldW;
                double regMaxX = regMinX + normW * worldW;

                // Y axis flip
                // Pixel y=0 -> World MaxY
                // Pixel y+h -> World MaxY - ...
                double regMaxY = worldMax.getY() - normY * worldH;
                double regMinY = regMaxY - normH * worldH;

                OdGeExtents3d regionExt = new OdGeExtents3d(
                        new OdGePoint3d(regMinX, regMinY, 0),
                        new OdGePoint3d(regMaxX, regMaxY, 0));

                // Calculate Target Output Size
                int pW = (int) Math.ceil((regMaxX - regMinX) * globalScale);
                int pH = (int) Math.ceil((regMaxY - regMinY) * globalScale);

                pW = Math.max(1, pW);
                pH = Math.max(1, pH);

                System.out.println("渲染区域 " + (i + 1) + ": " + pW + "x" + pH);

                String tempName = "temp_part_" + System.currentTimeMillis() + "_" + i + ".png";
                java.io.File tempPart = new java.io.File(parentDir, tempName);

                try {
                    renderRegionToImage(database, tempPart.getAbsolutePath(), pW, pH, maxResolutionLimit, regionExt);
                    if (tempPart.exists()) {
                        // 验证是否成功生成，且是有效图片
                        try (FileInputStream fis = new FileInputStream(tempPart)) {
                            // 只读头信息，不加载整个图片
                            // 不过为了获取宽高，可能还是得 ImageIO.read 部分
                            // 简单起见，这里假设生成就是成功的，直接记录
                            // 为了获取真实宽高 (可能被 ODA 内部调整)，还是得读一下头
                            // 或者直接用我们请求的 pW, pH (如果 ODA 没改的话)

                            // 为了稳妥，读一下图片对象，获取宽高后立即释放
                            BufferedImage part = ImageIO.read(tempPart);
                            if (part != null) {
                                int partW = part.getWidth();
                                int partH = part.getHeight();
                                parts.add(new ImagePart(tempPart, 0, 0, partW, partH));
                                totalMergedHeight += partH + gap;
                                maxMergedWidth = Math.max(maxMergedWidth, partW);
                                // 不要 delete tempPart，后面合并要用
                            }
                            // part = null; // Help GC
                        }
                    }
                } catch (Exception ex) {
                    ex.printStackTrace();
                }
            }

            if (parts.isEmpty()) {
                System.out.println("区域渲染失败");
                return;
            }

            totalMergedHeight -= gap;

            System.out.println("正在合并图片: " + maxMergedWidth + "x" + totalMergedHeight);

            // 如果图片非常大 (例如超过 2GB 内存)，BufferedImage 可能会失败
            // Java 数组最大索引是 Integer.MAX_VALUE (21亿)，但 RGBA 占 4 字节，所以最大像素数约为 5亿 (23000x23000)
            // 30000x20000 = 6亿像素，肯定会爆 BufferedImage。

            // 解决方案：如果不缩放，就无法生成单一文件 (PNG 限制)。
            // 除非使用 BigBufferedImage (基于磁盘)，或者分块保存。
            // 这里我们尝试：如果尺寸过大，就强制缩放输出图片到安全范围 (例如最长边 20000)

            int safeMaxDim = 20000;
            double outputScale = 1.0;
            if (maxMergedWidth > safeMaxDim || totalMergedHeight > safeMaxDim) {
                double sx = (double) safeMaxDim / maxMergedWidth;
                double sy = (double) safeMaxDim / totalMergedHeight;
                outputScale = Math.min(sx, sy);
                System.out.println(
                        "输出尺寸过大，将自动缩放至安全范围 (" + safeMaxDim + "px)，缩放比例: " + String.format("%.4f", outputScale));

                maxMergedWidth = (int) (maxMergedWidth * outputScale);
                totalMergedHeight = (int) (totalMergedHeight * outputScale);
                System.out.println("最终输出尺寸: " + maxMergedWidth + "x" + totalMergedHeight);
            }

            BufferedImage finalImage = new BufferedImage(maxMergedWidth, totalMergedHeight,
                    BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = finalImage.createGraphics();

            // 优化：设置更好的插值算法
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);

            int currentY = 0;
            for (ImagePart part : parts) {
                BufferedImage chunk = ImageIO.read(part.file);
                if (chunk != null) {
                    int targetW = (int) (part.w * outputScale);
                    int targetH = (int) (part.h * outputScale);

                    int drawX = (maxMergedWidth - targetW) / 2;
                    g.drawImage(chunk, drawX, currentY, targetW, targetH, null);
                    currentY += targetH + (int) (gap * outputScale);

                    // 释放内存
                    chunk = null;
                }
                // 删除临时文件
                part.file.delete();
            }
            g.dispose();

            ImageIO.write(finalImage, "png", dstFile);
            System.out.println("处理完成: " + dstFileName);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void renderRegionToImage(OdDbDatabase database, String outputFile, int width, int height,
            int maxResolutionLimit, OdGeExtents3d region) {
        MemoryTransaction transaction = MemoryManager.GetMemoryManager().StartTransaction();
        try {
            // Force limit max resolution per part to 20000 to avoid OOM
            int safeLimit = Math.min(maxResolutionLimit, 20000);
            int[] size = normalizeRenderSize(width, height, safeLimit);
            int renderWidth = size[0];
            int renderHeight = size[1];

            // 如果尺寸被缩放了，说明触发了分辨率限制
            if (renderWidth != width || renderHeight != height) {
                // System.out.println("区域尺寸已自动缩放为: " + renderWidth + "x" + renderHeight);
            }

            OdGsModule gsModule = loadGsModuleWithFallback(renderWidth > 8192 || renderHeight > 8192);
            OdGsDevice device = gsModule.createBitmapDevice();
            OdGiContextForDbDatabase context = OdGiContextForDbDatabase.createObject();

            context.setDatabase(database);
            context.enableGsModel(true);
            context.setPlotGeneration(true);

            device = TD_DbCoreIntegrated_Globals.OdDbGsManager_setupActiveLayoutViews(device, context);
            long[] palette = TD_RootIntegrated_Globals.odcmAcadDarkPalette();
            device.setLogicalPalette(palette, 256);
            device.setBackgroundColor(0);
            context.setPaletteBackground(0);

            device.onSize(new OdGsDCRect(new OdGsDCPoint(0, renderHeight), new OdGsDCPoint(renderWidth, 0)));

            OdGsView view = device.viewAt(0);

            if (region != null) {
                OdGePoint3d min = region.minPoint();
                OdGePoint3d max = region.maxPoint();

                double w = Math.abs(max.getX() - min.getX());
                double h = Math.abs(max.getY() - min.getY());

                // 计算中心点
                OdGePoint3d center = new OdGePoint3d(
                        (min.getX() + max.getX()) / 2.0,
                        (min.getY() + max.getY()) / 2.0,
                        (min.getZ() + max.getZ()) / 2.0);

                // 设置视图：目标在 center，相机在 center + Z，上方向为 Y
                OdGePoint3d position = new OdGePoint3d(center.getX(), center.getY(), center.getZ() + 1.0);
                OdGeVector3d upVector = new OdGeVector3d(0, 1, 0);

                // 使用 setView 设置正交投影
                view.setView(position, center, upVector, w, h, OdGsView_Projection.kParallel);
            } else {
                OdAbstractViewPE viewPE = OdAbstractViewPE.cast(view);
                viewPE.zoomExtents(view);
            }

            device.update();

            OdGiRasterImage image = OdGiRasterImage.cast(device.properties().getAt("RasterImage"));
            OdRxRasterServices rasterServices = OdRxRasterServices
                    .cast(TD_RootIntegrated_Globals.odrxDynamicLinker().loadApp("RxRasterServices"));
            if (rasterServices != null) {
                rasterServices.saveRasterImage(image, outputFile, new long[] { 0 });
            }
        } finally {
            MemoryManager.GetMemoryManager().StopTransaction(transaction);
        }
    }

    private static OdGsModule loadGsModuleWithFallback(boolean preferSoftware) {
        String[] prefixes;
        if (preferSoftware) {
            System.out.println("检测到大尺寸输出，优先使用软件渲染模式...");
            prefixes = new String[] { "WinBitmap", "WinGDI", "SoftwareRendering2D", "WinDirectX", "WinOpenGL",
                    "WinGLES2" };
        } else {
            prefixes = new String[] { "WinDirectX", "WinOpenGL", "WinGLES2", "WinGDI", "WinBitmap",
                    "SoftwareRendering2D" };
        }
        OdError lastError = null;

        for (String prefix : prefixes) {
            String moduleFile = findTxvByPrefix(prefix);
            if (moduleFile == null) {
                continue;
            }
            try {
                System.out.println("使用渲染模块: " + moduleFile);
                return OdGsModule.cast(TD_RootIntegrated_Globals.odrxDynamicLinker().loadModule(moduleFile));
            } catch (OdError e) {
                lastError = e;
            }
        }

        if (lastError != null) {
            throw lastError;
        }
        throw new IllegalStateException("未找到可用的 .txv 渲染模块");
    }

    private static String findTxvByPrefix(String prefix) {
        java.io.File dir = new java.io.File(ODA_NATIVE_PATH);
        java.io.File[] matches = dir.listFiles((d, name) -> name.startsWith(prefix) && name.endsWith(".txv"));
        if (matches == null || matches.length == 0) {
            return null;
        }
        java.util.Arrays.sort(matches, java.util.Comparator.comparing(java.io.File::getName));
        return matches[0].getName();
    }

    private static int[] normalizeRenderSize(int width, int height, int max) {
        int w = Math.max(1, width);
        int h = Math.max(1, height);
        if (w <= max && h <= max) {
            return new int[] { w, h };
        }
        double scale = Math.min((double) max / (double) w, (double) max / (double) h);
        int nw = Math.max(1, (int) Math.floor(w * scale));
        int nh = Math.max(1, (int) Math.floor(h * scale));
        return new int[] { nw, nh };
    }

    private static void setLibraryPath(String nativePath) {
        System.setProperty("java.library.path", nativePath);
        try {
            java.lang.reflect.Field field = ClassLoader.class.getDeclaredField("sys_paths");
            field.setAccessible(true);
            field.set(null, null);
        } catch (Exception ignored) {
        }
    }

    private static boolean isPathConfigured(String requiredPath) {
        String path = System.getenv("PATH");
        if (path == null) {
            return false;
        }
        java.io.File req = new java.io.File(requiredPath);
        for (String p : path.split(java.io.File.pathSeparator)) {
            // Simple check
            if (p.equalsIgnoreCase(requiredPath)) {
                return true;
            }
            try {
                if (new java.io.File(p).getCanonicalPath().equalsIgnoreCase(req.getCanonicalPath())) {
                    return true;
                }
            } catch (Exception e) {
            }
        }
        return false;
    }

    private static void relaunchWithCorrectPath(String libPath, String[] args) {
        try {
            String javaHome = System.getProperty("java.home");
            String javaBin = javaHome + java.io.File.separator + "bin" + java.io.File.separator + "java";

            // Reconstruct classpath
            String classpath = System.getProperty("java.class.path");

            java.util.List<String> command = new java.util.ArrayList<>();
            command.add(javaBin);
            command.add("-cp");
            command.add(classpath);
            // Pass library path explicitly too, just in case
            command.add("-Djava.library.path=" + libPath);
            command.add(DwgToPng.class.getName());
            if (args != null) {
                for (String arg : args) {
                    command.add(arg);
                }
            }

            ProcessBuilder pb = new ProcessBuilder(command);

            // Update environment for the new process
            java.util.Map<String, String> env = pb.environment();
            String currentPath = env.get("PATH");
            if (currentPath == null) {
                currentPath = env.get("Path");
            }
            String newPath = libPath + java.io.File.pathSeparator + (currentPath == null ? "" : currentPath);

            env.put("PATH", newPath);
            env.put("Path", newPath);

            pb.inheritIO();
            Process process = pb.start();
            System.exit(process.waitFor());
        } catch (Exception e) {
            e.printStackTrace();
            System.err.println("Failed to relaunch with correct PATH.");
        }
    }

    public static class CustomSystemServices extends RxSystemServicesImpl {
        public CustomSystemServices() {
            TD_RootIntegrated_Globals.odActivate(OdActivationInfo.userInfo, OdActivationInfo.userSignature);
        }
    }

    private static boolean shouldMergeClusters(List<DenseRegionExtractor.Region> c1,
            List<DenseRegionExtractor.Region> c2, int threshold) {
        for (DenseRegionExtractor.Region r1 : c1) {
            for (DenseRegionExtractor.Region r2 : c2) {
                if (getRegionDistance(r1, r2) < threshold) {
                    return true;
                }
            }
        }
        return false;
    }

    private static double getRegionDistance(DenseRegionExtractor.Region r1, DenseRegionExtractor.Region r2) {
        // Calculate gap between rectangles
        // max(r1.right, r2.right) - min(r1.left, r2.left) - r1.width - r2.width
        // If overlap, result is negative. max(0, result) gives 0.
        int left = Math.max(r1.x + r1.w, r2.x + r2.w) - Math.min(r1.x, r2.x) - r1.w - r2.w;
        int top = Math.max(r1.y + r1.h, r2.y + r2.h) - Math.min(r1.y, r2.y) - r1.h - r2.h;
        int dx = Math.max(0, left);
        int dy = Math.max(0, top);
        return Math.sqrt(dx * dx + dy * dy);
    }
}