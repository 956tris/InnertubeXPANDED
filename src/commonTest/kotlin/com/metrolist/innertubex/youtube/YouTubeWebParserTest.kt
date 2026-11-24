package com.metrolist.innertubex.youtube

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
}
