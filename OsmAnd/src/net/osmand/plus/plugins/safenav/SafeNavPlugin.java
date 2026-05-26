package net.osmand.plus.plugins.safenav;

import android.app.Activity;
import android.content.Context;
import android.hardware.SensorManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.osmand.plus.OsmandApplication;
import net.osmand.plus.R;
import net.osmand.plus.activities.MapActivity;
import net.osmand.plus.plugins.OsmandPlugin;
import net.osmand.plus.plugins.safenav.alerts.AlertsApiClient;
import net.osmand.plus.plugins.safenav.alerts.AlertsManager;
import net.osmand.plus.plugins.safenav.reb.GpsValidator;
import net.osmand.plus.plugins.safenav.reb.ImuDeadReckoning;
import net.osmand.plus.plugins.safenav.reb.RebStatusWidget;
import net.osmand.plus.views.mapwidgets.MapWidgetInfo;
import net.osmand.plus.views.mapwidgets.WidgetType;
import net.osmand.plus.views.mapwidgets.WidgetsPanel;

import android.util.Log;
import android.widget.Toast;

import java.util.List;

/**
 * SafeNav — головний плагін
 * Реєструється в PluginsHelper.initPlugins()
 */
public class SafeNavPlugin extends OsmandPlugin {

    public static final String PLUGIN_ID = "net.osmand.safenav";
    private static final String TAG = "SafeNav";

    // Налаштування
    public static final String PREF_ALERTS_ENABLED   = "safenav_alerts_enabled";
    public static final String PREF_ALERTS_API_KEY   = "safenav_alerts_api_key";
    public static final String PREF_REB_DETECTION    = "safenav_reb_detection";
    public static final String PREF_IMU_FALLBACK     = "safenav_imu_fallback";

    // Наші менеджери
    private AlertsManager alertsManager;
    private GpsValidator gpsValidator;
    private ImuDeadReckoning imuDeadReckoning;

    public SafeNavPlugin(OsmandApplication app) {
        super(app);
    }

    @Override
    public String getId() {
        return PLUGIN_ID;
    }

    @Override
    public String getName(Context ctx) {
        return "SafeNav — Захист від РЕБ";
    }

    @Override
    public String getDescription(@NonNull Context ctx) {
        return "Навігація під час повітряних тривог. Детектує GPS глушіння, " +
               "перемикається на IMU датчики, показує активні тривоги на карті.";
    }

    @Override
    public int getLogoResourceId() {
        // Використаємо стандартну іконку поки своєї немає
        return R.drawable.ic_action_location_color;
    }

    @Override
    public boolean isEnabled() {
        return app.getSettings().getBoolean(PREF_ALERTS_ENABLED, true);
    }

    // ----------------------------------------------------------------
    // Lifecycle
    // ----------------------------------------------------------------

    @Override
    public void onInstall(@NonNull OsmandApplication app, @Nullable Activity activity) {
        super.onInstall(app, activity);
        Log.i(TAG, "SafeNav встановлено");
    }

    @Override
    public boolean init(@NonNull OsmandApplication app, @Nullable Activity activity) {
        super.init(app, activity);
        Log.i(TAG, "SafeNav ініціалізація...");

        // 1. Менеджер тривог
        String apiKey = app.getSettings().getString(PREF_ALERTS_API_KEY, "");
        alertsManager = new AlertsManager(app, apiKey);
        alertsManager.start();

        // 2. Валідатор GPS (детекція РЕБ)
        if (app.getSettings().getBoolean(PREF_REB_DETECTION, true)) {
            SensorManager sm = (SensorManager) app.getSystemService(Context.SENSOR_SERVICE);
            gpsValidator = new GpsValidator(sm);
            Log.i(TAG, "GpsValidator запущено");
        }

        // 3. IMU dead reckoning (ініціалізуємо, але не запускаємо — тільки при РЕБ)
        if (app.getSettings().getBoolean(PREF_IMU_FALLBACK, true)) {
            SensorManager sm = (SensorManager) app.getSystemService(Context.SENSOR_SERVICE);
            imuDeadReckoning = new ImuDeadReckoning(sm);
            Log.i(TAG, "ImuDeadReckoning готовий");
        }

        Log.i(TAG, "SafeNav ініціалізовано успішно");
        return true;
    }

    @Override
    public void disable(@NonNull OsmandApplication app) {
        super.disable(app);
        if (alertsManager != null) alertsManager.stop();
        if (gpsValidator != null)  gpsValidator.release();
        if (imuDeadReckoning != null) imuDeadReckoning.stop();
        Log.i(TAG, "SafeNav вимкнено");
    }

    // ----------------------------------------------------------------
    // GPS validation hook — викликається з OsmAndLocationProvider
    // ----------------------------------------------------------------

    /**
     * Перевіряємо кожну нову GPS точку перед тим як передати в OsmAnd
     * @return true якщо GPS надійний, false — треба перейти на IMU
     */
    public boolean validateGpsLocation(net.osmand.Location location) {
        if (gpsValidator == null) return true;

        android.location.Location androidLoc = new android.location.Location("gps");
        androidLoc.setLatitude(location.getLatitude());
        androidLoc.setLongitude(location.getLongitude());
        androidLoc.setAccuracy(location.getAccuracy());
        androidLoc.setSpeed((float) location.getSpeed());

        GpsValidator.ValidationResult result = gpsValidator.validate(androidLoc);

        switch (result.getStatus()) {
            case JAMMED:
                Log.w(TAG, "РЕБ виявлено! " + result.getReason());
                onRebDetected(location);
                return false;

            case SUSPICIOUS:
                Log.w(TAG, "GPS підозрілий: " + result.getReason());
                // Показуємо жовтий індикатор але продовжуємо
                notifyGpsStatus(GpsValidator.GpsStatus.SUSPICIOUS);
                return true;

            default:
                notifyGpsStatus(GpsValidator.GpsStatus.VALID);
                return true;
        }
    }

    private void onRebDetected(net.osmand.Location lastGoodLocation) {
        // Запускаємо IMU режим від останньої відомої точки
        if (imuDeadReckoning != null) {
            imuDeadReckoning.start(
                lastGoodLocation.getLatitude(),
                lastGoodLocation.getLongitude(),
                position -> {
                    // Передаємо IMU позицію в OsmAnd навігацію
                    Log.d(TAG, String.format("IMU: %.6f, %.6f ±%.0fm",
                        position.getLatitude(), position.getLongitude(), position.getAccuracyM()));
                }
            );
        }

        // UI сповіщення в головному потоці
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
            Toast.makeText(app,
                "⚠️ GPS заглушено! Перехід на IMU навігацію",
                Toast.LENGTH_LONG).show();
        });

        notifyGpsStatus(GpsValidator.GpsStatus.JAMMED);
    }

    private void notifyGpsStatus(GpsValidator.GpsStatus status) {
        // Оновлюємо віджет статусу на карті
        app.runInUIThread(() -> {
            // TODO: оновити RebStatusWidget
        });
    }

    // ----------------------------------------------------------------
    // Getters
    // ----------------------------------------------------------------

    public AlertsManager getAlertsManager()         { return alertsManager; }
    public GpsValidator getGpsValidator()           { return gpsValidator; }
    public ImuDeadReckoning getImuDeadReckoning()   { return imuDeadReckoning; }

    @Nullable
    public static SafeNavPlugin get(@NonNull OsmandApplication app) {
        return (SafeNavPlugin) net.osmand.plus.plugins.PluginsHelper.getPlugin(PLUGIN_ID);
    }
}
