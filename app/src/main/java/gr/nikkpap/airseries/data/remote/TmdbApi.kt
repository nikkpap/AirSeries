package gr.nikkpap.airseries.data.remote

import android.content.Context
import gr.nikkpap.airseries.BuildConfig
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.Cache
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The Movie Database (TMDB) v3 API — used for movies.
 * The personal API key comes from the GitHub secret TMDB_API_KEY at build time.
 * "This product uses the TMDB API but is not endorsed or certified by TMDB."
 */
interface TmdbApi {
    @GET("search/movie")
    suspend fun search(
        @Query("query") query: String,
        @Query("language") lang: String,
        @Query("region") region: String,
        @Query("include_adult") adult: Boolean,
    ): TmdbPage

    @GET("movie/{id}")
    suspend fun movie(
        @Path("id") id: Int,
        @Query("language") lang: String,
        @Query("append_to_response") append: String,
    ): TmdbMovie

    @GET("movie/{id}")
    suspend fun movieBasic(@Path("id") id: Int, @Query("language") lang: String): TmdbMovie

    @GET("movie/now_playing")
    suspend fun nowPlaying(@Query("language") lang: String, @Query("region") region: String): TmdbPage

    @GET("movie/upcoming")
    suspend fun upcoming(@Query("language") lang: String, @Query("region") region: String): TmdbPage

    @GET("movie/popular")
    suspend fun popular(@Query("language") lang: String, @Query("region") region: String): TmdbPage

    @GET("movie/{id}/recommendations")
    suspend fun recommendations(@Path("id") id: Int, @Query("language") lang: String): TmdbPage

    companion object {
        const val IMG = "https://image.tmdb.org/t/p/"
        const val DETAILS_APPEND = "credits,videos,watch/providers,release_dates,recommendations"

        fun poster(path: String?, size: String = "w342") = path?.let { IMG + size + it }

        fun create(ctx: Context): TmdbApi {
            val client = OkHttpClient.Builder()
                .cache(Cache(File(ctx.cacheDir, "tmdb"), 15L * 1024 * 1024))
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(40, TimeUnit.SECONDS)
                .addInterceptor { chain ->
                    val req = chain.request()
                    val url = req.url.newBuilder().addQueryParameter("api_key", BuildConfig.TMDB_API_KEY).build()
                    chain.proceed(req.newBuilder().url(url).header("User-Agent", "AirSeries-Android/${BuildConfig.VERSION_NAME}").build())
                }
                .build()
            return Retrofit.Builder()
                .baseUrl("https://api.themoviedb.org/3/")
                .client(client)
                .addConverterFactory(TvMazeApi.json.asConverterFactory("application/json".toMediaType()))
                .build()
                .create(TmdbApi::class.java)
        }

        /** TMDB's fixed movie genre ids → (English, Greek). */
        val GENRES: Map<Int, Pair<String, String>> = mapOf(
            28 to ("Action" to "Δράση"), 12 to ("Adventure" to "Περιπέτεια"), 16 to ("Animation" to "Κινούμενα σχέδια"),
            35 to ("Comedy" to "Κωμωδία"), 80 to ("Crime" to "Έγκλημα"), 99 to ("Documentary" to "Ντοκιμαντέρ"),
            18 to ("Drama" to "Δράμα"), 10751 to ("Family" to "Οικογενειακή"), 14 to ("Fantasy" to "Φαντασίας"),
            36 to ("History" to "Ιστορική"), 27 to ("Horror" to "Τρόμου"), 10402 to ("Music" to "Μουσική"),
            9648 to ("Mystery" to "Μυστηρίου"), 10749 to ("Romance" to "Ρομαντική"), 878 to ("Science Fiction" to "Επιστ. Φαντασίας"),
            10770 to ("TV Movie" to "Τηλεταινία"), 53 to ("Thriller" to "Θρίλερ"), 10752 to ("War" to "Πολεμική"),
            37 to ("Western" to "Γουέστερν"),
        )
    }
}

@Serializable
data class TmdbPage(
    val page: Int = 1,
    val results: List<TmdbMovie> = emptyList(),
    @SerialName("total_pages") val totalPages: Int = 1,
)

@Serializable data class TmdbGenre(val id: Int = 0, val name: String = "")

@Serializable
data class TmdbMovie(
    val id: Int,
    val title: String = "",
    @SerialName("original_title") val originalTitle: String? = null,
    val overview: String? = null,
    @SerialName("poster_path") val posterPath: String? = null,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    val runtime: Int? = null,
    @SerialName("vote_average") val voteAverage: Double? = null,
    @SerialName("vote_count") val voteCount: Int? = null,
    val popularity: Double? = null,
    val genres: List<TmdbGenre> = emptyList(),
    @SerialName("genre_ids") val genreIds: List<Int> = emptyList(),
    val status: String? = null,
    @SerialName("imdb_id") val imdbId: String? = null,
    val tagline: String? = null,
    val credits: TmdbCredits? = null,
    val videos: TmdbVideos? = null,
    @SerialName("watch/providers") val providers: TmdbProvidersWrap? = null,
    @SerialName("release_dates") val releaseDates: TmdbReleaseDatesWrap? = null,
    val recommendations: TmdbPage? = null,
)

@Serializable data class TmdbCredits(val cast: List<TmdbCast> = emptyList(), val crew: List<TmdbCrew> = emptyList())
@Serializable data class TmdbCast(val id: Int, val name: String = "", val character: String? = null, @SerialName("profile_path") val profilePath: String? = null)
@Serializable data class TmdbCrew(val id: Int, val name: String = "", val job: String? = null)
@Serializable data class TmdbVideos(val results: List<TmdbVideo> = emptyList())

@Serializable
data class TmdbVideo(
    val key: String = "",
    val site: String = "",
    val type: String = "",
    val official: Boolean = false,
    @SerialName("iso_639_1") val lang: String? = null,
)

@Serializable data class TmdbProvidersWrap(val results: Map<String, TmdbCountryProviders> = emptyMap())

@Serializable
data class TmdbCountryProviders(
    val link: String? = null,
    val flatrate: List<TmdbProvider> = emptyList(),
    val rent: List<TmdbProvider> = emptyList(),
    val buy: List<TmdbProvider> = emptyList(),
    val free: List<TmdbProvider> = emptyList(),
    val ads: List<TmdbProvider> = emptyList(),
)

@Serializable
data class TmdbProvider(
    @SerialName("provider_id") val id: Int = 0,
    @SerialName("provider_name") val name: String = "",
    @SerialName("logo_path") val logoPath: String? = null,
)

@Serializable data class TmdbReleaseDatesWrap(val results: List<TmdbCountryRelease> = emptyList())

@Serializable
data class TmdbCountryRelease(
    @SerialName("iso_3166_1") val country: String = "",
    @SerialName("release_dates") val dates: List<TmdbReleaseDate> = emptyList(),
)

/** type: 1 premiere, 2 limited theatrical, 3 theatrical, 4 digital, 5 physical, 6 TV */
@Serializable data class TmdbReleaseDate(@SerialName("release_date") val date: String = "", val type: Int = 0)
