# TireCast GCP 배포 가이드

기획안 6.4 배포 전략 기준: 단일 VM(e2-medium) 안에서 Spring Boot(`:8080`, 외부 개방)와
Flask YOLO 서버(`:5000`, localhost 내부망)가 통신하는 구조.

## 최초 배포 (VM에서 1회)

```bash
# 1. VM에 SSH 접속 후
git clone https://github.com/gyumin123/TireCast.git ~/tirecast
bash ~/tirecast/deploy/setup-vm.sh
```

스크립트가 자동으로 처리하는 것:

| 단계 | 내용 |
|---|---|
| 패키지 | git, Java 21(없으면 Temurin), python3-venv, OpenCV 런타임(libgl1) |
| 스왑 | 2GB 스왑 생성 (4GB RAM에서 torch 추론 OOM 방지) |
| 빌드 | `./mvnw package` → 실행형 JAR |
| Python | venv + **CPU 전용 torch**(GPU 없는 e2용, 용량↓) + ultralytics |
| 서비스 | systemd 등록(`tirecast-flask`, `tirecast-spring`) — 부팅 시 자동 시작, 죽으면 자동 재시작 |

## 방화벽 (외부 접속 허용)

Cloud Shell 또는 gcloud가 설치된 곳에서:

```bash
gcloud compute firewall-rules create allow-tirecast --allow=tcp:8080 --direction=INGRESS
```

또는 GCP 콘솔 → VPC 네트워크 → 방화벽 → 규칙 만들기 (tcp:8080, 0.0.0.0/0).
5000 포트는 **열지 않는다** — Flask는 127.0.0.1 바인딩이라 외부에서 접근 불가(의도된 설계).

## 확인

```bash
# VM 내부에서
sudo systemctl status tirecast-flask tirecast-spring
curl http://localhost:5000/health     # {"status":"ok","model":"best.pt",...}
curl -I http://localhost:8080/        # HTTP 200

# 외부에서
http://<VM 외부 IP>:8080
```

## 재배포 (코드 수정 후)

```bash
# 로컬에서 git push 후, VM에서:
bash ~/tirecast/deploy/redeploy.sh
```

## 모델 교체 (F-MOD-03 핫리로드)

새로 학습한 `best.pt`를 VM의 `~/tirecast/models/best.pt`에 덮어쓰면
**서버 재시작 없이** 다음 추론 요청부터 새 모델이 적용된다.

```bash
# 로컬에서
scp best.pt <user>@<VM IP>:~/tirecast/models/best.pt
```

## 로그 보기

```bash
sudo journalctl -u tirecast-spring -f    # Spring 로그
sudo journalctl -u tirecast-flask -f     # Flask/YOLO 로그
```

## 알려진 제약: HTTPS와 위치 정보

브라우저 Geolocation API는 **HTTPS(또는 localhost)에서만 동작**한다.
`http://<IP>:8080`으로 접속하면 위치 권한 요청이 차단되어 항상 기본 위치(서울)로 폴백된다.
실사용을 위해서는 도메인 + HTTPS(Caddy 또는 Nginx + Let's Encrypt) 구성을 권장한다.
