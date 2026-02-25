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
        MemoryTransaction transaction = memoryManager.StartTransaction();
        boolean initialized = false;
        try {
            CustomSystemServices systemServices = new CustomSystemServices();
            ExHostAppServices hostApp = new ExHostAppServices();
            hostApp.disableOutput(true);

            TD_DbCoreIntegrated_Globals.odInitialize(systemServices);
            initialized = true;

            if (hostApp.findFile(srcFileName).isEmpty()) {
                System.out.println("找不到.dwg文件: " + srcFileName);
                return;
            }

            OdDbDatabase database = hostApp.readFile(srcFileName);
            renderToImage(database, dstFileName, width, height, maxResolutionLimit);
            System.out.println("导出全图完成: " + dstFileName);

            java.nio.file.Path finalOutPath = java.nio.file.Paths.get(dstFileName);
            java.nio.file.Path tempOutPath = finalOutPath.resolveSibling(finalOutPath.getFileName() + ".temp.png");

            // 移动原图到 temp，以便 DenseRegionExtractor 读取
            java.nio.file.Files.move(finalOutPath, tempOutPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);

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

                // 提取密集区域并合并，直接写入最终路径（覆盖原需求）
                DenseRegionExtractor.extractDenseRegions(tempOutPath, finalOutPath, options);
                success = true;
                System.out.println("密集区域提取完成，已生成合并图: " + "合成图-" + dstFileName);
            } catch (Exception e) {
                System.err.println("密集区域提取过程中发生错误: " + e.getMessage());
                e.printStackTrace();
                throw e; // 重新抛出以便外层捕获
            } finally {
                if (success) {
                    // 成功则清理临时全图
                    java.nio.file.Files.deleteIfExists(tempOutPath);
                } else {
                    // 如果失败，尝试恢复原图
                    System.err.println("正在恢复原图...");
                    try {
                        if (java.nio.file.Files.exists(tempOutPath)) {
                            java.nio.file.Files.move(tempOutPath, finalOutPath,
                                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        }
                    } catch (Exception restoreEx) {
                        System.err.println("无法恢复原图: " + restoreEx.getMessage());
                    }
                }
            }
        } catch (OdError error) {
            System.out.println(error.description());
        } catch (Error error) {
            System.out.println(error);
        } catch (Exception error) {
            System.out.println(error);
        } finally {
            memoryManager.StopTransaction(transaction);
            if (initialized) {
                TD_DbCoreIntegrated_Globals.odUninitialize();
            }
        }
    }

    private static void renderToImage(OdDbDatabase database, String outputFile, int width, int height,
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
}
