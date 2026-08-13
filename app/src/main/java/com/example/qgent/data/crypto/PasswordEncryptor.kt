package com.example.qgent.data.crypto

import android.util.Base64
import android.util.Log
import java.security.KeyFactory
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher

/**
 * 使用固定 RSA 公钥对密码加密（RSA/ECB/PKCS1Padding，输出 Base64）。
 *
 * 公钥为 X.509 SubjectPublicKeyInfo PEM 格式，直接加载。
 * keyId 固定为 "rsa-2026-08"。
 */
object PasswordEncryptor {

    const val KEY_ID = "rsa-2026-08"

    private const val TRANSFORMATION = "RSA/ECB/PKCS1Padding"

    // 固定 RSA 2048 位公钥（X.509 SubjectPublicKeyInfo，392 字符 Base64）
    private val x509Base64 = "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAttIDG9u/Ag+1odUe5brC" +
        "+54CFdFfHrjuC15piR73qx2tOquiRboEUB2Zsq681ABEtaUS2bk1ihjm2kjoHOCw" +
        "AZgXM2WSS+pAl5yTcfpGY33mpqQGKwgDerUEhMgEReXD6JSoGRR3vV6CDVmrGbrw" +
        "4Jhvjbi3WW3bKsEtFtwtF79wbHOp5HuzIIMY7l7yRljUF7wl1Hnwsc+VfYPnd1KN" +
        "T0pR7b4z8xOHM7F6rq/jQ1ByEsA4Stntx8WJE67C4seMyTOxY5EbVriR4x0lstUL" +
        "jzw8Qkuqu4fOAbVn4+JpfH9CjqvBNecHPwyg+E3scliNZYXywo7eMWS1RjkHPcXn" +
        "nwIDAQAB"

    private val publicKey by lazy {
        Log.d("PasswordEncryptor", "publicKey init, base64 length=${x509Base64.length}")
        val x509Bytes = Base64.decode(x509Base64, Base64.DEFAULT)
        Log.d("PasswordEncryptor", "decoded ${x509Bytes.size} bytes")
        val key = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(x509Bytes))
        Log.d("PasswordEncryptor", "publicKey loaded, algorithm=${key.algorithm}")
        key
    }

    fun encrypt(plainPassword: String): String {
        Log.d("PasswordEncryptor", "encrypt input length=${plainPassword.length}")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, publicKey)
        val encrypted = cipher.doFinal(plainPassword.toByteArray(Charsets.UTF_8))
        val result = Base64.encodeToString(encrypted, Base64.NO_WRAP)
        Log.d("PasswordEncryptor", "encrypt output length=${result.length}")
        return result
    }
}
