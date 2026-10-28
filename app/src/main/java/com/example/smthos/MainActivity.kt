
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

private val TerminalWhite = Color.White
private val TerminalBlack = Color.Black

private val folders = linkedMapOf(
    "a" to listOf(
        "Among Us",
        "Geometry Dash",
        "Offline Games",
        "Paper.io 2",
        "Roblox",
        "Sonolus",
        "Spotify"
    ),
    "b" to listOf(
        "Amazon Shopping",
        "BuildCores",
        "Calculator",
        "Chrome",
        "Compass",
        "eBay",
        "Kit",
        "Play Store",
        "Segway Mobility",
        "Store",
        "Temu",
        "Termux",
        "Wallet"
    ),
    "c" to listOf(
        "Calendar",
        "Camera",
        "Clock",
        "Contacts",
        "Gallery",
        "Gmail",
        "Maps",
        "My Files",
        "Notes",
        "Phone",
        "Samsung Pass",
        "Settings",
        "Weather"
    ),
    "?" to listOf(
        "ChatGPT",
        "Discord",
        "Google Chat",
        "Messages",
        "TikTok",
        "YouTube"
    )
)

private data class PhoneApp(
    val name: String,
    val packageName: String,
    val activityName: String
)

private fun normalize(value: String): String =
    value.lowercase().filter { it.isLetterOrDigit() }

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
        it.packageName to it.activityName
    }.sortedBy {
        it.name.lowercase()
    }
}

class MainActivity : ComponentActivity() {

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)

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

        val binaryText = remember(binaryTick, digitCount) {
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

    var input by remember {
        mutableStateOf("")
    }

    var currentFolder by remember {
        mutableStateOf<String?>(null)
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
        scrollState.animateScrollTo(scrollState.maxValue)
    }

    fun print(message: String) {
        output = output + message
    }

    fun showFolder(folder: String) {
        currentFolder = folder

        val folderApps = folders[folder].orEmpty()

        val entries = folderApps.mapIndexed { index, app ->
            val shortcut = ('a'.code + index).toChar()
            "$shortcut  $app"
        }

        print("[$folder] (${folderApps.size} apps)")
        print(entries.joinToString("\n"))
    }

    fun launchApp(requestedName: String) {
        val folder = currentFolder

        if (folder == null) {
            print("nah :)")
            return
        }

        val folderApps = folders[folder].orEmpty()
        val requested = requestedName.trim()

        if (requested.isEmpty()) {
            print("Enter an app shortcut.")
            return
        }

        val index = if (
            requested.length == 1 &&
            requested[0].lowercaseChar() in 'a'..'z'
        ) {
            requested[0].lowercaseChar() - 'a'
        } else {
            -1
        }

        val allowedApp = if (index in folderApps.indices) {
            folderApps[index]
        } else {
            folderApps.firstOrNull {
                normalize(it) == normalize(requested)
            }
        }

        if (allowedApp == null) {
            print("App not found in $folder: $requested")
            return
        }

        val packageAliases = mapOf(
            "ChatGPT" to "com.openai.chatgpt",
            "Discord" to "com.discord",
            "Google Chat" to "com.google.android.apps.dynamite",
            "Messages" to "com.google.android.apps.messaging",
            "TikTok" to "com.zhiliaoapp.musically",
            "YouTube" to "com.google.android.youtube",
            "Chrome" to "com.android.chrome",
            "Play Store" to "com.android.vending"
        )

        val nameAliases = mapOf(
            "Google Chat" to "Chat",
            "Store" to "Galaxy Store",
            "Play Store" to "Google Play Store",
            "Notes" to "Samsung Notes"
        )

        val packageName = packageAliases[allowedApp]

        val packageMatch = if (packageName != null) {
            apps.firstOrNull {
                it.packageName == packageName
            }
        } else {
            null
        }

        val targetName =
            nameAliases[allowedApp] ?: allowedApp

        val target = normalize(targetName)

        val nameMatch = apps.firstOrNull {
            normalize(it.name) == target
        } ?: apps.firstOrNull {
            normalize(it.name).contains(target)
        }

        val match = packageMatch ?: nameMatch

        try {
            val launchIntent = if (match != null) {
                Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)

                    setClassName(
                        match.packageName,
                        match.activityName
                    )

                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            } else if (packageName != null) {
                context.packageManager
                    .getLaunchIntentForPackage(packageName)
            } else {
                null
            }

            if (launchIntent != null) {
                context.startActivity(launchIntent)
                print("Launching $allowedApp...")
            } else {
                print("App not found: $allowedApp")
            }
        } catch (_: Exception) {
            print("Unable to launch $allowedApp.")
        }
    }

    fun runCommand() {
        val command = input.trim()

        if (command.isEmpty()) return

        input = ""

        if (command.equals("clear", ignoreCase = true)) {
            output = startupOutput
            return
        }

        val location = currentFolder ?: "home"
        print("$location > $command")

        val lower = command.lowercase()

        when {
            lower == "help" -> {
                print(
                    """
                    FOLDERS
                    a  - Games & Media
                    b  - Tools & Shopping
                    c  - System & Utilities

                    NAVIGATION
                    a-z          - Launch app by shortcut
                    back         - Return to home
                    /            - Return to home

                    SYSTEM
                    apps          - All detected apps
                    notifications - Notification help
                    control       - Android Settings
                    clear         - Clear terminal
                    help          - Command list
                    """.trimIndent()
                )
            }

            lower == "back" || lower == "/" -> {
                if (currentFolder == null) {
                    print("Already at home.")
                } else {
                    currentFolder = null
                    print("Returned to home.")
                }
            }

            folders.containsKey(lower) -> {
                if (
                    currentFolder != null &&
                    lower.length == 1 &&
                    lower != "?"
                ) {
                    launchApp(lower)
                } else {
                    showFolder(lower)
                }
            }

            lower == "apps" -> {
                if (apps.isEmpty()) {
                    print("No apps detected.")
                } else {
                    print(
                        apps.joinToString("\n") {
                            it.name
                        }
                    )
                }
            }

            lower == "notifications" -> {
                print("Swipe down from the top to view notifications.")
                print("Direct panel controls are coming later.")
            }

            lower == "control" -> {
                try {
                    context.startActivity(
                        Intent(Settings.ACTION_SETTINGS)
                    )
                    print("Opening Android Settings...")
                } catch (_: Exception) {
                    print("Unable to open Settings.")
                }
            }

            lower.startsWith("open ") -> {
                launchApp(command.substring(5))
            }

            else -> {
                launchApp(command)
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
                text = "Terminal Launcher · v1.0 | Developed by Ranveer | probsdns",
                color = TerminalWhite,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace
            )

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 16.dp),
                color = TerminalWhite
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(8.dp)
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
                        if (currentFolder == null) ">"
                        else "$currentFolder>",
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
                    onGo = { runCommand() }
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
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                BinaryNavigationDecoration()
            }
        }
    }
}
