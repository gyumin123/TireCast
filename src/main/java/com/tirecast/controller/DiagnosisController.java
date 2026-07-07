package com.tirecast.controller;

import com.tirecast.entity.DiagnosisHistory;
import com.tirecast.entity.User;
import com.tirecast.repository.DiagnosisHistoryRepository;
import com.tirecast.repository.UserRepository;
import com.tirecast.service.FlaskClient;
import com.tirecast.service.FlaskClient.Detection;
import com.tirecast.service.InsightService;
import com.tirecast.service.StorageService;
import com.tirecast.service.WeatherService;
import com.tirecast.service.WeatherService.Weather;
import jakarta.servlet.http.HttpSession;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 모듈 4·5: AI 진단 파이프라인 + 이력 관리
 * F-ANA-01~03 (분석), F-DSH-02 (저장), F-DSH-03 (이력 조회)
 */
@RestController
@RequestMapping("/api/diagnosis")
public class DiagnosisController {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final Set<String> ALL_LEVELS = Set.of("SAFE", "WARNING", "DANGER");

    private final UserRepository userRepository;
    private final DiagnosisHistoryRepository historyRepository;
    private final FlaskClient flaskClient;
    private final WeatherService weatherService;
    private final InsightService insightService;
    private final StorageService storageService;

    public DiagnosisController(UserRepository userRepository,
                               DiagnosisHistoryRepository historyRepository,
                               FlaskClient flaskClient,
                               WeatherService weatherService,
                               InsightService insightService,
                               StorageService storageService) {
        this.userRepository = userRepository;
        this.historyRepository = historyRepository;
        this.flaskClient = flaskClient;
        this.weatherService = weatherService;
        this.insightService = insightService;
        this.storageService = storageService;
    }

    /** 진단 실행: 이미지 저장 → Flask YOLO 추론 → 날씨 조회 → 융합 판단 → DB 저장 */
    @PostMapping("/analyze")
    public ResponseEntity<?> analyze(@RequestParam("image") MultipartFile image,
                                     @RequestParam(value = "latitude", required = false) Double latitude,
                                     @RequestParam(value = "longitude", required = false) Double longitude,
                                     HttpSession session) throws IOException {
        Long userId = (Long) session.getAttribute(AuthController.SESSION_USER_ID);
        if (userId == null) return unauthorized();
        if (image == null || image.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("message", "이미지 파일이 필요합니다."));
        }
        User user = userRepository.findById(userId).orElseThrow();

        byte[] originalBytes = image.getBytes();

        // F-ANA-01: YOLO 결함 탐지 (Flask)
        FlaskClient.PredictResult prediction;
        try {
            prediction = flaskClient.predict(originalBytes, image.getOriginalFilename());
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("message", e.getMessage()));
        }

        // F-ANA-02: 실시간 기상 조회 (좌표 없으면 서울 폴백)
        Weather weather;
        try {
            weather = weatherService.fetch(latitude, longitude);
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("message", e.getMessage()));
        }

        // F-ANA-03: 안전도 융합 알고리즘
        InsightService.Insight insight = insightService.evaluate(prediction.detections(), weather);

        // F-DSH-02: 이미지 파일 저장 + 이력 DB 저장
        String originalPath = storageService.save(originalBytes, "original");
        String resultPath = storageService.save(prediction.annotatedImage(), "result");

        DiagnosisHistory history = new DiagnosisHistory(
                user, originalPath, resultPath,
                summarizeTireStatus(prediction.detections()),
                weather.condition(), weather.location(),
                weather.temperature(), weather.precipitation(),
                insight.safetyLevel(), insight.actionGuide());
        historyRepository.save(history);

        // 대시보드 렌더링용 응답
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("historyId", history.getHistoryId());
        resp.put("status", toClientLevel(insight.safetyLevel()));
        resp.put("guide", insight.actionGuide());
        resp.put("defects", toDefectList(prediction.detections()));
        resp.put("bboxes", toBboxList(prediction.detections()));
        resp.put("resultImageUrl", resultPath);
        resp.put("weather", weatherJson(weather));
        return ResponseEntity.ok(resp);
    }

    /** F-DSH-03: 커서 기반 이력 조회 — 응답 { items, nextCursor } */
    @GetMapping("/history")
    public ResponseEntity<?> history(@RequestParam(value = "cursor", required = false) Long cursor,
                                     @RequestParam(value = "limit", defaultValue = "20") int limit,
                                     @RequestParam(value = "status", required = false) List<String> status,
                                     HttpSession session) {
        Long userId = (Long) session.getAttribute(AuthController.SESSION_USER_ID);
        if (userId == null) return unauthorized();

        Set<String> levels = toDbLevels(status);
        int size = Math.clamp(limit, 1, 100);
        List<DiagnosisHistory> rows = historyRepository
                .findByUser_UserIdAndHistoryIdLessThanAndSafetyLevelInOrderByHistoryIdDesc(
                        userId, cursor != null ? cursor : Long.MAX_VALUE, levels,
                        PageRequest.of(0, size + 1)); // +1건으로 다음 페이지 유무 판단

        boolean hasNext = rows.size() > size;
        List<DiagnosisHistory> page = hasNext ? rows.subList(0, size) : rows;

        List<Map<String, Object>> items = page.stream().map(h -> {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("id", h.getHistoryId());
            m.put("date", h.getCreatedAt().format(DATE_FMT));
            m.put("summary", summaryText(h));
            m.put("status", toClientLevel(h.getSafetyLevel()));
            return m;
        }).toList();

        Map<String, Object> resp = new HashMap<>();
        resp.put("items", items);
        resp.put("nextCursor", hasNext ? page.get(page.size() - 1).getHistoryId() : null);
        return ResponseEntity.ok(resp);
    }

    /** 이력 상세 (모달) — 당시 이미지·결함·날씨·위치명·가이드 */
    @GetMapping("/history/{id}")
    public ResponseEntity<?> historyDetail(@PathVariable Long id, HttpSession session) {
        Long userId = (Long) session.getAttribute(AuthController.SESSION_USER_ID);
        if (userId == null) return unauthorized();

        return historyRepository.findByHistoryIdAndUser_UserId(id, userId)
                .<ResponseEntity<?>>map(h -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("imageUrl", h.getResultImagePath());
                    m.put("defects", parseTireStatus(h.getTireStatus()));
                    m.put("bboxes", List.of()); // BBox는 결과 이미지에 이미 그려져 있음
                    m.put("guide", h.getActionGuide());
                    Map<String, Object> w = new LinkedHashMap<>();
                    w.put("location", h.getLocationName());
                    w.put("description", weatherService.describe(h.getWeatherCondition()));
                    w.put("icon", weatherService.iconOf(h.getWeatherCondition()));
                    w.put("precipitation", h.getPrecipitation());
                    w.put("temperature", h.getTemperature());
                    m.put("weather", w);
                    return ResponseEntity.ok(m);
                })
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("message", "진단 이력을 찾을 수 없습니다.")));
    }

    /* ---------- 변환 헬퍼 ---------- */

    private ResponseEntity<Map<String, String>> unauthorized() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("message", "로그인이 필요합니다."));
    }

    /** DB safety_level(SAFE/WARNING/DANGER) → 프론트 상태값(safe/warn/danger) */
    private String toClientLevel(String dbLevel) {
        return switch (dbLevel) {
            case "WARNING" -> "warn";
            case "DANGER" -> "danger";
            default -> "safe";
        };
    }

    /** 프론트 필터 파라미터(safe/warning/danger) → DB 값 */
    private Set<String> toDbLevels(List<String> status) {
        if (status == null || status.isEmpty()) return ALL_LEVELS;
        Set<String> levels = new java.util.HashSet<>();
        for (String s : status) {
            switch (s.toLowerCase()) {
                case "safe" -> levels.add("SAFE");
                case "warning", "warn" -> levels.add("WARNING");
                case "danger" -> levels.add("DANGER");
            }
        }
        return levels.isEmpty() ? ALL_LEVELS : levels;
    }

    /** 탐지 결과 → tire_status 컬럼 요약 문자열 (예: "tr 0.87, cut 0.64" / "NORMAL") — VARCHAR(50) */
    private String summarizeTireStatus(List<Detection> detections) {
        if (detections.isEmpty()) return "NORMAL";
        StringBuilder sb = new StringBuilder();
        for (Detection d : detections) {
            String part = (sb.isEmpty() ? "" : ", ") + d.className() + " " + String.format("%.2f", d.confidence());
            if (sb.length() + part.length() > 50) break;
            sb.append(part);
        }
        return sb.toString();
    }

    /** tire_status 문자열 → 모달 결함 배지 목록 복원 */
    private List<Map<String, Object>> parseTireStatus(String tireStatus) {
        if (tireStatus == null || tireStatus.equals("NORMAL")) return List.of();
        Map<String, String> labels = Map.of(
                "CBU", "부풀음 (CBU)",
                "bead_damage", "비드 손상 (bead_damage)",
                "cut", "측면 크랙 (cut)",
                "tr", "트레드 마모 (tr)");
        List<Map<String, Object>> out = new ArrayList<>();
        for (String part : tireStatus.split(",")) {
            String[] kv = part.trim().split("\\s+");
            if (kv.length < 2) continue;
            try {
                out.add(Map.of("label", labels.getOrDefault(kv[0], kv[0]),
                        "confidence", Double.parseDouble(kv[1])));
            } catch (NumberFormatException ignored) {}
        }
        return out;
    }

    /** 이력 리스트 요약 문구 생성 */
    private String summaryText(DiagnosisHistory h) {
        String levelKo = switch (h.getSafetyLevel()) {
            case "WARNING" -> "경고";
            case "DANGER" -> "위험";
            default -> "안전";
        };
        if ("NORMAL".equals(h.getTireStatus())) return levelKo + " — 결함 미탐지";
        List<String> names = new ArrayList<>();
        Map<String, String> shortNames = Map.of(
                "CBU", "부풀음", "bead_damage", "비드 손상", "cut", "측면 크랙", "tr", "트레드 마모");
        for (String part : h.getTireStatus().split(",")) {
            String cls = part.trim().split("\\s+")[0];
            String name = shortNames.get(cls);
            if (name != null && !names.contains(name)) names.add(name);
        }
        return levelKo + " — " + (names.isEmpty() ? "결함 탐지" : String.join("·", names) + " 탐지");
    }

    private Map<String, Object> weatherJson(Weather w) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("location", w.location());
        m.put("description", weatherService.describe(w.condition()));
        m.put("icon", weatherService.iconOf(w.condition()));
        m.put("precipitation", w.precipitation());
        m.put("temperature", w.temperature());
        return m;
    }

    private List<Map<String, Object>> toDefectList(List<Detection> detections) {
        return detections.stream().<Map<String, Object>>map(d ->
                Map.of("label", d.label(), "confidence", d.confidence())).toList();
    }

    private List<Map<String, Object>> toBboxList(List<Detection> detections) {
        return detections.stream().<Map<String, Object>>map(d -> {
            Map<String, Object> m = new LinkedHashMap<>(d.bboxPct());
            m.put("label", d.className() + " " + String.format("%.2f", d.confidence()));
            return m;
        }).toList();
    }
}
