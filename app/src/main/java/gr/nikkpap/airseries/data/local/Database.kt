package gr.nikkpap.airseries.data.local

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate
import java.time.ZoneId

@Entity(tableName = "shows")
data class ShowEntity(
    @PrimaryKey val id: Int,
    val name: String,
    val imageMedium: String?,
    val imageOriginal: String?,
    val summary: String?,
    val genres: String,          // "Drama|Crime"
    val status: String?,
    val network: String?,
    val language: String?,
    val rating: Double?,
    val premiered: String?,
    val ended: String?,
    val runtime: Int?,
    val scheduleTime: String?,
    val scheduleDays: String?,
    val url: String?,
    val officialSite: String?,
    val imdb: String?,
    val weight: Int?,
    val nextEpisodeId: Int?,
    val addedAt: Long,
    val lastSync: Long,
)

@Entity(tableName = "episodes", indices = [Index("showId")])
data class EpisodeEntity(
    @PrimaryKey val id: Int,
    val showId: Int,
    val season: Int,
    val number: Int?,            // null = special
    val name: String,
    val airdate: String?,
    val airstamp: Long?,         // UTC epoch millis
    val runtime: Int?,
    val imageMedium: String?,
    val imageOriginal: String?,
    val summary: String?,
    val rating: Double?,
    val url: String?,
    val watched: Boolean = false,
    val watchedAt: Long? = null,
    val notified: Boolean = false,
)

@Entity(tableName = "rec_candidates")
data class RecCandidateEntity(
    @PrimaryKey val id: Int,
    val name: String,
    val image: String?,
    val genres: String,
    val rating: Double?,
    val weight: Int?,
    val language: String?,
    val premiered: String?,
    val status: String?,
    val summary: String?,
    val network: String?,
    val castOverlap: Int,
    val viaShowId: Int?,
    val fetchedAt: Long,
)

@Entity(tableName = "dismissed")
data class DismissedEntity(@PrimaryKey val showId: Int)

fun splitGenres(s: String): List<String> = if (s.isBlank()) emptyList() else s.split("|")
fun ShowEntity.genreList(): List<String> = splitGenres(genres)
fun RecCandidateEntity.genreList(): List<String> = splitGenres(genres)

/** Best-known air moment: exact airstamp, else the air date at local midnight. */
fun EpisodeEntity.airTime(): Long? = airstamp ?: airdate?.takeIf { it.isNotBlank() }?.let {
    runCatching { LocalDate.parse(it).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() }.getOrNull()
}

fun EpisodeEntity.isAired(now: Long): Boolean = airTime()?.let { it <= now } ?: false
val EpisodeEntity.isRegular: Boolean get() = number != null

@Dao
interface AppDao {
    // ---- shows (favorites) ----
    @Query("SELECT * FROM shows ORDER BY name COLLATE NOCASE")
    fun showsFlow(): Flow<List<ShowEntity>>

    @Query("SELECT * FROM shows")
    suspend fun shows(): List<ShowEntity>

    @Query("SELECT * FROM shows WHERE id = :id")
    fun showFlow(id: Int): Flow<ShowEntity?>

    @Query("SELECT * FROM shows WHERE id = :id")
    suspend fun show(id: Int): ShowEntity?

    @Query("SELECT id FROM shows")
    fun favoriteIdsFlow(): Flow<List<Int>>

    @Upsert
    suspend fun upsertShow(show: ShowEntity)

    @Query("DELETE FROM shows WHERE id = :id")
    suspend fun deleteShow(id: Int)

    // ---- episodes ----
    @Query("SELECT * FROM episodes ORDER BY showId, season, number")
    fun allEpisodesFlow(): Flow<List<EpisodeEntity>>

    @Query("SELECT * FROM episodes WHERE showId = :showId ORDER BY season, number")
    fun episodesFlow(showId: Int): Flow<List<EpisodeEntity>>

    @Query("SELECT * FROM episodes WHERE showId = :showId ORDER BY season, number")
    suspend fun episodes(showId: Int): List<EpisodeEntity>

    @Query("SELECT * FROM episodes")
    suspend fun allEpisodes(): List<EpisodeEntity>

    @Query("SELECT * FROM episodes WHERE id = :id")
    suspend fun episode(id: Int): EpisodeEntity?

    @Upsert
    suspend fun upsertEpisodes(list: List<EpisodeEntity>)

    @Query("DELETE FROM episodes WHERE showId = :showId")
    suspend fun deleteEpisodesOfShow(showId: Int)

    @Query("DELETE FROM episodes WHERE id IN (:ids)")
    suspend fun deleteEpisodes(ids: List<Int>)

    @Query("UPDATE episodes SET watched = :watched, watchedAt = :at WHERE id = :id")
    suspend fun setWatched(id: Int, watched: Boolean, at: Long?)

    @Query("UPDATE episodes SET watched = :watched, watchedAt = :at WHERE id IN (:ids)")
    suspend fun setWatchedMany(ids: List<Int>, watched: Boolean, at: Long?)

    @Query("UPDATE episodes SET notified = 1 WHERE id = :id")
    suspend fun setNotified(id: Int)

    // ---- recommendations ----
    @Query("SELECT * FROM rec_candidates")
    fun candidatesFlow(): Flow<List<RecCandidateEntity>>

    @Upsert
    suspend fun upsertCandidates(list: List<RecCandidateEntity>)

    @Query("DELETE FROM rec_candidates")
    suspend fun clearCandidates()

    @Query("SELECT showId FROM dismissed")
    fun dismissedFlow(): Flow<List<Int>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun dismiss(d: DismissedEntity)

    @Query("DELETE FROM dismissed")
    suspend fun clearDismissed()
}

@Database(
    entities = [ShowEntity::class, EpisodeEntity::class, RecCandidateEntity::class, DismissedEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): AppDao

    companion object {
        @Volatile private var instance: AppDatabase? = null
        fun get(ctx: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(ctx.applicationContext, AppDatabase::class.java, "airseries.db")
                .fallbackToDestructiveMigration()
                .build()
                .also { instance = it }
        }
    }
}
