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
import net.osmand.plus.plugins.safenav.shelter.ShelterFinder;
import net.osmand.plus.plugins.safenav.shelter.ShelterFinder.Shelter;
import net.osmand.plus.plugins.safenav.shelter.ShelterMapLayer;
import net.osmand.plus.settings.backend.OsmandSettings;
import net.osmand.plus.views.OsmandMapTileView;

import android.util.Log;
import android.widget.Toast;

import java.util.List;

public class SafeNavPlugin extends OsmandPlugin {

    public static final String PLUGIN_ID = "net.osmand.safenav";
    private static final String TAG = "SafeNav";

    public static final String PREF_ALERTS_ENABLED = "safenav_alerts_enabled";
    public static final String PREF_ALERTS_API_KEY = "safenav_alerts_api_key";
    public static final String PREF_REB_DETECTION  = "safenav_reb_detection";
    public static final String PREF_IMU_FALLBACK   = "safenav_imu_fallback";
    public static final String PREF_SHELTER_SEARCH = "safenav_shelter_search";

    private AlertMapLayer   alertMapLayer;
    private ShelterMapLayer shelterMapLayer;
    private AlertsManager   alertsManager;
    private ShelterFinder   shelterFinder;
    // GpsValidator і ImuDeadReckoning — підключимо пізніше окремим кроком

    public SafeNavPlugin(OsmandApplication app) { super(app); }

    @Override public String getId() { return PLUGIN_ID; }

    // ✅ Правильна сигнатура
    @Override public String getName() { return "SafeNav — Захист від РЕБ"; }

    // ✅ Правильна сигнатура
    @Override public CharSequence getDescription(boolean linksEnabled) {
        return "Навігація під час тривог. Детектує РЕБ, IMU fallback, укриття на карті.";
    }

    @Override
    public boolean init(@NonNull OsmandApplication app, @Nullable Activity activity) {
        super.init(app, activity);
        OsmandSettings settings = app.getSettings();

        // ✅ Правильний API для налаштувань
        String apiKey = settings.registerStringPreference(PREF_ALERTS_API_KEY, "")
            .makeGlobal().get();

        alertsManager = new AlertsManager(app, apiKey != null ? apiKey : "");
        alertsManager.start();

        shelterFinder = new ShelterFinder(app);
        Log.i(TAG, "SafeNav ініціалізовано");
        return true;
    }

    @Override
    public void registerLayers(@NonNull Context context, @Nullable MapActivity mapActivity) {
        if (mapActivity == null) return;
        OsmandMapTileView mapView = mapActivity.getMapView();
        if (mapView == null) return;

        if (alertMapLayer   != null) mapView.removeLayer(alertMapLayer);
        if (shelterMapLayer != null) mapView.removeLayer(shelterMapLayer);

        alertMapLayer   = new AlertMapLayer(context, app);
        shelterMapLayer = new ShelterMapLayer(context, app);

        mapView.addLayer(alertMapLayer,   8.5f);
        mapView.addLayer(shelterMapLayer, 8.6f);

        alertsManager.addListener(new AlertsManager.AlertsListener() {
            @Override
            public void onAlertsUpdated(List<AlertRegion> alerts) {
                alertMapLayer.setAlerts(alerts);
                boolean hasAirRaid = false;
                for (AlertRegion a : alerts) {
                    if ("air_raid".equals(a.alertType)) { hasAirRaid = true; break; }
                }
                if (!hasAirRaid) shelterMapLayer.hideShelters();
            }

            @Override
            public void onNewAlert(AlertRegion alert) {
                alertMapLayer.onNewAlert(alert);
                new android.os.Handler(android.os.Looper.getMainLooper())
                    .postDelayed(() -> searchSheltersNearUser(), 3_000);
                Toast.makeText(app, "🚨 Тривога: " + alert.locationTitle,
                    Toast.LENGTH_LONG).show();
            }
        });

        Log.i(TAG, "SafeNav шари зареєстровано");
    }

    private void searchSheltersNearUser() {
        if (shelterFinder == null || shelterMapLayer == null) return;
        net.osmand.Location loc = app.getLocationProvider().getLastKnownLocation();
        if (loc == null) return;

        double lat = loc.getLatitude();
        double lon = loc.getLongitude();

        shelterFinder.findNearby(lat, lon, 5, new ShelterFinder.ShelterCallback() {
            @Override
            public void onSheltersFound(List<Shelter> shelters, double radiusM) {
                shelterMapLayer.showShelters(shelters, new LatLon(lat, lon));
                if (!shelters.isEmpty()) {
                    Toast.makeText(app,
                        "🏠 " + shelters.get(0).name + " — " + shelters.get(0).getDistanceText(),
                        Toast.LENGTH_LONG).show();
                }
            }
            @Override public void onNoSheltersFound(double r) {
                Toast.makeText(app, "⚠ Укриттів поблизу не знайдено", Toast.LENGTH_LONG).show();
            }
            @Override public void onError(String msg) {
                Log.e(TAG, "Shelter error: " + msg);
            }
        });
    }

    @Override
    public void disable(@NonNull OsmandApplication app) {
        super.disable(app);
        if (alertsManager   != null) alertsManager.stop();
        if (shelterFinder   != null) shelterFinder.shutdown();
        if (alertMapLayer   != null) alertMapLayer.stopAnimation();
        if (shelterMapLayer != null) shelterMapLayer.hideShelters();
    }

    @Override
    public void mapActivityDestroy(@NonNull MapActivity activity) {
        OsmandMapTileView mv = activity.getMapView();
        if (alertMapLayer   != null) { mv.removeLayer(alertMapLayer);   alertMapLayer   = null; }
        if (shelterMapLayer != null) { mv.removeLayer(shelterMapLayer); shelterMapLayer = null; }
    }

    public AlertMapLayer    getAlertMapLayer()   { return alertMapLayer; }
    public ShelterMapLayer  getShelterMapLayer() { return shelterMapLayer; }
    public AlertsManager    getAlertsManager()   { return alertsManager; }
    public ShelterFinder    getShelterFinder()   { return shelterFinder; }

    @Nullable
    public static SafeNavPlugin get(@NonNull OsmandApplication app) {
        return (SafeNavPlugin) net.osmand.plus.plugins.PluginsHelper.getPlugin(PLUGIN_ID);
    }
}
