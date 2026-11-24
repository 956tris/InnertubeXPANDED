package com.metrolist.innertubex.youtube

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

object YouTubeWebParser {
    private val videoIdRegex = Regex("[A-Za-z0-9_-]{11}")
    private const val MAX_ITEMS = 500
    private const val MAX_TRAVERSAL_NODES = 20_000

    fun parseFeedPage(root: JsonObject): YouTubeFeedPage {
        val shelves = mutableListOf<YouTubeShelf>()
        val items = mutableListOf<YouTubeFeedItem>()
        val seenItemKeys = HashSet<String>()

        fun addItem(
            item: YouTubeFeedItem,
            target: MutableList<YouTubeFeedItem>,
        ) {
            if (target.size >= MAX_ITEMS) return
            val key =
                item.video?.let { "v:${it.id}" }
                    ?: item.channel?.let { "c:${it.id}" }
                    ?: item.playlist?.let { "p:${it.id}" }
                    ?: return
            if (seenItemKeys.add(key)) {
                target += item
            }
        }

        for (candidate in extractItems(root)) {
            addItem(candidate, items)
        }

        val shelfObjects =
            findObjectsByKey(
                root,
                setOf("shelfRenderer", "richSectionRenderer", "reelShelfRenderer"),
            )
        for (shelf in shelfObjects) {
            val innerContent = shelf.obj("content")
            if (innerContent?.containsKey("reelShelfRenderer") == true || innerContent?.containsKey("shelfRenderer") == true) {
                continue
            }
            val isShorts =
                shelf.containsKey("reelShelfRenderer") ||
                    findObjectsByKey(shelf, setOf("reelItemRenderer", "shortsLockupViewModel")).isNotEmpty()
            val title =
                text(shelf["title"])
                    ?: text(shelf.obj("header")?.obj("title"))
                    ?: text(innerContent?.obj("shelfRenderer")?.get("title"))
                    ?: text(innerContent?.obj("reelShelfRenderer")?.get("title"))
                    ?: if (isShorts) "Shorts" else "Featured"
            val shelfItems = mutableListOf<YouTubeFeedItem>()
            val shelfSeen = HashSet<String>()
            for (candidate in extractItems(shelf)) {
                val key = candidate.itemKey() ?: continue
                if (shelfSeen.add(key)) {
                    shelfItems += candidate
                }
                addItem(candidate, items)
            }
            if (shelfItems.isNotEmpty()) {
                shelves += YouTubeShelf(title = title, items = shelfItems, isShorts = isShorts)
            }
        }

        return YouTubeFeedPage(
            shelves = shelves.take(60),
            items = items,
            continuation = extractContinuation(root),
        )
    }

    fun parseChannelPage(
        channelId: String,
        root: JsonObject,
    ): YouTubeChannelPage {
        val c4 = findObjectsByKey(root, setOf("c4TabbedHeaderRenderer")).firstOrNull()
        val pageHeader = findObjectsByKey(root, setOf("pageHeaderRenderer")).firstOrNull()
        val viewModel = pageHeader?.obj("content")?.obj("pageHeaderViewModel")
        val metadata = findObjectsByKey(root, setOf("channelMetadataRenderer")).firstOrNull()

        val resolvedId =
            c4?.string("channelId")
                ?: metadata?.string("externalId")
                ?: channelId
        val title =
            c4?.string("title")
                ?: metadata?.string("title")
                ?: viewModel
                    ?.obj("title")
                    ?.obj("dynamicTextViewModel")
                    ?.obj("text")
                    ?.string("content")
                ?: text(pageHeader?.get("pageTitle"))
                ?: resolvedId
        val handle =
            c4?.let { text(it["channelHandleText"]) }
                ?: metadata?.string("vanityChannelUrl")?.substringAfterLast('/')?.takeIf { it.startsWith("@") }
                ?: findMetadataRowsText(viewModel).firstOrNull { it.startsWith("@") }
        val subscriberText =
            c4?.let { text(it["subscriberCountText"]) }
                ?: findMetadataRowsText(viewModel).firstOrNull { it.contains("subscriber", ignoreCase = true) }
        val description =
            metadata?.string("description")
                ?: viewModel
                    ?.obj("description")
                    ?.obj("descriptionPreviewViewModel")
                    ?.obj("description")
                    ?.string("content")
        val avatarUrl =
            bestThumbnail(c4?.get("avatar"))
                ?: bestThumbnail(metadata?.get("avatar"))
                ?: bestThumbnail(
                    viewModel
                        ?.obj("image")
                        ?.obj("decoratedAvatarViewModel")
                        ?.obj("avatar")
                        ?.obj("avatarViewModel")
                        ?.get("image"),
                )
                ?: ""
        val bannerUrl =
            bestThumbnail(c4?.get("banner"))
                ?: bestThumbnail(viewModel?.obj("banner")?.obj("imageBannerViewModel")?.get("image"))
                ?: ""

        val allItems = extractItems(root)
        val videos = allItems.mapNotNull { it.video }.distinctBy { it.id }.take(MAX_ITEMS)
        val playlists = allItems.mapNotNull { it.playlist }.distinctBy { it.id }.take(MAX_ITEMS)

        return YouTubeChannelPage(
            id = resolvedId,
            title = title,
            handle = handle,
            description = description,
            subscriberText = subscriberText,
            avatarUrl = avatarUrl,
            bannerUrl = bannerUrl,
            videos = videos,
            playlists = playlists,
            continuation = extractContinuation(root),
        )
    }

    fun parsePlaylistPage(
        playlistId: String,
        root: JsonObject,
    ): YouTubePlaylistPage {
        val header = findObjectsByKey(root, setOf("playlistHeaderRenderer")).firstOrNull()
        val pageHeader = findObjectsByKey(root, setOf("pageHeaderRenderer")).firstOrNull()
        val viewModel = pageHeader?.obj("content")?.obj("pageHeaderViewModel")
        val title =
            header?.let { text(it["title"]) }
                ?: viewModel
                    ?.obj("title")
                    ?.obj("dynamicTextViewModel")
                    ?.obj("text")
                    ?.string("content")
                ?: text(pageHeader?.get("pageTitle"))
                ?: playlistId
        val author =
            header?.let { text(it["ownerText"]) }
                ?: viewModel
                    ?.obj("metadata")
                    ?.obj("contentMetadataViewModel")
                    ?.array("metadataRows")
                    ?.firstOrNull()
                    ?.let { (it as? JsonObject)?.array("metadataParts")?.firstOrNull() }
                    ?.let { (it as? JsonObject)?.obj("text")?.string("content") }
                    .orEmpty()
        val description =
            header?.let { text(it["descriptionText"]) }
                ?: viewModel
                    ?.obj("description")
                    ?.obj("descriptionPreviewViewModel")
                    ?.obj("description")
                    ?.string("content")
        val thumbnailUrl =
            bestThumbnail(header?.obj("playlistHeaderBanner")?.obj("heroPlaylistThumbnailRenderer")?.get("thumbnail"))
                ?: bestThumbnail(viewModel?.obj("heroImage")?.obj("contentPreviewImageViewModel")?.get("image"))
                ?: ""
        val videos = extractItems(root).mapNotNull { it.video }.distinctBy { it.id }.take(MAX_ITEMS)
        return YouTubePlaylistPage(
            id = playlistId,
            title = title,
            author = author,
            description = description,
            thumbnailUrl = thumbnailUrl.ifBlank { videos.firstOrNull()?.thumbnailUrl.orEmpty() },
            videos = videos,
            continuation = extractContinuation(root),
        )
    }

    fun parseNextPage(root: JsonObject): YouTubeNextPage {
        val primary = findObjectsByKey(root, setOf("videoPrimaryInfoRenderer")).firstOrNull()
        val secondary = findObjectsByKey(root, setOf("videoSecondaryInfoRenderer")).firstOrNull()
        val currentVideoId =
            root
                .obj("currentVideoEndpoint")
                ?.obj("watchEndpoint")
                ?.string("videoId")
                ?.takeIf(videoIdRegex::matches)
        val owner = secondary?.obj("owner")?.obj("videoOwnerRenderer")
        val primaryTitle = primary?.let { text(it["title"]) }
        val likeStatus =
            findObjectsByKey(primary ?: JsonObject(emptyMap()), setOf("segmentedLikeDislikeButtonViewModel"))
                .firstOrNull()
                ?.obj("likeButtonViewModel")
                ?.obj("likeButtonViewModel")
                ?.string("likeStatus")
                ?: findObjectsByKey(primary ?: JsonObject(emptyMap()), setOf("toggleButtonRenderer"))
                    .firstOrNull { it.obj("defaultIcon")?.string("iconType") == "LIKE" }
                    ?.let { if ((it["isToggled"] as? JsonPrimitive)?.booleanOrNull == true) "LIKE" else "INDIFFERENT" }

        val currentVideo =
            if (currentVideoId != null && !primaryTitle.isNullOrBlank()) {
                YouTubeVideo(
                    id = currentVideoId,
                    title = primaryTitle,
                    channelName = owner?.let { text(it["title"]) }.orEmpty(),
                    channelId = owner?.obj("navigationEndpoint")?.obj("browseEndpoint")?.string("browseId"),
                    viewCountText = primary.obj("viewCount")?.obj("videoViewCountRenderer")?.let { text(it["viewCount"]) },
                    publishedText = text(primary["dateText"]),
                    thumbnailUrl = "https://i.ytimg.com/vi/$currentVideoId/hqdefault.jpg",
                    likeStatus = likeStatus,
                )
            } else {
                null
            }

        val playlistVideos =
            findObjectsByKey(root, setOf("playlistPanelVideoRenderer"))
                .mapNotNull(::parsePlaylistPanelVideo)
                .distinctBy { it.id }
                .take(MAX_ITEMS)

        val secondaryResults =
            root
                .obj("contents")
                ?.obj("twoColumnWatchNextResults")
                ?.obj("secondaryResults")
                ?.obj("secondaryResults")
                ?: root
        val related =
            extractItems(secondaryResults)
                .mapNotNull { it.video }
                .filter { it.id != currentVideoId }
                .distinctBy { it.id }
                .take(MAX_ITEMS)

        val chapters =
            findObjectsByKey(root, setOf("macroMarkersListItemRenderer"))
                .mapNotNull { marker ->
                    val title = text(marker["title"]) ?: return@mapNotNull null
                    val startMs =
                        marker
                            .obj("onTap")
                            ?.obj("watchEndpoint")
                            ?.string("startTimeSeconds")
                            ?.toLongOrNull()
                            ?.times(1000L)
                            ?: text(marker["timeDescription"])?.let(::parseDurationSeconds)?.times(1000L)
                            ?: return@mapNotNull null
                    YouTubeChapter(title = title, startMs = startMs)
                }.distinctBy { it.startMs }
                .sortedBy { it.startMs }

        return YouTubeNextPage(
            video = currentVideo,
            related = related,
            playlistVideos = playlistVideos,
            chapters = chapters,
            continuation = extractContinuation(secondaryResults) ?: extractContinuation(root),
        )
    }

    fun parseAccountInfo(root: JsonObject): YouTubeAccountInfo? {
        val active = findObjectsByKey(root, setOf("activeAccountHeaderRenderer")).firstOrNull()
        if (active != null) {
            val name = text(active["accountName"]) ?: return null
            return YouTubeAccountInfo(
                name = name,
                handle = text(active["channelHandle"]),
                avatarUrl = bestThumbnail(active["accountPhoto"]),
            )
        }
        val google = findObjectsByKey(root, setOf("googleAccountHeaderRenderer")).firstOrNull() ?: return null
        val name = text(google["name"]) ?: return null
        return YouTubeAccountInfo(
            name = name,
            handle = text(google["email"]),
            avatarUrl = bestThumbnail(google["accountPhoto"]),
        )
    }

    fun parseResolvedBrowseId(root: JsonObject): String? =
        root.obj("endpoint")?.obj("browseEndpoint")?.string("browseId")
            ?: findObjectsByKey(root, setOf("browseEndpoint")).firstOrNull()?.string("browseId")

    fun parseCaptionTracks(root: JsonObject): List<YouTubeCaptionTrack> {
        val list =
            root
                .obj("captions")
                ?.obj("playerCaptionsTracklistRenderer")
                ?.array("captionTracks")
                .orEmpty()
        return list.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val code = obj.string("languageCode") ?: return@mapNotNull null
            val baseUrl = obj.string("baseUrl")?.takeIf { it.startsWith("https://", ignoreCase = true) } ?: return@mapNotNull null
            val name = text(obj["name"]) ?: code
            YouTubeCaptionTrack(
                languageCode = code,
                name = name,
                baseUrl = baseUrl,
                isAutoGenerated = obj.string("kind") == "asr",
            )
        }
    }

    fun parseTimedTextJson3(root: JsonObject): List<YouTubeCaptionLine> {
        val events = root.array("events").orEmpty()
        val lines = ArrayList<YouTubeCaptionLine>(events.size.coerceAtMost(2000))
        for (element in events.take(5000)) {
            val event = element as? JsonObject ?: continue
            val startMs = (event["tStartMs"] as? JsonPrimitive)?.longOrNull ?: continue
            val durationMs = (event["dDurationMs"] as? JsonPrimitive)?.longOrNull ?: 0L
            val text =
                event
                    .array("segs")
                    .orEmpty()
                    .mapNotNull { (it as? JsonObject)?.string("utf8") }
                    .joinToString("")
                    .replace('\n', ' ')
                    .trim()
            if (text.isNotEmpty()) {
                lines += YouTubeCaptionLine(startMs = startMs, durationMs = durationMs, text = text)
            }
        }
        return lines
    }

    private fun extractItems(node: JsonElement): List<YouTubeFeedItem> {
        val results = mutableListOf<YouTubeFeedItem>()
        val queue = ArrayDeque<JsonElement>()
        queue.add(node)
        var visited = 0
        while (queue.isNotEmpty() && visited < MAX_TRAVERSAL_NODES && results.size < MAX_ITEMS) {
            visited++
            when (val current = queue.removeFirst()) {
                is JsonArray -> {
                    for (child in current) queue.add(child)
                }

                is JsonObject -> {
                    current.obj("videoRenderer")?.let(::parseClassicVideo)?.let {
                        results += YouTubeFeedItem(video = it)
                        continue
                    }
                    current.obj("compactVideoRenderer")?.let(::parseClassicVideo)?.let {
                        results += YouTubeFeedItem(video = it)
                        continue
                    }
                    current.obj("gridVideoRenderer")?.let(::parseClassicVideo)?.let {
                        results += YouTubeFeedItem(video = it)
                        continue
                    }
                    current.obj("playlistVideoRenderer")?.let(::parseClassicVideo)?.let {
                        results += YouTubeFeedItem(video = it)
                        continue
                    }
                    current.obj("reelItemRenderer")?.let(::parseReelItem)?.let {
                        results += YouTubeFeedItem(video = it)
                        continue
                    }
                    current.obj("shortsLockupViewModel")?.let(::parseShortsLockup)?.let {
                        results += YouTubeFeedItem(video = it)
                        continue
                    }
                    current.obj("lockupViewModel")?.let(::parseLockup)?.let {
                        results += it
                        continue
                    }
                    current.obj("channelRenderer")?.let(::parseChannelRenderer)?.let {
                        results += YouTubeFeedItem(channel = it)
                        continue
                    }
                    current.obj("playlistRenderer")?.let(::parsePlaylistRenderer)?.let {
                        results += YouTubeFeedItem(playlist = it)
                        continue
                    }
                    current.obj("gridPlaylistRenderer")?.let(::parsePlaylistRenderer)?.let {
                        results += YouTubeFeedItem(playlist = it)
                        continue
                    }
                    for (value in current.values) {
                        if (value is JsonObject || value is JsonArray) {
                            queue.add(value)
                        }
                    }
                }

                else -> {}
            }
        }
        return results
    }

    private fun parseClassicVideo(obj: JsonObject): YouTubeVideo? {
        val id =
            obj.string("videoId")
                ?: obj.obj("navigationEndpoint")?.obj("watchEndpoint")?.string("videoId")
                ?: return null
        if (!videoIdRegex.matches(id)) return null
        val title = text(obj["title"]) ?: text(obj["headline"]) ?: return null
        val ownerObj =
            obj.obj("ownerText")
                ?: obj.obj("shortBylineText")
                ?: obj.obj("longBylineText")
        val channelName = text(ownerObj) ?: ""
        val channelId =
            ownerObj
                ?.array("runs")
                ?.firstOrNull()
                ?.let { (it as? JsonObject)?.obj("navigationEndpoint")?.obj("browseEndpoint")?.string("browseId") }
                ?: obj
                    .obj("channelThumbnailSupportedRenderers")
                    ?.obj("channelThumbnailWithLinkRenderer")
                    ?.obj("navigationEndpoint")
                    ?.obj("browseEndpoint")
                    ?.string("browseId")
        val durationText =
            text(obj["lengthText"])
                ?: findObjectsByKey(obj, setOf("thumbnailOverlayTimeStatusRenderer")).firstOrNull()?.let { text(it["text"]) }
        val durationSeconds = durationText?.let(::parseDurationSeconds)
        val badges = collectStringsForKey(obj, "label") + collectStringsForKey(obj, "style")
        val isLive =
            durationText.equals("LIVE", ignoreCase = true) ||
                badges.any { it.contains("LIVE", ignoreCase = true) }
        val is4k = badges.any { it.equals("4K", ignoreCase = true) }
        return YouTubeVideo(
            id = id,
            title = title,
            channelName = channelName,
            channelId = channelId,
            durationSeconds = durationSeconds,
            viewCountText = text(obj["shortViewCountText"]) ?: text(obj["viewCountText"]),
            publishedText = text(obj["publishedTimeText"]),
            thumbnailUrl = bestThumbnail(obj["thumbnail"]) ?: "https://i.ytimg.com/vi/$id/hqdefault.jpg",
            isLive = isLive,
            isShort = false,
            is4k = is4k,
        )
    }

    private fun parsePlaylistPanelVideo(obj: JsonObject): YouTubeVideo? {
        val id = obj.string("videoId") ?: return null
        if (!videoIdRegex.matches(id)) return null
        val title = text(obj["title"]) ?: return null
        val owner = obj.obj("shortBylineText") ?: obj.obj("longBylineText")
        return YouTubeVideo(
            id = id,
            title = title,
            channelName = text(owner).orEmpty(),
            channelId =
                owner
                    ?.array("runs")
                    ?.firstOrNull()
                    ?.let { (it as? JsonObject)?.obj("navigationEndpoint")?.obj("browseEndpoint")?.string("browseId") },
            durationSeconds = text(obj["lengthText"])?.let(::parseDurationSeconds),
            thumbnailUrl = bestThumbnail(obj["thumbnail"]) ?: "https://i.ytimg.com/vi/$id/hqdefault.jpg",
        )
    }

    private fun parseReelItem(obj: JsonObject): YouTubeVideo? {
        val id =
            obj.string("videoId")
                ?: obj.obj("navigationEndpoint")?.obj("reelWatchEndpoint")?.string("videoId")
                ?: return null
        if (!videoIdRegex.matches(id)) return null
        val title = text(obj["headline"]) ?: return null
        return YouTubeVideo(
            id = id,
            title = title,
            channelName = "",
            viewCountText = text(obj["viewCountText"]),
            thumbnailUrl = bestThumbnail(obj["thumbnail"]) ?: "https://i.ytimg.com/vi/$id/hqdefault.jpg",
            isShort = true,
        )
    }

    private fun parseShortsLockup(obj: JsonObject): YouTubeVideo? {
        val id =
            obj
                .obj("onTap")
                ?.obj("innertubeCommand")
                ?.obj("reelWatchEndpoint")
                ?.string("videoId")
                ?: obj.obj("entityId")?.let { null }
                ?: findObjectsByKey(obj, setOf("reelWatchEndpoint")).firstOrNull()?.string("videoId")
                ?: return null
        if (!videoIdRegex.matches(id)) return null
        val overlay = obj.obj("overlayMetadata")
        val title = overlay?.obj("primaryText")?.string("content") ?: return null
        return YouTubeVideo(
            id = id,
            title = title,
            channelName = "",
            viewCountText = overlay.obj("secondaryText")?.string("content"),
            thumbnailUrl =
                bestThumbnail(obj.obj("thumbnailViewModel")?.obj("thumbnailViewModel")?.get("image"))
                    ?: bestThumbnail(obj["thumbnail"])
                    ?: "https://i.ytimg.com/vi/$id/hqdefault.jpg",
            isShort = true,
        )
    }

    private fun parseLockup(obj: JsonObject): YouTubeFeedItem? {
        val contentType = obj.string("contentType") ?: ""
        val contentId = obj.string("contentId")
        val meta = obj.obj("metadata")?.obj("lockupMetadataViewModel")
        val title = meta?.obj("title")?.string("content") ?: return null
        val rows =
            meta
                .obj("metadata")
                ?.obj("contentMetadataViewModel")
                ?.array("metadataRows")
                .orEmpty()
                .mapNotNull { row ->
                    (row as? JsonObject)
                        ?.array("metadataParts")
                        ?.mapNotNull { part -> (part as? JsonObject)?.obj("text")?.string("content") }
                        ?.joinToString(" · ")
                        ?.takeIf(String::isNotBlank)
                }
        val thumb =
            bestThumbnail(
                obj.obj("contentImage")?.obj("thumbnailViewModel")?.get("image")
                    ?: obj
                        .obj(
                            "contentImage",
                        )?.obj("collectionThumbnailViewModel")
                        ?.obj("primaryThumbnail")
                        ?.obj("thumbnailViewModel")
                        ?.get("image"),
            ).orEmpty()

        val watchVideoId =
            obj
                .obj("rendererContext")
                ?.obj("commandContext")
                ?.obj("onTap")
                ?.obj("innertubeCommand")
                ?.obj("watchEndpoint")
                ?.string("videoId")
                ?: contentId?.takeIf(videoIdRegex::matches)

        if (contentType == "LOCKUP_CONTENT_TYPE_PLAYLIST" || contentType == "LOCKUP_CONTENT_TYPE_PODCAST") {
            val playlistId =
                obj
                    .obj("rendererContext")
                    ?.obj("commandContext")
                    ?.obj("onTap")
                    ?.obj("innertubeCommand")
                    ?.obj("watchEndpoint")
                    ?.string("playlistId")
                    ?: obj
                        .obj("rendererContext")
                        ?.obj("commandContext")
                        ?.obj("onTap")
                        ?.obj("innertubeCommand")
                        ?.obj("browseEndpoint")
                        ?.string("browseId")
                    ?: contentId
                    ?: return null
            return YouTubeFeedItem(
                playlist =
                    YouTubePlaylistSummary(
                        id = playlistId,
                        title = title,
                        author = rows.firstOrNull().orEmpty(),
                        videoCountText = rows.getOrNull(1),
                        thumbnailUrl = thumb,
                    ),
            )
        }

        val id = watchVideoId ?: return null
        val durationText =
            findObjectsByKey(obj, setOf("thumbnailOverlayBadgeViewModel"))
                .firstNotNullOfOrNull { badge ->
                    badge
                        .array("thumbnailBadges")
                        ?.firstOrNull()
                        ?.let { (it as? JsonObject)?.obj("thumbnailBadgeViewModel")?.string("text") }
                }
        val channelId =
            findObjectsByKey(meta, setOf("browseEndpoint"))
                .firstOrNull()
                ?.string("browseId")
        return YouTubeFeedItem(
            video =
                YouTubeVideo(
                    id = id,
                    title = title,
                    channelName = rows.firstOrNull().orEmpty(),
                    channelId = channelId,
                    durationSeconds = durationText?.let(::parseDurationSeconds),
                    viewCountText = rows.getOrNull(1),
                    publishedText = rows.getOrNull(2),
                    thumbnailUrl = thumb.ifBlank { "https://i.ytimg.com/vi/$id/hqdefault.jpg" },
                    isLive = durationText.equals("LIVE", ignoreCase = true),
                    isShort = contentType == "LOCKUP_CONTENT_TYPE_SHORT",
                ),
        )
    }

    private fun parseChannelRenderer(obj: JsonObject): YouTubeChannelSummary? {
        val id = obj.string("channelId") ?: return null
        val title = text(obj["title"]) ?: return null
        return YouTubeChannelSummary(
            id = id,
            title = title,
            handle = text(obj["subscriberCountText"])?.takeIf { it.startsWith("@") },
            subscriberText = text(obj["videoCountText"]) ?: text(obj["subscriberCountText"]),
            avatarUrl = bestThumbnail(obj["thumbnail"]).orEmpty(),
        )
    }

    private fun parsePlaylistRenderer(obj: JsonObject): YouTubePlaylistSummary? {
        val id = obj.string("playlistId") ?: return null
        val title = text(obj["title"]) ?: return null
        val author = text(obj["shortBylineText"]) ?: text(obj["longBylineText"]) ?: ""
        return YouTubePlaylistSummary(
            id = id,
            title = title,
            author = author,
            videoCountText = text(obj["videoCountText"]) ?: obj.string("videoCount")?.let { "$it videos" },
            thumbnailUrl = bestThumbnail(obj["thumbnails"] ?: obj["thumbnail"]).orEmpty(),
        )
    }

    private fun extractContinuation(root: JsonElement): String? {
        val continuationItems = findObjectsByKey(root, setOf("continuationItemRenderer"))
        for (item in continuationItems) {
            val token =
                item.obj("continuationEndpoint")?.obj("continuationCommand")?.string("token")
                    ?: item
                        .obj("button")
                        ?.obj("buttonRenderer")
                        ?.obj("command")
                        ?.obj("continuationCommand")
                        ?.string("token")
            if (!token.isNullOrBlank()) return token
        }
        return findObjectsByKey(root, setOf("nextContinuationData"))
            .firstNotNullOfOrNull { it.string("continuation")?.takeIf(String::isNotBlank) }
    }

    private fun findMetadataRowsText(viewModel: JsonObject?): List<String> {
        viewModel ?: return emptyList()
        return viewModel
            .obj("metadata")
            ?.obj("contentMetadataViewModel")
            ?.array("metadataRows")
            .orEmpty()
            .flatMap { row ->
                (row as? JsonObject)
                    ?.array("metadataParts")
                    .orEmpty()
                    .mapNotNull { part -> (part as? JsonObject)?.obj("text")?.string("content") }
            }
    }

    internal fun parseDurationSeconds(raw: String): Long? {
        val clean = raw.trim()
        if (clean.isEmpty() || !clean.all { it.isDigit() || it == ':' }) return null
        val parts = clean.split(':')
        if (parts.size !in 2..3) return null
        var total = 0L
        for (part in parts) {
            val value = part.toLongOrNull() ?: return null
            total = total * 60L + value
        }
        return total.takeIf { it >= 0 }
    }

    private fun bestThumbnail(element: JsonElement?): String? {
        val list =
            when (element) {
                is JsonArray -> {
                    element
                }

                is JsonObject -> {
                    element.array("thumbnails")
                        ?: element.array("sources")
                        ?: (element["thumbnails"] as? JsonArray)
                }

                else -> {
                    null
                }
            } ?: return null
        val best =
            list
                .mapNotNull { it as? JsonObject }
                .maxByOrNull { (it["width"] as? JsonPrimitive)?.intOrNull ?: 0 }
                ?.string("url")
                ?: return null
        return if (best.startsWith("//")) "https:$best" else best
    }

    private fun findObjectsByKey(
        root: JsonElement,
        keys: Set<String>,
    ): List<JsonObject> {
        val out = mutableListOf<JsonObject>()
        val queue = ArrayDeque<JsonElement>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < MAX_TRAVERSAL_NODES) {
            visited++
            when (val current = queue.removeFirst()) {
                is JsonArray -> {
                    for (child in current) queue.add(child)
                }

                is JsonObject -> {
                    for ((key, value) in current) {
                        if (key in keys && value is JsonObject) {
                            out += value
                        }
                        if (value is JsonObject || value is JsonArray) {
                            queue.add(value)
                        }
                    }
                }

                else -> {}
            }
        }
        return out
    }

    private fun collectStringsForKey(
        root: JsonElement,
        key: String,
    ): List<String> {
        val out = mutableListOf<String>()
        val queue = ArrayDeque<JsonElement>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < 2000) {
            visited++
            when (val current = queue.removeFirst()) {
                is JsonArray -> {
                    for (child in current) queue.add(child)
                }

                is JsonObject -> {
                    current.string(key)?.let { out += it }
                    for (value in current.values) {
                        if (value is JsonObject || value is JsonArray) queue.add(value)
                    }
                }

                else -> {}
            }
        }
        return out
    }

    private fun text(element: JsonElement?): String? =
        when (element) {
            is JsonPrimitive -> {
                element.contentOrNull?.takeIf { it.isNotBlank() }
            }

            is JsonObject -> {
                element.string("simpleText")
                    ?: element.string("content")
                    ?: element
                        .array("runs")
                        ?.mapNotNull { (it as? JsonObject)?.string("text") }
                        ?.joinToString("")
                        ?.takeIf { it.isNotBlank() }
            }

            else -> {
                null
            }
        }

    private fun YouTubeFeedItem.itemKey(): String? =
        video?.let { "v:${it.id}" }
            ?: channel?.let { "c:${it.id}" }
            ?: playlist?.let { "p:${it.id}" }

    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

    private fun JsonObject.array(key: String): JsonArray? = this[key] as? JsonArray

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
}
