package com.metrolist.innertubex.youtube

import com.metrolist.innertubex.InnerTube
import com.metrolist.innertubex.InnerTubeHttpException
import com.metrolist.innertubex.bodyAsJsonObjectLimited
import com.metrolist.innertubex.fetchCaptionText
import com.metrolist.innertubex.models.YouTubeClient
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
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
        if (continuation != null && continuation.startsWith(PUBLIC_FEED_CONTINUATION_PREFIX)) {
            val index = continuation.removePrefix(PUBLIC_FEED_CONTINUATION_PREFIX).toIntOrNull()
            if (index != null && index in PUBLIC_HOME_FEEDS.indices) {
                val (browseId, params) = PUBLIC_HOME_FEEDS[index]
                val feedPage = browseFeed(browseId = browseId, params = params, setLogin = false)
                val nextContinuation =
                    if (index + 1 < PUBLIC_HOME_FEEDS.size) {
                        "$PUBLIC_FEED_CONTINUATION_PREFIX${index + 1}"
                    } else {
                        null
                    }
                return feedPage.copy(continuation = nextContinuation)
            }
        }
        val response =
            innerTube.browse(
                client = webClientProvider(),
                browseId = if (continuation == null) "FEwhat_to_watch" else null,
                continuation = continuation,
                setLogin = setLogin,
            )
        if (!response.status.isSuccess()) throw InnerTubeHttpException("browse:home", response.status)
        val page = YouTubeWebParser.parseFeedPage(response.bodyAsJsonObjectLimited(MAX_YOUTUBE_RESPONSE_BYTES))
        if (continuation == null && page.shelves.isEmpty() && page.items.isEmpty()) {
            return loadPublicFallbackHome()
        }
        return page
    }

    suspend fun browseFeed(
        browseId: String,
        params: String? = null,
        continuation: String? = null,
        setLogin: Boolean = false,
    ): YouTubeFeedPage {
        val response =
            innerTube.browse(
                client = webClientProvider(),
                browseId = browseId.takeIf { continuation == null },
                params = params.takeIf { continuation == null },
                continuation = continuation,
                setLogin = setLogin,
            )
        if (!response.status.isSuccess()) throw InnerTubeHttpException("browse:$browseId", response.status)
        return YouTubeWebParser.parseFeedPage(response.bodyAsJsonObjectLimited(MAX_YOUTUBE_RESPONSE_BYTES))
    }

    private suspend fun loadPublicFallbackHome(): YouTubeFeedPage {
        val mergedShelves = mutableListOf<YouTubeShelf>()
        val mergedItems = mutableListOf<YouTubeFeedItem>()
        val seenKeys = HashSet<String>()
        var loadedFeeds = 0
        for ((browseId, params) in PUBLIC_HOME_FEEDS.take(INITIAL_PUBLIC_FEED_COUNT)) {
            val feedPage =
                try {
                    browseFeed(browseId = browseId, params = params, setLogin = false)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
            loadedFeeds++
            if (feedPage != null) {
                mergedShelves += feedPage.shelves
                for (item in feedPage.items) {
                    val key =
                        item.video?.let { "v:${it.id}" }
                            ?: item.channel?.let { "c:${it.id}" }
                            ?: item.playlist?.let { "p:${it.id}" }
                            ?: continue
                    if (seenKeys.add(key)) {
                        mergedItems += item
                    }
                }
            }
        }
        val nextContinuation =
            if (loadedFeeds < PUBLIC_HOME_FEEDS.size && (mergedShelves.isNotEmpty() || mergedItems.isNotEmpty())) {
                "$PUBLIC_FEED_CONTINUATION_PREFIX$loadedFeeds"
            } else {
                null
            }
        return YouTubeFeedPage(
            shelves = mergedShelves,
            items = mergedItems,
            continuation = nextContinuation,
        )
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

    private companion object {
        const val INITIAL_PUBLIC_FEED_COUNT = 2
        const val PUBLIC_FEED_CONTINUATION_PREFIX = "yt-public-home:"
        val PUBLIC_HOME_FEEDS =
            listOf(
                "UC-9-kyTW8ZkZNDHQJ6FgpwQ" to null,
                "UC4R8DWoMoI7CAwX8_LjQHig" to null,
                "UCYfdidRxbB8Qhf0Nx7ioOYw" to null,
                "UCEgdi0XIXXZ-qJOFPf4JSKw" to null,
                "FEpodcasts_destination" to "qgcCCAE%3D",
            )
    }
}
