import cv2
import torch
import numpy as np
import tensorflow as tf
from torchvision import models, transforms
from ultralytics import YOLO
from PIL import ImageFont, ImageDraw, Image
import os
import sys
import time
import argparse  # ★ 추가됨: 서버 명령어 처리용
from datetime import datetime


# ============================
# 한글 출력
# ============================
def draw_text(img, text, point, color=(0, 255, 0), font_size=20):
    img_pil = Image.fromarray(cv2.cvtColor(img, cv2.COLOR_BGR2RGB))
    draw = ImageDraw.Draw(img_pil)
    try:
        font = ImageFont.truetype("malgun.ttf", font_size)
    except:
        font = ImageFont.load_default()
    draw.text(point, text, font=font, fill=color)
    return cv2.cvtColor(np.array(img_pil), cv2.COLOR_RGB2BGR)


# ============================
# 모델 로드
# ============================
def load_models(device="cuda"):
    print(f"🚀 모델 로딩(device={device})")

    detector = YOLO("yolov8n.pt")
    detector.to(device)
    # detector.fuse() # 오류 발생시 주석 처리

    label_map = torch.load("label_to_idx_final.pth")
    idx_to_label = {v: k for k, v in label_map.items()}
    num_classes = len(label_map)

    classifier = models.efficientnet_b4(weights=None)
    classifier.classifier[1] = torch.nn.Linear(
        classifier.classifier[1].in_features, num_classes
    )

    state_dict = torch.load("vehicle_classifier_A100_final_best.pth", map_location=device)
    classifier.load_state_dict(state_dict)
    classifier = classifier.to(device).half()
    classifier.eval()

    preprocess = transforms.Compose([
        transforms.Resize((380, 380)),
        transforms.Normalize([0.485, 0.456, 0.406],
                             [0.229, 0.224, 0.225])
    ])

    plate_model = tf.lite.Interpreter(model_path="yolov5_0901-fp16.tflite")
    plate_model.allocate_tensors()

    return detector, classifier, preprocess, plate_model, idx_to_label


# ============================
# 번호판 탐지
# ============================
def detect_plate(interpreter, img_roi):
    input_details = interpreter.get_input_details()
    output_details = interpreter.get_output_details()

    model_h = input_details[0]["shape"][1]
    model_w = input_details[0]["shape"][2]

    orig_h, orig_w = img_roi.shape[:2]

    img_rgb = cv2.cvtColor(img_roi, cv2.COLOR_BGR2RGB)
    img_resized = cv2.resize(img_rgb, (model_w, model_h))

    input_data = np.expand_dims(img_resized, 0)
    if input_details[0]["dtype"] == np.float32:
        input_data = input_data.astype(np.float32) / 255.0

    interpreter.set_tensor(input_details[0]["index"], input_data)
    interpreter.invoke()
    output = interpreter.get_tensor(output_details[0]["index"])

    preds = np.squeeze(output)
    if preds.ndim == 1:
        preds = [preds]

    boxes = []

    for p in preds:
        if len(p) < 6:
            continue

        conf = p[4]
        if conf < 0.1:
            continue

        cls_score = p[5 + np.argmax(p[5:])]
        final = conf * cls_score
        if final < 0.1:
            continue

        cx, cy, w, h = p[:4]

        if w < 1 and h < 1:
            W = int(w * orig_w)
            H = int(h * orig_h)
            X = int(cx * orig_w)
            Y = int(cy * orig_h)
        else:
            W = int(w)
            H = int(h)
            X = int(cx)
            Y = int(cy)

        x1 = int(X - W / 2)
        y1 = int(Y - H / 2)

        boxes.append([x1, y1, W, H])

    return boxes


# ============================
# 초고속 모자이크
# ============================
def fast_mosaic(frame, x1, y1, x2, y2, size=12):
    roi = frame[y1:y2, x1:x2]
    if roi.size == 0:
        return
    small = cv2.resize(roi, (size, size), interpolation=cv2.INTER_LINEAR)
    frame[y1:y2, x1:x2] = cv2.resize(
        small, (roi.shape[1], roi.shape[0]), interpolation=cv2.INTER_NEAREST)


# ============================
# 이미지 분석 함수 (수정됨: 경로 인자)
# ============================
def analyze_image(image_file, output_file):

    if not os.path.exists(image_file):
        print(f"❌ 이미지 파일 없음: {image_file}")
        return

    device = "cuda" if torch.cuda.is_available() else "cpu"
    det, classifier, preprocess, plate_model, idx_to_label = load_models(device)

    # 시간 측정 시작
    start_t = time.time()
    print(f"\n▶ 이미지 분석 시작: {image_file}\n")

    frame = cv2.imread(image_file)
    h, w = frame.shape[:2]

    # 차량 탐지
    results = det(frame, device=device, half=True)

    if len(results[0].boxes) == 0:
        print("🚫 차량 없음 -> 원본 저장")
        cv2.imwrite(output_file, frame) # 차량 없으면 원본이라도 저장해서 보내줌
        return

    for box in results[0].boxes:
        x1, y1, x2, y2 = map(int, box.xyxy[0].cpu().numpy())

        x1, y1 = max(0, x1), max(0, y1)
        x2, y2 = min(w, x2), min(h, y2)

        vehicle_roi = frame[y1:y2, x1:x2]

        # 차종 분류
        roi_rgb = cv2.cvtColor(vehicle_roi, cv2.COLOR_BGR2RGB)
        tensor = torch.from_numpy(roi_rgb).permute(2, 0, 1).float() / 255.0
        tensor = preprocess(tensor).unsqueeze(0).to(device).half()

        # torch.amp.autocast 사용 (버전에 따라 다를 수 있음)
        try:
            with torch.no_grad(), torch.amp.autocast("cuda"):
                out_cls = classifier(tensor)
                prob = torch.nn.functional.softmax(out_cls, 1)
                score, idx = torch.max(prob, 1)
        except:
             with torch.no_grad(): # 구버전 호환용
                out_cls = classifier(tensor)
                prob = torch.nn.functional.softmax(out_cls, 1)
                score, idx = torch.max(prob, 1)

        label = idx_to_label[idx.item()]
        score_pct = score.item() * 100

        # 번호판 탐지
        pboxes = detect_plate(plate_model, vehicle_roi)

        for px, py, pw, ph in pboxes:
            gx1 = x1 + px
            gy1 = y1 + py
            gx2 = gx1 + pw
            gy2 = gy1 + ph

            fast_mosaic(frame, gx1, gy1, gx2, gy2)
            cv2.rectangle(frame, (gx1, gy1), (gx2, gy2), (0, 0, 255), 2)

        # 시각화
        cv2.rectangle(frame, (x1, y1), (x2, y2), (0, 255, 0), 2)
        frame = draw_text(frame, f"{label} {score_pct:.1f}%", (x1, y1 - 30))

    # ★ 결과 저장 (서버가 지정한 경로에 저장)
    cv2.imwrite(output_file, frame)

    # 시간 측정 종료
    elapsed = time.time() - start_t
    print(f"\n✅ 분석 완료: {output_file}")
    print(f"⏱ 처리 시간: {elapsed:.2f}초")


# ============================
# 실행부 (수정됨: argparse 사용)
# ============================
if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", type=str, required=True, help="입력 이미지 경로")
    parser.add_argument("--output", type=str, required=True, help="출력 이미지 경로")
    args = parser.parse_args()

    if not os.path.exists(args.source):
        print(f"❌ 에러: '{args.source}' 파일을 찾을 수 없습니다.")
        sys.exit(1)

    # 서버가 준 경로 그대로 실행
    analyze_image(args.source, args.output)