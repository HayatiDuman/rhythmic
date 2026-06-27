package com.example.rhythmic

import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.io.IOException
import java.util.concurrent.TimeUnit

class OkHttpDownloader private constructor() : Downloader() {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    companion object {
        private var instance: OkHttpDownloader? = null

        fun getInstance(): OkHttpDownloader {
            if (instance == null) {
                instance = OkHttpDownloader()
            }
            return instance!!
        }
    }

    @Throws(IOException::class, ReCaptchaException::class)
    override fun execute(request: Request): Response {
        val url = request.url()
        val method = request.httpMethod()
        val requestHeaders = request.headers()

        // 🔥 HATA VEREN SATIR DÜZELTİLDİ: data() yerine dataToSend()
        val requestBodyBytes = request.dataToSend()

        // 1. NewPipe Başlıklarını OkHttp Başlıklarına Çevir
        val headersBuilder = Headers.Builder()
        requestHeaders?.forEach { (key, values) ->
            values.forEach { value ->
                headersBuilder.add(key, value)
            }
        }

        // Standart User-Agent ekleyerek YouTube'un istekleri reddetmesini engelliyoruz
        if (requestHeaders?.containsKey("User-Agent") == false) {
            headersBuilder.add("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
        }

        // 2. OkHttp İsteğini İnşa Et (GET veya POST)
        val okHttpRequestBuilder = okhttp3.Request.Builder()
            .url(url)
            .headers(headersBuilder.build())

        if (method.equals("POST", ignoreCase = true)) {
            // Byte dizisini (ByteArray) OkHttp'nin anlayacağı RequestBody'ye çeviriyoruz
            val body = requestBodyBytes?.toRequestBody() ?: ByteArray(0).toRequestBody()
            okHttpRequestBuilder.post(body)
        } else {
            okHttpRequestBuilder.get()
        }

        // 3. İsteği At ve Cevabı NewPipe'ın Anlayacağı Formata Çevir
        client.newCall(okHttpRequestBuilder.build()).execute().use { okHttpResponse ->
            val responseCode = okHttpResponse.code
            val responseMessage = okHttpResponse.message
            val responseBodyString = okHttpResponse.body?.string()

            // NewPipe'ın beklediği başlık haritasını oluştur
            val responseHeaders = mutableMapOf<String, List<String>>()
            okHttpResponse.headers.forEach { pair ->
                responseHeaders[pair.first] = listOf(pair.second)
            }

            // Eğer YouTube ReCAPTCHA duvarı ördüyse bunu NewPipe'a bildir
            if (responseCode == 429) {
                throw ReCaptchaException("YouTube rate limit / ReCAPTCHA tetiklendi.", url)
            }

            return Response(responseCode, responseMessage, responseHeaders, responseBodyString, url)
        }
    }
}