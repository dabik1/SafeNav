package net.osmand.plus.plugins.safenav;

import android.app.Activity;
import android.content.Context;
import android.hardware.SensorManager;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.osmand.data.LatLon;
import net.osmand.plus.OsmandApplication;
import net.osmand.plus.activities.MapActivity;
import net.osmand.plus.plugins.OsmandPlugin;
import net.osmand.plus.plugins.safenav.alerts.AlertMapLayer;
import net.osmand.plus.plugins.safenav.alerts.AlertsManager;
import net.osmand.plus.plugins.safenav.alerts.AlertsManager.AlertRegion;
import net.osmand.plus.plugins.safenav.reb.GpsValidator;
import net.osmand.plus.plugins.safenav.reb.ImuDeadReckoning;
import net.osmand.plus.plugins.safenav.shelter.ShelterFinder;
import net.osmand.plus.plugins.safenav.shelter.ShelterFinder.Shelter;
import net.osmand.plus.plugins.safenav.shelter.ShelterMapLayer;
import net.osmand.plus.views.OsmandMapTileView;

import android.util.Log;
import android.widget.Toast;

import java.util.List;

/**
 * SafeNav — головний плагін
 * Координує: тривоги → літак → пошук укриттів → карта
 */
public class SafeNavPlugin extends OsmandPlugin {

    public static final String PLUGIN_ID  = "net.osmand.safenav";
    private static final String TAG       = "SafeNav";

    // Налаштування
    public static final String PREF_ALERTS_ENABLED  = "safenav_alerts_enabled";
    public static final String PREF_ALERTS_API_KEY  = "safenav_alerts_api_key";
    public static final String PREF_REB_DETECTION   = "safenav_reb_detection";
    public static final String PREF_IMU_FALLBACK    = "safenav_imu_fallback";
    public static final String PREF_SHELTER_SEARCH  = "safenav_shelter_search";

    // Шари карти
    private AlertMapLayer    alertMapLayer;
    private ShelterMapLayer  shelterMapLayer;

    // Менеджери
    private AlertsManager    alertsManager;
    private GpsValidator     gpsValidator;
    private ImuDeadReckoning imuDeadReckoning;
    private ShelterFinder    shelterFinder;

    public SafeNavPlugin(OsmandApplication app) { super(app); }

    @Override public String getId()              { return PLUGIN_ID; }
    @Override public String getName(Context ctx) { return "SafeNav — Захист від РЕБ"; }
    @Override public String getDescription(@NonNull Context ctx) {
        return "Навігація під час тривог. Детектує РЕБ, IMU fallback, укриття на карті.";
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

        // 1. Тривоги
        String apiKey = app.getSettings().getString(PREF_ALERTS_API_KEY, "");
        alertsManager = new AlertsManager(app, apiKey != null ? apiKey : "");
        alertsManager.start();

        // 2. GPS детектор
        if (app.getSettings().getBoolean(PREF_REB_DETECTION, true)) {
            SensorManager sm = (SensorManager) app.getSystemService(Context.SENSOR_SERVICE);
            gpsValidator = new GpsValidator(sm);
        }

        // 3. IMU
        if (app.getSettings().getBoolean(PREF_IMU_FALLBACK, true)) {
            SensorManager sm = (SensorManager) app.getSystemService(Context.SENSOR_SERVICE);
            imuDeadReckoning = new ImuDeadReckoning(sm);
        }

        // 4. Пошук укриттів
        shelterFinder = new ShelterFinder(app);

        Log.i(TAG, "SafeNav ініціалізовано");
        return true;
    }

    // ----------------------------------------------------------------
    // Реєстрація шарів карти
    // ----------------------------------------------------------------

    @Override
    public void registerLayers(@NonNull Context context, @Nullable MapActivity mapActivity) {
        if (mapActivity == null) return;
        OsmandMapTileView mapView = mapActivity.getMapView();
        if (mapView == null) return;

        // Видаляємо старі шари
        if (alertMapLayer   != null) mapView.removeLayer(alertMapLayer);
        if (shelterMapLayer != null) mapView.removeLayer(shelterMapLayer);

        // Шар тривог — zOrder 8.5 (вище навігації)
        alertMapLayer = new AlertMapLayer(context, app);
        mapView.addLayer(alertMapLayer, 8.5f);

        // Шар укриттів — zOrder 8.6 (поверх тривог)
        shelterMapLayer = new ShelterMapLayer(context, app);
        mapView.addLayer(shelterMapLayer, 8.6f);

        // --- Підключаємо ланцюжок подій ---
        // Тривога → AlertMapLayer (літак) → ShelterFinder → ShelterMapLayer
        alertsManager.addListener(new AlertsManager.AlertsListener() {

            @Override
            public void onAlertsUpdated(List<AlertRegion> alerts) {
                alertMapLayer.setAlerts(alerts);
                // Якщо тривога скасована — ховаємо укриття
                boolean hasAirRaid = alerts.stream()
                    .anyMatch(a -> "air_raid".equals(a.alertType));
                if (!hasAirRaid) shelterMapLayer.hideShelters();
            }

            @Override
            public void onNewAlert(AlertRegion alert) {
                // 1. Показуємо літак на карті
                alertMapLayer.onNewAlert(alert);

                // 2. Через 3 секунди (після приземлення) → шукаємо укриття
                new android.os.Handler(android.os.Looper.getMainLooper())
                    .postDelayed(() -> searchSheltersNearUser(), 3_000);

                // 3. Toast
                Toast.makeText(app,
                    "🚨 Тривога: " + alert.locationTitle,
                    Toast.LENGTH_LONG).show();
            }
        });

        Log.i(TAG, "SafeNav шари зареєстровано");
    }

    // ----------------------------------------------------------------
    // Пошук укриттів
    // ----------------------------------------------------------------

    private void searchSheltersNearUser() {
        if (shelterFinder == null || shelterMapLayer == null) return;
        if (!app.getSettings().getBoolean(PREF_SHELTER_SEARCH, true)) return;

        // Отримуємо поточну позицію
        net.osmand.Location loc = app.getLocationProvider().getLastKnownLocation();
        if (loc == null) {
            Log.w(TAG, "Позиція невідома — не можемо шукати укриття");
            return;
        }

        double lat = loc.getLatitude();
        double lon = loc.getLongitude();

        Log.i(TAG, String.format("Шукаємо укриття біля %.4f, %.4f", lat, lon));

        shelterFinder.findNearby(lat, lon, 5, new ShelterFinder.ShelterCallback() {

            @Override
            public void onSheltersFound(List<Shelter> shelters, double radiusM) {
                Log.i(TAG, "Знайдено " + shelters.size() +
                      " укриттів в радіусі " + (int)radiusM + "м");

                // Показуємо на карті
                LatLon userPos = new LatLon(lat, lon);
                shelterMapLayer.showShelters(shelters, userPos);

                // Toast з найближчим
                Shelter nearest = shelters.get(0);
                Toast.makeText(app,
                    "🏠 Найближче укриття: " + nearest.name +
                    " — " + nearest.getDistanceText(),
                    Toast.LENGTH_LONG).show();
            }

            @Override
            public void onNoSheltersFound(double maxRadius) {
                Log.w(TAG, "Укриттів не знайдено в радіусі " + (int)maxRadius + "м");
                Toast.makeText(app,
                    "⚠ Укриттів поблизу не знайдено. " +
                    "Шукайте підвал або заглиблений простір.",
                    Toast.LENGTH_LONG).show();
            }

            @Override
            public void onError(String message) {
                Log.e(TAG, "Помилка пошуку укриттів: " + message);
            }
        });
    }

    // ----------------------------------------------------------------
    // GPS валідація / РЕБ
    // ----------------------------------------------------------------

    public boolean validateGpsLocation(net.osmand.Location location) {
        if (gpsValidator == null) return true;

        android.location.Location al = new android.location.Location("gps");
        al.setLatitude(location.getLatitude());
        al.setLongitude(location.getLongitude());
        al.setAccuracy(location.getAccuracy());
        al.setSpeed((float) location.getSpeed());

        GpsValidator.ValidationResult r = gpsValidator.validate(al);

        if (r.getStatus() == GpsValidator.GpsStatus.JAMMED) {
            Log.w(TAG, "РЕБ! " + r.getReason());
            onRebDetected(location);
            return false;
        }
        return true;
    }

    private void onRebDetected(net.osmand.Location last) {
        if (imuDeadReckoning != null) {
            imuDeadReckoning.start(last.getLatitude(), last.getLongitude(),
                pos -> Log.d(TAG, String.format("IMU: %.6f,%.6f ±%.0fm",
                    pos.getLatitude(), pos.getLongitude(), pos.getAccuracyM()))
            );
        }
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() ->
            Toast.makeText(app, "⚠️ GPS заглушено! IMU режим", Toast.LENGTH_LONG).show());
    }

    // ----------------------------------------------------------------
    // Lifecycle
    // ----------------------------------------------------------------

    @Override
    public void disable(@NonNull OsmandApplication app) {
        super.disable(app);
        if (alertsManager    != null) alertsManager.stop();
        if (gpsValidator     != null) gpsValidator.release();
        if (imuDeadReckoning != null) imuDeadReckoning.stop();
        if (shelterFinder    != null) shelterFinder.shutdown();
        if (alertMapLayer    != null) alertMapLayer.stopAnimation();
        if (shelterMapLayer  != null) shelterMapLayer.hideShelters();
        Log.i(TAG, "SafeNav вимкнено");
    }

    @Override
    public void mapActivityDestroy(@NonNull MapActivity activity) {
        OsmandMapTileView mv = activity.getMapView();
        if (alertMapLayer   != null) mv.removeLayer(alertMapLayer);
        if (shelterMapLayer != null) mv.removeLayer(shelterMapLayer);
        alertMapLayer   = null;
        shelterMapLayer = null;
    }

    // ----------------------------------------------------------------
    // Getters
    // ----------------------------------------------------------------

    public AlertMapLayer     getAlertMapLayer()    { return alertMapLayer; }
    public ShelterMapLayer   getShelterMapLayer()  { return shelterMapLayer; }
    public AlertsManager     getAlertsManager()    { return alertsManager; }
    public ShelterFinder     getShelterFinder()    { return shelterFinder; }
    public GpsValidator      getGpsValidator()     { return gpsValidator; }
    public ImuDeadReckoning  getImuDeadReckoning() { return imuDeadReckoning; }

    @Nullable
    public static SafeNavPlugin get(@NonNull OsmandApplication app) {
        return (SafeNavPlugin) net.osmand.plus.plugins.PluginsHelper.getPlugin(PLUGIN_ID);
    }
}
