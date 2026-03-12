package com.example.upload.controller;

import com.example.upload.dto.CompleteUploadRequest;
import com.example.upload.dto.CreateSessionRequest;
import com.example.upload.dto.PresignedUrlRequest;
import com.example.upload.model.FileMetadata;
import com.example.upload.service.UploadService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@RestController
public class UploadController {

    private final UploadService uploadService;

    public UploadController(UploadService uploadService) {
        this.uploadService = uploadService;
    }

    // 기존 단일 파일 업로드
    @PostMapping("/upload")
    public ResponseEntity<?> upload(@RequestParam("file") MultipartFile file) {
        try {
            FileMetadata metadata = uploadService.upload(file);
            return ResponseEntity.ok(Map.of(
                    "id", metadata.getId(),
                    "filename", metadata.getFilename(),
                    "size", metadata.getSize(),
                    "status", metadata.getStatus()
            ));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", e.getMessage()));
        }
    }

    // Presigned URL 생성
    @PostMapping("/upload/presigned-url")
    public ResponseEntity<?> generatePresignedUrl(@RequestBody PresignedUrlRequest request) {
        try {
            return ResponseEntity.ok(uploadService.generatePresignedUrl(request));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", e.getMessage()));
        }
    }

    // Upload Session 생성
    @PostMapping("/upload/session")
    public ResponseEntity<?> createSession(@RequestBody CreateSessionRequest request) {
        try {
            return ResponseEntity.ok(uploadService.createSession(request));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", e.getMessage()));
        }
    }

    // Chunk Upload
    @PostMapping("/upload/chunk")
    public ResponseEntity<?> uploadChunk(
            @RequestParam("sessionId") String sessionId,
            @RequestParam("chunkNumber") int chunkNumber,
            @RequestParam("fileChunk") MultipartFile fileChunk) {
        try {
            return ResponseEntity.ok(uploadService.uploadChunk(sessionId, chunkNumber, fileChunk));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", e.getMessage()));
        }
    }

    // Upload Complete
    @PostMapping("/upload/complete")
    public ResponseEntity<?> completeUpload(@RequestBody CompleteUploadRequest request) {
        try {
            return ResponseEntity.ok(uploadService.completeUpload(request));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", e.getMessage()));
        }
    }

    // Session 상태 조회
    @GetMapping("/upload/session/{sessionId}")
    public ResponseEntity<?> getSessionStatus(@PathVariable String sessionId) {
        try {
            return ResponseEntity.ok(uploadService.getSessionStatus(sessionId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", e.getMessage()));
        }
    }
}
