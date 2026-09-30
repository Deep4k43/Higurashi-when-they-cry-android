package com.winlator.star.androidgames

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.Log
import android.util.LruCache
import androidx.core.graphics.drawable.toBitmap
import com.winlator.star.container.Container
import com.winlator.star.container.Shortcut
import com.winlator.star.core.FileUtils
import java.io.File

/**
 * Android games in the Games list ("+" → Add Android game). An entry is a plain `.desktop` shortcut
 * (see [AndroidGameEntry]) living in a container's desktop dir only because that is where the Games
 * list reads from; it never touches the container. Tapping one starts the app the normal Android
 * way, as its own task — nothing runs inside Bannerlator.
 */
object AndroidGames {
    private const val TAG = "AndroidGames"

    /** Portrait 2:3 tile the Games card crops to, the app icon centred on it. */
    private const val TILE_W = 360
    private const val TILE_H = 540
    private const val TILE_ICON = 216
    private const val TILE_BG = 0xFF1B1B1B.toInt()

    data class InstalledApp(
        val packageName: String,
        val label: String,
        /** Launcher activity class, kept only as a fallback for the launch. */
        val activity: String,
        val isGame: Boolean,
    )

    enum class LaunchResult { STARTED, NOT_INSTALLED, FAILED }

    fun packageOf(shortcut: Shortcut?): String? {
        if (shortcut == null) return null
        val pkg = shortcut.getExtra(AndroidGameEntry.EXTRA_PACKAGE)
        return pkg.takeIf { AndroidGameEntry.isAndroid(shortcut.getExtra("storeSource"), it) }
    }

    fun isAndroidEntry(shortcut: Shortcut?): Boolean = packageOf(shortcut) != null

    /** Packages already in the Games list, for the picker's "Already added" rows. */
    fun addedPackages(shortcuts: List<Shortcut>): Set<String> = shortcuts.mapNotNullTo(HashSet()) { packageOf(it) }

    /**
     * Every app with a launcher entry (phone launcher, or TV launcher for apps that only have that),
     * minus Bannerlator itself, sorted by label. Blocking — PackageManager calls; run off main.
     * No QUERY_ALL_PACKAGES: the manifest's <queries> MAIN/LAUNCHER intent is what makes these
     * visible on API 30+ targets (the current targetSdk, 28, sees them anyway).
     */
    fun listInstalledApps(context: Context): List<InstalledApp> {
        val pm = context.packageManager
        val self = context.packageName
        val found = LinkedHashMap<String, InstalledApp>()
        for (category in arrayOf(Intent.CATEGORY_LAUNCHER, Intent.CATEGORY_LEANBACK_LAUNCHER)) {
            val query = Intent(Intent.ACTION_MAIN).addCategory(category)
            val infos = runCatching { pm.queryIntentActivities(query, 0) }.getOrDefault(emptyList())
            for (ri in infos) {
                val ai = ri.activityInfo ?: continue
                val pkg = ai.packageName ?: continue
                if (pkg == self || pkg in found) continue
                val label = runCatching { ri.loadLabel(pm)?.toString() }.getOrNull()?.trim().orEmpty()
                found[pkg] = InstalledApp(pkg, label.ifEmpty { pkg }, ai.name, isGame(ai.applicationInfo))
            }
        }
        return found.values.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
    }

    /** What Android itself marks as a game: the Play category, or the older isGame manifest flag. */
    @Suppress("DEPRECATION")
    fun isGame(app: ApplicationInfo?): Boolean {
        if (app == null) return false
        return app.category == ApplicationInfo.CATEGORY_GAME || (app.flags and ApplicationInfo.FLAG_IS_GAME) != 0
    }

    // Icons for the picker rows; a few dozen at 96px is well under this.
    private val iconCache = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    /** The app's icon as a [sizePx] square bitmap, cached. Blocking; null when the app is gone. */
    fun iconBitmap(context: Context, pkg: String, sizePx: Int): Bitmap? {
        val key = "$pkg@$sizePx"
        iconCache.get(key)?.let { return it }
        val drawable = try {
            context.packageManager.getApplicationIcon(pkg)
        } catch (_: PackageManager.NameNotFoundException) {
            return null
        }
        val bmp = runCatching { drawable.toBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888) }.getOrNull() ?: return null
        iconCache.put(key, bmp)
        return bmp
    }

    private fun portraitTile(context: Context, pkg: String): Bitmap? {
        val icon = iconBitmap(context, pkg, TILE_ICON) ?: return null
        val tile = Bitmap.createBitmap(TILE_W, TILE_H, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(tile)
        canvas.drawColor(TILE_BG)
        canvas.drawBitmap(icon, ((TILE_W - TILE_ICON) / 2).toFloat(), ((TILE_H - TILE_ICON) / 2).toFloat(), null)
        return tile
    }

    /**
     * Writes the Games-list entry for [app] into [container]'s desktop dir, with the app's icon as
     * the card art (Icon= PNG) and the cover. A name already taken by another game gets
     * " (Android)" so a Windows copy of the same title is never overwritten. Blocking.
     * Returns the new `.desktop`, or null if it could not be written.
     */
    fun addToShortcuts(context: Context, container: Container, app: InstalledApp): File? {
        val desktopDir = container.desktopDir
        if (!desktopDir.isDirectory && !desktopDir.mkdirs()) {
            Log.w(TAG, "cannot create $desktopDir")
            return null
        }
        val wanted = AndroidGameEntry.safeName(app.label, app.packageName)
        var base = wanted
        if (File(desktopDir, "$base.desktop").exists()) base = "$wanted (Android)"
        var n = 2
        while (File(desktopDir, "$base.desktop").exists()) base = "$wanted (Android $n)".also { n++ }

        val tile = portraitTile(context, app.packageName)
        var iconName: String? = null
        if (tile != null) {
            val iconsDir = container.getIconsDir(64)
            if (iconsDir != null && (iconsDir.isDirectory || iconsDir.mkdirs()) &&
                FileUtils.saveBitmapToFile(tile, File(iconsDir, "$base.png"))
            ) iconName = base
        }
        val file = File(desktopDir, "$base.desktop")
        val text = AndroidGameEntry.desktopEntry(app.label, app.packageName, app.activity, iconName)
        if (!FileUtils.writeString(file, text)) {
            Log.w(TAG, "could not write $file")
            return null
        }
        if (tile != null) {
            try { Shortcut(container, file).saveCustomCoverArt(tile) } catch (e: Exception) { Log.w(TAG, "cover for $base", e) }
        }
        Log.i(TAG, "added ${app.packageName} as '$base' in container ${container.id}")
        return file
    }

    fun isInstalled(context: Context, pkg: String): Boolean = try {
        context.packageManager.getApplicationInfo(pkg, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    /**
     * The intent that opens the game as Android would from its own launcher icon: the package's
     * launch intent, the TV one for TV-only apps, else the activity recorded when it was added.
     * Null when the app is gone or has no way in.
     */
    fun launchIntent(context: Context, shortcut: Shortcut): Intent? {
        val pkg = packageOf(shortcut) ?: return null
        if (!isInstalled(context, pkg)) return null
        val pm = context.packageManager
        return pm.getLaunchIntentForPackage(pkg)
            ?: pm.getLeanbackLaunchIntentForPackage(pkg)
            ?: shortcut.getExtra(AndroidGameEntry.EXTRA_ACTIVITY).takeIf { it.isNotEmpty() }?.let {
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setClassName(pkg, it)
            }
    }

    /** Starts the game as its own task. Bannerlator stays where it is, in the background. */
    fun launch(context: Context, shortcut: Shortcut): LaunchResult {
        val pkg = packageOf(shortcut) ?: return LaunchResult.FAILED
        if (!isInstalled(context, pkg)) return LaunchResult.NOT_INSTALLED
        val intent = launchIntent(context, shortcut) ?: return LaunchResult.FAILED
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            LaunchResult.STARTED
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "no activity for $pkg", e)
            LaunchResult.FAILED
        } catch (e: SecurityException) {
            Log.w(TAG, "not allowed to start $pkg", e)
            LaunchResult.FAILED
        }
    }

    /** The one-line message for a launch that did not start, or null when it did. */
    fun failureMessage(shortcut: Shortcut, result: LaunchResult): String? = when (result) {
        LaunchResult.STARTED -> null
        LaunchResult.NOT_INSTALLED -> "${shortcut.name} isn't installed anymore"
        LaunchResult.FAILED -> "Couldn't open ${shortcut.name}"
    }
}
