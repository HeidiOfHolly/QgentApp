package com.example.qgent.data.repository

import android.net.Uri
import android.util.Log
import com.example.qgent.data.SessionStore
import com.example.qgent.data.api.QgApiService
import com.example.qgent.data.api.RetrofitClient
import com.example.qgent.data.model.ApiException
import com.example.qgent.data.model.ApiResponse
import com.example.qgent.data.model.AvatarConfirmRequest
import com.example.qgent.data.model.AvatarConfirmResponse
import com.example.qgent.data.model.AvatarCredentialRequest
import com.example.qgent.data.model.AvatarCredentialResponse
import com.example.qgent.data.model.toDataOrThrow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.Response
import java.util.UUID

/**
 * 头像上传（§7.0 /me/avatar；§28.1 团队头像；§31.1 项目头像）：
 * 签发直传凭证 → PUT 上传 → confirm 确认并返回公共读 URL。
 * 兼容 OSS 预签名直传和后端返回 /api/v1/... 的鉴权代理上传地址。
 * credential/confirm 由调用方注入（用户/团队/项目各自的端点），本类只做通用流程。
 * OSS 未启用（本地/CI）时 credential/confirm 返回 501 AVATAR_STORAGE_NOT_CONFIGURED，
 * 调用方按「头像上传暂不可用」提示。
 */
class AvatarUploader(
    private val service: QgApiService,
    private val uploadClient: OkHttpClient
) {

    /** 用户头像上传（/me/avatar，既有入口） */
    suspend fun upload(mediaType: String, sizeBytes: Long, bytes: ByteArray): Result<String> =
        uploadFor(mediaType, sizeBytes, bytes, service::createAvatarCredential, service::confirmAvatar)

    /** 通用头像上传：credential/confirm 由调用方指定端点（团队/项目/Agent 等） */
    suspend fun uploadFor(
        mediaType: String,
        sizeBytes: Long,
        bytes: ByteArray,
        createCredential: suspend (idempotencyKey: String, body: AvatarCredentialRequest) -> Response<ApiResponse<AvatarCredentialResponse>>,
        confirm: suspend (idempotencyKey: String, body: AvatarConfirmRequest) -> Response<ApiResponse<AvatarConfirmResponse>>
    ): Result<String> = try {
        val credential = createCredential(
            UUID.randomUUID().toString(),
            AvatarCredentialRequest(mediaType = mediaType, sizeBytes = sizeBytes)
        ).toDataOrThrow()

        val rawUploadUrl = credential.uploadUrl
            ?: throw ApiException("EMPTY_UPLOAD_URL", "头像直传地址为空")
        val uploadUrl = resolveUploadUrl(rawUploadUrl)
        val objectKey = credential.objectKey
            ?: throw ApiException("EMPTY_OBJECT_KEY", "头像直传对象键为空")
        Log.d("AvatarUploader", "uploadUrl=$uploadUrl objectKey=$objectKey")

        withContext(Dispatchers.IO) {
            val body = bytes.toRequestBody(null)
            val putKey = UUID.randomUUID().toString()
            var response = putOnce(uploadUrl, body, credential.headers, putKey)
            // uploadClient 不含 TokenAuthenticator；代理上传遇到过期 token 时手动刷新后重试。
            if (response.code == 401 && RetrofitClient.refreshAccessToken()) {
                response.close()
                response = putOnce(uploadUrl, body, credential.headers, putKey)
            }
            response.use { result ->
                if (!result.isSuccessful) {
                    val detail = runCatching { result.body?.string()?.take(300) }.getOrNull().orEmpty()
                    throw ApiException("AVATAR_UPLOAD_FAILED", "头像上传失败 (${result.code}) $detail")
                }
            }
        }

        val confirmed = confirm(
            UUID.randomUUID().toString(),
            AvatarConfirmRequest(objectKey)
        ).toDataOrThrow()
        val avatarUrl = confirmed.avatarUrl
            ?: throw ApiException("EMPTY_AVATAR_URL", "头像地址为空")
        Result.success(avatarUrl)
    } catch (e: Exception) {
        Result.failure(e)
    }

    private fun resolveUploadUrl(rawUrl: String): String = when {
        rawUrl.startsWith("http://") || rawUrl.startsWith("https://") ->
            RetrofitClient.resolveMediaUrl(rawUrl)
        rawUrl.startsWith("/api/v1/") -> RetrofitClient.origin() + rawUrl
        else -> RetrofitClient.resolveMediaUrl(rawUrl)
    }

    private fun putOnce(
        uploadUrl: String,
        body: okhttp3.RequestBody,
        extraHeaders: Map<String, String>?,
        idempotencyKey: String
    ): okhttp3.Response {
        val builder = Request.Builder().url(uploadUrl).put(body)
        if (Uri.parse(uploadUrl).authority == Uri.parse(RetrofitClient.BASE_URL).authority) {
            builder.header("Idempotency-Key", idempotencyKey)
            SessionStore.accessToken()?.let { builder.header("Authorization", "Bearer $it") }
        }
        extraHeaders?.forEach { (name, value) -> builder.header(name, value) }
        return uploadClient.newCall(builder.build()).execute()
    }
}
