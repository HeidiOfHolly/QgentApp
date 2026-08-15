package com.example.qgent.data.repository

import android.util.Log
import com.example.qgent.data.api.QgApiService
import com.example.qgent.data.api.RetrofitClient
import com.example.qgent.data.model.ApiException
import com.example.qgent.data.model.CreateAttachmentRequest
import com.example.qgent.data.model.toDataOrThrow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID

class AttachmentUploader(
    private val service: QgApiService,
    private val uploadClient: OkHttpClient
) {

    /** 上传附件：拿凭证 → PUT 直传 → 确认 → 返回 content.url 相对路径 */
    suspend fun upload(
        projectId: String,
        fileName: String,
        contentType: String?,
        sizeBytes: Long,
        bytes: ByteArray
    ): Result<String> = try {
        // Idempotency-Key 后端强制要求（防重复提交）；每次上传都是全新操作，直接生成新 UUID
        val attachment = service.createAttachment(
            projectId,
            UUID.randomUUID().toString(),
            CreateAttachmentRequest(
                fileName = fileName,
                contentType = contentType,
                sizeBytes = sizeBytes
            )
        ).toDataOrThrow()

        // uploadUrl 可能是相对路径（后端代理直传）或完整预签名 URL；相对路径拼 BASE_URL
        val uploadUrl = attachment.uploadUrl?.let { RetrofitClient.resolveMediaUrl(it) }
            ?: throw ApiException("EMPTY_UPLOAD_URL", "附件直传地址为空")
        Log.d("AttachmentUploader", "uploadUrl=$uploadUrl headers=${attachment.headers}")

        withContext(Dispatchers.IO) {
            // OSS V1 预签名 URL 的签名不含 Content-Type；PUT 若带 Content-Type 会导致
            // SignatureDoesNotMatch（403），这里刻意不设 Content-Type，与签名保持一致
            val body = bytes.toRequestBody(null)
            val builder = Request.Builder().url(uploadUrl).put(body)
            // 预签名 URL 若签名时绑定了额外 header，需按响应回填（当前后端返回 headers 恒空）
            attachment.headers?.forEach { (name, value) -> builder.header(name, value) }
            val request = builder.build()
            uploadClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    // 403 的 body 通常是 OSS 返回的 XML 错误（SignatureDoesNotMatch / AccessDenied），带上便于定位
                    val detail = runCatching { response.body?.string()?.take(300) }.getOrNull().orEmpty()
                    throw ApiException("UPLOAD_FAILED", "附件上传失败 (${response.code}) $detail")
                }
            }
        }

        service.confirmAttachment(projectId, attachment.attachmentId, UUID.randomUUID().toString()).toDataOrThrow()

        Result.success("/projects/$projectId/attachments/${attachment.attachmentId}/content")
    } catch (e: Exception) {
        Result.failure(e)
    }
}
