package com.example.qgent.data.repository

import android.net.Uri
import android.util.Log
import com.example.qgent.data.SessionStore
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

/** 上传结果：attachmentId（IMAGE/FILE 消息 content 必填，契约 v0.1 §6.2）+ content.url 相对路径 */
data class UploadedAttachment(
    val attachmentId: String,
    val contentUrl: String
)

/**
 * 附件上传（代理直传契约，2026-08-21）：POST 创建凭证 → PUT 代理上传原始字节 → POST confirm。
 * - uploadUrl 为相对路径（/api/v1/projects/...），须拼 API base origin（勿用 BASE_URL+path 叠出双 /api/v1）；
 * - PUT/confirm 均需 Bearer + Idempotency-Key；create / PUT / confirm 各自独立 key
 *   （同键不同请求体 → 409 IDEMPOTENCY_KEY_REUSED），PUT 重试（401 刷新/网络中断）复用 PUT 自己的 key；
 * - PUT 成功为 204 无响应体，不解析 JSON。
 */
class AttachmentUploader(
    private val service: QgApiService,
    private val uploadClient: OkHttpClient
) {

    /** 上传附件：拿凭证 → PUT 直传 → 确认 → 返回 attachmentId + content.url 相对路径 */
    suspend fun upload(
        projectId: String,
        fileName: String,
        contentType: String?,
        sizeBytes: Long,
        bytes: ByteArray
    ): Result<UploadedAttachment> {
        // 失败定位用：当前执行到哪一步（create / put / confirm）
        var lastStep = "create"
        return try {
            // 三个写操作各自独立幂等键（同键不同请求体 → 409 IDEMPOTENCY_KEY_REUSED）
            val createKey = UUID.randomUUID().toString()
            val putKey = UUID.randomUUID().toString()
            val attachment = service.createAttachment(
                projectId,
                createKey,
                CreateAttachmentRequest(
                    fileName = fileName,
                    contentType = contentType,
                    sizeBytes = sizeBytes
                )
            ).toDataOrThrow()
            Log.d("AttachmentUploader", "STEP1 create ok attachmentId=${attachment.attachmentId}")

            // uploadUrl 为相对路径 /api/v1/projects/...（代理上传），拼 API base origin；
            // 兼容旧后端返回的完整预签名 URL
            val rawUrl = attachment.uploadUrl
                ?: throw ApiException("EMPTY_UPLOAD_URL", "附件直传地址为空")
            val uploadUrl = when {
                rawUrl.startsWith("http://") || rawUrl.startsWith("https://") ->
                    RetrofitClient.resolveMediaUrl(rawUrl)
                rawUrl.startsWith("/api/v1/") -> RetrofitClient.origin() + rawUrl
                else -> RetrofitClient.resolveMediaUrl(rawUrl)
            }
            Log.d("AttachmentUploader", "STEP2 put target=$uploadUrl headers=${attachment.headers}")

            withContext(Dispatchers.IO) {
                lastStep = "put"
                // 原始文件字节（不要包 JSON/base64）；Content-Type 由凭证 headers 回传（3.2/4.2）
                val body = bytes.toRequestBody(null)
                var response = putOnce(uploadUrl, body, attachment.headers, putKey)
                // access token 可能过期：REST 请求由 TokenAuthenticator 静默刷新，手动 PUT 无此机制 →
                // 401 时刷新 token（写回 SessionStore）后复用同一 PUT 幂等键重试（幂等安全，回放或幂等处理）
                if (response.code == 401 && RetrofitClient.refreshAccessToken()) {
                    response.close()
                    response = putOnce(uploadUrl, body, attachment.headers, putKey)
                }
                response.use { r ->
                    // 204 无响应体 = 成功（幂等回放同样返回 204），不解析 JSON
                    if (!r.isSuccessful) {
                        val detail = runCatching { r.body?.string()?.take(300) }.getOrNull().orEmpty()
                        Log.e("AttachmentUploader", "STEP2 put failed: ${r.code} $detail")
                        throw ApiException("UPLOAD_FAILED", putErrorMessage(r.code, detail))
                    }
                }
            }

            lastStep = "confirm"
            service.confirmAttachment(projectId, attachment.attachmentId, UUID.randomUUID().toString()).toDataOrThrow()
            Log.d("AttachmentUploader", "STEP3 confirm ok attachmentId=${attachment.attachmentId}")

            Result.success(
                UploadedAttachment(
                    attachmentId = attachment.attachmentId,
                    contentUrl = "/projects/$projectId/attachments/${attachment.attachmentId}/content"
                )
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            // 页面销毁/协程取消：保持取消语义向上传播（不转 failure、不弹「上传失败」）
            throw e
        } catch (e: Exception) {
            // 诊断：定位失败步骤 + 后端错误码/requestId（供联调排查）
            val api = e as? ApiException
            Log.e("AttachmentUploader", "upload FAILED: step=$lastStep code=${api?.code} requestId=${api?.requestId} msg=${e.message}")
            Result.failure(e)
        }
    }

    /** PUT 失败错误码 → 用户文案（§5 错误码表）；detail 保留便于排查 */
    private fun putErrorMessage(code: Int, detail: String): String = when {
        code == 413 -> "文件超过大小上限（默认 50MB）"
        detail.contains("ATTACHMENT_NOT_PENDING") -> "附件状态已变化，请重新上传"
        detail.contains("ATTACHMENT_NOT_FOUND") -> "附件不存在，请重新上传"
        detail.contains("IDEMPOTENCY_KEY_REUSED") -> "上传冲突，请重试"
        detail.contains("ATTACHMENT_TOO_LARGE") -> "文件超过大小上限（默认 50MB）"
        else -> "附件上传失败 ($code) $detail"
    }

    /** 执行一次 PUT 直传：后端代理直传带 Idempotency-Key + Bearer（401 刷新重试由调用方处理）；
     *  OSS 预签名直链不加任何自定义头（签名不含这些头会 SignatureDoesNotMatch）。 */
    private fun putOnce(
        uploadUrl: String,
        body: okhttp3.RequestBody,
        extraHeaders: Map<String, String>?,
        idempotencyKey: String
    ): okhttp3.Response {
        val builder = Request.Builder().url(uploadUrl).put(body)
        if (Uri.parse(uploadUrl).host == Uri.parse(RetrofitClient.BASE_URL).host) {
            builder.header("Idempotency-Key", idempotencyKey)
            SessionStore.accessToken()?.let { builder.header("Authorization", "Bearer $it") }
        }
        // 凭证声明的 Content-Type 等 header 按响应回传（3.1 headers 非空时）
        extraHeaders?.forEach { (name, value) -> builder.header(name, value) }
        return uploadClient.newCall(builder.build()).execute()
    }
}
