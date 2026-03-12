package com.example.upload.controller;

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
}
