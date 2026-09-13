package com.yonghyeok.term2

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.yonghyeok.term2.databinding.ActivityFeedbackBinding
import com.yonghyeok.term2.network.RetrofitClient
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.io.File
import java.io.FileOutputStream

class FeedbackActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFeedbackBinding
    private var selectedImageUri: Uri? = null
    private val vehicleList = mutableListOf<String>() // classes.txt 내용을 담을 리스트

    // 갤러리 런처
    private val galleryLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            selectedImageUri = result.data?.data
            binding.ivFeedbackImage.setImageURI(selectedImageUri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFeedbackBinding.inflate(layoutInflater)
        setContentView(binding.root)

        loadClassList() // 1. classes.txt 읽어오기
        setupListeners()
    }

    // assets 폴더의 classes.txt를 읽어서 자동완성 리스트에 넣음
    private fun loadClassList() {
        try {
            assets.open("classes.txt").bufferedReader().useLines { lines ->
                lines.forEach { vehicleList.add(it) }
            }
            // 어댑터 연결 (검색 및 선택 가능하게 함)
            val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, vehicleList)
            binding.actvCorrectLabel.setAdapter(adapter)

        } catch (e: Exception) {
            Toast.makeText(this, "목록 로드 실패", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupListeners() {
        // 이미지 가져오기 버튼
        binding.btnPickImage.setOnClickListener {
            val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
            galleryLauncher.launch(intent)
        }

        // 전송 버튼
        binding.btnSendFeedback.setOnClickListener {
            val wrong = binding.etWrongLabel.text.toString()
            val correct = binding.actvCorrectLabel.text.toString()

            if (selectedImageUri == null) {
                Toast.makeText(this, "이미지를 선택해주세요.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (correct.isEmpty()) {
                Toast.makeText(this, "올바른 정보를 입력해주세요.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            sendFeedbackToServer(selectedImageUri!!, wrong, correct)
        }
    }

    private fun sendFeedbackToServer(uri: Uri, wrong: String, correct: String) {
        Toast.makeText(this, "전송 중...", Toast.LENGTH_SHORT).show()

        // 1. 파일 준비
        val file = uriToFile(uri)
        if (file == null) {
            Toast.makeText(this, "파일 변환 실패", Toast.LENGTH_SHORT).show()
            return
        }

        val requestFile = file.asRequestBody("image/*".toMediaTypeOrNull())
        val body = MultipartBody.Part.createFormData("file", file.name, requestFile)

        // 2. 텍스트 데이터 준비 (RequestBody로 변환)
        val wrongBody = wrong.toRequestBody("text/plain".toMediaTypeOrNull())
        val correctBody = correct.toRequestBody("text/plain".toMediaTypeOrNull())

        // 3. 서버 전송
        RetrofitClient.instance.sendFeedback(body, wrongBody, correctBody)
            .enqueue(object : Callback<ResponseBody> {
                override fun onResponse(call: Call<ResponseBody>, response: Response<ResponseBody>) {
                    if (response.isSuccessful) {
                        Toast.makeText(this@FeedbackActivity, "소중한 피드백 감사합니다!", Toast.LENGTH_LONG).show()
                        finish() // 성공하면 화면 닫기
                    } else {
                        Toast.makeText(this@FeedbackActivity, "서버 오류: ${response.code()}", Toast.LENGTH_SHORT).show()
                    }
                }

                override fun onFailure(call: Call<ResponseBody>, t: Throwable) {
                    Toast.makeText(this@FeedbackActivity, "전송 실패: ${t.message}", Toast.LENGTH_SHORT).show()
                }
            })
    }

    // Uri -> File 변환 유틸 함수
    private fun uriToFile(uri: Uri): File? {
        return try {
            val inputStream = contentResolver.openInputStream(uri)
            val tempFile = File.createTempFile("feedback", ".jpg", cacheDir)
            val outputStream = FileOutputStream(tempFile)
            inputStream?.copyTo(outputStream)
            inputStream?.close()
            outputStream.close()
            tempFile
        } catch (e: Exception) {
            null
        }
    }
}