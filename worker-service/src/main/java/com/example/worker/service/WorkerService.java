package com.example.worker.service;

import com.example.worker.model.FileMetadata;
import com.example.worker.repository.FileMetadataRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
public class WorkerService implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(WorkerService.class);
    private static final String QUEUE_NAME = "file:process:queue";

    private final StringRedisTemplate redisTemplate;
    private final FileMetadataRepository metadataRepository;

    public WorkerService(StringRedisTemplate redisTemplate,
                         FileMetadataRepository metadataRepository) {
        this.redisTemplate = redisTemplate;
        this.metadataRepository = metadataRepository;
    }

    @Override
    public void run(String... args) {
        log.info("Worker started, listening on queue: {}", QUEUE_NAME);

        while (!Thread.currentThread().isInterrupted()) {
            try {
                // BRPOP blocks until a message is available (timeout 0 = wait forever)
                String fileId = redisTemplate.opsForList()
                        .rightPop(QUEUE_NAME, Duration.ofSeconds(5));

                if (fileId == null) {
                    continue;
                }

                processJob(fileId);
            } catch (Exception e) {
                log.error("Error processing job", e);
            }
        }
    }

    private void processJob(String fileIdStr) {
        try {
            Long fileId = Long.parseLong(fileIdStr);
            log.info("Processing file id: {}", fileId);

            FileMetadata metadata = metadataRepository.findById(fileId).orElse(null);
            if (metadata == null) {
                log.warn("File metadata not found for id: {}", fileId);
                return;
            }

            // Update status to PROCESSING
            metadata.setStatus("PROCESSING");
            metadataRepository.save(metadata);
            log.info("File {} status: PROCESSING", metadata.getFilename());

            // Simulate processing work
            Thread.sleep(3000);

            // Update status to PROCESSED
            metadata.setStatus("PROCESSED");
            metadataRepository.save(metadata);
            log.info("File {} status: PROCESSED", metadata.getFilename());

        } catch (NumberFormatException e) {
            log.error("Invalid file id: {}", fileIdStr);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
