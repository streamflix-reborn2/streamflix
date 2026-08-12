package com.streamflixreborn.streamflix.extractors

import android.util.Log
import com.streamflixreborn.streamflix.models.Video

internal fun selectExtractor(
    link: String,
    server: Video.Server?,
    candidates: Iterable<Extractor>,
): Extractor? {
    val urlRegex = Regex("^(https?://)?(www\\.)?")
    val compareUrl = link.lowercase().replace(urlRegex, "")

    candidates.firstOrNull { extractor ->
        compareUrl.startsWith(extractor.mainUrl.lowercase().replace(urlRegex, "")) ||
            extractor.aliasUrls.any { alias ->
                compareUrl.startsWith(alias.lowercase().replace(urlRegex, ""))
            }
    }?.let { return it }

    val domainOnlyRegex = Regex("^(https?://)?(www\\.)?(.*?)(\\.[a-z]+)")
    candidates.firstOrNull { extractor ->
        compareUrl.startsWith(
            extractor.mainUrl.lowercase().replace(domainOnlyRegex, "$3"),
        ) || extractor.aliasUrls.any { alias ->
            compareUrl.startsWith(alias.lowercase().replace(domainOnlyRegex, "$3"))
        }
    }?.let { return it }

    candidates.firstOrNull { extractor ->
        extractor.rotatingDomain.any { it.containsMatchIn(compareUrl) }
    }?.let { return it }

    val serverName = server?.name?.lowercase().orEmpty()
    return candidates.firstOrNull { extractor ->
        serverName.contains(extractor.name.lowercase())
    }
}

internal suspend fun dispatchExtraction(
    link: String,
    server: Video.Server?,
    candidates: Iterable<Extractor>,
): Video {
    val extractor = selectExtractor(link, server, candidates)
        ?: throw Exception("No extractors found for URL: $link")

    Log.i("StreamFlixES", "[EXTRACTOR] -> Starting: ${extractor.name} (URL: $link)")
    val video = extractor.extract(link, server)
    Log.i("StreamFlixES", "[VIDEO] -> Extracted: ${video.source}")
    return video
}

abstract class Extractor {

    abstract val name: String
    abstract val mainUrl: String
    open val aliasUrls: List<String> = emptyList()
    open val rotatingDomain: List<Regex> = emptyList()

    abstract suspend fun extract(link: String): Video

    open suspend fun extract(link: String, server: Video.Server? = null): Video {
        return extract(link)
    }

    companion object {
        // Most screens never invoke an extractor. Avoid constructing the full registry during app
        // startup (and JVM unit-test class initialization); initialize it only on first extraction.
        private val extractors: List<Extractor> by lazy {
            listOf(
                JKPlayerExtractor(),
                RabbitstreamExtractor(),
                RabbitstreamExtractor.MegacloudExtractor(),
                RabbitstreamExtractor.DokicloudExtractor(),
                RabbitstreamExtractor.PremiumEmbedingExtractor(),
                UpzoneExtractor(),
                StreamhubExtractor(),
                VtubeExtractor(),
                NuuploadExtractor(),
                VoeExtractor(),
                StreamtapeExtractor(),
                VidozaExtractor(),
                VidsrcToExtractor(),
                VidplayExtractor(),
                NekostreamExtractor(),
                FilemoonExtractor(),
                VidplayExtractor.MyCloud(),
                VidplayExtractor.VidplayOnline(),
                MyFileStorageExtractor(),
                MoflixExtractor(),
                MStreamDayExtractor(),
                VidsrcNetExtractor(),
                StreamWishExtractor(),
                StreamWishExtractor.UqloadsXyz(),
                StreamWishExtractor.SwishExtractor(),
                StreamWishExtractor.HlswishExtractor(),
                StreamWishExtractor.PlayerwishExtractor(),
                StreamWishExtractor.SwiftPlayersExtractor(),
                TwoEmbedExtractor(),
                ChillxExtractor(),
                ChillxExtractor.JeanExtractor(),
                MoviesapiExtractor(),
                CloseloadExtractor(),
                LuluVdoExtractor(),
                DoodLaExtractor(),
                DoodLaExtractor.DoodLiExtractor(),
                VidPlyExtractor(),
                MagaSavorExtractor(),
                VidMoLyExtractor(),
                VidMoLyExtractor.ToDomain(),
                VideoSibNetExtractor(),
                SaveFilesExtractor(),
                BigWarpExtractor(),
                DoodLaExtractor.DoodExtractor(),
                LoadXExtractor(),
                VidHideExtractor(),
                VeevExtractor(),
                RidooExtractor(),
                USTRExtractor(),
                VidGuardExtractor(),
                OkruExtractor(),
                StreamSBExtractor(),
                Mp4UploadExtractor(),
                StreamlareExtractor(),
                NinjaStreamExtractor(),
                UchExtractor(),
                VixSrcExtractor(),
                GoodstreamExtractor(),
                LamovieExtractor(),
                UqloadExtractor(),
                MailRuExtractor(),
                MixDropExtractor(),
                SupervideoExtractor(),
                DroploadExtractor(),
                RpmvidExtractor(),
                YourUploadExtractor(),
                PlusPomlaExtractor(),
                OneuploadExtractor(),
                FsvidExtractor(),
                GoogleDriveExtractor(),
                PcloudExtractor(),
                AmazonDriveExtractor(),
                VidzyExtractor(),
                GuploadExtractor(),
                StreamUpExtractor(),
                EinschaltenExtractor(),
                VidLinkExtractor(),
                VidsrcRuExtractor(),
                VidflixExtractor(),
                VidrockExtractor(),
                VideasyExtractor(),
                VidzeeExtractor(),
                VidnestExtractor(),
                PrimeSrcExtractor(),
                VidoraExtractor(),
                GxPlayerExtractor(),
                UpZurExtractor(),
                DailymotionExtractor(),
                ApiVoirFilmExtractor(),
                StreamixExtractor(),
                ShareCloudyExtractor(),
                StreamrubyExtractor(),
                VidaraExtractor(),
                VidsonicExtractor(),
                HxfileExtractor(),
                ZillaExtractor(),
                PDrainExtractor(),
                MaxstreamExtractor(),
                VidxGoExtractor(),
            )
        }

        suspend fun extract(link: String, server: Video.Server? = null): Video {
            var finalLink = link

            if (link.contains("mysync.mov/stream/")) {
                try {
                    val client = okhttp3.OkHttpClient.Builder()
                        .followRedirects(true)
                        .followSslRedirects(true)
                        .build()

                    val responseBody = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        val request = okhttp3.Request.Builder()
                            .url(link)
                            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
                            .build()
                        client.newCall(request).execute().use { it.body?.string() }
                    } ?: ""

                    val redirectUrl = responseBody
                        .substringAfter("window.location.replace(\"", "")
                        .substringBefore("\"")
                        .ifEmpty {
                            responseBody.substringAfter("window.location.href = \"", "")
                                .substringBefore("\"")
                        }
                        .ifEmpty {
                            responseBody.substringAfter("src=\"", "")
                                .substringBefore("\"")
                        }

                    if (redirectUrl.startsWith("http")) {
                        Log.d("Extractor", "Universal Bridge resolved: $link -> $redirectUrl")
                        finalLink = redirectUrl
                    }
                } catch (e: Exception) {
                    Log.e("Extractor", "Universal Bridge error: ${e.message}")
                }
            }

            return dispatchExtraction(
                link = finalLink,
                server = server,
                candidates = extractors,
            )
        }
    }
}
