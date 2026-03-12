package com.example.upload.service;

import com.example.upload.dto.CompleteUploadRequest;
import com.example.upload.dto.CreateSessionRequest;
import com.example.upload.dto.PresignedUrlRequest;
import com.example.upload.model.FileMetadata;
import com.example.upload.model.UploadSession;
import com.example.upload.repository.FileMetadataRepository;
import com.example.upload.repository.UploadSessionRepository;
import io.minio.*;
import io.minio.http.Method;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Service
public class UploadService {

    private static final Logger log = LoggerFactory.getLogger(UploadService.class);
    private static final String QUEUE_NAME = "file:process:queue";
    private static final int DEFAULT_CHUNK_SIZE = 5 * 1024 * 1024; // 5MB

    private final MinioClient minioClient;
    private final FileMetadataRepository metadataRepository;
    private final UploadSessionRepository sessionRepository;
    private final StringRedisTemplate redisTemplate;

    @Value("${minio.bucket}")
    private String bucket;

    public UploadService(MinioClient minioClient,
                         FileMetadataRepository metadataRepository,
                         UploadSessionRepository sessionRepository,
                         StringRedisTemplate redisTemplate) {
        this.minioClient = minioClient;
        this.metadataRepository = metadataRepository;
        this.sessionRepository = sessionRepository;
        this.redisTemplate = redisTemplate;
    }

    // ── 기존 단일 파일 업로드 ──

    public FileMetadata upload(MultipartFile file) throws Exception {
        String filename = file.getOriginalFilename();

        minioClient.putObject(PutObjectArgs.builder()
                .bucket(bucket)
                .object(filename)
                .stream(file.getInputStream(), file.getSize(), -1)
                .contentType(file.getContentType())
                .build());

        FileMetadata metadata = new FileMetadata(filename, file.getSize());
        metadata = metadataRepository.save(metadata);

        redisTemplate.opsForList().leftPush(QUEUE_NAME, String.valueOf(metadata.getId()));

        return metadata;
    }

    // ── Presigned URL 생성 ──

    public Map<String, Object> generatePresignedUrl(PresignedUrlRequest request) throws Exception {
        String objectKey = UUID.randomUUID() + "/" + request.getFilename();

        String url = minioClient.getPresignedObjectUrl(
                GetPresignedObjectUrlArgs.builder()
                        .method(Method.PUT)
                        .bucket(bucket)
                        .object(objectKey)
                        .expiry(60, TimeUnit.MINUTES)
                        .build());

        return Map.of(
                "url", url,
                "objectKey", objectKey,
                "expiresIn", 3600
        );
    }

    // ── Upload Session 생성 ──

    public Map<String, Object> createSession(CreateSessionRequest request) throws Exception {
        String sessionId = UUID.randomUUID().toString();
        int chunkSize = request.getChunkSize() != null ? request.getChunkSize() : DEFAULT_CHUNK_SIZE;
        int totalChunks = (int) Math.ceil((double) request.getTotalSize() / chunkSize);
        String objectKey = UUID.randomUUID() + "/" + request.getFilename();

        // uploadId로 sessionId를 재사용 (chunk object 경로의 prefix로 사용)
        UploadSession session = new UploadSession(
                sessionId, request.getFilename(), request.getTotalSize(),
                chunkSize, totalChunks, sessionId, objectKey);
        sessionRepository.save(session);

        log.info("Upload session created: sessionId={}, totalChunks={}",
                sessionId, totalChunks);

        return Map.of(
                "sessionId", sessionId,
                "chunkSize", chunkSize,
                "totalChunks", totalChunks,
                "uploadId", sessionId
        );
    }

    // ── Chunk Upload ──

    @Transactional
    public Map<String, Object> uploadChunk(String sessionId, int chunkNumber,
                                           MultipartFile fileChunk) throws Exception {
        UploadSession session = sessionRepository.findBySessionId(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Session not found: " + sessionId));

        if (!"INITIATED".equals(session.getStatus()) && !"UPLOADING".equals(session.getStatus())) {
            throw new IllegalStateException("Session is not in uploadable state: " + session.getStatus());
        }

        // 각 chunk를 개별 object로 저장
        String chunkObjectKey = getChunkObjectKey(session.getObjectKey(), chunkNumber);
        minioClient.putObject(PutObjectArgs.builder()
                .bucket(bucket)
                .object(chunkObjectKey)
                .stream(fileChunk.getInputStream(), fileChunk.getSize(), -1)
                .build());

        // Atomic increment (race condition 방지)
        sessionRepository.incrementUploadedChunks(sessionId);

        log.info("Chunk uploaded: sessionId={}, chunk={}/{}", sessionId, chunkNumber + 1, session.getTotalChunks());

        return Map.of(
                "sessionId", sessionId,
                "chunkNumber", chunkNumber,
                "totalChunks", session.getTotalChunks()
        );
    }

    // ── Upload Complete ──

    public Map<String, Object> completeUpload(CompleteUploadRequest request) throws Exception {
        UploadSession session = sessionRepository.findBySessionId(request.getSessionId())
                .orElseThrow(() -> new IllegalArgumentException("Session not found: " + request.getSessionId()));

        if (session.getUploadedChunks() < session.getTotalChunks()) {
            throw new IllegalStateException(
                    String.format("Not all chunks uploaded: %d/%d",
                            session.getUploadedChunks(), session.getTotalChunks()));
        }

        // composeObject로 chunk들을 하나의 object로 병합
        List<ComposeSource> sources = new ArrayList<>();
        for (int i = 0; i < session.getTotalChunks(); i++) {
            sources.add(ComposeSource.builder()
                    .bucket(bucket)
                    .object(getChunkObjectKey(session.getObjectKey(), i))
                    .build());
        }

        minioClient.composeObject(ComposeObjectArgs.builder()
                .bucket(bucket)
                .object(session.getObjectKey())
                .sources(sources)
                .build());

        // chunk object 삭제
        for (int i = 0; i < session.getTotalChunks(); i++) {
            minioClient.removeObject(RemoveObjectArgs.builder()
                    .bucket(bucket)
                    .object(getChunkObjectKey(session.getObjectKey(), i))
                    .build());
        }

        // 세션 상태 완료
        session.setStatus("COMPLETED");
        session.setUpdatedAt(LocalDateTime.now());
        sessionRepository.save(session);

        // 메타데이터 저장
        FileMetadata metadata = new FileMetadata(session.getFilename(), session.getTotalSize());
        metadata = metadataRepository.save(metadata);

        // Queue push
        redisTemplate.opsForList().leftPush(QUEUE_NAME, String.valueOf(metadata.getId()));

        log.info("Upload completed: sessionId={}, fileId={}", session.getSessionId(), metadata.getId());

        return Map.of(
                "id", metadata.getId(),
                "filename", metadata.getFilename(),
                "size", metadata.getSize(),
                "status", metadata.getStatus(),
                "sessionId", session.getSessionId()
        );
    }

    // ── Session 상태 조회 ──

    public Map<String, Object> getSessionStatus(String sessionId) {
        UploadSession session = sessionRepository.findBySessionId(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Session not found: " + sessionId));

        return Map.of(
                "sessionId", session.getSessionId(),
                "filename", session.getFilename(),
                "totalChunks", session.getTotalChunks(),
                "uploadedChunks", session.getUploadedChunks(),
                "status", session.getStatus()
        );
    }

    // ── Helper ──

    private String getChunkObjectKey(String objectKey, int chunkNumber) {
        return objectKey + ".chunk." + String.format("%05d", chunkNumber);
    }
}
