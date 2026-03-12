# File Upload System

대용량 파일 업로드 아키텍처를 이해하기 위한 간단한 분산 시스템
예제입니다.

이 프로젝트는 파일 업로드 처리와 파일 후처리를 분리한 비동기 구조를
구현하며\
객체 저장소, 메시지 큐, 워커 기반 처리 모델을 사용합니다.

------------------------------------------------------------------------

# Design Goals

이 프로젝트는 다음과 같은 분산 시스템 패턴을 이해하기 위해
작성되었습니다.

-   파일 업로드와 파일 처리 로직의 분리
-   Object Storage 기반 파일 저장 구조
-   Message Queue 기반 비동기 처리
-   Worker 기반 백그라운드 작업 처리
-   서비스 간 느슨한 결합(Decoupling)
-   Presigned URL 기반 Direct Upload
-   Multipart / Chunk Upload (병렬 업로드 지원)
-   Upload Session 관리

------------------------------------------------------------------------

# System Architecture

    Client (HTTP / curl)
          │
          ▼
    Upload Service (Spring Boot REST API, :8080)
          │
          ├──▶ MinIO (Object Storage, :9000)
          ├──▶ PostgreSQL (Metadata DB, :5432)
          └──▶ Redis (Message Queue, :6379)
                   │
                   ▼
            Worker Service
          (Background Processing)

이 시스템은 업로드 요청을 처리하는 **Upload Service**와\
파일 후처리를 수행하는 **Worker Service**를 분리한 구조입니다.

업로드된 파일은 Object Storage에 저장되고\
파일 처리 작업은 Message Queue를 통해 비동기적으로 수행됩니다.

------------------------------------------------------------------------

# Service Components

  Service          Technology    Description
  ---------------- ------------- ----------------------
  upload-service   Spring Boot   파일 업로드 API
  worker-service   Spring Boot   백그라운드 작업 처리
  postgres         PostgreSQL    파일 메타데이터 저장
  redis            Redis         서비스 간 메시지 큐
  minio            MinIO         S3 호환 객체 저장소

------------------------------------------------------------------------

# Upload Methods

이 시스템은 3가지 업로드 방식을 지원합니다.

## 1. Simple Upload (단일 파일 업로드)

기존 방식. 파일 전체를 한 번에 업로드합니다.

## 2. Presigned URL Upload (Direct Upload)

Backend가 MinIO Presigned URL을 생성하고, Client가 해당 URL로
Object Storage에 직접 업로드합니다.
서버 부하 없이 대용량 파일을 처리할 수 있습니다.

## 3. Chunk Upload (Multipart Upload)

파일을 여러 chunk로 분할하여 업로드합니다.
S3 Multipart Upload 방식과 동일하게 동작하며,
각 chunk를 병렬로 업로드할 수 있습니다.

------------------------------------------------------------------------

# Upload Flows

## Simple Upload Flow

    Client ──▶ POST /upload (file) ──▶ MinIO 저장 ──▶ DB 저장 ──▶ Queue push

## Presigned URL Flow

    Client ──▶ POST /upload/presigned-url ──▶ Presigned URL 수신
    Client ──▶ PUT {presigned-url} (file) ──▶ MinIO 직접 저장

## Chunk Upload Flow

    Client ──▶ POST /upload/session         (세션 생성)
    Client ──▶ POST /upload/chunk (×N, 병렬) (청크 업로드)
    Client ──▶ POST /upload/complete         (업로드 완료)
           ──▶ Multipart merge ──▶ DB 저장 ──▶ Queue push

------------------------------------------------------------------------

# API Reference

## POST /upload

단일 파일 업로드 (기존 방식)

``` bash
curl -F "file=@testfile.txt" http://localhost:8080/upload
```

Response

``` json
{
  "id": 1,
  "filename": "testfile.txt",
  "size": 1234,
  "status": "UPLOADED"
}
```

------------------------------------------------------------------------

## POST /upload/presigned-url

Presigned URL 생성

``` bash
curl -X POST http://localhost:8080/upload/presigned-url \
  -H "Content-Type: application/json" \
  -d '{"filename": "largefile.zip", "contentType": "application/zip"}'
```

Response

``` json
{
  "url": "http://minio:9000/file-uploads/...",
  "objectKey": "uuid/largefile.zip",
  "expiresIn": 3600
}
```

이후 클라이언트가 URL로 직접 업로드:

``` bash
curl -X PUT -T largefile.zip "{presigned-url}"
```

------------------------------------------------------------------------

## POST /upload/session

Upload Session 생성 (Chunk Upload 시작)

``` bash
curl -X POST http://localhost:8080/upload/session \
  -H "Content-Type: application/json" \
  -d '{"filename": "largefile.zip", "totalSize": 104857600, "chunkSize": 5242880}'
```

Response

``` json
{
  "sessionId": "uuid",
  "chunkSize": 5242880,
  "totalChunks": 20,
  "uploadId": "minio-upload-id"
}
```

------------------------------------------------------------------------

## POST /upload/chunk

Chunk 업로드 (병렬 전송 가능)

``` bash
curl -X POST http://localhost:8080/upload/chunk \
  -F "sessionId=uuid" \
  -F "chunkNumber=0" \
  -F "fileChunk=@chunk_0.bin"
```

Response

``` json
{
  "sessionId": "uuid",
  "chunkNumber": 0,
  "uploadedChunks": 1,
  "totalChunks": 20
}
```

------------------------------------------------------------------------

## POST /upload/complete

업로드 완료 (Multipart merge + 메타데이터 저장 + Queue push)

``` bash
curl -X POST http://localhost:8080/upload/complete \
  -H "Content-Type: application/json" \
  -d '{"sessionId": "uuid"}'
```

Response

``` json
{
  "id": 2,
  "filename": "largefile.zip",
  "size": 104857600,
  "status": "UPLOADED",
  "sessionId": "uuid"
}
```

------------------------------------------------------------------------

## GET /upload/session/{sessionId}

Upload Session 상태 조회

``` bash
curl http://localhost:8080/upload/session/{sessionId}
```

Response

``` json
{
  "sessionId": "uuid",
  "filename": "largefile.zip",
  "totalChunks": 20,
  "uploadedChunks": 15,
  "status": "UPLOADING"
}
```

------------------------------------------------------------------------

# Queue Processing

Redis 리스트 `file:process:queue`가 작업 큐 역할 수행

Upload Service

    LPUSH file:process:queue <file_id>

Worker Service

    BRPOP file:process:queue

Worker 동작

1.  파일 메타데이터 조회
2.  상태 `PROCESSING` 업데이트
3.  파일 처리 시뮬레이션
4.  상태 `PROCESSED` 업데이트

------------------------------------------------------------------------

# Status Flow

## File Status

    UPLOADED ──▶ PROCESSING ──▶ PROCESSED

## Upload Session Status

    INITIATED ──▶ UPLOADING ──▶ COMPLETED

------------------------------------------------------------------------

# Database Schema

``` sql
CREATE TABLE file_metadata (
    id BIGSERIAL PRIMARY KEY,
    filename VARCHAR(255) NOT NULL,
    size BIGINT NOT NULL,
    status VARCHAR(50) NOT NULL DEFAULT 'UPLOADED',
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE upload_session (
    id BIGSERIAL PRIMARY KEY,
    session_id VARCHAR(64) NOT NULL UNIQUE,
    filename VARCHAR(255) NOT NULL,
    total_size BIGINT NOT NULL,
    chunk_size INT NOT NULL,
    total_chunks INT NOT NULL,
    uploaded_chunks INT NOT NULL DEFAULT 0,
    upload_id VARCHAR(255) NOT NULL,
    object_key VARCHAR(512) NOT NULL,
    status VARCHAR(50) NOT NULL DEFAULT 'INITIATED',
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);
```

------------------------------------------------------------------------

# Project Structure

    file-upload-system/
    ├── docker-compose.yml
    ├── init-db/
    │   └── init.sql
    ├── upload-service/
    │   ├── Dockerfile
    │   ├── pom.xml
    │   └── src/main/java/com/example/upload/
    │       ├── UploadServiceApplication.java
    │       ├── config/
    │       │   └── MinioConfig.java
    │       ├── controller/
    │       │   └── UploadController.java
    │       ├── dto/
    │       │   ├── CompleteUploadRequest.java
    │       │   ├── CreateSessionRequest.java
    │       │   └── PresignedUrlRequest.java
    │       ├── model/
    │       │   ├── FileMetadata.java
    │       │   └── UploadSession.java
    │       ├── repository/
    │       │   ├── FileMetadataRepository.java
    │       │   └── UploadSessionRepository.java
    │       └── service/
    │           └── UploadService.java
    └── worker-service/
        ├── Dockerfile
        ├── pom.xml
        └── src/main/java/com/example/worker/
            ├── WorkerServiceApplication.java
            ├── model/
            │   └── FileMetadata.java
            ├── repository/
            │   └── FileMetadataRepository.java
            └── service/
                └── WorkerService.java

------------------------------------------------------------------------

# Running the System

## Requirements

-   Docker
-   Docker Compose

------------------------------------------------------------------------

## Start

    docker compose up --build

Upload API

    http://localhost:8080

------------------------------------------------------------------------

## Upload Test

Simple Upload

    curl -F "file=@testfile.txt" http://localhost:8080/upload

Chunk Upload (예시 스크립트)

``` bash
# 1. 세션 생성
SESSION=$(curl -s -X POST http://localhost:8080/upload/session \
  -H "Content-Type: application/json" \
  -d '{"filename":"largefile.zip","totalSize":10485760,"chunkSize":5242880}')

SESSION_ID=$(echo $SESSION | jq -r '.sessionId')

# 2. 청크 업로드 (5MB씩)
split -b 5242880 largefile.zip chunk_

i=0
for chunk in chunk_*; do
  curl -X POST http://localhost:8080/upload/chunk \
    -F "sessionId=$SESSION_ID" \
    -F "chunkNumber=$i" \
    -F "fileChunk=@$chunk" &
  i=$((i+1))
done
wait

# 3. 업로드 완료
curl -X POST http://localhost:8080/upload/complete \
  -H "Content-Type: application/json" \
  -d "{\"sessionId\":\"$SESSION_ID\"}"
```

------------------------------------------------------------------------

# MinIO Console

    http://localhost:9001

Login

    minioadmin
    minioadmin

------------------------------------------------------------------------

# Shutdown

    docker compose down

데이터까지 삭제

    docker compose down -v
