package com.metrolist.innertubex.youtube

import com.metrolist.innertubex.InnerTube
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class YouTubeWebParserTest {
    @Test
    fun parsesHomeFeedShelvesViewModelsShortsAndContinuation() {
        val root =
            Json
                .parseToJsonElement(
                    """
                    {
                      "contents": {
                        "twoColumnBrowseResultsRenderer": {
                          "tabs": [{
                            "tabRenderer": {
                              "content": {
                                "richGridRenderer": {
                                  "contents": [
                                    {
                                      "richItemRenderer": {
                                        "content": {
                                          "videoRenderer": {
                                            "videoId": "abcdefghijk",
                                            "title": {"runs": [{"text": "4K Demo Video"}]},
                                            "ownerText": {"runs": [{"text": "Creator One", "navigationEndpoint": {"browseEndpoint": {"browseId": "UCcreator01"}}}]},
                                            "lengthText": {"simpleText": "1:02:03"},
                                            "shortViewCountText": {"simpleText": "1.2M views"},
                                            "publishedTimeText": {"simpleText": "2 days ago"},
                                            "badges": [{"metadataBadgeRenderer": {"label": "4K"}}],
                                            "thumbnail": {"thumbnails": [{"url": "https://i.ytimg.com/vi/abcdefghijk/maxresdefault.jpg", "width": 1280}]}
                                          }
                                        }
                                      }
                                    },
                                    {
                                      "richSectionRenderer": {
                                        "content": {
                                          "reelShelfRenderer": {
                                            "title": {"simpleText": "Shorts"},
                                            "contents": [
                                              {
                                                "shortsLockupViewModel": {
                                                  "onTap": {"innertubeCommand": {"reelWatchEndpoint": {"videoId": "shortVideo1"}}},
                                                  "overlayMetadata": {
                                                    "primaryText": {"content": "Quick Clip"},
                                                    "secondaryText": {"content": "500K views"}
                                                  }
                                                }
                                              }
                                            ]
                                          }
                                        }
                                      }
                                    },
                                    {
                                      "continuationItemRenderer": {
                                        "continuationEndpoint": {"continuationCommand": {"token": "cont-home-2"}}
                                      }
                                    }
                                  ]
                                }
                              }
                            }
                          }]
                        }
                      }
                    }
                    """.trimIndent(),
                ).jsonObject

        val page = YouTubeWebParser.parseFeedPage(root)
        assertEquals("cont-home-2", page.continuation)
        assertEquals(2, page.items.size)
        val first = assertNotNull(page.items.first().video)
        assertEquals("abcdefghijk", first.id)
        assertEquals("4K Demo Video", first.title)
        assertEquals("Creator One", first.channelName)
        assertEquals("UCcreator01", first.channelId)
        assertEquals(3723L, first.durationSeconds)
        assertTrue(first.is4k)
        assertFalse(first.isShort)

        val shortsShelf = page.shelves.first { it.isShorts }
        assertEquals("Shorts", shortsShelf.title)
        val shortVideo = assertNotNull(shortsShelf.items.single().video)
        assertEquals("shortVideo1", shortVideo.id)
        assertTrue(shortVideo.isShort)
    }

    @Test
    fun parsesLockupSearchChannelPlaylistNextChaptersAndCaptions() {
        val searchRoot =
            Json
                .parseToJsonElement(
                    """
                    {
                      "contents": {
                        "twoColumnSearchResultsRenderer": {
                          "primaryContents": {
                            "sectionListRenderer": {
                              "contents": [{
                                "itemSectionRenderer": {
                                  "contents": [
                                    {
                                      "lockupViewModel": {
                                        "contentType": "LOCKUP_CONTENT_TYPE_VIDEO",
                                        "contentId": "vidLockup01",
                                        "metadata": {
                                          "lockupMetadataViewModel": {
                                            "title": {"content": "Lockup Track"},
                                            "metadata": {
                                              "contentMetadataViewModel": {
                                                "metadataRows": [
                                                  {"metadataParts": [{"text": {"content": "Lockup Channel"}}]},
                                                  {"metadataParts": [{"text": {"content": "10K views"}}]}
                                                ]
                                              }
                                            }
                                          }
                                        }
                                      }
                                    },
                                    {
                                      "channelRenderer": {
                                        "channelId": "UCchan123",
                                        "title": {"simpleText": "Channel Title"},
                                        "subscriberCountText": {"simpleText": "@channeltitle"},
                                        "videoCountText": {"simpleText": "2M subscribers"}
                                      }
                                    },
                                    {
                                      "playlistRenderer": {
                                        "playlistId": "PLlist123",
                                        "title": {"simpleText": "Playlist Title"},
                                        "shortBylineText": {"runs": [{"text": "Channel Title"}]},
                                        "videoCount": "15"
                                      }
                                    }
                                  ]
                                }
                              }]
                            }
                          }
                        }
                      }
                    }
                    """.trimIndent(),
                ).jsonObject
        val search = YouTubeWebParser.parseFeedPage(searchRoot)
        assertEquals(
            "vidLockup01",
            search.items
                .mapNotNull { it.video }
                .single()
                .id,
        )
        assertEquals(
            "UCchan123",
            search.items
                .mapNotNull { it.channel }
                .single()
                .id,
        )
        assertEquals(
            "PLlist123",
            search.items
                .mapNotNull { it.playlist }
                .single()
                .id,
        )

        val nextRoot =
            Json
                .parseToJsonElement(
                    """
                    {
                      "currentVideoEndpoint": {"watchEndpoint": {"videoId": "abcdefghijk"}},
                      "contents": {
                        "twoColumnWatchNextResults": {
                          "results": {
                            "results": {
                              "contents": [
                                {"videoPrimaryInfoRenderer": {"title": {"runs": [{"text": "Current Video"}]}}},
                                {"videoSecondaryInfoRenderer": {"owner": {"videoOwnerRenderer": {"title": {"runs": [{"text": "Owner"}]}, "navigationEndpoint": {"browseEndpoint": {"browseId": "UCowner"}}}}}}
                              ]
                            }
                          },
                          "secondaryResults": {
                            "secondaryResults": {
                              "results": [
                                {"compactVideoRenderer": {"videoId": "nextVideo01", "title": {"simpleText": "Up Next"}, "lengthText": {"simpleText": "04:15"}}},
                                {"continuationItemRenderer": {"continuationEndpoint": {"continuationCommand": {"token": "cont-next"}}}}
                              ]
                            }
                          }
                        }
                      },
                      "engagementPanels": [{
                        "engagementPanelSectionListRenderer": {
                          "content": {
                            "macroMarkersListRenderer": {
                              "contents": [
                                {"macroMarkersListItemRenderer": {"title": {"simpleText": "Intro"}, "timeDescription": {"simpleText": "0:00"}}},
                                {"macroMarkersListItemRenderer": {"title": {"simpleText": "Chorus"}, "onTap": {"watchEndpoint": {"startTimeSeconds": "45"}}}}
                              ]
                            }
                          }
                        }
                      }]
                    }
                    """.trimIndent(),
                ).jsonObject
        val next = YouTubeWebParser.parseNextPage(nextRoot)
        assertEquals("Current Video", next.video?.title)
        assertEquals("nextVideo01", next.related.single().id)
        assertEquals(255L, next.related.single().durationSeconds)
        assertEquals("cont-next", next.continuation)
        assertEquals(listOf(0L, 45_000L), next.chapters.map { it.startMs })

        val json3 =
            Json
                .parseToJsonElement(
                    """
                    {
                      "events": [
                        {"tStartMs": 1000, "dDurationMs": 2000, "segs": [{"utf8": "Hello "}, {"utf8": "world"}]}
                      ]
                    }
                    """.trimIndent(),
                ).jsonObject
        val lines = YouTubeWebParser.parseTimedTextJson3(json3)
        assertEquals("Hello world", lines.single().text)
        assertEquals(1000L, lines.single().startMs)
    }

    @Test
    fun parsesAccountInfoAndResolvedBrowseId() {
        val accountRoot =
            Json
                .parseToJsonElement(
                    """
                    {
                      "actions": [{
                        "openPopupAction": {
                          "popup": {
                            "multiPageMenuRenderer": {
                              "header": {
                                "activeAccountHeaderRenderer": {
                                  "accountName": {"simpleText": "Demo User"},
                                  "channelHandle": {"simpleText": "@demouser"}
                                }
                              }
                            }
                          }
                        }
                      }]
                    }
                    """.trimIndent(),
                ).jsonObject
        val account = assertNotNull(YouTubeWebParser.parseAccountInfo(accountRoot))
        assertEquals("Demo User", account.name)
        assertEquals("@demouser", account.handle)

        val resolved =
            Json
                .parseToJsonElement("""{"endpoint":{"browseEndpoint":{"browseId":"UCresolved123"}}}""")
                .jsonObject
        assertEquals("UCresolved123", YouTubeWebParser.parseResolvedBrowseId(resolved))
    }

    @Test
    fun parsesRichShelfTitleAndBottomOverlayLockupDuration() {
        val root =
            Json
                .parseToJsonElement(
                    """
                    {
                      "contents": {
                        "twoColumnBrowseResultsRenderer": {
                          "tabs": [{
                            "tabRenderer": {
                              "content": {
                                "richGridRenderer": {
                                  "contents": [{
                                    "richSectionRenderer": {
                                      "content": {
                                        "richShelfRenderer": {
                                          "title": {"simpleText": "New & Trending Songs"},
                                          "contents": [{
                                            "richItemRenderer": {
                                              "content": {
                                                "lockupViewModel": {
                                                  "contentType": "LOCKUP_CONTENT_TYPE_VIDEO",
                                                  "contentId": "2Tp9lBaj7W8",
                                                  "contentImage": {
                                                    "thumbnailViewModel": {
                                                      "image": {"sources": [{"url": "https://i.ytimg.com/vi/2Tp9lBaj7W8/hq720.jpg", "width": 720}]},
                                                      "overlays": [{
                                                        "thumbnailBottomOverlayViewModel": {
                                                          "badges": [{
                                                            "thumbnailBadgeViewModel": {
                                                              "text": "3:23"
                                                            }
                                                          }]
                                                        }
                                                      }]
                                                    }
                                                  },
                                                  "metadata": {
                                                    "lockupMetadataViewModel": {
                                                      "title": {"content": "Say Now - Hot & talking"},
                                                      "metadata": {
                                                        "contentMetadataViewModel": {
                                                          "metadataRows": [
                                                            {"metadataParts": [{"text": {"content": "Say Now"}}]},
                                                            {"metadataParts": [{"text": {"content": "250K views"}}]}
                                                          ]
                                                        }
                                                      }
                                                    }
                                                  }
                                                }
                                              }
                                            }
                                          }]
                                        }
                                      }
                                    }
                                  }]
                                }
                              }
                            }
                          }]
                        }
                      }
                    }
                    """.trimIndent(),
                ).jsonObject

        val page = YouTubeWebParser.parseFeedPage(root)
        val shelf = page.shelves.single()
        assertEquals("New & Trending Songs", shelf.title)
        val video = assertNotNull(shelf.items.single().video)
        assertEquals("2Tp9lBaj7W8", video.id)
        assertEquals(203L, video.durationSeconds)
        assertEquals("https://i.ytimg.com/vi/2Tp9lBaj7W8/hq720.jpg", video.thumbnailUrl)
    }

    @Test
    fun homeFallsBackToPublicFeedsWhenWhatToWatchReturnsNudgeOnly() =
        runBlocking {
            val browseIds = mutableListOf<String>()
            val http =
                HttpClient(
                    MockEngine { request ->
                        val text = (request.body as io.ktor.http.content.TextContent).text
                        val obj = Json.parseToJsonElement(text).jsonObject
                        val browseId =
                            obj["browseId"]
                                ?.toString()
                                ?.trim('"')
                                .orEmpty()
                        browseIds += browseId
                        val payload =
                            when (browseId) {
                                "FEwhat_to_watch" -> {
                                    """{"contents":{"twoColumnBrowseResultsRenderer":{"tabs":[{"tabRenderer":{"content":{"richGridRenderer":{"contents":[{"richSectionRenderer":{"content":{"feedNudgeRenderer":{"title":{"runs":[{"text":"Your watch history is off"}]}}}}}]}}}}]}}}"""
                                }

                                "UC-9-kyTW8ZkZNDHQJ6FgpwQ" -> {
                                    """{"contents":{"twoColumnBrowseResultsRenderer":{"tabs":[{"tabRenderer":{"content":{"richGridRenderer":{"contents":[{"richSectionRenderer":{"content":{"richShelfRenderer":{"title":{"simpleText":"Trending Music"},"contents":[{"richItemRenderer":{"content":{"videoRenderer":{"videoId":"publicVid01","title":{"simpleText":"Trending Track"},"ownerText":{"runs":[{"text":"Artist A"}]}}}}}]}}}}]}}}}]}}}"""
                                }

                                else -> {
                                    """{"contents":{"twoColumnBrowseResultsRenderer":{"tabs":[{"tabRenderer":{"content":{"richGridRenderer":{"contents":[{"richSectionRenderer":{"content":{"richShelfRenderer":{"title":{"simpleText":"Live Now"},"contents":[{"richItemRenderer":{"content":{"videoRenderer":{"videoId":"liveVid0001","title":{"simpleText":"Live Stream"},"lengthText":{"simpleText":"LIVE"}}}}}]}}}}]}}}}]}}}"""
                                }
                            }
                        respond(
                            content = payload,
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                        )
                    },
                ) {
                    install(ContentNegotiation) { json() }
                }
            val tube = InnerTube(http)
            try {
                val web = YouTubeWebClient(tube)
                val page = web.home()
                assertEquals(
                    listOf("FEwhat_to_watch", "UC-9-kyTW8ZkZNDHQJ6FgpwQ", "UC4R8DWoMoI7CAwX8_LjQHig"),
                    browseIds,
                )
                assertEquals(listOf("Trending Music", "Live Now"), page.shelves.map { it.title })
                assertEquals(
                    "publicVid01",
                    page.items
                        .first()
                        .video
                        ?.id,
                )
                assertEquals("yt-public-home:2", page.continuation)
            } finally {
                tube.close()
                http.close()
            }
        }
}
