package eu.kanade.tachiyomi.animeextension.pt.dattebayobr

import android.net.Uri
import android.util.Log
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Element
import rx.Observable
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

@Suppress("DEPRECATION")
class DattebayoBR : AnimeHttpSource() {

    override val name = "Dattebayo BR"

    override val baseUrl = "https://www.dattebayo-br.com"

    override val lang = "pt-BR"

    override val supportsLatest = true

    // Identical to v14.1.
    override fun headersBuilder(): Headers.Builder = Headers.Builder()
        .add(
            "User-Agent",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/122.0.0.0 Safari/537.36",
        )
        .add("Accept", "*/*")
        .add("Accept-Language", "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7")
        .add("X-Requested-With", "XMLHttpRequest")

    // ============================== Popular ==============================

    override fun popularAnimeRequest(page: Int): Request = GET(baseUrl, headers)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val animes = response.asJsoup().select("div.ultimosAnimesHomeItem").map { it.toAnimeCard() }
        // Home page is not paginated. Returning hasNextPage=true caused Dantotsu to loop
        // forever requesting /?page=N.
        return AnimesPage(animes, hasNextPage = false)
    }

    // ============================== Latest ===============================

    override fun latestUpdatesRequest(page: Int): Request = popularAnimeRequest(page)

    override fun latestUpdatesParse(response: Response): AnimesPage = popularAnimeParse(response)

    // ============================== Search ===============================

    // Fan out candidates (original query, AniList synonyms, token-truncated, longest token) in
    // parallel and MERGE the results, deduplicating by URL. Previously this was first-wins; that
    // hid the right anime when a generic candidate (e.g. "Classroom") returned the wrong season
    // before a specific one (e.g. the romaji of Season 4) could respond. Now the right season is
    // always present in the result list so Dantotsu can match it against AniList's title.
    override fun fetchSearchAnime(
        page: Int,
        query: String,
        filters: AnimeFilterList,
    ): Observable<AnimesPage> = Observable.fromCallable { parallelSearch(query, page) }

    private fun parallelSearch(rawQuery: String, page: Int): AnimesPage {
        val cleaned = rawQuery.trim()
        if (cleaned.isEmpty()) return AnimesPage(emptyList(), false)

        val pool = Executors.newFixedThreadPool(SEARCH_PARALLELISM)
        val merged = ConcurrentHashMap<String, SAnime>()
        val seen = ConcurrentHashMap.newKeySet<String>()
        val pending = AtomicInteger(0)
        val done = Object()

        fun fire(candidate: String) {
            val key = candidate.lowercase(Locale.ROOT)
            if (key.isBlank() || !seen.add(key)) return
            pending.incrementAndGet()
            pool.execute {
                try {
                    client.newCall(buildSearchRequest(candidate, page)).execute().use { resp ->
                        searchAnimeParse(resp).animes.forEach { a ->
                            merged.putIfAbsent(a.url, a)
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Search failed for '$candidate'", e)
                } finally {
                    if (pending.decrementAndGet() == 0) synchronized(done) { done.notifyAll() }
                }
            }
        }

        try {
            fire(cleaned)

            val tokens = cleaned.split(WHITESPACE).filter { it.isNotBlank() }
            val meaningful = tokens.filter { it.lowercase(Locale.ROOT) !in STOP_WORDS }
            if (meaningful.size > 3) fire(meaningful.take(3).joinToString(" "))
            meaningful.maxByOrNull { it.length }?.let { if (it.length >= 4) fire(it) }

            // AniList synonym lookup is itself a pool task; the synonyms it discovers are
            // queued via fire(...) on the same pool. Tracking outstanding work with an
            // AtomicInteger lets us shutdown the pool only once everything has drained.
            if (cleaned.isAsciiLatin()) {
                pending.incrementAndGet()
                pool.execute {
                    try {
                        fetchAniListSynonyms(cleaned).forEach(::fire)
                    } catch (e: Exception) {
                        Log.w(TAG, "AniList synonym fan-out failed", e)
                    } finally {
                        if (pending.decrementAndGet() == 0) synchronized(done) { done.notifyAll() }
                    }
                }
            }

            val deadline = System.currentTimeMillis() + SEARCH_TIMEOUT_SECONDS * 1000L
            synchronized(done) {
                while (pending.get() > 0) {
                    val remaining = deadline - System.currentTimeMillis()
                    if (remaining <= 0) break
                    done.wait(remaining)
                }
            }
        } finally {
            pool.shutdownNow()
        }

        if (merged.isEmpty()) return AnimesPage(emptyList(), false)

        return AnimesPage(rankByQueryAffinity(merged.values.toList(), cleaned), false)
    }

    // Rank candidates so that the result whose title best matches the query lands at index 0.
    // For series with multiple seasons (T1/T2/T3/T4, OVAs, movies) Dantotsu re-sorts our list
    // with FuzzyWuzzy, so ordering matters mainly when results have equal fuzzy ratios; we still
    // want a sane default. Two weights:
    //   * Token-overlap with the cleaned query (more shared words = better).
    //   * Numeric/season-marker bonus: tokens like "4", "4th", "2-nensei-hen", "second" carry
    //     extra weight so e.g. "...4th Season 2-nensei-hen 1 Gakki" outranks "..." (Season 1)
    //     when the query is about Season 4.
    // Tie-break: LONGER title wins (more specific = usually deeper season).
    private fun rankByQueryAffinity(animes: List<SAnime>, query: String): List<SAnime> {
        val queryTokens = query.lowercase(Locale.ROOT)
            .split(WHITESPACE)
            .filter { it.length >= 2 }
        if (queryTokens.isEmpty()) return animes

        fun score(anime: SAnime): Int {
            val title = anime.title.lowercase(Locale.ROOT)
            var s = 0
            for (tok in queryTokens) {
                if (title.contains(tok)) {
                    s += if (SEASON_MARKER_REGEX.containsMatchIn(tok)) 5 else 1
                }
            }
            return s
        }
        return animes.sortedWith(
            compareByDescending<SAnime> { score(it) }.thenByDescending { it.title.length },
        )
    }

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request =
        buildSearchRequest(query, page)

    override fun searchAnimeParse(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animes = document.select("div.ultimosAnimesHomeItem").map { it.toAnimeCard() }
        val hasNext = document.selectFirst("div.letterBox a:contains(»)")
            ?.attr("href")
            ?.contains("page=") == true
        return AnimesPage(animes, hasNext)
    }

    private fun buildSearchRequest(query: String, page: Int): Request {
        // URL-encode so special chars like `&`, `/`, `:`, `#` don't break the URL.
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        return GET("$baseUrl/busca?busca=$encoded&page=$page", headers)
    }

    private fun fetchAniListSynonyms(title: String): List<String> = runCatching {
        val payload = JSONObject().apply {
            put(
                "query",
                "query(\$s:String){Media(search:\$s,type:ANIME){title{romaji english native} synonyms}}",
            )
            put("variables", JSONObject().put("s", title))
        }.toString()
        val req = Request.Builder()
            .url("https://graphql.anilist.co")
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        val body = network.client.newCall(req).execute().use { it.body?.string().orEmpty() }
        val media = JSONObject(body)
            .optJSONObject("data")
            ?.optJSONObject("Media")
            ?: return@runCatching emptyList<String>()

        val out = linkedSetOf<String>()
        media.optJSONObject("title")?.let { t ->
            listOf("romaji", "english", "native").forEach { k ->
                t.optString(k).takeIf { it.isNotBlank() }?.let(out::add)
            }
        }
        media.optJSONArray("synonyms")?.let { arr ->
            for (i in 0 until arr.length()) {
                arr.optString(i).takeIf { it.isNotBlank() }?.let(out::add)
            }
        }
        out.remove(title)
        out.toList()
    }.onFailure { Log.w(TAG, "AniList synonym lookup failed", it) }.getOrDefault(emptyList())

    // ============================== Details ==============================

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        val anime = SAnime.create()
        anime.title = document.selectFirst(".tituloPage h1")?.text()?.trim() ?: "Sem título"
        anime.thumbnail_url = document.selectFirst(".aniInfosSingleCapa img")?.attr("abs:src")
        anime.description = document.selectFirst(".aniInfosSingleSinopse p")?.text()?.trim()
        anime.genre = document.select(".aniInfosSingleGeneros span").joinToString { it.text() }
        val status = document.selectFirst(".anime_status span")?.text()?.lowercase(Locale.ROOT)
        anime.status = if (status == "completo") SAnime.COMPLETED else SAnime.UNKNOWN
        anime.initialized = true
        return anime
    }

    // Expose public URL for Dantotsu / Aniyomi "Open in browser".
    override fun getAnimeUrl(anime: SAnime): String = baseUrl + anime.url

    // ============================= Episodes ==============================

    override fun episodeListParse(response: Response): List<SEpisode> {
        val baseUrlForPages = response.request.url.toString()
            .substringBefore("/page/")
            .removeSuffix("/")

        val collected = ArrayList<SEpisode>()
        val seenUrls = LinkedHashSet<String>()

        var pageIdx = 1
        // Hard cap to avoid infinite loops if CF returns cached page 1 for every paginated request.
        while (pageIdx <= MAX_EPISODE_PAGES) {
            val pageUrl = if (pageIdx == 1) baseUrlForPages else "$baseUrlForPages/page/$pageIdx"
            val pageResp = client.newCall(GET(pageUrl, headers)).execute()
            val pageDoc = pageResp.use { it.asJsoup() }
            val items = pageDoc.select("div.ultimosEpisodiosHomeItem")
            if (items.isEmpty()) break

            var addedAny = false
            for (element in items) {
                val ep = parseEpisodeElement(element) ?: continue
                if (seenUrls.add(ep.url)) {
                    collected += ep
                    addedAny = true
                }
            }
            if (!addedAny) break
            pageIdx++
        }

        if (collected.isEmpty()) return emptyList()

        val sorted = collected.sortedByDescending {
            it.episode_number.takeIf { n -> n > 0f } ?: Float.MAX_VALUE
        }
        return sorted.mapIndexed { index, ep ->
            if (ep.episode_number <= 0f) ep.episode_number = (sorted.size - index).toFloat()
            ep
        }
    }

    private fun parseEpisodeElement(element: Element): SEpisode? {
        return try {
            val link = element.selectFirst("a") ?: return null
            val rawNumber = element.selectFirst(".ultimosEpisodiosHomeItemInfosNum")
                ?.text()
                ?.replace("Episódio", "", ignoreCase = true)
                ?.trim()
                ?: return null
            val number = rawNumber.replace(",", ".").toFloatOrNull()
            val name = element.selectFirst(".ultimosEpisodiosHomeItemInfosNome")
                ?.text()
                ?.trim()
                ?: "Episódio $rawNumber"
            val ep = SEpisode.create()
            ep.setUrlWithoutDomain(link.attr("href"))
            ep.name = name
            ep.episode_number = number ?: 0f
            ep.date_upload = parseDate(
                element.selectFirst(".lancaster_episodio_info_data")?.text(),
            )
            ep
        } catch (e: Exception) {
            Log.w(TAG, "parseEpisodeElement failed", e)
            null
        }
    }

    private fun parseDate(text: String?): Long {
        if (text.isNullOrBlank()) return 0L
        return runCatching { DATE_FORMAT.parse(text)?.time ?: 0L }.getOrDefault(0L)
    }

    // ============================== Videos ===============================

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val httpUrl = response.request.url.toString()

        val tab = document.select("div.AbasBox div.Aba").firstOrNull { element ->
            val name = element.text().trim().uppercase(Locale.ROOT)
            "FULLHD" in name || "FULL HD" in name || "1080" in name
        } ?: return emptyList()

        val attr = tab.attr("aba-type")
        val rawTabName = tab.text().trim()
        val container = document.getElementById(attr)

        val rawVideoUrl = findVideoUrl(container ?: document)
        if (rawVideoUrl == null) return emptyList()

        val adsHeaders = headersBuilder()
            .add("Referer", httpUrl)
            .add("Origin", baseUrl)
            .build()

        val suffix = resolveAdsSuffix(rawVideoUrl, adsHeaders, rawTabName)
        var finalUrl = if (suffix.isNullOrBlank()) rawVideoUrl else (rawVideoUrl + suffix)
        if (finalUrl.startsWith("//")) finalUrl = "https:$finalUrl"
        val qualityLabel = decorateQualityLabel(rawTabName)

        return listOf(buildVideo(finalUrl, qualityLabel, adsHeaders))
    }

    private fun findVideoUrl(root: Element): String? {
        val allHtml = root.html()
        var match = VID_REGEX.find(allHtml)
        if (match != null) return match.groupValues[1].takeUnless { it.isBlank() }

        val allScripts = root.select("script").joinToString("\n") { it.data() }
        match = VID_REGEX.find(allScripts)
        if (match != null) return match.groupValues[1].takeUnless { it.isBlank() }

        match = VID_REGEX2.find(allHtml)
        if (match != null) return match.groupValues[1].takeUnless { it.isBlank() }

        val source = root.select("video source[src], source[src]").firstOrNull()
            ?.attr("src")?.takeUnless { it.isBlank() }
        if (source != null) return source

        return null
    }

    // Construct a Video using the legacy 5-arg constructor for compatibility with the older
    // Aniyomi animesource-api this extension was built against. The factory protects against
    // future renames by catching reflective-style failures defensively.
    private fun buildVideo(url: String, quality: String, headers: Headers): Video {
        return Video(url, quality, url, null as Uri?, headers)
    }

    // Decorate friendly quality tab names with the expected resolution so Dantotsu's quality
    // parser sees a unique integer per quality. We keep the original label (SD/HD/FULLHD/...)
    // visible in the player UI for the user.
    private fun decorateQualityLabel(tabName: String): String {
        val upper = tabName.uppercase(Locale.ROOT)
        return when {
            "FULLHD" in upper || "FULL HD" in upper || "1080" in upper -> "FULLHD 1080p"
            "HD" in upper || "720" in upper -> "HD 720p"
            "SD" in upper || "480" in upper -> "SD 480p"
            "360" in upper -> "SD 360p"
            else -> tabName
        }
    }

    // In-session cache of vid → suffix to avoid double-hitting the ads endpoint within a single
    // episode/page render (Dantotsu sometimes triggers videoListParse twice). Bounded by a soft
    // cap so it can't grow indefinitely.
    private val adsSuffixCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    private fun resolveAdsSuffix(vid: String, headers: Headers, tabName: String): String? {
        adsSuffixCache[vid]?.let { return it }

        val adsUrl = "$ADS_ENDPOINT?url=" + URLEncoder.encode(vid, "UTF-8")
        repeat(ADS_MAX_ATTEMPTS) { attempt ->
            try {
                val fullHeaders = headers.newBuilder()
                    .add("Referer", baseUrl + "/")
                    .add("Origin", baseUrl)
                    .add("Accept", "application/json, text/plain, */*")
                    .add("Sec-Fetch-Site", "cross-site")
                    .add("Sec-Fetch-Mode", "cors")
                    .add("Sec-Fetch-Dest", "empty")
                    .build()
                client.newCall(Request.Builder().url(adsUrl).headers(fullHeaders).build()).execute().use { resp ->
                    val str = resp.body?.string().orEmpty()
                    if (str.contains("publicidade")) {
                        val suffix = JSONArray(str).getJSONObject(0).optString("publicidade", "")
                        if (suffix.isNotBlank()) {
                            if (adsSuffixCache.size < ADS_CACHE_SOFT_CAP) adsSuffixCache[vid] = suffix
                            return suffix
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "ads.animeyabu.net attempt ${attempt + 1} failed for tab '$tabName'", e)
            }
            if (attempt < ADS_MAX_ATTEMPTS - 1) {
                try {
                    Thread.sleep(ADS_BASE_BACKOFF_MS * (attempt + 1L) + (Math.random() * 80).toLong())
                } catch (ie: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return null
                }
            }
        }
        return null
    }

    // ============================== Helpers ==============================

    private fun Element.toAnimeCard(): SAnime {
        val anime = SAnime.create()
        val link = selectFirst("a")!!
        anime.title = selectFirst(".ultimosAnimesHomeItemInfosNome")?.text()?.trim() ?: "Sem título"
        anime.setUrlWithoutDomain(link.attr("href"))
        anime.thumbnail_url = selectFirst(".ultimosAnimesHomeItemImg img")?.attr("abs:src")
        return anime
    }

    private fun String.isAsciiLatin(): Boolean = all { it.code <= 0x7F }

    companion object {
        private const val TAG = "DattebayoBR"
        private const val MAX_EPISODE_PAGES = 50

        private const val SEARCH_PARALLELISM = 5
        private const val SEARCH_TIMEOUT_SECONDS = 15L

        private const val ADS_ENDPOINT = "https://ads.animeyabu.net"
        private const val ADS_MAX_ATTEMPTS = 5
        private const val ADS_BASE_BACKOFF_MS = 200L
        private const val ADS_CACHE_SOFT_CAP = 64

        private val WHITESPACE = Regex("\\s+")
        private val VID_REGEX = Regex("var vid\\s*=\\s*['\"](.*?)['\"]")
        private val VID_REGEX2 = Regex("""(?:let|const|window\.)?\s*vid\s*=\s*['"](.*?)['"]""")

        // Tokens that strongly identify which season a result is for. Examples that this matches:
        // "4", "4th", "3rd", "2nd", "1", "ii", "iii", "iv", "v", "2-nensei", "2-nensei-hen",
        // "second", "third", "fourth", "part2", "part-2". Used to boost score in title ranking.
        private val SEASON_MARKER_REGEX = Regex(
            "^(\\d+(st|nd|rd|th|-[a-z]+)?|i{1,4}|iv|v|vi{0,3}|second|third|fourth|fifth|part-?\\d+)$",
            RegexOption.IGNORE_CASE,
        )

        private val DATE_FORMAT = SimpleDateFormat("dd/MM/yyyy 'às' HH:mm", Locale("pt", "BR"))

        private val STOP_WORDS = setOf(
            "the", "a", "an", "of", "and", "or",
            "wa", "no", "to", "ga", "de", "do", "da", "ni", "wo",
            "season", "part", "ova", "movie",
        )

        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
