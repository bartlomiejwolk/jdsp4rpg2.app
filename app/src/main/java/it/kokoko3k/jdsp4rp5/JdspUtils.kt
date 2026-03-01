package it.kokoko3k.jdsp4rp5

import android.content.Context
import android.os.Environment
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.FileWriter
import android.os.Build
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import android.widget.Toast
import java.util.Locale

object ApkInstaller {

    private const val TAG = "ApkInstaller"

    fun installApkFromAssets(context: Context, assetFileName: String, subfolder: String? = null): Boolean {
        try {
            val inputStream = if (subfolder != null) {
                context.assets.open("$subfolder/$assetFileName")
            } else {
                context.assets.open(assetFileName)
            }

            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!downloadsDir.exists() && !downloadsDir.mkdirs()) {
                Log.e(TAG, "Impossibile creare la directory Download")
                return false
            }

            val apkFile = File(downloadsDir, assetFileName)
            val outputStream = FileOutputStream(apkFile)

            inputStream.copyTo(outputStream)

            inputStream.close()
            outputStream.close()

            val intent = Intent(Intent.ACTION_VIEW)
            val apkUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apkFile)
            } else {
                Uri.fromFile(apkFile)
            }
            intent.setDataAndType(apkUri, "application/vnd.android.package-archive")
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) // Importante per Android 7.0+
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) // Necessario se chiamato da un contesto non Activity

            context.startActivity(intent)

            return true
        } catch (e: IOException) {
            Log.e(TAG, "Errore durante l'installazione dell'APK", e)
            return false
        }
    }
}


fun getLogFile(context: Context): File? {
    val TAG = "LogUtil" // Definizione della variabile tag
    val dir = context.getExternalFilesDir(null)
    if (dir == null) {
        Log.d(TAG, "Impossibile ottenere la directory dei documenti")
        return null
    }

    if (!dir.exists() && !dir.mkdirs()) {
        Log.d(TAG, "Impossibile creare la directory dei documenti")
        return null
    }

    return File(dir, "lastlog.txt")
}

fun copyAssetFolderToFilesDir(context: Context, assetFolderPath: String) {
    try {
        val assetManager = context.assets

        // Ottieni la lista dei file e delle sottocartelle nella cartella assets specificata
        val assetFiles = assetManager.list(assetFolderPath) ?: return // Se la cartella non esiste, esci

        if (assetFiles.isEmpty()) {
            // È una cartella vuota, creala nella directory dell'app
            val targetDir = File(context.filesDir, assetFolderPath)
            targetDir.mkdirs()
            return
        }

        for (assetFileName in assetFiles) {
            val fullAssetPath = if (assetFolderPath.isEmpty()) assetFileName else "$assetFolderPath/$assetFileName"

            try {
                //Prova ad aprire il file. Se fallisce, significa che è una cartella.
                assetManager.open(fullAssetPath).use {
                    // È un file, copialo
                    val outFile = File(context.filesDir, fullAssetPath)
                    outFile.parentFile?.mkdirs() // Crea le directory parent
                    FileOutputStream(outFile).use { output -> it.copyTo(output) }
                }
            } catch (e: IOException) {
                // Gestisci il caso in cui è una sottocartella, richiamando ricorsivamente la funzione
                copyAssetFolderToFilesDir(context, fullAssetPath)
            }
        }
    } catch (e: IOException) {
        e.printStackTrace()
    }
}

object JdspUtils {

    private const val TAG = "JdspUtils"

    val subfolder = "app"
    const val PREFS_NAME = "JdspPrefs"
    const val JDSP_BOOT_ENABLED_KEY = "jdspEnabled"
    const val JDSP_MEDIA_ONLY_KEY = "jdspMediaOnly"

    enum class RuntimeState {
        ACTIVE,
        INACTIVE,
        ACTIVE_OR_UNKNOWN
    }

    data class SetupResult(
        val success: Boolean,
        val exitCode: Int?,
        val logPath: String?,
        val errorSummary: String?,
        val runtimeState: RuntimeState,
        val setupMarkerSeen: Boolean? = null,
        val cleanupMarkerSeen: Boolean? = null
    )

    fun installJdsp(context:Context) {
        // extract assets/$subfolder/ under applicazione/files/
        // which means into in context.filesDir
        var apkname = "JamesDSPManagerThePBone.apk"
        ApkInstaller.installApkFromAssets(context, apkname, subfolder)
    }


    fun enableJdsp(context: Context, mediaOnly: Boolean = false): SetupResult {
        // extract assets/$subfolder/ under application/files/
        // which means into in context.filesDir
        copyAssetFolderToFilesDir(context, subfolder)

        // filespath is: /data/user/0/it.kokoko3k.jdsp4rp5/files/$subfolder/
        val filespath = File(context.filesDir, subfolder).absolutePath.toString()

        // logpath is: /storage/emulated/0/app.name/files/lostlog.txt
        val logpath = getLogFile(context)

        val mode = if (mediaOnly) "media_only" else "all_audio"
        val logTarget = logpath?.absolutePath ?: "/dev/null"
        val cmd =
            "sh " + filespath + "/support/subscripts/jdsp.setup.sh " + filespath + " " + mode +
                " > " + logTarget + " 2>&1; ec=\$?; echo EXIT_CODE=\$ec >> " + logTarget + "; echo EXIT_CODE=\$ec"
        Log.d(TAG, "enabling jdsp with cmd= " + cmd)

        //execute it:
        val rootexec = RootExec() // get instance
        val rawResult = rootexec.executeAsRoot(cmd)
        val logText = readLogText(logpath)
        val exitCode = extractExitCode(rawResult.getOrNull()) ?: extractExitCode(logText)
        val runtimeState = probeRuntimeState(context)
        val errorSummary = if (rawResult.isFailure) rawResult.exceptionOrNull()?.message else null
        val hasSetupSuccessMarker = logText?.contains("SETUP_RESULT=success")
        val markerGate = hasSetupSuccessMarker ?: true
        val enableSucceeded = exitCode == 0 && runtimeState != RuntimeState.INACTIVE && markerGate
        Log.d(
            TAG,
            "enable result predicates: exitCode=$exitCode runtimeState=$runtimeState setupMarker=$hasSetupSuccessMarker markerGate=$markerGate success=$enableSucceeded"
        )

        return SetupResult(
            success = enableSucceeded,
            exitCode = exitCode,
            logPath = logpath?.absolutePath,
            errorSummary = errorSummary,
            runtimeState = runtimeState,
            setupMarkerSeen = hasSetupSuccessMarker
        )
    }

    fun disableJdsp(context: Context): SetupResult {
        // extract assets/$subfolder/ under applicazione/files/
        // which means into in context.filesDir
        val subfolder = "app"
        copyAssetFolderToFilesDir(context, subfolder)

        // filespath is: /data/user/0/it.kokoko3k.jdsp4rp5/files/$subfolder/
        val filespath = File(context.filesDir, subfolder).absolutePath.toString()

        // logpath is: /storage/emulated/0/app.name/files/lostlog.txt
        val logpath = getLogFile(context)

        val logTarget = logpath?.absolutePath ?: "/dev/null"
        val cmd =
            "sh " + filespath + "/support/subscripts/jdsp.cleanup.sh " + filespath +
                " > " + logTarget + " 2>&1; ec=\$?; echo EXIT_CODE=\$ec >> " + logTarget + "; echo EXIT_CODE=\$ec"
        Log.d(TAG, "enabling jdsp with cmd= " + cmd)

        //execute it:
        val rootexec = RootExec() // get instance
        val rawResult = rootexec.executeAsRoot(cmd)
        val logText = readLogText(logpath)
        val exitCode = extractExitCode(rawResult.getOrNull()) ?: extractExitCode(logText)
        val runtimeState = probeRuntimeState(context)
        val errorSummary = if (rawResult.isFailure) rawResult.exceptionOrNull()?.message else null
        val hasCleanupSuccessMarker = logText?.contains("CLEANUP_RESULT=success")
        val markerGate = hasCleanupSuccessMarker ?: true
        val disableSucceeded = exitCode == 0 && runtimeState != RuntimeState.ACTIVE && markerGate
        Log.d(
            TAG,
            "disable result predicates: exitCode=$exitCode runtimeState=$runtimeState cleanupMarker=$hasCleanupSuccessMarker markerGate=$markerGate success=$disableSucceeded"
        )
        val resolvedRuntimeState = if (disableSucceeded) RuntimeState.INACTIVE else runtimeState

        return SetupResult(
            success = disableSucceeded,
            exitCode = exitCode,
            logPath = logpath?.absolutePath,
            errorSummary = errorSummary,
            runtimeState = resolvedRuntimeState,
            cleanupMarkerSeen = hasCleanupSuccessMarker
        )
    }

    fun probeRuntimeState(context: Context): RuntimeState {
        copyAssetFolderToFilesDir(context, subfolder)
        val filespath = File(context.filesDir, subfolder).absolutePath.toString()
        val rootexec = RootExec()
        val probeCmd =
            "target_mounted() { t=\"\$1\"; mount | awk -F' on | type ' -v tgt=\"\$t\" '\$2==tgt {found=1} END{exit(found?0:1)}'; }; " +
                "SOUNDFX_DIR=/vendor/lib64/soundfx; " +
                "AUDIO_EFFECTS_TARGET=/vendor/etc/audio/sku_cliffs/audio_effects.xml; " +
                "AUDIO_POLICY_TARGET=/vendor/etc/audio/sku_cliffs_qssi/audio_policy_configuration.xml; " +
                "TMPFS=\"$filespath/support/jdsp4rp5_tmpfs\"; " +
                "has_tmpfs=0; has_soundfx=0; has_effects=0; any_jdsp=0; " +
                "target_mounted \"\$TMPFS\" && has_tmpfs=1 || true; " +
                "target_mounted \"\$SOUNDFX_DIR\" && has_soundfx=1 || true; " +
                "target_mounted \"\$AUDIO_EFFECTS_TARGET\" && has_effects=1 || true; " +
                "for t in \"\$TMPFS\" \"\$SOUNDFX_DIR\" \"\$AUDIO_EFFECTS_TARGET\" \"\$AUDIO_POLICY_TARGET\" /vendor/etc/mixer_paths_qrd.xml /vendor/etc/audio_policy_volumes.xml /vendor/etc/default_volume_tables.xml /vendor/etc/acdbdata/MTP; do target_mounted \"\$t\" && any_jdsp=1 && break || true; done; " +
                "if [ \$has_soundfx -eq 1 ] && [ \$has_effects -eq 1 ]; then echo RUNTIME_STATE=active; " +
                "elif [ \$any_jdsp -eq 1 ]; then echo RUNTIME_STATE=active_or_unknown; " +
                "else echo RUNTIME_STATE=inactive; fi"

        val result = rootexec.executeAsRoot(probeCmd)
        val output = result.getOrNull()?.lowercase(Locale.US) ?: return RuntimeState.ACTIVE_OR_UNKNOWN
        return when {
            output.contains("runtime_state=active_or_unknown") -> RuntimeState.ACTIVE_OR_UNKNOWN
            output.contains("runtime_state=active") -> RuntimeState.ACTIVE
            output.contains("runtime_state=inactive") -> RuntimeState.INACTIVE
            else -> RuntimeState.ACTIVE_OR_UNKNOWN
        }
    }

    private fun extractExitCode(output: String?): Int? {
        if (output == null) return null
        val match = Regex("""EXIT_CODE=(\d+)""").find(output) ?: return null
        return match.groupValues[1].toIntOrNull()
    }

    private fun readLogText(logFile: File?): String? {
        if (logFile == null || !logFile.exists()) return null
        return runCatching { logFile.readText() }.getOrNull()
    }
}
