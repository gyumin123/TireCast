#!/usr/bin/env bash
# TireCast 재배포: 코드 갱신 → 재빌드 → 서비스 재시작
# 사용: bash deploy/redeploy.sh
set -euo pipefail

APP_DIR="$HOME/tirecast"
cd "$APP_DIR"

echo "=== 코드 갱신 ==="
git pull

echo "=== Spring Boot 재빌드 ==="
./mvnw -q package -DskipTests

echo "=== Python 의존성 갱신 (변경분만) ==="
flask-server/.venv/bin/pip install --quiet -r flask-server/requirements.txt

echo "=== 서비스 재시작 ==="
sudo systemctl restart tirecast-flask tirecast-spring

sleep 8
echo -n "Flask  /health : " && curl -s http://localhost:5000/health || true
echo
echo -n "Spring /       : " && curl -s -o /dev/null -w "HTTP %{http_code}" http://localhost:8080/ && echo
echo "재배포 완료!"
