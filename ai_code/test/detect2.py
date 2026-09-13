import cv2
import torch
import sys
import numpy as np
import tensorflow as tf
from torchvision import models, transforms
from ultralytics import YOLO
from PIL import ImageFont, ImageDraw, Image
import time
from datetime import datetime
import os
import argparse  # ★ 추가됨: 명령줄 인자 파싱용

# ==========================================
# 1. 한글 출력 헬퍼 함수
# ==========================================
def draw_text(img, text, point, color=(0, 255, 0), font_size=20):
    img_pil = Image.fromarray(cv2.cvtColor(img, cv2.COLOR_BGR2RGB))
    draw = ImageDraw.Draw(img_pil)
    try:
        font = ImageFont.truetype("malgun.ttf", font_size)
    except:
        font = ImageFont.load_default()
    draw.text(point, text, font=font, fill=color)
    return cv2.cvtColor(np.array(img_pil), cv2.COLOR_RGB2BGR)

# ==========================================
# 2. 모델 로드 (GPU 최적화)
# ==========================================
def load_models(device='cuda'):
    print(f"🚀 모델 로딩 (device={device})")
    vehicle_detector = YOLO("yolov8n.pt")
    vehicle_detector.to(device)
    # vehicle_detector.fuse() # 오류 발생 시 주석 처리 가능
    print("YOLO 로드 완료")

    label_map = torch.load('label_to_idx_final.pth')
    idx_to_label = {v: k for k, v in label_map.items()}
    num_classes = len(label_map)

    classifier = models.efficientnet_b4(weights=None)
    classifier.classifier[1] = torch.nn.Linear(classifier.classifier[1].in_features, num_classes)
    state_dict = torch.load('vehicle_classifier_A100_final_best.pth', map_location=device)
    classifier.load_state_dict(state_dict)
    classifier = classifier.to(device).half()
    classifier.eval()

    class_preprocess = transforms.Compose([
        transforms.Resize((380, 380)),
        transforms.Normalize([0.485, 0.456, 0.406], [0.229, 0.224, 0.225])
    ])

    plate_interpreter = tf.lite.Interpreter(model_path="yolov5_0901-fp16.tflite")
    plate_interpreter.allocate_tensors()

    return vehicle_detector, classifier, class_preprocess, plate_interpreter, idx_to_label

# ==========================================
# 3. 번호판 탐지
# ==========================================
def detect_plate(interpreter, img_roi):
    input_details = interpreter.get_input_details()
    output_details = interpreter.get_output_details()
    model_h = input_details[0]['shape'][1]
    model_w = input_details[0]['shape'][2]
    orig_h, orig_w = img_roi.shape[:2]

    img_rgb = cv2.cvtColor(img_roi, cv2.COLOR_BGR2RGB)
    img_resized = cv2.resize(img_rgb, (model_w, model_h))
    input_data = np.expand_dims(img_resized, axis=0)

    if input_details[0]['dtype'] == np.float32:
        input_data = input_data.astype(np.float32) / 255.0

    interpreter.set_tensor(input_details[0]['index'], input_data)
    interpreter.invoke()
    output_data = interpreter.get_tensor(output_details[0]['index'])
    predictions = np.squeeze(output_data)
    boxes = []
    conf_threshold = 0.10

    if predictions.ndim == 1:
        predictions = [predictions]

    for pred in predictions:
        if len(pred) < 6: continue
        confidence = pred[4]
        if confidence < conf_threshold: continue
        cls_scores = pred[5:]
        class_score = cls_scores[np.argmax(cls_scores)]
        final_score = confidence * class_score
        if final_score < conf_threshold: continue
        cx, cy, w, h = pred[:4]

        if w < 1 and h < 1:
            width = int(w * orig_w)
            height = int(h * orig_h)
            x_center = int(cx * orig_w)
            y_center = int(cy * orig_h)
        else:
            x_scale = orig_w / model_w
            y_scale = orig_h / model_h
            width = int(w * x_scale)
            height = int(h * y_scale)
            x_center = int(cx * x_scale)
            y_center = int(cy * y_scale)

        x = int(x_center - width / 2)
        y = int(y_center - height / 2)
        boxes.append([x, y, width, height])
    return boxes

# ==========================================
# 4. 모자이크 함수
# ==========================================
def fast_mosaic(frame, x1, y1, x2, y2, size=10):
    try:
        roi = frame[y1:y2, x1:x2]
        if roi.size == 0: return
        small = cv2.resize(roi, (size, size), interpolation=cv2.INTER_LINEAR)
        frame[y1:y2, x1:x2] = cv2.resize(small, (roi.shape[1], roi.shape[0]), interpolation=cv2.INTER_NEAREST)
    except Exception as e:
        pass

# ==========================================
# 5. 메인 실행 (수정됨: 경로 인자 받기)
# ==========================================
def run(source_path, output_path):
    start_time = time.time()
    print(f"⏳ 분석 시작: {source_path}")
    print(f"💾 저장 경로: {output_path}")

    device = "cuda" if torch.cuda.is_available() else "cpu"
    det, classifier, class_pre, plate_model, idx_to_label = load_models(device)

    cap = cv2.VideoCapture(source_path)
    if not cap.isOpened():
        print("❌ 영상 열기 실패")
        return

    width = int(cap.get(3))
    height = int(cap.get(4))
    fps = cap.get(5)

    # ★ 수정됨: 서버가 지정한 output_path에 정확히 저장
    out = cv2.VideoWriter(output_path,
                          cv2.VideoWriter_fourcc(*'mp4v'),
                          fps, (width, height))

    track_history = {}
    frame_count = 0
    best_frame = None
    max_conf = 0

    while True:
        ret, frame = cap.read()
        if not ret:
            break
        frame_count += 1

        # YOLO 추론
        results = det.track(
            frame, persist=True, classes=[2,3,5,7],
            verbose=False, half=True, device=device
        )

        if results[0].boxes.id is None:
            out.write(frame)
            continue

        boxes = results[0].boxes.xyxy.cpu().numpy().astype(int)
        ids = results[0].boxes.id.cpu().numpy().astype(int)

        for box, tid in zip(boxes, ids):
            x1, y1, x2, y2 = box
            x1, y1 = max(0,x1), max(0,y1)
            x2, y2 = min(width,x2), min(height,y2)
            if x2 <= x1 or y2 <= y1: continue

            vehicle_roi = frame[y1:y2, x1:x2]

            if tid not in track_history:
                track_history[tid] = {"info": "", "score": 0.0, "last": -999}

            if frame_count - track_history[tid]['last'] > 5:
                roi_rgb = cv2.cvtColor(vehicle_roi, cv2.COLOR_BGR2RGB)
                tensor = torch.from_numpy(roi_rgb).permute(2,0,1).float() / 255.0
                tensor = class_pre(tensor).unsqueeze(0).to(device).half()

                with torch.no_grad(): # amp.autocast 제거 (호환성)
                    out_cls = classifier(tensor)
                    prob = torch.nn.functional.softmax(out_cls, 1)
                    score, idx = torch.max(prob, 1)

                if score.item() > 0.4:
                    track_history[tid]['info'] = idx_to_label[idx.item()]
                    track_history[tid]['score'] = score.item() * 100
                    track_history[tid]['last'] = frame_count

            info = track_history[tid]['info']
            score_txt = f"{track_history[tid]['score']:.1f}%"

            if frame_count % 3 == 0:
                pboxes = detect_plate(plate_model, vehicle_roi)
            else:
                pboxes = []

            for px, py, pw, ph in pboxes:
                gx1 = x1 + px
                gy1 = y1 + py
                gx2 = gx1 + pw
                gy2 = gy1 + ph
                fast_mosaic(frame, gx1, gy1, gx2, gy2, size=12)
                cv2.rectangle(frame, (gx1, gy1), (gx2, gy2), (0,0,255), 2)

            cv2.rectangle(frame, (x1,y1), (x2,y2), (0,255,0), 2)
            frame = draw_text(frame, f"ID:{tid} {info} {score_txt}", (x1, y1 - 30))

            if track_history[tid]['score'] > max_conf:
                max_conf = track_history[tid]['score']
                best_frame = frame.copy()

        out.write(frame)

    cap.release()
    out.release()

    # 베스트샷 저장 (필요시 경로 수정 가능)
    if best_frame is not None:
        cv2.imwrite("best_shot.jpg", best_frame)

    print(f"\n✅ 분석 완료! 파일 저장됨: {output_path}")

if __name__ == "__main__":
    # ★ 수정됨: argparse로 서버의 명령어(--source, --output)를 받아들임
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", type=str, required=True, help="입력 영상 경로")
    parser.add_argument("--output", type=str, required=True, help="출력 영상 경로")
    args = parser.parse_args()

    if not os.path.exists(args.source):
        print(f"❌ 에러: '{args.source}' 파일을 찾을 수 없습니다.")
        sys.exit(1)

    # 서버가 준 경로 그대로 실행
    run(args.source, args.output)