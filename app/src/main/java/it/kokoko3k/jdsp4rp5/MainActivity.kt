package it.kokoko3k.jdsp4rp5

import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import it.kokoko3k.jdsp4rp5.ui.theme.jdsp4rp5Theme

class MainActivity : ComponentActivity() {
    private val runtimeExpectedActiveKey = "jdspRuntimeExpectedActive"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            jdsp4rp5Theme {
                val context = LocalContext.current
                val sharedPrefs = getSharedPreferences(JdspUtils.PREFS_NAME, Context.MODE_PRIVATE)
                var bootEnabled by remember {
                    mutableStateOf(sharedPrefs.getBoolean(JdspUtils.JDSP_BOOT_ENABLED_KEY, false))
                }
                var mediaOnly by remember {
                    mutableStateOf(sharedPrefs.getBoolean(JdspUtils.JDSP_MEDIA_ONLY_KEY, false))
                }
                var expectedActive by remember {
                    mutableStateOf(sharedPrefs.getBoolean(runtimeExpectedActiveKey, false))
                }
                fun applyProbeState(probeState: JdspUtils.RuntimeState): JdspUtils.RuntimeState {
                    if (probeState == JdspUtils.RuntimeState.INACTIVE && expectedActive) {
                        expectedActive = false
                        sharedPrefs.edit().putBoolean(runtimeExpectedActiveKey, false).apply()
                    }
                    return if (expectedActive && probeState == JdspUtils.RuntimeState.ACTIVE_OR_UNKNOWN) {
                        JdspUtils.RuntimeState.ACTIVE_OR_UNKNOWN
                    } else {
                        probeState
                    }
                }
                var runtimeState by remember {
                    mutableStateOf(applyProbeState(JdspUtils.probeRuntimeState(context)))
                }
                val lifecycleOwner = LocalLifecycleOwner.current

                DisposableEffect(lifecycleOwner, context) {
                    val observer = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_RESUME) {
                            runtimeState = applyProbeState(JdspUtils.probeRuntimeState(context))
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose {
                        lifecycleOwner.lifecycle.removeObserver(observer)
                    }
                }

                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    MainScreen(
                        modifier = Modifier.padding(innerPadding),
                        bootEnabled = bootEnabled,
                        mediaOnly = mediaOnly,
                        runtimeState = runtimeState,
                        context = context,
                        onBootToggle = { newValue ->
                            bootEnabled = newValue
                            with(sharedPrefs.edit()) {
                                putBoolean(JdspUtils.JDSP_BOOT_ENABLED_KEY, newValue)
                                apply()
                            }
                        },
                        onMediaOnlyToggle = { newValue ->
                            mediaOnly = newValue
                            with(sharedPrefs.edit()) {
                                putBoolean(JdspUtils.JDSP_MEDIA_ONLY_KEY, newValue)
                                apply()
                            }
                        },
                        onEnable = {
                            val result = JdspUtils.enableJdsp(context, mediaOnly)
                            if (result.success) {
                                expectedActive = true
                                sharedPrefs.edit().putBoolean(runtimeExpectedActiveKey, true).apply()
                            }
                            runtimeState = applyProbeState(result.runtimeState)
                            val msg = if (result.success) {
                                "Enabling JamesDsp..."
                            } else {
                                "Enable failed. Check lastlog.txt"
                            }
                            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                        },
                        onDisable = {
                            val result = JdspUtils.disableJdsp(context)
                            if (result.success) {
                                expectedActive = false
                                sharedPrefs.edit().putBoolean(runtimeExpectedActiveKey, false).apply()
                            }
                            runtimeState = applyProbeState(result.runtimeState)
                            val msg = if (result.success) {
                                "Disabling JamesDsp..."
                            } else {
                                "Disable failed. Check lastlog.txt"
                            }
                            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun MainScreen(
    modifier: Modifier = Modifier,
    bootEnabled: Boolean,
    mediaOnly: Boolean,
    runtimeState: JdspUtils.RuntimeState,
    context: Context,
    onBootToggle: (Boolean) -> Unit,
    onMediaOnlyToggle: (Boolean) -> Unit,
    onEnable: () -> Unit,
    onDisable: () -> Unit
) {
    val mediaToggleEnabled = runtimeState == JdspUtils.RuntimeState.INACTIVE

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Button(
            onClick = {
                Toast.makeText(context, "Install JamesDsp...", Toast.LENGTH_SHORT).show()
                JdspUtils.installJdsp(context)
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Install bundled JamesDSPManagerThePBone v1.6.8")
        }
        Spacer(modifier = Modifier.height(16.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Media-only mode (YouTube/Netflix/music)")
            Spacer(Modifier.width(8.dp))
            Switch(
                checked = mediaOnly,
                onCheckedChange = onMediaOnlyToggle,
                enabled = mediaToggleEnabled
            )
        }
        if (!mediaToggleEnabled) {
            Text(
                text = "Disable JDSP to change this option.",
                style = MaterialTheme.typography.bodySmall
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = onEnable,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Enable JDSP")
        }
        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = onDisable,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Disable JDSP")
        }

        Spacer(modifier = Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Enable JamesDSP at boot?")
            Spacer(Modifier.width(8.dp))
            Switch(
                checked = bootEnabled,
                onCheckedChange = onBootToggle
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
fun DefaultPreview() {
    val context = LocalContext.current
    jdsp4rp5Theme {
        MainScreen(
            bootEnabled = false,
            mediaOnly = false,
            runtimeState = JdspUtils.RuntimeState.INACTIVE,
            context = context,
            onBootToggle = { _ -> },
            onMediaOnlyToggle = { _ -> },
            onEnable = {},
            onDisable = {}
        )
    }
}
