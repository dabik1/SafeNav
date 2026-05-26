package net.osmand.plus.plugins.safenav.ml;

import android.app.Activity;
import android.content.Context;
import android.location.Location;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.osmand.plus.OsmandApplication;
import net.osmand.plus.activities.MapActivity;
import net.osmand.plus.plugins.OsmandPlugin;
import net.osmand.plus.plugins.safenav.ml.ImuTrainingRecorder.RecordingStats;

import android.util.Log;
import android.widget.Toast;

import java.io.File;
import java.util.List;

/**
 * SafeNav — Плагін запису тренувальних даних
 *
 * Активується кнопкою на карті.
 * Записує GPS + IMU в CSV файл.
 * Файли зберігаються в /SafeNav/training/
 */
public class TrainingRecorderPlugin extends OsmandPlugin {

    public static final String PLUGIN_ID = "net.osmand.safenav.recorder";
    private static final String TAG      = "SafeNav.RecPlugin";

    private ImuTrainingRecorder recorder;
    private RecordingWidgetView widgetView;
    private MapActivity         currentActivity;

    public TrainingRecorderPlugin(OsmandApplication app) {
        super(app);
    }

    @Override public String getId()              { return PLUGIN_ID; }
    @Override public String getName(Context ctx) { return "SafeNav: Запис даних"; }
    @Override public String getDescription(@NonNull Context ctx) {
        return "Записує GPS + IMU дані для навчання нейромережі. " +
               "Використовуйте під час звичайної їзди при хорошому GPS сигналі.";
    }

    @Override
    public boolean init(@NonNull OsmandApplication app, @Nullable Activity activity) {
        super.init(app, activity);
        recorder = new ImuTrainingRecorder(app);
        return true;
    }

    // ----------------------------------------------------------------
    // Керування записом
    // ----------------------------------------------------------------

    public void startRecording() {
        if (recorder == null || recorder.isRecording()) return;

        recorder.startRecording(new ImuTrainingRecorder.RecordingListener() {
            @Override
            public void onStatsUpdated(RecordingStats stats) {
                if (widgetView != null) widgetView.updateStats(stats);
            }

            @Override
            public void onSessionSaved(File file, RecordingStats stats) {
                Toast.makeText(app,
                    String.format("✅ Збережено: %s\n%d точок, %.1f МБ",
                        file.getName(),
                        stats.totalPoints,
                        stats.fileSizeKb / 1024f),
                    Toast.LENGTH_LONG).show();

                hideWidget();
                Log.i(TAG, "Сесія збережена: " + file.getAbsolutePath());
            }

            @Override
            public void onError(String message) {
                Toast.makeText(app, "❌ Помилка запису: " + message,
                    Toast.LENGTH_LONG).show();
            }
        });

        showWidget();
        Log.i(TAG, "Запис розпочато");
    }

    public void stopRecording() {
        if (recorder != null) recorder.stopRecording();
    }

    public boolean isRecording() {
        return recorder != null && recorder.isRecording();
    }

    // ----------------------------------------------------------------
    // GPS хук — передаємо кожну GPS точку в recorder
    // ----------------------------------------------------------------

    public void onGpsLocation(@NonNull Location loc) {
        if (recorder != null && recorder.isRecording()) {
            recorder.onGpsLocation(loc);
        }
    }

    // ----------------------------------------------------------------
    // Віджет на карті
    // ----------------------------------------------------------------

    @Override
    public void mapActivityCreate(@NonNull MapActivity activity) {
        currentActivity = activity;
    }

    @Override
    public void mapActivityDestroy(@NonNull MapActivity activity) {
        hideWidget();
        currentActivity = null;
    }

    private void showWidget() {
        if (currentActivity == null) return;

        currentActivity.runOnUiThread(() -> {
            if (widgetView != null) return;

            widgetView = new RecordingWidgetView(currentActivity);
            widgetView.setOnStopClickListener(() -> stopRecording());

            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                360, 160,
                Gravity.TOP | Gravity.END
            );
            params.topMargin  = 120;
            params.rightMargin = 16;

            // Додаємо поверх карти
            ViewGroup root = currentActivity.findViewById(android.R.id.content);
            if (root != null) root.addView(widgetView, params);
        });
    }

    private void hideWidget() {
        if (currentActivity == null || widgetView == null) return;
        currentActivity.runOnUiThread(() -> {
            ViewGroup root = currentActivity.findViewById(android.R.id.content);
            if (root != null) root.removeView(widgetView);
            widgetView = null;
        });
    }

    // ----------------------------------------------------------------
    // Статистика сесій
    // ----------------------------------------------------------------

    public List<File> getAllSessions() {
        return recorder != null ? recorder.getAllSessions() : java.util.Collections.emptyList();
    }

    public long getTotalDataMb() {
        return recorder != null ? recorder.getTotalTrainingDataMb() : 0;
    }

    @Override
    public void disable(@NonNull OsmandApplication app) {
        super.disable(app);
        if (recorder != null) recorder.shutdown();
        hideWidget();
    }

    @Nullable
    public static TrainingRecorderPlugin get(@NonNull OsmandApplication app) {
        return (TrainingRecorderPlugin) net.osmand.plus.plugins.PluginsHelper.getPlugin(PLUGIN_ID);
    }
}
