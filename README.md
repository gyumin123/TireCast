# 🛞 TireCast

**비전 AI와 실시간 기상 데이터를 융합한 맞춤형 타이어 안전 진단 웹 서비스**

> 타이어는 차량에서 지면과 닿는 유일한 부품이지만, 가장 점검받지 못하는 부품입니다.
> TireCast는 스마트폰으로 찍은 타이어 사진 한 장으로 결함을 진단하고,
> 현재 위치의 날씨와 교차 분석해 *"오늘은 대중교통을 이용하세요"* 같은
> 즉각적인 행동 지침(Actionable Insight)을 제공합니다.

## 🌐 배포 주소

**https://tirecast.gmpark.dev**

## 무엇을 해결하나요?

운전자는 두 가지 단절 속에서 위험에 노출됩니다.

1. **진단의 단절** — 트레드 마모, 미세 크랙, 부풀음(CBU), 비드 손상은 전문가가 아니면 판별하기 어렵습니다.
2. **데이터의 단절** — 타이어 결함은 폭우·폭염 같은 기상 조건과 결합할 때 몇 배로 위험해지지만
   (예: 마모 타이어 + 폭우 = 수막현상), 두 데이터를 융합해 경고해주는 서비스는 없었습니다.

TireCast는 **YOLOv8 결함 탐지 × Open-Meteo 실시간 기상**을 자체 융합 알고리즘으로 교차 분석해
3단계 위험도(🟢 안전 / 🟡 경고 / 🔴 위험)와 행동 지침을 산출합니다.

## 핵심 기능

| 기능 | 설명 |
|---|---|
| 📷 모바일 촬영 진단 | 카메라 촬영/갤러리 업로드, 브라우저에서 640px 자동 리사이징, GPS 좌표 수집(거부 시 서울 폴백) |
| 🤖 AI 결함 탐지 | 커스텀 학습 YOLOv8 — 트레드 마모(tr)·측면 크랙(cut)·부풀음(CBU)·비드 손상(bead_damage) 4종 탐지, 바운딩 박스 결과 이미지 |
| 🌦️ 기상 융합 인사이트 | 실시간 기온·강수량 + 위치명 역지오코딩 → 결함×날씨 규칙 기반 3단계 판정 및 한국어 행동 지침 |
| 📊 진단 히스토리 | 월별 아코디언 그룹핑, 상태 필터(안전/경고/위험), 커서 기반 무한 로딩, 당시 이미지·날씨·위치까지 보여주는 상세 모달 |
| 🔄 모델 핫리로드 | `models/best.pt`를 교체하면 서버 재시작 없이 다음 추론부터 새 모델 적용 |
| 🔐 회원 관리 | BCrypt 해시 + 세션 인증 (가입/로그인/로그아웃) |

## 시스템 아키텍처

```
사용자 (HTTPS)
   │
Cloudflare Proxy ─ tirecast.gmpark.dev (SSL 종단)
   │
Nginx 리버스 프록시 (:80)
   │
Spring Boot ─ SSR(Thymeleaf) · 세션 인증 · 융합 알고리즘
   │    ├── SQLite (users, diagnosis_history)
   │    ├── Open-Meteo API (실시간 기상)
   │    └── 역지오코딩 API (좌표 → 위치명)
   │
Flask (내부망 전용) ─ YOLOv8 추론 (CPU torch)
   └── models/best.pt (자동 탐색 + 핫리로드)
```

- 무거운 텐서 연산(Flask)을 웹 서버(Spring)와 **프로세스 격리** — 장애 전파 차단
- Flask는 `127.0.0.1` 바인딩으로 외부 접근 원천 차단
- 파일 기반 SQLite로 DB 데몬 없이 저사양 단일 VM에서 운영

## 🚀 로컬 실행 방법

### 사전 요구사항

- **Java 21+** (Spring Boot)
- **Python 3.11+** (Flask + YOLOv8)
- 학습된 모델 `models/best.pt` — 저장소에 포함되어 있음

### 1. Flask AI 서버 실행

```bash
git clone https://github.com/gyumin123/TireCast.git
cd TireCast

python3 -m venv flask-server/.venv
flask-server/.venv/bin/pip install -r flask-server/requirements.txt
flask-server/.venv/bin/python flask-server/app.py
# → http://localhost:5001 (macOS AirPlay의 5000 포트 선점을 피해 기본 5001)
# 포트 변경: FLASK_PORT=5000 flask-server/.venv/bin/python flask-server/app.py
```

> GPU가 없는 환경은 CPU 전용 torch로 설치 용량을 줄일 수 있습니다:
> `pip install torch torchvision --index-url https://download.pytorch.org/whl/cpu`

### 2. Spring Boot 웹 서버 실행

```bash
./mvnw spring-boot:run
# → http://localhost:8080
```

### 3. 사용

브라우저에서 `http://localhost:8080` 접속 → 회원가입 → 타이어 사진 업로드 → 진단 결과 확인.
`tirecast.db`(SQLite)와 `uploads/`(이미지)는 프로젝트 루트에 자동 생성됩니다.

모델 로드 상태 확인: `curl http://localhost:5001/health`

## 프로젝트 구조

```
├── src/main/java/com/tirecast/     # Spring Boot (컨트롤러·서비스·엔티티)
│   ├── controller/                 # 인증·진단·날씨·페이지 라우팅
│   ├── service/                    # 융합 알고리즘, Flask 클라이언트, 기상, 저장
│   └── entity/, repository/        # USERS, DIAGNOSIS_HISTORY (SQLite + JPA)
├── src/main/resources/templates/   # 로그인·회원가입·촬영·대시보드 (Thymeleaf, 글래스모피즘)
├── flask-server/                   # YOLOv8 추론 서버 (모델 자동 탐색·핫리로드)
├── models/best.pt                  # 학습된 YOLOv8 가중치 (Colab, epochs 50)
├── deployment.skill                # 운영 서버 안전 재배포 절차 (정본)
└── docs/발표보고서.md               # 발표용 슬라이드 보고서
```

## API 요약

| 메서드 | 경로 | 설명 |
|---|---|---|
| POST | `/api/auth/register` · `/login` · `/logout` | 회원 관리 (세션) |
| POST | `/api/diagnosis/analyze` | 이미지+GPS → YOLO 추론 → 날씨 융합 → 이력 저장 |
| GET | `/api/diagnosis/history?cursor=&limit=&status=` | 커서 기반 이력 조회 |
| GET | `/api/diagnosis/history/{id}` | 이력 상세 (당시 이미지·결함·날씨·위치) |
| GET | `/api/weather?lat=&lon=` | 실시간 기상 + 위치명 |
| POST | `/predict` (Flask, 내부망) | YOLOv8 결함 탐지 |

## 문서

- [발표 보고서 (배경·문제정의·아키텍처·시연 시나리오)](docs/발표보고서.md)
- [배포 절차 — deployment.skill](deployment.skill)

## 기술 스택

`Spring Boot 4.1 (Java 21)` · `Flask 3 + Ultralytics YOLOv8` · `SQLite` · `Thymeleaf SSR` ·
`Open-Meteo / BigDataCloud API` · `GCP Compute Engine + Cloudflare + Nginx + systemd`
