package com.yonghyeok.term2

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.*
import android.os.Bundle
import android.util.Log
import android.util.Size
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.yonghyeok.term2.databinding.ActivityCameraBinding
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate

/**
 * 감지 결과 데이터 클래스
 */
data class DetectionResult(
    val rect: Rect,
    val label: String,
    val score: Float,
    val vehicleType: String = ""
)

class CameraActivity : AppCompatActivity() {
    private lateinit var binding: ActivityCameraBinding
    private lateinit var previewView: PreviewView
    private lateinit var vehicleDetector: Interpreter

    private lateinit var classifier: Interpreter
    private lateinit var classLabels: List<String>

    private var bestFrame: Bitmap? = null
    private var bestScore = 0f
    private var bestVehicleType = ""

    // --- 상 수 ---
    private val CAMERA_PERMISSION_CODE = 100
    private val YOLO_INPUT = 640 // YOLOv8n 모델 입력 크기
    private val CONFIDENCE_THRESHOLD = 0.5f // 확률 임계값 (너무 낮으면 쓰레기값 검출)
    private val NMS_IOU_THRESHOLD = 0.45f // NMS IoU 임계값 (겹침 제거 기준)

    // --- 카메라/추론 관련 ---
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private var frameCounter = 0
    // COCO 데이터셋 기준 차량 클래스 인덱스
    private val vehicleClassIndices = listOf(1, 2, 3, 5, 7) // Bicycle, Car, Motorcycle, Bus, Truck
    private val classNames = mapOf(
        1 to "Bicycle", 2 to "Car", 3 to "Motorcycle",
        5 to "Bus", 7 to "Truck"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCameraBinding.inflate(layoutInflater)
        setContentView(binding.root)
        previewView = binding.previewView
        requestCameraPermission()
    }

    private fun requestCameraPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            loadModels()
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_CODE)
        }
    }

    override fun onRequestPermissionsResult(req: Int, perm: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(req, perm, res)
        if (req == CAMERA_PERMISSION_CODE && res.isNotEmpty() && res[0] == PackageManager.PERMISSION_GRANTED) {
            loadModels()
            startCamera()
            return
        }
        Toast.makeText(this, "카메라 권한이 필요합니다.", Toast.LENGTH_LONG).show()
        finish()
    }

    // ---------------------------------------------------------------------------------------------
    // ## 모델 로드
    // ---------------------------------------------------------------------------------------------
    private fun loadFile(name: String): MappedByteBuffer {
        val fd = assets.openFd(name)
        val fis = FileInputStream(fd.fileDescriptor)
        return fis.channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.length)
    }

    private fun loadModels() {
        val compatList = CompatibilityList()
        val opts = Interpreter.Options()

        if (compatList.isDelegateSupportedOnThisDevice) {
            // 1. GPU 가속 지원 시: GPU Delegate 사용 (속도 대폭 향상)
            val delegateOptions = compatList.bestOptionsForThisDevice
            opts.addDelegate(GpuDelegate(delegateOptions))
            Log.d("MODEL_LOAD", "GPU Acceleration Enabled")
        } else {
            // 2. GPU 미지원 시: 기존 CPU 4쓰레드 + XNNPACK 사용
            opts.setNumThreads(4)
            opts.setUseXNNPACK(true)
            Log.d("MODEL_LOAD", "GPU not supported. Using CPU XNNPACK")
        }

        try {
            vehicleDetector = Interpreter(loadFile("vehicle_detect.tflite"), opts)
            classifier = Interpreter(loadFile("vehicle_class.tflite"), opts)
            classLabels = assets.open("classes.txt").bufferedReader().readLines()
            Toast.makeText(this, "모델 로딩 완료 (GPU 모드 확인)", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e("MODEL_LOAD", "Failed to load model: ${e.message}")
            Toast.makeText(this, "모델 로딩 실패: ${e.message}", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    // ---------------------------------------------------------------------------------------------
    // ## CameraX 설정
    // ---------------------------------------------------------------------------------------------
    @SuppressLint("UnsafeOptInUsageError")
    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)

        providerFuture.addListener({
            val cameraProvider = providerFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            val analysis = ImageAnalysis.Builder()
                .setTargetResolution(Size(1280, 720))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            analysis.setAnalyzer(cameraExecutor) { image ->
                frameCounter++
                if (frameCounter % 3 != 0) { // 성능을 위해 프레임 드랍 조절
                    image.close()
                    return@setAnalyzer
                }

                // 1) 전처리
                val rgb = yuv420ToBitmap(image)
                val rotated = rotateBitmap(rgb, image.imageInfo.rotationDegrees.toFloat())

                // 2) YOLO 차량 탐지 (수정된 로직)
                val yoloBoxes = detectVehicles(rotated)

                if (yoloBoxes.isEmpty()) {
                    runOnUiThread { binding.overlayView.setResults(emptyList(), rotated.width, rotated.height) }
                    image.close()
                    return@setAnalyzer
                }

                // 3) 가장 큰 차량 박스 선택
                val selectedBox = yoloBoxes.maxByOrNull { it.rect.width() * it.rect.height() }

                if (selectedBox == null) {
                    image.close()
                    return@setAnalyzer
                }

                // 4) 차종 분류 (EfficientNet)
                var vehicleType = "Unknown"
                var typeScore = 0f

                try {
                    // 좌표 안전장치
                    val safeRect = Rect(
                        max(0, selectedBox.rect.left),
                        max(0, selectedBox.rect.top),
                        min(rotated.width, selectedBox.rect.right),
                        min(rotated.height, selectedBox.rect.bottom)
                    )

                    if (safeRect.width() > 0 && safeRect.height() > 0) {
                        val crop = Bitmap.createBitmap(
                            rotated, safeRect.left, safeRect.top, safeRect.width(), safeRect.height()
                        )
                        val (name, score) = classifyVehicleType(crop)
                        vehicleType = name
                        typeScore = score
                    }
                } catch (e: Exception) {
                    Log.e("CLASSIFY_ERROR", "Crop/Classify failed: ${e.message}")
                }

                val finalResult = DetectionResult(
                    rect = selectedBox.rect,
                    label = "Vehicle",
                    score = typeScore,
                    vehicleType = vehicleType
                )

                // 5) 베스트샷 갱신
                if (typeScore > bestScore) {
                    bestScore = typeScore
                    bestVehicleType = vehicleType
                    bestFrame = rotated.copy(rotated.config ?: Bitmap.Config.ARGB_8888, true)
                }

                runOnUiThread {
                    binding.overlayView.setResults(listOf(finalResult), rotated.width, rotated.height)
                }
                image.close()
            }

            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
        }, ContextCompat.getMainExecutor(this))
    }

    // ---------------------------------------------------------------------------------------------
    // ## YOLOv8 추론 및 후처리 (핵심 수정 부분)
    // ---------------------------------------------------------------------------------------------
    private fun detectVehicles(bitmap: Bitmap): List<DetectionResult> {
        // --- 1. 전처리 (기존과 동일) ---
        val scaleW = YOLO_INPUT.toFloat() / bitmap.width.toFloat()
        val scaleH = YOLO_INPUT.toFloat() / bitmap.height.toFloat()
        val scale = min(scaleW, scaleH)
        val newWidth = (bitmap.width * scale).toInt()
        val newHeight = (bitmap.height * scale).toInt()

        val resized = Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
        val padded = Bitmap.createBitmap(YOLO_INPUT, YOLO_INPUT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(padded)
        canvas.drawColor(Color.BLACK)

        val offsetX = ((YOLO_INPUT - newWidth) / 2).toFloat()
        val offsetY = ((YOLO_INPUT - newHeight) / 2).toFloat()
        canvas.drawBitmap(resized, offsetX, offsetY, null)

        val input = ByteBuffer.allocateDirect(1 * YOLO_INPUT * YOLO_INPUT * 3 * 4)
        input.order(ByteOrder.nativeOrder())
        val arr = IntArray(YOLO_INPUT * YOLO_INPUT)
        padded.getPixels(arr, 0, YOLO_INPUT, 0, 0, YOLO_INPUT, YOLO_INPUT)

        for (px in arr) {
            input.putFloat(((px shr 16) and 0xFF) / 255f)
            input.putFloat(((px shr 8) and 0xFF) / 255f)
            input.putFloat((px and 0xFF) / 255f)
        }

        // --- 2. 모델 추론 ---
        val raw = Array(1) { Array(84) { FloatArray(8400) } }
        try {
            vehicleDetector.run(input, raw)
        } catch (e: Exception) {
            Log.e("YOLO_ERROR", "Run failed: ${e.message}")
            // 만약 여기서 에러가 나면 모델 shape가 [1, 8400, 84]일 수 있습니다.
            return emptyList()
        }

        // --- 3. 디버깅 및 디코딩 ---
        val candidates = mutableListOf<DetectionResult>()

        // [디버그] 첫 번째 앵커의 데이터 확인 (좌표 범위 및 점수 확인용)
        val firstCx = raw[0][0][0]
        val firstScore = raw[0][4][0]
        Log.d("YOLO_DEBUG", "First Anchor -> cx: $firstCx, class0_score: $firstScore")

        // [중요] 좌표가 정규화(0~1)되어 있는지 픽셀(0~640)인지 자동 감지
        // 중앙값 근처의 앵커 하나를 샘플링해서 확인
        val sampleCx = raw[0][0][4200]
        val isNormalized = sampleCx < 1.5f // 값이 1.5보다 작으면 0~1 정규화된 값으로 간주

        Log.d("YOLO_DEBUG", "Coordinate Format: ${if(isNormalized) "Normalized (0~1)" else "Pixels (0~640)"}")

        var maxScoreFound = 0f // 디버깅용: 전체 루프에서 발견된 가장 높은 점수

        for (i in 0 until 8400) {
            // 1) 클래스 점수 확인
            var maxScore = 0f
            var bestClass = -1

            // 전체 클래스 80개를 다 확인해봅니다 (혹시 클래스 인덱스가 다를 수 있으니)
            for (c in 0 until 80) {
                val score = raw[0][c + 4][i]
                if (score > maxScore) {
                    maxScore = score
                    bestClass = c
                }
            }

            if (maxScore > maxScoreFound) maxScoreFound = maxScore

            // 테스트를 위해 임계값을 낮춤 (0.5 -> 0.25)
            // 차량 클래스 인덱스(vehicleClassIndices)에 포함되는지 확인
            if (maxScore > 0.25f && bestClass in vehicleClassIndices) {

                var cx = raw[0][0][i]
                var cy = raw[0][1][i]
                var w = raw[0][2][i]
                var h = raw[0][3][i]

                // [보정] 만약 0~1 사이 값이라면 640을 곱해서 픽셀 좌표로 변환
                if (isNormalized) {
                    cx *= YOLO_INPUT
                    cy *= YOLO_INPUT
                    w *= YOLO_INPUT
                    h *= YOLO_INPUT
                }

                // 좌표 복원
                val x1 = ((cx - w / 2 - offsetX) / scale).toInt()
                val y1 = ((cy - h / 2 - offsetY) / scale).toInt()
                val x2 = ((cx + w / 2 - offsetX) / scale).toInt()
                val y2 = ((cy + h / 2 - offsetY) / scale).toInt()

                if (x1 < x2 && y1 < y2) {
                    val label = classNames[bestClass] ?: "Vehicle"
                    candidates.add(DetectionResult(Rect(x1, y1, x2, y2), label, maxScore))
                }
            }
        }

        Log.d("YOLO_DEBUG", "Max Score Found: $maxScoreFound, Candidates: ${candidates.size}")

        return applyNMS(candidates, NMS_IOU_THRESHOLD)
    }

    /**
     * NMS: 겹치는 박스 제거 (클래스 구분 없음 - 차량은 다 차량으로 취급)
     */
    private fun applyNMS(candidates: List<DetectionResult>, iouThreshold: Float): List<DetectionResult> {
        if (candidates.isEmpty()) return emptyList()

        // 점수 높은 순 정렬
        val sorted = candidates.sortedByDescending { it.score }.toMutableList()
        val results = mutableListOf<DetectionResult>()

        while (sorted.isNotEmpty()) {
            val best = sorted[0]
            results.add(best)
            sorted.removeAt(0)

            val iterator = sorted.iterator()
            while (iterator.hasNext()) {
                val current = iterator.next()
                // IoU가 높으면(많이 겹치면) 제거
                if (calculateIoU(best.rect, current.rect) > iouThreshold) {
                    iterator.remove()
                }
            }
        }
        return results
    }

    private fun calculateIoU(boxA: Rect, boxB: Rect): Float {
        val xA = max(boxA.left, boxB.left)
        val yA = max(boxA.top, boxB.top)
        val xB = min(boxA.right, boxB.right)
        val yB = min(boxA.bottom, boxB.bottom)

        val interArea = max(0, xB - xA) * max(0, yB - yA)
        val boxAArea = boxA.width() * boxA.height()
        val boxBArea = boxB.width() * boxB.height()

        val unionArea = boxAArea + boxBArea - interArea
        if (unionArea <= 0) return 0f

        return interArea.toFloat() / unionArea.toFloat()
    }

    // ---------------------------------------------------------------------------------------------
    // ## 유틸리티 (이미지 변환 등)
    // ---------------------------------------------------------------------------------------------
    private fun yuv420ToBitmap(image: ImageProxy): Bitmap {
        val yBuffer = image.planes[0].buffer // Y
        val uBuffer = image.planes[1].buffer // U
        val vBuffer = image.planes[2].buffer // V

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        // NV21 포맷으로 데이터를 합칩니다.
        val nv21 = ByteArray(ySize + uSize + vSize)

        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)

        val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
        val out = java.io.ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), 90, out)

        val imageBytes = out.toByteArray()
        return BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
    }

    private fun rotateBitmap(src: Bitmap, angle: Float): Bitmap {
        if (angle == 0f) return src
        val matrix = Matrix().apply { postRotate(angle) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
    }

    private fun classifyVehicleType(crop: Bitmap): Pair<String, Float> {
        // EfficientNet B4 input size (380 or 384 usually, confirm your model)
        val inputSize = 384
        val input = ByteBuffer.allocateDirect(1 * inputSize * inputSize * 3 * 4)
        input.order(ByteOrder.nativeOrder())

        val resized = Bitmap.createScaledBitmap(crop, inputSize, inputSize, true)
        val arr = IntArray(inputSize * inputSize)
        resized.getPixels(arr, 0, inputSize, 0, 0, inputSize, inputSize)

        for (px in arr) {
            val r = ((px shr 16) and 0xFF) / 255f
            val g = ((px shr 8) and 0xFF) / 255f
            val b = (px and 0xFF) / 255f
            // EfficientNet normalization
            input.putFloat((r - 0.485f) / 0.229f)
            input.putFloat((g - 0.456f) / 0.224f)
            input.putFloat((b - 0.406f) / 0.225f)
        }

        val output = Array(1) { FloatArray(classLabels.size) }
        classifier.run(input, output)

        val scores = output[0]
        var maxIdx = 0
        var maxScore = -1f
        for (i in scores.indices) {
            if (scores[i] > maxScore) {
                maxScore = scores[i]
                maxIdx = i
            }
        }
        return classLabels[maxIdx] to maxScore
    }
}