package com.daddylive

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.Jsoup
import android.util.Base64
import java.net.URLEncoder
import org.json.JSONObject

// Provide destructuring helpers for UShortArray if used elsewhere (component1/component2)
operator fun UShortArray.component1(): UShort = this.getOrNull(0) ?: 0u
operator fun UShortArray.component2(): UShort = this.getOrNull(1) ?: 0u

class DaddyLiveProvider : MainAPI() { // All providers must be an instance of MainAPI
    // Use the exact base URL from the original Kodi plugin
    override var mainUrl = "https://dlhd.dad"
    override var name = "DaddyLive V2"
    override val supportedTypes = setOf(TvType.Live)

    override var lang = "en"

    // Enable this when your provider has a main page
    override val hasMainPage = true

    // Mirror Kodi plugin's variables.py
    private val baseUrl = "https://dlhd.dad"
    private val baseUrlOld = "https://daddylivestream.com"
    private val channelsUrl = "$baseUrl/24-7-channels.php"
    private val scheduleUrl = "$baseUrl/index.php"
    private val userAgent = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Mobile Safari/537.36"
    private val sharedHeaders = mutableMapOf(
        "User-Agent" to userAgent,
        "Referer" to "$baseUrl/",
        "Origin" to "$baseUrl/"
    )
    // In-memory cache of schedule events (refreshed per fetch)
    private var cachedSchedule: List<ScheduleEvent>? = null

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val channels = fetchChannels()
        val items = channels.map { channel ->
            newMovieSearchResponse(
                name = channel.title,
                url = channel.link,
                type = TvType.Live
            )
        }
        return newHomePageResponse(listOf(HomePageList("Channels", items)))
    }

    // This function gets called when you search for something
    override suspend fun search(query: String): List<SearchResponse> {
        val channels = fetchChannels()
        val events = fetchScheduleEvents()
        val results = mutableListOf<SearchResponse>()

        // Search channels
        channels.filter { it.title.contains(query, ignoreCase = true) }.forEach { channel ->
            results.add(
                newMovieSearchResponse(
                    name = channel.title,
                    url = channel.link,
                    type = TvType.Live
                )
            )
        }

        // Search events (match title)
        events.filter { it.event.contains(query, ignoreCase = true) }.forEach { event ->
            results.add(
                newMovieSearchResponse(
                    name = "${event.time} - ${event.event}",
                    url = event.event, // will be used to lookup channels with getMatchLinks
                    type = TvType.Live
                )
            )
        }

        return results
    }

    override suspend fun load(url: String): LoadResponse {
        // For live streams, url is the stream path like /stream/stream-123.php
        return newMovieLoadResponse(
            name = "Live Stream",
            url = url,
            type = TvType.Live,
            data = url
        )
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (!data.startsWith("/") && !data.startsWith("http")) {
            val matches = getMatchLinks(data)
            var handledEvent = false
            for ((title, link) in matches) {
                val resolved = resolveLink(link) ?: continue
                callback.invoke(buildExtractorLink(title, resolved))
                handledEvent = true
            }
            if (handledEvent) return true
        }

        val candidateLinks = mutableListOf<String>()
        val trimmedData = data.trim()
        if (trimmedData.startsWith("[[")) {
            try {
                val json = org.json.JSONArray(trimmedData)
                for (i in 0 until json.length()) {
                    val arr = json.optJSONArray(i)
                    if (arr != null && arr.length() > 1) {
                        candidateLinks.add(arr.optString(1))
                    }
                }
            } catch (_: Exception) {
            }
        } else if (data.startsWith("/") || data.startsWith("http", ignoreCase = true)) {
            candidateLinks.add(data)
        }

        var handled = false
        for (link in candidateLinks) {
            val resolved = resolveLink(link)
            if (resolved != null) {
                callback.invoke(buildExtractorLink(this.name, resolved))
                handled = true
                continue
            }

            val absoluteLink = when {
                link.startsWith("http", ignoreCase = true) -> link
                link.startsWith("/", ignoreCase = true) -> "$baseUrl$link"
                else -> "$baseUrl/$link"
            }

            val loaded = loadExtractor(absoluteLink, subtitleCallback, callback)
            if (loaded) handled = true
        }

        return handled
    }

    private suspend fun fetchChannels(): List<Channel> {
        return try {
            val response = getWithHeaders(channelsUrl)
            val soup = Jsoup.parse(response.text)
            val channels = mutableListOf<Channel>()
            soup.select(".card").forEach { card ->
                val title = card.selectFirst("div")?.text() ?: ""
                val href = "$baseUrl${card.attr("href")}"
                val queryParams = href.substringAfter("?")
                val channelId = queryParams.split("&").find { it.startsWith("id=") }?.substringAfter("id=") ?: ""
                val link = "/stream/stream-$channelId.php"
                channels.add(Channel(title, link))
            }
            channels
        } catch (e: Exception) {
            // Fallback to old URL
            try {
                val response = getWithHeaders("$baseUrl/24-7-channels.php") // Assuming old is same
                val soup = Jsoup.parse(response.text)
                val channels = mutableListOf<Channel>()
                soup.select("a").drop(8).forEach { a ->
                    val title = a.text()
                    val link = a.attr("href")
                    if (link !in channels.map { it.link }) {
                        channels.add(Channel(title, link))
                    }
                }
                channels
            } catch (e2: Exception) {
                emptyList()
            }
        }
    }

    private suspend fun fetchScheduleEvents(): List<ScheduleEvent> {
        // Return cached schedule if present
        cachedSchedule?.let { return it }
        val events = mutableListOf<ScheduleEvent>()
        try {
            val response = getWithHeaders(scheduleUrl)
            val soup = Jsoup.parse(response.text)
            val days = soup.select(".schedule__day")
            for (day in days) {
                val categories = day.select(".schedule__category.is-expanded")
                if (categories.isEmpty()) continue
                val sDate = day.selectFirst(".schedule__dayTitle")?.text()?.split(" -")?.get(0)?.trim() ?: ""
                for (cat in categories) {
                    val catName = cat.selectFirst(".card__meta")?.text() ?: ""
                    val eventElems = cat.select(".schedule__event")
                    for (ev in eventElems) {
                        val eTime = ev.selectFirst(".schedule__time")?.text() ?: ""
                        val title = ev.selectFirst(".schedule__eventTitle")?.text() ?: ""
                        val channels = mutableListOf<ChannelRef>()
                        val chContainer = ev.selectFirst(".schedule__channels")
                        chContainer?.select("a")?.forEach { a ->
                            val href = a.attr("href")
                            val linkFull = if (href.startsWith("http")) href else "$baseUrl$href"
                            val channelId = try { java.net.URI(linkFull).query?.split("&")?.find { it.startsWith("id=") }?.substringAfter("id=") ?: "" } catch (e: Exception) { "" }
                            channels.add(ChannelRef(a.text(), channelId))
                        }
                        events.add(ScheduleEvent(date = sDate, category = catName, time = eTime, event = title, channels = channels))
                    }
                }
            }
        } catch (e: Exception) {
            // ignore and return what we have
        }
        cachedSchedule = events
        return events
    }

    // Given an event title, return a list of (channelName, streamPath) pairs
    private suspend fun getMatchLinks(eventTitle: String): List<Pair<String, String>> {
        val events = fetchScheduleEvents()
        val matches = events.filter {
            it.event.equals(eventTitle, ignoreCase = true) || it.event.contains(eventTitle, ignoreCase = true)
        }
        val results = mutableListOf<Pair<String, String>>()
        for (m in matches) {
            for (ch in m.channels) {
                if (ch.channelId.isNotBlank()) {
                    results.add(Pair(ch.channelName, "/stream/stream-${ch.channelId}.php"))
                }
            }
        }
        return results
    }

    // removed duplicate stub

    private suspend fun resolveLink(url: String): String? {
        try {
            sharedHeaders["Referer"] = "$baseUrl/"
            sharedHeaders["Origin"] = "$baseUrl/"
            val php = url.substringAfterLast('/')
            val candidates = mutableListOf<String>()
            if (php.endsWith(".php")) {
                val allowed = listOf("stream", "cast", "watch", "plus", "casting", "player")
                for (t in allowed) {
                    candidates.add("$baseUrl/$t/$php")
                    if (baseUrlOld.isNotEmpty()) candidates.add("$baseUrlOld/$t/$php")
                }
            } else {
                candidates.add(if (url.startsWith("http")) url else if (url.startsWith("/")) "$baseUrl$url" else "$baseUrl/$url")
            }

            for (candidate in candidates) {
                try {
                    val resp = getWithHeaders(candidate, timeout = 30L)
                    val soup = Jsoup.parse(resp.text)
                    val iframe = soup.selectFirst("iframe#thatframe, iframe.video, iframe") ?: continue
                    var url2 = iframe.attr("src")

                    // Follow wrappers (lovecdn/wikisport)
                    if (url2.contains("wikisport") || url2.contains("lovecdn")) {
                        val r2 = getWithHeaders(url2, referer = candidate, timeout = 60L)
                        val s2 = Jsoup.parse(r2.text)
                        val iframe2 = s2.selectFirst("iframe") ?: continue
                        url2 = iframe2.attr("src")
                        if (url2.contains("lovecdn")) {
                            val m3u8 = url2.replace("embed.html", "index.fmp4.m3u8")
                            return "$m3u8|Referer=$url2&Connection=Keep-Alive&User-Agent=$userAgent"
                        }
                    }

                    // Fetch the target and inspect
                    val targetResp = getWithHeaders(url2, referer = candidate)
                    val text = targetResp.text

                    // newkso / top2 logic
                    val channelKeyRegex = Regex("const\\s+CHANNEL_KEY\\s*=\\s*\"([^\"]+)\"")
                    val channelKey = channelKeyRegex.find(text)?.groupValues?.get(1)
                    if (channelKey != null) {
                        val bundleRegex = Regex("const\\s+[A-Z]+\\s*=\\s*\"([^\"]+)\"")
                        val bundle = bundleRegex.find(text)?.groupValues?.get(1) ?: return null
                        val decodedBundle = base64Decode(bundle)
                        val parts = JSONObject(decodedBundle)
                        val ts = URLEncoder.encode(parts.getString("b_ts"), "UTF-8")
                        val rnd = URLEncoder.encode(parts.getString("b_rnd"), "UTF-8")
                        val sig = URLEncoder.encode(parts.getString("b_sig"), "UTF-8")
                        val bx = listOf(40, 60, 61, 33, 103, 57, 33, 57)
                        val sc = bx.map { (it xor 73).toChar() }.joinToString("")
                        val host = "https://top2new.newkso.ru/"
                        val authUrl = "$host$sc?channel_id=${URLEncoder.encode(channelKey, "UTF-8")}&ts=$ts&rnd=$rnd&sig=$sig"
                        getWithHeaders(authUrl, referer = url2)
                        val serverLookupUrl = "https://${java.net.URI(url2).host}/server_lookup.php?channel_id=$channelKey"
                        val serverResponse = getWithHeaders(serverLookupUrl, referer = url2)
                        val serverJson = serverResponse.parsedSafe<Map<String, String>>() ?: return null
                        val serverKey = serverJson["server_key"] ?: return null
                        val m3u8 = if (serverKey == "top1/cdn") {
                            "https://top1.newkso.ru/top1/cdn/$channelKey/mono.m3u8"
                        } else {
                            "https://$serverKey.new.newkso.ru/$serverKey/$channelKey/mono.m3u8"
                        }
                        val referer = "https://${java.net.URI(url2).host}"
                        return "$m3u8|Referer=$referer/&Origin=$referer&Connection=Keep-Alive&User-Agent=$userAgent"
                    }

                    // atob('...') pattern -> find initUrl
                    val atobRegex = Regex("atob\\('([^']+)'\\)")
                    val atobMatch = atobRegex.find(text)
                    if (atobMatch != null) {
                        val b64 = atobMatch.groupValues[1]
                        val decoded = String(Base64.decode(b64, Base64.DEFAULT))
                        val initUrlRegex = Regex("initUrl\\s*=\\s*\"([^\"]+)\"")
                        val initUrl = initUrlRegex.find(decoded)?.groupValues?.get(1)
                        if (initUrl != null) {
                            val r = getWithHeaders(initUrl)
                            val m = String(Base64.decode(r.text.toByteArray(), Base64.DEFAULT))
                            val referer = "https://${java.net.URI(url2).host}"
                            return "$m|Referer=$url2&Connection=Keep-Alive&User-Agent=$userAgent"
                        }
                    }

                    // blogspot pattern
                    if (url2.contains("blogspot.com")) {
                        val channelId = try { java.net.URI(url2).query?.split("&")?.find { it.startsWith("id=") }?.substringAfter("id=") } catch (e: Exception) { null }
                        if (channelId != null) {
                            val pattern = Regex("\"${Regex.escape(channelId)}\"\\s*:\\s*\\{[^}]*?url:\\s*\"([^\"]+)\"", RegexOption.DOT_MATCHES_ALL)
                            val m = pattern.find(text)
                            if (m != null) {
                                val m3u8 = m.groupValues[1]
                                val referer = "https://${java.net.URI(url2).host}"
                                return "$m3u8|Referer=$referer/&Origin=$referer&Connection=Keep-Alive&User-Agent=$userAgent"
                            }
                        }
                    }

                    // var PlayS pattern
                    val playSRegex = Regex("var\\s+PlayS\\s*=\\s*'([^']+)'")
                    val playSMatch = playSRegex.find(text)
                    if (playSMatch != null) {
                        val m3u8 = playSMatch.groupValues[1]
                        val referer = "https://${java.net.URI(url2).host}"
                        return "$m3u8|Referer=$referer/&Origin=$referer&Connection=Keep-Alive&User-Agent=$userAgent"
                    }
                } catch (_: Exception) {
                    continue
                }
            }
        } catch (e: Exception) {
            // ignore
        }
        return null
    }

    private fun updateHeaders(referer: String?): Pair<MutableMap<String, String>, String?> {
        val newReferer = referer?.takeIf { it.isNotBlank() }
        if (newReferer != null) {
            sharedHeaders["Referer"] = newReferer
            sharedHeaders["Origin"] = newReferer
        }
        if (!sharedHeaders.containsKey("Referer")) {
            sharedHeaders["Referer"] = "$baseUrl/"
        }
        if (!sharedHeaders.containsKey("Origin")) {
            sharedHeaders["Origin"] = "$baseUrl/"
        }
        return sharedHeaders to sharedHeaders["Referer"]
    }

    private suspend fun getWithHeaders(
        url: String,
        referer: String? = null,
        timeout: Long = 30L
    ): com.lagradost.nicehttp.NiceResponse {
        val (headers, effectiveReferer) = updateHeaders(referer)
        val timeoutMs = timeout
        return runCatching {
            app.get(
                url,
                referer = effectiveReferer,
                headers = headers,
                timeout = timeoutMs
            )
        }.getOrElse { error ->
            if (url.startsWith("https://")) {
                val fallbackUrl = "http://" + url.removePrefix("https://")
                return app.get(
                    fallbackUrl,
                    referer = effectiveReferer,
                    headers = headers,
                    timeout = timeoutMs
                )
            } else {
                throw error
            }
        }
    }

    private suspend fun buildExtractorLink(displayName: String, resolved: String): ExtractorLink {
        val (streamUrl, extraHeaders) = splitResolvedLink(resolved)
        val headerMap = extraHeaders.toMutableMap()
        headerMap.putIfAbsent("User-Agent", userAgent)
        val refererHeader = headerMap["Referer"] ?: "$baseUrl/"
        headerMap.putIfAbsent("Referer", refererHeader)
        headerMap.putIfAbsent("Origin", refererHeader)
        headerMap.putIfAbsent("Connection", "Keep-Alive")

        // Return ExtractorLink with explicit headers set so CloudStream will use them
        return newExtractorLink(
            source = this.name,
            name = displayName,
            url = streamUrl,
            type = ExtractorLinkType.M3U8
        ) {
            this.referer = refererHeader
            this.quality = Qualities.Unknown.value
            this.headers = headerMap.toMap()
        }
    }

    private fun splitResolvedLink(resolved: String): Pair<String, Map<String, String>> {
        val segments = resolved.split("|", limit = 2)
        if (segments.size == 1) return segments[0] to emptyMap()
        val headers = mutableMapOf<String, String>()
        segments[1].split("&").forEach { fragment ->
            if (fragment.isBlank()) return@forEach
            val kv = fragment.split("=", limit = 2)
            if (kv.size == 2) {
                headers[kv[0]] = kv[1]
            }
        }
        return segments[0] to headers
    }

    private fun base64Decode(str: String): String {
        return String(Base64.decode(str, Base64.DEFAULT))
    }

    data class Channel(val title: String, val link: String)
    data class ChannelRef(val channelName: String, val channelId: String)
    data class ScheduleEvent(val date: String, val category: String, val time: String, val event: String, val channels: List<ChannelRef>)
}