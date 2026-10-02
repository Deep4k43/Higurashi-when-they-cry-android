package com.winlator.star

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.background
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import com.winlator.star.ui.screens.OutlinedAlertDialog
import androidx.compose.material3.Divider
import androidx.compose.material3.Surface
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.winlator.star.core.UpdateManager
import androidx.compose.runtime.rememberCoroutineScope
import com.winlator.star.box64.Box64Preset
import com.winlator.star.container.Container
import com.winlator.star.contents.WrapperManager
import com.winlator.star.core.StringUtils
import com.winlator.star.core.WineInfo
import com.winlator.star.core.WinePath
import com.winlator.star.fexcore.FEXCorePreset
import com.winlator.star.ui.LocalTopBarActions
import com.winlator.star.ui.LocalTopBarOverlayInset
import com.winlator.star.ui.LocalTopBarTransparent
import com.winlator.star.ui.topBarActionsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.preference.PreferenceManager
import com.winlator.star.BuildConfig
import com.winlator.star.core.ImageUtils
import com.winlator.star.core.PreloaderDialog
import com.winlator.star.core.WineThemeManager
import com.winlator.star.core.WinFgDiag
import com.winlator.star.container.ContainerManager
import com.winlator.star.store.AmazonMainActivity
import com.winlator.star.store.EpicMainActivity
import com.winlator.star.store.GogMainActivity
import com.winlator.star.store.SteamMainActivity
import com.winlator.star.ui.AccountUiBus
import com.winlator.star.ui.AppDrawerContent
import com.winlator.star.ui.AppNavGraph
import com.winlator.star.ui.AppTopBar
import com.winlator.star.ui.PreloaderOverlay
import com.winlator.star.ui.Screen
import com.winlator.star.ui.screens.SplashScreen
import com.winlator.star.ui.screens.SplashViewModel
import com.winlator.star.ui.theme.AppThemeState
import com.winlator.star.ui.theme.WinlatorTheme
import com.winlator.star.util.ContainerExeRunner
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.Executors
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    companion object {
        const val PERMISSION_WRITE_EXTERNAL_STORAGE_REQUEST_CODE: Byte = 1
        const val OPEN_FILE_REQUEST_CODE: Byte = 2
        const val OPEN_DIRECTORY_REQUEST_CODE: Byte = 4
        const val OPEN_IMAGE_REQUEST_CODE: Byte = 5

        /** String extra: a Screen route to open (e.g. Screen.Games.route). Used by
         *  the store activities' "Open Shortcuts" action to deep-link back here. */
        const val EXTRA_OPEN_SCREEN = "open_screen"

        /** Display label of the preset graphics driver. The container's `graphicsDriver` id is
         *  [StringUtils.parseIdentifier] of this label (i.e. `wrapper-enhanced`), and the archive
         *  staged from assets lands at filesDir/graphics_driver/<that id>.tzst. */
        const val PRESET_WRAPPER_LABEL = "Wrapper Enhanced (Turnip-Mali-G57-beta_1.1.0)"

        /** Wine-side path of the executable the app boots straight into on launch. */
        const val TARGET_EXECUTABLE_PATH =
            "D:\\MY GAMES\\Higurashi When They Cry Hou - Ch.1 Onikakushi\\HigurashiEp01.exe"

        /** Pref holding [TARGET_EXECUTABLE_PATH] once the preset container exists. */
        const val TARGET_EXECUTABLE_KEY = "target_executable_path"
        /** Pref gating the boot-straight-into-the-game behaviour (default ON). */
        const val AUTO_LAUNCH_KEY = "auto_launch_on_start"
        /** Pref remembering which container owns the auto-launch target. */
        const val TARGET_CONTAINER_KEY = "target_container_id"

        /** How long onCreate's auto-launch waiter keeps polling for install/permission/container. */
        const val AUTO_LAUNCH_WAIT_MS = 5L * 60L * 1000L

        const val TAG = "MainActivity"

        @JvmField val CONTAINER_PATTERN_COMPRESSION_LEVEL: Byte = 9
        @JvmField var PACKAGE_NAME: String = ""
    }

    @JvmField val preloaderDialog: PreloaderDialog = PreloaderDialog(this)
    lateinit var containerManager: ContainerManager
        private set

    private val splashViewModel: SplashViewModel by lazy {
        ViewModelProvider(this)[SplashViewModel::class.java]
    }

    // Holds the OS cold-start splash on screen only until the Compose UI is about to draw its first
    // frame. Not held for the imagefs install — that has its own in-app SplashScreen surface.
    @Volatile
    private var contentReady = false

    private val showAllFilesDialog = mutableStateOf(false)
    private val showAboutDialog = mutableStateOf(false)

    // Route requested via EXTRA_OPEN_SCREEN on a relaunch (onNewIntent); consumed
    // by AppShell, which navigates to it and clears it.
    private val pendingRoute = mutableStateOf<String?>(null)

    // One-shot gate for the boot-straight-into-the-game waiter: set the moment the preset target
    // has been launched (or found to be unlaunchable), so a later recomposition can't re-fire it.
    private val autoLaunchAttempted = mutableStateOf(false)

    // One-shot gate for preset container creation: creation needs a finished imagefs install, and
    // createContainer() is expensive (prefix pack + common DLLs), so at most one attempt per process.
    private val containerCreationAttempted = mutableStateOf(false)

    // ---- Settings-side Controller Test (Input Controls screen) input fork ----
    // While the at-rest controller-test dialog is open in TEST mode a game controller's key/axis events
    // are forked into controllerTestController — a throwaway snapshot that only drives the visualizer —
    // and CONSUMED at the dispatch chokepoints so gamepad presses can't navigate the Compose UI. The
    // gate is read DIRECTLY from ControllerTestBus.active (a @Volatile set SYNCHRONOUSLY when the dialog
    // shows, via a SideEffect — not a late LaunchedEffect/callback), AND'd with !controllerTestPaused so
    // a background can't leave it latched. This is the fix for the "doesn't react + leaks until rotate"
    // bug: the very first press after opening is already gated on the immediate value.
    @Volatile private var controllerTestPaused = false
    private val controllerTestController = com.winlator.star.inputcontrols.ExternalController()
    private var controllerTestGuideDown = false
    private var lastControllerTestAxisLogMs = 0L

    private fun settingsTestArmed(): Boolean =
        com.winlator.star.ui.controllertest.ControllerTestBus.active && !controllerTestPaused
    private var controllerTestLastDeviceId = -1

    // Steam Controller support in the at-rest test dialog: SDL runs only while the dialog is open (and
    // the setting is on), feeding the same snapshot as an Android pad. A Steam Controller has no Android
    // gamepad device, so without this the test never sees it.
    private var settingsSteamBackend: com.winlator.star.inputcontrols.SteamControllerBackend? = null
    private var settingsSteamPads = 0

    private val openImageLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val bitmap = result.data?.data?.let {
                ImageUtils.getBitmapFromUri(this, it, 1280)
            } ?: return@registerForActivityResult
            val file = WineThemeManager.getUserWallpaperFile(this)
            ImageUtils.save(bitmap, file, Bitmap.CompressFormat.PNG, 100)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setCompatVectorFromResourcesEnabled(true)
        // Wire the AndroidX cold-start splash BEFORE super.onCreate so the OS splash bridges the gap
        // to our first Compose frame; hold it only until the UI is ready to draw (set just below).
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        splashScreen.setKeepOnScreenCondition { !contentReady }

        PACKAGE_NAME = applicationContext.packageName
        AppThemeState.init(this)
        // Apply the user's App-orientation preference to THIS app-UI activity only (the game's
        // XServerDisplayActivity manages its own orientation and is unaffected).
        com.winlator.star.core.AppOrientation.apply(this)

        val prefs = PreferenceManager.getDefaultSharedPreferences(this)

        val winlatorDir = File(SettingsFragment.DEFAULT_WINLATOR_PATH)
        if (!winlatorDir.exists()) winlatorDir.mkdirs()

        containerManager = ContainerManager(this)

        // Stage the preset graphics driver (assets -> filesDir/graphics_driver import) before the
        // container that points at it is created, so the id is resolvable on the very first launch.
        stagePresetGraphicsDriver()

        val selectedMenuItemId = intent.getIntExtra("selected_menu_item_id", 0)
        val startRoute = validRouteOrNull(intent.getStringExtra(EXTRA_OPEN_SCREEN))
            ?: menuItemIdToRoute(selectedMenuItemId)
            ?: when {
                prefs.getBoolean("enable_big_picture_mode", false) -> Screen.BigPicture.route
                prefs.getString("default_landing_screen", "games") == "containers" -> Screen.Containers.route
                else -> Screen.Games.route
            }

        val willInstall = splashViewModel.installIfNeeded(this)
        if (!willInstall) {
            // imagefs is already installed, so the preset prefix can be laid down right away.
            // (During an install this is deferred to the auto-launch waiter — see below.)
            createDefaultContainerIfNeeded()
            // Already installed — request permissions immediately
            requestAppPermissions()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
                showAllFilesDialog.value = true
            }
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
            }
        }
        // If willInstall == true: permissions are requested after user taps Proceed

        // First-run/install decision is made; let the OS splash hand off to the Compose UI.
        contentReady = true

        // Settings-side Controller Test: the Input Controls screen's test dialog arms/disarms the input
        // fork through this bus (so this Activity consumes gamepad events instead of navigating the UI),
        // and asks us to natively rumble the live pad for "Identify".
        // The gate itself is ControllerTestBus.active (read directly in dispatch). This callback only
        // clears the throwaway controller's stale state when the dialog opens, and drops the snapshot
        // when it closes.
        com.winlator.star.ui.controllertest.ControllerTestBus.onActiveChanged =
            com.winlator.star.ui.controllertest.ControllerTestBus.ActiveCallback { active ->
                if (active) {
                    controllerTestController.state.reset()
                    controllerTestController.remappedState.reset()
                    controllerTestGuideDown = false
                    startSettingsSteamController()
                } else {
                    stopSettingsSteamController()
                    com.winlator.star.ui.controllertest.ControllerTestBus.setSnapshot(null)
                }
            }
        com.winlator.star.ui.controllertest.ControllerTestBus.onIdentify =
            Runnable { settingsControllerIdentify() }

        setContent {
            WinlatorTheme {
                val isInstalling by splashViewModel.isInstalling.collectAsState()
                val installProgress by splashViewModel.progress.collectAsState()
                val showProceed by splashViewModel.showProceed.collectAsState()

                Box(modifier = Modifier.fillMaxSize()) {
                    AppShell(
                        startRoute = startRoute,
                        pendingRoute = pendingRoute.value,
                        onPendingRouteConsumed = { pendingRoute.value = null },
                        showAllFilesDialog = showAllFilesDialog.value,
                        showAboutDialog = showAboutDialog.value,
                        onDismissAllFilesDialog = { showAllFilesDialog.value = false },
                        onConfirmAllFilesDialog = {
                            showAllFilesDialog.value = false
                            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                            intent.data = Uri.parse("package:$packageName")
                            startActivity(intent)
                        },
                        onDismissAboutDialog = { showAboutDialog.value = false },
                        onAboutRequested = { showAboutDialog.value = true },
                        onLaunchStore = { screen -> launchStore(screen) },
                    )

                    // Resume a mid-flight component installer (Phase 3b) after the app restarts.
                    // On completion/discard it routes back to Games (via pendingRoute, same one-shot
                    // channel the store deep-link uses) so the user isn't stranded on the resume dialog.
                    com.winlator.star.ui.screens.ComponentInstallResume(
                        onNavigateToGames = { pendingRoute.value = Screen.Games.route },
                    )

                    if (isInstalling) {
                        SplashScreen(
                            progress = installProgress,
                            showProceed = showProceed,
                            onProceed = {
                                splashViewModel.dismissSplash()
                                requestAppPermissions()
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                                    !Environment.isExternalStorageManager()
                                ) {
                                    showAllFilesDialog.value = true
                                }
                                if (Build.VERSION.SDK_INT >= 33 &&
                                    ContextCompat.checkSelfPermission(
                                        this@MainActivity,
                                        Manifest.permission.POST_NOTIFICATIONS
                                    ) != PackageManager.PERMISSION_GRANTED
                                ) {
                                    requestPermissions(
                                        arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0
                                    )
                                }
                            },
                        )
                    }

                    // Compose-based preloader overlay — replaces XML PreloaderDialog
                    PreloaderOverlay()

                    // Boot straight into the preset target: the container list below is only the
                    // fallback for the cases this waiter can't get past (imagefs install still
                    // running, All Files Access not granted yet, or the exe missing on disk).
                    LaunchedEffect(Unit) { awaitAndLaunchTarget() }
                }
            }
        }
    }

    private fun launchStore(screen: Screen) {
        val cls = when (screen) {
            Screen.Gog    -> GogMainActivity::class.java
            Screen.Epic   -> EpicMainActivity::class.java
            Screen.Amazon -> AmazonMainActivity::class.java
            Screen.Steam  -> SteamMainActivity::class.java
            else          -> return
        }
        startActivity(Intent(this, cls))
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // Install runs independently now; nothing to do after storage permission result.
    }

    private fun requestAppPermissions() {
        val hasWrite = ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        val hasRead = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        val storageReady = hasWrite && hasRead || Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        if (storageReady) return  // Already granted; install was already started separately.

        requestPermissions(
            arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE),
            PERMISSION_WRITE_EXTERNAL_STORAGE_REQUEST_CODE.toInt(),
        )
    }

    /** Called by DownloadProgressDialog after a download to re-request permissions if needed. */
    fun doPermissionsFlow() {
        requestAppPermissions()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !android.os.Environment.isExternalStorageManager()) {
            showAllFilesDialog.value = true
        }
    }

    /**
     * Creates the preset container exactly once per process, and only once the imagefs install has
     * finished: [ContainerManager.createContainer] extracts `<wine>_container_pattern.tzst` or the
     * imagefs `prefixPack.txz` plus the common DLL layers, so a first-run attempt during install
     * would fail and delete the freshly-made container directory. Called from onCreate when nothing
     * is installing, and from [awaitAndLaunchTarget] the moment an in-flight install completes.
     */
    private fun createDefaultContainerIfNeeded() {
        if (containerCreationAttempted.value) return
        if (containerManager.getContainers().isNotEmpty()) {
            containerCreationAttempted.value = true
            return
        }
        containerCreationAttempted.value = true
        createDefaultContainer()
    }

    /**
     * First-run container for the Higurashi Ch.1 auto-launch target:
     * 1280x720 on the X11 display backend, the "Wrapper Enhanced" graphics driver, DXVK
     * 1.7.2-async (+VKD3D 2.8), Box64 0.3.7 and FEXCore 2505-0 with the performance presets.
     * On success the Wine-side path of the game exe (and the auto-launch switch) is persisted so
     * every cold start can boot straight into it.
     */
    private fun createDefaultContainer() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)

        // Smart Wine selection: Proton-10.0 if present, else bundled Proton
        val wineTarget = if (java.io.File(filesDir, "installed_wine/Proton-10.0-arm64ec-0").exists()) {
            "Proton-10.0-arm64ec-0"
        } else {
            WineInfo.MAIN_WINE_VERSION.identifier()
        }

        // Smart FEXCore selection: 2608-0 if present, else bundled 2508-0
        val fexTarget = if (java.io.File(filesDir, "fexcore/fexcore-2608.tzst").exists()) "2608-0" else "2508-0"

        val json = JSONObject().apply {
            put("name", "Higurashi Ch.1")
            put("screenSize", "1280x720")
            put("fullscreenMode", 0)
            put("lc_all", "en_US.UTF-8")
            put("startupSelection", "2")
            put("extraData", JSONObject().apply {
                put("displayBackend", Container.DISPLAY_BACKEND_X11)
                put("autoCloseOnExit", "1")
            })
            put("graphicsDriver", StringUtils.parseIdentifier(PRESET_WRAPPER_LABEL))
            put("graphicsDriverConfig", "maxDeviceMemory=4096,presentMode=mailbox,bcnEmulation=gpu,bcnWorkerThreads=0,bcnEmulationCaps=true,transcodeASTC=true")
            put("dxwrapper", Container.DEFAULT_DXWRAPPER)
            put("dxwrapperConfig", "version=1.7.2-async-1,vkd3dVersion=none,vkd3dLevel=12_1,ddrawWrapper=none,async=1,asyncCache=0,framerate=0,ckpt=1")
            put("wineVersion", wineTarget)
            put("emulator", "fexcore")
            put("box64Version", "0.3.7")
            put("fexcoreVersion", fexTarget)
            put("box64Preset", "Performance (Mali)")
            put("fexcorePreset", "Performance")
            put("audioDriver", Container.DEFAULT_AUDIO_DRIVER)
            put("drives", Container.DEFAULT_DRIVES)
            put("cpuList", "0-7")
            put("renderer", "vulkan")
            put("rendererPresentMode", "mailbox")
            put("runAsAdmin", true)
        }

        containerManager.createContainerAsync(json, null) { container ->
            if (container != null) {
                prefs.edit()
                    .putString(TARGET_EXECUTABLE_KEY, TARGET_EXECUTABLE_PATH)
                    .putBoolean(AUTO_LAUNCH_KEY, true)
                    .putInt(TARGET_CONTAINER_KEY, container.id)
                    .apply()
            }
        }
    }

    /**
     * Stage the preset graphics driver from assets as a user import (filesDir/graphics_driver/
     * <id>.tzst + its .meta sidecar) — the extraction path only resolves an unknown driver id
     * through that exact file, and the import also gives the driver its label in the Graphics
     * Driver dropdown. Idempotent: an already-staged import is left untouched.
     */
    private fun stagePresetGraphicsDriver() {
        val identifier = StringUtils.parseIdentifier(PRESET_WRAPPER_LABEL)
        val wrapperManager = WrapperManager(this)
        if (wrapperManager.isImported(identifier)) return

        Executors.newSingleThreadExecutor().execute {
            val archive = File(cacheDir, "$identifier.tzst")
            try {
                assets.open("graphics_driver/$identifier.tzst").use { input ->
                    archive.outputStream().use { output -> input.copyTo(output) }
                }
                val imported = wrapperManager.importWrapper(Uri.fromFile(archive), PRESET_WRAPPER_LABEL)
                if (BuildConfig.DEBUG) Log.d(TAG, "preset graphics driver staged as '$imported'")
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) Log.d(TAG, "preset graphics driver staging failed: ${e.message}")
            } finally {
                archive.delete()
            }
        }
    }

    /**
     * Waits out the imagefs install (and the storage permission), creates the preset container if
     * this first run doesn't have one yet, then boots straight into the target — bypassing the
     * container list. Polls at 2 Hz for at most [AUTO_LAUNCH_WAIT_MS] and gives up silently (the
     * normal launcher UI is the fallback) once the target is known to be unlaunchable.
     */
    private suspend fun awaitAndLaunchTarget() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        if (!prefs.getBoolean(AUTO_LAUNCH_KEY, true)) return
        // Falls back to the preset path: on a very first run the preference is only written once
        // createDefaultContainer() reports success, which happens a moment into this very wait.
        val target = (prefs.getString(TARGET_EXECUTABLE_KEY, null) ?: "").trim()
            .ifEmpty { TARGET_EXECUTABLE_PATH }

        val deadline = System.currentTimeMillis() + AUTO_LAUNCH_WAIT_MS
        while (System.currentTimeMillis() < deadline && !autoLaunchAttempted.value) {
            val installing = splashViewModel.isInstalling.value
            if (!installing) createDefaultContainerIfNeeded()

            val storageReady = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Environment.isExternalStorageManager()
            } else {
                ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                    PackageManager.PERMISSION_GRANTED
            }
            val container = if (!installing && storageReady) {
                val wanted = prefs.getInt(TARGET_CONTAINER_KEY, -1)
                val containers = containerManager.getContainers()
                containers.firstOrNull { it.id == wanted } ?: containers.firstOrNull()
            } else null

            if (container != null) {
                autoLaunchAttempted.value = true
                launchTarget(container, target)
                return
            }
            delay(500)
        }
    }

    /** Resolves the Wine-side target to a file on disk and hands it to the standard runner. */
    private fun launchTarget(container: Container, winPath: String) {
        var exe = WinePath.resolveAndroidPath(container, winPath)
        if (exe == null || !exe.isFile) {
            val root = java.io.File("/storage/emulated/0")
            val p1 = java.io.File(root, "MY GAMES/Higurashi When They Cry Hou - Ch.1 Onikakushi/HigurashiEp01.exe")
            val p2 = java.io.File(root, "Download/MY GAMES/Higurashi When They Cry Hou - Ch.1 Onikakushi/HigurashiEp01.exe")
            exe = when {
                p1.isFile -> p1
                p2.isFile -> p2
                else -> {
                    try {
                        root.walkTopDown()
                            .onEnter { !it.name.equals("Android", ignoreCase = true) }
                            .maxDepth(6)
                            .firstOrNull { it.isFile && it.name.equals("HigurashiEp01.exe", ignoreCase = true) }
                    } catch (e: Exception) { null }
                }
            }
        }
        if (exe == null || !exe.isFile) {
            if (BuildConfig.DEBUG) Log.d(TAG, "auto-launch skipped: $winPath -> not on disk")
            return
        }
        val error = ContainerExeRunner.run(this, container, exe)
        if (error != null && BuildConfig.DEBUG) Log.d(TAG, "auto-launch failed: $error")
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Deep-link from the store activities (CLEAR_TOP|SINGLE_TOP relaunch).
        validRouteOrNull(intent.getStringExtra(EXTRA_OPEN_SCREEN))?.let {
            pendingRoute.value = it
        }
    }

    override fun onDestroy() {
        // Kill any armed win-fg diagnostic-log capture (logcat subprocess) so it can't outlive the app.
        // The game (XServerDisplayActivity) shares this process, so a mid-play capture survives until here.
        WinFgDiag.stopDiagLog(this)
        stopSettingsSteamController()
        super.onDestroy()
    }

    private fun startSettingsSteamController() {
        if (settingsSteamBackend != null) return
        if (!com.winlator.star.ui.components.GlobalControllerPrefs.isSteamControllerEnabled(this)) return
        val backend = com.winlator.star.inputcontrols.SteamControllerBackend(
            this, com.winlator.star.inputcontrols.SteamControllerBackend.TRACKPAD_MOUSE_OFF,
            com.winlator.star.ui.components.GlobalControllerPrefs.getSteamPaddleBindings(this),
            object : com.winlator.star.inputcontrols.SteamControllerBackend.Listener {
                override fun onSteamPadConnected(pad: com.winlator.star.inputcontrols.ExternalController) {
                    settingsSteamPads++
                }

                override fun onSteamPadDisconnected(pad: com.winlator.star.inputcontrols.ExternalController) {
                    settingsSteamPads = (settingsSteamPads - 1).coerceAtLeast(0)
                }

                override fun onSteamPadState(
                    pad: com.winlator.star.inputcontrols.ExternalController,
                    guideDown: Boolean,
                    quickAccessDown: Boolean,
                    pressedKeyCodes: IntArray,
                ) {
                    if (!settingsTestArmed()) return
                    controllerTestController.state.copy(pad.state)
                    controllerTestGuideDown = guideDown
                    controllerTestLastDeviceId = pad.deviceId
                    val st = controllerTestController.state
                    com.winlator.star.ui.controllertest.ControllerTestBus.setSnapshot(
                        com.winlator.star.ui.controllertest.ControllerTestSnapshot(
                            st.buttons.toInt() and 0xFFFF,
                            st.dpad[0], st.dpad[1], st.dpad[2], st.dpad[3],
                            st.thumbLX, st.thumbLY, st.thumbRX, st.thumbRY,
                            st.triggerL, st.triggerR,
                            guideDown,
                            pad.deviceId,
                            pad.name ?: "Steam Controller",
                            com.winlator.star.ui.controllertest.PadArt.STEAM.ordinal,
                            -1,
                            true,
                            quickAccessDown
                        )
                    )
                }

                override fun onSteamPadBinding(binding: com.winlator.star.inputcontrols.Binding, down: Boolean) {}
                override fun onSteamPadMouseMove(dx: Int, dy: Int) {}
                override fun onSteamPadMouseButton(secondary: Boolean, down: Boolean) {}
            }
        )
        if (backend.start()) settingsSteamBackend = backend
    }

    private fun stopSettingsSteamController() {
        val backend = settingsSteamBackend ?: return
        settingsSteamBackend = null
        settingsSteamPads = 0
        backend.stop()
    }

    /** While SDL owns a Steam Controller in the test dialog, whatever Android still reports for it (its
     *  keyboard/mouse mode) is the same pad: consume it so it can't navigate the UI or feed the fork. */
    private fun isSettingsSteamShadowEvent(device: android.view.InputDevice?): Boolean =
        settingsSteamBackend != null && settingsSteamPads > 0 &&
            device?.vendorId == com.winlator.star.inputcontrols.SteamControllerBackend.VALVE_VENDOR_ID

    override fun onPause() {
        super.onPause()
        // Pause (not clear) the fork across a background; the bus flag stays set by the open dialog so
        // onResume re-arms without needing the dialog to recompose.
        controllerTestPaused = true
    }

    override fun onStop() {
        super.onStop()
        controllerTestPaused = true
    }

    override fun onResume() {
        super.onResume()
        controllerTestPaused = false
        // A game may still be playing on the TV. Tapping the launcher brings THIS task forward — the
        // companion screen is in a task of its own, deliberately, so the system has no reason to prefer
        // it — and the user was left looking at the games list with nothing to say a session was live.
        // Hand the screen over to the companion, which is where "Send input back to the TV" and "End the
        // game" are, and which sends the controller back to the TV as it comes up.
        //
        // The companion answers for itself whether there is anything to come back to, from the state the
        // SESSION maintains: no live TV session and this is a no-op, so an ordinary handheld session (or
        // no session at all) lands exactly where it always did. Nothing here reaches the session on the
        // TV: the game keeps playing, untouched, whichever way this goes.
        com.winlator.star.display.TvCompanionActivity.resumeForLiveSession(this)
    }

    // ---- Settings-side Controller Test input fork ----
    // Gate = ControllerTestBus.active (armed SYNCHRONOUSLY by the dialog's SideEffect) AND not paused.
    // When CLOSED both overrides just call super. When OPEN in TEST mode, a game-controller event drives
    // ONLY the throwaway visualizer snapshot and is CONSUMED so it can't navigate the app UI.

    override fun dispatchGenericMotionEvent(event: android.view.MotionEvent): Boolean {
        if (isSettingsSteamShadowEvent(event.device)) return true
        if (settingsTestArmed() && isControllerTestMotionEvent(event)) {
            controllerTestFeedMotionEvent(event)
            return true
        }
        return super.dispatchGenericMotionEvent(event)
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (isSettingsSteamShadowEvent(event.device)) return true
        if (settingsTestArmed() &&
            com.winlator.star.inputcontrols.ExternalController.isGameController(event.device)) {
            controllerTestFeedKeyEvent(event)
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun isControllerTestMotionEvent(event: android.view.MotionEvent): Boolean {
        val src = event.source
        val joystickish =
            (src and android.view.InputDevice.SOURCE_JOYSTICK) == android.view.InputDevice.SOURCE_JOYSTICK ||
            (src and android.view.InputDevice.SOURCE_GAMEPAD) == android.view.InputDevice.SOURCE_GAMEPAD
        return joystickish && com.winlator.star.inputcontrols.ExternalController.isGameController(event.device)
    }

    private fun controllerTestFeedMotionEvent(event: android.view.MotionEvent) {
        if (com.winlator.star.inputcontrols.ExternalController.isJoystickDevice(event)) {
            val now = android.os.SystemClock.uptimeMillis()
            if (now - lastControllerTestAxisLogMs > 1000L) {
                lastControllerTestAxisLogMs = now
                android.util.Log.d(
                    "ControllerTest",
                    "settings axis dispatch reached src=" + event.source + " dev=" + event.deviceId
                )
            }
        }
        controllerTestController.updateStateFromMotionEvent(event)
        controllerTestPublishSnapshot(event.device)
    }

    private fun controllerTestFeedKeyEvent(event: android.view.KeyEvent) {
        if (event.repeatCount == 0) controllerTestController.updateStateFromKeyEvent(event)
        val kc = event.keyCode
        if (kc == android.view.KeyEvent.KEYCODE_BUTTON_MODE || kc == android.view.KeyEvent.KEYCODE_HOME) {
            controllerTestGuideDown = event.action == android.view.KeyEvent.ACTION_DOWN
        }
        controllerTestPublishSnapshot(event.device)
    }

    private fun controllerTestPublishSnapshot(device: android.view.InputDevice?) {
        val st = controllerTestController.state
        var battery = -1
        if (device != null && Build.VERSION.SDK_INT >= 29) {
            try {
                val bs = device.batteryState
                if (bs != null && bs.isPresent) {
                    val cap = bs.capacity
                    if (cap >= 0f) battery = Math.round(cap * 100f)
                }
            } catch (_: Throwable) { }
        }
        var hasVibrator = false
        if (device != null) {
            val vib = device.vibrator
            hasVibrator = vib != null && vib.hasVibrator()
        }
        controllerTestLastDeviceId = device?.id ?: -1
        com.winlator.star.ui.controllertest.ControllerTestBus.setSnapshot(
            com.winlator.star.ui.controllertest.ControllerTestSnapshot(
                st.buttons.toInt() and 0xFFFF,
                st.dpad[0], st.dpad[1], st.dpad[2], st.dpad[3],
                st.thumbLX, st.thumbLY, st.thumbRX, st.thumbRY,
                st.triggerL, st.triggerR,
                controllerTestGuideDown,
                device?.id ?: -1,
                device?.name ?: "",
                com.winlator.star.ui.controllertest.classifyPadArt(device).ordinal,
                battery,
                hasVibrator
            )
        )
    }

    /** Native "Identify" — rumble the last pad the visualizer saw, via VibratorManager (independent
     *  motors, API 31+) or the single vibrator otherwise. No game / WinHandler here. */
    private fun settingsControllerIdentify() {
        val id = controllerTestLastDeviceId
        // Steam Controller (SDL, synthetic deviceId): rumble through SDL.
        if (id <= com.winlator.star.inputcontrols.SteamControllerBackend.DEVICE_ID_BASE) {
            settingsSteamBackend?.rumble(id, 48000, 32000, 420)
            return
        }
        if (id < 0) return
        val device = android.view.InputDevice.getDevice(id) ?: return
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                val vm = device.vibratorManager
                val ids = vm?.vibratorIds
                if (vm != null && ids != null && ids.isNotEmpty()) {
                    val combo = android.os.CombinedVibration.startParallel()
                    for (vid in ids) combo.addVibrator(vid, android.os.VibrationEffect.createOneShot(420L, 200))
                    vm.vibrate(combo.combine())
                    return
                }
            }
            val v = device.vibrator
            if (v != null && v.hasVibrator()) {
                v.vibrate(android.os.VibrationEffect.createOneShot(420L, 200))
            }
        } catch (_: Throwable) { }
    }

    /** Only accepts known drawer routes so a bad extra can't crash navigation. */
    private fun validRouteOrNull(route: String?): String? =
        route?.takeIf { r -> Screen.drawerItems.any { it.route == r } }

    private fun menuItemIdToRoute(itemId: Int): String? = when (itemId) {
        R.id.main_menu_containers -> Screen.Containers.route
        R.id.main_menu_shortcuts  -> Screen.Games.route
        R.id.main_menu_contents   -> Screen.Contents.route
        R.id.main_menu_input_controls -> Screen.InputControls.route
        R.id.main_menu_adrenotools_gpu_drivers -> Screen.AdrenoTools.route
        R.id.main_menu_settings   -> Screen.Settings.route
        else -> null
    }
}

@Composable
private fun AppShell(
    startRoute: String,
    pendingRoute: String?,
    onPendingRouteConsumed: () -> Unit,
    showAllFilesDialog: Boolean,
    showAboutDialog: Boolean,
    onDismissAllFilesDialog: () -> Unit,
    onConfirmAllFilesDialog: () -> Unit,
    onDismissAboutDialog: () -> Unit,
    onAboutRequested: () -> Unit,
    onLaunchStore: (Screen) -> Unit,
) {
    val navController = rememberNavController()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val topBarActionsState = remember { topBarActionsState() }
    val topBarTransparentState = remember { mutableStateOf(false) }

    val backstackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backstackEntry?.destination?.route ?: startRoute

    // Big Picture is a full-bleed couch/TV launcher: no top bar, no drawer gestures, no scaffold
    // content padding (it draws its own immersive layout).
    // Big Picture now renders the landscape "games wall", which draws its OWN rail header + nav rail +
    // footer, so it gets the chrome-free full-bleed treatment. The Games route is the normal phone-grid
    // library again (top bar + drawer), so it is NOT full-bleed.
    val isBigPicture = currentRoute == Screen.BigPicture.route
    val isFullBleed = isBigPicture

    // In-app update banner: only when a newer stable exists, notify is on, and
    // this version wasn't skipped.
    var bannerUpdate by remember { mutableStateOf<UpdateManager.UpdateInfo?>(null) }
    var bannerDismissed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        UpdateManager.check(context) { info ->
            (context as? MainActivity)?.runOnUiThread {
                if (info != null && info.isNewer &&
                    UpdateManager.isNotifyEnabled(context) &&
                    info.versionCode != UpdateManager.skippedVersionCode(context)
                ) {
                    bannerUpdate = info
                }
            }
        }
    }

    // Navigate to a route requested by a relaunch intent (store "Open Shortcuts").
    LaunchedEffect(pendingRoute) {
        if (pendingRoute != null) {
            navController.navigate(pendingRoute) {
                popUpTo(navController.graph.startDestinationId) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
            onPendingRouteConsumed()
        }
    }

    // Clear top bar actions on navigation so stale actions from a previous screen don't persist.
    // Screens that need actions re-set them via SideEffect on each recomposition.
    // Also re-read the optional signed-in account so the ☰→avatar swap / drawer header reflect a login
    // or logout that happened on the screen we're returning from.
    LaunchedEffect(currentRoute) {
        topBarActionsState.value = {}
        AccountUiBus.refresh(context)
    }
    // Reactive mirror of the signed-in account (null = logged out / anonymous UX unchanged).
    val account = AccountUiBus.account

    val screenTitle = when {
        currentRoute.startsWith("container_detail") -> {
            val id = backstackEntry?.arguments?.getInt("id") ?: -1
            when {
                id == com.winlator.star.ui.screens.ContainerDetailViewModel.EDIT_DEFAULTS_ID ->
                    context.getString(R.string.new_container_defaults)
                id > 0 -> context.getString(R.string.edit_container)
                else -> context.getString(R.string.new_container)
            }
        }
        else -> Screen.drawerItems.firstOrNull { it.route == currentRoute }?.label ?: "Winlator"
    }

    // The Games tab's XMB view asks for a see-through top bar; the screen is then laid out under the
    // bar so its backdrop runs to the top. Games route only, and not while the update banner shows
    // (it sits between the bar and the screen).
    val barOverlay = currentRoute == Screen.Games.route && topBarTransparentState.value &&
        !isFullBleed && !(bannerUpdate != null && !bannerDismissed)

    CompositionLocalProvider(LocalTopBarActions provides topBarActionsState, LocalTopBarTransparent provides topBarTransparentState) {
    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = !currentRoute.startsWith("container_detail") && !isFullBleed,
        drawerContent = {
            AppDrawerContent(
                currentRoute = currentRoute,
                account = account,
                onNavigate = { screen ->
                    scope.launch { drawerState.close() }
                    navController.navigate(screen.route) {
                        popUpTo(navController.graph.startDestinationId) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                onLaunchStore = { screen ->
                    scope.launch { drawerState.close() }
                    onLaunchStore(screen)
                },
                onAbout = {
                    scope.launch { drawerState.close() }
                    onAboutRequested()
                },
                // The My-account sheet lives on the Shortcuts screen; land there, then ask it to open.
                onMyAccount = {
                    scope.launch { drawerState.close() }
                    navController.navigate(Screen.Games.route) {
                        popUpTo(navController.graph.startDestinationId) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                    AccountUiBus.requestMyAccount()
                },
            )
        },
    ) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                if (!isFullBleed) {
                    AppTopBar(
                        title = screenTitle,
                        showBack = false,
                        // Signed-in + has a picture → the ☰ becomes their avatar (still opens the drawer).
                        // Versioned URL so a live picture change refreshes the swap in lockstep with the drawer.
                        avatarUrl = account?.displayAvatarUrl,
                        onNavClick = {
                            scope.launch {
                                if (drawerState.isOpen) drawerState.close() else drawerState.open()
                            }
                        },
                        // Steam connection status pill, right after the "Games" title (Games screen only).
                        // Self-gates to signed-in users; tap when offline to retry. See SteamConnectionPill.
                        titleTrailing = if (currentRoute == Screen.Games.route) {
                            { com.winlator.star.store.SteamConnectionPill() }
                        } else null,
                        transparent = barOverlay,
                        actions = topBarActionsState.value,
                    )
                }
            },
        ) { innerPadding ->
            val layoutDir = LocalLayoutDirection.current
            val contentPadding = when {
                isFullBleed -> PaddingValues(0.dp)
                // Drawn under the see-through bar: every inset except the top.
                barOverlay -> PaddingValues(
                    start = innerPadding.calculateStartPadding(layoutDir),
                    end = innerPadding.calculateEndPadding(layoutDir),
                    bottom = innerPadding.calculateBottomPadding(),
                )
                else -> innerPadding
            }
            CompositionLocalProvider(LocalTopBarOverlayInset provides if (barOverlay) innerPadding.calculateTopPadding() else 0.dp) {
            Column(modifier = Modifier.padding(contentPadding)) {
                val upd = bannerUpdate
                if (upd != null && !bannerDismissed && !isFullBleed) {
                    UpdateBanner(
                        versionName = upd.versionName,
                        onUpdate = {
                            (context as? MainActivity)?.let { UpdateManager.downloadAndInstall(it, upd) {} }
                        },
                        onDismiss = {
                            bannerDismissed = true
                            UpdateManager.skipVersion(context, upd.versionCode)
                        },
                    )
                }
                AppNavGraph(
                    navController = navController,
                    startRoute = startRoute,
                    modifier = Modifier.weight(1f),
                )
                // App-wide minimized progress pill for a running archive unpack. Renders nothing when
                // idle; sits below the nav content so it floats over every screen. Hidden in Big
                // Picture (fullscreen) mode.
                if (!isFullBleed) {
                    com.winlator.star.ui.UnpackProgressPill()
                }
            }
            } // end LocalTopBarOverlayInset
        }
    }
    } // end CompositionLocalProvider

    if (showAllFilesDialog) {
        AllFilesAccessDialog(
            onConfirm = onConfirmAllFilesDialog,
            onDismiss = onDismissAllFilesDialog,
        )
    }

    if (showAboutDialog) {
        AboutDialog(onDismiss = onDismissAboutDialog)
    }
}

@Composable
private fun AllFilesAccessDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    OutlinedAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("All Files Access Required") },
        text = {
            Text(
                "In order to grant access to additional storage devices such as USB storage, " +
                "the All Files Access permission must be granted. Press OK to open Android Settings."
            )
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun UpdateBanner(versionName: String, onUpdate: () -> Unit, onDismiss: () -> Unit) {
    val ink = androidx.compose.ui.graphics.Color(0xFF1A1A2E)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(androidx.compose.ui.graphics.Color(0xFFFFC107))
            .padding(start = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Update available — V $versionName",
            color = ink,
            fontWeight = FontWeight.SemiBold,
            fontSize = 13.sp,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onUpdate) {
            Text("Update", color = ink, fontWeight = FontWeight.Bold)
        }
        TextButton(onClick = onDismiss) {
            Text("Skip", color = ink)
        }
    }
}

@Composable
private fun AboutDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val activity = context as? MainActivity
    var update by remember { mutableStateOf<UpdateManager.UpdateInfo?>(null) }
    LaunchedEffect(Unit) {
        UpdateManager.check(context) { info -> activity?.runOnUiThread { update = info } }
    }
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = androidx.compose.material3.MaterialTheme.colorScheme.surface,
            border = androidx.compose.foundation.BorderStroke(1.dp, androidx.compose.material3.MaterialTheme.colorScheme.outline),
            modifier = androidx.compose.ui.Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = androidx.compose.ui.Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Logo + name
                Image(
                    painter = painterResource(R.drawable.splash_logo),
                    contentDescription = null,
                    modifier = androidx.compose.ui.Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp)
                )
                Text(
                    text = "Bannerlator Bionic",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurface
                )
                val newer = update?.takeIf { it.isNewer }
                Text(
                    // Read from BuildConfig so it tracks the gradle versionName automatically
                    // and never drifts from the real app version again.
                    text = "V ${BuildConfig.VERSION_NAME}" +
                        (newer?.let { " · latest V ${it.versionName}" } ?: ""),
                    fontSize = 13.sp,
                    color = com.winlator.star.ui.theme.OnSurfaceVariant
                )
                if (newer != null) {
                    Button(
                        onClick = { activity?.let { UpdateManager.downloadAndInstall(it, newer) {} } },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = androidx.compose.ui.graphics.Color(0xFF4CAF50)
                        ),
                        modifier = androidx.compose.ui.Modifier.fillMaxWidth()
                    ) { Text("Update now", color = androidx.compose.ui.graphics.Color.White) }
                }

                Spacer(androidx.compose.ui.Modifier.height(4.dp))
                Divider(color = com.winlator.star.ui.theme.Divider)
                Spacer(androidx.compose.ui.Modifier.height(4.dp))

                // Powered by
                AboutSection(title = "Powered By") {
                    AboutRow("Wine",    "Windows compatibility layer")
                    AboutRow("Box64",   "x86_64 emulation on ARM")
                    AboutRow("FEX-Emu", "Fast x86 emulator")
                    AboutRow("Turnip",  "Open-source Vulkan driver")
                }

                Spacer(androidx.compose.ui.Modifier.height(4.dp))
                Divider(color = com.winlator.star.ui.theme.Divider)
                Spacer(androidx.compose.ui.Modifier.height(4.dp))

                // Credits
                AboutSection(title = "Credits") {
                    AboutRow("brunodev85",      "Winlator — original project")
                    AboutRow("MishaMixXx",      "Winlator Bionic")
                    AboutRow("The412Banner",    "Bannerlator")
                    AboutRow("ptitSeb",         "Box64")
                    AboutRow("WineHQ",          "Wine project")
                    AboutRow("Mesa / Freedreno","Turnip Vulkan driver")
                }

                Spacer(androidx.compose.ui.Modifier.height(8.dp))
                TextButton(
                    onClick = onDismiss,
                    modifier = androidx.compose.ui.Modifier.fillMaxWidth()
                ) { Text("Close") }
            }
        }
    }
}

@Composable
private fun AboutSection(title: String, content: @Composable () -> Unit) {
    Column(modifier = androidx.compose.ui.Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = title,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = androidx.compose.material3.MaterialTheme.colorScheme.primary,
            modifier = androidx.compose.ui.Modifier.padding(bottom = 2.dp)
        )
        content()
    }
}

@Composable
private fun AboutRow(name: String, description: String) {
    Row(
        modifier = androidx.compose.ui.Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = name, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = androidx.compose.material3.MaterialTheme.colorScheme.onSurface)
        Spacer(androidx.compose.ui.Modifier.width(8.dp))
        Text(text = description, fontSize = 12.sp, color = com.winlator.star.ui.theme.OnSurfaceVariant)
    }
}
