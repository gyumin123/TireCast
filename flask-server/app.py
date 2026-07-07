"""
TireCast AI 추론 서버 (Flask + YOLOv8)

- F-MOD-01: 모델 파일 자동 탐색 — models/ 디렉토리에서 .pt 탐색, best.pt 우선
- F-MOD-02: 모델 로드 및 예외 처리 — 파일 없음/손상 시 서버는 유지, 명확한 에러 응답
- F-MOD-03: 모델 교체 감지(Hot Reload) — mtime 변경 시 다음 요청부터 새 모델 사용
- F-ANA-01: POST /predict — 타이어 결함 탐지, BBox 좌표 + 주석 이미지 반환
"""
import base64
import io
import logging
import threading
from pathlib import Path

from flask import Flask, jsonify, request

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
log = logging.getLogger("tirecast-ai")

app = Flask(__name__)

# 프로젝트 루트 기준 /models 디렉토리 (배치 규칙: models/best.pt 고정)
MODELS_DIR = Path(__file__).resolve().parent.parent / "models"
PREFERRED_NAME = "best.pt"

# 데이터셋 클래스 → 한국어 라벨 (Tyre Damage Detection.yolov8 data.yaml 기준)
CLASS_LABELS = {
    "CBU": "부풀음 (CBU)",
    "bead_damage": "비드 손상 (bead_damage)",
    "cut": "측면 크랙 (cut)",
    "tr": "트레드 마모 (tr)",
}

_model = None
_model_path = None
_model_mtime = None
_lock = threading.Lock()


def find_model_file():
    """F-MOD-01: models/ 에서 .pt 파일 탐색. best.pt 우선, 없으면 최근 수정 파일."""
    if not MODELS_DIR.is_dir():
        return None
    preferred = MODELS_DIR / PREFERRED_NAME
    if preferred.is_file():
        return preferred
    candidates = sorted(MODELS_DIR.glob("*.pt"), key=lambda p: p.stat().st_mtime, reverse=True)
    return candidates[0] if candidates else None


def load_model():
    """F-MOD-02/03: 모델 로드. mtime이 바뀌면 재로드(Hot Reload)."""
    global _model, _model_path, _model_mtime
    path = find_model_file()
    if path is None:
        log.error("모델 파일을 찾을 수 없습니다. 경로를 확인하세요: %s/*.pt", MODELS_DIR)
        _model = None
        return None

    mtime = path.stat().st_mtime
    with _lock:
        if _model is not None and _model_path == path and _model_mtime == mtime:
            return _model  # 변경 없음 — 기존 모델 재사용
        try:
            from ultralytics import YOLO
            log.info("모델 로드 중: %s", path)
            _model = YOLO(str(path))
            _model_path, _model_mtime = path, mtime
            log.info("모델 로드 완료: %s (classes=%s)", path.name, _model.names)
        except Exception:
            log.exception("모델 로드 실패 — 파일이 손상되었거나 형식이 올바르지 않습니다: %s", path)
            _model = None
    return _model


@app.get("/health")
def health():
    model = load_model()
    return jsonify({
        "status": "ok" if model else "model_unavailable",
        "model": _model_path.name if _model_path else None,
        "classes": list(model.names.values()) if model else [],
    }), (200 if model else 503)


@app.post("/predict")
def predict():
    """F-ANA-01: 이미지에서 타이어 결함 탐지."""
    model = load_model()
    if model is None:
        return jsonify({"error": "모델 파일을 찾을 수 없습니다. models/best.pt 배치 여부를 확인하세요."}), 503

    file = request.files.get("image")
    if file is None:
        return jsonify({"error": "image 파일이 필요합니다."}), 400

    try:
        from PIL import Image
        img = Image.open(io.BytesIO(file.read())).convert("RGB")
    except Exception:
        return jsonify({"error": "이미지를 해석할 수 없습니다."}), 400

    results = model.predict(img, conf=0.4, verbose=False)
    r = results[0]
    w, h = img.size

    detections = []
    for box in r.boxes:
        cls_name = model.names[int(box.cls)]
        conf = float(box.conf)
        x1, y1, x2, y2 = (float(v) for v in box.xyxy[0])
        detections.append({
            "class": cls_name,
            "label": CLASS_LABELS.get(cls_name, cls_name),
            "confidence": round(conf, 4),
            "bbox": [round(x1, 1), round(y1, 1), round(x2, 1), round(y2, 1)],
            "bbox_pct": {  # 화면 오버레이용 % 좌표
                "left": round(x1 / w * 100, 2),
                "top": round(y1 / h * 100, 2),
                "width": round((x2 - x1) / w * 100, 2),
                "height": round((y2 - y1) / h * 100, 2),
            },
        })

    # 바운딩 박스가 그려진 결과 이미지 (JPEG base64)
    annotated = Image.fromarray(r.plot()[..., ::-1])  # BGR → RGB
    buf = io.BytesIO()
    annotated.save(buf, format="JPEG", quality=88)
    annotated_b64 = base64.b64encode(buf.getvalue()).decode()

    return jsonify({
        "detections": detections,
        "annotated_image_base64": annotated_b64,
        "model": _model_path.name,
    })


if __name__ == "__main__":
    import os
    load_model()  # 기동 시 1회 로드 시도 (실패해도 서버는 뜬다 — F-MOD-02)
    # 기본 5001: macOS 로컬 개발 시 AirPlay 수신기가 5000을 선점하는 충돌 회피.
    # 배포 환경(GCP)에서 명세대로 5000을 쓰려면 FLASK_PORT=5000 으로 기동.
    app.run(host="127.0.0.1", port=int(os.environ.get("FLASK_PORT", "5001")))
