package gr.nikkpap.airseries.util

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/** Battery-optimization helpers so reminders arrive on time (Samsung, Xiaomi, Huawei…). */
object Battery {
    fun isUnrestricted(ctx: Context): Boolean =
        ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)

    /** Shows the system "Allow app to run in background?" dialog. */
    @SuppressLint("BatteryLife")
    fun requestUnrestricted(ctx: Context) {
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val ok = runCatching { ctx.startActivity(direct) }.isSuccess
        if (!ok) openBatterySettings(ctx)
    }

    fun openBatterySettings(ctx: Context) {
        val list = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val details = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { ctx.startActivity(list) }.isFailure) runCatching { ctx.startActivity(details) }
    }

    /** Phone maker, for brand-specific advice (e.g. "samsung", "xiaomi"). */
    val maker: String get() = Build.MANUFACTURER.lowercase()

    /** dontkillmyapp.com has step-by-step guides per phone brand. */
    fun openBrandGuide(ctx: Context) {
        val known = setOf("samsung", "xiaomi", "huawei", "honor", "oneplus", "oppo", "realme", "vivo", "motorola", "nokia", "sony", "asus", "google", "meizu", "lenovo", "tecno", "infinix")
        val slug = if (maker in known) maker else ""
        openUrl(ctx, "https://dontkillmyapp.com/$slug")
    }
}
