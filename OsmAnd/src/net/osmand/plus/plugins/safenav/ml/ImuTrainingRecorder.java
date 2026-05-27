package net.osmand.plus.plugins.safenav.ml;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;

import net.osmand.plus.OsmandApplication;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * SafeNav — Записувач тренувальних даних для нейромережі
 *
 * Формат одного запису (рядок CSV):
 * timestamp_ms,
 * gps_lat, gps_lon, gps_alt, gps_speed_ms, gps_bearing, gps_accuracy,
 * acc_x, acc_y, acc_z,
 * gyro_x, gyro_y, gyro_z,
 * mag_x, mag_y, mag_z,
 * pressure_hpa,
 * gps_valid (0/1)
 *
 * Де: gps_valid=1 → GPS хороший (навчальна мітка)
 *     gps_valid=0 → GPS поганий (не використовувати як мітку)
 */
public class ImuTrainingRecorder implements SensorEventListener {

    private static final String TAG = "SafeNav.Recorder";

    // Частота запису
    private static final int IMU_RATE_US    = 20_000;   // 50 Гц
    private static final int GPS_MIN_ACC_M  = 15;        // GPS точніше 15м = valid
    private static final int FLUSH_EVERY_N  = 500;       // записуємо на диск кожні 500 рядків

    // Стан датчиків (останнє значення)
    private float[] accel    = {0, 0, 9.8f};
    private float[] gyro     = {0, 0, 0};
    private float[] mag      = {25, 0, -40};
    private float   pressure = 1013.25f;
    private long    imuTs    = 0;         // timestamp нанос

    // GPS
    private double  gpsLat  = 0, gpsLon  = 0, gpsAlt = 0;
    private float   gpsSpd  = 0, gpsBear = 0, gpsAcc = 999f;
    private long    gpsTs   = 0;
    private boolean gpsValid = false;

    // Буфер і файл
    private final List<String> buffer  = new ArrayList<>();
    private BufferedWriter writer      = null;
    private File currentFile           = null;
    private int  totalPoints           = 0;
    private int  validGpsPoints        = 0;
    private boolean recording          = false;
    private long    sessionStartMs     = 0;

    private final OsmandApplication app;
    private final SensorManager     sensorManager;
    private final ExecutorService   ioExecutor = Executors.newSingleThreadExecutor();
    private final Handler           handler    = new Handler(Looper.getMainLooper());

    // Listeners для UI
    public interface RecordingListener {
        void onStatsUpdated(RecordingStats stats);
        void onSessionSaved(File file, RecordingStats stats);
        void onError(String message);
    }

    public static class RecordingStats {
        public int   totalPoints;
        public int   validGpsPoints;
        public long  durationMs;
        public float fileSizeKb;
        public boolean isRecording;

        public String getDurationText() {
            long s = durationMs / 1000;
            return String.format("%02d:%02d:%02d", s/3600, (s%3600)/60, s%60);
        }

        public String getGpsQualityText() {
            if (totalPoints == 0) return "0%";
            return (int)(100f * validGpsPoints / totalPoints) + "%";
        }
    }

    private RecordingListener listener;

    public ImuTrainingRecorder(@NonNull OsmandApplication app) {
        this.app = app;
        this.sensorManager = (SensorManager) app.getSystemService(Context.SENSOR_SERVICE);
    }

    // ----------------------------------------------------------------
    // Старт / Стоп запису
    // ----------------------------------------------------------------

    public void startRecording(@NonNull RecordingListener listener) {
        if (recording) {
            Log.w(TAG, "Запис вже іде");
            return;
        }

        this.listener    = listener;
        this.recording   = true;
        this.totalPoints = 0;
        this.validGpsPoints = 0;
        this.sessionStartMs = System.currentTimeMillis();

        // Відкриваємо файл
        ioExecutor.execute(() -> {
            try {
                currentFile = createSessionFile();
                writer = new BufferedWriter(new FileWriter(currentFile, true));
                writeHeader();
                Log.i(TAG, "Запис розпочато: " + currentFile.getName());
            } catch (IOException e) {
                recording = false;
                handler.post(() -> listener.onError("Не вдалось створити файл: " + e.getMessage()));
            }
        });

        // Реєструємо датчики
        registerSensors();

        // Статистика кожну секунду
        handler.post(statsRunnable);
    }

    public void stopRecording() {
        if (!recording) return;
        recording = false;

        unregisterSensors();
        handler.removeCallbacks(statsRunnable);

        ioExecutor.execute(() -> {
            try {
                flushBuffer();
                if (writer != null) writer.close();

                RecordingStats stats = buildStats();
                Log.i(TAG, "Запис завершено: " + totalPoints + " точок, " +
                      currentFile.length() / 1024 + " КБ");

                handler.post(() -> {
                    if (listener != null)
                        listener.onSessionSaved(currentFile, stats);
                });
            } catch (IOException e) {
                Log.e(TAG, "Помилка закриття файлу: " + e.getMessage());
            }
        });
    }

    // ----------------------------------------------------------------
    // Датчики
    // ----------------------------------------------------------------

    private void registerSensors() {
        int[] types = {
            Sensor.TYPE_ACCELEROMETER,
            Sensor.TYPE_GYROSCOPE,
            Sensor.TYPE_MAGNETIC_FIELD,
            Sensor.TYPE_PRESSURE
        };
        for (int type : types) {
            Sensor s = sensorManager.getDefaultSensor(type);
            if (s != null) sensorManager.registerListener(this, s, IMU_RATE_US);
        }
    }

    private void unregisterSensors() {
        sensorManager.unregisterListener(this);
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (!recording) return;

        switch (event.sensor.getType()) {
            case Sensor.TYPE_ACCELEROMETER:
                accel = event.values.clone();
                imuTs = event.timestamp;
                // Записуємо рядок на кожен accel event (~50 Гц)
                writeRow();
                break;
            case Sensor.TYPE_GYROSCOPE:
                gyro = event.values.clone();
                break;
            case Sensor.TYPE_MAGNETIC_FIELD:
                mag = event.values.clone();
                break;
            case Sensor.TYPE_PRESSURE:
                pressure = event.values[0];
                break;
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    // ----------------------------------------------------------------
    // GPS — викликається ззовні (з OsmAndLocationProvider)
    // ----------------------------------------------------------------

    public void onGpsLocation(@NonNull Location loc) {
        if (!recording) return;

        gpsLat  = loc.getLatitude();
        gpsLon  = loc.getLongitude();
        gpsAlt  = loc.getAltitude();
        gpsSpd  = loc.getSpeed();
        gpsBear = loc.getBearing();
        gpsAcc  = loc.getAccuracy();
        gpsTs   = loc.getTime();
        gpsValid = loc.hasAccuracy() && loc.getAccuracy() <= GPS_MIN_ACC_M;
    }

    // ----------------------------------------------------------------
    // Запис рядка
    // ----------------------------------------------------------------

    private void writeRow() {
        if (writer == null) return;

        long tsMs = System.currentTimeMillis();

        // CSV рядок
        String row = String.format(Locale.US,
            "%d,%.7f,%.7f,%.2f,%.4f,%.2f,%.1f," +   // ts + GPS
            "%.6f,%.6f,%.6f," +                        // accel
            "%.6f,%.6f,%.6f," +                        // gyro
            "%.2f,%.2f,%.2f," +                        // mag
            "%.2f,%d",                                 // pressure + gps_valid
            tsMs,
            gpsLat, gpsLon, gpsAlt, gpsSpd, gpsBear, gpsAcc,
            accel[0], accel[1], accel[2],
            gyro[0],  gyro[1],  gyro[2],
            mag[0],   mag[1],   mag[2],
            pressure,
            gpsValid ? 1 : 0
        );

        synchronized (buffer) {
            buffer.add(row);
            totalPoints++;
            if (gpsValid) validGpsPoints++;

            if (buffer.size() >= FLUSH_EVERY_N) {
                List<String> toFlush = new ArrayList<>(buffer);
                buffer.clear();
                ioExecutor.execute(() -> writeLines(toFlush));
            }
        }
    }

    private void writeLines(List<String> lines) {
        if (writer == null) return;
        try {
            for (String line : lines) {
                writer.write(line);
                writer.newLine();
            }
            writer.flush();
        } catch (IOException e) {
            Log.e(TAG, "Помилка запису: " + e.getMessage());
        }
    }

    private void flushBuffer() {
        synchronized (buffer) {
            if (!buffer.isEmpty()) {
                writeLines(new ArrayList<>(buffer));
                buffer.clear();
            }
        }
    }

    // ----------------------------------------------------------------
    // Файл і заголовок
    // ----------------------------------------------------------------

    private File createSessionFile() {
        // Зберігаємо в /sdcard/SafeNav/training/
        File dir = new File(app.getExternalFilesDir(null), "SafeNav/training");
        if (!dir.exists()) dir.mkdirs();

        String ts = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)
            .format(new Date());
        return new File(dir, "track_" + ts + ".csv");
    }

    private void writeHeader() throws IOException {
        writer.write(
            "timestamp_ms," +
            "gps_lat,gps_lon,gps_alt_m,gps_speed_ms,gps_bearing_deg,gps_accuracy_m," +
            "acc_x,acc_y,acc_z," +
            "gyro_x,gyro_y,gyro_z," +
            "mag_x,mag_y,mag_z," +
            "pressure_hpa,gps_valid"
        );
        writer.newLine();
        writer.flush();
    }

    // ----------------------------------------------------------------
    // Статистика
    // ----------------------------------------------------------------

    private final Runnable statsRunnable = new Runnable() {
        @Override public void run() {
            if (!recording) return;
            if (listener != null)
                listener.onStatsUpdated(buildStats());
            handler.postDelayed(this, 1_000);
        }
    };

    private RecordingStats buildStats() {
        RecordingStats s = new RecordingStats();
        s.totalPoints    = totalPoints;
        s.validGpsPoints = validGpsPoints;
        s.durationMs     = System.currentTimeMillis() - sessionStartMs;
        s.fileSizeKb     = currentFile != null ? currentFile.length() / 1024f : 0;
        s.isRecording    = recording;
        return s;
    }

    public boolean isRecording() { return recording; }
    public File getCurrentFile() { return currentFile; }

    public List<File> getAllSessions() {
        File dir = new File(app.getExternalFilesDir(null), "SafeNav/training");
        List<File> files = new ArrayList<>();
        if (dir.exists()) {
            for (File f : dir.listFiles()) {
                if (f.getName().endsWith(".csv")) files.add(f);
            }
        }
        return files;
    }

    public long getTotalTrainingDataMb() {
        long total = 0;
        for (File f : getAllSessions()) total += f.length();
        return total / (1024 * 1024);
    }

    public void shutdown() {
        if (recording) stopRecording();
        ioExecutor.shutdown();
    }
}
