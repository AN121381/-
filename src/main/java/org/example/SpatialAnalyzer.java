package org.example;

import com.opendesign.drawings.td_dbcoreintegrated.*;
import com.opendesign.kernel.td_rootintegrated.*;

import java.util.ArrayList;
import java.util.List;

public class SpatialAnalyzer {

    public static class Result {
        public OdGeExtents3d totalExtents;
        public List<OdGeExtents3d> regions;
    }

    public static Result analyze(OdDbDatabase db, int baseGridResolution) {
        OdDbBlockTableRecord modelSpace = OdDbBlockTableRecord.cast(db.getModelSpaceId().safeOpenObject());

        List<OdGeExtents3d> entityExtents = new ArrayList<>();
        OdDbObjectIterator iter = modelSpace.newIterator();

        for (; !iter.done(); iter.step()) {
            OdDbEntity entity = OdDbEntity.cast(iter.objectId().safeOpenObject());
            if (entity != null) {
                if (!isLayerVisible(db, entity.layerId())) {
                    continue;
                }

                if (entity.isA().name().equals("OdDbViewport")) {
                    continue;
                }

                OdGeExtents3d ext = new OdGeExtents3d();
                try {
                    if (entity.getGeomExtents(ext) == OdResult.eOk) {
                        OdGePoint3d min = ext.minPoint();
                        OdGePoint3d max = ext.maxPoint();

                        // Basic validation
                        if (min.getX() < max.getX() && min.getY() < max.getY()) {
                            // Skip infinite or extremely large coordinates
                            if (isValidCoordinate(min) && isValidCoordinate(max)) {
                                entityExtents.add(ext);
                            }
                        }
                    }
                } catch (Exception e) {
                    // Ignore entities that fail to give extents
                }
            }
        }

        Result result = new Result();
        result.regions = new ArrayList<>();

        if (entityExtents.isEmpty()) {
            return result;
        }

        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;

        for (OdGeExtents3d ext : entityExtents) {
            minX = Math.min(minX, ext.minPoint().getX());
            minY = Math.min(minY, ext.minPoint().getY());
            maxX = Math.max(maxX, ext.maxPoint().getX());
            maxY = Math.max(maxY, ext.maxPoint().getY());
        }

        result.totalExtents = new OdGeExtents3d(new OdGePoint3d(minX, minY, 0), new OdGePoint3d(maxX, maxY, 0));

        double width = maxX - minX;
        double height = maxY - minY;

        if (width <= 1e-6 || height <= 1e-6)
            return result;

        double aspect = width / height;
        int gridW, gridH;

        if (aspect >= 1.0) {
            gridH = baseGridResolution;
            gridW = (int) Math.ceil(baseGridResolution * aspect);
        } else {
            gridW = baseGridResolution;
            gridH = (int) Math.ceil(baseGridResolution / aspect);
        }

        // Limit max grid size to avoid OOM
        int maxGridDim = 2000;
        if (gridW > maxGridDim)
            gridW = maxGridDim;
        if (gridH > maxGridDim)
            gridH = maxGridDim;

        boolean[][] grid = new boolean[gridW][gridH];
        double cellW = width / gridW;
        double cellH = height / gridH;

        // 4. Populate Grid
        for (OdGeExtents3d ext : entityExtents) {
            OdGePoint3d min = ext.minPoint();
            OdGePoint3d max = ext.maxPoint();

            double entW = max.getX() - min.getX();
            double entH = max.getY() - min.getY();

            if (entW > width * 0.5 || entH > height * 0.5) {
                continue;
            }

            int x0 = (int) ((min.getX() - minX) / cellW);
            int y0 = (int) ((min.getY() - minY) / cellH);
            int x1 = (int) ((max.getX() - minX) / cellW);
            int y1 = (int) ((max.getY() - minY) / cellH);

            x0 = Math.max(0, Math.min(gridW - 1, x0));
            y0 = Math.max(0, Math.min(gridH - 1, y0));
            x1 = Math.max(0, Math.min(gridW - 1, x1));
            y1 = Math.max(0, Math.min(gridH - 1, y1));

            for (int x = x0; x <= x1; x++) {
                for (int y = y0; y <= y1; y++) {
                    grid[x][y] = true;
                }
            }
        }

        boolean[][] dilatedGrid = new boolean[gridW][gridH];
        int dilationSize = 1; // 1 cell radius

        for (int x = 0; x < gridW; x++) {
            for (int y = 0; y < gridH; y++) {
                if (grid[x][y]) {
                    // Dilate
                    for (int dx = -dilationSize; dx <= dilationSize; dx++) {
                        for (int dy = -dilationSize; dy <= dilationSize; dy++) {
                            int nx = x + dx;
                            int ny = y + dy;
                            if (nx >= 0 && nx < gridW && ny >= 0 && ny < gridH) {
                                dilatedGrid[nx][ny] = true;
                            }
                        }
                    }
                }
            }
        }

        boolean[][] processGrid = dilatedGrid;

        boolean[][] visited = new boolean[gridW][gridH];

        for (int x = 0; x < gridW; x++) {
            for (int y = 0; y < gridH; y++) {
                if (processGrid[x][y] && !visited[x][y]) {

                    int[] bounds = new int[] { x, x, y, y };
                    floodFill(processGrid, visited, x, y, gridW, gridH, bounds);

                    int regionW = bounds[1] - bounds[0] + 1;
                    int regionH = bounds[3] - bounds[2] + 1;
                    if (regionW < 2 && regionH < 2) {
                        continue;
                    }

                    double rMinX = minX + Math.max(0, bounds[0]) * cellW;
                    double rMaxX = minX + Math.min(gridW, bounds[1] + 1) * cellW;
                    double rMinY = minY + Math.max(0, bounds[2]) * cellH;
                    double rMaxY = minY + Math.min(gridH, bounds[3] + 1) * cellH;

                    OdGeExtents3d regionExt = new OdGeExtents3d(
                            new OdGePoint3d(rMinX, rMinY, 0),
                            new OdGePoint3d(rMaxX, rMaxY, 0));
                    result.regions.add(regionExt);
                }
            }
        }

        return result;
    }

    private static boolean isLayerVisible(OdDbDatabase db, OdDbObjectId layerId) {
        try {
            OdDbLayerTableRecord layer = OdDbLayerTableRecord.cast(layerId.safeOpenObject());
            if (layer != null) {
                return !layer.isOff() && !layer.isFrozen();
            }
        } catch (Exception e) {

        }
        return true;
    }

    private static boolean isValidCoordinate(OdGePoint3d p) {
        double limit = 1e20;
        return Math.abs(p.getX()) < limit && Math.abs(p.getY()) < limit;
    }

    private static void floodFill(boolean[][] grid, boolean[][] visited, int x, int y, int w, int h, int[] bounds) {

        ArrayList<int[]> stack = new ArrayList<>();
        stack.add(new int[] { x, y });

        while (!stack.isEmpty()) {
            int[] p = stack.remove(stack.size() - 1);
            int cx = p[0];
            int cy = p[1];

            if (cx < 0 || cx >= w || cy < 0 || cy >= h)
                continue;
            if (visited[cx][cy] || !grid[cx][cy])
                continue;

            visited[cx][cy] = true;

            if (cx < bounds[0])
                bounds[0] = cx;
            if (cx > bounds[1])
                bounds[1] = cx;
            if (cy < bounds[2])
                bounds[2] = cy;
            if (cy > bounds[3])
                bounds[3] = cy;

            stack.add(new int[] { cx + 1, cy });
            stack.add(new int[] { cx - 1, cy });
            stack.add(new int[] { cx, cy + 1 });
            stack.add(new int[] { cx, cy - 1 });
        }
    }
}
