# 백엔드 Scalability 실험 계획

## 1. 목표

아키텍처 단계별로 **SLO를 지키면서 처리할 수 있는 최대 트래픽**을 측정하고, 각 단계에서 **무엇이 병목이 되어 다음 단계로 넘어가야 하는지**를 데이터로 확인한다.

> 숫자 자체보다 "왜 그 숫자에서 멈췄는가(병목 자원)"를 찾는 것이 핵심 산출물이다.

### 성공 기준 (SLO) — 모든 실험에 동일하게 적용
| 지표 | 기준 |
|---|---|
| p99 latency | < 200ms |
| p95 latency | < 100ms |
| 에러율 (5xx + timeout) | < 1% |

**최대 처리량(Max Sustainable RPS)** = 위 SLO를 5분 이상 유지한 가장 높은 RPS.

---

## 2. 실험 시나리오

| # | 구성 | 확인하고 싶은 것 | 예상 병목 |
|---|---|---|---|
| S1 | App ×1 + MySQL ×1 | 베이스라인 | App CPU 또는 커넥션 풀 |
| S2 | App ×N(1,2,4,8) + LB + MySQL ×1 | App 수평 확장 효과, DB 한계점 | MySQL CPU / 커넥션 수 / 락 경합 |
| S3 | App ×N + MySQL Primary ×1 + Replica ×1~2 (Read/Write 분리) | 읽기 부하 분산 효과 | Primary 쓰기 처리량, Replication lag |
| S4 | S3 + Redis 캐시 | 캐시 히트율에 따른 DB 부하 감소 | 캐시 미스 시 DB, 핫키 |
| S5 | App ×N + Cassandra 클러스터(3 → 6 노드) | 쓰기 중심 워크로드에서 노드 추가 시 선형 확장 여부 | 파티션 설계, Compaction, CL 설정 |
| S6 | App ×N + MySQL 앱 레벨 샤딩 (2 → 4 샤드) | RDB 유지하면서 쓰기 확장 | 샤드 간 부하 편차, 크로스 샤드 쿼리 |

> S4(캐시)는 실제 서비스에서 Read Replica 다음으로 가장 흔한 단계. S6은 S5의 비교 대상으로, "쓰기 확장에 NoSQL이 정말 필요한가"를 공정하게 판단하기 위해 포함.

---

## 3. 테스트 대상 애플리케이션

모든 시나리오에서 **같은 API**를 사용하고, 저장소 계층만 교체한다.

### 기술 스택
- **Java 25 (LTS) + Spring Boot 4**
- Spring MVC + Virtual Threads (`spring.threads.virtual.enabled=true`) — 스레드 풀이 아닌 DB 커넥션 풀이 실제 동시성 한계가 되도록
- MySQL: JDBC(`JdbcClient`) + HikariCP — ORM 오버헤드를 배제하고 DB 특성을 드러내기 위해 JPA는 사용하지 않음
- Cassandra: DataStax Java Driver
- Redis: Spring Data Redis (Lettuce)
- 메트릭: Micrometer + Prometheus

### 도메인: 간단한 SNS (게시글 / 좋아요)
| API | 종류 | 설명 |
|---|---|---|
| `GET /posts/{id}` | Read (PK 조회) | 가장 가벼운 읽기 |
| `GET /users/{id}/posts?limit=20` | Read (범위 조회) | 인덱스 범위 스캔 |
| `POST /posts` | Write | INSERT |
| `POST /posts/{id}/like` | Write (경합) | 같은 row UPDATE → 락 경합 유발 |

- 저장소 계층을 인터페이스로 분리 (`PostRepository` → MySQL 구현 / Cassandra 구현)
- Read/Write 분리는 라우팅 DataSource(예: Spring `AbstractRoutingDataSource`)로 구현
- 애플리케이션 로직은 의도적으로 얇게 유지 (DB 특성이 드러나도록)

### 샤딩 설계 (S6)
- **샤드 키: `user_id`** → `GET /users/{id}/posts`(범위 조회)가 항상 한 샤드 안에서 끝남
- **게시글 ID에 샤드 번호를 포함** (Snowflake 형식: 시간 + 샤드 번호 + 시퀀스) → `GET /posts/{id}`, `POST /posts/{id}/like`도 ID만 보고 샤드를 찾음 (조회용 매핑 테이블 불필요)
- 샤드 라우팅: `user_id` 해시 → 샤드 번호 → 샤드별 DataSource 선택
- 2 → 4 샤드 확장 시 데이터 재분배는 실험 범위에서 제외 (샤드 수별로 데이터를 따로 적재해 스냅샷으로 보관)

### 데이터 규모
- users 100만, posts 1,000만 row 수준으로 **한 번만 적재 → EBS 스냅샷으로 보관**, 이후에는 스냅샷 복원으로 사용
- 기본 조건은 데이터가 InnoDB buffer pool에 다 들어가는 경우 (적재 후 데이터+인덱스 약 2GB)
- 디스크 I/O 병목 확인은 **S1에서만** `innodb_buffer_pool_size`를 데이터보다 작게 줄여 R1/R2만 추가 측정 (데이터 재적재 불필요)

---

## 4. 워크로드 (트래픽 패턴)

### 4.1 단독 측정 — 읽기 / 쓰기 각각의 한계 (주 실험)

읽기와 쓰기는 병목 지점이 다르므로 **먼저 각각 단독으로 한계를 측정**한다.

| 워크로드 | 구성 | 대상 API | 주로 드러나는 병목 |
|---|---|---|---|
| R1 PK 조회 | Read 100% | `GET /posts/{id}` | App CPU, 커넥션 풀, buffer pool 적중률 |
| R2 범위 조회 | Read 100% | `GET /users/{id}/posts` | 인덱스 스캔, 디스크 I/O |
| W1 INSERT | Write 100% | `POST /posts` | redo log flush(fsync), 인덱스 갱신, Primary 단일 노드 |
| W2 UPDATE 경합 | Write 100% | `POST /posts/{id}/like` | row lock 대기, 데드락 |

각 시나리오에서 기대하는 변화:

| 시나리오 | 읽기 성능 | 쓰기 성능 |
|---|---|---|
| S2 App 증설 | DB 한계까지 증가 | DB 한계까지 증가 (읽기보다 빨리 막힘) |
| S3 Read Replica | Replica 수에 비례해 증가 | **변화 없음** — 여전히 Primary 1대 (오히려 binlog 부하로 약간 감소 가능) |
| S4 캐시 | 크게 증가 (적중률 의존) | 변화 없음 또는 감소 (캐시 무효화 비용) |
| S5 Cassandra | 노드 수에 비례 (단, 범위 조회는 모델링 의존) | 노드 수에 비례해 증가 |
| S6 샤딩 | 샤드 수에 비례 | 샤드 수에 비례 |

> 이 표의 예상이 실제로 맞는지 확인하는 것이 실험의 핵심. 특히 "Replica를 늘려도 쓰기는 안 늘어난다" → "그래서 샤딩/Cassandra가 필요하다"는 흐름을 수치로 보여준다.

### 4.2 혼합 측정 — 읽기와 쓰기의 상호 간섭

단독 한계를 구한 뒤, 실제 서비스에 가까운 혼합 부하에서 **서로 얼마나 방해하는지** 측정한다.

| 워크로드 | Read : Write | 의미 |
|---|---|---|
| M1 Read-heavy | 95 : 5 | 일반적인 웹 서비스 |
| M2 Mixed | 70 : 30 | |
| M3 Write-heavy | 20 : 80 | 로그/이벤트 수집형 — Cassandra가 유리할 영역 |

- 혼합 부하에서도 **읽기/쓰기 지표를 따로 집계** (k6 요청에 `type:read` / `type:write` 태그를 붙여 분리)
- 추가 실험: **쓰기 부하를 고정(예: 단독 한계의 50%)한 상태에서 읽기 한계가 얼마나 떨어지는지** 측정 → 쓰기가 읽기에 주는 간섭을 정량화
- S3에서는 쓰기 부하가 커질수록 **replication lag**가 어떻게 늘어나는지 함께 기록

- 키 분포: 기본은 **Uniform**, **Zipfian(핫키 존재)** 은 캐시 효과(S4)와 락 경합(W2) 확인에만 사용

### 4.3 측정 절차 (효율화)

한 워크로드당 **약 20분**을 목표로 한다.

| 단계 | 내용 | 시간 |
|---|---|---|
| ① 탐색 (1회) | **Coarse → Fine Step Load**: 처음엔 큰 폭(예: 1분마다 +1,000 RPS)으로 올려 대략적인 한계를 찾고, 한계의 70%부터 작은 폭(예: 1분마다 +5%)으로 다시 올려 knee를 확정 | ~10분 |
| ② 확인 (2회) | knee 값으로 **5분 Constant Load** × 2회 → SLO 유지 여부 확인. 3회(탐색 포함) 결과의 중앙값을 사용 | ~10분 |

- 이전 시나리오의 결과로 시작 RPS를 정한다 (예: S2 App 2대는 S1 한계의 1.5배부터 시작) → 탐색 시간 단축
- 회차 사이 DB 초기화는 하지 않는다. 쓰기 테스트로 늘어난 데이터는 1,000만 건 대비 무시 가능한 수준이며, 워크로드 그룹이 바뀔 때만 초기화
- Soak / Spike 테스트는 이번 범위에서 제외 (필요하면 별도 진행)

### 4.4 시나리오별 측정 워크로드

모든 시나리오에서 7종(R1, R2, W1, W2, M1~M3)을 다 측정하지 않고, **그 시나리오가 답해야 하는 질문에 필요한 것만** 측정한다.

| 시나리오 | 측정 대상 | 측정 워크로드 | 횟수 | 예상 시간 |
|---|---|---|---|---|
| S1 | App 1 | 7종 전부 + buffer pool 축소 조건 R1/R2 | 9 | ~3h |
| S2 | App 2 / 4 / 8 | R1, W1 (구성별) + App 4대에서 M1 | 7 | ~2.5h |
| S3 | Replica 1 / 2 | R1, R2 (구성별) + W1 1회 + M1, M2 (replication lag 확인) | 7 | ~2.5h |
| S4 | Redis 추가 | R1 (Uniform / Zipfian) + M1 | 3 | ~1h |
| S5 | Cassandra 3 / 6 노드 | R1, W1 (구성별) + R2, M3 | 6 | ~2h |
| S6 | MySQL 샤드 2 / 4 | R1, W1 (구성별) + W2, M3 (S5와 같은 조건으로 비교) | 6 | ~2h |

> 같은 시나리오 안에서 구성을 바꿀 때(App 대수, Replica 수, 노드 수)는 Terraform 변수만 바꿔서 인스턴스를 추가/제거한다. 처음부터 다시 만들지 않는다.

> 부하는 **open model(arrival-rate 고정)** 로 생성한다. VU 수 고정(closed model) 방식은 서버가 느려지면 부하도 같이 줄어들어 한계를 과대평가하게 됨. (k6의 `ramping-arrival-rate` executor 사용)

---

## 5. 인프라 구성

### 원칙
- **부하 발생기는 반드시 별도 머신**에서 실행 (같은 머신이면 부하 발생기가 CPU를 뺏어 결과가 왜곡됨)
- 모든 시나리오에서 **인스턴스 스펙 고정** (App: 2vCPU/4GB, DB: 4vCPU/16GB 등)
- 각 실험 전 부하 발생기·LB 자체가 병목이 아닌지 먼저 확인 (빈 응답 API로 측정)

### 환경 옵션
| 옵션 | 장점 | 단점 |
|---|---|---|
| A. 로컬 Docker Compose + `cpus`/`mem_limit` 제한 | 무료, 빠른 반복 | 모든 컨테이너가 같은 CPU/디스크 공유 → 절대값 신뢰도 낮음 |
| B. 클라우드 (AWS EC2 등) + Terraform | 실제에 가까운 결과, 네트워크 지연 포함 | 비용 발생 (실험 시간만 켜면 수만 원 수준) |

**결정**: 개발·디버깅은 전부 A(로컬)에서 끝내고, **B(클라우드)는 마지막에 한 번에 띄워서 전체 측정 후 종료**한다.

- 로컬 결과는 절대 수치로 쓰지 않고, 스크립트·대시보드·시나리오 흐름이 끝까지 돌아가는지 검증하는 용도로만 사용
- 로컬은 **소규모 데이터(users 1만, posts 10만)** 로만 동작을 확인한다. 전체 규모(1,000만 건) 적재와 모든 수치 측정은 클라우드에서만 한다
- 클라우드에서는 디버깅하지 않는다. 문제가 생기면 즉시 destroy → 로컬에서 수정 → 다시 실행

### EC2 구성 (B 환경)

**기본 단위 (S1)**: App EC2 1 + DB EC2 1 + **부하 발생기 EC2 1** = 3대

| 역할 | 인스턴스 예시 | 비고 |
|---|---|---|
| App | c6i.large (2vCPU/4GB) | 스펙을 작게 잡아야 App 증설 효과가 잘 보임 |
| DB (MySQL) | m6i.xlarge (4vCPU/16GB) + gp3 (IOPS/처리량 고정) | |
| 부하 발생기 (k6) | c6i.xlarge | 테스트 중 CPU 70% 넘으면 1대 추가 |
| 모니터링 | t3.small 또는 부하 발생기에 같이 | 측정 대상 서버에는 두지 않음 |

**시나리오별 추가 인스턴스**

| 시나리오 | 구성 |
|---|---|
| S1 | App 1 + DB 1 |
| S2 | App 1→2→4(→8) + LB(Nginx EC2 1대 또는 ALB) + DB 1 |
| S3 | S2 + Replica 1~2 (Primary와 동일 스펙) |
| S4 | S3 + Redis 1 |
| S5 | App N + Cassandra 3→6 |
| S6 | App N + MySQL 샤드 2→4 (샤드마다 DB 스펙과 동일) |

**주의 사항**
- **t 계열(t3, t4g 등) 사용 금지** (모니터링 제외): CPU 크레딧이 소진되면 성능이 급락해 결과가 왜곡됨
- **모든 인스턴스를 같은 AZ**에 배치 (AZ 간 지연·전송 비용 제거)
- EBS는 gp3로 IOPS/처리량을 명시 고정, 사용할 데이터는 미리 읽어 워밍업
- Terraform으로 구성해 **실험할 때만 생성 → 끝나면 destroy** (비용 통제)

### 클라우드 일괄 실행 절차

전체 측정을 스크립트 하나(`run-all.sh`)로 자동화해 사람이 붙어 있지 않아도 끝까지 돌아가게 한다.

```
1. terraform apply (S1 구성)
2. MySQL 데이터 적재 → EBS 스냅샷 생성 (최초 1회)
3. for 시나리오 in S1..S6:
     terraform apply (해당 구성으로 변경)
     for 워크로드 in 4.4 표:
         k6 실행 → 결과(JSON) + Grafana 스냅샷 저장
4. 결과를 로컬로 다운로드 (또는 S3 업로드)
5. terraform destroy   ← 성공/실패와 관계없이 항상 실행 (trap)
```

- **안전장치**
  - 스크립트 실패 시에도 `trap`으로 destroy 실행
  - 모든 인스턴스를 `instance_initiated_shutdown_behavior = "terminate"`로 만들고 부팅 시 `shutdown -h +1440` (24시간 후 자동 삭제) 설정 — 스크립트가 멈춰도 과금이 무한정 이어지지 않게
  - AWS Budgets 알림 ($40, $70)
  - 종료 후 `aws ec2 describe-instances`로 남은 인스턴스 0개인지 확인
- 시나리오 사이에 결과를 확인하고 싶으면 시나리오 단위로 끊어 실행해도 됨 (단, 그 사이 인스턴스는 destroy)

### 예상 비용 (서울 리전, 온디맨드, 1$ ≈ 1,400원 가정 — 실행 전 AWS Pricing Calculator로 재확인)

| 시나리오 | 구성 | 시간당 | 가동 시간 | 소계 |
|---|---|---|---|---|
| 준비 | 인프라 생성 + 데이터 적재 + 스냅샷 | ~$0.54 | 1.5h | ~$1 |
| S1 | App 1 + DB 1 + 부하 1 | ~$0.54 | 3h | ~$2 |
| S2 | App 최대 8 + Nginx + DB + 부하 | ~$0.9–1.3 | 2.5h | ~$3 |
| S3 | App 4 + Primary + Replica 최대 2 + 부하 | ~$1.4 | 2.5h | ~$4 |
| S4 | S3 + Redis | ~$1.5 | 1h | ~$2 |
| S5 | App 4 + Cassandra 최대 6 + 부하 (+ 데이터 적재 1h) | ~$2.0 | 3h | ~$6 |
| S6 | App 4 + MySQL 샤드 최대 4 + 부하 (+ 데이터 적재 1h) | ~$1.6 | 3h | ~$5 |
| 기타 | EBS, 스냅샷, 공인 IP, 모니터링 | | | ~$6 |
| **합계** | | | **약 17h** | **~$30** |
| **예산 (실패 후 재실행 1회 여유 포함)** | | | | **~$60 (약 8.5만 원)** |

- 부하 발생기는 Spot 사용 가능 (~60–70% 절감). 회수되면 해당 워크로드만 재실행
- **위험**: 끄는 걸 잊으면 S1 구성만으로도 월 ~$390 (약 55만 원) → 위 안전장치로 방지

### 구성 요소
| 역할 | 도구 |
|---|---|
| App | Java 25 + Spring Boot 4 |
| LB | Nginx (round-robin) |
| DB | MySQL 8, Cassandra 4/5, Redis 7 |
| 부하 생성 | k6 |
| 모니터링 | Prometheus + Grafana, node_exporter, mysqld_exporter, Cassandra exporter |
| APM (선택) | Pinpoint / OpenTelemetry |

---

## 6. 수집 지표

| 계층 | 지표 |
|---|---|
| Client (k6) | RPS, p50/p95/p99 latency, 에러율 |
| App | CPU, Heap/GC, 스레드 풀 사용률, **커넥션 풀 active/pending**, 대기 시간 |
| DB (MySQL) | CPU, QPS, `Threads_running`, 슬로우 쿼리, row lock wait, buffer pool hit ratio, 디스크 IOPS, **replication lag** |
| DB (Cassandra) | read/write latency, pending compactions, GC pause, 노드별 부하 편차 |
| OS | CPU, 메모리, 디스크 I/O, 네트워크 |

---

## 7. 통제 변수 & 공정성

- 시나리오 시작 시 **스냅샷에서 DB 복원** (재적재 없음), 1~2분 워밍업 후 측정
- 각 워크로드는 **탐색 1회 + 확인 2회**, 중앙값 사용 (4.3 참고)
- 시나리오마다 **커넥션 풀 크기를 튜닝**해서 최적값으로 비교 (튜닝 안 된 구성끼리 비교하면 의미 없음)
  - 주의: App N대 × 풀 크기 ≤ MySQL `max_connections`
  - **절차**: S1에서 DB 전체 커넥션 수(App 1대이므로 = 풀 크기)를 10 / 20 / 40 / 80으로 바꿔 R1, W1을 대략 탐색(coarse)만 수행 → 가장 높은 값을 **DB 커넥션 예산**으로 확정
  - S2 이후에는 예산을 App 대수로 나눠 배분 (`infra/local/up.py --db-connections <예산>`, App당 최소 5). DB가 받는 총 커넥션 수를 고정해야 "App 증설 효과"만 비교됨
  - Replica / 샤드가 추가되면 DB 노드마다 같은 예산을 적용
- **App 대수를 바꾼 직후에는 반드시 워밍업**: 로컬 검증에서 새로 뜬 JVM 4대는 첫 30초 동안 300/s도 처리하지 못했고(p99 2.5초), 워밍 후에는 같은 부하를 p99 21ms로 처리함
- Cassandra 비교 시 **일관성 수준 명시** (예: `QUORUM` 읽기/쓰기). `ONE`으로 측정하면 MySQL과 다른 보장 수준을 비교하게 됨
- Cassandra는 MySQL 스키마를 그대로 옮기지 말고 **쿼리 중심 모델링** (예: `posts_by_user` 테이블)
  - 구현: `posts_by_id`(단건), `posts_by_user`(사용자 파티션, id 내림차순), `post_likes`(counter — 일반 컬럼과 같은 테이블에 둘 수 없음)
  - 글 작성은 두 테이블에 병렬 INSERT (logged batch 아님: 빠르지만 중간 실패 시 두 테이블이 어긋날 수 있음)
  - 좋아요는 counter라 행 잠금 경합이 없음 → W2(핫키 좋아요)에서 MySQL과 가장 큰 차이가 예상됨. 대신 404 의미를 지키려고 존재 확인 읽기 1회가 추가됨
  - 목록 조회는 글 목록 1회 + 좋아요 수 `IN` 조회 1회
- Read Replica 사용 시 **read-after-write 불일치** 발생 여부도 기록 (성능만큼 중요한 트레이드오프)
  - 측정: `find_limit.py --raw-rate 20` — 본 부하와 별도 시나리오로 "작성 직후 같은 글 조회"를 초당 20회 수행, 404 비율을 `stale_reads`로 기록 (SLO 판정에서는 제외)
  - 로컬 검증에서 이미 M3(쓰기 80%) 부하 시 87%, M2에서 약 50%가 404 → 작성 직후 조회는 Primary로 보내야 함을 수치로 보여줄 수 있음
- Replica 측정 전에는 **복제 지연 0, Primary와 row 수 일치를 확인**하고 시작 (Replica를 새로 만들면 처음부터 따라잡는 동안 404가 대량 발생함)
- S4 캐시 정책: 단건 조회 `post:{id}` 10분(좋아요 시 삭제), 사용자 타임라인 첫 페이지 `timeline:{userId}` 60초(작성 시 삭제, 좋아요 수는 TTL 동안 늦게 반영될 수 있음), 존재하지 않는 글은 캐시하지 않음
  - 로컬 검증: Zipf(핫키 1,000개) 적중률 약 80%, Uniform 약 2% → **캐시 효과는 키 분포가 좌우**하므로 S4는 반드시 두 분포 모두 측정
- 복제 지연은 `performance_schema`의 트랜잭션별 (적용 완료 시각 − 원본 커밋 시각)으로 ms 단위 측정 (`Seconds_Behind_Source`는 1초 단위라 부족)

---

## 8. 결과 정리 형식

### 8.1 단독 측정 결과 (읽기 / 쓰기 분리)

| 시나리오 | 인스턴스 수 | R1 Max RPS | R2 Max RPS | W1 Max RPS | W2 Max RPS | 읽기 병목 | 쓰기 병목 |
|---|---|---|---|---|---|---|---|
| S1 | | | | | | | |
| S2 (App ×4) | | | | | | | |
| ... | | | | | | | |

### 8.2 혼합 측정 결과

| 시나리오 | 워크로드 | 총 Max RPS | Read p99 | Write p99 | 병목 자원 | Replication lag |
|---|---|---|---|---|---|---|
| S1 | M1 | | | | | - |
| ... | | | | | | |

추가 그래프:
- **읽기 Max RPS / 쓰기 Max RPS를 시나리오별 막대 그래프로 나란히** — 어떤 구성이 읽기만 늘리고 쓰기는 못 늘리는지 한눈에 비교
- **RPS vs p99 latency** 곡선 (시나리오별, 읽기/쓰기 각각)
- **쓰기 부하 vs 읽기 p99** (쓰기가 읽기를 얼마나 방해하는지)
- **App 대수 vs Max RPS** (S2에서 선형 확장이 어디서 꺾이는지)
- **Replica 수 vs Read RPS** (S3)
- **노드/샤드 수 vs Write RPS** — Cassandra(S5)와 MySQL 샤딩(S6)을 한 그래프에 비교

---

## 9. 진행 단계

| 단계 | 내용 |
|---|---|
**Phase 0~5는 전부 로컬, 클라우드는 Phase 6에서 한 번만 사용.**

| 단계 | 환경 | 내용 |
|---|---|---|
| Phase 0 ✅ | 로컬 | 테스트 앱 + 저장소 계층 인터페이스 구현, 데이터 적재 스크립트 |
| Phase 1 ✅ | 로컬 | Docker Compose 환경, 모니터링 대시보드, k6 스크립트 (탐색/확인 단계 포함) |
| Phase 2 ✅ | 로컬 | S1~S2 흐름 검증 (App 증설, LB) |
| Phase 3 ✅ | 로컬 | Read/Write 분리 + Replica, Redis 캐시 구현 → S3~S4 흐름 검증 |
| Phase 4 🚧 | 로컬 | Cassandra 저장소 구현 → S5 흐름 검증, 샤드 라우팅 구현 → S6 흐름 검증 |
| Phase 5 | 로컬 + AWS(소규모) | Terraform 작성, `run-all.sh` 작성. 최소 구성으로 apply → 워크로드 1개 → destroy 까지 **리허설 1회** (~$1) |
| Phase 6 | AWS | **일괄 실행**: 전체 측정 후 destroy (~17h) |
| Phase 7 | 로컬 | 결과 분석 및 리포트 작성 |

---

## 10. 결정이 필요한 사항

- [x] **실행 환경**: 로컬에서 개발·검증 → AWS에서 마지막에 일괄 측정 후 종료 (App / DB / 부하 발생기 EC2 분리)
- [x] **앱 스택**: Java 25 + Spring Boot 4
- [x] **도메인**: SNS (게시글 / 좋아요)
- [x] **범위**: S1~S6 전부 포함
