package net.osmand.plus.plugins.safenav.shelter;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import net.osmand.ResultMatcher;
import net.osmand.data.Amenity;
import net.osmand.data.LatLon;
import net.osmand.osm.AbstractPoiType;
import net.osmand.osm.MapPoiTypes;
import net.osmand.osm.PoiCategory;
import net.osmand.plus.OsmandApplication;
import net.osmand.plus.poi.PoiUIFilter;
import net.osmand.util.MapUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * SafeNav — Пошук найближчих укриттів
 *
 * OSM теги для укриттів:
 *   amenity=shelter
 *   shelter_type=public_transport / weather / basic_hut
 *   emergency=assembly_point
 *   building=basement  (підвал — специфічно для України)
 *   civil_defence=shelter (додається волонтерами з 2022)
 *
 * Радіус пошуку: 500м → 1км → 2км (розширюємо якщо нічого немає)
 */
public class ShelterFinder {

    private static final String TAG = "SafeNav.Shelter";

    // Радіуси пошуку в метрах
    private static final double[] SEARCH_RADII = {500, 1000, 2000, 5000};

    // OSM теги що вважаємо укриттями (пріоритет зверху вниз)
    private static final String[][] SHELTER_TAGS = {
        {"civil_defence",  "shelter"},        // найкраще — офіційне укриття
        {"amenity",        "shelter"},         // OSM стандарт
        {"emergency",      "assembly_point"}, // місце збору
        {"amenity",        "subway_entrance"}, // метро — відмінне укриття
        {"railway",        "subway_entrance"},
        {"amenity",        "parking_entrance"}, // підземний паркінг
        {"building",       "basement"},        // підвал
        {"amenity",        "community_centre"}, // громадський центр
    };

    // Оцінка якості укриття (вище = краще)
    private static final Map<String, Integer> QUALITY_SCORE = new HashMap<String, Integer>() {{
        put("civil_defence:shelter",    100);
        put("amenity:shelter",           80);
        put("railway:subway_entrance",   90);
        put("amenity:subway_entrance",   90);
        put("amenity:parking_entrance",  70);
        put("building:basement",         60);
        put("emergency:assembly_point",  50);
        put("amenity:community_centre",  40);
    }};

    public static class Shelter {
        public final Amenity amenity;
        public final double  distanceM;
        public final int     qualityScore;
        public final String  shelterType;
        public final String  name;
        public final LatLon  location;

        public Shelter(Amenity amenity, double distanceM, int quality, String type) {
            this.amenity      = amenity;
            this.distanceM    = distanceM;
            this.qualityScore = quality;
            this.shelterType  = type;
            this.location     = amenity.getLocation();

            // Визначаємо назву
            String rawName = amenity.getName();
            if (rawName != null && !rawName.isEmpty()) {
                this.name = rawName;
            } else {
                this.name = getDefaultName(type);
            }
        }

        private String getDefaultName(String type) {
            switch (type) {
                case "civil_defence:shelter":  return "Укриття цивільної оборони";
                case "amenity:shelter":         return "Укриття";
                case "railway:subway_entrance":
                case "amenity:subway_entrance": return "Станція метро (укриття)";
                case "amenity:parking_entrance":return "Підземний паркінг";
                case "building:basement":       return "Підвал";
                case "emergency:assembly_point":return "Місце збору";
                case "amenity:community_centre":return "Громадський центр";
                default:                        return "Укриття";
            }
        }

        /** Текстова відстань: "250 м" або "1.2 км" */
        public String getDistanceText() {
            if (distanceM < 1000) {
                return Math.round(distanceM / 10.0) * 10 + " м";
            } else {
                return String.format("%.1f км", distanceM / 1000.0);
            }
        }

        /** Іконка для типу укриття */
        public String getIcon() {
            switch (shelterType) {
                case "civil_defence:shelter":   return "🛡";
                case "railway:subway_entrance":
                case "amenity:subway_entrance": return "🚇";
                case "amenity:parking_entrance":return "🅿";
                case "building:basement":       return "🏚";
                case "emergency:assembly_point":return "⚠";
                default:                        return "🏠";
            }
        }
    }

    public interface ShelterCallback {
        void onSheltersFound(List<Shelter> shelters, double searchRadiusM);
        void onNoSheltersFound(double maxRadiusSearched);
        void onError(String message);
    }

    private final OsmandApplication app;
    private final ExecutorService   executor = Executors.newSingleThreadExecutor();

    public ShelterFinder(@NonNull OsmandApplication app) {
        this.app = app;
    }

    /**
     * Головний метод: шукаємо укриття від поточної позиції
     * Автоматично розширює радіус якщо нічого не знайдено
     */
    public void findNearby(double lat, double lon, int maxResults,
                           @NonNull ShelterCallback callback) {
        executor.execute(() -> {
            try {
                for (double radiusM : SEARCH_RADII) {
                    Log.d(TAG, String.format("Шукаємо укриття в радіусі %.0f м від %.4f,%.4f",
                        radiusM, lat, lon));

                    List<Shelter> found = searchInRadius(lat, lon, radiusM, maxResults);

                    if (!found.isEmpty()) {
                        // Сортуємо: спочатку по якості, потім по відстані
                        Collections.sort(found, (a, b) -> {
                            int scoreDiff = b.qualityScore - a.qualityScore;
                            if (scoreDiff != 0) return scoreDiff;
                            return Double.compare(a.distanceM, b.distanceM);
                        });

                        List<Shelter> top = found.subList(0, Math.min(maxResults, found.size()));
                        double finalRadius = radiusM;
                        new android.os.Handler(android.os.Looper.getMainLooper())
                            .post(() -> callback.onSheltersFound(top, finalRadius));
                        return;
                    }

                    Log.d(TAG, "Нічого не знайдено, збільшуємо радіус...");
                }

                // Нічого не знайшли в жодному радіусі
                new android.os.Handler(android.os.Looper.getMainLooper())
                    .post(() -> callback.onNoSheltersFound(SEARCH_RADII[SEARCH_RADII.length - 1]));

            } catch (Exception e) {
                Log.e(TAG, "Помилка пошуку: " + e.getMessage());
                new android.os.Handler(android.os.Looper.getMainLooper())
                    .post(() -> callback.onError(e.getMessage()));
            }
        });
    }

    @WorkerThread
    private List<Shelter> searchInRadius(double lat, double lon,
                                          double radiusM, int maxResults) {
        List<Shelter> result = new ArrayList<>();

        // Перетворюємо радіус в градуси (приблизно)
        double degDelta = radiusM / 111_320.0;
        double top    = lat + degDelta;
        double bottom = lat - degDelta;
        double left   = lon - degDelta / Math.cos(Math.toRadians(lat));
        double right  = lon + degDelta / Math.cos(Math.toRadians(lat));

        for (String[] tag : SHELTER_TAGS) {
            String key   = tag[0];
            String value = tag[1];

            try {
                List<Amenity> amenities = searchByTag(key, value, top, left, bottom, right);

                for (Amenity amenity : amenities) {
                    LatLon loc = amenity.getLocation();
                    if (loc == null) continue;

                    double dist = MapUtils.getDistance(lat, lon, loc.getLatitude(), loc.getLongitude());
                    if (dist > radiusM) continue;

                    String typeKey = key + ":" + value;
                    int score = QUALITY_SCORE.getOrDefault(typeKey, 30);

                    // Додаткові бали якщо є конкретна назва
                    String name = amenity.getName();
                    if (name != null && !name.isEmpty()) score += 10;

                    // Уникаємо дублікатів (одне місце може мати кілька тегів)
                    boolean duplicate = false;
                    for (Shelter existing : result) {
                        if (MapUtils.getDistance(
                            existing.location.getLatitude(), existing.location.getLongitude(),
                            loc.getLatitude(), loc.getLongitude()) < 30) {
                            duplicate = true;
                            break;
                        }
                    }

                    if (!duplicate) {
                        result.add(new Shelter(amenity, dist, score, typeKey));
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "Помилка пошуку тегу " + key + "=" + value + ": " + e.getMessage());
            }
        }

        return result;
    }

    @WorkerThread
    private List<Amenity> searchByTag(String key, String value,
                                       double top, double left,
                                       double bottom, double right) {
        // TODO: реалізувати через реальний POI search API OsmAnd
        // Повертаємо порожній список — пошук укриттів в наступній версії
        return new ArrayList<>();
    }

    public void shutdown() {
        executor.shutdown();
    }
}
