package net.osmand.plus.plugins.safenav.alerts;

import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.util.Log;

import androidx.annotation.NonNull;

import net.osmand.plus.OsmandApplication;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * SafeNav — Менеджер тривог України
 * Джерело: api.alerts.in.ua
 */
public class AlertsManager {

    private static final String TAG = "SafeNav.Alerts";
    private static final String API_URL = "https://api.alerts.in.ua/v1/alerts/active.json";
    private static final int POLL_INTERVAL_SEC = 30;

    private final OsmandApplication app;
    private final String apiKey;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private ScheduledExecutorService scheduler;
    private TextToSpeech tts;

    private List<AlertRegion> currentAlerts = new ArrayList<>();
    private List<AlertsListener> listeners = new ArrayList<>();

    public interface AlertsListener {
        void onAlertsUpdated(List<AlertRegion> alerts);
        void onNewAlert(AlertRegion alert);
    }

    public static class AlertRegion {
        public int id;
        public String locationTitle;
        public String locationType;   // oblast, raion
        public String alertType;      // air_raid, artillery
        public String startedAt;

        @Override
        public String toString() {
            return locationTitle + " [" + alertType + "]";
        }
    }

    public AlertsManager(@NonNull OsmandApplication app, @NonNull String apiKey) {
        this.app = app;
        this.apiKey = apiKey;
        initTts();
    }

    private void initTts() {
        tts = new TextToSpeech(app, status -> {
            if (status == TextToSpeech.SUCCESS) {
                tts.setLanguage(new Locale("uk", "UA"));
                Log.i(TAG, "TTS ініціалізовано (українська)");
            }
        });
    }

    public void start() {
        if (apiKey.isEmpty()) {
            Log.w(TAG, "API ключ не задано — тривоги вимкнено. Отримайте на alerts.in.ua");
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleAtFixedRate(this::fetchAlerts, 0, POLL_INTERVAL_SEC, TimeUnit.SECONDS);
        Log.i(TAG, "Моніторинг тривог запущено (кожні " + POLL_INTERVAL_SEC + "с)");
    }

    public void stop() {
        if (scheduler != null) scheduler.shutdown();
        if (tts != null) { tts.stop(); tts.shutdown(); }
    }

    private void fetchAlerts() {
        try {
            URL url = new URL(API_URL);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("X-API-Key", apiKey);
            conn.setRequestProperty("Accept", "application/json");
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(10_000);

            if (conn.getResponseCode() == HttpURLConnection.HTTP_OK) {
                BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
                reader.close();

                List<AlertRegion> newAlerts = parseAlerts(sb.toString());
                processAlerts(newAlerts);
            }
            conn.disconnect();
        } catch (Exception e) {
            Log.w(TAG, "Помилка отримання тривог: " + e.getMessage());
        }
    }

    private List<AlertRegion> parseAlerts(String json) {
        List<AlertRegion> result = new ArrayList<>();
        try {
            JSONObject root = new JSONObject(json);
            JSONArray arr = root.getJSONArray("alerts");
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                AlertRegion r = new AlertRegion();
                r.id            = obj.optInt("id");
                r.locationTitle = obj.optString("location_title");
                r.locationType  = obj.optString("location_type");
                r.alertType     = obj.optString("alert_type");
                r.startedAt     = obj.optString("started_at");
                result.add(r);
            }
        } catch (Exception e) {
            Log.e(TAG, "Помилка парсингу: " + e.getMessage());
        }
        return result;
    }

    private void processAlerts(List<AlertRegion> newAlerts) {
        // Шукаємо НОВІ тривоги яких раніше не було
        for (AlertRegion newAlert : newAlerts) {
            boolean isNew = currentAlerts.stream()
                .noneMatch(old -> old.id == newAlert.id);
            if (isNew && "air_raid".equals(newAlert.alertType)) {
                onNewAirRaid(newAlert);
            }
        }

        currentAlerts = newAlerts;

        // Сповіщаємо слухачів в UI потоці
        mainHandler.post(() -> {
            for (AlertsListener listener : listeners) {
                listener.onAlertsUpdated(currentAlerts);
            }
        });

        Log.d(TAG, "Тривог активних: " + newAlerts.size());
    }

    private void onNewAirRaid(@NonNull AlertRegion alert) {
        String message = "Увага! Повітряна тривога в " + alert.locationTitle;
        Log.w(TAG, "🚨 " + message);

        mainHandler.post(() -> {
            // TTS сповіщення
            if (tts != null) {
                tts.speak(message, TextToSpeech.QUEUE_FLUSH, null, "safenav_alert");
            }
            // Сповіщаємо слухачів
            for (AlertsListener listener : listeners) {
                listener.onNewAlert(alert);
            }
        });
    }

    public void addListener(AlertsListener listener) {
        listeners.add(listener);
    }

    public void removeListener(AlertsListener listener) {
        listeners.remove(listener);
    }

    public List<AlertRegion> getCurrentAlerts() {
        return currentAlerts;
    }

    public boolean hasActiveAlertInRegion(String oblastName) {
        for (AlertRegion alert : currentAlerts) {
            if (alert.locationTitle != null &&
                alert.locationTitle.contains(oblastName) &&
                "air_raid".equals(alert.alertType)) {
                return true;
            }
        }
        return false;
    }
}
