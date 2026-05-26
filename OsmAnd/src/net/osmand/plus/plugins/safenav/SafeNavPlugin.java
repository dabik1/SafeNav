package net.osmand.plus.plugins.safenav;

import android.app.Activity;
import android.content.Context;
import android.hardware.SensorManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.osmand.plus.OsmandApplication;
import net.osmand.plus.activities.MapActivity;
import net.osmand.plus.plugins.OsmandPlugin;
import net.osmand.plus.plugins.safenav.alerts.AlertMapLayer;
import net.osmand.plus.plugins.safenav.alerts.AlertsManager;
import net.osmand.plus.plugins.safenav.alerts.AlertsManager.AlertRegion;
import net.osmand.plus.plugins.safenav.reb.GpsValidator;
import net.osmand.plus.plugins.safenav.reb.ImuDeadReckoning;
import net.osmand.plus.views.OsmandMapTileView;

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
    public static final String PREF_ALERTS_ENABLED = "safenav_alerts_enabled";
    public static final String PREF_ALERTS_API_KEY = "safenav_alerts_api_key";
    public static final String PREF_REB_DETECTION  = "safenav_reb_detection";
    public static final String PREF_IMU_FALLBACK   = "safenav_imu_fallback";

    // Шар карти та менеджери
    private AlertMapLayer alertMapLayer;
    private AlertsManager alertsManager;
    private GpsValidator  gpsValidator;
    private ImuDeadReckoning imuDeadReckoning;

    public SafeNavPlugin(OsmandApplication app) {
        super(app);
    }

    @Override public String getId()                   { return PLUGIN_ID; }
    @Override public String getName(Context ctx)      { return "SafeNav — Захист від РЕБ"; }
    @Override public String getDescription(@NonNull Context ctx) {
        return "Навігація під час тривог. Детектує GPS глушіння РЕБ, " +
               "перемикається на IMU датчики, показує тривоги на карті.";
    }
    @Override public int getLogoResourceId() {
        return net.osmand.plus.R.drawable.ic_action_location_color;
    }

    // ----------------------------------------------------------------
    // Ініціалізація
    // ----------------------------------------------------------------

    @Override
    public boolean init(@NonNull OsmandApplication app, @Nullable Activity activity) {
        super.init(app, activity);
        Log.i(TAG, "SafeNav init...");

        // Менеджер тривог
        String apiKey = app.getSettings().getString(PREF_ALERTS_API_KEY, "");
        alertsManager = new AlertsManager(app, apiKey != null ? apiKey : "");
        alertsManager.start();

        // GpsValidator
        if (app.getSettings().getBoolean(PREF_REB_DETECTION, true)) {
            SensorManager sm = (SensorManager) app.getSystemService(Context.SENSOR_SERVICE);
            gpsValidator = new GpsValidator(sm);
        }

        // IMU dead reckoning
        if (app.getSettings().getBoolean(PREF_IMU_FALLBACK, true)) {
            SensorManager sm = (SensorManager) app.getSystemService(Context.SENSOR_SERVICE);
            imuDeadReckoning = new ImuDeadReckoning(sm);
        }

        Log.i(TAG, "SafeNav ініціалізовано");
        return true;
    }

    // ----------------------------------------------------------------
    // Реєстрація шару карти
    // ----------------------------------------------------------------

    @Override
    public void registerLayers(@NonNull Context context, @Nullable MapActivity mapActivity) {
        if (mapActivity == null) return;

        OsmandMapTileView mapView = mapActivity.getMapView();
        if (mapView == null) return;

        // Видаляємо старий шар якщо є
        if (alertMapLayer != null) {
            mapView.removeLayer(alertMapLayer);
        }

        // Створюємо шар — zOrder 8.5 (вище навігації але нижче меню)
        alertMapLayer = new AlertMapLayer(context, app);
        mapView.addLayer(alertMapLayer, 8.5f);

        // Підключаємо listener тривог → шар карти
        alertsManager.addListener(new AlertsManager.AlertsListener() {
            @Override
            public void onAlertsUpdated(List<AlertRegion> alerts) {
                if (alertMapLayer != null) {
                    alertMapLayer.setAlerts(alerts);
                }
            }

            @Override
            public void onNewAlert(AlertRegion alert) {
                if (alertMapLayer != null) {
                    alertMapLayer.onNewAlert(alert);
                }
                // Toast додатково
                new android.os.Handler(android.os.Looper.getMainLooper()).post(() ->
                    Toast.makeText(app,
                        "🚨 Тривога: " + alert.locationTitle,
                        Toast.LENGTH_LONG).show()
                );
            }
        });

        Log.i(TAG, "AlertMapLayer зареєстровано на карті");
    }

    // ----------------------------------------------------------------
    // GPS валідація
    // ----------------------------------------------------------------

    public boolean validateGpsLocation(net.osmand.Location location) {
        if (gpsValidator == null) return true;

        android.location.Location androidLoc = new android.location.Location("gps");
        androidLoc.setLatitude(location.getLatitude());
        androidLoc.setLongitude(location.getLongitude());
        androidLoc.setAccuracy(location.getAccuracy());
        androidLoc.setSpeed((float) location.getSpeed());

        GpsValidator.ValidationResult result = gpsValidator.validate(androidLoc);

        if (result.getStatus() == GpsValidator.GpsStatus.JAMMED) {
            Log.w(TAG, "РЕБ виявлено! " + result.getReason());
            onRebDetected(location);
            return false;
        }
        return true;
    }

    private void onRebDetected(net.osmand.Location last) {
        if (imuDeadReckoning != null) {
            imuDeadReckoning.start(last.getLatitude(), last.getLongitude(), pos ->
                Log.d(TAG, String.format("IMU позиція: %.6f, %.6f ±%.0fm",
                    pos.getLatitude(), pos.getLongitude(), pos.getAccuracyM()))
            );
        }
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() ->
            Toast.makeText(app, "⚠️ GPS заглушено! Перехід на IMU", Toast.LENGTH_LONG).show()
        );
    }

    // ----------------------------------------------------------------
    // Lifecycle
    // ----------------------------------------------------------------

    @Override
    public void disable(@NonNull OsmandApplication app) {
        super.disable(app);
        if (alertsManager != null)    alertsManager.stop();
        if (gpsValidator != null)     gpsValidator.release();
        if (imuDeadReckoning != null) imuDeadReckoning.stop();
        if (alertMapLayer != null)    alertMapLayer.stopAnimation();
        Log.i(TAG, "SafeNav вимкнено");
    }

    @Override
    public void mapActivityDestroy(@NonNull MapActivity activity) {
        if (alertMapLayer != null) {
            activity.getMapView().removeLayer(alertMapLayer);
            alertMapLayer = null;
        }
    }

    // ----------------------------------------------------------------
    // Getters
    // ----------------------------------------------------------------

    public AlertMapLayer      getAlertMapLayer()    { return alertMapLayer; }
    public AlertsManager      getAlertsManager()    { return alertsManager; }
    public GpsValidator       getGpsValidator()     { return gpsValidator; }
    public ImuDeadReckoning   getImuDeadReckoning() { return imuDeadReckoning; }

    @Nullable
    public static SafeNavPlugin get(@NonNull OsmandApplication app) {
        return (SafeNavPlugin) net.osmand.plus.plugins.PluginsHelper.getPlugin(PLUGIN_ID);
    }
}
