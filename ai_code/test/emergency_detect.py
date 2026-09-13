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
import argparse
from datetime import datetime

# ============================
# 기본 유틸
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

def load_models(device="cuda"):
    print(f"🚀 모델 로딩(device={device})")
    detector = YOLO("yolov8n.pt")
    detector.to(device)
    
    label_map = torch.load("label_to_idx_final.pth")
    idx_to_label = {v: k for k, v in label_map.items()}
    num_classes = len(label_map)
    
    classifier = models.efficientnet_b4(weights=None)
    classifier.classifier[1] = torch.nn.Linear(classifier.classifier[1].in_features, num_classes)
    state_dict = torch.load("vehicle_classifier_A100_final_best.pth", map_location=device)
    classifier.load_state_dict(state_dict)
    classifier = classifier.to(device).half()
    classifier.eval()
    
    preprocess = transforms.Compose([
        transforms.Resize((380, 380)),
        transforms.Normalize([0.485, 0.456, 0.406], [0.229, 0.224, 0.225])
    ])
    
    plate_model = tf.lite.Interpreter(model_path="yolov5_0901-fp16.tflite")
    plate_model.allocate_tensors()
    
    return detector, classifier, preprocess, plate_model, idx_to_label

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
    if preds.ndim == 1: preds = [preds]
    
    boxes = []
    for p in preds:
        if len(p) < 6: continue
        conf = p[4]
        if conf < 0.1: continue
        
        cls_score = p[5 + np.argmax(p[5:])]
        final = conf * cls_score
        if final < 0.1: continue
        
        cx, cy, w, h = p[:4]
        if w < 1 and h < 1:
            W, H = int(w * orig_w), int(h * orig_h)
            X, Y = int(cx * orig_w), int(cy * orig_h)
        else:
            W, H, X, Y = int(w), int(h), int(cx), int(cy)
            
        x1 = int(X - W / 2)
        y1 = int(Y - H / 2)
        boxes.append([x1, y1, W, H])
        
    return boxes

# ============================
# ★ 핵심: 긴급 분석 함수 (방법 2 적용: CLAHE + Lanczos + Sharpening)
# ============================
def analyze_emergency(image_file, output_file, lat, lng):
    if not os.path.exists(image_file): return

    device = "cuda" if torch.cuda.is_available() else "cpu"
    det, classifier, preprocess, plate_model, idx_to_label = load_models(device)
    print(f"\n🚨 긴급 분석 시작! 위치: {lat}, {lng}")

    frame = cv2.imread(image_file)
    h_img, w_img = frame.shape[:2]

    # 1. 차량 탐지 및 분류
    results = det(frame, device=device, half=True)
    if len(results[0].boxes) == 0:
        cv2.imwrite(output_file, frame)
        return

    best_plate_img = None # 가장 선명한 번호판 저장용

    for box in results[0].boxes:
        x1, y1, x2, y2 = map(int, box.xyxy[0].cpu().numpy())
        x1, y1 = max(0, x1), max(0, y1)
        x2, y2 = min(w_img, x2), min(h_img, y2)
        vehicle_roi = frame[y1:y2, x1:x2]

        # 차종 분류
        roi_rgb = cv2.cvtColor(vehicle_roi, cv2.COLOR_BGR2RGB)
        tensor = torch.from_numpy(roi_rgb).permute(2, 0, 1).float() / 255.0
        tensor = preprocess(tensor).unsqueeze(0).to(device).half()
        with torch.no_grad():
            out_cls = classifier(tensor)
            prob = torch.nn.functional.softmax(out_cls, 1)
            score, idx = torch.max(prob, 1)
        label = idx_to_label[idx.item()]
        score_pct = score.item() * 100

        # 2. 번호판 탐지
        pboxes = detect_plate(plate_model, vehicle_roi)
        for px, py, pw, ph in pboxes:
            gx1, gy1 = x1 + px, y1 + py
            gx2, gy2 = gx1 + pw, gy1 + ph
            
            # 번호판 영역 잘라내기
            plate_crop = frame[gy1:gy2, gx1:gx2]
            if plate_crop.size > 0:
                best_plate_img = plate_crop 

            # 빨간 박스 표시
            cv2.rectangle(frame, (gx1, gy1), (gx2, gy2), (0, 0, 255), 3)

        # 시각화
        cv2.rectangle(frame, (x1, y1), (x2, y2), (0, 255, 0), 2)
        frame = draw_text(frame, f"긴급:{label} {score_pct:.1f}%", (x1, y1 - 30), color=(0,0,255))

    # 3. ★ 번호판 선명화/확대 및 왼쪽 상단 부착 (방법 2) ★
    if best_plate_img is not None:
        try:
            # [Step 1] 대비(Contrast) 향상 (CLAHE)
            # YUV 색공간으로 변경 -> 밝기 채널(Y)에 CLAHE 적용 -> 다시 BGR 변환
            img_yuv = cv2.cvtColor(best_plate_img, cv2.COLOR_BGR2YUV)
            clahe = cv2.createCLAHE(clipLimit=3.0, tileGridSize=(8,8))
            img_yuv[:,:,0] = clahe.apply(img_yuv[:,:,0])
            enhanced_plate = cv2.cvtColor(img_yuv, cv2.COLOR_YUV2BGR)

            # [Step 2] 고품질 확대 (Lanczos 보간법 + 5배)
            # INTER_LANCZOS4는 글자/텍스트 확대에 가장 유리함
            enlarged = cv2.resize(enhanced_plate, None, fx=5.0, fy=5.0, interpolation=cv2.INTER_LANCZOS4)

            # [Step 3] 선명화 (Sharpening Filter)
            # 경계선을 뚜렷하게 만듦
            kernel = np.array([[0, -1, 0],
                               [-1, 5, -1],
                               [0, -1, 0]])
            sharpened = cv2.filter2D(enlarged, -1, kernel)

            # [Step 4] 왼쪽 상단에 붙이기 (크기 제한 적용)
            eh, ew = sharpened.shape[:2]
            
            # 화면 절반을 넘지 않도록 제한
            max_h = h_img // 2
            max_w = w_img // 2
            paste_h = min(eh, max_h)
            paste_w = min(ew, max_w)

            # 잘라서 붙이기
            frame[0:paste_h, 0:paste_w] = sharpened[0:paste_h, 0:paste_w]
            
            # 노란 테두리
            cv2.rectangle(frame, (0,0), (paste_w, paste_h), (0,255,255), 3)

        except Exception as e:
            print(f"번호판 선명화 실패: {e}")

    # 위치 정보 기록
    frame = draw_text(frame, f"신고 위치: {lat}, {lng}", (10, h_img - 40), color=(0,255,255), font_size=25)

    cv2.imwrite(output_file, frame)
    print(f"✅ 긴급 분석 완료: {output_file}")

if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", required=True)
    parser.add_argument("--output", required=True)
    parser.add_argument("--lat", required=True)
    parser.add_argument("--lng", required=True)
    args = parser.parse_args()
    analyze_emergency(args.source, args.output, args.lat, args.lng)