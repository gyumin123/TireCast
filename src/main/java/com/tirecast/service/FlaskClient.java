package com.tirecast.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * F-ANA-01: 내부망 Flask AI 서버(POST /predict) 호출.
 * 이미지를 전달하고 결함 탐지 결과(JSON) + 주석 이미지(base64)를 받는다.
 */
@Service
public class FlaskClient {

    private final RestClient rest;

    public record Detection(String className, String label, double confidence,
                            Map<String, Double> bboxPct) {}

    public record PredictResult(List<Detection> detections, byte[] annotatedImage) {}

    public FlaskClient(@Value("${tirecast.flask-url}") String flaskUrl) {
        this.rest = RestClient.builder().baseUrl(flaskUrl).build();
    }

    public PredictResult predict(byte[] imageBytes, String filename) {
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("image", new ByteArrayResource(imageBytes) {
            @Override public String getFilename() { return filename; }
        });

        Map<?, ?> body;
        try {
            body = rest.post()
                    .uri("/predict")
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(form)
                    .retrieve()
                    .body(Map.class);
        } catch (Exception e) {
            throw new IllegalStateException("AI 분석 서버에 연결할 수 없습니다. 잠시 후 다시 시도해주세요.", e);
        }

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rawDetections = (List<Map<String, Object>>) body.get("detections");
        List<Detection> detections = rawDetections.stream().map(d -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> pct = (Map<String, Object>) d.get("bbox_pct");
            return new Detection(
                    (String) d.get("class"),
                    (String) d.get("label"),
                    ((Number) d.get("confidence")).doubleValue(),
                    Map.of(
                            "left", ((Number) pct.get("left")).doubleValue(),
                            "top", ((Number) pct.get("top")).doubleValue(),
                            "width", ((Number) pct.get("width")).doubleValue(),
                            "height", ((Number) pct.get("height")).doubleValue()
                    ));
        }).toList();

        byte[] annotated = java.util.Base64.getDecoder()
                .decode((String) body.get("annotated_image_base64"));

        return new PredictResult(detections, annotated);
    }
}
