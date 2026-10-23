package com.example.mediathektv

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Lädt den Katalog im Hintergrund nach – auch wenn die App geschlossen ist.
 * Beim nächsten Öffnen ist dann schon alles aktuell (die App liest ihn aus dem Gerätespeicher).
 */
class CatalogRefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val repo = CatalogRepository(applicationContext)
        repo.refresh()                                     // null = unverändert (304) oder Fehler
        return if (repo.lastRefreshFailed && runAttemptCount < 3) Result.retry() else Result.success()
    }

    companion object {
        private const val NAME = "catalog-refresh"

        /** Einmal beim App-Start aufrufen; mehrfaches Aufrufen ändert nichts (KEEP). */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<CatalogRefreshWorker>(3, TimeUnit.HOURS, 30, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
