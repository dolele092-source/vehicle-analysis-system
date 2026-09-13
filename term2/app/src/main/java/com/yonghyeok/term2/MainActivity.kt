package com.yonghyeok.term2

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.FileProvider
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.yonghyeok.term2.databinding.ActivityMainBinding
import com.yonghyeok.term2.network.RetrofitClient
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    // --- 위치 및 카메라 관련 변수 ---
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var currentLocation: Location? = null
    private var photoUri: Uri? = null // 카메라로 찍은 사진 저장용 URI

    // --- 갤러리 런처 (영상용) ---
    private val videoPickerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri = result.data?.data
            if (uri != null) {
                uploadFile(uri, isVideo = true)
            }
        }
    }

    // --- 갤러리 런처 (이미지용) ---
    private val imagePickerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri = result.data?.data
            if (uri != null) {
                uploadFile(uri, isVideo = false)
            }
        }
    }

    // --- 카메라 런처 (긴급 신고용) ---
    private val takePictureLauncher = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success && photoUri != null) {
            // 촬영 성공 시 서버로 전송 (이미지 + 위치정보)
            uploadEmergencyFile(photoUri!!)
        } else {
            Toast.makeText(this, "사진 촬영이 취소되었습니다.", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 위치 서비스 초기화
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        setupListeners()
    }

    private fun setupListeners() {
        // 1. On-Device Camera (실시간 탐지)
        binding.cardOnDevice.setOnClickListener {
            startActivity(Intent(this, CameraActivity::class.java))
        }

        // 2. Server Analysis - Video (Gallery)
        binding.btnSelectGallery.setOnClickListener {
            val intent = Intent(Intent.ACTION_PICK, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            intent.type = "video/*"
            videoPickerLauncher.launch(intent)
        }

        // 3. Server Analysis - Image (Gallery)
        binding.cardImageTracking.setOnClickListener {
            val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
            intent.type = "image/*"
            imagePickerLauncher.launch(intent)
        }

        // 4. 피드백 버튼 (잘못된 정보 수정)
        binding.cardCorrectInfo.setOnClickListener {
            val intent = Intent(this, FeedbackActivity::class.java)
            startActivity(intent)
        }

        // 5. 긴급 신고 (SOS) 버튼 -> 위치 파악 후 카메라 실행
        binding.btnEmergency.setOnClickListener {
            startEmergencyProcess()
        }
    }

    // ------------------------------------------------------------------------
    // ## 긴급 신고 프로세스 (권한 확인 -> 위치 확보 -> 카메라 -> 전송)
    // ------------------------------------------------------------------------
    private fun startEmergencyProcess() {
        // 1. 위치 권한 확인
        if (ActivityCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_COARSE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(this, "위치 권한을 허용해주세요.", Toast.LENGTH_LONG).show()
            // 실제 앱에서는 여기서 requestPermissions를 호출해야 함
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                1001
            )
            return
        }

        Toast.makeText(this, "🚨 위치 확인 중...", Toast.LENGTH_SHORT).show()

        // 2. 현재 위치 가져오기
        fusedLocationClient.lastLocation.addOnSuccessListener { location: Location? ->
            currentLocation = location
            if (currentLocation != null) {
                // 위치 확보 성공 -> 카메라 실행
                launchCamera()
            } else {
                Toast.makeText(this, "위치를 찾을 수 없습니다. GPS를 확인해주세요.", Toast.LENGTH_SHORT).show()
                // 위치가 없더라도 촬영은 진행하고 싶다면 여기서 launchCamera() 호출 가능
            }
        }.addOnFailureListener {
            Toast.makeText(this, "위치 정보 오류: ${it.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun launchCamera() {
        try {
            val photoFile = File.createTempFile("SOS_", ".jpg", cacheDir)
            photoUri = FileProvider.getUriForFile(this, "$packageName.provider", photoFile)



            // [수정 후] !! 추가 (null이 아님을 보장)
            if (photoUri != null) {
                takePictureLauncher.launch(photoUri!!)
            }

        } catch (e: Exception) {
            Toast.makeText(this, "카메라 실행 실패: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun uploadEmergencyFile(uri: Uri) {
        Toast.makeText(this, "🚨 긴급 분석 요청 전송 중...", Toast.LENGTH_LONG).show()

        // 1. 파일 변환
        val file = uriToFile(uri, isVideo = false)
        if (file == null) {
            Toast.makeText(this, "파일 처리 실패", Toast.LENGTH_SHORT).show()
            return
        }

        val requestFile = file.asRequestBody("image/jpeg".toMediaTypeOrNull())
        val body = MultipartBody.Part.createFormData("file", file.name, requestFile)

        // 2. GPS 데이터 준비
        val latStr = (currentLocation?.latitude ?: 0.0).toString()
        val lngStr = (currentLocation?.longitude ?: 0.0).toString()

        val latBody = latStr.toRequestBody("text/plain".toMediaTypeOrNull())
        val lngBody = lngStr.toRequestBody("text/plain".toMediaTypeOrNull())

        // 3. 서버 전송
        RetrofitClient.instance.sendEmergency(body, latBody, lngBody)
            .enqueue(object : Callback<ResponseBody> {
                override fun onResponse(call: Call<ResponseBody>, response: Response<ResponseBody>) {
                    if (response.isSuccessful && response.body() != null) {
                        val savedFile = saveResponseToFile(response.body()!!, isVideo = false)
                        if (savedFile != null) {
                            Toast.makeText(this@MainActivity, "🚨 긴급 분석 완료!", Toast.LENGTH_SHORT).show()
                            openFile(savedFile, isVideo = false)
                        }
                    } else {
                        Toast.makeText(this@MainActivity, "서버 오류: ${response.code()}", Toast.LENGTH_SHORT).show()
                    }
                    // 전송 후 원본 임시 파일 삭제 (선택 사항)
                    file.delete()
                }

                override fun onFailure(call: Call<ResponseBody>, t: Throwable) {
                    Toast.makeText(this@MainActivity, "전송 실패: ${t.message}", Toast.LENGTH_SHORT).show()
                    file.delete()
                }
            })
    }


    // ------------------------------------------------------------------------
    // ## 기존 서버 업로드 로직 (갤러리용)
    // ------------------------------------------------------------------------
    private fun uploadFile(uri: Uri, isVideo: Boolean) {
        Toast.makeText(this, "서버로 전송 중입니다... 잠시만 기다려주세요.", Toast.LENGTH_LONG).show()

        val file = uriToFile(uri, isVideo) ?: return
        val mediaType = if (isVideo) "video/mp4".toMediaTypeOrNull() else "image/jpeg".toMediaTypeOrNull()
        val requestBody = file.asRequestBody(mediaType)
        val body = MultipartBody.Part.createFormData("file", file.name, requestBody)

        val call = if (isVideo) {
            RetrofitClient.instance.uploadVideo(body)
        } else {
            RetrofitClient.instance.uploadImage(body)
        }

        call.enqueue(object : Callback<ResponseBody> {
            override fun onResponse(call: Call<ResponseBody>, response: Response<ResponseBody>) {
                if (response.isSuccessful && response.body() != null) {
                    val savedFile = saveResponseToFile(response.body()!!, isVideo)
                    if (savedFile != null) {
                        Toast.makeText(this@MainActivity, "분석 완료!", Toast.LENGTH_SHORT).show()
                        openFile(savedFile, isVideo)
                    } else {
                        Toast.makeText(this@MainActivity, "파일 저장 실패", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(this@MainActivity, "서버 오류: ${response.code()}", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onFailure(call: Call<ResponseBody>, t: Throwable) {
                Toast.makeText(this@MainActivity, "네트워크 오류: ${t.message}", Toast.LENGTH_SHORT).show()
                Log.e("NetworkError", t.message.toString())
            }
        })
    }

    // ------------------------------------------------------------------------
    // ## 유틸리티 함수 (파일 변환 및 저장)
    // ------------------------------------------------------------------------

    /** Uri에서 임시 파일을 생성하여 반환 */
    private fun uriToFile(uri: Uri, isVideo: Boolean): File? {
        return try {
            val inputStream = contentResolver.openInputStream(uri)
            val suffix = if (isVideo) ".mp4" else ".jpg"
            val tempFile = File.createTempFile("upload", suffix, cacheDir)
            val outputStream = FileOutputStream(tempFile)
            inputStream?.copyTo(outputStream)
            inputStream?.close()
            outputStream.close()
            tempFile
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /** 서버 응답(ResponseBody)을 로컬 파일로 저장 */
    private fun saveResponseToFile(body: ResponseBody, isVideo: Boolean): File? {
        return try {
            val suffix = if (isVideo) "_result.mp4" else "_result.jpg"
            val file = File(getExternalFilesDir(null), "analyzed$suffix") // 저장 경로

            val inputStream = body.byteStream()
            val outputStream = FileOutputStream(file)

            inputStream.use { input ->
                outputStream.use { output ->
                    input.copyTo(output)
                }
            }
            file
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /** 저장된 파일을 기본 앱으로 실행 */
    private fun openFile(file: File, isVideo: Boolean) {
        val uri = FileProvider.getUriForFile(this, "$packageName.provider", file)
        val mimeType = if (isVideo) "video/*" else "image/*"

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType)
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "파일을 열 수 있는 앱이 없습니다.", Toast.LENGTH_SHORT).show()
        }
    }
}