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

이 구조는 실제 대규모 시스템에서 다음과 같은 방식으로 확장될 수
있습니다.

-   Presigned URL 기반 Direct Upload
-   Multipart / Chunk Upload
-   Parallel Upload
-   Worker Auto Scaling
-   Dead Letter Queue(DLQ)
-   CDN 기반 파일 배포

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

# File Upload Flow

1.  Client가 `POST /upload` 요청으로 파일 업로드
2.  Upload Service가 파일을 **MinIO(Object Storage)** 에 저장
3.  Upload Service가 **PostgreSQL**에 메타데이터 저장 (`UPLOADED`)
4.  Upload Service가 **Redis Queue**에 작업 메시지 추가
5.  Worker Service가 메시지를 소비
6.  Worker가 파일 처리 수행 후 상태 업데이트

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

    UPLOADED
       │
       ▼
    PROCESSING
       │
       ▼
    PROCESSED

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
```

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

    curl -F "file=@testfile.txt" http://localhost:8080/upload

Example Response

    {
     "id":1,
     "filename":"testfile.txt",
     "size":1234,
     "status":"UPLOADED"
    }

몇 초 후 Worker가 파일을 처리하고 상태가 `PROCESSED`로 변경됩니다.

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

------------------------------------------------------------------------

# Architecture Extensions

이 시스템은 다음과 같은 방식으로 확장할 수 있습니다.

-   Presigned URL 기반 Direct Upload
-   Multipart / Chunk Upload
-   Parallel Upload
-   Worker Auto Scaling
-   Dead Letter Queue(DLQ)
-   CDN 기반 파일 배포
