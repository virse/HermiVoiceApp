package de.hermi.voice.update

import android.content.Context
import android.os.Build

/**
 * Liest die installierte App-Version. Bewusst über PackageInfo statt
 * BuildConfig: In diesem Projekt ist `buildConfig` nicht aktiviert, daher
 * existiert `BuildConfig.VERSION_CODE` nicht.
 */
object UpdateVersion {

    fun installedVersionCode(context: Context): Long {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
    }

    fun installedVersionName(context: Context): String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
}
