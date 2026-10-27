
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
    "fun" to listOf(
        "Among Us",
        "Geometry Dash",
        "Roblox",
        "Spotify",
        "Offline Games",
        "Sonolus",
        "Paper.io 2",
        "Phone",
        "Gallery"
    ),

    "???" to listOf(
        "Chat",
        "Discord",
        "YouTube",
        "Messages",
        "Chrome",
        "Contacts"
    ),

    "tools" to listOf(
        "BuildCores",
        "Termux",
        "Settings",
        "My Files",
        "Notes",
        "Screen time",
        "Clock",
        "Calendar",
        "Gmail",
        "Maps",
        "Weather",
        "eBay",
        "Amazon Shopping",
        "Camera",
        "Samsung Pass",
        "Store",
        "Play Store",
        "Compass",
        "Calculator",
        "Segway Mobility",
        "Temu",
        "Wallet",
        "Kit"
    )
)

private data class PhoneApp(
    val name: String,
    val packageName: String,
    val activityName: String
)

private fun normalize(value: String): String {
    return value.lowercase().filter {
        it.isLetterOrDigit()
    }
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


    var output by remember {
        mutableStateOf(
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
        )
    }

    val scrollState = rememberScrollState(
    )

    LaunchedEffect(output) {
        scrollState.animateScrollTo(
            scrollState.maxValue
        )
    }

    fun print(message: String) {
        output = output + message
    }

    fun launchApp(requestedName: String) {

        val name = requestedName.trim()

        if (name.isEmpty()) {
            print("Enter an app name.")
            return
        }

        val folder = currentFolder

        if (folder == null) {
            print("nah :)")
            return
        }

        val folderApps = folders[folder].orEmpty()

        val requested = normalize(name)

        val allowedApp = folderApps.firstOrNull {
            normalize(it) == requested
        }

        if (allowedApp == null) {
            print("App not found in $folder: $name")
            print("Type 'back' to return to home.")
            return
        }

        if (normalize(allowedApp) == "playstore") {
            try {
                val playStorePackage = "com.android.vending"

                val installedPlayStore = apps.firstOrNull {
                    it.packageName == playStorePackage
                }

                val playStoreIntent =
                    if (installedPlayStore != null) {
                        Intent(Intent.ACTION_MAIN).apply {
                            addCategory(Intent.CATEGORY_LAUNCHER)
                            setClassName(
                                installedPlayStore.packageName,
                                installedPlayStore.activityName
                            )
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                    } else {
                        context.packageManager.getLaunchIntentForPackage(
                            playStorePackage
                        )
                    }

                if (playStoreIntent != null) {
                    context.startActivity(playStoreIntent)
                    print("Launching Play Store...")
                } else {
                    print("Unable to find Play Store.")
                }

            } catch (_: Exception) {
                print("Unable to launch Play Store.")
            }

            return
        }

        val aliases = mapOf(
            "chat" to "chat",
            "store" to "galaxy store",
            "play store" to "google play store",
            "screen time" to "screen time",
            "notes" to "samsung notes",
            "my files" to "my files"
        )

        val target = normalize(
            aliases[allowedApp.lowercase()] ?: allowedApp
        )

        val match = apps.firstOrNull {
            normalize(it.name) == target
        } ?: apps.firstOrNull {
            normalize(it.name).contains(target)
        }

        if (match == null) {
            print("App not found: $name")
            print("Type 'apps' to see detected apps.")
            return
        }

        try {
            val launchIntent = Intent(
                Intent.ACTION_MAIN
            ).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)

                setClassName(
                    match.packageName,
                    match.activityName
                )

                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            context.startActivity(launchIntent)

            print("Launching ${match.name}...")

        } catch (_: Exception) {
            print("Unable to launch $name.")
        }
    }

    fun runCommand() {

        val command = input.trim()

        if (command.isEmpty()) return

        input = ""

        if (command.equals("clear", ignoreCase = true)) {
            output = emptyList()
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
                    tools  - Tools folder
                    ???    - Socials folder
                    fun    - Fun folder
                    back   - Return to home

                    APPS
                    apps         - All detected apps
                    open [name]  - Open app in current folder
                    [app name]   - Open app in current folder

                    SYSTEM
                    notifications - Notification help
                    control       - Android Settings
                    clear         - Clear terminal
                    help          - Command list
                    """.trimIndent()
                )
            }

            lower == "back" || lower == "home" -> {
                if (currentFolder == null) {
                    print("Already at home.")
                } else {
                    currentFolder = null
                    print("Returned to home.")
                }
            }

            folders.containsKey(lower) -> {

                currentFolder = lower

                val folderApps = folders[lower].orEmpty()

                print(
                    "${lower.uppercase()} (${folderApps.size} apps)"
                )

                print(
                    folderApps.joinToString("\n")
                )
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
                launchApp(
                    command.substring(5)
                )
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
                text = "Terminal Launcher · v0.1 | Developed by Ranveer | Probsdns",
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
                        if (currentFolder == null) ">" else "$currentFolder>",
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
