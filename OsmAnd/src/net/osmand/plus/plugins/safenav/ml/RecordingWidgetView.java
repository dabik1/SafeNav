package net.osmand.plus.plugins.safenav.ml;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import net.osmand.plus.plugins.safenav.ml.ImuTrainingRecorder.RecordingStats;

/**
 * SafeNav — Віджет запису тренувальних даних
 *
 * Відображається поверх карти (кут екрану):
 *
 * ┌──────────────────────────────┐
 * │ 🔴 REC  00:23:15            │
 * │ Точок: 84,230   GPS: 94%    │
 * │ Файл: 12.4 МБ  [■ СТОП]    │
 * └──────────────────────────────┘
 */
public class RecordingWidgetView extends View {

    private RecordingStats stats;
    private boolean blinkState = true;
    private final Handler handler = new Handler(Looper.getMainLooper());

    // Паінти
    private final Paint bgPaint   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint recPaint  = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint txtPaint  = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint subPaint  = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint barPaint  = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint btnPaint  = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint btnTxtPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gpsGoodPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gpsBadPaint  = new Paint(Paint.ANTI_ALIAS_FLAG);

    private OnStopClickListener stopListener;

    public interface OnStopClickListener {
        void onStopClicked();
    }

    public RecordingWidgetView(Context ctx) { super(ctx); init(); }
    public RecordingWidgetView(Context ctx, AttributeSet a) { super(ctx, a); init(); }

    private void init() {
        bgPaint.setStyle(Paint.Style.FILL);

        recPaint.setColor(Color.argb(255, 255, 50, 50));
        recPaint.setStyle(Paint.Style.FILL);

        txtPaint.setColor(Color.WHITE);
        txtPaint.setTypeface(Typeface.DEFAULT_BOLD);
        txtPaint.setTextSize(38f);

        subPaint.setColor(Color.argb(200, 210, 210, 210));
        subPaint.setTextSize(28f);

        barPaint.setColor(Color.argb(200, 100, 200, 100));
        barPaint.setStyle(Paint.Style.FILL);

        btnPaint.setColor(Color.argb(220, 200, 30, 30));
        btnPaint.setStyle(Paint.Style.FILL);

        btnTxtPaint.setColor(Color.WHITE);
        btnTxtPaint.setTypeface(Typeface.DEFAULT_BOLD);
        btnTxtPaint.setTextSize(28f);
        btnTxtPaint.setTextAlign(Paint.Align.CENTER);

        gpsGoodPaint.setColor(Color.argb(255, 60, 220, 80));
        gpsGoodPaint.setStyle(Paint.Style.FILL);

        gpsBadPaint.setColor(Color.argb(255, 220, 80, 60));
        gpsBadPaint.setStyle(Paint.Style.FILL);

        // Блимання REC кожні 600мс
        handler.post(blinkRunnable);
    }

    private final Runnable blinkRunnable = new Runnable() {
        @Override public void run() {
            blinkState = !blinkState;
            invalidate();
            handler.postDelayed(this, 600);
        }
    };

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (stats == null) return;

        int w = getWidth();
        int h = getHeight();

        // Фон — темно-сірий напівпрозорий
        LinearGradient grad = new LinearGradient(0, 0, 0, h,
            Color.argb(230, 25, 25, 30),
            Color.argb(220, 15, 15, 20),
            Shader.TileMode.CLAMP);
        bgPaint.setShader(grad);
        canvas.drawRoundRect(new RectF(0, 0, w, h), 20f, 20f, bgPaint);

        // ── Рядок 1: REC • час ──
        float y1 = 46f;

        // Блимаючий кружок REC
        if (blinkState) {
            canvas.drawCircle(28f, y1 - 10f, 11f, recPaint);
        }

        txtPaint.setColor(Color.WHITE);
        txtPaint.setTextSize(38f);
        canvas.drawText("REC", 50f, y1, txtPaint);

        // Таймер
        txtPaint.setColor(Color.argb(255, 180, 255, 180));
        canvas.drawText(stats.getDurationText(), 120f, y1, txtPaint);

        // ── Рядок 2: точки + GPS якість ──
        float y2 = y1 + 40f;

        subPaint.setTextSize(28f);
        subPaint.setColor(Color.argb(200, 200, 200, 200));
        canvas.drawText(formatPoints(stats.totalPoints) + " точок", 14f, y2, subPaint);

        // GPS індикатор якості
        float gpsQuality = stats.totalPoints > 0
            ? (float) stats.validGpsPoints / stats.totalPoints : 0f;
        drawGpsQuality(canvas, w - 140f, y2 - 22f, 130f, 30f, gpsQuality);

        // ── Рядок 3: розмір файлу + кнопка СТОП ──
        float y3 = y2 + 38f;

        subPaint.setColor(Color.argb(170, 170, 200, 170));
        canvas.drawText(String.format("%.1f МБ", stats.fileSizeKb / 1024f),
            14f, y3, subPaint);

        // Кнопка СТОП
        RectF btn = new RectF(w - 120f, y3 - 32f, w - 10f, y3 + 4f);
        canvas.drawRoundRect(btn, 14f, 14f, btnPaint);
        canvas.drawText("⬛ СТОП", btn.centerX(), btn.centerY() + 10f, btnTxtPaint);
    }

    private void drawGpsQuality(Canvas canvas, float x, float y,
                                 float barW, float barH, float quality) {
        // Фон смуги
        Paint bgBar = new Paint(Paint.ANTI_ALIAS_FLAG);
        bgBar.setColor(Color.argb(100, 80, 80, 80));
        bgBar.setStyle(Paint.Style.FILL);
        canvas.drawRoundRect(new RectF(x, y, x + barW, y + barH), 8f, 8f, bgBar);

        // Заповнення
        int r = (int)((1 - quality) * 220);
        int g = (int)(quality * 200);
        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        fill.setColor(Color.argb(200, r, g, 50));
        fill.setStyle(Paint.Style.FILL);
        canvas.drawRoundRect(
            new RectF(x, y, x + barW * quality, y + barH), 8f, 8f, fill);

        // Текст відсотку
        Paint pct = new Paint(Paint.ANTI_ALIAS_FLAG);
        pct.setColor(Color.WHITE);
        pct.setTextSize(22f);
        pct.setTextAlign(Paint.Align.CENTER);
        pct.setTypeface(Typeface.DEFAULT_BOLD);
        canvas.drawText("GPS " + (int)(quality * 100) + "%",
            x + barW / 2f, y + barH - 6f, pct);
    }

    private String formatPoints(int n) {
        if (n < 1000) return String.valueOf(n);
        return String.format("%.1fK", n / 1000f);
    }

    public void updateStats(RecordingStats stats) {
        this.stats = stats;
        invalidate();
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getAction() == MotionEvent.ACTION_UP && stats != null) {
            // Перевіряємо тап на кнопку СТОП
            float btnX = getWidth() - 120f;
            if (e.getX() > btnX && stopListener != null) {
                stopListener.onStopClicked();
                return true;
            }
        }
        return super.onTouchEvent(e);
    }

    public void setOnStopClickListener(OnStopClickListener l) {
        this.stopListener = l;
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        handler.removeCallbacks(blinkRunnable);
    }
}
