# 파일 업로드 시스템

시스템 디자인 면접 연습을 위한 분산 파일 업로드 및 비동기 처리 시스템입니다.

## 시스템 아키텍처

```
클라이언트 (curl / HTTP)
       │
       ▼
Upload Service (Spring Boot REST API, 포트 8080)
       │
       ├──▶ MinIO (S3 호환 객체 저장소, 포트 9000)
       ├──▶ PostgreSQL (메타데이터 저장소, 포트 5432)
       └──▶ Redis Queue (작업 큐, 포트 6379)
                │
                ▼
         Worker Service (큐 소비 및 메타데이터 상태 업데이트)
```

### 서비스 구성

| 서비스          | 기술         | 역할                              |
|----------------|-------------|----------------------------------|
| upload-service | Spring Boot | 파일 업로드 REST API               |
| worker         | Spring Boot | 백그라운드 작업 처리기              |
| postgres       | PostgreSQL  | 파일 메타데이터 저장               |
| redis          | Redis       | 서비스 간 메시지 큐                |
| minio          | MinIO       | S3 호환 객체 저장소                |

## 프로젝트 구조

```
file-upload-system/
├── docker-compose.yml                 # 전체 인프라 정의
├── README.md
├── init-db/
│   └── init.sql                       # DB 테이블 생성 스크립트
├── upload-service/
│   ├── Dockerfile
│   ├── pom.xml
│   └── src/main/
│       ├── java/com/example/upload/
│       │   ├── UploadServiceApplication.java
│       │   ├── config/MinioConfig.java           # MinIO 클라이언트 설정
│       │   ├── controller/UploadController.java  # POST /upload 엔드포인트
│       │   ├── model/FileMetadata.java           # JPA 엔티티
│       │   ├── repository/FileMetadataRepository.java
│       │   └── service/UploadService.java        # 업로드 핵심 로직
│       └── resources/application.yml
└── worker-service/
    ├── Dockerfile
    ├── pom.xml
    └── src/main/
        ├── java/com/example/worker/
        │   ├── WorkerServiceApplication.java
        │   ├── model/FileMetadata.java
        │   ├── repository/FileMetadataRepository.java
        │   └── service/WorkerService.java        # 큐 소비 및 처리 로직
        └── resources/application.yml
```

## 업로드 흐름

1. 클라이언트가 `POST /upload` 요청으로 멀티파트 파일을 전송
2. Upload Service가 파일 바이너리를 **MinIO** (객체 저장소)에 저장
3. Upload Service가 **PostgreSQL**에 메타데이터 레코드 삽입 (상태: `UPLOADED`)
4. Upload Service가 메타데이터 ID를 **Redis** 리스트(큐)에 푸시
5. Upload Service가 클라이언트에게 메타데이터(id, filename, size, status) 응답 반환

## 큐와 워커의 작업 처리 방식

- **Redis 리스트** `file:process:queue`가 단순 FIFO 작업 큐 역할 수행
- Upload Service는 `LPUSH`(왼쪽 삽입)로 파일 ID를 큐에 추가
- Worker Service는 `BRPOP`(블로킹 오른쪽 추출)으로 파일 ID를 꺼내 FIFO 순서 보장
- 워커가 작업을 수신하면:
  1. PostgreSQL에서 파일 메타데이터 조회
  2. 상태를 `PROCESSING`으로 업데이트
  3. 파일 처리 시뮬레이션 (3초 대기)
  4. 상태를 `PROCESSED`로 업데이트
- 워커는 무한 루프로 실행되며, 큐에 작업이 없으면 블로킹 대기

### 상태 전이

```
UPLOADED  ──▶  PROCESSING  ──▶  PROCESSED
```

## 데이터베이스 스키마

```sql
CREATE TABLE file_metadata (
    id         BIGSERIAL PRIMARY KEY,
    filename   VARCHAR(255) NOT NULL,
    size       BIGINT NOT NULL,
    status     VARCHAR(50) NOT NULL DEFAULT 'UPLOADED',
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);
```

## 실행 방법

### 사전 요구사항

- Docker 및 Docker Compose

### 시작

```bash
docker compose up --build
```

5개 서비스가 모두 실행됩니다. 업로드 API는 `http://localhost:8080`에서 사용 가능합니다.

### 업로드 테스트

```bash
# 파일 업로드
curl -F "file=@testfile.txt" http://localhost:8080/upload

# 응답 예시
# {"id":1,"filename":"testfile.txt","size":1234,"status":"UPLOADED"}
```

몇 초 후 워커가 파일을 처리하고 상태가 `PROCESSED`로 변경됩니다.

### MinIO 콘솔

MinIO 웹 콘솔: `http://localhost:9001` (로그인: `minioadmin` / `minioadmin`)

### 종료

```bash
docker compose down
```

저장된 데이터까지 삭제하려면:

```bash
docker compose down -v
```
