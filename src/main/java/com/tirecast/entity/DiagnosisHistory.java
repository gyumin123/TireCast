package com.tirecast.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/** DIAGNOSIS_HISTORY 테이블 — ERD 명세 기준 (USERS 1:N) */
@Entity
@Table(name = "diagnosis_history")
public class DiagnosisHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "history_id")
    private Long historyId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id")
    private User user;

    @Column(name = "original_image_path", length = 255, nullable = false)
    private String originalImagePath;

    @Column(name = "result_image_path", length = 255, nullable = false)
    private String resultImagePath;

    /** AI 판별 상태 — 탐지 결함을 "클래스 신뢰도" 쌍으로 요약 (예: "tr 0.87, cut 0.64" / 미탐지 시 "NORMAL") */
    @Column(name = "tire_status", length = 50, nullable = false)
    private String tireStatus;

    /** Open-Meteo 날씨 상태 코드 (예: CLEAR, HEAVY_RAIN) */
    @Column(name = "weather_condition", length = 50, nullable = false)
    private String weatherCondition;

    /** 진단 당시 위치명 (역지오코딩 결과, 예: "대구광역시 달서구") — ERD 확장 컬럼 */
    @Column(name = "location_name", length = 100)
    private String locationName;

    @Column(name = "temperature")
    private Double temperature;

    @Column(name = "precipitation")
    private Double precipitation;

    /** 융합 알고리즘 결과 등급: SAFE / WARNING / DANGER */
    @Column(name = "safety_level", length = 20, nullable = false)
    private String safetyLevel;

    @Column(name = "action_guide", columnDefinition = "TEXT", nullable = false)
    private String actionGuide;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    protected DiagnosisHistory() {}

    public DiagnosisHistory(User user, String originalImagePath, String resultImagePath,
                            String tireStatus, String weatherCondition, String locationName,
                            Double temperature, Double precipitation,
                            String safetyLevel, String actionGuide) {
        this.user = user;
        this.originalImagePath = originalImagePath;
        this.resultImagePath = resultImagePath;
        this.tireStatus = tireStatus;
        this.weatherCondition = weatherCondition;
        this.locationName = locationName;
        this.temperature = temperature;
        this.precipitation = precipitation;
        this.safetyLevel = safetyLevel;
        this.actionGuide = actionGuide;
    }

    public Long getHistoryId() { return historyId; }
    public User getUser() { return user; }
    public String getOriginalImagePath() { return originalImagePath; }
    public String getResultImagePath() { return resultImagePath; }
    public String getTireStatus() { return tireStatus; }
    public String getWeatherCondition() { return weatherCondition; }
    public String getLocationName() { return locationName; }
    public Double getTemperature() { return temperature; }
    public Double getPrecipitation() { return precipitation; }
    public String getSafetyLevel() { return safetyLevel; }
    public String getActionGuide() { return actionGuide; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
