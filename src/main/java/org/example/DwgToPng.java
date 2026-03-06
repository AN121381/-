package org.example;

import com.opendesign.drawings.td_dbcoreintegrated.*;
import com.opendesign.kernel.td_rootintegrated.*;

import teigha.OdActivationInfo;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
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

    private static void processSingleFile(ExHostAppServices hostApp, String srcFileName, String dstFileName, int width,
            int height, int maxResolutionLimit, int maxRegions, Properties config) {
        if (hostApp.findFile(srcFileName).isEmpty()) {
            System.out.println("找不到.dwg文件: " + srcFileName);
            return;
        }

        boolean rerenderRegions = Boolean.parseBoolean(config.getProperty("rerender_regions", "false"));
        boolean rerenderWhiteFrames = Boolean.parseBoolean(config.getProperty("rerender_white_frames", "false"));
        boolean whiteFrameDebugLog = Boolean.parseBoolean(config.getProperty("white_frame_debug_log", "false"));
        double regionRerenderScale = Double.parseDouble(config.getProperty("region_rerender_scale", "2.0"));
        int regionRerenderMaxDimension = Integer.parseInt(config.getProperty("region_rerender_max_dimension", "16384"));
        long rerenderMaxPixels = Long.parseLong(config.getProperty("rerender_max_pixels", "120000000"));
        boolean keepRegionImages = Boolean.parseBoolean(config.getProperty("keep_region_images", "true"));
        int stitchGap = Integer.parseInt(config.getProperty("stitch_gap", "100"));
        int mergedMaxDimension = Integer.parseInt(config.getProperty("merged_max_dimension", "45000"));
        double regionMarginRatio = Double.parseDouble(config.getProperty("region_margin_ratio", "0.02"));
        boolean keepFullImage = Boolean.parseBoolean(config.getProperty("keep_full_image", "false"));
        double whiteFrameSearchExpandRatio = Double
                .parseDouble(config.getProperty("white_frame_search_expand_ratio", "0.5"));
        int whiteFrameGrayThreshold = Integer.parseInt(config.getProperty("white_frame_gray_threshold", "160"));
        int whiteFrameChannelDelta = Integer.parseInt(config.getProperty("white_frame_channel_delta", "40"));
        int whiteFrameMinBoxDim = Integer.parseInt(config.getProperty("white_frame_min_box_dim", "200"));
        double whiteFrameMinSideCoverage = Double
                .parseDouble(config.getProperty("white_frame_min_side_coverage", "0.12"));
        double whiteFrameMaxInnerCoverage = Double
                .parseDouble(config.getProperty("white_frame_max_inner_coverage", "0.06"));
        int whiteFrameMarginPx = Integer.parseInt(config.getProperty("white_frame_margin_px", "20"));
        int whiteFrameMaxFrames = Integer.parseInt(config.getProperty("white_frame_max_frames", "3"));
        double dwgFrameSearchExpandRatio = Double
                .parseDouble(config.getProperty("dwg_frame_search_expand_ratio", "1.0"));
        double dwgFrameMarginRatio = Double.parseDouble(config.getProperty("dwg_frame_margin_ratio", "0.02"));
        double dwgFrameRerenderScale = Double
                .parseDouble(config.getProperty("dwg_frame_rerender_scale", Double.toString(regionRerenderScale)));
        double dwgFrameMinAreaRatio = Double.parseDouble(config.getProperty("dwg_frame_min_area_ratio", "0.02"));
        boolean dwgFrameDebugLog = Boolean.parseBoolean(config.getProperty("dwg_frame_debug_log", "false"));
        boolean dwgFrameOnly = Boolean.parseBoolean(config.getProperty("dwg_frame_only", "false"));
        boolean effectiveRerenderRegions = rerenderRegions || rerenderWhiteFrames;

        System.out.println("模式: rerender_regions=" + rerenderRegions + ", rerender_white_frames=" + rerenderWhiteFrames
                + ", effective_rerender_regions=" + effectiveRerenderRegions + ", dwg_frame_only=" + dwgFrameOnly
                + ", dwg_frame_debug_log=" + dwgFrameDebugLog);
        // 使用临时 ASCII 文件名以避免潜在的路径编码问题
        java.io.File dstFile = new java.io.File(dstFileName);
        String tempFileName = "temp_" + System.currentTimeMillis() + "_" + (int) (Math.random() * 1000) + ".png";
        java.io.File tempFile = new java.io.File(dstFile.getParent(), tempFileName);
        String tempFilePath = tempFile.getAbsolutePath();

        RenderResult fullRender;
        OdDbDatabase database;
        try {
            database = hostApp.readFile(srcFileName);
            fullRender = renderToImage(database, tempFilePath, width, height, maxResolutionLimit);
            System.out.println("导出全图完成: " + dstFileName); // 用户感知到的文件名
        } catch (Exception e) {
            // 如果渲染失败，删除临时文件并抛出异常
            tempFile.delete();
            throw e;
        }

        java.nio.file.Path finalOutPath = java.nio.file.Paths.get(dstFileName);
        // 注意：这里 tempOutPath 是用于 DenseRegionExtractor 的中间文件，
        // 但我们刚刚生成的 tempFilePath 才是全图。
        // 原逻辑是：生成全图 -> 移动到 .temp.png -> 提取区域 -> 覆盖全图。

        // 修改后的逻辑：
        // 1. 全图生成在 tempFilePath (ASCII)
        // 2. 将 tempFilePath 移动到 .temp.png (作为 DenseRegionExtractor 的输入)
        // 3. DenseRegionExtractor 输出到 finalOutPath (中文路径)

        java.nio.file.Path tempRegionInputPath = finalOutPath.resolveSibling(finalOutPath.getFileName() + ".temp.png");

        try {
            // 移动生成的 ASCII 临时全图到处理用的临时路径
            java.nio.file.Files.move(tempFile.toPath(), tempRegionInputPath,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            System.err.println("无法移动临时文件: " + e.getMessage());
            // 尝试删除原始临时文件
            tempFile.delete();
            return;
        }

        boolean success = false;
        try {
            DenseRegionExtractor.Options options = new DenseRegionExtractor.Options();
            options.maxRegions = Math.max(1, maxRegions);

            // 加载高级参数
            if (config.containsKey("region_percentile")) {
                options.percentile = Double.parseDouble(config.getProperty("region_percentile"));
            }
            if (config.containsKey("min_cell_fill")) {
                options.minCellFill = Double.parseDouble(config.getProperty("min_cell_fill"));
            }
            if (config.containsKey("min_region_pixels")) {
                options.minRegionPixels = Integer.parseInt(config.getProperty("min_region_pixels"));
            }
            if (config.containsKey("margin_cells")) {
                options.marginCells = Integer.parseInt(config.getProperty("margin_cells"));
            }
            if (config.containsKey("bg_delta")) {
                options.bgDelta = Integer.parseInt(config.getProperty("bg_delta"));
            }
            if (config.containsKey("grid_target_cells_across")) {
                options.gridTargetCellsAcross = Integer.parseInt(config.getProperty("grid_target_cells_across"));
            }
            if (config.containsKey("score_threshold_ratio")) {
                options.scoreThresholdRatio = Double.parseDouble(config.getProperty("score_threshold_ratio"));
            }
            if (config.containsKey("min_region_density")) {
                options.minRegionDensity = Double.parseDouble(config.getProperty("min_region_density"));
            }
            if (config.containsKey("min_region_fill_ratio")) {
                options.minRegionFillRatio = Double.parseDouble(config.getProperty("min_region_fill_ratio"));
            }

            if (!effectiveRerenderRegions) {
                DenseRegionExtractor.extractDenseRegions(tempRegionInputPath, finalOutPath, options);
                success = true;
                System.out.println("密集区域提取完成: " + dstFileName);
            } else {
                DenseRegionExtractor.DetectionResult detected = DenseRegionExtractor
                        .detectDenseRegions(tempRegionInputPath, options);
                if (detected.regions.isEmpty()) {
                    if (rerenderWhiteFrames) {
                        ViewState.RegionView anchorWorld = new ViewState.RegionView(fullRender.view.targetX,
                                fullRender.view.targetY, fullRender.view.targetZ, fullRender.view.fieldWidth,
                                fullRender.view.fieldHeight);
                        OdGeExtents3d searchWorld = worldExtents(anchorWorld.targetX, anchorWorld.targetY,
                                anchorWorld.fieldWidth, anchorWorld.fieldHeight, dwgFrameSearchExpandRatio);

                        DwgFrameFinder.Options ffOpt = new DwgFrameFinder.Options();
                        ffOpt.maxFrames = Math.max(1, whiteFrameMaxFrames);
                        ffOpt.requireRect = true;
                        ffOpt.minArea = Math.max(1e-9,
                                anchorWorld.fieldWidth * anchorWorld.fieldHeight * dwgFrameMinAreaRatio);

                        java.util.List<OdGeExtents3d> dwgFrames = DwgFrameFinder.findFrames(database, searchWorld,
                                ffOpt);
                        if (dwgFrames.size() < ffOpt.maxFrames) {
                            DwgFrameFinder.Options relaxed = new DwgFrameFinder.Options();
                            relaxed.maxFrames = ffOpt.maxFrames;
                            relaxed.requireRect = false;
                            relaxed.minArea = ffOpt.minArea * 0.1;
                            relaxed.maxIouToKeepBoth = ffOpt.maxIouToKeepBoth;
                            java.util.List<OdGeExtents3d> more = DwgFrameFinder.findFrames(database, searchWorld,
                                    relaxed);
                            for (OdGeExtents3d e : more) {
                                if (dwgFrames.size() >= relaxed.maxFrames) {
                                    break;
                                }
                                dwgFrames.add(e);
                            }
                        }

                        if (dwgFrameDebugLog) {
                            System.out.println("DWG外框搜索范围: min=(" + fmt(searchWorld.minPoint().getX()) + ","
                                    + fmt(searchWorld.minPoint().getY()) + ") max=("
                                    + fmt(searchWorld.maxPoint().getX()) + "," + fmt(searchWorld.maxPoint().getY())
                                    + ")");
                            for (int i = 0; i < dwgFrames.size(); i++) {
                                OdGeExtents3d e = dwgFrames.get(i);
                                System.out.println("DWG外框[" + (i + 1) + "]: min=(" + fmt(e.minPoint().getX()) + ","
                                        + fmt(e.minPoint().getY()) + ") max=(" + fmt(e.maxPoint().getX()) + ","
                                        + fmt(e.maxPoint().getY()) + ")");
                            }
                        }

                        if (!dwgFrames.isEmpty()) {
                            java.util.List<java.nio.file.Path> frameImages = new java.util.ArrayList<>();

                            String outName = finalOutPath.getFileName().toString();
                            String baseName = outName.toLowerCase().endsWith(".png")
                                    ? outName.substring(0, outName.length() - 4)
                                    : outName;

                            double pxPerWorldX = fullRender.view.fieldWidth <= 0 ? 1.0
                                    : ((double) fullRender.renderWidth / fullRender.view.fieldWidth);
                            double pxPerWorldY = fullRender.view.fieldHeight <= 0 ? 1.0
                                    : ((double) fullRender.renderHeight / fullRender.view.fieldHeight);

                            int idx = 1;
                            for (OdGeExtents3d e0 : dwgFrames) {
                                if (idx > whiteFrameMaxFrames) {
                                    break;
                                }
                                OdGeExtents3d e = expandExtents(e0, dwgFrameMarginRatio);
                                double fw = e.maxPoint().getX() - e.minPoint().getX();
                                double fh = e.maxPoint().getY() - e.minPoint().getY();
                                int basePxW = Math.max(1, (int) Math.round(fw * pxPerWorldX));
                                int basePxH = Math.max(1, (int) Math.round(fh * pxPerWorldY));

                                int[] tileSize = computeRerenderSize(basePxW, basePxH, dwgFrameRerenderScale,
                                        regionRerenderMaxDimension, rerenderMaxPixels);
                                int tileW = tileSize[0];
                                int tileH = tileSize[1];

                                String frameFileName = baseName + "_frame_" + idx + ".png";
                                java.nio.file.Path frameOutPath = finalOutPath.resolveSibling(frameFileName);

                                java.io.File frameTempFile = new java.io.File(dstFile.getParent(),
                                        "temp_frame_" + System.currentTimeMillis() + "_" + idx + ".png");
                                boolean moved = false;
                                try {
                                    renderWorldExtentsToImage(database, frameTempFile.getAbsolutePath(), tileW, tileH,
                                            maxResolutionLimit, fullRender.view, e);

                                    java.nio.file.Files.move(frameTempFile.toPath(), frameOutPath,
                                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                                    moved = true;
                                } finally {
                                    if (!moved) {
                                        try {
                                            java.nio.file.Files.deleteIfExists(frameTempFile.toPath());
                                        } catch (Exception ignored) {
                                        }
                                    }
                                }

                                frameImages.add(frameOutPath);
                                idx++;
                            }

                            if (!frameImages.isEmpty()) {
                                stitchVertically(frameImages, finalOutPath, stitchGap, mergedMaxDimension);
                                success = true;
                                System.out.println("DWG外框二次渲染并拼接完成: " + dstFileName);

                                if (!keepRegionImages) {
                                    for (java.nio.file.Path p : frameImages) {
                                        try {
                                            java.nio.file.Files.deleteIfExists(p);
                                        } catch (Exception ignored) {
                                        }
                                    }
                                }
                                return;
                            }
                        }

                        if (dwgFrameOnly) {
                            java.nio.file.Files.move(tempRegionInputPath, finalOutPath,
                                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                            success = true;
                            System.out.println("未检测到密集区域且未识别到DWG外框，输出全图: " + dstFileName);
                            return;
                        }
                    }

                    java.nio.file.Files.move(tempRegionInputPath, finalOutPath,
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    success = true;
                    System.out.println("未检测到密集区域，输出全图: " + dstFileName);
                } else {
                    if (rerenderWhiteFrames) {
                        DenseRegionExtractor.Region anchor = detected.regions.get(0);
                        for (DenseRegionExtractor.Region rr : detected.regions) {
                            if (rr.score > anchor.score) {
                                anchor = rr;
                            }
                        }

                        ViewState.RegionView anchorWorld = fullRender.view.regionForPixelRect(fullRender.renderWidth,
                                fullRender.renderHeight,
                                new java.awt.Rectangle(anchor.x, anchor.y, anchor.w, anchor.h));
                        OdGeExtents3d searchWorld = worldExtents(anchorWorld.targetX, anchorWorld.targetY,
                                anchorWorld.fieldWidth, anchorWorld.fieldHeight, dwgFrameSearchExpandRatio);

                        DwgFrameFinder.Options ffOpt = new DwgFrameFinder.Options();
                        ffOpt.maxFrames = Math.max(1, whiteFrameMaxFrames);
                        ffOpt.requireRect = true;
                        ffOpt.minArea = Math.max(1e-9,
                                anchorWorld.fieldWidth * anchorWorld.fieldHeight * dwgFrameMinAreaRatio);

                        java.util.List<OdGeExtents3d> dwgFrames = DwgFrameFinder.findFrames(database, searchWorld,
                                ffOpt);
                        if (dwgFrames.size() < ffOpt.maxFrames) {
                            DwgFrameFinder.Options relaxed = new DwgFrameFinder.Options();
                            relaxed.maxFrames = ffOpt.maxFrames;
                            relaxed.requireRect = false;
                            relaxed.minArea = ffOpt.minArea * 0.1;
                            relaxed.maxIouToKeepBoth = ffOpt.maxIouToKeepBoth;
                            java.util.List<OdGeExtents3d> more = DwgFrameFinder.findFrames(database, searchWorld,
                                    relaxed);
                            for (OdGeExtents3d e : more) {
                                if (dwgFrames.size() >= relaxed.maxFrames) {
                                    break;
                                }
                                dwgFrames.add(e);
                            }
                        }

                        if (dwgFrameDebugLog) {
                            System.out.println("DWG外框搜索范围: min=(" + fmt(searchWorld.minPoint().getX()) + ","
                                    + fmt(searchWorld.minPoint().getY()) + ") max=("
                                    + fmt(searchWorld.maxPoint().getX()) + "," + fmt(searchWorld.maxPoint().getY())
                                    + ")");
                            for (int i = 0; i < dwgFrames.size(); i++) {
                                OdGeExtents3d e = dwgFrames.get(i);
                                System.out.println("DWG外框[" + (i + 1) + "]: min=(" + fmt(e.minPoint().getX()) + ","
                                        + fmt(e.minPoint().getY()) + ") max=(" + fmt(e.maxPoint().getX()) + ","
                                        + fmt(e.maxPoint().getY()) + ")");
                            }
                        }

                        if (!dwgFrames.isEmpty()) {
                            java.util.List<java.nio.file.Path> frameImages = new java.util.ArrayList<>();

                            String outName = finalOutPath.getFileName().toString();
                            String baseName = outName.toLowerCase().endsWith(".png")
                                    ? outName.substring(0, outName.length() - 4)
                                    : outName;

                            double pxPerWorldX = fullRender.view.fieldWidth <= 0 ? 1.0
                                    : ((double) fullRender.renderWidth / fullRender.view.fieldWidth);
                            double pxPerWorldY = fullRender.view.fieldHeight <= 0 ? 1.0
                                    : ((double) fullRender.renderHeight / fullRender.view.fieldHeight);

                            int idx = 1;
                            for (OdGeExtents3d e0 : dwgFrames) {
                                if (idx > whiteFrameMaxFrames) {
                                    break;
                                }
                                OdGeExtents3d e = expandExtents(e0, dwgFrameMarginRatio);
                                double fw = e.maxPoint().getX() - e.minPoint().getX();
                                double fh = e.maxPoint().getY() - e.minPoint().getY();
                                int basePxW = Math.max(1, (int) Math.round(fw * pxPerWorldX));
                                int basePxH = Math.max(1, (int) Math.round(fh * pxPerWorldY));

                                int[] tileSize = computeRerenderSize(basePxW, basePxH, dwgFrameRerenderScale,
                                        regionRerenderMaxDimension, rerenderMaxPixels);
                                int tileW = tileSize[0];
                                int tileH = tileSize[1];

                                String frameFileName = baseName + "_frame_" + idx + ".png";
                                java.nio.file.Path frameOutPath = finalOutPath.resolveSibling(frameFileName);

                                java.io.File frameTempFile = new java.io.File(dstFile.getParent(),
                                        "temp_frame_" + System.currentTimeMillis() + "_" + idx + ".png");
                                boolean moved = false;
                                try {
                                    renderWorldExtentsToImage(database, frameTempFile.getAbsolutePath(), tileW, tileH,
                                            maxResolutionLimit, fullRender.view, e);

                                    java.nio.file.Files.move(frameTempFile.toPath(), frameOutPath,
                                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                                    moved = true;
                                } finally {
                                    if (!moved) {
                                        try {
                                            java.nio.file.Files.deleteIfExists(frameTempFile.toPath());
                                        } catch (Exception ignored) {
                                        }
                                    }
                                }

                                frameImages.add(frameOutPath);
                                idx++;
                            }

                            if (!frameImages.isEmpty()) {
                                stitchVertically(frameImages, finalOutPath, stitchGap, mergedMaxDimension);
                                success = true;
                                System.out.println("DWG外框二次渲染并拼接完成: " + dstFileName);

                                if (!keepRegionImages) {
                                    for (java.nio.file.Path p : frameImages) {
                                        try {
                                            java.nio.file.Files.deleteIfExists(p);
                                        } catch (Exception ignored) {
                                        }
                                    }
                                }
                                return;
                            }
                        }
                        if (!dwgFrameOnly) {
                            java.awt.Rectangle search = new java.awt.Rectangle(anchor.x, anchor.y, anchor.w, anchor.h);
                            int expand = (int) Math
                                    .round(Math.max(search.width, search.height) * whiteFrameSearchExpandRatio);
                            if (expand > 0) {
                                search.grow(expand, expand);
                            }
                            search = search.intersection(
                                    new java.awt.Rectangle(0, 0, fullRender.renderWidth, fullRender.renderHeight));

                            WhiteFrameDetector.Options wfOpt = new WhiteFrameDetector.Options();
                            wfOpt.maxFrames = Math.max(1, whiteFrameMaxFrames);
                            wfOpt.grayThreshold = whiteFrameGrayThreshold;
                            wfOpt.channelDelta = whiteFrameChannelDelta;
                            wfOpt.minBoxDim = whiteFrameMinBoxDim;
                            wfOpt.minSideCoverage = whiteFrameMinSideCoverage;
                            wfOpt.maxInnerCoverage = whiteFrameMaxInnerCoverage;
                            wfOpt.frameMarginPx = whiteFrameMarginPx;

                            java.util.List<java.awt.Rectangle> frames = WhiteFrameDetector
                                    .findWhiteFrames(tempRegionInputPath, search, wfOpt);

                            if (whiteFrameDebugLog) {
                                System.out.println("白框检测锚点: x=" + anchor.x + ",y=" + anchor.y + ",w=" + anchor.w + ",h="
                                        + anchor.h + ",score=" + String.format("%.6f", anchor.score));
                                System.out.println("白框搜索范围: x=" + search.x + ",y=" + search.y + ",w=" + search.width
                                        + ",h=" + search.height);
                                for (int i = 0; i < frames.size(); i++) {
                                    java.awt.Rectangle fr = frames.get(i);
                                    System.out.println("白框[" + (i + 1) + "]: x=" + fr.x + ",y=" + fr.y + ",w="
                                            + fr.width + ",h=" + fr.height);
                                }
                            }

                            if (!frames.isEmpty()) {
                                java.util.List<java.nio.file.Path> frameImages = new java.util.ArrayList<>();

                                String outName = finalOutPath.getFileName().toString();
                                String baseName = outName.toLowerCase().endsWith(".png")
                                        ? outName.substring(0, outName.length() - 4)
                                        : outName;

                                int idx = 1;
                                for (java.awt.Rectangle rect : frames) {
                                    int[] tileSize = computeRerenderSize(rect.width, rect.height, regionRerenderScale,
                                            regionRerenderMaxDimension, rerenderMaxPixels);
                                    int tileW = tileSize[0];
                                    int tileH = tileSize[1];

                                    String frameFileName = baseName + "_frame_" + idx + ".png";
                                    java.nio.file.Path frameOutPath = finalOutPath.resolveSibling(frameFileName);

                                    java.io.File frameTempFile = new java.io.File(dstFile.getParent(),
                                            "temp_frame_" + System.currentTimeMillis() + "_" + idx + ".png");
                                    boolean moved = false;
                                    try {
                                        renderRegionToImage(database, frameTempFile.getAbsolutePath(), tileW, tileH,
                                                maxResolutionLimit, fullRender.view, fullRender.renderWidth,
                                                fullRender.renderHeight, rect);

                                        java.nio.file.Files.move(frameTempFile.toPath(), frameOutPath,
                                                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                                        moved = true;
                                    } finally {
                                        if (!moved) {
                                            try {
                                                java.nio.file.Files.deleteIfExists(frameTempFile.toPath());
                                            } catch (Exception ignored) {
                                            }
                                        }
                                    }

                                    frameImages.add(frameOutPath);
                                    idx++;
                                }

                                stitchVertically(frameImages, finalOutPath, stitchGap, mergedMaxDimension);
                                success = true;
                                System.out.println("白框二次渲染并拼接完成: " + dstFileName);

                                if (!keepRegionImages) {
                                    for (java.nio.file.Path p : frameImages) {
                                        try {
                                            java.nio.file.Files.deleteIfExists(p);
                                        } catch (Exception ignored) {
                                        }
                                    }
                                }
                                return;
                            }
                        }
                    }

                    java.util.List<java.nio.file.Path> regionImages = new java.util.ArrayList<>();

                    String outName = finalOutPath.getFileName().toString();
                    String baseName = outName.toLowerCase().endsWith(".png")
                            ? outName.substring(0, outName.length() - 4)
                            : outName;

                    int idx = 1;
                    for (DenseRegionExtractor.Region r : detected.regions) {
                        java.awt.Rectangle rect = new java.awt.Rectangle(r.x, r.y, r.w, r.h);
                        int margin = (int) Math.round(Math.max(rect.width, rect.height) * regionMarginRatio);
                        if (margin > 0) {
                            rect.grow(margin, margin);
                        }
                        rect = rect.intersection(
                                new java.awt.Rectangle(0, 0, fullRender.renderWidth, fullRender.renderHeight));
                        if (rect.isEmpty()) {
                            continue;
                        }

                        int[] tileSize = computeRerenderSize(rect.width, rect.height, regionRerenderScale,
                                regionRerenderMaxDimension, rerenderMaxPixels);
                        int tileW = tileSize[0];
                        int tileH = tileSize[1];

                        String regionFileName = baseName + "_region_" + idx + ".png";
                        java.nio.file.Path regionOutPath = finalOutPath.resolveSibling(regionFileName);

                        java.io.File regionTempFile = new java.io.File(dstFile.getParent(),
                                "temp_region_" + System.currentTimeMillis() + "_" + idx + ".png");
                        boolean moved = false;
                        try {
                            renderRegionToImage(database, regionTempFile.getAbsolutePath(), tileW, tileH,
                                    maxResolutionLimit, fullRender.view, fullRender.renderWidth,
                                    fullRender.renderHeight, rect);

                            java.nio.file.Files.move(regionTempFile.toPath(), regionOutPath,
                                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                            moved = true;
                        } finally {
                            if (!moved) {
                                try {
                                    java.nio.file.Files.deleteIfExists(regionTempFile.toPath());
                                } catch (Exception ignored) {
                                }
                            }
                        }

                        regionImages.add(regionOutPath);
                        idx++;
                    }

                    if (regionImages.isEmpty()) {
                        java.nio.file.Files.move(tempRegionInputPath, finalOutPath,
                                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        success = true;
                        System.out.println("区域渲染跳过（无有效区域），输出全图: " + dstFileName);
                    } else {
                        stitchVertically(regionImages, finalOutPath, stitchGap, mergedMaxDimension);
                        success = true;
                        System.out.println("区域二次渲染并拼接完成: " + dstFileName);
                    }

                    if (!keepRegionImages) {
                        for (java.nio.file.Path p : regionImages) {
                            try {
                                java.nio.file.Files.deleteIfExists(p);
                            } catch (Exception ignored) {
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("密集区域提取过程中发生错误: " + e.getMessage());
            e.printStackTrace();
            throw new RuntimeException(e);
        } finally {
            if (success) {
                // 成功则清理临时全图
                if (!keepFullImage) {
                    try {
                        java.nio.file.Files.deleteIfExists(tempRegionInputPath);
                    } catch (IOException ignored) {
                    }
                }
            } else {
                // 如果失败，尝试恢复原图 (从 tempRegionInputPath 移动回 finalOutPath)
                System.err.println("正在恢复原图...");
                try {
                    if (java.nio.file.Files.exists(tempRegionInputPath)) {
                        java.nio.file.Files.move(tempRegionInputPath, finalOutPath,
                                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    }
                } catch (Exception restoreEx) {
                    System.err.println("无法恢复原图: " + restoreEx.getMessage());
                }
            }
        }
    }

    private static RenderResult renderToImage(OdDbDatabase database, String outputFile, int width, int height,
            int maxResolutionLimit) {
        MemoryTransaction transaction = MemoryManager.GetMemoryManager().StartTransaction();
        try {
            int[] size = normalizeRenderSize(width, height, maxResolutionLimit);
            int renderWidth = size[0];
            int renderHeight = size[1];
            if (renderWidth != width || renderHeight != height) {
                System.out.println("渲染尺寸过大，已自动缩放为: " + renderWidth + "x" + renderHeight);
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
            OdAbstractViewPE viewPE = OdAbstractViewPE.cast(view);
            viewPE.zoomExtents(view);

            ViewState state = ViewState.fromView(view, renderWidth, renderHeight);

            device.update();

            OdGiRasterImage image = OdGiRasterImage.cast(device.properties().getAt("RasterImage"));
            OdRxRasterServices rasterServices = OdRxRasterServices
                    .cast(TD_RootIntegrated_Globals.odrxDynamicLinker().loadApp("RxRasterServices"));
            if (rasterServices != null) {
                rasterServices.saveRasterImage(image, outputFile, new long[] { 0 });
            }
            return new RenderResult(renderWidth, renderHeight, state);
        } finally {
            MemoryManager.GetMemoryManager().StopTransaction(transaction);
        }
    }

    private static void renderRegionToImage(OdDbDatabase database, String outputFile, int width, int height,
            int maxResolutionLimit, ViewState baseView, int basePixelWidth, int basePixelHeight,
            java.awt.Rectangle pixelRect) {
        MemoryTransaction transaction = MemoryManager.GetMemoryManager().StartTransaction();
        try {
            int[] size = normalizeRenderSize(width, height, maxResolutionLimit);
            int renderWidth = size[0];
            int renderHeight = size[1];

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
            baseView.applyTo(view);

            ViewState.RegionView rv = baseView.regionForPixelRect(basePixelWidth, basePixelHeight, pixelRect);
            rv.applyTo(view, baseView);

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

    private static void renderWorldExtentsToImage(OdDbDatabase database, String outputFile, int width, int height,
            int maxResolutionLimit, ViewState baseView, OdGeExtents3d worldExtents) {
        MemoryTransaction transaction = MemoryManager.GetMemoryManager().StartTransaction();
        try {
            int[] size = normalizeRenderSize(width, height, maxResolutionLimit);
            int renderWidth = size[0];
            int renderHeight = size[1];

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
            baseView.applyTo(view);

            double minX = worldExtents.minPoint().getX();
            double minY = worldExtents.minPoint().getY();
            double maxX = worldExtents.maxPoint().getX();
            double maxY = worldExtents.maxPoint().getY();
            double cx = (minX + maxX) * 0.5;
            double cy = (minY + maxY) * 0.5;
            double fw = Math.max(1e-9, maxX - minX);
            double fh = Math.max(1e-9, maxY - minY);

            ViewState.RegionView rv = new ViewState.RegionView(cx, cy, 0.0, fw, fh);
            rv.applyTo(view, baseView);

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

    private static OdGeExtents3d worldExtents(double centerX, double centerY, double fieldWidth, double fieldHeight,
            double expandRatio) {
        double hw = fieldWidth * 0.5;
        double hh = fieldHeight * 0.5;
        double ex = Math.max(0.0, fieldWidth * Math.max(0.0, expandRatio));
        double ey = Math.max(0.0, fieldHeight * Math.max(0.0, expandRatio));
        double minX = centerX - hw - ex;
        double maxX = centerX + hw + ex;
        double minY = centerY - hh - ey;
        double maxY = centerY + hh + ey;
        return new OdGeExtents3d(new OdGePoint3d(minX, minY, 0), new OdGePoint3d(maxX, maxY, 0));
    }

    private static OdGeExtents3d expandExtents(OdGeExtents3d e, double marginRatio) {
        double minX = e.minPoint().getX();
        double minY = e.minPoint().getY();
        double maxX = e.maxPoint().getX();
        double maxY = e.maxPoint().getY();
        double w = maxX - minX;
        double h = maxY - minY;
        double mx = Math.max(0.0, w * Math.max(0.0, marginRatio));
        double my = Math.max(0.0, h * Math.max(0.0, marginRatio));
        return new OdGeExtents3d(new OdGePoint3d(minX - mx, minY - my, 0), new OdGePoint3d(maxX + mx, maxY + my, 0));
    }

    private static String fmt(double v) {
        return String.format("%.3f", v);
    }

    private static int[] computeRerenderSize(int baseW, int baseH, double scale, int maxDim, long maxPixels) {
        int w = Math.max(1, (int) Math.round(Math.max(1.0, scale) * (double) Math.max(1, baseW)));
        int h = Math.max(1, (int) Math.round(Math.max(1.0, scale) * (double) Math.max(1, baseH)));
        if (maxDim > 0 && (w > maxDim || h > maxDim)) {
            double s = Math.min((double) maxDim / (double) w, (double) maxDim / (double) h);
            w = Math.max(1, (int) Math.floor(w * s));
            h = Math.max(1, (int) Math.floor(h * s));
        }
        if (maxPixels > 0) {
            long area = (long) w * (long) h;
            if (area > maxPixels) {
                double s = Math.sqrt((double) maxPixels / (double) area);
                w = Math.max(1, (int) Math.floor(w * s));
                h = Math.max(1, (int) Math.floor(h * s));
            }
        }
        return new int[] { w, h };
    }

    private static void stitchVertically(java.util.List<java.nio.file.Path> regionPngs, java.nio.file.Path outPng,
            int gap, int maxDim) throws IOException {
        java.util.List<java.awt.image.BufferedImage> images = new java.util.ArrayList<>();
        int maxW = 0;
        int totalH = 0;
        for (java.nio.file.Path p : regionPngs) {
            java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(p.toFile());
            if (img == null) {
                continue;
            }
            images.add(img);
            maxW = Math.max(maxW, img.getWidth());
            totalH += img.getHeight();
        }

        if (images.isEmpty()) {
            return;
        }

        totalH += gap * Math.max(0, images.size() - 1);

        double scale = 1.0;
        if (maxDim > 0) {
            if (maxW > maxDim || totalH > maxDim) {
                scale = Math.min((double) maxDim / (double) maxW, (double) maxDim / (double) totalH);
                scale = Math.max(0.01, Math.min(1.0, scale));
            }
        }

        int outW = Math.max(1, (int) Math.floor(maxW * scale));
        int outH = Math.max(1, (int) Math.floor(totalH * scale));

        java.awt.image.BufferedImage merged = new java.awt.image.BufferedImage(outW, outH,
                java.awt.image.BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = merged.createGraphics();
        try {
            g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                    java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(java.awt.RenderingHints.KEY_RENDERING, java.awt.RenderingHints.VALUE_RENDER_QUALITY);

            int y = 0;
            int scaledGap = (int) Math.round(gap * scale);
            for (java.awt.image.BufferedImage img : images) {
                int sw = Math.max(1, (int) Math.floor(img.getWidth() * scale));
                int sh = Math.max(1, (int) Math.floor(img.getHeight() * scale));
                int x = (outW - sw) / 2;
                g.drawImage(img, x, y, sw, sh, null);
                y += sh + scaledGap;
            }
        } finally {
            g.dispose();
        }
        javax.imageio.ImageIO.write(merged, "png", outPng.toFile());
    }

    private static final class RenderResult {
        public final int renderWidth;
        public final int renderHeight;
        public final ViewState view;

        private RenderResult(int renderWidth, int renderHeight, ViewState view) {
            this.renderWidth = renderWidth;
            this.renderHeight = renderHeight;
            this.view = view;
        }
    }

    private static final class ViewState {
        public final double posX;
        public final double posY;
        public final double posZ;
        public final double targetX;
        public final double targetY;
        public final double targetZ;
        public final double upX;
        public final double upY;
        public final double upZ;
        public final double viewDirX;
        public final double viewDirY;
        public final double viewDirZ;
        public final double rightX;
        public final double rightY;
        public final double rightZ;
        public final double upViewX;
        public final double upViewY;
        public final double upViewZ;
        public final double distance;
        public final double fieldWidth;
        public final double fieldHeight;
        public final OdGsView_Projection projection;
        public final int pixelWidth;
        public final int pixelHeight;

        private ViewState(double posX, double posY, double posZ, double targetX, double targetY, double targetZ,
                double upX, double upY, double upZ, double viewDirX, double viewDirY, double viewDirZ, double rightX,
                double rightY, double rightZ, double upViewX, double upViewY, double upViewZ, double distance,
                double fieldWidth, double fieldHeight, OdGsView_Projection projection, int pixelWidth,
                int pixelHeight) {
            this.posX = posX;
            this.posY = posY;
            this.posZ = posZ;
            this.targetX = targetX;
            this.targetY = targetY;
            this.targetZ = targetZ;
            this.upX = upX;
            this.upY = upY;
            this.upZ = upZ;
            this.viewDirX = viewDirX;
            this.viewDirY = viewDirY;
            this.viewDirZ = viewDirZ;
            this.rightX = rightX;
            this.rightY = rightY;
            this.rightZ = rightZ;
            this.upViewX = upViewX;
            this.upViewY = upViewY;
            this.upViewZ = upViewZ;
            this.distance = distance;
            this.fieldWidth = fieldWidth;
            this.fieldHeight = fieldHeight;
            this.projection = projection;
            this.pixelWidth = pixelWidth;
            this.pixelHeight = pixelHeight;
        }

        public static ViewState fromView(OdGsView view, int pixelWidth, int pixelHeight) {
            OdGePoint3d pos = view.position();
            OdGePoint3d target = view.target();
            OdGeVector3d up = view.upVector();

            OdGeVector3d viewDir = target.subtract(pos).normalize();
            OdGeVector3d right = viewDir.crossProduct(up).normalize();
            OdGeVector3d upView = right.crossProduct(viewDir).normalize();

            double dist = pos.distanceTo(target);

            OdGsView_Projection proj = view.isPerspective() ? OdGsView_Projection.kPerspective
                    : OdGsView_Projection.kParallel;

            return new ViewState(pos.getX(), pos.getY(), pos.getZ(), target.getX(), target.getY(), target.getZ(),
                    up.getX(), up.getY(), up.getZ(), viewDir.getX(), viewDir.getY(), viewDir.getZ(), right.getX(),
                    right.getY(), right.getZ(), upView.getX(), upView.getY(), upView.getZ(), dist, view.fieldWidth(),
                    view.fieldHeight(), proj, pixelWidth, pixelHeight);
        }

        public void applyTo(OdGsView view) {
            view.setView(new OdGePoint3d(posX, posY, posZ), new OdGePoint3d(targetX, targetY, targetZ),
                    new OdGeVector3d(upX, upY, upZ), fieldWidth, fieldHeight, projection);
        }

        public RegionView regionForPixelRect(int basePixelWidth, int basePixelHeight, java.awt.Rectangle rect) {
            int pw = basePixelWidth <= 0 ? pixelWidth : basePixelWidth;
            int ph = basePixelHeight <= 0 ? pixelHeight : basePixelHeight;

            double u0 = ((double) rect.x / (double) pw) - 0.5;
            double u1 = ((double) (rect.x + rect.width) / (double) pw) - 0.5;
            double v0 = 0.5 - ((double) rect.y / (double) ph);
            double v1 = 0.5 - ((double) (rect.y + rect.height) / (double) ph);

            double uCenter = (u0 + u1) * 0.5;
            double vCenter = (v0 + v1) * 0.5;

            double dx = uCenter * fieldWidth;
            double dy = vCenter * fieldHeight;

            double centerX = targetX + rightX * dx + upViewX * dy;
            double centerY = targetY + rightY * dx + upViewY * dy;
            double centerZ = targetZ + rightZ * dx + upViewZ * dy;

            double rw = Math.max(1e-9, Math.abs(u1 - u0) * fieldWidth);
            double rh = Math.max(1e-9, Math.abs(v0 - v1) * fieldHeight);

            return new RegionView(centerX, centerY, centerZ, rw, rh);
        }

        public static final class RegionView {
            public final double targetX;
            public final double targetY;
            public final double targetZ;
            public final double fieldWidth;
            public final double fieldHeight;

            private RegionView(double targetX, double targetY, double targetZ, double fieldWidth, double fieldHeight) {
                this.targetX = targetX;
                this.targetY = targetY;
                this.targetZ = targetZ;
                this.fieldWidth = fieldWidth;
                this.fieldHeight = fieldHeight;
            }

            public void applyTo(OdGsView view, ViewState base) {
                double posX = targetX - base.viewDirX * base.distance;
                double posY = targetY - base.viewDirY * base.distance;
                double posZ = targetZ - base.viewDirZ * base.distance;

                view.setView(new OdGePoint3d(posX, posY, posZ), new OdGePoint3d(targetX, targetY, targetZ),
                        new OdGeVector3d(base.upX, base.upY, base.upZ), fieldWidth, fieldHeight, base.projection);
            }
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
}
