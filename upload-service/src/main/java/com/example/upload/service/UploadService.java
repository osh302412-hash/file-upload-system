package com.example.upload.service;

import com.example.upload.model.FileMetadata;
import com.example.upload.repository.FileMetadataRepository;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class UploadService {

    private static final String QUEUE_NAME = "file:process:queue";

    private final MinioClient minioClient;
    private final FileMetadataRepository metadataRepository;
    private final StringRedisTemplate redisTemplate;

    @Value("${minio.bucket}")
    private String bucket;

    public UploadService(MinioClient minioClient,
                         FileMetadataRepository metadataRepository,
                         StringRedisTemplate redisTemplate) {
        this.minioClient = minioClient;
        this.metadataRepository = metadataRepository;
        this.redisTemplate = redisTemplate;
    }

    public FileMetadata upload(MultipartFile file) throws Exception {
        String filename = file.getOriginalFilename();

        // 1. Store file in MinIO
        minioClient.putObject(PutObjectArgs.builder()
                .bucket(bucket)
                .object(filename)
                .stream(file.getInputStream(), file.getSize(), -1)
                .contentType(file.getContentType())
                .build());

        // 2. Save metadata to PostgreSQL
        FileMetadata metadata = new FileMetadata(filename, file.getSize());
        metadata = metadataRepository.save(metadata);

        // 3. Push job to Redis queue
        redisTemplate.opsForList().leftPush(QUEUE_NAME, String.valueOf(metadata.getId()));

        return metadata;
    }
}
