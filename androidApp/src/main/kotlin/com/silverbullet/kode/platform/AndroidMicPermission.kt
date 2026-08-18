package com.silverbullet.kode.platform

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.silverbullet.kode.feature.voice.domain.MicPermission
import kotlinx.coroutines.CompletableDeferred

/**
 * RECORD_AUDIO permission, bridged to the activity result API.
 *
 * The launcher must be registered before the activity is started, so [attach] is called
 * from `MainActivity.onCreate` and re-called after every configuration change — the
 * singleton keeps only the most recent activity's launcher.
 */
class AndroidMicPermission(private val context: Context) : MicPermission {

    private var launcher: ActivityResultLauncher<String>? = null
    private var pending: CompletableDeferred<Boolean>? = null

    fun attach(activity: ComponentActivity) {
        launcher = activity.registerForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { granted ->
            pending?.complete(granted)
            pending = null
        }
    }

    override suspend fun ensure(): Boolean {
        val alreadyGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        if (alreadyGranted) return true

        val launcher = launcher ?: return false
        // One request at a time; a concurrent caller awaits the same answer.
        pending?.let { return it.await() }
        val deferred = CompletableDeferred<Boolean>()
        pending = deferred
        launcher.launch(Manifest.permission.RECORD_AUDIO)
        return deferred.await()
    }
}
