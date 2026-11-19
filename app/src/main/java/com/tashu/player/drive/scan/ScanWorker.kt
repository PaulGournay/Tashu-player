package com.tashu.player.drive.scan

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.tashu.player.domain.repository.SettingsRepository
import com.tashu.player.drive.auth.AuthRequiredException
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

@HiltWorker
class ScanWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val scanner: DriveMusicScanner,
    private val settings: SettingsRepository,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val roots = settings.rootFolderIds.first()
        if (roots.isEmpty()) return Result.success()
        return try {
            scanner.scan(roots, force = inputData.getBoolean(KEY_FORCE, false))
            Result.success()
        } catch (e: AuthRequiredException) {
            Result.failure() // L'UI proposera de se reconnecter.
        } catch (e: Exception) {
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    companion object {
        private const val KEY_FORCE = "force"
        private const val ONE_SHOT = "drive-scan"
        private const val PERIODIC = "drive-scan-periodic"
        private val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        fun runNow(context: Context, force: Boolean = false) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                ONE_SHOT, ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<ScanWorker>().setConstraints(constraints)
                    .setInputData(workDataOf(KEY_FORCE to force)).build(),
            )
        }

        fun schedulePeriodic(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<ScanWorker>(12, TimeUnit.HOURS)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).setRequiresBatteryNotLow(true).build())
                    .build(),
            )
        }
    }
}
