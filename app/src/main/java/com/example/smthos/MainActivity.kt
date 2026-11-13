package com.example.smthos

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.atan2
import kotlin.random.Random

private val SMTHOS_VERSION = BuildConfig.SMTHOS_VERSION
private const val PREFS_NAME = "smthos_settings"
private const val PREF_WALLPAPER = "wallpaper_uri"
private const val PREF_TEXT_COLOR = "text_color"

private val TerminalBlack = Color.Black
private val DefaultTextColor = Color.White

private val hiddenApps = listOf(
    "ChatGPT",
    "Discord",
    "Google Chat",
    "Messages",
    "TikTok",
    "YouTube"
)

private val knownPackages = mapOf(
    "ChatGPT" to "com.openai.chatgpt",
    "Discord" to "com.discord",
    "Google Chat" to "com.google.android.apps.dynamite",
    "Messages" to "com.google.android.apps.messaging",
    "TikTok" to "com.zhiliaoapp.musically",
    "YouTube" to "com.google.android.youtube"
)

private val preferredNames = mapOf(
    "Google Chat" to "Chat",
    "Google Maps" to "Maps",
    "Google Drive" to "Drive",
    "Google Play Store" to "Play Store",
    "Google Classroom" to "Classroom",
    "Samsung Notes" to "Notes",
    "Samsung Health" to "Health",
    "Galaxy Store" to "Store"
)

private data class PhoneApp(
    val name: String,
    val packageName: String,
    val activityName: String
)

private data class AppShortcut(
    val label: String,
    val app: PhoneApp
)

private data class AppDirectory(
    val visible: Map<String, AppShortcut>,
    val hidden: Map<String, AppShortcut>
)

private fun normalize(value: String): String =
    value.lowercase().filter { it.isLetterOrDigit() }

private fun appKey(app: PhoneApp): String =
    "${app.packageName}/${app.activityName}"

private fun displayName(app: PhoneApp): String =
    preferredNames[app.name] ?: when (app.packageName) {
        "com.google.android.apps.classroom" -> "Classroom"
        else -> app.name
    }

private fun installedApps(context: Context): List<PhoneApp> {
    val intent = Intent(Intent.ACTION_MAIN).apply {
        addCategory(Intent.CATEGORY_LAUNCHER)
    }

    val manager = context.packageManager

    @Suppress("DEPRECATION")
    val results = manager.queryIntentActivities(intent, 0)

    return results.mapNotNull { result ->
        val activity = result.activityInfo
            ?: return@mapNotNull null

        PhoneApp(
            name = result.loadLabel(manager).toString(),
            packageName = activity.packageName,
            activityName = activity.name
        )
    }.distinctBy {
        appKey(it)
    }.sortedWith(
        compareBy<PhoneApp>(
            { it.name.lowercase() },
            { appKey(it) }
        )
    )
}

private fun resolveHiddenApp(
    label: String,
    apps: List<PhoneApp>
): PhoneApp? {
    val packageName = knownPackages[label]

    if (packageName != null) {
        apps.firstOrNull {
            it.packageName == packageName
        }?.let { return it }
    }

    val expectedName = normalize(
        preferredNames[label] ?: label
    )

    return apps.firstOrNull {
        normalize(it.name) == expectedName
    } ?: apps.firstOrNull {
        normalize(it.name) == normalize(label)
    }
}

private fun generateAliases(
    shortcuts: List<AppShortcut>
): Map<String, AppShortcut> {
    val result = linkedMapOf<String, AppShortcut>()

    val ordered = shortcuts.sortedWith(
        compareBy<AppShortcut>(
            { normalize(it.label).length },
            { normalize(it.label) },
            { appKey(it.app) }
        )
    )

    for (shortcut in ordered) {
        val name = normalize(shortcut.label)
        if (name.isEmpty()) continue

        val minimum = minOf(2, name.length)
        var chosen: String? = null

        for (length in minimum..name.length) {
            val candidate = name.take(length)

            val conflicts = ordered.any { other ->
                other != shortcut &&
                        normalize(other.label).startsWith(candidate)
            }

            if (!conflicts && candidate !in result) {
                chosen = candidate
                break
            }
        }

        if (chosen == null) {
            val packagePart = normalize(
                shortcut.app.packageName.substringAfterLast(".")
            )

            val activityPart = normalize(
                shortcut.app.activityName.substringAfterLast(".")
            )

            val base = name + packagePart

            chosen = listOf(
                name,
                base,
                base + activityPart,
                name + normalize(shortcut.app.packageName)
            ).firstOrNull {
                it.isNotEmpty() && it !in result
            }

            if (chosen == null) {
                var number = 2
                var candidate = base + number

                while (candidate in result) {
                    number++
                    candidate = base + number
                }

                chosen = candidate
            }
        }

        result[chosen] = shortcut
    }

    return result
}

private fun resolveShortcut(
    requested: String,
    aliases: Map<String, AppShortcut>
): AppShortcut? {
    val query = normalize(requested)
    if (query.isEmpty()) return null

    if (
        query == "googleclassroom" ||
        query == "classroom"
    ) {
        aliases.values.firstOrNull {
            it.app.packageName ==
                    "com.google.android.apps.classroom"
        }?.let { return it }
    }

    aliases[query]?.let { return it }

    val shortcuts = aliases.values.toList()

    val exactMatches = shortcuts.filter {
        normalize(it.label) == query
    }

    if (exactMatches.size == 1) {
        return exactMatches.first()
    }

    if (query.length < 2) return null

    val matching = shortcuts.filter {
        normalize(it.label).startsWith(query)
    }

    return if (matching.size == 1) {
        matching.first()
    } else {
        null
    }
}

private fun buildDirectory(
    context: Context,
    apps: List<PhoneApp>
): AppDirectory {
    val hiddenResolved = hiddenApps.mapNotNull { label ->
        resolveHiddenApp(label, apps)?.let { app ->
            AppShortcut(
                label = preferredNames[label] ?: label,
                app = app
            )
        }
    }.distinctBy {
        appKey(it.app)
    }

    val hiddenPackages = hiddenResolved
        .map { it.app.packageName }
        .toSet()

    val visibleApps = apps
        .filter {
            it.packageName !in hiddenPackages
        }
        .filter {
            it.packageName != context.packageName
        }
        .map {
            AppShortcut(
                label = displayName(it),
                app = it
            )
        }

    return AppDirectory(
        visible = generateAliases(visibleApps),
        hidden = generateAliases(hiddenResolved)
    )
}

class MainActivity : ComponentActivity() {

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(
            window,
            false
        )

        window.navigationBarColor =
            android.graphics.Color.TRANSPARENT

        WindowCompat.getInsetsController(
            window,
            window.decorView
        ).isAppearanceLightNavigationBars = false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    background = TerminalBlack,
                    surface = TerminalBlack,
                    onBackground = DefaultTextColor,
                    onSurface = DefaultTextColor,
                    primary = DefaultTextColor,
                    onPrimary = TerminalBlack
                )
            ) {
                SmthOS()
            }
        }
    }
}

private fun wallpaperMatrix(
    context: Context,
    uri: Uri
): Matrix {
    val orientation = try {
        context.contentResolver.openInputStream(uri)?.use {
            ExifInterface(it).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
        } ?: ExifInterface.ORIENTATION_NORMAL
    } catch (_: Exception) {
        ExifInterface.ORIENTATION_NORMAL
    }

    return Matrix().apply {
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL ->
                postScale(-1f, 1f)

            ExifInterface.ORIENTATION_ROTATE_180 ->
                postRotate(180f)

            ExifInterface.ORIENTATION_FLIP_VERTICAL ->
                postScale(1f, -1f)

            ExifInterface.ORIENTATION_TRANSPOSE -> {
                postRotate(90f)
                postScale(-1f, 1f)
            }

            ExifInterface.ORIENTATION_ROTATE_90 ->
                postRotate(90f)

            ExifInterface.ORIENTATION_TRANSVERSE -> {
                postRotate(-90f)
                postScale(-1f, 1f)
            }

            ExifInterface.ORIENTATION_ROTATE_270 ->
                postRotate(270f)
        }
    }
}

private suspend fun loadWallpaper(
    context: Context,
    uri: Uri
): androidx.compose.ui.graphics.ImageBitmap? =
    withContext(Dispatchers.IO) {
        try {
            val resolver = context.contentResolver
            val matrix = wallpaperMatrix(context, uri)

            val bounds = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }

            resolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }

            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                return@withContext null
            }

            val maxDimension = 2048
            var sampleSize = 1

            while (
                bounds.outWidth / sampleSize > maxDimension ||
                bounds.outHeight / sampleSize > maxDimension
            ) {
                sampleSize *= 2
            }

            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.RGB_565
            }

            val decoded = resolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(
                    it,
                    null,
                    options
                )
            } ?: return@withContext null

            val corrected = if (matrix.isIdentity) {
                decoded
            } else {
                try {
                    Bitmap.createBitmap(
                        decoded,
                        0,
                        0,
                        decoded.width,
                        decoded.height,
                        matrix,
                        true
                    ).also {
                        if (it !== decoded) {
                            decoded.recycle()
                        }
                    }
                } catch (_: Exception) {
                    decoded
                }
            }

            corrected.asImageBitmap()
        } catch (_: Exception) {
            null
        }
    }

@Composable
private fun BinaryNavigationDecoration(
    textColor: Color
) {
    val density = LocalDensity.current

    val navigationHeight =
        WindowInsets.navigationBars
            .asPaddingValues()
            .calculateBottomPadding()

    var binaryTick by remember {
        mutableIntStateOf(0)
    }

    LaunchedEffect(Unit) {
        while (true) {
            delay(180)
            binaryTick++
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(navigationHeight),
        contentAlignment = Alignment.CenterStart
    ) {
        val reservedRight = 155.dp
        val leftPadding = 16.dp

        val digitWidth = with(density) {
            20.sp.toDp() * 1.35f
        }

        val availableWidth =
            (maxWidth - reservedRight - leftPadding)
                .coerceAtLeast(0.dp)

        val digitCount =
            ((availableWidth.value / digitWidth.value)
                .toInt() - 7)
                .coerceAtLeast(0)

        val binaryText = remember(
            binaryTick,
            digitCount
        ) {
            List(digitCount) {
                if (Random.nextBoolean()) "1" else "0"
            }.joinToString(" ")
        }

        Text(
            text = binaryText,
            modifier = Modifier
                .padding(start = leftPadding)
                .offset(x = 26.dp, y = 1.dp),
            color = textColor,
            fontSize = 20.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            softWrap = false
        )
    }
}

@Composable
private fun TextColorPicker(
    initialColor: Color,
    onApply: (Color) -> Unit,
    onDismiss: () -> Unit
) {
    val hsv = remember(initialColor) {
        FloatArray(3).also {
            android.graphics.Color.colorToHSV(
                initialColor.toArgb(),
                it
            )
        }
    }

    var hue by remember(initialColor) {
        mutableFloatStateOf(hsv[0])
    }

    var saturation by remember(initialColor) {
        mutableFloatStateOf(hsv[1])
    }

    var brightness by remember(initialColor) {
        mutableFloatStateOf(hsv[2])
    }

    fun selectedColor(): Color =
        Color(
            android.graphics.Color.HSVToColor(
                floatArrayOf(hue, saturation, brightness)
            )
        )

    val wheelColors = remember {
        listOf(
            Color.Red,
            Color.Yellow,
            Color.Green,
            Color.Cyan,
            Color.Blue,
            Color.Magenta,
            Color.Red
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF171717),
        titleContentColor = Color.White,
        textContentColor = Color.White,
        title = {
            Text(
                "Text colour",
                fontFamily = FontFamily.Monospace
            )
        },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement =
                    Arrangement.spacedBy(12.dp)
            ) {
                Canvas(
                    modifier = Modifier
                        .size(220.dp)
                        .pointerInput(Unit) {
                            detectTapGestures { offset ->
                                val center = Offset(
                                    size.width / 2f,
                                    size.height / 2f
                                )

                                val angle = atan2(
                                    offset.y - center.y,
                                    offset.x - center.x
                                )

                                hue = (
                                        Math.toDegrees(angle.toDouble())
                                            .toFloat() + 360f
                                        ) % 360f
                            }
                        }
                        .pointerInput(Unit) {
                            detectDragGestures { change, _ ->
                                val center = Offset(
                                    size.width / 2f,
                                    size.height / 2f
                                )

                                val angle = atan2(
                                    change.position.y - center.y,
                                    change.position.x - center.x
                                )

                                hue = (
                                        Math.toDegrees(angle.toDouble())
                                            .toFloat() + 360f
                                        ) % 360f

                                change.consume()
                            }
                        }
                ) {
                    val radius = size.minDimension / 2f

                    drawCircle(
                        brush = Brush.sweepGradient(wheelColors),
                        radius = radius
                    )

                    drawCircle(
                        color = Color(0xFF171717),
                        radius = radius * 0.67f
                    )

                    drawCircle(
                        color = selectedColor(),
                        radius = radius * 0.51f
                    )

                    drawCircle(
                        color = Color.White,
                        radius = radius * 0.51f,
                        style = Stroke(width = 2.dp.toPx())
                    )

                    val angle = Math.toRadians(
                        hue.toDouble()
                    )

                    val markerRadius = radius * 0.83f

                    val markerCenter = Offset(
                        center.x + (
                                kotlin.math.cos(angle) *
                                        markerRadius
                                ).toFloat(),
                        center.y + (
                                kotlin.math.sin(angle) *
                                        markerRadius
                                ).toFloat()
                    )

                    drawCircle(
                        color = Color.White,
                        radius = 9.dp.toPx(),
                        center = markerCenter
                    )

                    drawCircle(
                        color = Color.Black,
                        radius = 6.dp.toPx(),
                        center = markerCenter
                    )
                }

                Text(
                    text = "Saturation",
                    color = Color.White,
                    fontFamily = FontFamily.Monospace
                )

                Slider(
                    value = saturation,
                    onValueChange = { saturation = it },
                    valueRange = 0f..1f,
                    colors = SliderDefaults.colors(
                        thumbColor = selectedColor(),
                        activeTrackColor = selectedColor()
                    )
                )

                Text(
                    text = "Brightness",
                    color = Color.White,
                    fontFamily = FontFamily.Monospace
                )

                Slider(
                    value = brightness,
                    onValueChange = { brightness = it },
                    valueRange = 0f..1f,
                    colors = SliderDefaults.colors(
                        thumbColor = selectedColor(),
                        activeTrackColor = selectedColor()
                    )
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(42.dp)
                        .background(selectedColor())
                        .border(1.dp, Color.White)
                )

                Text(
                    text = String.format(
                        "#%06X",
                        selectedColor().toArgb() and 0xFFFFFF
                    ),
                    color = Color.White,
                    fontFamily = FontFamily.Monospace
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onApply(selectedColor()) }
            ) {
                Text("Apply", color = Color.White)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = Color.White)
            }
        }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SmthOS() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val preferences = remember(context) {
        context.getSharedPreferences(
            PREFS_NAME,
            Context.MODE_PRIVATE
        )
    }

    var wallpaperUri by remember {
        mutableStateOf(
            preferences.getString(PREF_WALLPAPER, null)
        )
    }

    var textColor by remember {
        mutableStateOf(
            Color(
                preferences.getInt(
                    PREF_TEXT_COLOR,
                    android.graphics.Color.WHITE
                )
            )
        )
    }

    var showColorPicker by remember {
        mutableStateOf(false)
    }

    var wallpaperImage by remember {
        mutableStateOf<
                androidx.compose.ui.graphics.ImageBitmap?
                >(null)
    }

    LaunchedEffect(wallpaperUri) {
        wallpaperImage = null

        val savedUri = wallpaperUri
        if (savedUri != null) {
            wallpaperImage = loadWallpaper(
                context,
                Uri.parse(savedUri)
            )
        }
    }

    val startupOutput = remember {
        listOf(
            "Type help for all commands and their actions",
            "",
            """
                  \         /
                   \       /
                .-----------.
               /             \
              |   o       o   |
              |               |
               \_____________/
             .-----------------.
          .--|                 |--.
          |  |                 |  |
          |  |                 |  |
          |  |                 |  |
          '--|                 |--'
             '-----------------'
                |   |   |   |
                |   |   |   |
                '---'   '---'
            """.trimIndent()
        )
    }

    var output by remember {
        mutableStateOf(startupOutput)
    }

    fun printLine(message: String) {
        output = output + message
    }

    val wallpaperPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )

                wallpaperUri = uri.toString()

                preferences.edit()
                    .putString(PREF_WALLPAPER, uri.toString())
                    .apply()

                printLine("Wallpaper updated.")
            } catch (_: Exception) {
                printLine("Unable to save wallpaper permission.")
            }
        }
    }

    var apps by remember {
        mutableStateOf(installedApps(context))
    }

    val directory = remember(context, apps) {
        buildDirectory(context, apps)
    }

    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                apps = installedApps(context)
            }
        }

        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    var input by remember {
        mutableStateOf("")
    }

    var inHiddenFolder by remember {
        mutableStateOf(false)
    }

    val scrollState = rememberScrollState()

    LaunchedEffect(output) {
        scrollState.animateScrollTo(
            scrollState.maxValue
        )
    }

    fun launchApp(shortcut: AppShortcut) {
        val app = shortcut.app

        try {
            val intent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)

                setClassName(
                    app.packageName,
                    app.activityName
                )

                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            context.startActivity(intent)
            printLine("Launching ${shortcut.label}...")
        } catch (_: Exception) {
            printLine("Unable to launch ${shortcut.label}.")
        }
    }

    fun activeAliases(): Map<String, AppShortcut> =
        if (inHiddenFolder) {
            directory.hidden
        } else {
            directory.visible
        }

    fun openHiddenFolder() {
        inHiddenFolder = true

        printLine(
            "[?] Hidden Apps (${directory.hidden.size})"
        )

        val lines = directory.hidden.values
            .map { it.label }
            .distinct()
            .sorted()
            .joinToString("\n")

        printLine(
            lines.ifEmpty { "No hidden apps detected." }
        )
    }

    fun runCommand() {
        val command = input.trim()
        if (command.isEmpty()) return

        input = ""
        val lower = command.lowercase()

        if (
            lower == "back" ||
            lower == "/" ||
            lower == "clear"
        ) {
            if (lower != "clear") {
                inHiddenFolder = false
            }

            output = startupOutput
            return
        }

        val location = if (inHiddenFolder) "?" else "home"
        printLine("$location > $command")

        when {
            lower == "help" -> {
                printLine(
                    """
                    QUICK LAUNCH
                    ab       - Launch app with 2+ letters
                    aliases  - Show app shortcuts
                    apps     - List detected apps
                    refresh  - Rescan installed apps

                    CUSTOMISATION
                    setwall  - Choose wallpaper
                    resetwall - Remove wallpaper
                    textcol  - Choose text colour
                    resetcol - Reset text colour

                    HIDDEN FOLDER
                    ?        - Open hidden apps
                    back     - Return home and clear

                    SYSTEM
                    notifications - Notification help
                    control       - Android Settings
                    clear         - Clear terminal
                    help          - Command list
                    """.trimIndent()
                )
            }

            lower == "?" -> {
                openHiddenFolder()
            }

            lower == "aliases" -> {
                val lines = activeAliases()
                    .toSortedMap()
                    .entries
                    .joinToString("\n") { (alias, shortcut) ->
                        "$alias  ${shortcut.label}"
                    }

                printLine(
                    lines.ifEmpty { "No apps detected." }
                )
            }

            lower == "apps" -> {
                val lines = activeAliases()
                    .values
                    .map { it.label }
                    .distinct()
                    .sorted()
                    .joinToString("\n")

                printLine(
                    lines.ifEmpty { "No apps detected." }
                )
            }

            lower == "refresh" -> {
                apps = installedApps(context)

                val refreshed = buildDirectory(context, apps)

                printLine(
                    "App detection refreshed: " +
                            "${refreshed.visible.size} visible, " +
                            "${refreshed.hidden.size} hidden."
                )
            }

            lower == "setwall" -> {
                wallpaperPicker.launch(arrayOf("image/*"))
            }

            lower == "resetwall" -> {
                wallpaperUri = null

                preferences.edit()
                    .remove(PREF_WALLPAPER)
                    .apply()

                printLine("Wallpaper reset to black.")
            }

            lower == "textcol" -> {
                showColorPicker = true
            }

            lower == "resetcol" -> {
                textColor = DefaultTextColor

                preferences.edit()
                    .putInt(
                        PREF_TEXT_COLOR,
                        android.graphics.Color.WHITE
                    )
                    .apply()

                printLine("Text colour reset to white.")
            }

            lower == "notifications" -> {
                printLine(
                    "Swipe down from the top to view notifications."
                )

                printLine(
                    "Direct panel controls are coming later."
                )
            }

            lower == "control" -> {
                try {
                    context.startActivity(
                        Intent(Settings.ACTION_SETTINGS)
                    )

                    printLine("Opening Android Settings...")
                } catch (_: Exception) {
                    printLine("Unable to open Settings.")
                }
            }

            lower.startsWith("open ") -> {
                val requested = command
                    .substring(5)
                    .trim()

                val shortcut = resolveShortcut(
                    requested,
                    activeAliases()
                )

                if (shortcut != null) {
                    launchApp(shortcut)
                } else {
                    printLine("App not found: $requested")
                }
            }

            else -> {
                val shortcut = resolveShortcut(
                    command,
                    activeAliases()
                )

                if (shortcut != null) {
                    launchApp(shortcut)
                } else {
                    printLine("Unknown command: $command")
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(TerminalBlack)
    ) {
        if (wallpaperImage != null) {
            Image(
                bitmap = wallpaperImage!!,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Color.Black.copy(alpha = 0.30f)
                    )
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .imePadding()
                .padding(20.dp)
        ) {
            Text(
                text = "smthOS ZFold8UPort",
                color = textColor,
                style = MaterialTheme.typography.headlineMedium,
                fontFamily = FontFamily.Monospace
            )

            Text(
                text = "Terminal Launcher · $SMTHOS_VERSION | Developed by probsdns",
                color = textColor,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace
            )

            HorizontalDivider(
                modifier = Modifier.padding(
                    vertical = 16.dp
                ),
                color = textColor
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(scrollState),
                verticalArrangement =
                    Arrangement.spacedBy(8.dp)
            ) {
                output.forEach { line ->
                    Text(
                        text = line,
                        color = textColor,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            Spacer(
                modifier = Modifier.height(12.dp)
            )

            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,

                prefix = {
                    Text(
                        if (inHiddenFolder) "?>" else ">",
                        color = textColor
                    )
                },

                placeholder = {
                    Text(
                        "Enter command...",
                        color = textColor.copy(alpha = 0.8f)
                    )
                },

                keyboardOptions = KeyboardOptions(
                    imeAction = ImeAction.Go
                ),

                keyboardActions = KeyboardActions(
                    onGo = { runCommand() }
                ),

                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = textColor,
                    unfocusedTextColor = textColor,
                    focusedBorderColor = textColor,
                    unfocusedBorderColor = textColor,
                    cursorColor = textColor,
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedPrefixColor = textColor,
                    unfocusedPrefixColor = textColor
                )
            )
        }

        if (!WindowInsets.isImeVisible) {
            Box(
                modifier = Modifier.align(
                    Alignment.BottomCenter
                )
            ) {
                BinaryNavigationDecoration(textColor)
            }
        }
    }

    if (showColorPicker) {
        TextColorPicker(
            initialColor = textColor,
            onApply = { selected ->
                textColor = selected

                preferences.edit()
                    .putInt(
                        PREF_TEXT_COLOR,
                        selected.toArgb()
                    )
                    .apply()

                showColorPicker = false
                printLine("Text colour updated.")
            },
            onDismiss = {
                showColorPicker = false
            }
        )
    }
}
