from flask import Flask, request, send_file
import os
import subprocess
import time
from datetime import datetime


app = Flask(__name__)

# 파일 저장 경로 설정
UPLOAD_FOLDER = 'uploads'
RESULT_FOLDER = 'results'

# 폴더가 없으면 생성
os.makedirs(UPLOAD_FOLDER, exist_ok=True)
os.makedirs(RESULT_FOLDER, exist_ok=True)

@app.route('/')
def home():
    return "AI Server is Running!"

# ----------------------------------------------------------
# 1. 영상 분석 엔드포인트 (/analyze/video)
# ----------------------------------------------------------
@app.route('/analyze/video', methods=['POST'])
def analyze_video():
    if 'file' not in request.files:
        return "No file part", 400
    
    file = request.files['file']
    if file.filename == '':
        return "No selected file", 400

    # 1. 업로드된 영상 저장 (절대 경로로 변환)
    filename = file.filename
    # os.path.abspath를 사용하여 C:\Users\... 식으로 전체 경로를 만듦
    input_path = os.path.abspath(os.path.join(UPLOAD_FOLDER, filename))
    
    # 결과 파일 경로도 절대 경로로 설정
    output_filename = "result_" + filename 
    output_path = os.path.abspath(os.path.join(RESULT_FOLDER, output_filename))
    
    # 파일 저장
    file.save(input_path)
    
    # ★ 디버깅용 로그: 실제로 파일이 있는지 확인
    if os.path.exists(input_path):
        print(f"[INFO] Video Saved at: {input_path}")
    else:
        print(f"[ERROR] File save failed!")
        return "Server Save Error", 500

    # 2. detect2.py 실행 (Subprocess)
    try:
        print(f"[INFO] Running detect2.py with source: {input_path}")
        
        # 명령어 구성
        command = [
            "python", "detect2.py",
            "--source", input_path,     # 절대 경로 전달
            "--output", output_path     # 절대 경로 전달 (코드에서 지원해야 함)
        ]
        
        # 실행
        result = subprocess.run(command, capture_output=True, text=True)
        
        # detect2.py의 출력 내용(로그)을 확인 (에러 찾기용)
        print("STDOUT:", result.stdout)
        print("STDERR:", result.stderr)

        if result.returncode != 0:
             print(f"[ERROR] detect2.py execution failed")
             return "Analysis Script Error", 500

    except Exception as e:
        print(f"[ERROR] Subprocess Failed: {e}")
        return "Analysis Failed", 500

    # 3. 결과 파일 반환
    if os.path.exists(output_path):
        print(f"[INFO] Sending result: {output_path}")
        return send_file(output_path, mimetype='video/mp4')
    else:
        # 만약 detect2.py가 저장한 파일명이 다르다면 여기서 확인 필요
        print(f"[ERROR] Result file not found at: {output_path}")
        # 혹시 모르니 result 폴더의 파일 목록을 출력해봄
        print(f"[DEBUG] Files in results folder: {os.listdir(RESULT_FOLDER)}")
        return "Result file not found", 500
# ----------------------------------------------------------
# 2. 이미지 분석 엔드포인트 (/analyze/image) -> detectimg.py 실행
# ----------------------------------------------------------
@app.route('/analyze/image', methods=['POST'])
def analyze_image():
    if 'file' not in request.files:
        return "No file part", 400
    
    file = request.files['file']
    if file.filename == '':
        return "No selected file", 400

    # 1. 업로드된 이미지 저장
    filename = file.filename # 예: image.jpg
    input_path = os.path.join(UPLOAD_FOLDER, filename)
    
    output_filename = "result_" + filename
    output_path = os.path.join(RESULT_FOLDER, output_filename)

    file.save(input_path)
    print(f"[INFO] Image Uploaded: {input_path}")

    # 2. detectimg.py 실행
    # ★ 중요: detectimg.py 실행 명령어에 맞게 수정하세요.
    try:
        print("[INFO] Starting Image Analysis...")
        
        command = [
            "python", "detectimg.py",
            "--source", input_path,
            "--output", output_path
        ]
        
        subprocess.run(command, check=True)
        print(f"[INFO] Analysis Complete: {output_path}")

    except subprocess.CalledProcessError as e:
        print(f"[ERROR] Analysis Failed: {e}")
        return "Analysis Failed", 500

    # 3. 결과 파일 반환
    if os.path.exists(output_path):
        return send_file(output_path, mimetype='image/jpeg')
    else:
        print(f"[ERROR] Result file not found")
        return "Result file not found", 500


# ----------------------------------------------------------
# 3. 피드백(오류 신고) 받기 (/feedback)
# ----------------------------------------------------------
FEEDBACK_FOLDER = 'feedback_data'
os.makedirs(FEEDBACK_FOLDER, exist_ok=True)

@app.route('/feedback', methods=['POST'])
def feedback():
    # 1. 필수 데이터 확인
    if 'file' not in request.files:
        return "No file part", 400
    
    file = request.files['file']
    wrong_label = request.form.get('wrong_label', 'Unknown')   # AI가 판단한 값
    correct_label = request.form.get('correct_label', 'Unknown') # 사용자가 입력한 정답

    if file.filename == '':
        return "No selected file", 400

    # 2. 파일명 생성 (타임스탬프 사용)
    timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
    filename = f"feedback_{timestamp}.jpg"
    txt_filename = f"feedback_{timestamp}.txt"

    # 3. 이미지 저장
    img_save_path = os.path.join(FEEDBACK_FOLDER, filename)
    file.save(img_save_path)

    # 4. 텍스트 정보 저장
    txt_save_path = os.path.join(FEEDBACK_FOLDER, txt_filename)
    with open(txt_save_path, 'w', encoding='utf-8') as f:
        f.write(f"Image: {filename}\n")
        f.write(f"AI Prediction (Wrong): {wrong_label}\n")
        f.write(f"User Correction (Right): {correct_label}\n")
        f.write(f"Date: {timestamp}\n")

    print(f"[FEEDBACK] Saved: {wrong_label} -> {correct_label}")
    return "Feedback Saved", 200

# ----------------------------------------------------------
# 4. 긴급 신고 (이미지 전용 - GPS 제거 버전)
# ----------------------------------------------------------
@app.route('/analyze/emergency', methods=['POST'])
def analyze_emergency_route():
    if 'file' not in request.files: return "No file part", 400
    file = request.files['file']
    if file.filename == '': return "No selected file", 400

    # ★ 수정됨: 안드로이드에서 GPS를 안 보내므로, 서버가 임의로 설정
    lat = "37.5665" # 예: 서울 (임시 좌표)
    lng = "126.9780"
    print(f"[EMERGENCY] Image received. Skipping GPS. using default: {lat}, {lng}")

    filename = "emergency_" + file.filename
    input_path = os.path.abspath(os.path.join(UPLOAD_FOLDER, filename))
    output_filename = "result_" + filename
    output_path = os.path.abspath(os.path.join(RESULT_FOLDER, output_filename))

    file.save(input_path)

    try:
        # emergency_detect.py는 위도/경도를 인자로 꼭 필요로 하므로, 가짜 값을 넣어줌
        command = [
            "python", "emergency_detect.py",
            "--source", input_path,
            "--output", output_path,
            "--lat", lat,
            "--lng", lng
        ]
        subprocess.run(command, check=True)
        print(f"[INFO] Emergency Analysis Complete: {output_path}")

    except subprocess.CalledProcessError as e:
        print(f"[ERROR] Emergency Analysis Failed: {e}")
        return "Analysis Failed", 500

    if os.path.exists(output_path):
        return send_file(output_path, mimetype='image/jpeg')
    else:
        return "Result file not found", 500
if __name__ == '__main__':
    # host='0.0.0.0'으로 해야 외부(스마트폰)에서 접속 가능
    # port=8000 (안드로이드 코드와 일치시켜야 함)
    app.run(host='0.0.0.0', port=8000, debug=True)