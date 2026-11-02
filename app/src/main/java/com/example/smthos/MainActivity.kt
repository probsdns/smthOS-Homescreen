
package com.example.smthos

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import kotlinx.coroutines.delay
import kotlin.random.Random

// Only the clean version number appears in the app.
// GitHub branch names do not affect this.
private const val SMTHOS_VERSION = "v1.3"

private val TerminalWhite = Color.White
private val TerminalBlack = Color.Black

// These apps are hidden from normal listings and aliases.
// They can only be launched from inside the ? folder.
private val hiddenApps = listOf(
    "ChatGPT",
    "Discord",
    "Google Chat",
    "Messages",
    "TikTok",
    "YouTube"
)

// Helps resolve apps whose actual installed labels differ.
private val knownPackages = mapOf(
    "ChatGPT" to "com.openai.chatgpt",
    "Discord" to "com.discord",
    "Google Chat" to "com.google.android.apps.dynamite",
    "Messages" to "com.google.android.apps.messaging",
    "TikTok" to "com.zhiliaoapp.musically",
    "YouTube" to "com.google.android.youtube",
    "Chrome" to "com.android.chrome",
    "Play Store" to "com.android.vending"
)

private val preferredNames = mapOf(
    "Google Chat" to "Chat",
    "Google Maps" to "Maps",
    "Google Drive" to "Drive",
    "Google Play Store" to "Play Store",
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
    preferredNames[app.name] ?: app.name

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

private fun aliasCandidates(name: String): List<String> {
    val words = name.lowercase()
        .split(Regex("[^a-z0-9]+"))
        .filter { it.isNotEmpty() }

    val combined = words.joinToString("")
    val candidates = mutableListOf<String>()

    for (word in words) {
        if (word.length >= 3) {
            candidates.add(word.take(3))
        }
    }

    if (combined.length >= 3) {
        for (i in 0..combined.length - 3) {
            candidates.add(
                combined.substring(i, i + 3)
            )
        }
    }

    return candidates.distinct()
}

private fun buildDirectory(
    context: Context,
    apps: List<PhoneApp>
): AppDirectory {
    val prefs = context.getSharedPreferences(
        "smthos_quick_launch",
        Context.MODE_PRIVATE
    )

    // Find hidden apps first so they never enter
    // the normal app directory.
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

    val hiddenKeys = hiddenResolved
        .map { appKey(it.app) }
        .toSet()

    val visibleApps = apps
        .filter { appKey(it) !in hiddenKeys }
        .filter {
            it.packageName != context.packageName
        }
        .map {
            AppShortcut(
                label = displayName(it),
                app = it
            )
        }

    val visibleAliases = linkedMapOf<String, AppShortcut>()
    val hiddenAliases = linkedMapOf<String, AppShortcut>()

    val usedVisible = mutableSetOf<String>()
    val usedHidden = mutableSetOf<String>()

    val editor = prefs.edit()

    fun assignAliases(
        shortcuts: List<AppShortcut>,
        result: MutableMap<String, AppShortcut>,
        used: MutableSet<String>,
        preferencePrefix: String
    ) {
        val ordered = shortcuts.sortedWith(
            compareBy<AppShortcut>(
                { it.label.lowercase() },
                { appKey(it.app) }
            )
        )

        // Restore saved aliases first.
        for (shortcut in ordered) {
            val preferenceKey =
                preferencePrefix + appKey(shortcut.app)

            val saved = prefs.getString(
                preferenceKey,
                null
            )

            if (
                saved != null &&
                saved.matches(Regex("[a-z0-9]{3}")) &&
                used.add(saved)
            ) {
                result[saved] = shortcut
            }
        }

        // Assign aliases to new apps.
        for (shortcut in ordered) {
            if (result.values.any {
                    appKey(it.app) == appKey(shortcut.app)
                }) {
                continue
            }

            val options =
                aliasCandidates(shortcut.label) +
                        aliasCandidates(shortcut.app.name)

            var chosen = options.firstOrNull {
                it !in used
            }

            // Fallback for unusual or duplicate names.
            if (chosen == null) {
                outer@ for (a in 'a'..'z') {
                    for (b in 'a'..'z') {
                        for (c in 'a'..'z') {
                            val candidate = "$a$b$c"

                            if (candidate !in used) {
                                chosen = candidate
                                break@outer
                            }
                        }
                    }
                }
            }

            if (chosen != null) {
                used.add(chosen)
                result[chosen] = shortcut

                editor.putString(
                    preferencePrefix + appKey(shortcut.app),
                    chosen
                )
            }
        }
    }

    // Separate namespaces let hidden apps have their
    // own three-letter commands inside ?.
    assignAliases(
        visibleApps,
        visibleAliases,
        usedVisible,
        "visible:"
    )

    assignAliases(
        hiddenResolved,
        hiddenAliases,
        usedHidden,
        "hidden:"
    )

    editor.apply()

    return AppDirectory(
        visible = visibleAliases,
        hidden = hiddenAliases
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
                    onBackground = TerminalWhite,
                    onSurface = TerminalWhite,
                    primary = TerminalWhite,
                    onPrimary = TerminalBlack
                )
            ) {
                SmthOS()
            }
        }
    }
}

@Composable
private fun BinaryNavigationDecoration() {
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
            .height(navigationHeight)
            .background(TerminalBlack),
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
                .offset(y = 4.dp),
            color = TerminalWhite,
            fontSize = 20.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            softWrap = false
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SmthOS() {
    val context = LocalContext.current

    val apps = remember(context) {
        installedApps(context)
    }

    val directory = remember(context, apps) {
        buildDirectory(context, apps)
    }

    var input by remember {
        mutableStateOf("")
    }

    // The only folder is the hidden folder.
    var inHiddenFolder by remember {
        mutableStateOf(false)
    }

    val startupOutput = remember {
        listOf(
            "Work in progress some features might break so ye",
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

    val scrollState = rememberScrollState()

    LaunchedEffect(output) {
        scrollState.animateScrollTo(
            scrollState.maxValue
        )
    }

    fun printLine(message: String) {
        output = output + message
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

            printLine(
                "Launching ${shortcut.label}..."
            )
        } catch (_: Exception) {
            printLine(
                "Unable to launch ${shortcut.label}."
            )
        }
    }

    fun openHiddenFolder() {
        inHiddenFolder = true

        printLine(
            "[?] Hidden Apps (${directory.hidden.size})"
        )

        if (directory.hidden.isEmpty()) {
            printLine("No hidden apps detected.")
        } else {
            printLine(
                directory.hidden.values
                    .map { it.label }
                    .distinct()
                    .sorted()
                    .joinToString("\n")
            )
        }
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

        val location = if (inHiddenFolder) {
            "?"
        } else {
            "home"
        }

        printLine("$location > $command")

        when {
            lower == "help" -> {
                printLine(
                    """
                    QUICK LAUNCH
                    abc      - Launch app using 3 letters
                    aliases  - Show normal app shortcuts
                    apps     - List normal apps

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
                val activeAliases =
                    if (inHiddenFolder) {
                        directory.hidden
                    } else {
                        directory.visible
                    }

                val lines = activeAliases
                    .toSortedMap()
                    .entries
                    .joinToString("\n") {
                            (alias, shortcut) ->
                        "$alias  ${shortcut.label}"
                    }

                printLine(
                    lines.ifEmpty {
                        "No apps detected."
                    }
                )
            }

            lower == "apps" -> {
                val activeAliases =
                    if (inHiddenFolder) {
                        directory.hidden
                    } else {
                        directory.visible
                    }

                val lines = activeAliases.values
                    .map { it.label }
                    .distinct()
                    .sorted()
                    .joinToString("\n")

                printLine(
                    lines.ifEmpty {
                        "No apps detected."
                    }
                )
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

                    printLine(
                        "Opening Android Settings..."
                    )
                } catch (_: Exception) {
                    printLine(
                        "Unable to open Settings."
                    )
                }
            }

            lower.startsWith("open ") -> {
                val requested = command
                    .substring(5)
                    .trim()

                val activeAliases =
                    if (inHiddenFolder) {
                        directory.hidden
                    } else {
                        directory.visible
                    }

                val shortcut =
                    activeAliases[requested.lowercase()]
                        ?: activeAliases.values.firstOrNull {
                            normalize(it.label) ==
                                    normalize(requested)
                        }

                if (shortcut != null) {
                    launchApp(shortcut)
                } else {
                    printLine(
                        "App not found: $requested"
                    )
                }
            }

            else -> {
                val activeAliases =
                    if (inHiddenFolder) {
                        directory.hidden
                    } else {
                        directory.visible
                    }

                val shortcut =
                    activeAliases[lower]
                        ?: activeAliases.values.firstOrNull {
                            normalize(it.label) ==
                                    normalize(command)
                        }

                if (shortcut != null) {
                    launchApp(shortcut)
                } else {
                    printLine(
                        "Unknown command: $command"
                    )
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(TerminalBlack)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .imePadding()
                .padding(20.dp)
        ) {
            Text(
                text = "smthOS",
                color = TerminalWhite,
                style = MaterialTheme.typography.headlineMedium,
                fontFamily = FontFamily.Monospace
            )

            Text(
                text = "Terminal Launcher · $SMTHOS_VERSION | Developed by Ranveer | probsdns",
                color = TerminalWhite,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace
            )

            HorizontalDivider(
                modifier = Modifier.padding(
                    vertical = 16.dp
                ),
                color = TerminalWhite
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
                        color = TerminalWhite,
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
                        if (inHiddenFolder) "?>"
                        else ">",
                        color = TerminalWhite
                    )
                },

                placeholder = {
                    Text(
                        "Enter command...",
                        color = TerminalWhite
                    )
                },

                keyboardOptions = KeyboardOptions(
                    imeAction = ImeAction.Go
                ),

                keyboardActions = KeyboardActions(
                    onGo = {
                        runCommand()
                    }
                ),

                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = TerminalWhite,
                    unfocusedTextColor = TerminalWhite,
                    focusedBorderColor = TerminalWhite,
                    unfocusedBorderColor = TerminalWhite,
                    cursorColor = TerminalWhite,
                    focusedContainerColor = TerminalBlack,
                    unfocusedContainerColor = TerminalBlack
                )
            )
        }

        if (!WindowInsets.isImeVisible) {
            Box(
                modifier = Modifier.align(
                    Alignment.BottomCenter
                )
            ) {
                BinaryNavigationDecoration()
            }
        }
    }
}
