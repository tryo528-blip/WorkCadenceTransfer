package com.tryo528.workcadencetransfer

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import com.tryo528.workcadencetransfer.security.SecurePendingStore
import com.tryo528.workcadencetransfer.transfer.CanonicalDigest
import com.tryo528.workcadencetransfer.transfer.ContractException
import com.tryo528.workcadencetransfer.transfer.Enrollment
import com.tryo528.workcadencetransfer.transfer.ImportedPhoto
import com.tryo528.workcadencetransfer.transfer.JsonContracts
import com.tryo528.workcadencetransfer.transfer.PendingSubmission
import com.tryo528.workcadencetransfer.transfer.PinnedReceiverClient
import com.tryo528.workcadencetransfer.transfer.ReceiverException
import com.tryo528.workcadencetransfer.transfer.SubmissionFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

private const val MAX_PHOTO_BYTES = 5_242_880
private const val MAX_PHOTOS = 5

private data class SharedImport(val sequence: Long, val uris: List<Uri>)

class MainActivity : ComponentActivity() {
    private var importSequence = 0L
    private val sharedImport = androidx.compose.runtime.mutableStateOf(SharedImport(0, emptyList()))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sharedImport.value = SharedImport(++importSequence, extractSharedUris(intent))
        setContent {
            TransferTheme {
                TransferApp(sharedImport = sharedImport.value)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        sharedImport.value = SharedImport(++importSequence, extractSharedUris(intent))
    }

    private fun extractSharedUris(intent: Intent?): List<Uri> {
        val fromExtra = when (intent?.action) {
            Intent.ACTION_SEND -> listOfNotNull(parcelableUri(intent))
            Intent.ACTION_SEND_MULTIPLE -> parcelableUris(intent)
            else -> emptyList()
        }
        if (fromExtra.isNotEmpty()) return fromExtra
        val clipData = intent?.clipData ?: return emptyList()
        return (0 until clipData.itemCount).mapNotNull { clipData.getItemAt(it).uri }
    }

    @Suppress("DEPRECATION")
    private fun parcelableUri(intent: Intent): Uri? = if (Build.VERSION.SDK_INT >= 33) {
        intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
    } else {
        intent.getParcelableExtra(Intent.EXTRA_STREAM)
    }

    @Suppress("DEPRECATION")
    private fun parcelableUris(intent: Intent): List<Uri> = if (Build.VERSION.SDK_INT >= 33) {
        intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java) ?: emptyList()
    } else {
        intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM) ?: emptyList()
    }
}

private enum class Screen { HOME, PREVIEW, ENROLL, PROGRESS, READY }

private val Paper = Color(0xFFF4F7FB)
private val Ink = Color(0xFF10213D)
private val Muted = Color(0xFF5F6F88)
private val Blue900 = Color(0xFF102B69)
private val Blue700 = Color(0xFF2457D6)
private val Blue100 = Color(0xFFEAF0FF)
private val Green700 = Color(0xFF087A5B)
private val Green100 = Color(0xFFE5F7F1)
private val Amber700 = Color(0xFFA45E00)
private val Amber100 = Color(0xFFFFF3D9)
private val Line = Color(0xFFDBE3EF)

private enum class Glyph { Lock, Cloud, Photo, Security, Back, CheckCircle }

@Composable
private fun AppGlyph(glyph: Glyph, tint: Color, modifier: Modifier = Modifier) {
    val symbol = when (glyph) {
        Glyph.Lock -> "▣"
        Glyph.Cloud -> "☁"
        Glyph.Photo -> "▦"
        Glyph.Security -> "◈"
        Glyph.Back -> "←"
        Glyph.CheckCircle -> "✓"
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        if (glyph == Glyph.CheckCircle) {
            Surface(color = tint.copy(alpha = 0.12f), shape = CircleShape) {
                Text(symbol, color = tint, fontSize = 58.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(10.dp))
            }
        } else {
            Text(symbol, color = tint, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun TransferTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Blue700,
            onPrimary = Color.White,
            primaryContainer = Blue100,
            onPrimaryContainer = Blue900,
            background = Paper,
            surface = Color.White,
            onSurface = Ink,
            onSurfaceVariant = Muted,
            outline = Line
        ),
        content = content
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TransferApp(sharedImport: SharedImport) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { SecurePendingStore(context.applicationContext) }
    val client = remember { PinnedReceiverClient() }
    val photos = remember { mutableStateListOf<ImportedPhoto>() }
    var screenName by rememberSaveable { mutableStateOf(Screen.HOME.name) }
    var memo by rememberSaveable { mutableStateOf("") }
    var pairingJson by rememberSaveable { mutableStateOf("") }
    var enrollment by remember { mutableStateOf<Enrollment?>(null) }
    var pending by remember { mutableStateOf<PendingSubmission?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf<String?>(null) }
    val screen = Screen.valueOf(screenName)

    LaunchedEffect(Unit) {
        enrollment = withContext(Dispatchers.IO) { store.loadEnrollment() }
    }

    LaunchedEffect(sharedImport.sequence) {
        if (sharedImport.uris.isEmpty()) return@LaunchedEffect
        busy = true
        val imported = withContext(Dispatchers.IO) { readJpegUris(context, sharedImport.uris.take(MAX_PHOTOS)) }
        busy = false
        if (imported.isEmpty()) {
            error = "공유된 자료에서 JPEG 사진을 찾지 못했습니다."
        } else {
            photos.clear()
            photos.addAll(imported)
            screenName = Screen.PREVIEW.name
            error = if (sharedImport.uris.size > MAX_PHOTOS) "처음 5장의 JPEG만 가져왔습니다." else null
            info = "공유받은 사진을 앱 전용 영역에 준비했습니다."
        }
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) {
            scope.launch {
                busy = true
                val imported = withContext(Dispatchers.IO) { readJpegUris(context, uris.take(MAX_PHOTOS)) }
                busy = false
                if (imported.isEmpty()) {
                    error = "JPEG 사진만 가져올 수 있습니다. PNG·동영상·손상된 파일은 제외했습니다."
                } else {
                    photos.clear()
                    photos.addAll(imported)
                    screenName = Screen.PREVIEW.name
                    error = null
                }
            }
        }
    }

    fun startImport() = importLauncher.launch("image/jpeg")

    fun sendCurrent() {
        val currentEnrollment = enrollment
        if (currentEnrollment == null) {
            info = "먼저 PC를 등록해야 합니다."
            screenName = Screen.ENROLL.name
            return
        }
        val normalizedMemo = CanonicalDigest.normalizeMemo(memo)
        if (normalizedMemo.isBlank() && photos.isEmpty()) {
            error = "메모나 사진을 하나 이상 준비해 주세요."
            return
        }
        val createdAt = DateTimeFormatter.ISO_INSTANT.format(Instant.now())
        val targetDate = LocalDate.now(ZoneId.of("Asia/Seoul")).toString()
        val next = SubmissionFactory.create(currentEnrollment.deviceId, normalizedMemo, targetDate, photos.toList(), createdAt)
        pending = next
        error = null
        info = null
        screenName = Screen.PROGRESS.name
        busy = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    store.save(next)
                    client.submit(currentEnrollment, next)
                }
                withContext(Dispatchers.IO) { store.delete(next.metadata.submissionId) }
                pending = null
                photos.clear()
                memo = ""
                screenName = Screen.READY.name
            } catch (exception: Exception) {
                error = exception.userMessage()
            } finally {
                busy = false
            }
        }
    }

    fun retryPending() {
        val current = pending ?: return
        val currentEnrollment = enrollment ?: return
        error = null
        busy = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) { client.submit(currentEnrollment, current) }
                withContext(Dispatchers.IO) { store.delete(current.metadata.submissionId) }
                pending = null
                photos.clear()
                memo = ""
                screenName = Screen.READY.name
            } catch (exception: Exception) {
                error = exception.userMessage()
            } finally {
                busy = false
            }
        }
    }

    fun enroll() {
        scope.launch {
            busy = true
            error = null
            try {
                val pairing = JsonContracts.pairingQrFromJson(pairingJson)
                val next = withContext(Dispatchers.IO) { client.enroll(pairing) }
                withContext(Dispatchers.IO) { store.saveEnrollment(next) }
                enrollment = next
                pairingJson = ""
                info = "PC 등록이 완료되었습니다."
                screenName = Screen.HOME.name
            } catch (exception: Exception) {
                error = exception.userMessage()
            } finally {
                busy = false
            }
        }
    }

    Scaffold(containerColor = Paper) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (screen) {
                Screen.HOME -> HomeScreen(
                    enrollment = enrollment,
                    memo = memo,
                    onMemoChange = { memo = it },
                    photos = photos,
                    onAddPhotos = ::startImport,
                    onSend = ::sendCurrent,
                    onEnroll = { screenName = Screen.ENROLL.name },
                    busy = busy,
                    error = error,
                    info = info
                )
                Screen.PREVIEW -> PreviewScreen(
                    photos = photos,
                    onBack = { screenName = Screen.HOME.name },
                    onAddPhotos = ::startImport,
                    onSend = ::sendCurrent,
                    busy = busy,
                    error = error
                )
                Screen.ENROLL -> EnrollmentScreen(
                    json = pairingJson,
                    onJsonChange = { pairingJson = it },
                    onBack = { screenName = Screen.HOME.name },
                    onEnroll = ::enroll,
                    busy = busy,
                    error = error
                )
                Screen.PROGRESS -> ProgressScreen(
                    pending = pending,
                    error = error,
                    busy = busy,
                    onRetry = ::retryPending,
                    onBack = { screenName = Screen.HOME.name }
                )
                Screen.READY -> ReadyScreen(onNewRecord = { screenName = Screen.HOME.name })
            }
            if (busy) LoadingDialog()
        }
    }
}

@Composable
private fun HomeScreen(
    enrollment: Enrollment?,
    memo: String,
    onMemoChange: (String) -> Unit,
    photos: List<ImportedPhoto>,
    onAddPhotos: () -> Unit,
    onSend: () -> Unit,
    onEnroll: () -> Unit,
    busy: Boolean,
    error: String?,
    info: String?
) {
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppGlyph(Glyph.Lock, Blue700, Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Transfer", color = Ink, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
            Surface(shape = CircleShape, color = if (enrollment == null) Amber100 else Green100) {
                Text(if (enrollment == null) "PC 등록 필요" else "PC 연결됨", color = if (enrollment == null) Amber700 else Green700, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp))
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("오늘의 전송", color = Blue700, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Text("메모와 사진을\n안전하게 보내요", color = Ink, fontSize = 26.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold)
            Text("전송은 화면을 열어 둔 동안만 진행됩니다.", color = Muted, fontSize = 11.sp)
        }
        Surface(color = Blue100, shape = RoundedCornerShape(12.dp)) {
            Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                AppGlyph(Glyph.Cloud, Blue700, Modifier.size(17.dp))
                Spacer(Modifier.width(8.dp))
                Text("2026.08.14 · 오늘 기록", color = Blue700, fontSize = 11.sp, fontWeight = FontWeight.Medium)
            }
        }
        OutlinedTextField(value = memo, onValueChange = onMemoChange, modifier = Modifier.fillMaxWidth(), label = { Text("메모") }, placeholder = { Text("전달할 내용을 적어주세요") }, supportingText = { Text("최대 8,192 UTF-8 bytes") }, minLines = 3, maxLines = 5)
        Card(colors = CardDefaults.cardColors(containerColor = Color.White), border = androidx.compose.foundation.BorderStroke(1.dp, Line), shape = RoundedCornerShape(18.dp)) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AppGlyph(Glyph.Photo, Blue700, Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("사진", color = Ink, fontWeight = FontWeight.Bold)
                }
                Text(if (photos.isEmpty()) "선택된 사진 없음" else "${photos.size}장 선택됨 · JPEG", color = Muted, fontSize = 11.sp)
                OutlinedButton(onClick = onAddPhotos, modifier = Modifier.fillMaxWidth(), enabled = !busy) { Text("카메라 또는 공유에서 추가") }
            }
        }
        Surface(color = Green100, shape = RoundedCornerShape(14.dp)) {
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                AppGlyph(Glyph.Security, Green700, Modifier.size(22.dp))
                Spacer(Modifier.width(8.dp))
                Column { Text("갤러리 저장 없음", color = Green700, fontWeight = FontWeight.Bold, fontSize = 11.sp); Text("성공 전까지 앱 전용 암호화 보관", color = Ink, fontSize = 10.sp) }
            }
        }
        Button(onClick = onSend, modifier = Modifier.fillMaxWidth(), enabled = !busy && (memo.isNotBlank() || photos.isNotEmpty()), shape = RoundedCornerShape(26.dp), colors = ButtonDefaults.buttonColors(containerColor = Blue700)) { Text("PC로 보내기", modifier = Modifier.padding(vertical = 4.dp)) }
        if (enrollment == null) TextButton(onClick = onEnroll, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("PC 등록하기", color = Blue700) }
        if (info != null) Text(info, color = Green700, fontSize = 11.sp, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        if (error != null) Text(error, color = Color(0xFFBD2F45), fontSize = 11.sp, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        Spacer(Modifier.weight(1f))
        Text("Foreground only · READY ACK 뒤 로컬 보관함 정리", color = Muted, fontSize = 9.sp, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
    }
}

@Composable
private fun PreviewScreen(photos: List<ImportedPhoto>, onBack: () -> Unit, onAddPhotos: () -> Unit, onSend: () -> Unit, busy: Boolean, error: String?) {
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Row(verticalAlignment = Alignment.CenterVertically) { IconButton(onClick = onBack) { AppGlyph(Glyph.Back, Ink, Modifier.size(24.dp)) }; Text("사진 미리보기", color = Ink, fontWeight = FontWeight.Bold) }
            Surface(shape = CircleShape, color = Blue100) { Text("${photos.size} / 5", color = Blue700, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)) }
        }
        Text("선택 순서대로 보냅니다", color = Ink, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text("사진은 PC에서 다시 압축·정규화되며 원본 파일명은 쓰지 않습니다.", color = Muted, fontSize = 11.sp)
        LazyVerticalGrid(columns = GridCells.Fixed(2), modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(photos, key = { it.photoId }) { photo ->
                Card(colors = CardDefaults.cardColors(containerColor = Color.White), border = androidx.compose.foundation.BorderStroke(1.dp, Line), shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(Modifier.fillMaxWidth().height(86.dp).clip(RoundedCornerShape(11.dp)).background(Blue100), contentAlignment = Alignment.Center) { AppGlyph(Glyph.Photo, Blue700, Modifier.size(32.dp)) }
                        Text("JPEG · ${photo.bytes.size / 1024} KB", color = Ink, fontSize = 10.sp, fontWeight = FontWeight.Medium)
                        Text("정규화 대기", color = Green700, fontSize = 9.sp)
                    }
                }
            }
            item {
                OutlinedButton(onClick = onAddPhotos, modifier = Modifier.height(142.dp).fillMaxWidth(), enabled = !busy, shape = RoundedCornerShape(16.dp)) { Text("+\n사진 더 추가", textAlign = TextAlign.Center, color = Blue700) }
            }
        }
        Surface(color = Blue100, shape = RoundedCornerShape(14.dp)) {
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) { AppGlyph(Glyph.Security, Blue700, Modifier.size(22.dp)); Spacer(Modifier.width(8.dp)); Column { Text("저장본은 항상 정상 방향", color = Blue700, fontWeight = FontWeight.Bold, fontSize = 11.sp); Text("EXIF · GPS · XMP · thumbnail 제거", color = Ink, fontSize = 10.sp) } }
        }
        Button(onClick = onSend, modifier = Modifier.fillMaxWidth(), enabled = !busy && photos.isNotEmpty(), shape = RoundedCornerShape(26.dp), colors = ButtonDefaults.buttonColors(containerColor = Blue700)) { Text("다음: 전송", modifier = Modifier.padding(vertical = 4.dp)) }
        if (error != null) Text(error, color = Color(0xFFBD2F45), fontSize = 11.sp, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
    }
}

@Composable
private fun EnrollmentScreen(json: String, onJsonChange: (String) -> Unit, onBack: () -> Unit, onEnroll: () -> Unit, busy: Boolean, error: String?) {
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { IconButton(onClick = onBack) { AppGlyph(Glyph.Back, Ink, Modifier.size(24.dp)) }; Text("PC 등록", color = Ink, fontWeight = FontWeight.Bold) }
        Text("PC와 안전하게 연결해요", color = Ink, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text("Windows receiver 화면의 pairing QR JSON을 복사해 아래에 붙여넣습니다. 네트워크는 등록을 누를 때만 사용합니다.", color = Muted, fontSize = 11.sp)
        OutlinedTextField(value = json, onValueChange = onJsonChange, modifier = Modifier.fillMaxWidth().weight(1f), label = { Text("pairing QR JSON") }, placeholder = { Text("{\\\"version\\\":1,...}") }, minLines = 8)
        Button(onClick = onEnroll, modifier = Modifier.fillMaxWidth(), enabled = !busy && json.isNotBlank(), shape = RoundedCornerShape(26.dp), colors = ButtonDefaults.buttonColors(containerColor = Blue700)) { Text("PC 등록하기") }
        if (error != null) Text(error, color = Color(0xFFBD2F45), fontSize = 11.sp, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
    }
}

@Composable
private fun ProgressScreen(pending: PendingSubmission?, error: String?, busy: Boolean, onRetry: () -> Unit, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { AppGlyph(Glyph.Lock, Blue700, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("PC로 보내는 중", color = Ink, fontWeight = FontWeight.Bold) }
        Text("잠시만 기다려요", color = Ink, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text("암호화된 자료를 PC에 전달하고 있습니다.", color = Muted, fontSize = 11.sp)
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CircularProgressIndicator(color = Blue700, trackColor = Blue100)
            Text("${pending?.photos?.size ?: 0}장 · foreground 전송", color = Blue700, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            LinearProgressIndicator(Modifier.fillMaxWidth(), color = Blue700, trackColor = Line)
        }
        Card(colors = CardDefaults.cardColors(containerColor = Color.White), border = androidx.compose.foundation.BorderStroke(1.dp, Line), shape = RoundedCornerShape(18.dp)) {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TransferStep("✓", "암호화된 제출 준비", "앱 전용 보관함에서 확인", Green700)
                TransferStep("2", "TLS로 PC에 전송 중", "현재 제출을 처리 중", Blue700)
                TransferStep("3", "READY ACK 확인 대기", "완료 전에는 로컬 자료를 유지", Muted)
            }
        }
        Surface(color = Amber100, shape = RoundedCornerShape(14.dp)) { Row(Modifier.fillMaxWidth().padding(11.dp), verticalAlignment = Alignment.CenterVertically) { AppGlyph(Glyph.Security, Amber700, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Column { Text("화면을 닫지 마세요", color = Amber700, fontWeight = FontWeight.Bold, fontSize = 11.sp); Text("foreground를 벗어나면 안전하게 중단합니다.", color = Ink, fontSize = 10.sp) } } }
        if (error != null) {
            Text(error, color = Color(0xFFBD2F45), fontSize = 11.sp, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
            OutlinedButton(onClick = onRetry, modifier = Modifier.fillMaxWidth(), enabled = !busy, shape = RoundedCornerShape(26.dp)) { Text("같은 제출 재시도") }
            TextButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("작성 화면으로", color = Blue700) }
        }
        Spacer(Modifier.weight(1f))
        Text("제출 ID와 digest는 재시도에서 유지됩니다.", color = Muted, fontSize = 9.sp, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
    }
}

@Composable
private fun TransferStep(number: String, title: String, detail: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(color = color.copy(alpha = 0.12f), shape = CircleShape, modifier = Modifier.size(28.dp)) { Box(contentAlignment = Alignment.Center) { Text(number, color = color, fontSize = 12.sp, fontWeight = FontWeight.Bold) } }
        Spacer(Modifier.width(10.dp))
        Column { Text(title, color = color, fontSize = 11.sp, fontWeight = FontWeight.Bold); Text(detail, color = Muted, fontSize = 10.sp) }
    }
}

@Composable
private fun ReadyScreen(onNewRecord: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { AppGlyph(Glyph.Security, Green700, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("전송 결과", color = Ink, fontWeight = FontWeight.Bold); Spacer(Modifier.weight(1f)); Surface(color = Green100, shape = CircleShape) { Text("READY", color = Green700, fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 9.dp, vertical = 7.dp)) } }
        Spacer(Modifier.height(8.dp))
        AppGlyph(Glyph.CheckCircle, Green700, Modifier.size(86.dp))
        Text("PC에 안전하게 보관했어요", color = Ink, fontSize = 22.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Text("인증된 READY ACK를 받은 뒤 이 기기의 전송 자료를 정리했습니다.", color = Muted, fontSize = 11.sp, textAlign = TextAlign.Center)
        Card(colors = CardDefaults.cardColors(containerColor = Color.White), border = androidx.compose.foundation.BorderStroke(1.dp, Line), shape = RoundedCornerShape(18.dp)) { Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Text("이번 기록", color = Ink, fontWeight = FontWeight.Bold, fontSize = 13.sp); ReadyStat("대상 날짜", "오늘"); ReadyStat("사진", "정규화 완료"); ReadyStat("보관", "Windows encrypted READY") } }
        Surface(color = Green100, shape = RoundedCornerShape(14.dp)) { Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) { AppGlyph(Glyph.Security, Green700, Modifier.size(22.dp)); Spacer(Modifier.width(8.dp)); Column { Text("로컬 암호화 보관함 정리 완료", color = Green700, fontWeight = FontWeight.Bold, fontSize = 11.sp); Text("평문 사진·원본 파일명·썸네일 없음", color = Ink, fontSize = 10.sp) } } }
        Button(onClick = onNewRecord, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(26.dp), colors = ButtonDefaults.buttonColors(containerColor = Blue700)) { Text("새 기록 작성", modifier = Modifier.padding(vertical = 4.dp)) }
        Spacer(Modifier.weight(1f))
        Text("암호화 정본은 PC에서 별도 보존 규정에 따릅니다.", color = Muted, fontSize = 9.sp, textAlign = TextAlign.Center)
    }
}

@Composable
private fun ReadyStat(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(label, color = Muted, fontSize = 10.sp); Text(value, color = Ink, fontSize = 10.sp, fontWeight = FontWeight.Bold) }
}

@Composable
private fun LoadingDialog() {
    AlertDialog(onDismissRequest = {}, confirmButton = {}, title = { Text("처리 중") }, text = { Text("암호화된 자료를 준비하고 있습니다.") })
}

private suspend fun readJpegUris(context: android.content.Context, uris: List<Uri>): List<ImportedPhoto> {
    return uris.mapNotNull { uri ->
        val mime = context.contentResolver.getType(uri)
        if (mime != null && mime != "image/jpeg") return@mapNotNull null
        val bytes = context.contentResolver.openInputStream(uri)?.use(::readAtMost) ?: return@mapNotNull null
        if (bytes.size < 4 || bytes.size > MAX_PHOTO_BYTES || bytes[0] != 0xFF.toByte() || bytes[1] != 0xD8.toByte()) return@mapNotNull null
        ImportedPhoto(UUID.randomUUID().toString(), bytes, context.contentResolver.getType(uri))
    }
}

private fun readAtMost(input: java.io.InputStream): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val read = input.read(buffer)
        if (read < 0) return output.toByteArray()
        if (output.size() + read > MAX_PHOTO_BYTES) return ByteArray(MAX_PHOTO_BYTES + 1)
        output.write(buffer, 0, read)
    }
}

private fun Throwable.userMessage(): String = when (this) {
    is ContractException -> message ?: "계약 검증에 실패했습니다."
    is ReceiverException -> message ?: "Windows receiver와 통신하지 못했습니다."
    else -> message ?: "처리하지 못했습니다. 같은 제출을 다시 시도해 주세요."
}
