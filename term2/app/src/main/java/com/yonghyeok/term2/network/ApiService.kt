package com.yonghyeok.term2.network

import okhttp3.MultipartBody
import okhttp3.ResponseBody
import okhttp3.RequestBody
import retrofit2.Call
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part

interface ApiService {
    // 영상 업로드
    @Multipart
    @POST("analyze/video")
    fun uploadVideo(@Part video: MultipartBody.Part): Call<ResponseBody>

    // 이미지 업로드
    @Multipart
    @POST("analyze/image")
    fun uploadImage(@Part image: MultipartBody.Part): Call<ResponseBody>

    // ★ 피드백 전송 (여기를 확인하세요!)
    // wrongLabel과 correctLabel은 반드시 'RequestBody' 타입이어야 합니다.
    @Multipart
    @POST("feedback")
    fun sendFeedback(
        @Part image: MultipartBody.Part,
        @Part("wrong_label") wrongLabel: RequestBody,     // <--- 여기 확인
        @Part("correct_label") correctLabel: RequestBody  // <--- 여기 확인
    ): Call<ResponseBody>


    @Multipart
    @POST("analyze/emergency")
    fun sendEmergency(
        @Part image: MultipartBody.Part,
        @Part("lat") latitude: RequestBody,
        @Part("lng") longitude: RequestBody
    ): Call<ResponseBody>
}