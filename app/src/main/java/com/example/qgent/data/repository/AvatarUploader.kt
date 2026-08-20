package com.example.qgent.data.repository

import android.util.Log
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
 * 签发直传凭证 → PUT 直传 OSS → confirm 确认并返回公共读 URL。
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

        val uploadUrl = credential.uploadUrl?.let { RetrofitClient.resolveMediaUrl(it) }
            ?: throw ApiException("EMPTY_UPLOAD_URL", "头像直传地址为空")
        val objectKey = credential.objectKey
            ?: throw ApiException("EMPTY_OBJECT_KEY", "头像直传对象键为空")
        Log.d("AvatarUploader", "uploadUrl=$uploadUrl objectKey=$objectKey")

        withContext(Dispatchers.IO) {
            // OSS V1 预签名 URL 的签名不含 Content-Type；PUT 带 Content-Type 会 SignatureDoesNotMatch（403），
            // 与附件上传保持一致：不设 Content-Type
            val body = bytes.toRequestBody(null)
            val builder = Request.Builder().url(uploadUrl).put(body)
            credential.headers?.forEach { (name, value) -> builder.header(name, value) }
            uploadClient.newCall(builder.build()).execute().use { response ->
                if (!response.isSuccessful) {
                    val detail = runCatching { response.body?.string()?.take(300) }.getOrNull().orEmpty()
                    throw ApiException("AVATAR_UPLOAD_FAILED", "头像上传失败 (${response.code}) $detail")
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
}
