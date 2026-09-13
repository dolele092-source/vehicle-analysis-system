import tensorflow as tf
import numpy as np

# --- 파일 경로를 모델 이름으로 설정합니다 ---
# vehicle_detect.tflite 파일이 이 스크립트와 같은 폴더에 있어야 합니다.
model_path = "yolov8n_float16.tflite"

try:
    # TFLite 인터프리터 로드
    interpreter = tf.lite.Interpreter(model_path=model_path)
    interpreter.allocate_tensors()

    print("="*50)
    print("TFLite 모델 정보")
    print("="*50)

    # 입력 정보 확인
    input_details = interpreter.get_input_details()
    print("✅ INPUT:")
    for detail in input_details:
        print(f"  - Name: {detail['name']}, Shape: {detail['shape']}, Dtype: {detail['dtype']}")

    # 출력 정보 확인 (가장 중요한 부분)
    output_details = interpreter.get_output_details()
    print("\n✅ OUTPUT (가장 중요):")
    for detail in output_details:
        # 출력 형태(Shape)를 명확하게 표시
        output_shape_str = np.array2string(detail['shape'], separator=', ')
        print(f"  - Name: {detail['name']}")
        print(f"  - Shape: {output_shape_str}")
        print(f"  - Dtype: {detail['dtype']}")

    print("="*50)

except FileNotFoundError:
    print(f"❌ 오류: '{model_path}' 파일을 찾을 수 없습니다. 경로를 확인해 주세요.")
except Exception as e:
    print(f"❌ 오류 발생: {e}")