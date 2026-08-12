package com.streamflixreborn.streamflix.extractors

import android.util.Base64
import android.util.Log
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.DnsResolver
import okhttp3.OkHttpClient
import org.json.JSONObject
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.*
import java.security.*
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.*
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

open class FilemoonExtractor : Extractor() {

    override val name = "Filemoon"
    override val mainUrl = "https://filemoon.site"
    override val aliasUrls = listOf("https://bf0skv.org","https://bysejikuar.com","https://moflix-stream.link","https://bysezoxexe.com","https://bysebuho.com","https://filemoon.sx","https://bysekoze.com","https://bysesayeveum.com")

    private var deviceId = UUID.randomUUID().toString().replace("-", "")

    override suspend fun extract(link: String): Video {
        val service = Service.build(mainUrl)
        // Regex to match /e/ or /d/ and ID
        val matcher = Regex("""/(e|d)/([a-zA-Z0-9]+)""").find(link) 
            ?: throw Exception("Could not extract video ID or type")
        
        val linkType = matcher.groupValues[1]
        val videoId = matcher.groupValues[2]
        
        val currentDomain = Regex("""(https?://[^/]+)""").find(link)?.groupValues?.get(1)
            ?: throw Exception("Could not extract Base URL")

        Log.i("StreamFlixES", "[Filemoon] Extraction started")

        // 1. Details
        val detailsUrl = "$currentDomain/api/videos/$videoId/embed/details"
        val details = service.getDetails(detailsUrl)
        val embedFrameUrl = details.embed_frame_url ?: throw Exception("embed_frame_url not found")
        
        val playbackDomain = Regex("""(https?://[^/]+)""").find(embedFrameUrl)?.groupValues?.get(1)
            ?: throw Exception("Could not extract playback domain")
        // 2. Challenge
        val challengeUrl = "$playbackDomain/api/videos/access/challenge"
        val challenge = service.getChallenge(challengeUrl, mapOf(
            "Referer" to embedFrameUrl,
            "Origin" to playbackDomain,
            "User-Agent" to Service.DEFAULT_USER_AGENT
        ))
        
        val challengeId = challenge.challenge_id ?: throw Exception("No challenge_id")
        val nonce = challenge.nonce ?: throw Exception("No nonce")
        
        // Always generate a new viewerId
        var viewerId = UUID.randomUUID().toString().replace("-", "")
        // 3. Attestation
        val attestation = generateAttestation(nonce)
        val attestUrl = "$playbackDomain/api/videos/access/attest"
        
        val attestPayload: Map<String, kotlin.Any> = mapOf(
            "viewer_id" to viewerId,
            "device_id" to deviceId,
            "challenge_id" to challengeId,
            "nonce" to nonce,
            "signature" to attestation.signature,
            "public_key" to attestation.publicKey,
            "client" to mapOf(
                "user_agent" to Service.DEFAULT_USER_AGENT,
                "architecture" to "x86",
                "bitness" to "64",
                "platform" to "Windows",
                "platform_version" to "10.0.0",
                "pixel_ratio" to 1.0,
                "screen_width" to 1920,
                "screen_height" to 1080,
                "languages" to listOf("en-US")
            ),
            "storage" to mapOf(
                "cookie" to viewerId,
                "local_storage" to viewerId,
                "indexed_db" to "$viewerId:$deviceId",
                "cache_storage" to "$viewerId:$deviceId"
            ),
            "attributes" to mapOf("entropy" to "high")
        )

        val attestResponse = service.attest(attestUrl, attestPayload, mapOf(
            "Referer" to embedFrameUrl,
            "Origin" to playbackDomain,
            "User-Agent" to Service.DEFAULT_USER_AGENT
        ))
        
        val token = attestResponse.token ?: throw Exception("No attest token")
        viewerId = attestResponse.viewer_id ?: viewerId
        deviceId = attestResponse.device_id ?: deviceId
        val confidence = attestResponse.confidence ?: throw Exception("No confidence in response")

        Log.i("StreamFlixES", "[Filemoon] Attestation completed")

        // 4. Playback
        val playbackUrl = "$playbackDomain/api/videos/$videoId/embed/playback"
        val playbackPayload: Map<String, kotlin.Any> = mapOf(
            "fingerprint" to mapOf(
                "token" to token,
                "viewer_id" to viewerId,
                "device_id" to deviceId,
                "confidence" to confidence
            )
        )

        val playbackResponse = service.getPlayback(playbackUrl, playbackPayload, mapOf(
            "Referer" to embedFrameUrl,
            "Origin" to playbackDomain,
            "X-Embed-Parent" to (if (linkType == "e") link else ""),
            "User-Agent" to Service.DEFAULT_USER_AGENT
        ))

        val playbackData = playbackResponse.playback ?: throw Exception("No playback data")
        Log.i("StreamFlixES", "[Filemoon] Decrypting playback data")
        val decryptedJson = decryptPlayback(playbackData)
        
        val jsonObject = JSONObject(decryptedJson)
        val sources = jsonObject.optJSONArray("sources")
            ?: throw Exception("No sources found")
        val sourceUrl = sources.getJSONObject(0).getString("url")

        Log.i("StreamFlixES", "[Filemoon] Source extracted")

        return Video(
            source = sourceUrl,
            headers = mapOf(
                "Referer" to embedFrameUrl,
                "User-Agent" to Service.DEFAULT_USER_AGENT,
                "Origin" to playbackDomain
            )
        )
    }

    private fun generateAttestation(nonce: String): Attestation {
        val keyPairGenerator = KeyPairGenerator.getInstance("EC")
        keyPairGenerator.initialize(ECGenParameterSpec("secp256r1"))
        val keyPair = keyPairGenerator.generateKeyPair()
        val privateKey = keyPair.private
        val publicKey = keyPair.public as ECPublicKey

        // JWK coordinates
        val x = Base64.encodeToString(publicKey.w.affineX.toByteArray().stripLeadingZero(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val y = Base64.encodeToString(publicKey.w.affineY.toByteArray().stripLeadingZero(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

        val signatureObj = Signature.getInstance("SHA256withECDSA")
        signatureObj.initSign(privateKey)
        signatureObj.update(nonce.toByteArray())
        val derSignature = signatureObj.sign()

        // Convert DER to Raw (r + s)
        val rawSignature = derToRawSignature(derSignature)
        val encodedSignature = Base64.encodeToString(rawSignature, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

        val jwk = mapOf(
            "crv" to "P-256",
            "ext" to true,
            "key_ops" to listOf("verify"),
            "kty" to "EC",
            "x" to x,
            "y" to y
        )

        return Attestation(encodedSignature, jwk)
    }

    private fun derToRawSignature(der: ByteArray): ByteArray {
        // Simple DER parser for ECDSA signature (SEQUENCE { r INTEGER, s INTEGER })
        var offset = 2
        val rLen = der[offset + 1].toInt()
        val r = der.copyOfRange(offset + 2, offset + 2 + rLen).stripLeadingZero()
        offset += 2 + rLen
        val sLen = der[offset + 1].toInt()
        val s = der.copyOfRange(offset + 2, offset + 2 + sLen).stripLeadingZero()

        val raw = ByteArray(64)
        System.arraycopy(r, 0, raw, 32 - r.size, r.size)
        System.arraycopy(s, 0, raw, 64 - s.size, s.size)
        return raw
    }

    private fun ByteArray.stripLeadingZero(): ByteArray {
        return if (this.isNotEmpty() && this[0] == 0.toByte()) {
            this.copyOfRange(1, this.size)
        } else {
            this
        }
    }

    data class Attestation(val signature: String, val publicKey: Map<String, kotlin.Any>)

    private fun decryptPlayback(data: PlaybackData): String {
        val iv = Base64.decode(data.iv, Base64.URL_SAFE)
        val payload = Base64.decode(data.payload, Base64.URL_SAFE)
        val p1 = Base64.decode(data.key_parts[0], Base64.URL_SAFE)
        val p2 = Base64.decode(data.key_parts[1], Base64.URL_SAFE)
        
        val key = ByteArray(p1.size + p2.size)
        System.arraycopy(p1, 0, key, 0, p1.size)
        System.arraycopy(p2, 0, key, p1.size, p2.size)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val spec = GCMParameterSpec(128, iv)
        val secretKey = SecretKeySpec(key, "AES")
        
        cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)
        
        val decryptedBytes = cipher.doFinal(payload)
        return String(decryptedBytes, Charsets.UTF_8)
    }

    open class Any(hostUrl: String) : FilemoonExtractor() {
        override val mainUrl = hostUrl
    }

    private interface Service {
        @GET
        suspend fun getDetails(@Url url: String): DetailsResponse

        @POST
        suspend fun getChallenge(@Url url: String, @HeaderMap headers: Map<String, String>): ChallengeResponse

        @JvmSuppressWildcards
        @POST
        suspend fun attest(@Url url: String, @Body body: Map<String, kotlin.Any>, @HeaderMap headers: Map<String, String>): AttestResponse

        @JvmSuppressWildcards
        @POST
        suspend fun getPlayback(@Url url: String, @Body body: Map<String, kotlin.Any>, @HeaderMap headers: Map<String, String>): PlaybackResponse

        companion object {
            const val DEFAULT_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/147.0.0.0 Safari/537.36"

            fun build(baseUrl: String): Service {
                val cookieJar = object : okhttp3.CookieJar {
                    private val cookieStore = HashMap<String, List<okhttp3.Cookie>>()
                    override fun saveFromResponse(url: okhttp3.HttpUrl, cookies: List<okhttp3.Cookie>) {
                        cookieStore[url.host] = cookies
                    }
                    override fun loadForRequest(url: okhttp3.HttpUrl): List<okhttp3.Cookie> {
                        return cookieStore[url.host] ?: ArrayList()
                    }
                }

                val client = OkHttpClient.Builder()
                    .cookieJar(cookieJar)
                    .dns(DnsResolver.doh).build()

                return Retrofit.Builder()
                    .baseUrl(baseUrl)
                    .client(client)
                    .addConverterFactory(GsonConverterFactory.create())
                    .build()
                    .create(Service::class.java)
            }
        }
    }

    data class DetailsResponse(val embed_frame_url: String?)
    data class ChallengeResponse(val challenge_id: String?, val nonce: String?, val viewer_hint: String?)
    data class AttestResponse(val token: String?, val viewer_id: String?, val device_id: String?, val confidence: Double?)
    data class PlaybackResponse(val playback: PlaybackData?)
    data class PlaybackData(
        val iv: String,
        val payload: String,
        val key_parts: List<String>
        )
}
