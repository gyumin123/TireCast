package com.tirecast.controller;

import com.tirecast.service.WeatherService;
import com.tirecast.service.WeatherService.Weather;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/** F-ANA-02: 대시보드용 실시간 기상 조회 API */
@RestController
public class WeatherController {

    private final WeatherService weatherService;

    public WeatherController(WeatherService weatherService) {
        this.weatherService = weatherService;
    }

    @GetMapping("/api/weather")
    public ResponseEntity<?> weather(@RequestParam(value = "lat", required = false) Double lat,
                                     @RequestParam(value = "lon", required = false) Double lon) {
        try {
            Weather w = weatherService.fetch(lat, lon);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("location", w.location());
            m.put("description", weatherService.describe(w.condition()));
            m.put("icon", weatherService.iconOf(w.condition()));
            m.put("precipitation", w.precipitation());
            m.put("temperature", w.temperature());
            return ResponseEntity.ok(m);
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("message", e.getMessage()));
        }
    }
}
