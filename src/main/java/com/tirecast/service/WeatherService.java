package com.tirecast.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * F-ANA-02: Open-Meteo 실시간 기상 조회 + 위치명 역지오코딩.
 * 위치 권한 거부 시 기본값(서울)으로 폴백 — F-COL-02.
 */
@Service
public class WeatherService {

    private static final Logger log = LoggerFactory.getLogger(WeatherService.class);

    /** 기본 위치: 서울시청 */
    public static final double DEFAULT_LAT = 37.5665;
    public static final double DEFAULT_LON = 126.9780;

    private final RestClient rest = RestClient.create();

    public record Weather(String condition, String description, String icon,
                          double temperature, double precipitation, String location) {}

    public Weather fetch(Double lat, Double lon) {
        double latitude = lat != null ? lat : DEFAULT_LAT;
        double longitude = lon != null ? lon : DEFAULT_LON;
        boolean isFallback = (lat == null || lon == null);

        Map<String, Object> current;
        try {
            Map<?, ?> body = rest.get()
                    .uri("https://api.open-meteo.com/v1/forecast?latitude={lat}&longitude={lon}" +
                         "&current=temperature_2m,precipitation,weather_code&timezone=auto",
                         latitude, longitude)
                    .retrieve()
                    .body(Map.class);
            @SuppressWarnings("unchecked")
            Map<String, Object> cur = (Map<String, Object>) body.get("current");
            current = cur;
        } catch (Exception e) {
            log.error("Open-Meteo 호출 실패", e);
            throw new IllegalStateException("기상 정보를 가져오지 못했습니다.");
        }

        double temperature = ((Number) current.get("temperature_2m")).doubleValue();
        double precipitation = ((Number) current.get("precipitation")).doubleValue();
        int weatherCode = ((Number) current.get("weather_code")).intValue();

        String condition = mapCondition(weatherCode, precipitation);
        String location = isFallback ? "서울특별시 (기본 위치)" : reverseGeocode(latitude, longitude);

        return new Weather(condition, describe(condition), iconOf(condition),
                temperature, precipitation, location);
    }

    /** WMO weather code → 내부 날씨 상태 코드 */
    private String mapCondition(int code, double precipitation) {
        if (precipitation >= 8.0) return "HEAVY_RAIN";
        if (code == 0) return "CLEAR";
        if (code <= 2) return "PARTLY_CLOUDY";
        if (code == 3) return "CLOUDY";
        if (code == 45 || code == 48) return "FOG";
        if (code >= 51 && code <= 57) return "DRIZZLE";
        if ((code >= 61 && code <= 65) || (code >= 80 && code <= 82)) {
            return (code == 65 || code == 82) ? "HEAVY_RAIN" : "RAIN";
        }
        if (code == 66 || code == 67) return "FREEZING_RAIN";
        if ((code >= 71 && code <= 77) || code == 85 || code == 86) return "SNOW";
        if (code >= 95) return "THUNDERSTORM";
        return "CLOUDY";
    }

    public String describe(String condition) {
        return switch (condition) {
            case "CLEAR" -> "맑음";
            case "PARTLY_CLOUDY" -> "구름 조금";
            case "CLOUDY" -> "흐림";
            case "FOG" -> "안개";
            case "DRIZZLE" -> "이슬비";
            case "RAIN" -> "비";
            case "HEAVY_RAIN" -> "강한 비";
            case "FREEZING_RAIN" -> "어는 비";
            case "SNOW" -> "눈";
            case "THUNDERSTORM" -> "뇌우";
            default -> condition;
        };
    }

    public String iconOf(String condition) {
        return switch (condition) {
            case "CLEAR" -> "☀️";
            case "PARTLY_CLOUDY" -> "🌤️";
            case "CLOUDY" -> "☁️";
            case "FOG" -> "🌫️";
            case "DRIZZLE", "RAIN" -> "🌧️";
            case "HEAVY_RAIN" -> "⛈️";
            case "FREEZING_RAIN" -> "🌨️";
            case "SNOW" -> "❄️";
            case "THUNDERSTORM" -> "🌩️";
            default -> "🌡️";
        };
    }

    /** 위/경도 → 행정구역명 (BigDataCloud 무료 API, 실패 시 좌표 문자열로 폴백) */
    private String reverseGeocode(double lat, double lon) {
        try {
            Map<?, ?> body = rest.get()
                    .uri("https://api.bigdatacloud.net/data/reverse-geocode-client" +
                         "?latitude={lat}&longitude={lon}&localityLanguage=ko", lat, lon)
                    .retrieve()
                    .body(Map.class);
            String city = str(body.get("city"));
            String locality = str(body.get("locality"));
            String principal = str(body.get("principalSubdivision"));
            String name = !city.isBlank() ? city : principal;
            if (!locality.isBlank() && !locality.equals(name)) name = (name + " " + locality).trim();
            if (!name.isBlank()) return name;
        } catch (Exception e) {
            log.warn("역지오코딩 실패 — 좌표로 대체: {}", e.getMessage());
        }
        return String.format("위도 %.3f, 경도 %.3f", lat, lon);
    }

    private String str(Object o) { return o == null ? "" : o.toString(); }
}
