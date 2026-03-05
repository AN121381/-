package org.example;

import com.opendesign.drawings.td_dbcoreintegrated.*;
import com.opendesign.kernel.td_rootintegrated.*;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class DwgFrameFinder {

    private DwgFrameFinder() {
    }

    public static List<OdGeExtents3d> findFrames(OdDbDatabase db, OdGeExtents3d search, Options options) {
        List<Candidate> candidates = new ArrayList<>();

        OdDbBlockTableRecord modelSpace = OdDbBlockTableRecord.cast(db.getModelSpaceId().safeOpenObject());
        OdDbObjectIterator iter = modelSpace.newIterator();

        double cx = (search.minPoint().getX() + search.maxPoint().getX()) * 0.5;
        double cy = (search.minPoint().getY() + search.maxPoint().getY()) * 0.5;

        for (; !iter.done(); iter.step()) {
            OdDbEntity entity = OdDbEntity.cast(iter.objectId().safeOpenObject());
            if (entity == null) {
                continue;
            }
            if (!isLayerVisible(db, entity.layerId())) {
                continue;
            }
            if (entity.isA().name().equals("OdDbViewport")) {
                continue;
            }

            OdDbPolyline pl = OdDbPolyline.cast(entity);
            if (pl == null) {
                continue;
            }
            if (!pl.isClosed()) {
                continue;
            }
            if (!pl.isOnlyLines()) {
                continue;
            }
            if (pl.numVerts() < 4) {
                continue;
            }
            if (options.requireRect && !looksLikeRectangle(pl, options.rightAngleCosTolerance, options.minEdgeRatio)) {
                continue;
            }

            OdGeExtents3d ext = new OdGeExtents3d();
            try {
                if (entity.getGeomExtents(ext) != OdResult.eOk) {
                    continue;
                }
            } catch (Exception e) {
                continue;
            }

            if (!intersects(search, ext)) {
                continue;
            }

            double w = ext.maxPoint().getX() - ext.minPoint().getX();
            double h = ext.maxPoint().getY() - ext.minPoint().getY();
            if (w <= 0 || h <= 0) {
                continue;
            }
            double area = w * h;
            if (area < options.minArea) {
                continue;
            }

            double ex = (ext.minPoint().getX() + ext.maxPoint().getX()) * 0.5;
            double ey = (ext.minPoint().getY() + ext.maxPoint().getY()) * 0.5;
            double dx = ex - cx;
            double dy = ey - cy;
            double dist = Math.sqrt(dx * dx + dy * dy);
            double score = area / (1.0 + dist);
            candidates.add(new Candidate(ext, score, area));
        }

        candidates.sort(Comparator.<Candidate>comparingDouble(c -> c.score).reversed().thenComparingDouble(c -> c.area)
                .reversed());

        List<OdGeExtents3d> out = new ArrayList<>();
        for (Candidate c : candidates) {
            boolean overlapped = false;
            for (OdGeExtents3d e : out) {
                if (iou(e, c.ext) >= options.maxIouToKeepBoth) {
                    overlapped = true;
                    break;
                }
            }
            if (overlapped) {
                continue;
            }
            out.add(c.ext);
            if (out.size() >= options.maxFrames) {
                break;
            }
        }

        out.sort(Comparator.comparingDouble((OdGeExtents3d e) -> e.minPoint().getY())
                .thenComparingDouble(e -> e.minPoint().getX()));
        return out;
    }

    private static boolean looksLikeRectangle(OdDbPolyline pl, double cosTol, double minEdgeRatio) {
        int n = (int) pl.numVerts();
        if (n < 4 || n > 6) {
            return false;
        }
        List<OdGePoint2d> pts = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            OdGePoint2d p = new OdGePoint2d();
            pl.getPointAt(i, p);
            pts.add(p);
        }

        if (n > 4) {
            pts = simplifyCollinear(pts, cosTol);
        }
        if (pts.size() != 4) {
            return false;
        }

        double[] lens = new double[4];
        for (int i = 0; i < 4; i++) {
            OdGePoint2d a = pts.get(i);
            OdGePoint2d b = pts.get((i + 1) % 4);
            double dx = b.getX() - a.getX();
            double dy = b.getY() - a.getY();
            lens[i] = Math.hypot(dx, dy);
            if (lens[i] <= 1e-9) {
                return false;
            }
        }

        double minLen = Math.min(Math.min(lens[0], lens[1]), Math.min(lens[2], lens[3]));
        double maxLen = Math.max(Math.max(lens[0], lens[1]), Math.max(lens[2], lens[3]));
        if (minLen / maxLen < minEdgeRatio) {
            return false;
        }

        for (int i = 0; i < 4; i++) {
            OdGePoint2d p0 = pts.get((i + 3) % 4);
            OdGePoint2d p1 = pts.get(i);
            OdGePoint2d p2 = pts.get((i + 1) % 4);
            double ax = p0.getX() - p1.getX();
            double ay = p0.getY() - p1.getY();
            double bx = p2.getX() - p1.getX();
            double by = p2.getY() - p1.getY();
            double al = Math.hypot(ax, ay);
            double bl = Math.hypot(bx, by);
            if (al <= 1e-9 || bl <= 1e-9) {
                return false;
            }
            double cos = Math.abs((ax * bx + ay * by) / (al * bl));
            if (cos > cosTol) {
                return false;
            }
        }
        return true;
    }

    private static List<OdGePoint2d> simplifyCollinear(List<OdGePoint2d> pts, double cosTol) {
        if (pts.size() <= 4) {
            return pts;
        }
        List<OdGePoint2d> out = new ArrayList<>();
        int n = pts.size();
        for (int i = 0; i < n; i++) {
            OdGePoint2d prev = pts.get((i + n - 1) % n);
            OdGePoint2d cur = pts.get(i);
            OdGePoint2d next = pts.get((i + 1) % n);
            double ax = prev.getX() - cur.getX();
            double ay = prev.getY() - cur.getY();
            double bx = next.getX() - cur.getX();
            double by = next.getY() - cur.getY();
            double al = Math.hypot(ax, ay);
            double bl = Math.hypot(bx, by);
            if (al <= 1e-9 || bl <= 1e-9) {
                continue;
            }
            double cos = Math.abs((ax * bx + ay * by) / (al * bl));
            if (cos > 1.0 - cosTol) {
                continue;
            }
            out.add(cur);
        }
        return out;
    }

    private static boolean intersects(OdGeExtents3d a, OdGeExtents3d b) {
        double ax0 = a.minPoint().getX();
        double ay0 = a.minPoint().getY();
        double ax1 = a.maxPoint().getX();
        double ay1 = a.maxPoint().getY();

        double bx0 = b.minPoint().getX();
        double by0 = b.minPoint().getY();
        double bx1 = b.maxPoint().getX();
        double by1 = b.maxPoint().getY();

        return ax0 < bx1 && ax1 > bx0 && ay0 < by1 && ay1 > by0;
    }

    private static double iou(OdGeExtents3d a, OdGeExtents3d b) {
        double x0 = Math.max(a.minPoint().getX(), b.minPoint().getX());
        double y0 = Math.max(a.minPoint().getY(), b.minPoint().getY());
        double x1 = Math.min(a.maxPoint().getX(), b.maxPoint().getX());
        double y1 = Math.min(a.maxPoint().getY(), b.maxPoint().getY());
        double iw = Math.max(0.0, x1 - x0);
        double ih = Math.max(0.0, y1 - y0);
        double inter = iw * ih;
        double aa = Math.max(0.0, a.maxPoint().getX() - a.minPoint().getX())
                * Math.max(0.0, a.maxPoint().getY() - a.minPoint().getY());
        double bb = Math.max(0.0, b.maxPoint().getX() - b.minPoint().getX())
                * Math.max(0.0, b.maxPoint().getY() - b.minPoint().getY());
        double u = aa + bb - inter;
        return u <= 0.0 ? 0.0 : inter / u;
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

    private static final class Candidate {
        final OdGeExtents3d ext;
        final double score;
        final double area;

        private Candidate(OdGeExtents3d ext, double score, double area) {
            this.ext = ext;
            this.score = score;
            this.area = area;
        }
    }

    public static final class Options {
        public int maxFrames = 3;
        public boolean requireRect = true;
        public double rightAngleCosTolerance = 0.15;
        public double minEdgeRatio = 0.05;
        public double minArea = 1.0;
        public double maxIouToKeepBoth = 0.25;
    }
}

