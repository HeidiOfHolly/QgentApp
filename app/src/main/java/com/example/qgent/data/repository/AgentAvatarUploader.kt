package com.example.qgent.data.repository

import android.util.Log
import com.example.qgent.data.api.QgApiService
import com.example.qgent.data.api.RetrofitClient
import com.example.qgent.data.model.ApiException
import com.example.qgent.data.model.AvatarConfirmRequest
import com.example.qgent.data.model.AvatarCredentialRequest
import com.example.qgent.data.model.toDataOrThrow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID

/**
 * Agent 头像上传（v2.0.6 §5.2）：credential → OSS 直传 → confirm → 公共读 URL。
 * 对象键 agents/{teamId}/{uuid}.{ext}，confirm 校验前缀与 teamId 匹配。
 */
class AgentAvatarUploader(
    private val service: QgApiService,
    private val uploadClient: OkHttpClient
) {

    /** 上传 Agent 头像，成功返回公共读头像 URL */
    suspend fun upload(teamId: String, mediaType: String, sizeBytes: Long, bytes: ByteArray): Result<String> = try {
        val credential = service.createAgentAvatarCredential(
            teamId,
            UUID.randomUUID().toString(),
            AvatarCredentialRequest(mediaType = mediaType, sizeBytes = sizeBytes)
        ).toDataOrThrow()

        val uploadUrl = credential.uploadUrl?.let { RetrofitClient.resolveMediaUrl(it) }
            ?: throw ApiException("EMPTY_UPLOAD_URL", "头像直传地址为空")
        val objectKey = credential.objectKey
            ?: throw ApiException("EMPTY_OBJECT_KEY", "头像直传对象键为空")
        Log.d("AgentAvatar", "uploadUrl=$uploadUrl objectKey=$objectKey")

        withContext(Dispatchers.IO) {
            val body = bytes.toRequestBody(null)
            val builder = Request.Builder().url(uploadUrl).put(body)
            // 凭证 headers 含 Content-Type 等（v2.0.6 §5.2 返回 headers），原样回填保持签名一致
            credential.headers?.forEach { (name, value) -> builder.header(name, value) }
            uploadClient.newCall(builder.build()).execute().use { response ->
                if (!response.isSuccessful) {
                    val detail = runCatching { response.body?.string()?.take(300) }.getOrNull().orEmpty()
                    throw ApiException("AGENT_AVATAR_UPLOAD_FAILED", "头像上传失败 (${response.code}) $detail")
                }
            }
        }

        val confirmed = service.confirmAgentAvatar(
            teamId,
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
