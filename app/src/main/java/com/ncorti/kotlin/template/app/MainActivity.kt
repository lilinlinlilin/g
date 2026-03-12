package com.ncorti.kotlin.template

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.MediaPlayer
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.abs
import kotlin.math.sqrt

val Context.soundDataStore: DataStore<Preferences> by preferencesDataStore(name = "sounds")

class MainActivity : ComponentActivity(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private var currentPlayer: MediaPlayer? = null
    private var selectedDesc by mutableStateOf<String?>(null)
    private var currentlyPlayingDesc by mutableStateOf<String?>(null)

    // 摇晃阈值 6f（极敏感）
    private val shakeThreshold = 6f
    private var lastShake = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color.Transparent
                ) {
                    SoundScreen(
                        selected = selectedDesc,
                        onSelect = { selectedDesc = it },
                        onPlayToggle = { desc -> togglePlay(desc) }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_GAME)
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
        currentPlayer?.pause()
        currentlyPlayingDesc = null
    }

    override fun onDestroy() {
        currentPlayer?.release()
        currentPlayer = null
        currentlyPlayingDesc = null
        super.onDestroy()
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type != Sensor.TYPE_ACCELEROMETER) return

        val now = System.currentTimeMillis()
        if (now - lastShake < 400) return
        lastShake = now

        val x = event.values[0].toDouble()
        val y = event.values[1].toDouble()
        val z = event.values[2].toDouble()
        val speed = sqrt(x * x + y * y + z * z) - SensorManager.GRAVITY_EARTH

        if (abs(speed) > shakeThreshold && selectedDesc != null) {
            if (currentPlayer?.isPlaying != true || currentlyPlayingDesc != selectedDesc) {
                playAudio(selectedDesc!!)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun playAudio(desc: String) {
        currentPlayer?.release()
        currentPlayer = null

        val soundsDir = getExternalFilesDir(null)?.resolve("sounds") ?: return
        if (!soundsDir.exists()) soundsDir.mkdirs()

        val audioFile = File(soundsDir, desc)

        if (audioFile.exists()) {
            try {
                currentPlayer = MediaPlayer().apply {
                    setDataSource(audioFile.absolutePath)
                    prepare()

                    // 关键修复：播放结束时自动恢复按钮状态
                    setOnCompletionListener {
                        currentlyPlayingDesc = null  // 播放完自动清空状态，按钮颜色恢复
                    }

                    start()
                }
                currentlyPlayingDesc = desc
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "播放失败：${e.message}", Toast.LENGTH_LONG).show()
                currentlyPlayingDesc = null
            }
        } else {
            Toast.makeText(this@MainActivity, "未找到音频文件：$desc", Toast.LENGTH_SHORT).show()
            currentlyPlayingDesc = null
        }
    }

    fun togglePlay(desc: String) {
        if (currentPlayer?.isPlaying == true && currentlyPlayingDesc == desc) {
            currentPlayer?.stop()
            currentPlayer?.release()
            currentPlayer = null
            currentlyPlayingDesc = null
        } else {
            playAudio(desc)
            selectedDesc = desc
        }
    }
}

@Composable
fun SoundScreen(
    selected: String?,
    onSelect: (String) -> Unit,
    onPlayToggle: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var descriptions by remember { mutableStateOf(listOf<String>()) }
    var showAddDialog by remember { mutableStateOf(false) }
    var inputDesc by remember { mutableStateOf("") }
    var editingDesc by remember { mutableStateOf<String?>(null) }
    var currentlyPlaying by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        context.soundDataStore.data
            .map { prefs ->
                val saved = prefs[stringPreferencesKey("descriptions")] ?: ""
                if (saved.isNotEmpty()) saved.split(",").filter { it.isNotBlank() } else emptyList()
            }
            .collect { newList ->
                descriptions = newList
            }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onBackground,
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Text("+")
            }
        }
    ) { innerPadding ->

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (descriptions.isEmpty()) {
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = "還沒有聲音描述～\n測試文字：如果 App 已全屏，這個文字會貼近邊緣",
                        color = Color.Gray,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.weight(1f))
                } else {
                    LazyColumn(modifier = Modifier.weight(1f)) {
                        items(descriptions) { desc ->
                            val isSelected = desc == selected
                            val isPlaying = desc == currentlyPlaying

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    border = BorderStroke(
                                        width = 2.dp,
                                        color = if (isSelected) Color.Blue else Color.LightGray
                                    ),
                                    color = if (isSelected) Color.Blue.copy(alpha = 0.08f) else MaterialTheme.colorScheme.surface,
                                    modifier = Modifier
                                        .weight(1f)
                                        .pointerInput(desc) {
                                            detectTapGestures(
                                                onTap = { onSelect(desc) },
                                                onLongPress = { editingDesc = desc }
                                            )
                                        }
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp, vertical = 14.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = desc,
                                            color = if (isSelected) Color.Blue else MaterialTheme.colorScheme.onSurface,
                                            textAlign = TextAlign.Center,
                                            maxLines = 1,
                                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                        )
                                    }
                                }

                                Spacer(Modifier.width(8.dp))

                                ElevatedButton(
                                    onClick = {
                                        onPlayToggle(desc)
                                        currentlyPlaying = if (isPlaying) null else desc
                                    },
                                    modifier = Modifier.size(36.dp),
                                    shape = CircleShape,
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (isPlaying) Color.Red.copy(alpha = 0.8f) else MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                                    ),
                                    contentPadding = PaddingValues(0.dp)
                                ) {}
                            }
                        }
                    }
                }
            }
        }
    }

    // 添加/编辑对话框保持不变
    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { },
            text = {
                OutlinedTextField(
                    value = inputDesc,
                    onValueChange = { inputDesc = it.trim() },
                    label = { Text("描述") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (inputDesc.isNotBlank()) {
                        val newList = (descriptions + inputDesc).distinct()
                        scope.launch {
                            context.soundDataStore.updateData { prefs ->
                                prefs.toMutablePreferences().apply {
                                    set(stringPreferencesKey("descriptions"), newList.joinToString(","))
                                }
                            }
                        }
                        inputDesc = ""
                    }
                    showAddDialog = false
                }) { Text("確定") }
            },
            dismissButton = { TextButton(onClick = { showAddDialog = false }) { } }
        )
    }

    editingDesc?.let { current ->
        var editInput by remember { mutableStateOf(current) }

        AlertDialog(
            onDismissRequest = { editingDesc = null },
            title = { },
            text = {
                OutlinedTextField(
                    value = editInput,
                    onValueChange = { editInput = it.trim() },
                    label = { Text("修改") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (editInput.isNotBlank() && editInput != current) {
                        val newList = descriptions.map { if (it == current) editInput else it }
                        scope.launch {
                            context.soundDataStore.updateData { prefs ->
                                prefs.toMutablePreferences().apply {
                                    set(stringPreferencesKey("descriptions"), newList.joinToString(","))
                                }
                            }
                        }
                    }
                    editingDesc = null
                }) { Text("保存") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        val toDelete = current
                        val newList = descriptions.filter { it != toDelete }
                        scope.launch {
                            context.soundDataStore.updateData { prefs ->
                                prefs.toMutablePreferences().apply {
                                    set(stringPreferencesKey("descriptions"), newList.joinToString(","))
                                }
                            }
                        }
                        if (selected == toDelete) onSelect("")
                        if (currentlyPlaying == toDelete) {
                            onPlayToggle(toDelete)
                            currentlyPlaying = null
                        }
                        editingDesc = null
                    }) {
                        Text("删除", color = MaterialTheme.colorScheme.error)
                    }
                    TextButton(onClick = { editingDesc = null }) { }
                }
            }
        )
    }
}
