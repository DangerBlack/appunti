package org.appunti.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import org.appunti.app.asr.ModelStore
import org.appunti.app.data.Lectures
import org.appunti.app.ui.MainActivity

class AppuntiApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Lectures.init(this)
        Notifications.createChannels(this)
        Recovery.run(this)
        ModelStore.ensure(this)
    }
}

object Notifications {
    const val CH_RECORD = "registrazione"
    const val CH_TRANSCRIBE = "trascrizione"
    const val CH_DONE = "pronte"
    const val ID_RECORD = 1
    const val ID_TRANSCRIBE = 2
    const val ID_DONE = 3

    fun createChannels(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_RECORD, c.getString(R.string.ch_recording), NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_TRANSCRIBE, c.getString(R.string.ch_transcribing), NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_DONE, c.getString(R.string.ch_done), NotificationManager.IMPORTANCE_DEFAULT))
    }

    fun openApp(c: Context, lectureId: String? = null): PendingIntent =
        PendingIntent.getActivity(
            c, lectureId?.hashCode() ?: 0,
            Intent(c, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .apply { lectureId?.let { putExtra(MainActivity.EXTRA_LECTURE, it) } },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    fun builder(c: Context, channel: String, title: String, text: String, lectureId: String? = null) =
        NotificationCompat.Builder(c, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openApp(c, lectureId))
            .setOnlyAlertOnce(true)
}
