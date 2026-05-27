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
import java.util.Collections;
import java.util.List;

public class TrainingRecorderPlugin extends OsmandPlugin {

    public static final String PLUGIN_ID = "net.osmand.safenav.recorder";
    private static final String TAG = "SafeNav.RecPlugin";

    private ImuTrainingRecorder recorder;
    private RecordingWidgetView widgetView;
    private MapActivity currentActivity;

    public TrainingRecorderPlugin(OsmandApplication app) { super(app); }

    @Override public String getId() { return PLUGIN_ID; }

    // ✅ Правильні сигнатури
    @Override public String getName() { return "SafeNav: Запис даних"; }

    @Override public CharSequence getDescription(boolean linksEnabled) {
        return "Записує GPS + IMU дані для навчання нейромережі.";
    }

    @Override
    public boolean init(@NonNull OsmandApplication app, @Nullable Activity activity) {
        super.init(app, activity);
        recorder = new ImuTrainingRecorder(app);
        return true;
    }

    public void startRecording() {
        if (recorder == null || recorder.isRecording()) return;
        recorder.startRecording(new ImuTrainingRecorder.RecordingListener() {
            @Override public void onStatsUpdated(RecordingStats stats) {
                if (widgetView != null) widgetView.updateStats(stats);
            }
            @Override public void onSessionSaved(File file, RecordingStats stats) {
                Toast.makeText(app,
                    String.format("✅ Збережено: %s\n%d точок, %.1f МБ",
                        file.getName(), stats.totalPoints, stats.fileSizeKb / 1024f),
                    Toast.LENGTH_LONG).show();
                hideWidget();
            }
            @Override public void onError(String message) {
                Toast.makeText(app, "❌ " + message, Toast.LENGTH_LONG).show();
            }
        });
        showWidget();
    }

    public void stopRecording() {
        if (recorder != null) recorder.stopRecording();
    }

    public boolean isRecording() {
        return recorder != null && recorder.isRecording();
    }

    public void onGpsLocation(@NonNull Location loc) {
        if (recorder != null && recorder.isRecording()) recorder.onGpsLocation(loc);
    }

    @Override public void mapActivityCreate(@NonNull MapActivity activity) {
        currentActivity = activity;
    }

    @Override public void mapActivityDestroy(@NonNull MapActivity activity) {
        hideWidget();
        currentActivity = null;
    }

    private void showWidget() {
        if (currentActivity == null) return;
        currentActivity.runOnUiThread(() -> {
            if (widgetView != null) return;
            widgetView = new RecordingWidgetView(currentActivity);
            widgetView.setOnStopClickListener(() -> stopRecording());
            FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(
                360, 160, Gravity.TOP | Gravity.END);
            p.topMargin = 120; p.rightMargin = 16;
            ViewGroup root = currentActivity.findViewById(android.R.id.content);
            if (root != null) root.addView(widgetView, p);
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

    public List<File> getAllSessions() {
        return recorder != null ? recorder.getAllSessions() : Collections.emptyList();
    }

    @Override public void disable(@NonNull OsmandApplication app) {
        super.disable(app);
        if (recorder != null) recorder.shutdown();
        hideWidget();
    }
}
