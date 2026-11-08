# Backend Scalability Experiments

같은 SNS API를 저장소 구성만 바꿔가며(S1~S6) 부하 테스트한다. 실험 계획: [docs/EXPERIMENT_PLAN.md](docs/EXPERIMENT_PLAN.md)

## 구성

| 경로 | 내용 |
|---|---|
| `app/` | Java 25 + Spring Boot 4 SNS API, 데이터 적재기 |
| `k6/workload.js` | 워크로드(R1~R2, W1~W2, M1~M3) 1단계 부하 스크립트 |
| `bench/find_limit.py` | 한계 탐색 드라이버 (워밍업 → 대략 탐색 → 정밀 탐색 → 확인) |
| `docker-compose.yml` | 로컬 공통 인프라: MySQL, Prometheus, Grafana, k6 |
| `infra/local/up.py` | App N대 + Nginx + Read Replica M대 구성 생성 및 기동 (S1 = 1대, S2 = 2/4/8대, S3 = Replica 1/2대) |
| `infra/local/grafana/build_dashboard.py` | Grafana 대시보드 생성 (패널은 여기서 수정) |
| `infra/aws/` | Terraform(Docker로 실행) + 호스트 역할 스크립트(`remote/`) |
| `bench/run_all.py`, `bench/scenarios.py` | 클라우드 전체 실험 자동 실행 (생성 → 설정 → 측정 → 결과 수집 → 삭제) |
| `infra/local/` | 로컬 설정 파일, Grafana 대시보드 |

## API

| Method | Path | 워크로드 |
|---|---|---|
| GET | `/posts/{id}` | R1 PK 조회 |
| GET | `/users/{id}/posts?limit=20&before={postId}` | R2 범위 조회 (최신순, 커서) |
| POST | `/posts` `{"userId": 1, "content": "..."}` | W1 INSERT |
| POST | `/posts/{id}/like` | W2 UPDATE 경합 |

응답의 `id`는 문자열이다 (런타임 Snowflake ID가 JavaScript 안전 정수 범위를 넘기 때문).

## 로컬 실행 (동작 확인용 — 수치는 의미 없음)

```bash
cd app && ./gradlew test bootJar && cd ..   # 테스트 (Docker 필요) + sns.jar 빌드
python3 infra/local/up.py --apps 2           # MySQL, App ×2, Nginx(:8080), Prometheus(:9090), Grafana(:3000)
#   --apps N: 서버 대수, --db-connections 40: DB 노드당 커넥션 예산(App당 = 예산 / N), --pool P: App당 풀 직접 지정
#   --replicas M: Read Replica (GTID 복제, 읽기는 Replica에 라운드로빈). 데이터는 Primary에만 적재
#   --cache: Redis cache-aside (S4). 단건 조회 10분, 사용자 타임라인 첫 페이지 60초 캐시
#   --storage cassandra --cassandra-nodes N: S5 (RF = min(3, N), LOCAL_QUORUM). 노드당 약 1.5GB 필요
#   --storage sharded --shards N: S6 MySQL 샤딩 (샤드 = (userId % 1024) % N, N은 1024의 약수)
#   --load USERS:POSTS: 저장소를 먼저 띄우고 compose 네트워크 안에서 적재 후 앱 기동 (Cassandra는 최초 1회 필수)

# 소규모 데이터 적재 (약 4초) — 또는 up.py --load 10000:100000
java -jar app/build/libs/sns.jar --spring.profiles.active=loader \
  --sns.loader.users=10000 --sns.loader.posts=100000
#   기본값(users 100만, posts 1,000만, 약 2GB)은 클라우드 측정용

# 한계 탐색 (짧은 시간으로 흐름만 확인)
python3 bench/find_limit.py --label local-check --workload M2 --users 10000 --posts 100000 \
  --warmup-duration 30s --coarse-duration 10s --fine-duration 10s --confirm-duration 15s

# S3: read-after-write 불일치 측정 (부하와 별도로 초당 20회 "작성 직후 조회" → 404 비율 = stale_reads)
python3 bench/find_limit.py --label s3-rep2 --workload M2 --raw-rate 20 ...

# S4: 캐시 효과는 키 분포에 크게 좌우됨 → Uniform과 Zipf(핫키) 둘 다 측정
python3 bench/find_limit.py --label s4 --workload R1 --dist zipf ...

# 커넥션 풀 튜닝용: 대략 탐색만
python3 bench/find_limit.py --label s1-pool10 --workload R1 --coarse-only ...

# k6 단일 실행
docker compose run --rm k6 run -e WORKLOAD=R1 -e RATE=100 -e DURATION=10s \
  -e USERS=10000 -e POSTS=100000 /scripts/workload.js
```

- Grafana: http://localhost:3000 (Scalability 대시보드: k6 → App → MySQL 순으로 병목 추적)
- 결과: `results/<label>/<workload>/` (단계별 k6 요약, `steps.jsonl`, `result.json`), 전체 요약 `results/results.csv`
- 판정 기준: p99 < 200ms, p95 < 100ms, 에러 < 1%, 목표 RPS의 95% 이상 실제 전송

| 환경 변수 (App) | 기본값 | 설명 |
|---|---|---|
| `DB_URL` | `jdbc:mysql://localhost:3306/sns` | |
| `DB_USERNAME` / `DB_PASSWORD` | `sns` / `sns` | |
| `DB_POOL_SIZE` | `20` | HikariCP 풀 크기 (시나리오마다 튜닝) |
| `WORKER_ID` | `1` | 앱 인스턴스마다 다르게 (1~31) |
| `DB_REPLICA_URLS` | (없음) | S3: 쉼표로 구분한 Replica JDBC URL. 읽기만 라운드로빈으로 보냄 |
| `CACHE_ENABLED` / `REDIS_HOST` | `false` / `localhost` | S4: Redis cache-aside 사용 여부와 주소 |
| `SPRING_PROFILES_ACTIVE=cassandra` | | S5: Cassandra 저장소 (MySQL 자동 설정 꺼짐) |
| `SPRING_PROFILES_ACTIVE=sharded` + `DB_SHARD_URLS` | | S6: 쉼표로 구분한 샤드 JDBC URL (순서 = 샤드 번호) |
| `CASSANDRA_CONTACT_POINTS` / `CASSANDRA_DC` / `CASSANDRA_RF` | `localhost:9042` / `datacenter1` / `3` | S5 접속 정보, keyspace 복제 계수(적재 시 적용) |

**App 실행 방식 주의**: 실행 jar를 그대로 `java -jar`로 띄우지 말고 압축을 풀어 실행한다
(`java -Djarmode=tools -jar sns.jar extract`, `-Djdk.virtualThreadScheduler.parallelism=4`, `app/Dockerfile` 참고).
CPU 1~2개 환경에서 가상 스레드 + Spring Boot nested jar 클래스 로더 락 때문에 앱 전체가 멈추는 교착이 재현됐다.

메트릭: `/actuator/prometheus`

## 클라우드 실행 (AWS, 비용 발생)

```bash
python3 bench/run_all.py --plan rehearsal              # 계획·예상 비용만 출력 (아무것도 만들지 않음)
python3 bench/run_all.py --plan rehearsal --dry-run    # 실행될 명령 전부 출력 (아무것도 만들지 않음)
caffeinate -i python3 bench/run_all.py --plan rehearsal --yes   # 리허설 (~1시간, ~$1–2)
caffeinate -i python3 bench/run_all.py --plan full --yes        # 전체 (~17시간, ~$30)
#   --only s1,s2 : 일부 단계만, --keep : 끝나도 삭제하지 않음 (디버깅용, 과금 계속됨)
```

- 필요: Docker(Terraform 실행용), AWS CLI 자격 증명(`~/.aws`), 서울 리전 EC2 생성 권한, 동시 vCPU 한도 약 44 이상
- 진행 중 Grafana: `http://<loadgen-1 공인 IP>:3000` (내 IP에서만 접근 가능)
- 결과: `results/cloud-<시각>/` (단계별 k6 요약, `results.csv`, 실행 로그)
- 실패해도 마지막에 항상 결과 복사 → `terraform destroy` → 남은 인스턴스 확인. 그래도 모든 인스턴스는 24시간 후 스스로 종료됨

## ID 형식과 적재 데이터 (k6 스크립트가 의존)

ID: `[41bit 타임스탬프 ms][10bit 논리 샤드 = userId % 1024][5bit worker][7bit 시퀀스]`

적재 데이터는 DB 조회 없이 계산할 수 있다 (U = 사용자 수, 기본 1,000,000):

```
n     = 1 .. 게시글 수
owner = ((n - 1) % U) + 1
id    = n * 4194304 + (owner % 1024) * 4096     // n << 22 | shard << 12, 2^53 미만
```

변경 시 `SnowflakeIdGenerator`, `DataLoader`, k6 스크립트를 함께 수정할 것.
