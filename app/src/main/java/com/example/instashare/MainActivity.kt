package com.example.instashare

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDL.UpdateChannel
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image

sealed class DownloadState {
    object Idle : DownloadState()
    object Initializing : DownloadState()
    data class Downloading(val progress: Float, val speed: String) : DownloadState()
    object Processing : DownloadState()
    data class Success(val file: File) : DownloadState()
    data class Error(val message: String) : DownloadState()
}

private fun getCookiesFile(context: android.content.Context): File {
    return File(context.filesDir, "cookies.txt")
}

private fun saveCookies(context: android.content.Context, content: String) {
    val processedContent = try {
        val lines = content.lines()
        val processedLines = lines.map { line ->
            val trimmed = line.replace(Regex("^[ \r\n]+|[ \r\n]+\$"), "")
            val isHttpOnly = trimmed.startsWith("#HttpOnly_") || trimmed.startsWith("# HttpOnly_")
            val httpOnlyPrefix = if (trimmed.startsWith("#HttpOnly_")) "#HttpOnly_" else "# HttpOnly_"
            val isComment = trimmed.startsWith("#") && !isHttpOnly
            if (trimmed.isEmpty() || isComment) {
                line
            } else {
                val contentToProcess = if (isHttpOnly) trimmed.substring(httpOnlyPrefix.length) else trimmed
                val tabParts = contentToProcess.split("\t", limit = -1)
                val processedLine = if (tabParts.size == 7) {
                    contentToProcess
                } else if (tabParts.size > 7) {
                    contentToProcess
                } else {
                    val spaceParts = contentToProcess.split(Regex("\\s+"))
                    if (spaceParts.size == 7) {
                        spaceParts.joinToString("\t")
                    } else if (spaceParts.size > 7) {
                        val firstSix = spaceParts.take(6)
                        val rest = spaceParts.drop(6).joinToString(" ")
                        (firstSix + rest).joinToString("\t")
                    } else if (spaceParts.size == 6) {
                        spaceParts.joinToString("\t") + "\t"
                    } else {
                        contentToProcess
                    }
                }
                if (isHttpOnly) "$httpOnlyPrefix$processedLine" else processedLine
            }
        }
        processedLines.joinToString("\n")
    } catch (e: Exception) {
        Log.e("CookieSaver", "Error auto-formatting cookies, writing raw text", e)
        content
    }

    val file = getCookiesFile(context)
    file.writeText(processedContent)
}

private fun readCookies(context: android.content.Context): String {
    val file = getCookiesFile(context)
    return if (file.exists()) file.readText() else ""
}

private fun clearCookies(context: android.content.Context) {
    val file = getCookiesFile(context)
    if (file.exists()) {
        file.delete()
    }
}

class DownloadViewModel : ViewModel() {
    var state by mutableStateOf<DownloadState>(DownloadState.Idle)
        private set

    fun downloadVideo(context: android.content.Context, cacheDir: File, url: String) {
        viewModelScope.launch {
            state = DownloadState.Initializing
            val resultFile = withContext(Dispatchers.IO) {
                try {
                    // Try to initialize and update to the latest yt-dlp to fix extractor bugs (e.g. Instagram empty response)
                    try {
                        YoutubeDL.getInstance().init(context.applicationContext)
                        Log.d("DownloadViewModel", "Checking for yt-dlp updates...")
                        YoutubeDL.getInstance().updateYoutubeDL(context.applicationContext, UpdateChannel.NIGHTLY)
                        Log.d("DownloadViewModel", "yt-dlp update check complete")
                    } catch (e: Exception) {
                        Log.e("DownloadViewModel", "Failed to update/init yt-dlp", e)
                    }
                    
                    val downloadsDir = File(cacheDir, "downloads")
                    if (downloadsDir.exists()) {
                        downloadsDir.listFiles()?.forEach { it.delete() }
                    } else {
                        downloadsDir.mkdirs()
                    }
                    
                    val downloadId = java.util.UUID.randomUUID().toString()
                    val request = YoutubeDLRequest(url)
                    request.addOption("-o", "${downloadsDir.absolutePath}/$downloadId.%(ext)s")
                    request.addOption("-f", "bv*[vcodec^=avc1]+ba[acodec^=mp4a]/b[vcodec^=avc1]/bestvideo+bestaudio/best")
                    request.addOption("--recode-video", "mp4")
                    request.addOption("--postprocessor-args", "ffmpeg:-movflags +faststart")
                    request.addOption("--postprocessor-args", "VideoConvertor:-pix_fmt yuv420p")
                    
                    val cookiesFile = getCookiesFile(context)
                    if (cookiesFile.exists() && cookiesFile.length() > 0) {
                        request.addOption("--cookies", cookiesFile.absolutePath)
                        Log.d("DownloadViewModel", "Using saved cookies: ${cookiesFile.absolutePath}")
                    }
                    
                    YoutubeDL.getInstance().execute(request, "main") { progress, _, speed ->
                        viewModelScope.launch {
                            state = DownloadState.Downloading(progress / 100f, speed)
                        }
                    }
                    
                    val finalFile = File(downloadsDir, "$downloadId.mp4")
                    if (!finalFile.exists()) {
                        val extensionFile = downloadsDir.listFiles()?.find { it.name.startsWith(downloadId) }
                        if (extensionFile == null || !extensionFile.exists()) {
                            throw Exception("File not found after download.")
                        }
                        if (extensionFile.absolutePath != finalFile.absolutePath) {
                            if (!extensionFile.renameTo(finalFile)) {
                                throw Exception("Failed to rename download file to mp4.")
                            }
                        }
                    }
                    
                    // Transcode or remux if needed to ensure H.264/AAC faststart
                    withContext(Dispatchers.Main) {
                        state = DownloadState.Processing
                    }
                    processVideoFile(context, cacheDir, downloadId)
                    
                    finalFile
                } catch (e: Exception) {
                    Log.e("DownloadViewModel", "Error downloading", e)
                    return@withContext e
                }
            }

            if (resultFile is File) {
                state = DownloadState.Success(resultFile)
            } else if (resultFile is Exception) {
                state = DownloadState.Error(resultFile.message ?: "Failed to download video properly.")
            } else {
                state = DownloadState.Error("Unknown error occurred.")
            }
        }
    }

    private fun processVideoFile(context: android.content.Context, cacheDir: File, downloadId: String) {
        val downloadsDir = File(cacheDir, "downloads")
        val finalFile = File(downloadsDir, "$downloadId.mp4")
        if (!finalFile.exists()) {
            throw Exception("Downloaded file not found for processing.")
        }

        // Determine if the file is already H.264 and AAC
        val isCompatible = checkCompatibleCodecs(finalFile)
        
        val tempFile = File(downloadsDir, "${downloadId}_processed.mp4")
        val ffmpegBinary = findFFmpegBinary(context) ?: throw Exception("FFmpeg engine not found on device.")
        
        val ffmpegArgs = if (isCompatible) {
            // Fast remux to apply faststart (stream copy only video and audio)
            arrayOf(
                ffmpegBinary.absolutePath,
                "-y",
                "-i", finalFile.absolutePath,
                "-map", "0:v?",
                "-map", "0:a?",
                "-c:v", "copy",
                "-c:a", "copy",
                "-movflags", "+faststart",
                tempFile.absolutePath
            )
        } else {
            // Full transcode to H.264, AAC, YUV420p, faststart
            arrayOf(
                ffmpegBinary.absolutePath,
                "-y",
                "-i", finalFile.absolutePath,
                "-map", "0:v?",
                "-map", "0:a?",
                "-c:v", "libx264",
                "-c:a", "aac",
                "-pix_fmt", "yuv420p",
                "-movflags", "+faststart",
                tempFile.absolutePath
            )
        }

        try {
            val pb = ProcessBuilder(*ffmpegArgs)
            val env = pb.environment()
            
            // Set LD_LIBRARY_PATH so dynamic linker can locate dependent shared libraries (e.g. libavcodec.so, libx264.so, libexpat.so.1)
            val libraryDirs = findLibraryDirs(context)
            val nativeLibDir = context.applicationInfo.nativeLibraryDir
            val currentLdPath = env["LD_LIBRARY_PATH"]
            val allPaths = (libraryDirs.map { it.absolutePath } + listOf(nativeLibDir, currentLdPath))
                .filterNotNull()
                .filter { it.isNotEmpty() }
                .distinct()
            val newLdPath = allPaths.joinToString(":")
            
            env["LD_LIBRARY_PATH"] = newLdPath
            Log.d("FFmpegProcess", "Set LD_LIBRARY_PATH to: $newLdPath")
            
            pb.redirectErrorStream(true)
            val process = pb.start()
            
            // Read output in a separate thread to prevent process hanging due to filled buffer
            val logOutput = StringBuilder()
            val readerThread = Thread {
                try {
                    process.inputStream.bufferedReader().use { reader ->
                        var line: String?
                        while (reader.readLine().also { line = it } != null) {
                            logOutput.append(line).append("\n")
                        }
                    }
                } catch (e: Exception) {
                    Log.e("FFmpegProcess", "Error reading process log", e)
                }
            }
            readerThread.start()
            
            val exitCode = process.waitFor()
            readerThread.join()
            
            if (exitCode != 0) {
                Log.e("FFmpegProcess", "FFmpeg failed with exit code $exitCode:\n$logOutput")
                throw Exception("Video optimization failed (FFmpeg exit code $exitCode).")
            }
            
            // Delete original file and rename temp file to finalFile
            finalFile.delete()
            if (!tempFile.renameTo(finalFile)) {
                throw Exception("Failed to rename optimized video file.")
            }
        } catch (e: Exception) {
            if (tempFile.exists()) tempFile.delete()
            throw e
        }
    }

    private fun checkCompatibleCodecs(file: File): Boolean {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            var hasVideo = false
            var hasAudio = false
            var isVideoH264 = false
            var isAudioAac = false

            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/")) {
                    hasVideo = true
                    if (mime == MediaFormat.MIMETYPE_VIDEO_AVC) {
                        isVideoH264 = true
                    }
                } else if (mime.startsWith("audio/")) {
                    hasAudio = true
                    if (mime == MediaFormat.MIMETYPE_AUDIO_AAC) {
                        isAudioAac = true
                    }
                }
            }
            // If the file has a video track, it must be H.264. If it has an audio track, it must be AAC.
            return (!hasVideo || isVideoH264) && (!hasAudio || isAudioAac)
        } catch (e: Exception) {
            Log.e("MediaCheck", "Error checking media formats", e)
            return false
        } finally {
            extractor.release()
        }
    }

    private fun findLibraryDirs(context: android.content.Context): List<File> {
        val libDirs = mutableListOf<File>()
        libDirs.add(File(context.applicationInfo.nativeLibraryDir))
        
        val dirs = listOf(context.noBackupFilesDir, context.filesDir)
        for (dir in dirs) {
            if (dir.exists()) {
                dir.walk().forEach { file ->
                    if (file.extension == "so" && file.isFile) {
                        val parent = file.parentFile
                        if (parent != null && !libDirs.contains(parent)) {
                            libDirs.add(parent)
                        }
                    }
                }
            }
        }
        return libDirs
    }

    private fun findFFmpegBinary(context: android.content.Context): File? {
        val names = listOf("libffmpeg.so", "ffmpeg", "libffmpeg")
        
        // 1. Check native library directory (where Android extracts native libraries)
        val nativeDir = File(context.applicationInfo.nativeLibraryDir)
        if (nativeDir.exists() && nativeDir.isDirectory) {
            for (name in names) {
                val file = File(nativeDir, name)
                if (file.exists() && file.isFile) {
                    Log.d("FFmpegFinder", "Found FFmpeg in native lib dir: ${file.absolutePath}")
                    return file
                }
            }
        }
        
        // 2. Check filesDir and noBackupFilesDir (recursively walking)
        val dirs = listOf(context.filesDir, context.noBackupFilesDir)
        for (dir in dirs) {
            val found = dir.walk().find { file ->
                names.any { name -> file.name == name } && file.isFile
            }
            if (found != null) {
                Log.d("FFmpegFinder", "Found FFmpeg in app dir: ${found.absolutePath}")
                return found
            }
        }
        
        Log.e("FFmpegFinder", "FFmpeg binary not found anywhere!")
        return null
    }
}

class MainActivity : ComponentActivity() {

    private val viewModel = DownloadViewModel()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        var receivedUrl: String? = null
        if (intent.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
            receivedUrl = sharedText?.let { extractUrl(it) }
        }

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen(
                        receivedUrl = receivedUrl,
                        viewModel = viewModel,
                        cacheDir = cacheDir,
                        onShare = { file -> shareFile(file) }
                    )
                }
            }
        }
    }

    private fun extractUrl(text: String): String? {
        val urlRegex = "(https?://[^\\s]+)".toRegex()
        val match = urlRegex.find(text)?.value ?: return null
        return match.dropLastWhile { it in ".,!?;:)\"']}" }
    }

    private fun shareFile(file: File) {
        try {
            val uri: Uri = FileProvider.getUriForFile(
                this,
                "com.example.instashare.fileprovider",
                file
            )
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "video/mp4"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                // Add subject
                putExtra(Intent.EXTRA_SUBJECT, "Shared Video")
            }
            startActivity(Intent.createChooser(shareIntent, "Share Video via"))
        } catch (e: Exception) {
            Toast.makeText(this, "Could not share file", Toast.LENGTH_SHORT).show()
        }
    }
}

@Composable
fun MainScreen(
    receivedUrl: String?,
    viewModel: DownloadViewModel,
    cacheDir: File,
    onShare: (File) -> Unit
) {
    val state = viewModel.state
    val context = LocalContext.current
    var showCookieDialog by remember { mutableStateOf(false) }
    
    // Auto start download
    LaunchedEffect(receivedUrl) {
        if (receivedUrl != null && state is DownloadState.Idle) {
            viewModel.downloadVideo(context, cacheDir, receivedUrl)
        }
    }

    // Auto share when done
    LaunchedEffect(state) {
        if (state is DownloadState.Success) {
            onShare(state.file)
        }
    }

    // Modern obsidian/dark theme background
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF0C091A), // Deep obsidian purple
                        Color(0xFF050308)  // Pure dark obsidian
                    )
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        // Decorative glowing circle behind card
        Box(
            modifier = Modifier
                .size(300.dp)
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            Color(0x1E8B5CF6), // Subtle violet glow
                            Color.Transparent
                        )
                    )
                )
                .align(Alignment.Center)
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Header Section
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(top = 40.dp)
            ) {
                Text(
                    text = "ChuckleShare",
                    style = MaterialTheme.typography.titleLarge.copy(
                        brush = Brush.horizontalGradient(
                            colors = listOf(
                                Color(0xFF60A5FA), // Soft blue
                                Color(0xFFA78BFA), // Pastel purple
                                Color(0xFFF472B6)  // Pastel pink
                            )
                        ),
                        fontWeight = FontWeight.Bold,
                        fontSize = 36.sp
                    ),
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "He finally stopped sending links??",
                    color = Color(0xFF9CA3AF),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center
                )
            }

            // Central Glassmorphic Card Container
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color(0x12FFFFFF)) // Ultra semi-transparent white
                    .border(
                        width = 1.dp,
                        brush = Brush.linearGradient(
                            colors = listOf(
                                Color(0x33FFFFFF),
                                Color(0x05FFFFFF)
                            )
                        ),
                        shape = RoundedCornerShape(24.dp)
                    )
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    when (state) {
                        is DownloadState.Idle -> {
                            Text(
                                text = if (receivedUrl == null) {
                                    "Waiting for Video link..."
                                } else {
                                    "Analyzing request..."
                                },
                                color = Color.White,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.SemiBold,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = if (receivedUrl == null) {
                                    "Share or send a video link from Instagram, TikTok, or YouTube directly to ChuckleShare."
                                } else {
                                    "Checking download cache and establishing connection..."
                                },
                                color = Color(0xFF9CA3AF),
                                fontSize = 14.sp,
                                textAlign = TextAlign.Center
                            )
                        }
                        is DownloadState.Initializing -> {
                            AnimatedKhanChuckling(
                                modifier = Modifier.padding(bottom = 16.dp)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Preparing Downloader Engine...",
                                color = Color.White,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Medium,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Updating dependencies natively",
                                color = Color(0xFF6B7280),
                                fontSize = 12.sp,
                                textAlign = TextAlign.Center
                            )
                        }
                        is DownloadState.Downloading -> {
                            AnimatedKhanChuckling(
                                modifier = Modifier.padding(bottom = 16.dp)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Downloading Video",
                                color = Color.White,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            
                            // Custom Gradient Progress Bar
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(12.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Color(0x1AFFFFFF))
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxHeight()
                                        .fillMaxWidth(state.progress.coerceIn(0f, 1f))
                                        .background(
                                            Brush.horizontalGradient(
                                                listOf(
                                                    Color(0xFF60A5FA),
                                                    Color(0xFFEC4899)
                                                )
                                            )
                                        )
                                )
                            }
                            Spacer(modifier = Modifier.height(16.dp))
                            
                            Text(
                                text = "${(state.progress * 100).toInt()}% Complete",
                                color = Color.White,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Speed: ${state.speed}",
                                color = Color(0xFF9CA3AF),
                                fontSize = 13.sp
                            )
                        }
                        is DownloadState.Processing -> {
                            AnimatedKhanChuckling(
                                modifier = Modifier.padding(bottom = 16.dp)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Optimizing Video...",
                                color = Color.White,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Medium,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Configuring iOS compatibility parameters",
                                color = Color(0xFF6B7280),
                                fontSize = 12.sp,
                                textAlign = TextAlign.Center
                            )
                        }
                        is DownloadState.Success -> {
                            Image(
                                painter = painterResource(id = R.drawable.khan_thumbs_up),
                                contentDescription = "Khan thumbs up",
                                modifier = Modifier
                                    .size(100.dp)
                                    .clip(CircleShape)
                                    .border(3.dp, Color(0xFF10B981), CircleShape)
                            )
                            Spacer(modifier = Modifier.height(20.dp))
                            Text(
                                text = "Download Finished!",
                                color = Color.White,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Video prepared with universal compatibility.",
                                color = Color(0xFF9CA3AF),
                                fontSize = 13.sp,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(24.dp))
                            Button(
                                onClick = { onShare(state.file) },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFF8B5CF6),
                                    contentColor = Color.White
                                ),
                                shape = RoundedCornerShape(14.dp),
                                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
                                elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp)
                            ) {
                                Text(
                                    text = "Share Video Again",
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                        is DownloadState.Error -> {
                            Image(
                                painter = painterResource(id = R.drawable.khan_frowning),
                                contentDescription = "Khan frowning",
                                modifier = Modifier
                                    .size(100.dp)
                                    .clip(CircleShape)
                                    .border(3.dp, Color(0xFFEF4444), CircleShape)
                            )
                            Spacer(modifier = Modifier.height(20.dp))
                            Text(
                                text = "Download Failed",
                                color = Color(0xFFEF4444),
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = state.message,
                                color = Color(0xFF9CA3AF),
                                fontSize = 13.sp,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(horizontal = 8.dp)
                            )
                            if (receivedUrl != null) {
                                Spacer(modifier = Modifier.height(24.dp))
                                Button(
                                    onClick = { viewModel.downloadVideo(context, cacheDir, receivedUrl) },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Color(0x33EF4444),
                                        contentColor = Color(0xFFEF4444)
                                    ),
                                    border = BorderStroke(1.dp, Color(0xFFEF4444)),
                                    shape = RoundedCornerShape(14.dp),
                                    contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp)
                                ) {
                                    Text(
                                        text = "Retry Download",
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Footer / Status
            Text(
                text = "Powered by yt-dlp & FFmpeg",
                color = Color(0xFF4B5563),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(bottom = 24.dp)
            )
        }

        // Top right Cookie Settings Button
        IconButton(
            onClick = { showCookieDialog = true },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 28.dp, end = 20.dp)
                .size(48.dp)
                .background(Color(0x0DFFFFFF), CircleShape)
                .border(1.dp, Color(0x1AFFFFFF), CircleShape)
        ) {
            Text(
                text = "🍪",
                fontSize = 20.sp
            )
        }

        if (showCookieDialog) {
            CookieSettingsDialog(
                onDismiss = { showCookieDialog = false },
                context = context
            )
        }
    }
}

@Composable
fun CookieSettingsDialog(
    onDismiss: () -> Unit,
    context: android.content.Context
) {
    var rawText by remember { mutableStateOf(readCookies(context)) }
    
    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFF0C091A), // Deep obsidian purple
                            Color(0xFF050308)  // Pure dark obsidian
                        )
                    )
                )
                .border(
                    width = 1.dp,
                    brush = Brush.linearGradient(
                        colors = listOf(
                            Color(0x33FFFFFF),
                            Color(0x05FFFFFF)
                        )
                    ),
                    shape = RoundedCornerShape(24.dp)
                )
                .padding(24.dp)
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Cookie Integration",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Paste your Netscape format cookies below to download from restricted platforms like Instagram.",
                    color = Color(0xFF9CA3AF),
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(16.dp))
                
                OutlinedTextField(
                    value = rawText,
                    onValueChange = { rawText = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = Color(0xFFA78BFA),
                        unfocusedBorderColor = Color(0x33FFFFFF),
                        focusedContainerColor = Color(0x0AFFFFFF),
                        unfocusedContainerColor = Color(0x05FFFFFF)
                    ),
                    placeholder = {
                        Text(
                            text = "# Netscape HTTP Cookie File\n.instagram.com\tTRUE\t/\tTRUE\t...",
                            color = Color(0xFF4B5563),
                            fontSize = 12.sp
                        )
                    },
                    shape = RoundedCornerShape(12.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
                
                // Update Downloader Engine Button
                var isUpdating by remember { mutableStateOf(false) }
                val coroutineScope = rememberCoroutineScope()
                
                Button(
                    onClick = {
                        isUpdating = true
                        coroutineScope.launch(Dispatchers.IO) {
                            try {
                                YoutubeDL.getInstance().updateYoutubeDL(context.applicationContext, UpdateChannel.NIGHTLY)
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(context, "Downloader engine updated successfully!", Toast.LENGTH_LONG).show()
                                }
                            } catch (e: Exception) {
                                Log.e("CookieSettingsDialog", "Engine update failed", e)
                                withContext(Dispatchers.Main) {
                                    Toast.makeText(context, "Engine update failed: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                                }
                            } finally {
                                withContext(Dispatchers.Main) {
                                    isUpdating = false
                                }
                            }
                        }
                    },
                    enabled = !isUpdating,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0x33A78BFA),
                        contentColor = Color(0xFFA78BFA)
                    ),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    if (isUpdating) {
                        CircularProgressIndicator(
                            color = Color(0xFFA78BFA),
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Updating Engine...", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    } else {
                        Text("Update Downloader Engine (yt-dlp)", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    TextButton(
                        onClick = {
                            clearCookies(context)
                            rawText = ""
                            Toast.makeText(context, "Cookies cleared", Toast.LENGTH_SHORT).show()
                        },
                        colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFEF4444))
                    ) {
                        Text("Clear", fontWeight = FontWeight.SemiBold)
                    }
                    
                    Row {
                        TextButton(
                            onClick = onDismiss,
                            colors = ButtonDefaults.textButtonColors(contentColor = Color.White)
                        ) {
                            Text("Cancel")
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = {
                                saveCookies(context, rawText)
                                Toast.makeText(context, "Cookies saved", Toast.LENGTH_SHORT).show()
                                onDismiss()
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF8B5CF6),
                                contentColor = Color.White
                            ),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("Save", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AnimatedKhanChuckling(modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "khan_laugh")
    
    val scale by infiniteTransition.animateFloat(
        initialValue = 0.93f,
        targetValue = 1.07f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 350, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )
    
    val bounce by infiniteTransition.animateFloat(
        initialValue = -6f,
        targetValue = 6f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "bounce"
    )

    val rotation by infiniteTransition.animateFloat(
        initialValue = -4f,
        targetValue = 4f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 450, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "rotation"
    )

    Image(
        painter = painterResource(id = R.drawable.khan_chuckling),
        contentDescription = "Chuckling Khan Warlord",
        modifier = modifier
            .size(100.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationY = bounce.dp.toPx()
                rotationZ = rotation
            }
            .clip(CircleShape)
            .border(2.dp, Color(0xFF8B5CF6), CircleShape)
    )
}
