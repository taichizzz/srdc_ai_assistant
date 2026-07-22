package com.foxconn.seeandsay.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.foxconn.seeandsay.bridge.AccessibilityBridge
import com.foxconn.seeandsay.bridge.SeeAndSayService
import com.foxconn.seeandsay.bridge.UiBridge
import com.foxconn.seeandsay.bridge.model.ScreenElement
import com.foxconn.seeandsay.decision.Decision
import com.foxconn.seeandsay.decision.TextMatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * DEBUG-only launcher activity hosting [DebugScreen] for the Week 2 bridge acceptance
 * (docs/ARCHITECTURE §10). It is a separate activity with its own launcher entry so it does not
 * touch the STT [MainActivity]; voice wiring is deliberately absent until Week 3.
 */
class BridgeDebugActivity : ComponentActivity() {

    private val bridge: UiBridge = AccessibilityBridge()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    DebugScreen(
                        bridge = bridge,
                        moveAppToBack = { moveTaskToBack(true) },
                    )
                }
            }
        }
    }
}

/** Delay after backgrounding so the underlying screen is frontmost and settled before reading. */
private const val SETTLE_DELAY_MS = 1_000L

/**
 * Executes one typed command against the screen underneath this app (the M2.2 loop without voice):
 * background the app → wait → read → match → click, reporting each outcome as user-visible text.
 *
 * @param bridge the Bridge used to read and act.
 * @param command raw typed command, e.g.「設定」.
 * @param moveAppToBack backgrounds the activity so the target screen becomes readable.
 * @param appContext application context for Toasts that must survive backgrounding.
 * @return a short zh-TW result line for on-screen state.
 */
private suspend fun runCommand(
    bridge: UiBridge,
    command: String,
    moveAppToBack: () -> Unit,
    appContext: Context,
): String {
    moveAppToBack()
    delay(SETTLE_DELAY_MS)
    val result = try {
        val snapshot = bridge.readScreen()
        when (val decision = TextMatcher().match(command, snapshot)) {
            is Decision.Click -> {
                val label = snapshot.elements.getOrNull(decision.index)?.text ?: command
                val clicked = bridge.click(decision.index)
                if (clicked) "已點擊『$label』" else "找到『$label』但點擊失敗"
            }

            is Decision.Speak -> decision.text
            is Decision.SetText -> "此指令需要輸入欄位（M2.2 不由比對器產生）"
            Decision.Back -> "此指令對應返回（請用返回鍵測試）"
            Decision.NoMatch -> "畫面上找不到可點的『$command』"
        }
    } catch (error: Exception) {
        error.message ?: "執行失敗"
    }
    Toast.makeText(appContext, result, Toast.LENGTH_LONG).show()
    return result
}

/**
 * Week 2 bridge debug UI: service status, live element list (M2.1), and typed-command execution
 * against the underlying screen (M2.2).
 *
 * @param bridge the Bridge used to read the screen and perform actions.
 * @param moveAppToBack backgrounds the hosting activity before command execution.
 */
@Composable
private fun DebugScreen(
    bridge: UiBridge,
    moveAppToBack: () -> Unit,
) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val scope = rememberCoroutineScope()

    var serviceEnabled by remember { mutableStateOf(SeeAndSayService.instance != null) }
    var elements by remember { mutableStateOf<List<ScreenElement>>(emptyList()) }
    var screenName by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var command by remember { mutableStateOf("") }
    var commandResult by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        Text("Bridge 測試（M2.1 讀 / M2.2 點）", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            if (serviceEnabled) "無障礙服務：已啟用" else "無障礙服務：未啟用",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(12.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                context.startActivity(
                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }) {
                Text("開啟無障礙設定")
            }
            OutlinedButton(onClick = {
                serviceEnabled = SeeAndSayService.instance != null
            }) {
                Text("檢查服務狀態")
            }
        }

        Spacer(Modifier.height(16.dp))
        Text("M2.2 指令執行（打字，退背景後點底下畫面）", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = command,
            onValueChange = { command = it },
            label = { Text("指令（例如：設定）") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = command.isNotBlank(),
                onClick = {
                    scope.launch {
                        serviceEnabled = SeeAndSayService.instance != null
                        commandResult = runCommand(bridge, command.trim(), moveAppToBack, appContext)
                    }
                },
            ) {
                Text("執行指令")
            }
            OutlinedButton(onClick = {
                scope.launch {
                    moveAppToBack()
                    delay(SETTLE_DELAY_MS)
                    val ok = bridge.back()
                    val text = if (ok) "已送出返回鍵" else "返回鍵失敗（服務未啟用？）"
                    commandResult = text
                    Toast.makeText(appContext, text, Toast.LENGTH_LONG).show()
                }
            }) {
                Text("返回鍵測試")
            }
        }
        commandResult?.let {
            Spacer(Modifier.height(6.dp))
            Text("上次結果：$it", style = MaterialTheme.typography.bodyMedium)
        }

        Spacer(Modifier.height(16.dp))
        Text("M2.1 讀畫面", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = {
                scope.launch {
                    serviceEnabled = SeeAndSayService.instance != null
                    try {
                        val snapshot = bridge.readScreen()
                        screenName = snapshot.screen
                        elements = snapshot.elements
                        message = "讀到 ${snapshot.elements.size} 個元素"
                    } catch (error: Exception) {
                        elements = emptyList()
                        screenName = null
                        message = error.message ?: "讀畫面失敗"
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("讀畫面")
        }

        Spacer(Modifier.height(12.dp))
        screenName?.let { Text("畫面：$it", style = MaterialTheme.typography.bodyMedium) }
        message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        Spacer(Modifier.height(8.dp))
        HorizontalDivider()

        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            items(elements) { element ->
                ElementRow(element)
                HorizontalDivider()
            }
        }
    }
}

/**
 * Renders one snapshot element as `i · text · click/edit flags · bounds` for visual verification.
 *
 * @param element the element to display.
 */
@Composable
private fun ElementRow(element: ScreenElement) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
    ) {
        Text(
            "${element.i}. ${element.text.ifEmpty { "（無文字）" }}",
            style = MaterialTheme.typography.bodyLarge,
        )
        val flags = buildString {
            if (element.clickable) append("clickable ")
            if (element.editable) append("editable ")
        }.trim().ifEmpty { "—" }
        Text(
            "$flags · bounds=${element.bounds}",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
