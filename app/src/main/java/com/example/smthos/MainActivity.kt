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

private const val SMTHOS_VERSION = "v1.4"

private val TerminalWhite = Color.White
private val TerminalBlack = Color.Black

// Hidden apps only work inside the ? folder.
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

// -------------------------------------------------
// v1.4 DYNAMIC PREFIX SYSTEM
// -------------------------------------------------
//
// Minimum: 2 letters.
//
// Examples:
// Spotify -> sp
// Steam   -> st
//
// Chat    -> ch
// ChatGPT -> chatg
//
// Shorter names get priority when names overlap.
//
// Longer valid prefixes also work:
// ch, cha, chat -> Chat
// chatg, chatgp, chatgpt -> ChatGPT
//
// Visible and hidden apps use separate directories.
// -------------------------------------------------

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

        // Find the shortest prefix that does not belong
        // to an earlier, shorter app name.
        for (length in minimum..name.length) {
            val candidate = name.take(length)

            val overlapsEarlierApp = result.values.any {
                val earlierName = normalize(it.label)

                earlierName.startsWith(candidate) ||
                        candidate.startsWith(earlierName)
            }

            if (
                !overlapsEarlierApp &&
                candidate !in result
            ) {
                chosen = candidate
                break
            }
        }

        // If two apps have identical names, create
        // a fallback using their package names.
        if (chosen == null) {
            val packagePart = normalize(
                shortcut.app.packageName
                    .substringAfterLast(".")
            )

            val activityPart = normalize(
                shortcut.app.activityName
                    .substringAfterLast(".")
            )

            val base = name + packagePart

            val fallbackCandidates = listOf(
                base,
                base + activityPart,
                name + normalize(shortcut.app.packageName)
            )

            chosen = fallbackCandidates.firstOrNull {
                it.isNotEmpty() && it !in result
            }

            // Guarantee a unique command even when
            // multiple launcher activities share a name.
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

// Resolve commands against all app names, not just
// the exact shortest alias.
//
// This lets someone type a longer prefix if they want.
//
// When two names share a prefix, the shorter app
// wins until the input distinguishes the longer app.

private fun resolveShortcut(
    requested: String,
    aliases: Map<String, AppShortcut>
): AppShortcut? {
    val query = normalize(requested)

    if (query.isEmpty()) return null

    // First check exact assigned aliases.
    aliases[query]?.let { return it }

    val shortcuts = aliases.values.toList()

    // Full app names always work.
    val exactMatches = shortcuts.filter {
        normalize(it.label) == query
    }

    if (exactMatches.size == 1) {
        return exactMatches.first()
    }

    // One-letter shortcuts are not allowed.
    if (query.length < 2) return null

    val matching = shortcuts.filter {
        normalize(it.label).startsWith(query)
    }

    if (matching.isEmpty()) return null

    // If the query matches multiple names, choose
    // the shortest matching app name.
    //
    // Example:
    // "chat" matches Chat and ChatGPT.
    // Chat wins because its name is shorter.
    //
    // "chatg" matches only ChatGPT.
    return matching.sortedWith(
        compareBy<AppShortcut>(
            { normalize(it.label).length },
            { normalize(it.label) },
            { appKey(it.app) }
        )
    ).first()
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

    val hiddenKeys = hiddenResolved
        .map { appKey(it.app) }
        .toSet()

    val visibleApps = apps
        .filter {
            appKey(it) !in hiddenKeys
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

    fun activeAliases(): Map<String, AppShortcut> {
        return if (inHiddenFolder) {
            directory.hidden
        } else {
            directory.visible
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
                    ab       - Launch app with 2+ letters
                    aliases  - Show shortest app shortcuts
                    apps     - List apps

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
                val lines = activeAliases()
                    .values
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

                val shortcut = resolveShortcut(
                    requested,
                    activeAliases()
                )

                if (shortcut != null) {
                    launchApp(shortcut)
                } else {
                    printLine(
                        "App not found: $requested"
                    )
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
