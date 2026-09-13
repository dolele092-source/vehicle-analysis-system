import tensorflow as tf
import numpy as np
import cv2
from PIL import Image
import os

# --- 설정 ---
# 변환된 모델 파일 경로
MODEL_PATH = 'vehicle_classifier.tflite'
# 라벨 리스트 파일 경로
LABEL_PATH = 'classes.txt'
# 테스트할 이미지 경로
IMAGE_PATH = 'test_car.jpg'  # 본인의 이미지 경로로 수정하세요

# --- 1. 라벨 로드 ---
print("1. 라벨 리스트 로드 중...")
with open(LABEL_PATH, 'r', encoding='utf-8') as f:
    labels = [line.strip() for line in f.readlines()]
print(f"총 {len(labels)}개의 클래스가 로드되었습니다.")

# --- 2. TFLite 모델 로드 ---
print("2. TFLite 모델 로드 중...")
# TFLite 인터프리터 생성
interpreter = tf.lite.Interpreter(model_path=MODEL_PATH)
interpreter.allocate_tensors()

# 입력 및 출력 텐서 정보 가져오기
input_details = interpreter.get_input_details()
output_details = interpreter.get_output_details()

# 모델이 기대하는 입력 크기 확인 (예: 384x384)
input_shape = input_details[0]['shape']
target_height = input_shape[2] if input_shape[1] == 3 else input_shape[1]
target_width = input_shape[3] if input_shape[1] == 3 else input_shape[2]

print(f"모델 입력 형태: {input_shape} (H:{target_height}, W:{target_width})")

# --- 3. 이미지 전처리 (학습 때와 동일해야 함!) ---
print(f"3. 이미지 전처리 중... ({IMAGE_PATH})")

# OpenCV로 이미지 읽기
img_cv = cv2.imread(IMAGE_PATH)
if img_cv is None:
    print("오류: 이미지를 찾을 수 없습니다.")
    exit()

# BGR -> RGB 변환 및 PIL 이미지로 변환
img_rgb = cv2.cvtColor(img_cv, cv2.COLOR_BGR2RGB)
img_pil = Image.fromarray(img_rgb)

# 리사이즈 (384x384)
img_resized = img_pil.resize((target_width, target_height))

# 정규화 (Normalize) - PyTorch와 동일한 방식 필수!
# 값 범위: 0~255 -> 0.0~1.0
input_data = np.array(img_resized, dtype=np.float32) / 255.0

# 표준화 (Mean, Std 적용)
mean = np.array([0.485, 0.456, 0.406], dtype=np.float32)
std = np.array([0.229, 0.224, 0.225], dtype=np.float32)
input_data = (input_data - mean) / std

# 차원 변환 (H, W, C) -> (1, H, W, C) 또는 (1, C, H, W)
# TFLite는 보통 NHWC (Batch, Height, Width, Channel)를 선호하지만,
# 변환 과정에 따라 NCHW일 수도 있으므로 shape을 보고 결정합니다.
if input_shape[1] == 3: # NCHW 형식인 경우 (1, 3, 384, 384)
    input_data = input_data.transpose(2, 0, 1) # (H, W, C) -> (C, H, W)

input_data = np.expand_dims(input_data, axis=0) # 배치 차원 추가

# --- 4. 추론 (Inference) ---
print("4. 추론 실행 중...")
# 입력 데이터 설정
interpreter.set_tensor(input_details[0]['index'], input_data)

# 실행
interpreter.invoke()

# 출력 데이터 가져오기
output_data = interpreter.get_tensor(output_details[0]['index'])

# --- 5. 결과 해석 ---
# Softmax 확률 계산 (만약 모델 출력에 Softmax가 포함 안 되어 있다면)
def softmax(x):
    e_x = np.exp(x - np.max(x))
    return e_x / e_x.sum(axis=1)

# 이미 확률값인지 로짓(Logit)인지 확인 후 처리
if np.max(output_data) > 1.0 or np.min(output_data) < 0.0:
    probabilities = softmax(output_data)
else:
    probabilities = output_data

# 가장 높은 확률의 인덱스 찾기
predicted_idx = np.argmax(probabilities)
confidence = probabilities[0][predicted_idx] * 100
predicted_label = labels[predicted_idx]

print("\n" + "="*30)
print(f"▶ 예측 결과: {predicted_label}")
print(f"▶ 신뢰도: {confidence:.2f}%")
print("="*30)

# --- 6. 결과 보여주기 (창 띄우기) ---
# 결과 텍스트를 이미지에 쓰기
text = f"{predicted_label} ({confidence:.1f}%)"
cv2.putText(img_cv, text, (10, 30), cv2.FONT_HERSHEY_SIMPLEX,
            0.7, (0, 255, 0), 2)

cv2.imshow("TFLite Result", img_cv)
print("아무 키나 누르면 종료됩니다.")
cv2.waitKey(0)
cv2.destroyAllWindows()