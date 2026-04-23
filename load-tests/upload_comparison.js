/**
 * File Upload 방식별 성능 비교
 * - Simple Upload vs Chunk(Multipart) Upload 처리 시간 비교
 * - 이력서 포인트: "Chunk 병렬 업로드로 대용량 파일 처리 시간 X% 단축"
 */
import http from 'k6/http';
import { check, group, sleep } from 'k6';
import { Trend, Rate, Counter } from 'k6/metrics';
import exec from 'k6/execution';

const simpleUploadDuration = new Trend('simple_upload_duration', true);
const chunkUploadDuration  = new Trend('chunk_upload_total_duration', true);
const uploadErrors         = new Rate('upload_error_rate');
const completedUploads     = new Counter('completed_uploads');

export const options = {
  scenarios: {
    // 시나리오 A: Simple Upload 부하
    simple_upload: {
      executor: 'ramping-vus',
      stages: [
        { duration: '20s', target: 10 },
        { duration: '1m',  target: 20 },
        { duration: '20s', target: 0 },
      ],
      tags: { method: 'simple' },
      startTime: '0s',
    },
    // 시나리오 B: Chunk Upload 부하
    chunk_upload: {
      executor: 'ramping-vus',
      stages: [
        { duration: '20s', target: 5 },
        { duration: '1m',  target: 10 },
        { duration: '20s', target: 0 },
      ],
      tags: { method: 'chunk' },
      startTime: '2m',
    },
  },
  thresholds: {
    'upload_error_rate':          ['rate<0.05'],
    'simple_upload_duration':     ['p(95)<3000'],
    'chunk_upload_total_duration':['p(95)<5000'],
  },
};

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

// 1MB 더미 파일 데이터 생성
function generateFileContent(sizeKB) {
  return 'x'.repeat(sizeKB * 1024);
}

export default function () {
  const scenarioName = exec.scenario.name;

  if (scenarioName === 'chunk_upload') {
    runChunkUpload();
  } else {
    runSimpleUpload();
  }

  sleep(1);
}

function runSimpleUpload() {
  group('simple_upload', () => {
    const fileContent = generateFileContent(100); // 100KB
    const formData = {
      file: http.file(fileContent, 'testfile.txt', 'text/plain'),
    };

    const start = Date.now();
    const res = http.post(`${BASE_URL}/upload`, formData);
    const elapsed = Date.now() - start;

    const ok = check(res, {
      'simple upload 200': (r) => r.status === 200,
      'has file id':       (r) => r.json('id') !== undefined,
    });

    uploadErrors.add(!ok);
    simpleUploadDuration.add(elapsed);
    if (ok) completedUploads.add(1);
  });
}

function runChunkUpload() {
  group('chunk_upload', () => {
    const totalSize = 1024 * 1024;  // 1MB
    const chunkSize = 262144;       // 256KB → 4 chunks
    const filename  = `testfile_${Date.now()}.bin`;

    const start = Date.now();

    // Step 1: 세션 생성
    const sessionRes = http.post(
      `${BASE_URL}/upload/session`,
      JSON.stringify({ filename, totalSize, chunkSize }),
      { headers: { 'Content-Type': 'application/json' } }
    );

    if (!check(sessionRes, { 'session created': (r) => r.status === 200 })) {
      uploadErrors.add(1);
      return;
    }

    const sessionId  = sessionRes.json('sessionId');
    const totalChunks = sessionRes.json('totalChunks');

    // Step 2: 청크 업로드 (순차 - k6에서 병렬은 별도 설정 필요)
    let allChunksOk = true;
    for (let i = 0; i < totalChunks; i++) {
      const chunkData = generateFileContent(256); // 256KB
      const chunkRes = http.post(`${BASE_URL}/upload/chunk`, {
        sessionId:   sessionId,
        chunkNumber: i.toString(),
        fileChunk:   http.file(chunkData, `chunk_${i}.bin`, 'application/octet-stream'),
      });

      if (!check(chunkRes, { [`chunk ${i} ok`]: (r) => r.status === 200 })) {
        allChunksOk = false;
        break;
      }
    }

    if (!allChunksOk) {
      uploadErrors.add(1);
      return;
    }

    // Step 3: 완료
    const completeRes = http.post(
      `${BASE_URL}/upload/complete`,
      JSON.stringify({ sessionId }),
      { headers: { 'Content-Type': 'application/json' } }
    );

    const elapsed = Date.now() - start;

    const ok = check(completeRes, {
      'chunk upload complete': (r) => r.status === 200,
      'status UPLOADED':       (r) => r.json('status') === 'UPLOADED',
    });

    uploadErrors.add(!ok);
    chunkUploadDuration.add(elapsed);
    if (ok) completedUploads.add(1);
  });
}

export function handleSummary(data) {
  const simpleP95 = data.metrics['simple_upload_duration']
    ? data.metrics['simple_upload_duration'].values['p(95)'].toFixed(2) : 'N/A';
  const chunkP95 = data.metrics['chunk_upload_total_duration']
    ? data.metrics['chunk_upload_total_duration'].values['p(95)'].toFixed(2) : 'N/A';
  const completed = data.metrics['completed_uploads']
    ? data.metrics['completed_uploads'].values.count : 0;

  console.log('\n========== File Upload 성능 비교 ==========');
  console.log(`Simple Upload p95:      ${simpleP95}ms`);
  console.log(`Chunk Upload  p95:      ${chunkP95}ms`);
  console.log(`완료된 업로드:           ${completed}건`);
  console.log('==========================================\n');

  return { stdout: JSON.stringify(data, null, 2) };
}
