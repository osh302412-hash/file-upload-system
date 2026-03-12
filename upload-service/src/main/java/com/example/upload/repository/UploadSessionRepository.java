package com.example.upload.repository;

import com.example.upload.model.UploadSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface UploadSessionRepository extends JpaRepository<UploadSession, Long> {
    Optional<UploadSession> findBySessionId(String sessionId);

    @Modifying
    @Query("UPDATE UploadSession s SET s.uploadedChunks = s.uploadedChunks + 1, " +
            "s.status = 'UPLOADING', s.updatedAt = CURRENT_TIMESTAMP " +
            "WHERE s.sessionId = :sessionId")
    int incrementUploadedChunks(@Param("sessionId") String sessionId);
}
