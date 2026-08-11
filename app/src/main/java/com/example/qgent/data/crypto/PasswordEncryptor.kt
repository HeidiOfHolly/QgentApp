package com.example.qgent.data.crypto

import android.util.Base64
import java.security.KeyFactory
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher

/**
 * 按接口文档 §4.1 对密码做 RSA 加密：
 * 使用平台下发的 RSA 公钥（PEM），算法 RSA/ECB/PKCS1Padding，输出 Base64 密文。
 * 客户端不得自行生成密钥，密钥与 keyId 一律来自 /auth/password-public-key。
 */
object PasswordEncryptor {

    private const val TRANSFORMATION = "RSA/ECB/PKCS1Padding"

    fun encrypt(publicKeyPem: String, plainPassword: String): String {
        val pem = publicKeyPem
            .replace("-----BEGIN PUBLIC KEY-----", "")
            .replace("-----END PUBLIC KEY-----", "")
            .replace("\\s".toRegex(), "")
        val decodedKey = Base64.decode(pem, Base64.DEFAULT)

        val publicKey = KeyFactory.getInstance("RSA")
            .generatePublic(X509EncodedKeySpec(decodedKey))

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, publicKey)
        val encrypted = cipher.doFinal(plainPassword.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(encrypted, Base64.NO_WRAP)
    }
}
