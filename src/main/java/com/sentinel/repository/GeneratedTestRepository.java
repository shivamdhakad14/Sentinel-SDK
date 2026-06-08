package com.sentinel.repository;

import com.sentinel.model.GeneratedTest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface GeneratedTestRepository extends JpaRepository<GeneratedTest, Long> {
    List<GeneratedTest> findByScanSessionIdOrderByGeneratedAtDesc(Long sessionId);

    List<GeneratedTest> findByTestType(GeneratedTest.TestType testType);

    List<GeneratedTest> findBySelfHealingAppliedTrue();

    Optional<GeneratedTest> findByScanSessionIdAndTargetEndpointAndTestType(
            Long sessionId, String endpoint, GeneratedTest.TestType type);

    @Query("SELECT COUNT(t) FROM GeneratedTest t WHERE t.generatedAt >= :since")
    long countRecentlyGenerated(@Param("since") LocalDateTime since);
}
