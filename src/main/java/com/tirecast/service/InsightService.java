package com.tirecast.service;

import com.tirecast.service.FlaskClient.Detection;
import com.tirecast.service.WeatherService.Weather;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * F-ANA-03: 안전도 융합 알고리즘.
 * 타이어 결함(YOLO)과 기상 데이터를 교차 분석해 3단계 위험도와 행동 지침을 산출한다.
 */
@Service
public class InsightService {

    public record Insight(String safetyLevel, String actionGuide) {}

    /** 강수량(mm) 기준: 이 이상이면 수막현상 위험 조건으로 판단 */
    private static final double RAIN_RISK_MM = 5.0;
    /** 폭염 기준(°C): 이 이상이면 크랙/부풀음 파열 위험 조건으로 판단 */
    private static final double HEAT_RISK_C = 33.0;

    public Insight evaluate(List<Detection> detections, Weather weather) {
        boolean hasTreadWear = has(detections, "tr");
        boolean hasCut = has(detections, "cut");
        boolean hasCbu = has(detections, "CBU");
        boolean hasBeadDamage = has(detections, "bead_damage");

        boolean rainy = weather.precipitation() >= RAIN_RISK_MM
                || "HEAVY_RAIN".equals(weather.condition())
                || "THUNDERSTORM".equals(weather.condition());
        boolean hot = weather.temperature() >= HEAT_RISK_C;
        boolean icy = "SNOW".equals(weather.condition()) || "FREEZING_RAIN".equals(weather.condition());

        // 1) 구조적 손상(부풀음·비드)은 날씨와 무관하게 위험
        if (hasCbu || hasBeadDamage) {
            StringBuilder sb = new StringBuilder("타이어에 ")
                    .append(hasCbu && hasBeadDamage ? "부풀음과 비드 손상이" : hasCbu ? "부풀음(CBU)이" : "비드 손상이")
                    .append(" 탐지되었습니다. 주행 중 파열로 이어질 수 있는 구조적 결함으로, 운행을 중단하고 즉시 정비소를 방문하세요.");
            if (hot) sb.append(" 특히 현재 기온이 ").append(fmt(weather.temperature()))
                    .append("°C로 높아 내부 공기 팽창에 의한 파열 위험이 더욱 큽니다.");
            return new Insight("DANGER", sb.toString());
        }

        // 2) 마모·크랙 + 악천후 조합 → 위험 격상
        if ((hasTreadWear || hasCut) && rainy) {
            return new Insight("DANGER",
                    "트레드 마모" + (hasCut ? "와 측면 크랙이" : "가") + " 있는 상태에서 현재 "
                    + weather.description() + "(강수량 " + fmt(weather.precipitation())
                    + "mm)가 관측되고 있습니다. 수막현상으로 제동거리가 급격히 길어질 수 있으므로 "
                    + "오늘은 대중교통 이용을 권장하며, 빠른 시일 내 타이어 교체가 필요합니다.");
        }
        if ((hasTreadWear || hasCut) && icy) {
            return new Insight("DANGER",
                    "마모·크랙이 있는 타이어로 눈길/빙판길을 주행하는 것은 매우 위험합니다. "
                    + "오늘은 차량 운행을 자제하고 타이어 교체 후 운행하세요.");
        }
        if (hasCut && hot) {
            return new Insight("DANGER",
                    "측면 크랙이 있는 상태에서 현재 기온이 " + fmt(weather.temperature())
                    + "°C로 높습니다. 고온에서 크랙 부위가 급격히 벌어져 파열될 수 있으니 "
                    + "장거리·고속 주행을 피하고 정비소 점검을 받으세요.");
        }

        // 3) 마모·크랙 단독 → 경고
        if (hasTreadWear || hasCut) {
            String defect = hasTreadWear && hasCut ? "트레드 마모와 측면 크랙이"
                    : hasTreadWear ? "트레드 마모가" : "측면 크랙이";
            return new Insight("WARNING",
                    defect + " 탐지되었습니다. 당장 주행은 가능하지만 제동력이 저하된 상태이므로 "
                    + "감속 운전하시고, 가까운 시일 내 타이어 점검·교체를 권장합니다."
                    + (weather.precipitation() > 0 ? " 비가 오는 날에는 운행을 자제하세요." : ""));
        }

        // 4) 결함 미탐지 → 안전 (악천후 시 일반 주의 문구)
        if (rainy) {
            return new Insight("SAFE",
                    "타이어에서 결함이 탐지되지 않았습니다. 다만 현재 " + weather.description()
                    + "(강수량 " + fmt(weather.precipitation()) + "mm)이므로 감속 운전과 충분한 안전거리를 유지하세요.");
        }
        return new Insight("SAFE",
                "타이어 상태가 양호합니다. 현재 기상 조건(" + weather.description()
                + ", " + fmt(weather.temperature()) + "°C)에서 안전하게 주행할 수 있습니다.");
    }

    private boolean has(List<Detection> detections, String className) {
        return detections.stream().anyMatch(d -> d.className().equals(className));
    }

    private String fmt(double v) {
        return v == Math.floor(v) ? String.valueOf((long) v) : String.format("%.1f", v);
    }
}
