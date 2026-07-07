package com.tirecast.repository;

import com.tirecast.entity.DiagnosisHistory;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface DiagnosisHistoryRepository extends JpaRepository<DiagnosisHistory, Long> {

    /** 커서(keyset) 기반 이력 조회 — historyId < cursor 조건으로 최신순 페이지네이션 */
    List<DiagnosisHistory> findByUser_UserIdAndHistoryIdLessThanAndSafetyLevelInOrderByHistoryIdDesc(
            Long userId, Long cursor, Collection<String> safetyLevels, Pageable pageable);

    Optional<DiagnosisHistory> findByHistoryIdAndUser_UserId(Long historyId, Long userId);
}
