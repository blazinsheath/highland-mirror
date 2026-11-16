package io.github.cfraz89.slipstream

import android.content.Context
import android.os.Build
import android.util.AtomicFile
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import java.util.concurrent.TimeUnit

/** Per-install ADB identity, stored only in the app's private, non-backed-up directory. */
class MirrorAdbKeys(context: Context) : AbsAdbConnectionManager() {
    private val key: PrivateKey
    private val cert: Certificate
    init {
        val file = AtomicFile(File(context.noBackupFilesDir, "helper-adb-identity"))
        if (file.baseFile.exists()) {
            DataInputStream(file.openRead()).use { input ->
                val k = ByteArray(input.readInt().also { require(it in 1..16384) }); input.readFully(k)
                val c = ByteArray(input.readInt().also { require(it in 1..16384) }); input.readFully(c)
                key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(k))
                cert = CertificateFactory.getInstance("X.509").generateCertificate(c.inputStream())
            }
        } else {
            val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            key = pair.private
            val name = X500Name("CN=Highland Mirror")
            val now = System.currentTimeMillis()
            val bc = BouncyCastleProvider()
            val holder = JcaX509v3CertificateBuilder(name, BigInteger(128, SecureRandom()).setBit(127),
                Date(now - 86400000L), Date(now + 3650L * 86400000L), name, pair.public)
                .build(JcaContentSignerBuilder("SHA256withRSA").setProvider(bc).build(key))
            cert = JcaX509CertificateConverter().setProvider(bc).getCertificate(holder)
            val stream = file.startWrite()
            try {
                val output = DataOutputStream(stream)
                output.writeInt(key.encoded.size); output.write(key.encoded)
                output.writeInt(cert.encoded.size); output.write(cert.encoded); output.flush()
                file.finishWrite(stream)
            } catch (e: Exception) { file.failWrite(stream); throw e }
        }
        setApi(Build.VERSION.SDK_INT)
        setHostAddress("127.0.0.1")
        setTimeout(5, TimeUnit.SECONDS)
        setThrowOnUnauthorised(true)
    }
    override fun getPrivateKey() = key
    override fun getCertificate() = cert
    override fun getDeviceName() = "Highland Mirror"
}
