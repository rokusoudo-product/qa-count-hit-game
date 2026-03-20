package com.rokusoudo.hitokazu.data.api

import com.rokusoudo.hitokazu.BuildConfig
import com.rokusoudo.hitokazu.data.model.*
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.*

interface ApiService {

    @POST("rooms")
    suspend fun createRoom(@Body body: Map<String, String>): Response<CreateRoomResponse>

    @GET("rooms/{roomId}/qr")
    suspend fun getQr(@Path("roomId") roomId: String): Response<QrResponse>

    @POST("rooms/{roomId}/join")
    suspend fun joinRoom(
        @Path("roomId") roomId: String,
        @Body request: JoinRoomRequest
    ): Response<JoinRoomResponse>

    @POST("rooms/{roomId}/start")
    suspend fun startGame(@Path("roomId") roomId: String): Response<Map<String, Any>>

    @GET("questions")
    suspend fun getQuestions(): Response<Map<String, List<Question>>>

    @POST("rooms/{roomId}/answer")
    suspend fun submitAnswer(
        @Path("roomId") roomId: String,
        @Body request: SubmitAnswerRequest
    ): Response<Map<String, String>>

    @POST("rooms/{roomId}/prediction")
    suspend fun submitPrediction(
        @Path("roomId") roomId: String,
        @Body request: SubmitPredictionRequest
    ): Response<Map<String, String>>
}

object ApiClient {
    private val moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    private val client = OkHttpClient.Builder()
        .apply {
            if (BuildConfig.DEBUG) {
                addInterceptor(HttpLoggingInterceptor().apply {
                    level = HttpLoggingInterceptor.Level.BODY
                })
            }
        }
        .build()

    val service: ApiService = Retrofit.Builder()
        .baseUrl(BuildConfig.BASE_URL + "/")
        .client(client)
        .addConverterFactory(MoshiConverterFactory.create(moshi))
        .build()
        .create(ApiService::class.java)
}
