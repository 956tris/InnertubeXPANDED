package com.metrolist.innertubex.youtube

import com.metrolist.innertubex.InnerTube
import com.metrolist.innertubex.InnerTubeHttpException
import com.metrolist.innertubex.bodyAsJsonObjectLimited
import com.metrolist.innertubex.fetchCaptionText
import com.metrolist.innertubex.models.YouTubeClient
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

private const val MAX_YOUTUBE_RESPONSE_BYTES = 6 * 1024 * 1024

class YouTubeWebClient(
    val innerTube: InnerTube,
    private val webClientProvider: suspend () -> YouTubeClient = { YouTubeClient.WEB },
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun home(
        continuation: String? = null,
        setLogin: Boolean = false,
    ): YouTubeFeedPage {
        val response =
            innerTube.browse(
                client = webClientProvider(),
                browseId = if (continuation == null) "FEwhat_to_watch" else null,
                continuation = continuation,
                setLogin = setLogin,
            )
        if (!response.status.isSuccess()) throw InnerTubeHttpException("browse:home", response.status)
        return YouTubeWebParser.parseFeedPage(response.bodyAsJsonObjectLimited(MAX_YOUTUBE_RESPONSE_BYTES))
    }

    suspend fun search(
        query: String? = null,
        filter: YouTubeSearchFilter = YouTubeSearchFilter.ALL,
        continuation: String? = null,
        setLogin: Boolean = false,
    ): YouTubeFeedPage {
        val response =
            innerTube.search(
                client = webClientProvider(),
                query = query.takeIf { continuation == null },
                params = filter.params.takeIf { continuation == null },
                continuation = continuation,
                setLogin = setLogin,
            )
        if (!response.status.isSuccess()) throw InnerTubeHttpException("search", response.status)
        return YouTubeWebParser.parseFeedPage(response.bodyAsJsonObjectLimited(MAX_YOUTUBE_RESPONSE_BYTES))
    }

    suspend fun next(
        videoId: String? = null,
        playlistId: String? = null,
        continuation: String? = null,
    ): YouTubeNextPage {
        val response =
            innerTube.next(
                client = webClientProvider(),
                videoId = videoId.takeIf { continuation == null },
                playlistId = playlistId.takeIf { continuation == null },
                continuation = continuation,
            )
        if (!response.status.isSuccess()) throw InnerTubeHttpException("next", response.status)
        return YouTubeWebParser.parseNextPage(response.bodyAsJsonObjectLimited(MAX_YOUTUBE_RESPONSE_BYTES))
    }

    suspend fun channel(
        channelIdOrHandle: String,
        continuation: String? = null,
    ): YouTubeChannelPage {
        val resolvedId =
            if (continuation == null && channelIdOrHandle.startsWith("@")) {
                resolveHandle(channelIdOrHandle) ?: channelIdOrHandle
            } else {
                channelIdOrHandle
            }
        val response =
            innerTube.browse(
                client = webClientProvider(),
                browseId = resolvedId.takeIf { continuation == null },
                continuation = continuation,
            )
        if (!response.status.isSuccess()) throw InnerTubeHttpException("browse:channel", response.status)
        return YouTubeWebParser.parseChannelPage(resolvedId, response.bodyAsJsonObjectLimited(MAX_YOUTUBE_RESPONSE_BYTES))
    }

    suspend fun playlist(
        playlistId: String,
        continuation: String? = null,
    ): YouTubePlaylistPage {
        val browseId =
            if (playlistId.startsWith("VL") || playlistId.startsWith("FE")) {
                playlistId
            } else {
                "VL$playlistId"
            }
        val response =
            innerTube.browse(
                client = webClientProvider(),
                browseId = browseId.takeIf { continuation == null },
                continuation = continuation,
                setLogin = innerTube.hasSapCookieAuth(),
            )
        if (!response.status.isSuccess()) throw InnerTubeHttpException("browse:playlist", response.status)
        return YouTubeWebParser.parsePlaylistPage(playlistId, response.bodyAsJsonObjectLimited(MAX_YOUTUBE_RESPONSE_BYTES))
    }

    suspend fun library(
        browseId: String,
        continuation: String? = null,
    ): YouTubeFeedPage {
        val response =
            innerTube.browse(
                client = webClientProvider(),
                browseId = browseId.takeIf { continuation == null },
                continuation = continuation,
                setLogin = true,
            )
        if (!response.status.isSuccess()) throw InnerTubeHttpException("browse:library", response.status)
        return YouTubeWebParser.parseFeedPage(response.bodyAsJsonObjectLimited(MAX_YOUTUBE_RESPONSE_BYTES))
    }

    suspend fun resolveHandle(handleOrUrl: String): String? {
        val url =
            if (handleOrUrl.startsWith("https://", ignoreCase = true)) {
                handleOrUrl
            } else {
                "https://www.youtube.com/${handleOrUrl.trimStart('/')}"
            }
        val response = innerTube.resolveUrl(webClientProvider(), url)
        if (!response.status.isSuccess()) return null
        return YouTubeWebParser.parseResolvedBrowseId(response.bodyAsJsonObjectLimited(MAX_YOUTUBE_RESPONSE_BYTES))
    }

    suspend fun accountInfo(): YouTubeAccountInfo? {
        val response = innerTube.accountMenu(webClientProvider())
        if (!response.status.isSuccess()) return null
        return YouTubeWebParser.parseAccountInfo(response.bodyAsJsonObjectLimited(MAX_YOUTUBE_RESPONSE_BYTES))
    }

    suspend fun captionTracks(videoId: String): List<YouTubeCaptionTrack> {
        val response = innerTube.player(webClientProvider(), videoId = videoId)
        if (!response.status.isSuccess()) return emptyList()
        return YouTubeWebParser.parseCaptionTracks(response.bodyAsJsonObjectLimited(MAX_YOUTUBE_RESPONSE_BYTES))
    }

    suspend fun fetchCaptions(track: YouTubeCaptionTrack): List<YouTubeCaptionLine> {
        val separator = if ('?' in track.baseUrl) "&" else "?"
        val url =
            if ("fmt=" in track.baseUrl) {
                track.baseUrl
            } else {
                "${track.baseUrl}${separator}fmt=json3"
            }
        val body = innerTube.fetchCaptionText(url)
        val root = json.parseToJsonElement(body).jsonObject
        return YouTubeWebParser.parseTimedTextJson3(root)
    }
}
