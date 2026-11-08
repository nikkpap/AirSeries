package gr.nikkpap.airseries.data.remote

import android.content.Context
import gr.nikkpap.airseries.BuildConfig
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Cache
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * TVmaze public API — free, no key required. https://www.tvmaze.com/api
 * Data licensed CC BY-SA. Rate limit: ~20 calls / 10 s (we retry on HTTP 429).
 */
interface TvMazeApi {

    @GET("search/shows")
    suspend fun search(@Query("q") query: String): List<SearchResultDto>

    @GET("shows/{id}")
    suspend fun showFull(
        @Path("id") id: Int,
        @Query(value = "embed[]", encoded = true) embed: List<String>,
    ): ShowDto

    @GET("shows/{id}/cast")
    suspend fun cast(@Path("id") id: Int): List<CastDto>

    @GET("people/{id}/castcredits?embed=show")
    suspend fun castCredits(@Path("id") id: Int): List<CastCreditDto>

    @GET("shows")
    suspend fun showsPage(@Query("page") page: Int): Response<List<ShowDto>>

    companion object {
        val EMBED = listOf("episodes", "nextepisode")

        val json = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
            explicitNulls = false
            isLenient = true
        }

        fun create(ctx: Context): TvMazeApi {
            val client = OkHttpClient.Builder()
                .cache(Cache(File(ctx.cacheDir, "http"), 25L * 1024 * 1024))
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(40, TimeUnit.SECONDS)
                .addInterceptor { chain ->
                    val req = chain.request().newBuilder()
                        .header("User-Agent", "AirSeries-Android/${BuildConfig.VERSION_NAME}")
                        .build()
                    var resp = chain.proceed(req)
                    var tries = 0
                    while (resp.code == 429 && tries < 4) {
                        resp.close()
                        tries++
                        Thread.sleep(1500L * tries)
                        resp = chain.proceed(req)
                    }
                    resp
                }
                .build()
            return Retrofit.Builder()
                .baseUrl("https://api.tvmaze.com/")
                .client(client)
                .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
                .build()
                .create(TvMazeApi::class.java)
        }
    }
}

@Serializable data class ImageDto(val medium: String? = null, val original: String? = null)
@Serializable data class RatingDto(val average: Double? = null)
@Serializable data class CountryDto(val name: String? = null, val code: String? = null)
@Serializable data class NetworkDto(val id: Int? = null, val name: String? = null, val country: CountryDto? = null)
@Serializable data class ScheduleDto(val time: String? = null, val days: List<String> = emptyList())
@Serializable data class ExternalsDto(val imdb: String? = null)

@Serializable
data class ShowDto(
    val id: Int,
    val url: String? = null,
    val name: String = "",
    val type: String? = null,
    val language: String? = null,
    val genres: List<String> = emptyList(),
    val status: String? = null,
    val runtime: Int? = null,
    val averageRuntime: Int? = null,
    val premiered: String? = null,
    val ended: String? = null,
    val officialSite: String? = null,
    val schedule: ScheduleDto? = null,
    val rating: RatingDto? = null,
    val weight: Int? = null,
    val network: NetworkDto? = null,
    val webChannel: NetworkDto? = null,
    val image: ImageDto? = null,
    val summary: String? = null,
    val externals: ExternalsDto? = null,
    @SerialName("_embedded") val embedded: ShowEmbedded? = null,
)

@Serializable
data class ShowEmbedded(
    val episodes: List<EpisodeDto>? = null,
    val nextepisode: EpisodeDto? = null,
)

@Serializable
data class EpisodeDto(
    val id: Int,
    val url: String? = null,
    val name: String = "",
    val season: Int = 0,
    val number: Int? = null,
    val type: String? = null,
    val airdate: String? = null,
    val airtime: String? = null,
    val airstamp: String? = null,
    val runtime: Int? = null,
    val rating: RatingDto? = null,
    val image: ImageDto? = null,
    val summary: String? = null,
)

@Serializable data class SearchResultDto(val score: Double = 0.0, val show: ShowDto)
@Serializable data class PersonDto(val id: Int, val name: String = "")
@Serializable data class CastDto(val person: PersonDto)
@Serializable data class CreditEmbedded(val show: ShowDto? = null)
@Serializable data class CastCreditDto(@SerialName("_embedded") val embedded: CreditEmbedded? = null)
