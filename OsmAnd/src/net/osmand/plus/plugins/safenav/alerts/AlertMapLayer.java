package net.osmand.plus.plugins.safenav.alerts;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.osmand.data.LatLon;
import net.osmand.data.QuadRect;
import net.osmand.data.RotatedTileBox;
import net.osmand.plus.OsmandApplication;
import net.osmand.plus.activities.MapActivity;
import net.osmand.plus.plugins.safenav.alerts.AlertsManager.AlertRegion;
import net.osmand.plus.views.OsmandMapTileView;
import net.osmand.plus.views.layers.base.OsmandMapLayer;

import java.util.ArrayList;
import java.util.List;

/**
 * SafeNav — Шар тривог на карті
 *
 * Малює:
 *  1. Червоний напівпрозорий оверлей поверх регіону тривоги
 *  2. Анімована пташка/літак що падає зверху з повідомленням
 *  3. Банер "⚠️ ТРИВОГА — Київська область"
 */
public class AlertMapLayer extends OsmandMapLayer {

    private static final String TAG = "SafeNav.AlertLayer";

    // --- Паінти ---
    private final Paint overlayPaint   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bannerBgPaint  = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bannerTxtPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint iconPaint      = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint pulsePaint     = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadowPaint    = new Paint(Paint.ANTI_ALIAS_FLAG);

    // --- Анімація ---
    private float birdY         = -120f;   // поточна Y пташки (починає зверху)
    private float birdTargetY   = 0f;      // куди летить
    private float pulseRadius   = 0f;      // пульсуюче коло
    private float pulseAlpha    = 1f;
    private boolean animating   = false;
    private boolean birdLanded  = false;

    // --- Банер ---
    private boolean showBanner      = false;
    private String  bannerText      = "";
    private float   bannerAlpha     = 0f;   // 0..1 fade-in
    private long    bannerShowTime  = 0;
    private static final long BANNER_DURATION_MS = 8_000;

    // --- Дані ---
    private List<AlertRegion> activeAlerts = new ArrayList<>();
    private final OsmandApplication app;
    private final Handler handler = new Handler(Looper.getMainLooper());

    // Регіони-полігони для областей України (центри областей для іконки)
    // Повний список — центроїди всіх 25 областей
    private static final float[][] OBLAST_CENTERS = {
        // {lat, lon, name_hash} — спрощено, в повній версії точні полігони з OSM
        {50.45f, 30.52f},  // Київська
        {49.42f, 32.07f},  // Черкаська
        {48.46f, 35.04f},  // Дніпропетровська
        {49.59f, 36.23f},  // Харківська
        {50.36f, 26.25f},  // Рівненська
        {49.83f, 24.01f},  // Львівська
        {48.29f, 25.56f},  // Чернівецька
        {48.36f, 31.18f},  // Кіровоградська
        {47.55f, 35.10f},  // Запорізька
        {47.54f, 32.36f},  // Херсонська
        {46.97f, 31.99f},  // Миколаївська
        {46.49f, 30.74f},  // Одеська
        {48.92f, 24.71f},  // Івано-Франківська
        {49.56f, 25.60f},  // Тернопільська
        {50.73f, 28.68f},  // Житомирська
        {51.33f, 25.32f},  // Волинська
        {51.73f, 33.91f},  // Сумська
        {51.50f, 31.28f},  // Чернігівська
        {50.91f, 34.80f},  // Полтавська
        {48.63f, 22.29f},  // Закарпатська
        {48.66f, 26.57f},  // Хмельницька
        {50.26f, 28.66f},  // Вінницька
        {48.08f, 37.80f},  // Донецька
        {48.57f, 39.35f},  // Луганська
        {50.60f, 26.25f},  // Хмельницька 2
    };

    public AlertMapLayer(@NonNull Context ctx, @NonNull OsmandApplication app) {
        super(ctx);
        this.app = app;
        initPaints();
    }

    private void initPaints() {
        // Червоний оверлей регіону
        overlayPaint.setColor(Color.argb(60, 220, 30, 30));
        overlayPaint.setStyle(Paint.Style.FILL);

        // Фон банеру — темно-червоний градієнт
        bannerBgPaint.setColor(Color.argb(230, 180, 0, 0));
        bannerBgPaint.setStyle(Paint.Style.FILL);

        // Текст банеру
        bannerTxtPaint.setColor(Color.WHITE);
        bannerTxtPaint.setTypeface(Typeface.DEFAULT_BOLD);
        bannerTxtPaint.setTextSize(44f);
        bannerTxtPaint.setTextAlign(Paint.Align.CENTER);
        bannerTxtPaint.setShadowLayer(4f, 0f, 2f, Color.BLACK);

        // Пульс під іконкою
        pulsePaint.setColor(Color.argb(120, 255, 50, 50));
        pulsePaint.setStyle(Paint.Style.STROKE);
        pulsePaint.setStrokeWidth(6f);

        // Тінь іконки
        shadowPaint.setColor(Color.argb(80, 0, 0, 0));
        shadowPaint.setStyle(Paint.Style.FILL);
        shadowPaint.setMaskFilter(new android.graphics.BlurMaskFilter(
            12f, android.graphics.BlurMaskFilter.Blur.NORMAL));
    }

    @Override
    public void onDraw(@NonNull Canvas canvas, @NonNull RotatedTileBox tileBox, @NonNull DrawSettings settings) {
        if (activeAlerts.isEmpty()) return;

        int screenW = canvas.getWidth();
        int screenH = canvas.getHeight();

        // 1. Малюємо червоний оверлей по всьому екрану (спрощено)
        //    В повній версії — полігони областей з OSM
        drawAlertOverlay(canvas, screenW, screenH);

        // 2. Анімована іконка літак/пташка
        drawAnimatedBird(canvas, screenW, screenH);

        // 3. Банер з назвою регіону
        if (showBanner) {
            drawAlertBanner(canvas, screenW, screenH);
        }
    }

    /**
     * Червоний напівпрозорий оверлей + пульсуючий контур
     */
    private void drawAlertOverlay(Canvas canvas, int w, int h) {
        // Повний екран — тривога активна
        canvas.drawRect(0, 0, w, h, overlayPaint);

        // Пульсуючий червоний контур по краях
        pulsePaint.setAlpha((int)(pulseAlpha * 180));
        float margin = pulseRadius;
        canvas.drawRect(margin, margin, w - margin, h - margin, pulsePaint);
    }

    /**
     * Анімована іконка: літак падає зверху, приземляється в центрі
     */
    private void drawAnimatedBird(Canvas canvas, int w, int h) {
        float cx = w / 2f;
        float cy = h / 2f;

        // Тінь під іконкою (з'являється коли приземляється)
        if (birdLanded) {
            float shadowScale = 1f - (birdY - birdTargetY) / h;
            canvas.drawOval(
                new RectF(cx - 40 * shadowScale, cy + 50,
                          cx + 40 * shadowScale, cy + 62),
                shadowPaint
            );
        }

        // Малюємо іконку літак/пташка
        canvas.save();
        canvas.translate(cx, birdY);

        // Обертання вниз при падінні
        float rotation = birdLanded ? 0f : -15f;
        canvas.rotate(rotation);

        // Малюємо простий SVG-like літак з Path
        drawAircraftIcon(canvas, birdLanded ? 0.8f : 1.0f);

        canvas.restore();

        // Пульсуюче коло після приземлення
        if (birdLanded) {
            pulsePaint.setColor(Color.argb((int)(pulseAlpha * 200), 255, 30, 30));
            pulsePaint.setStrokeWidth(4f);
            canvas.drawCircle(cx, birdTargetY, pulseRadius + 30, pulsePaint);
        }
    }

    /**
     * Малюємо іконку літака (векторна — без bitmap залежностей)
     */
    private void drawAircraftIcon(Canvas canvas, float scale) {
        Paint bodyPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        bodyPaint.setColor(Color.argb(230, 220, 30, 30));
        bodyPaint.setStyle(Paint.Style.FILL);

        Paint outlinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        outlinePaint.setColor(Color.WHITE);
        outlinePaint.setStyle(Paint.Style.STROKE);
        outlinePaint.setStrokeWidth(3f);

        float s = 60f * scale;

        // Корпус літака
        Path body = new Path();
        body.moveTo(0, -s);          // ніс
        body.lineTo(s * 0.2f, s * 0.3f);
        body.lineTo(0, s * 0.1f);
        body.lineTo(-s * 0.2f, s * 0.3f);
        body.close();

        // Крила
        Path wings = new Path();
        wings.moveTo(-s * 0.8f, s * 0.1f);
        wings.lineTo(s * 0.8f, s * 0.1f);
        wings.lineTo(s * 0.3f, s * 0.35f);
        wings.lineTo(-s * 0.3f, s * 0.35f);
        wings.close();

        // Хвіст
        Path tail = new Path();
        tail.moveTo(-s * 0.4f, s * 0.55f);
        tail.lineTo(s * 0.4f, s * 0.55f);
        tail.lineTo(s * 0.15f, s * 0.75f);
        tail.lineTo(-s * 0.15f, s * 0.75f);
        tail.close();

        canvas.drawPath(wings, bodyPaint);
        canvas.drawPath(tail, bodyPaint);
        canvas.drawPath(body, bodyPaint);
        canvas.drawPath(wings, outlinePaint);
        canvas.drawPath(body, outlinePaint);

        // Сигнальне коло навколо літака
        Paint circlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        circlePaint.setColor(Color.argb(160, 255, 80, 80));
        circlePaint.setStyle(Paint.Style.STROKE);
        circlePaint.setStrokeWidth(5f);
        canvas.drawCircle(0, s * 0f, s * 1.2f, circlePaint);
    }

    /**
     * Банер зверху екрану з назвою тривоги
     */
    private void drawAlertBanner(Canvas canvas, int w, int h) {
        // Fade-in анімація
        long elapsed = System.currentTimeMillis() - bannerShowTime;
        if (elapsed < 500) {
            bannerAlpha = elapsed / 500f;
        } else if (elapsed > BANNER_DURATION_MS - 500) {
            bannerAlpha = Math.max(0, (BANNER_DURATION_MS - elapsed) / 500f);
        } else {
            bannerAlpha = 1f;
        }

        if (bannerAlpha <= 0) {
            showBanner = false;
            return;
        }

        float bannerH = 140f;

        // Градієнт фон
        Paint gradPaint = new Paint();
        gradPaint.setAlpha((int)(bannerAlpha * 230));
        LinearGradient grad = new LinearGradient(
            0, 0, 0, bannerH,
            Color.argb(255, 200, 0, 0),
            Color.argb(200, 140, 0, 0),
            Shader.TileMode.CLAMP
        );
        gradPaint.setShader(grad);
        gradPaint.setStyle(Paint.Style.FILL);

        RectF bannerRect = new RectF(0, 0, w, bannerH);
        canvas.drawRoundRect(bannerRect, 0, 24f, gradPaint);

        // Іконка ⚠ зліва
        Paint warnPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        warnPaint.setColor(Color.argb((int)(bannerAlpha * 255), 255, 230, 0));
        warnPaint.setTextSize(64f);
        warnPaint.setTypeface(Typeface.DEFAULT_BOLD);
        canvas.drawText("⚠", 60f, bannerH * 0.72f, warnPaint);

        // Текст тривоги
        bannerTxtPaint.setAlpha((int)(bannerAlpha * 255));
        bannerTxtPaint.setTextSize(40f);
        canvas.drawText("ПОВІТРЯНА ТРИВОГА", w / 2f + 20f, bannerH * 0.42f, bannerTxtPaint);

        bannerTxtPaint.setTextSize(34f);
        bannerTxtPaint.setAlpha((int)(bannerAlpha * 200));
        canvas.drawText(bannerText, w / 2f + 20f, bannerH * 0.78f, bannerTxtPaint);

        // Нижня смуга пульс
        Paint linePaint = new Paint();
        linePaint.setColor(Color.argb((int)(bannerAlpha * pulseAlpha * 255), 255, 100, 100));
        linePaint.setStrokeWidth(4f);
        canvas.drawLine(0, bannerH, w, bannerH, linePaint);
    }

    // ----------------------------------------------------------------
    // Анімація
    // ----------------------------------------------------------------

    /**
     * Запустити анімацію при новій тривозі
     */
    public void triggerAlert(AlertRegion alert) {
        birdY      = -120f;
        birdLanded = false;
        pulseRadius = 0f;
        pulseAlpha  = 1f;
        showBanner  = true;
        bannerText  = alert.locationTitle;
        bannerShowTime = System.currentTimeMillis();
        animating   = true;

        // Ціль — центр екрану
        birdTargetY = 300f;

        startAnimation();
    }

    private void startAnimation() {
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (!animating) return;

                // Плавне падіння пташки (easing)
                float diff = birdTargetY - birdY;
                if (Math.abs(diff) > 3f) {
                    birdY += diff * 0.12f;
                } else {
                    birdY = birdTargetY;
                    if (!birdLanded) {
                        birdLanded = true;
                        pulseRadius = 0f;
                    }
                }

                // Пульс після приземлення
                if (birdLanded) {
                    pulseRadius += 4f;
                    pulseAlpha = Math.max(0, 1f - pulseRadius / 200f);
                    if (pulseRadius > 200f) {
                        pulseRadius = 0f;
                        pulseAlpha  = 1f;
                    }
                }

                // Оновлюємо карту
                refreshMap();

                if (animating) {
                    handler.postDelayed(this, 16); // ~60fps
                }
            }
        });
    }

    private void refreshMap() {
        OsmandMapTileView view = app.getOsmandMap() != null
            ? app.getOsmandMap().getMapView() : null;
        if (view != null) {
            view.refreshMap();
        }
    }

    public void stopAnimation() {
        animating  = false;
        showBanner = false;
        activeAlerts.clear();
        refreshMap();
    }

    // ----------------------------------------------------------------
    // Публічні методи
    // ----------------------------------------------------------------

    public void setAlerts(List<AlertRegion> alerts) {
        this.activeAlerts = new ArrayList<>(alerts);
        if (alerts.isEmpty()) {
            stopAnimation();
        }
    }

    public void onNewAlert(AlertRegion alert) {
        if (!activeAlerts.contains(alert)) {
            activeAlerts.add(alert);
        }
        triggerAlert(alert);
    }

    // ----------------------------------------------------------------
    // OsmandMapLayer interface
    // ----------------------------------------------------------------

    @Override
    public void initLayer(@NonNull OsmandMapTileView view) {
        super.initLayer(view);
    }

    @Override
    public void destroyLayer() {
        stopAnimation();
    }

    @Override
    public boolean drawInScreenPixels() {
        return true; // малюємо в координатах екрану, не карти
    }

    @Override
    public boolean onLongPressEvent(@NonNull PointF point, @NonNull RotatedTileBox tileBox) {
        return false;
    }

    @Override
    public boolean onSingleTap(@NonNull PointF point, @NonNull RotatedTileBox tileBox) {
        // Тап на банер — відкриває деталі тривоги
        if (showBanner && point.y < 140f) {
            // TODO: відкрити AlertDetailsFragment
            return true;
        }
        return false;
    }
}
