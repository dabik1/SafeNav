package net.osmand.plus.plugins.safenav.shelter;

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
import net.osmand.plus.plugins.safenav.shelter.ShelterFinder.Shelter;
import net.osmand.plus.views.OsmandMapTileView;
import net.osmand.plus.views.layers.base.OsmandMapLayer;
import net.osmand.util.MapUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * SafeNav — Шар укриттів на карті
 *
 * Відображає після приземлення літака:
 *  1. Маркери укриттів (іконки) на карті
 *  2. Картки укриттів знизу екрану (slide-up)
 *  3. Лінія від позиції до найближчого укриття
 *  4. TTS: "Найближче укриття за 250 метрів — підвал ТЦ"
 */
public class ShelterMapLayer extends OsmandMapLayer {

    private static final String TAG = "SafeNav.ShelterLayer";

    // --- Паінти ---
    private final Paint markerBgPaint   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint markerTxtPaint  = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint markerRingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint routePaint      = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cardBgPaint     = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cardTxtPaint    = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cardSubPaint    = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint distancePaint   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint closestArrow    = new Paint(Paint.ANTI_ALIAS_FLAG);

    // --- Дані ---
    private List<Shelter> shelters    = new ArrayList<>();
    private LatLon userPosition       = null;
    private Shelter selectedShelter   = null;

    // --- Анімація карток ---
    private float cardSlideY       = 0f;    // 0 = прихована, 1 = повністю видима
    private float cardTargetY      = 0f;
    private boolean cardsVisible   = false;
    private int  currentCardIndex  = 0;     // яка картка зараз активна

    // --- Пульс найближчого ---
    private float pulseR     = 0f;
    private float pulseAlpha = 1f;

    private final OsmandApplication app;
    private final Handler handler = new Handler(Looper.getMainLooper());

    public ShelterMapLayer(@NonNull Context ctx, @NonNull OsmandApplication app) {
        super(ctx);
        this.app = app;
        initPaints();
    }

    private void initPaints() {
        // Маркер — зелений кружок
        markerBgPaint.setStyle(Paint.Style.FILL);
        markerBgPaint.setColor(Color.argb(220, 20, 160, 60));

        markerRingPaint.setStyle(Paint.Style.STROKE);
        markerRingPaint.setStrokeWidth(3.5f);
        markerRingPaint.setColor(Color.WHITE);

        markerTxtPaint.setColor(Color.WHITE);
        markerTxtPaint.setTextSize(38f);
        markerTxtPaint.setTextAlign(Paint.Align.CENTER);
        markerTxtPaint.setTypeface(Typeface.DEFAULT_BOLD);

        // Лінія до укриття
        routePaint.setColor(Color.argb(200, 30, 180, 80));
        routePaint.setStyle(Paint.Style.STROKE);
        routePaint.setStrokeWidth(7f);
        routePaint.setPathEffect(new android.graphics.DashPathEffect(
            new float[]{24f, 14f}, 0f));
        routePaint.setStrokeCap(Paint.Cap.ROUND);

        // Картка укриття
        cardBgPaint.setStyle(Paint.Style.FILL);

        cardTxtPaint.setColor(Color.WHITE);
        cardTxtPaint.setTypeface(Typeface.DEFAULT_BOLD);
        cardTxtPaint.setTextSize(40f);

        cardSubPaint.setColor(Color.argb(200, 220, 220, 220));
        cardSubPaint.setTextSize(32f);

        distancePaint.setColor(Color.argb(255, 100, 255, 140));
        distancePaint.setTextSize(48f);
        distancePaint.setTypeface(Typeface.DEFAULT_BOLD);
        distancePaint.setTextAlign(Paint.Align.RIGHT);

        closestArrow.setColor(Color.argb(200, 50, 220, 100));
        closestArrow.setStyle(Paint.Style.STROKE);
        closestArrow.setStrokeWidth(5f);
        closestArrow.setStrokeCap(Paint.Cap.ROUND);
    }

    // ----------------------------------------------------------------
    // Головне малювання
    // ----------------------------------------------------------------

    @Override
    public void onDraw(@NonNull Canvas canvas, @NonNull RotatedTileBox tileBox,
                       @NonNull DrawSettings settings) {
        if (shelters.isEmpty()) return;

        int w = canvas.getWidth();
        int h = canvas.getHeight();

        // 1. Маркери всіх укриттів на карті
        drawShelterMarkers(canvas, tileBox);

        // 2. Лінія від позиції до вибраного укриття
        if (userPosition != null && !shelters.isEmpty()) {
            drawRouteLine(canvas, tileBox, shelters.get(currentCardIndex));
        }

        // 3. Картки укриттів знизу (slide-up)
        if (cardsVisible) {
            drawShelterCards(canvas, w, h);
        }

        // Пульс найближчого маркера
        animatePulse(canvas, tileBox);
    }

    // ----------------------------------------------------------------
    // Маркери на карті
    // ----------------------------------------------------------------

    private void drawShelterMarkers(Canvas canvas, RotatedTileBox tileBox) {
        for (int i = 0; i < shelters.size(); i++) {
            Shelter s = shelters.get(i);
            if (s.location == null) continue;

            float xyX = tileBox.getPixXFromLatLon(s.location.getLatitude(), s.location.getLongitude();
            float xyY = tileBox.getPixYFromLatLon(s.location.getLatitude(), s.location.getLongitude();
            float px = xyX;
            float py = xyY;

            boolean isNearest = (i == 0);
            boolean isSelected = (i == currentCardIndex);
            float radius = isNearest ? 36f : (isSelected ? 30f : 24f);

            // Тінь
            Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
            shadow.setColor(Color.argb(80, 0, 0, 0));
            shadow.setStyle(Paint.Style.FILL);
            shadow.setMaskFilter(new android.graphics.BlurMaskFilter(
                10f, android.graphics.BlurMaskFilter.Blur.NORMAL));
            canvas.drawCircle(px + 3, py + 5, radius, shadow);

            // Фон маркера
            if (isNearest) {
                markerBgPaint.setColor(Color.argb(235, 20, 200, 70));
            } else if (isSelected) {
                markerBgPaint.setColor(Color.argb(220, 20, 160, 80));
            } else {
                markerBgPaint.setColor(Color.argb(190, 30, 130, 60));
            }
            canvas.drawCircle(px, py, radius, markerBgPaint);
            canvas.drawCircle(px, py, radius, markerRingPaint);

            // Іконка всередині
            markerTxtPaint.setTextSize(isNearest ? 34f : 26f);
            canvas.drawText(s.getIcon(), px, py + markerTxtPaint.getTextSize() * 0.36f, markerTxtPaint);

            // Номер маркера
            if (!isNearest) {
                Paint numPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                numPaint.setColor(Color.WHITE);
                numPaint.setTextSize(20f);
                numPaint.setTypeface(Typeface.DEFAULT_BOLD);
                numPaint.setTextAlign(Paint.Align.CENTER);
                canvas.drawText(String.valueOf(i + 1), px + radius - 2, py - radius + 16, numPaint);
            }

            // Відстань під маркером (тільки для найближчого)
            if (isNearest) {
                Paint distLabel = new Paint(Paint.ANTI_ALIAS_FLAG);
                distLabel.setColor(Color.WHITE);
                distLabel.setTextSize(26f);
                distLabel.setTextAlign(Paint.Align.CENTER);
                distLabel.setTypeface(Typeface.DEFAULT_BOLD);
                distLabel.setShadowLayer(4f, 0, 1f, Color.argb(180, 0, 80, 0));
                canvas.drawText(s.getDistanceText(), px, py + radius + 30f, distLabel);
            }
        }
    }

    // ----------------------------------------------------------------
    // Пульс найближчого
    // ----------------------------------------------------------------

    private void animatePulse(Canvas canvas, RotatedTileBox tileBox) {
        if (shelters.isEmpty()) return;
        Shelter nearest = shelters.get(0);
        if (nearest.location == null) return;

        float xyX = tileBox.getPixXFromLatLon(nearest.location.getLatitude(), nearest.location.getLongitude();
        float xyY = tileBox.getPixYFromLatLon(nearest.location.getLatitude(), nearest.location.getLongitude();

        Paint pulse = new Paint(Paint.ANTI_ALIAS_FLAG);
        pulse.setStyle(Paint.Style.STROKE);
        pulse.setStrokeWidth(4f);
        pulse.setColor(Color.argb((int)(pulseAlpha * 180), 50, 230, 100));
        canvas.drawCircle(xyX, xyY, pulseR + 36, pulse);
    }

    // ----------------------------------------------------------------
    // Лінія маршруту до укриття
    // ----------------------------------------------------------------

    private void drawRouteLine(Canvas canvas, RotatedTileBox tileBox, Shelter target) {
        if (target == null || target.location == null) return;

        float userXYX = tileBox.getPixXFromLatLon(userPosition.getLatitude(), userPosition.getLongitude();
        float userXYY = tileBox.getPixYFromLatLon(userPosition.getLatitude(), userPosition.getLongitude();
        float shelterXYX = tileBox.getPixXFromLatLon(target.location.getLatitude(), target.location.getLongitude();
        float shelterXYY = tileBox.getPixYFromLatLon(target.location.getLatitude(), target.location.getLongitude();

        Path line = new Path();
        line.moveTo(userPx, userPy);
        line.lineTo(shelterPx, shelterPy);
        canvas.drawPath(line, routePaint);

        // Стрілка напрямку
        drawArrow(canvas, userPx, userPy, shelterPx, shelterPy);
    }

    private void drawArrow(Canvas canvas, float x1, float y1, float x2, float y2) {
        float dx = x2 - x1;
        float dy = y2 - y1;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 80f) return;

        // Середина лінії
        float mx = x1 + dx * 0.5f;
        float my = y1 + dy * 0.5f;

        float nx = -dy / len;
        float ny =  dx / len;
        float arrowSize = 22f;

        Path arrow = new Path();
        arrow.moveTo(mx + dx / len * arrowSize, my + dy / len * arrowSize);
        arrow.lineTo(mx + nx * arrowSize * 0.6f - dx / len * arrowSize * 0.6f,
                     my + ny * arrowSize * 0.6f - dy / len * arrowSize * 0.6f);
        arrow.lineTo(mx - nx * arrowSize * 0.6f - dx / len * arrowSize * 0.6f,
                     my - ny * arrowSize * 0.6f - dy / len * arrowSize * 0.6f);
        arrow.close();

        Paint arrowFill = new Paint(Paint.ANTI_ALIAS_FLAG);
        arrowFill.setColor(Color.argb(220, 50, 220, 100));
        arrowFill.setStyle(Paint.Style.FILL);
        canvas.drawPath(arrow, arrowFill);
    }

    // ----------------------------------------------------------------
    // Картки укриттів (slide-up панель)
    // ----------------------------------------------------------------

    private void drawShelterCards(Canvas canvas, int w, int h) {
        // Анімація slide-up
        cardSlideY += (cardTargetY - cardSlideY) * 0.15f;

        float cardH = 200f;
        float cardW = w - 40f;
        float cardX = 20f;
        float cardY = h - cardH * cardSlideY - 20f;

        Shelter s = shelters.get(currentCardIndex);

        // Фон картки — градієнт
        LinearGradient grad = new LinearGradient(
            cardX, cardY, cardX, cardY + cardH,
            Color.argb(240, 10, 90, 30),
            Color.argb(240, 5,  60, 20),
            Shader.TileMode.CLAMP
        );
        cardBgPaint.setShader(grad);

        RectF cardRect = new RectF(cardX, cardY, cardX + cardW, cardY + cardH);
        canvas.drawRoundRect(cardRect, 24f, 24f, cardBgPaint);

        // Ліва смуга кольором пріоритету
        Paint stripe = new Paint();
        stripe.setColor(getPriorityColor(s.qualityScore));
        stripe.setStyle(Paint.Style.FILL);
        canvas.drawRoundRect(new RectF(cardX, cardY, cardX + 10f, cardY + cardH), 8f, 8f, stripe);

        // Іконка типу
        markerTxtPaint.setTextSize(56f);
        canvas.drawText(s.getIcon(), cardX + 55f, cardY + 72f, markerTxtPaint);

        // Назва укриття
        cardTxtPaint.setTextSize(38f);
        canvas.drawText(
            truncate(s.name, 28),
            cardX + 110f, cardY + 62f, cardTxtPaint
        );

        // Підпис типу
        cardSubPaint.setTextSize(28f);
        canvas.drawText(
            getTypeName(s.shelterType),
            cardX + 110f, cardY + 96f, cardSubPaint
        );

        // Адреса (якщо є)
        String street = s.amenity != null ? s.amenity.getTagContent("addr:street") : null;
        if (street != null && !street.isEmpty()) {
            cardSubPaint.setTextSize(26f);
            cardSubPaint.setColor(Color.argb(170, 200, 220, 200));
            canvas.drawText(truncate(street, 32), cardX + 110f, cardY + 128f, cardSubPaint);
            cardSubPaint.setColor(Color.argb(200, 220, 220, 220));
        }

        // Відстань — великим шрифтом справа
        distancePaint.setTextSize(52f);
        canvas.drawText(s.getDistanceText(),
            cardX + cardW - 20f, cardY + 68f, distancePaint);

        // Якість укриття (зірочки)
        String stars = getQualityStars(s.qualityScore);
        distancePaint.setTextSize(28f);
        canvas.drawText(stars, cardX + cardW - 20f, cardY + 106f, distancePaint);

        // Кнопка "Маршрут" і навігатор між картками
        drawCardControls(canvas, cardX, cardY, cardW, cardH, w);
    }

    private void drawCardControls(Canvas canvas, float cx, float cy,
                                   float cw, float ch, int screenW) {
        // Стрілки перемикання карток
        if (shelters.size() > 1) {
            Paint navPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            navPaint.setColor(Color.argb(180, 255, 255, 255));
            navPaint.setTextSize(50f);
            navPaint.setTextAlign(Paint.Align.CENTER);

            // Індикатор картки: ● ○ ○
            float dotY = cy + ch - 22f;
            float dotStartX = cx + cw / 2f - (shelters.size() - 1) * 18f;
            for (int i = 0; i < shelters.size(); i++) {
                Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
                dot.setStyle(Paint.Style.FILL);
                dot.setColor(i == currentCardIndex
                    ? Color.argb(255, 100, 255, 140)
                    : Color.argb(120, 255, 255, 255));
                canvas.drawCircle(dotStartX + i * 36f, dotY, i == currentCardIndex ? 9f : 6f, dot);
            }
        }

        // Кнопка "Сюди" — маршрут
        Paint btnPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        btnPaint.setColor(Color.argb(220, 30, 200, 80));
        btnPaint.setStyle(Paint.Style.FILL);
        RectF btn = new RectF(cx + cw - 180f, cy + ch - 68f, cx + cw - 10f, cy + ch - 14f);
        canvas.drawRoundRect(btn, 20f, 20f, btnPaint);

        Paint btnTxt = new Paint(Paint.ANTI_ALIAS_FLAG);
        btnTxt.setColor(Color.WHITE);
        btnTxt.setTextSize(28f);
        btnTxt.setTextAlign(Paint.Align.CENTER);
        btnTxt.setTypeface(Typeface.DEFAULT_BOLD);
        canvas.drawText("Сюди →", cx + cw - 95f, cy + ch - 28f, btnTxt);
    }

    private int getPriorityColor(int score) {
        if (score >= 80) return Color.argb(255, 50, 220, 80);   // зелений — чудово
        if (score >= 60) return Color.argb(255, 180, 220, 50);  // жовтий — добре
        return Color.argb(255, 220, 160, 50);                    // помаранч — задовільно
    }

    private String getQualityStars(int score) {
        if (score >= 90) return "★★★★★";
        if (score >= 70) return "★★★★☆";
        if (score >= 50) return "★★★☆☆";
        if (score >= 30) return "★★☆☆☆";
        return "★☆☆☆☆";
    }

    private String getTypeName(String type) {
        switch (type) {
            case "civil_defence:shelter":   return "Офіційне укриття ЦО";
            case "amenity:shelter":          return "Укриття";
            case "railway:subway_entrance":
            case "amenity:subway_entrance":  return "Станція метро";
            case "amenity:parking_entrance": return "Підземний паркінг";
            case "building:basement":        return "Підвал";
            case "emergency:assembly_point": return "Місце збору";
            case "amenity:community_centre": return "Громадський центр";
            default:                         return "Укриття";
        }
    }

    private String truncate(String s, int max) {
        return s.length() > max ? s.substring(0, max - 1) + "…" : s;
    }

    // ----------------------------------------------------------------
    // Публічні методи — керування шаром
    // ----------------------------------------------------------------

    /**
     * Показати укриття після того як літак приземлився
     */
    public void showShelters(@NonNull List<Shelter> shelterList,
                             @Nullable LatLon userPos) {
        this.shelters.clear();
        this.shelters.addAll(shelterList);
        this.userPosition   = userPos;
        this.currentCardIndex = 0;
        this.cardsVisible   = true;
        this.cardTargetY    = 1.0f;
        this.cardSlideY     = 0f;
        this.pulseR         = 0f;

        startPulseAnimation();
        refreshMap();

        // TTS озвучення найближчого
        if (!shelterList.isEmpty()) {
            announceShelter(shelterList.get(0));
        }
    }

    public void hideShelters() {
        cardTargetY = 0f;
        handler.postDelayed(() -> {
            cardsVisible = false;
            shelters.clear();
            refreshMap();
        }, 400);
    }

    public void nextCard() {
        if (shelters.isEmpty()) return;
        currentCardIndex = (currentCardIndex + 1) % shelters.size();
        refreshMap();
    }

    public void prevCard() {
        if (shelters.isEmpty()) return;
        currentCardIndex = (currentCardIndex - 1 + shelters.size()) % shelters.size();
        refreshMap();
    }

    /** Обробка тапу — перемикання карток або вибір укриття */
    @Override
    public boolean onSingleTap(@NonNull PointF point, @NonNull RotatedTileBox tileBox) {
        if (!cardsVisible || shelters.isEmpty()) return false;

        int w = tileBox.getPixWidth();
        int h = tileBox.getPixHeight();
        float cardH   = 200f;
        float cardY   = h - cardH * cardSlideY - 20f;

        // Тап в зоні картки
        if (point.y > cardY) {
            // Права половина → наступна, ліва → попередня
            if (point.x > w / 2f) nextCard();
            else prevCard();
            return true;
        }

        // Тап на маркер укриття
        for (int i = 0; i < shelters.size(); i++) {
            Shelter s = shelters.get(i);
            if (s.location == null) continue;
            float xyX = tileBox.getPixXFromLatLon(s.location.getLatitude(), s.location.getLongitude();
            float xyY = tileBox.getPixYFromLatLon(s.location.getLatitude(), s.location.getLongitude();
            float dist = (float) Math.sqrt(
                Math.pow(point.x - xyX, 2) + Math.pow(point.y - xyY, 2));
            if (dist < 50f) {
                currentCardIndex = i;
                refreshMap();
                return true;
            }
        }
        return false;
    }

    // ----------------------------------------------------------------
    // Анімація пульсу
    // ----------------------------------------------------------------

    private void startPulseAnimation() {
        handler.post(new Runnable() {
            @Override public void run() {
                if (shelters.isEmpty()) return;
                pulseR += 4f;
                pulseAlpha = Math.max(0f, 1f - pulseR / 180f);
                if (pulseR > 180f) { pulseR = 0f; pulseAlpha = 1f; }
                refreshMap();
                handler.postDelayed(this, 16);
            }
        });
    }

    // ----------------------------------------------------------------
    // TTS
    // ----------------------------------------------------------------

    private void announceShelter(Shelter s) {
        String msg = "Найближче укриття за " + s.getDistanceText() +
                     " — " + s.name;
        try {
            android.speech.tts.TextToSpeech tts = new android.speech.tts.TextToSpeech(
                app, status -> {});
            tts.speak(msg, android.speech.tts.TextToSpeech.QUEUE_ADD, null, "shelter");
        } catch (Exception e) {
            android.util.Log.w(TAG, "TTS error: " + e.getMessage());
        }
    }

    private void refreshMap() {
        OsmandMapTileView view = app.getOsmandMap() != null
            ? app.getOsmandMap().getMapView() : null;
        if (view != null) view.refreshMap();
    }

    // ----------------------------------------------------------------
    // OsmandMapLayer interface
    // ----------------------------------------------------------------

    @Override public void initLayer(@NonNull OsmandMapTileView view) { super.initLayer(view); }
    @Override public void destroyLayer() { handler.removeCallbacksAndMessages(null); }
    @Override public boolean drawInScreenPixels() { return false; }
    @Override public boolean onLongPressEvent(@NonNull PointF p, @NonNull RotatedTileBox t) {
        return false;
    }
}
