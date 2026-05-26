package net.osmand.plus.plugins.safenav.reb;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.View;

/**
 * SafeNav — Віджет статусу GPS/РЕБ на карті
 *
 * Відображається в кутку карти:
 *   🟢 GPS  — надійний
 *   🟡 GPS? — підозрілий
 *   🔴 РЕБ  — IMU режим
 */
public class RebStatusWidget extends View {

    public enum Status { GPS_VALID, GPS_SUSPICIOUS, REB_JAMMED, IMU_ONLY }

    private Status currentStatus = Status.GPS_VALID;
    private float accuracyM = 0f;
    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bgRect = new RectF();

    public RebStatusWidget(Context ctx) { super(ctx); init(); }
    public RebStatusWidget(Context ctx, AttributeSet attrs) { super(ctx, attrs); init(); }

    private void init() {
        bgPaint.setStyle(Paint.Style.FILL);
        bgPaint.setAlpha(200);

        textPaint.setColor(Color.WHITE);
        textPaint.setTypeface(Typeface.DEFAULT_BOLD);
        textPaint.setTextSize(36f);
        textPaint.setTextAlign(Paint.Align.CENTER);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        int w = getWidth();
        int h = getHeight();

        // Фон
        bgPaint.setColor(getStatusColor());
        bgRect.set(0, 0, w, h);
        canvas.drawRoundRect(bgRect, 16f, 16f, bgPaint);

        // Іконка + текст
        String line1 = getStatusIcon() + " " + getStatusText();
        String line2 = getStatusSubtext();

        canvas.drawText(line1, w / 2f, h / 2f - 8f, textPaint);

        textPaint.setTextSize(24f);
        textPaint.setAlpha(200);
        canvas.drawText(line2, w / 2f, h / 2f + 22f, textPaint);
        textPaint.setTextSize(36f);
        textPaint.setAlpha(255);
    }

    private int getStatusColor() {
        switch (currentStatus) {
            case GPS_VALID:      return Color.parseColor("#2E7D32"); // темно-зелений
            case GPS_SUSPICIOUS: return Color.parseColor("#F57F17"); // жовтий
            case REB_JAMMED:     return Color.parseColor("#B71C1C"); // темно-червоний
            case IMU_ONLY:       return Color.parseColor("#1565C0"); // синій
            default:             return Color.GRAY;
        }
    }

    private String getStatusIcon() {
        switch (currentStatus) {
            case GPS_VALID:      return "●";
            case GPS_SUSPICIOUS: return "◑";
            case REB_JAMMED:     return "✕";
            case IMU_ONLY:       return "⟳";
            default:             return "?";
        }
    }

    private String getStatusText() {
        switch (currentStatus) {
            case GPS_VALID:      return "GPS";
            case GPS_SUSPICIOUS: return "GPS?";
            case REB_JAMMED:     return "РЕБ!";
            case IMU_ONLY:       return "IMU";
            default:             return "---";
        }
    }

    private String getStatusSubtext() {
        switch (currentStatus) {
            case GPS_VALID:      return "Надійний";
            case GPS_SUSPICIOUS: return "Перевірка...";
            case REB_JAMMED:     return "Глушіння!";
            case IMU_ONLY:       return String.format("±%.0f м", accuracyM);
            default:             return "";
        }
    }

    public void setStatus(Status status, float accuracyMeters) {
        this.currentStatus = status;
        this.accuracyM = accuracyMeters;
        invalidate();
    }

    public void setStatus(Status status) {
        setStatus(status, 0f);
    }
}
