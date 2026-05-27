package net.osmand.plus.plugins.safenav.alerts;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;

import net.osmand.data.LatLon;
import net.osmand.data.RotatedTileBox;
import net.osmand.plus.OsmandApplication;
import net.osmand.plus.plugins.safenav.alerts.AlertsManager.AlertRegion;
import net.osmand.plus.views.OsmandMapTileView;
import net.osmand.plus.views.layers.base.OsmandMapLayer;

import java.util.ArrayList;
import java.util.List;

/**
 * SafeNav — Шар тривог на карті
 *
 * Малює:
 *   1. Точний полігон межі області (червоний)
 *   2. Анімований літак що падає в центр регіону
 *   3. Банер "⚠ ПОВІТРЯНА ТРИВОГА — Київська область"
 */
public class AlertMapLayer extends OsmandMapLayer {

    private static final String TAG = "SafeNav.AlertLayer";

    // --- Паінти ---
    private final Paint fillPaint    = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint  = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bannerBgPaint  = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bannerTxtPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pulsePaint   = new Paint(Paint.ANTI_ALIAS_FLAG);

    // --- Анімація ---
    private float birdOffsetY   = -200f; // зміщення від цільової точки
    private float pulseRadius   = 0f;
    private float pulseAlpha    = 1f;
    private boolean birdLanded  = false;
    private boolean animating   = false;

    // --- Банер ---
    private boolean showBanner     = false;
    private String  bannerOblast   = "";
    private float   bannerAlpha    = 0f;
    private long    bannerShowTime = 0;
    private static final long BANNER_MS = 9_000;

    // --- Дані ---
    private final List<AlertRegion> activeAlerts = new ArrayList<>();
    private final OsmandApplication app;
    private final Handler handler = new Handler(Looper.getMainLooper());

    // Піксельні координати центру поточного регіону (для літака)
    private float targetScreenX = 0f;
    private float targetScreenY = 0f;
    private boolean targetValid = false;

    public AlertMapLayer(@NonNull Context ctx, @NonNull OsmandApplication app) {
        super(ctx);
        this.app = app;
        initPaints();
    }

    private void initPaints() {
        // Заливка полігону
        fillPaint.setColor(Color.argb(70, 220, 20, 20));
        fillPaint.setStyle(Paint.Style.FILL);

        // Контур полігону
        strokePaint.setColor(Color.argb(200, 255, 40, 40));
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(5f);
        strokePaint.setPathEffect(new android.graphics.DashPathEffect(
            new float[]{20f, 10f}, 0f));

        // Банер фон
        bannerBgPaint.setStyle(Paint.Style.FILL);

        // Банер текст
        bannerTxtPaint.setColor(Color.WHITE);
        bannerTxtPaint.setTypeface(Typeface.DEFAULT_BOLD);
        bannerTxtPaint.setTextAlign(Paint.Align.CENTER);
        bannerTxtPaint.setShadowLayer(4f, 0, 2f, Color.BLACK);

        // Пульс
        pulsePaint.setStyle(Paint.Style.STROKE);
        pulsePaint.setStrokeWidth(5f);
    }

    // ----------------------------------------------------------------
    // Головний метод малювання
    // ----------------------------------------------------------------

    @Override
    public void onDraw(@NonNull Canvas canvas, @NonNull RotatedTileBox tileBox,
                       @NonNull DrawSettings settings) {
        if (activeAlerts.isEmpty()) return;

        int w = canvas.getWidth();
        int h = canvas.getHeight();

        for (AlertRegion alert : activeAlerts) {
            if (!"air_raid".equals(alert.alertType)) continue;

            // 1. Малюємо полігон межі області
            drawOblastPolygon(canvas, tileBox, alert);
        }

        // 2. Анімований літак
        if (targetValid) {
            drawAnimatedAircraft(canvas, targetScreenX, targetScreenY + birdOffsetY);
        }

        // 3. Банер
        if (showBanner) {
            drawAlertBanner(canvas, w, h);
        }
    }

    // ----------------------------------------------------------------
    // Малювання полігону
    // ----------------------------------------------------------------

    private void drawOblastPolygon(Canvas canvas, RotatedTileBox tileBox, AlertRegion alert) {
        double[][][] polygons = UkraineOblastBoundaries.findOblast(alert.locationTitle);
        if (polygons == null) {
            // Якщо область не знайдена — малюємо пульсуючий круг на центрі екрану
            drawFallbackCircle(canvas, canvas.getWidth() / 2f, canvas.getHeight() / 2f);
            return;
        }

        // Знаходимо центроїд для літака
        double[] centroid = UkraineOblastBoundaries.getCentroid(polygons);
        if (centroid != null) {
            // Конвертуємо geo → screen
            targetScreenX = tileBox.getPixXFromLatLon(centroid[1], centroid[0]);
            targetScreenY = tileBox.getPixYFromLatLon(centroid[1], centroid[0]);
            targetValid   = true;
        }

        // Малюємо кожен полігон (область може мати острови тощо)
        for (double[][] ring : polygons) {
            Path path = buildPath(ring, tileBox);
            if (path == null) continue;

            canvas.drawPath(path, fillPaint);
            canvas.drawPath(path, strokePaint);
        }

        // Пульсуючий ефект поверх полігону
        if (birdLanded && targetValid) {
            pulsePaint.setColor(Color.argb((int)(pulseAlpha * 180), 255, 50, 50));
            canvas.drawCircle(targetScreenX, targetScreenY, pulseRadius + 40, pulsePaint);
        }
    }

    /**
     * Конвертує geo-координати полігону в Path на екрані
     * coords[i] = [longitude, latitude]
     */
    private Path buildPath(double[][] coords, RotatedTileBox tileBox) {
        if (coords == null || coords.length < 3) return null;

        Path path = new Path();
        boolean first = true;

        for (double[] coord : coords) {
            double lon = coord[0];
            double lat = coord[1];

            // Перевірка що координати в межах України
            if (lat < 44 || lat > 53 || lon < 22 || lon > 41) continue;

            float px = tileBox.getPixXFromLatLon(lat, lon);
            float py = tileBox.getPixYFromLatLon(lat, lon);

            if (first) {
                path.moveTo(px, py);
                first = false;
            } else {
                path.lineTo(px, py);
            }
        }

        if (first) return null; // жодної точки не потрапило на екран
        path.close();
        return path;
    }

    private void drawFallbackCircle(Canvas canvas, float cx, float cy) {
        Paint circlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        circlePaint.setColor(Color.argb(60, 220, 20, 20));
        circlePaint.setStyle(Paint.Style.FILL);
        canvas.drawCircle(cx, cy, 300f, circlePaint);
        circlePaint.setColor(Color.argb(150, 255, 40, 40));
        circlePaint.setStyle(Paint.Style.STROKE);
        circlePaint.setStrokeWidth(4f);
        canvas.drawCircle(cx, cy, 300f, circlePaint);
    }

    // ----------------------------------------------------------------
    // Анімований літак
    // ----------------------------------------------------------------

    private void drawAnimatedAircraft(Canvas canvas, float cx, float cy) {
        canvas.save();
        canvas.translate(cx, cy);

        float tilt = birdLanded ? 0f : -20f; // нахил при падінні
        canvas.rotate(tilt);

        float scale = birdLanded ? 0.9f : 1.1f;
        drawAircraftPath(canvas, scale);

        canvas.restore();

        // Пульс після приземлення
        if (birdLanded) {
            pulsePaint.setColor(Color.argb((int)(pulseAlpha * 160), 255, 60, 60));
            pulsePaint.setStrokeWidth(6f);
            canvas.drawCircle(cx, cy + 10, pulseRadius + 20, pulsePaint);
        }
    }

    private void drawAircraftPath(Canvas canvas, float scale) {
        float s = 55f * scale;

        Paint body = new Paint(Paint.ANTI_ALIAS_FLAG);
        body.setColor(Color.argb(230, 210, 20, 20));
        body.setStyle(Paint.Style.FILL);

        Paint outline = new Paint(Paint.ANTI_ALIAS_FLAG);
        outline.setColor(Color.WHITE);
        outline.setStyle(Paint.Style.STROKE);
        outline.setStrokeWidth(3.5f);

        // Крила
        Path wings = new Path();
        wings.moveTo(-s * 0.85f, s * 0.05f);
        wings.lineTo( s * 0.85f, s * 0.05f);
        wings.lineTo( s * 0.28f, s * 0.38f);
        wings.lineTo(-s * 0.28f, s * 0.38f);
        wings.close();

        // Хвіст
        Path tail = new Path();
        tail.moveTo(-s * 0.38f, s * 0.52f);
        tail.lineTo( s * 0.38f, s * 0.52f);
        tail.lineTo( s * 0.14f, s * 0.72f);
        tail.lineTo(-s * 0.14f, s * 0.72f);
        tail.close();

        // Корпус (ніс летить вниз — ↓)
        Path fuselage = new Path();
        fuselage.moveTo(0, -s);
        fuselage.lineTo( s * 0.18f,  s * 0.28f);
        fuselage.lineTo(0,           s * 0.10f);
        fuselage.lineTo(-s * 0.18f,  s * 0.28f);
        fuselage.close();

        canvas.drawPath(wings,    body);
        canvas.drawPath(tail,     body);
        canvas.drawPath(fuselage, body);
        canvas.drawPath(wings,    outline);
        canvas.drawPath(fuselage, outline);

        // Сигнальне червоне коло
        Paint circle = new Paint(Paint.ANTI_ALIAS_FLAG);
        circle.setColor(Color.argb(140, 255, 80, 80));
        circle.setStyle(Paint.Style.STROKE);
        circle.setStrokeWidth(4f);
        canvas.drawCircle(0, 0, s * 1.3f, circle);
    }

    // ----------------------------------------------------------------
    // Банер
    // ----------------------------------------------------------------

    private void drawAlertBanner(Canvas canvas, int w, int h) {
        long elapsed = System.currentTimeMillis() - bannerShowTime;

        if (elapsed < 400)
            bannerAlpha = elapsed / 400f;
        else if (elapsed > BANNER_MS - 600)
            bannerAlpha = Math.max(0, (BANNER_MS - elapsed) / 600f);
        else
            bannerAlpha = 1f;

        if (bannerAlpha <= 0) { showBanner = false; return; }

        float bh = 148f;

        // Градієнт фон
        LinearGradient grad = new LinearGradient(
            0, 0, 0, bh,
            Color.argb((int)(bannerAlpha * 255), 200, 10, 10),
            Color.argb((int)(bannerAlpha * 210), 130, 0,  0),
            Shader.TileMode.CLAMP
        );
        bannerBgPaint.setShader(grad);
        canvas.drawRect(0, 0, w, bh, bannerBgPaint);

        // Мигаючий лівий акцент
        if ((System.currentTimeMillis() / 500) % 2 == 0) {
            Paint accent = new Paint();
            accent.setColor(Color.argb((int)(bannerAlpha * 255), 255, 220, 0));
            accent.setStyle(Paint.Style.FILL);
            canvas.drawRect(0, 0, 10f, bh, accent);
        }

        // Іконка ⚠
        Paint warnPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        warnPaint.setColor(Color.argb((int)(bannerAlpha * 255), 255, 230, 0));
        warnPaint.setTextSize(70f);
        warnPaint.setTypeface(Typeface.DEFAULT_BOLD);
        canvas.drawText("⚠", 60f, bh * 0.75f, warnPaint);

        // Заголовок
        bannerTxtPaint.setTextSize(42f);
        bannerTxtPaint.setAlpha((int)(bannerAlpha * 255));
        canvas.drawText("ПОВІТРЯНА ТРИВОГА", w / 2f + 25f, bh * 0.42f, bannerTxtPaint);

        // Назва області
        bannerTxtPaint.setTextSize(36f);
        bannerTxtPaint.setAlpha((int)(bannerAlpha * 210));
        canvas.drawText(bannerOblast, w / 2f + 25f, bh * 0.80f, bannerTxtPaint);

        // Нижня лінія-пульс
        Paint line = new Paint();
        float lineAlpha = 0.5f + 0.5f * (float) Math.sin(System.currentTimeMillis() / 300.0);
        line.setColor(Color.argb((int)(bannerAlpha * lineAlpha * 255), 255, 80, 80));
        line.setStrokeWidth(3f);
        canvas.drawLine(0, bh, w, bh, line);
    }

    // ----------------------------------------------------------------
    // Анімація — головний loop
    // ----------------------------------------------------------------

    public void triggerAlert(AlertRegion alert) {
        birdOffsetY   = -600f;  // старт — далеко зверху
        birdLanded    = false;
        pulseRadius   = 0f;
        pulseAlpha    = 1f;
        showBanner    = true;
        bannerOblast  = alert.locationTitle;
        bannerShowTime = System.currentTimeMillis();
        animating     = true;
        targetValid   = false;

        handler.post(animLoop);
    }

    private final Runnable animLoop = new Runnable() {
        @Override public void run() {
            if (!animating) return;

            // Плавний спуск (easing out)
            if (!birdLanded) {
                birdOffsetY += (0f - birdOffsetY) * 0.10f;
                if (Math.abs(birdOffsetY) < 4f) {
                    birdOffsetY = 0f;
                    birdLanded  = true;
                }
            } else {
                // Пульс після приземлення
                pulseRadius += 5f;
                pulseAlpha = Math.max(0f, 1f - pulseRadius / 220f);
                if (pulseRadius > 220f) {
                    pulseRadius = 0f;
                    pulseAlpha  = 1f;
                }
            }

            refreshMap();
            handler.postDelayed(this, 16); // 60fps
        }
    };

    private void refreshMap() {
        OsmandMapTileView view = app.getOsmandMap() != null
            ? app.getOsmandMap().getMapView() : null;
        if (view != null) view.refreshMap();
    }

    // ----------------------------------------------------------------
    // Публічні методи
    // ----------------------------------------------------------------

    public void setAlerts(List<AlertRegion> alerts) {
        activeAlerts.clear();
        activeAlerts.addAll(alerts);
        if (alerts.isEmpty()) stopAnimation();
        else refreshMap();
    }

    public void onNewAlert(AlertRegion alert) {
        if (!activeAlerts.contains(alert)) activeAlerts.add(alert);
        triggerAlert(alert);
    }

    public void stopAnimation() {
        animating   = false;
        showBanner  = false;
        targetValid = false;
        activeAlerts.clear();
        handler.removeCallbacks(animLoop);
        refreshMap();
    }

    // ----------------------------------------------------------------
    // OsmandMapLayer interface
    // ----------------------------------------------------------------

    @Override
    public void initLayer(@NonNull OsmandMapTileView view) { super.initLayer(view); }

    @Override
    public void destroyLayer() { stopAnimation(); }

    @Override
    public boolean drawInScreenPixels() { return false; } // coords у тайл-боксі

    @Override
    public boolean onLongPressEvent(@NonNull PointF point, @NonNull RotatedTileBox tileBox) {
        return false;
    }

    @Override
    public boolean onSingleTap(@NonNull PointF point, @NonNull RotatedTileBox tileBox) {
        if (showBanner && point.y < 148f) {
            // TODO: AlertDetailsFragment
            return true;
        }
        return false;
    }
}
