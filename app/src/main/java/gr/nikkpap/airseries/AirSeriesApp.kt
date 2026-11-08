package gr.nikkpap.airseries

import android.app.Application
import android.content.Context
import gr.nikkpap.airseries.data.Prefs
import gr.nikkpap.airseries.data.Recommender
import gr.nikkpap.airseries.data.Repository
import gr.nikkpap.airseries.data.local.AppDatabase
import gr.nikkpap.airseries.data.remote.TvMazeApi
import gr.nikkpap.airseries.work.Notifier
import gr.nikkpap.airseries.work.Scheduler

class AirSeriesApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        Notifier.createChannels(this)
        Scheduler.schedulePeriodicSync(this)
        Scheduler.syncNow(this, force = false)
    }
}

/** Simple manual dependency container (no DI library needed). */
class AppContainer(ctx: Context) {
    val prefs = Prefs(ctx)
    val db = AppDatabase.get(ctx)
    val api = TvMazeApi.create(ctx)
    val repo = Repository(ctx.applicationContext, api, db, prefs)
    val recommender = Recommender(api, db, prefs)
}

val Context.container: AppContainer
    get() = (applicationContext as AirSeriesApp).container
