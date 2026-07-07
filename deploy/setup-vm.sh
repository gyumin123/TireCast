#!/usr/bin/env bash
# ============================================================
# TireCast GCP VM 원클릭 배포 스크립트 (Ubuntu / Debian)
# 대상: e2-medium (4GB RAM) — 기획안 6.4 배포 전략 기준
#   - Spring Boot :8080 (외부 개방)
#   - Flask YOLO  :5000 (내부망 localhost 전용)
# 사용: bash deploy/setup-vm.sh
# 재배포: bash deploy/redeploy.sh
# ============================================================
set -euo pipefail

REPO_URL="https://github.com/gyumin123/TireCast.git"
APP_DIR="$HOME/tirecast"
FLASK_PORT=5000

echo "=== [1/7] 시스템 패키지 설치 ==="
sudo apt-get update -y
sudo apt-get install -y git python3-venv python3-pip libgl1 libglib2.0-0 curl

# Java 21 — 배포판 저장소에 없으면 Adoptium(Temurin) 저장소 추가
if ! java -version 2>&1 | grep -qE 'version "(2[1-9])'; then
  if apt-cache show openjdk-21-jdk-headless >/dev/null 2>&1; then
    sudo apt-get install -y openjdk-21-jdk-headless
  else
    sudo apt-get install -y wget apt-transport-https gnupg
    wget -qO- https://packages.adoptium.net/artifactory/api/gpg/key/public \
      | sudo gpg --dearmor -o /usr/share/keyrings/adoptium.gpg
    echo "deb [signed-by=/usr/share/keyrings/adoptium.gpg] https://packages.adoptium.net/artifactory/deb $(. /etc/os-release && echo "$VERSION_CODENAME") main" \
      | sudo tee /etc/apt/sources.list.d/adoptium.list
    sudo apt-get update -y
    sudo apt-get install -y temurin-21-jdk
  fi
fi

echo "=== [2/7] 스왑 2GB 확보 (4GB RAM에서 torch 추론 보호) ==="
if [ "$(swapon --show | wc -l)" -eq 0 ]; then
  sudo fallocate -l 2G /swapfile
  sudo chmod 600 /swapfile
  sudo mkswap /swapfile
  sudo swapon /swapfile
  echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
fi

echo "=== [3/7] 소스 코드 준비 ==="
if [ -d "$APP_DIR/.git" ]; then
  git -C "$APP_DIR" pull
else
  git clone "$REPO_URL" "$APP_DIR"
fi
cd "$APP_DIR"

echo "=== [4/7] Spring Boot 빌드 ==="
./mvnw -q package -DskipTests

echo "=== [5/7] Flask 가상환경 구성 (CPU 전용 torch — GPU 없는 e2 인스턴스용) ==="
if [ ! -d flask-server/.venv ]; then python3 -m venv flask-server/.venv; fi
flask-server/.venv/bin/pip install --quiet --upgrade pip
flask-server/.venv/bin/pip install --quiet torch torchvision --index-url https://download.pytorch.org/whl/cpu
flask-server/.venv/bin/pip install --quiet -r flask-server/requirements.txt

echo "=== [6/7] systemd 서비스 등록 ==="
JAR="$(ls target/tirecast-*.jar | head -1)"
JAVA_BIN="$(command -v java)"

sudo tee /etc/systemd/system/tirecast-flask.service >/dev/null <<UNIT
[Unit]
Description=TireCast Flask AI Server (YOLOv8)
After=network.target

[Service]
User=$USER
WorkingDirectory=$APP_DIR/flask-server
Environment=FLASK_PORT=$FLASK_PORT
ExecStart=$APP_DIR/flask-server/.venv/bin/python app.py
Restart=on-failure
RestartSec=5

[Install]
WantedBy=multi-user.target
UNIT

sudo tee /etc/systemd/system/tirecast-spring.service >/dev/null <<UNIT
[Unit]
Description=TireCast Spring Boot Web Server
After=network.target tirecast-flask.service

[Service]
User=$USER
WorkingDirectory=$APP_DIR
ExecStart=$JAVA_BIN -Xmx768m -jar $APP_DIR/$JAR --tirecast.flask-url=http://localhost:$FLASK_PORT
Restart=on-failure
RestartSec=5

[Install]
WantedBy=multi-user.target
UNIT

sudo systemctl daemon-reload
sudo systemctl enable --now tirecast-flask tirecast-spring

echo "=== [7/7] 기동 확인 ==="
sleep 10
echo -n "Flask  /health : " && curl -s "http://localhost:$FLASK_PORT/health" || true
echo
echo -n "Spring /       : " && curl -s -o /dev/null -w "HTTP %{http_code}" http://localhost:8080/ && echo
echo
echo "배포 완료!"
echo "외부 접속은 GCP 방화벽에서 tcp:8080 허용이 필요합니다:"
echo "  gcloud compute firewall-rules create allow-tirecast --allow=tcp:8080 --direction=INGRESS"
