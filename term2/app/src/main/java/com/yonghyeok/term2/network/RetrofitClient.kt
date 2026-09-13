package com.yonghyeok.term2.network

import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit  // ★ 필수 import

object RetrofitClient {
    // 본인 PC IP 주소 (그대로 유지)
    private const val BASE_URL = "http://192.168.35.141:8000/"

    // ★ 타임아웃 시간을 5분(300초)으로 대폭 늘림
    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(600, TimeUnit.SECONDS) // 서버 연결 대기 시간
        .readTimeout(600, TimeUnit.SECONDS)    // ★ 가장 중요: 서버가 분석 끝낼 때까지 기다리는 시간
        .writeTimeout(600, TimeUnit.SECONDS)   // 대용량 영상 업로드 시간
        .build()

    val instance: ApiService by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(okHttpClient) // ★ 위에서 만든 클라이언트를 여기에 장착
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ApiService::class.java)
    }
}