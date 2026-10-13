package com.nuvio.app.debug

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.nuvio.app.core.ui.NuvioPosterCard
import com.nuvio.app.core.ui.NuvioPosterShape
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.core.ui.configurePlatformImageLoader
import com.nuvio.app.core.poster.CustomPosterFallbackInterceptor
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.svg.SvgDecoder
import com.nuvio.app.features.player.*
import com.nuvio.app.features.watched.*
import com.nuvio.app.features.player.desktop.preloadNativePlayerBridgeAsync
import com.nuvio.app.features.player.desktop.NativePlayerController
import com.nuvio.debug.*
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.serialization.json.*
import kotlinx.serialization.encodeToString
import org.jetbrains.skia.EncodedImageFormat

/** Explicit debug entry point: no account, sync, scrobbling or telemetry startup. */
@OptIn(ExperimentalComposeUiApi::class)
fun runDesktopDebugCli(args: Array<String>) {
    val index = args.indexOf("--debug-cli")
    require(index >= 0 && index + 1 < args.size) { "--debug-cli requires a session directory" }
    val directory = File(args[index + 1]).absoluteFile.apply { mkdirs() }
    System.setProperty("nuvio.debug.dataDir", File(directory, "data").absolutePath)
    SingletonImageLoader.setSafe { context ->
        ImageLoader.Builder(context).components {
            add(CustomPosterFallbackInterceptor())
            add(SvgDecoder.Factory())
            add(coil3.network.ktor3.KtorNetworkFetcherFactory())
        }.configurePlatformImageLoader().build()
    }
    preloadNativePlayerBridgeAsync()
    var url by mutableStateOf<String?>(null)
    var headers by mutableStateOf<Map<String, String>>(emptyMap())
    var revision by mutableStateOf(0)
    var controller: PlayerEngineController? = null
    var snapshot = PlayerPlaybackSnapshot(isLoading = false)
    var playerError = false
    var muted = true
    var previewScene: ImageComposeScene? = null
    var previewFrames = 0
    var exit by mutableStateOf(false)
    lateinit var server: DebugCommandServer

    fun historyLookup(query: JsonObject) = WatchedRepository.isWatched(
        id = query["contentId"]!!.jsonPrimitive.content,
        type = query["contentType"]!!.jsonPrimitive.content,
        season = query["season"]?.jsonPrimitive?.intOrNull,
        episode = query["episode"]?.jsonPrimitive?.intOrNull,
    )

    fun status() = buildJsonObject {
        put("ok", true); put("platform", "desktop"); put("engine", "native-mpv")
        put("mode", if (url == null) "idle" else "player")
        put("controllerReady", (controller as? NativePlayerController)?.isDebugReady() == true)
        put("playerError", playerError)
        put("isLoading", snapshot.isLoading); put("isPlaying", snapshot.isPlaying)
        put("isEnded", snapshot.isEnded); put("positionMs", snapshot.positionMs)
        put("durationMs", snapshot.durationMs); put("bufferedPositionMs", snapshot.bufferedPositionMs)
        put("videoWidth", snapshot.videoWidth); put("videoHeight", snapshot.videoHeight)
        put("playbackSpeed", snapshot.playbackSpeed); put("previewFrames", previewFrames)
        put("audioTracks", buildJsonArray { controller?.getAudioTracks()?.forEachIndexed { i, t ->
            add(buildJsonObject { put("index", i); put("description", t.toString()) })
        } })
        put("subtitleTracks", buildJsonArray { controller?.getSubtitleTracks()?.forEachIndexed { i, t ->
            add(buildJsonObject { put("index", i); put("description", t.toString()) })
        } })
    }

    server = DebugCommandServer(File(directory, "session.json")) { request ->
        val result = CompletableFuture<JsonObject>()
        SwingUtilities.invokeLater {
            if (result.isCancelled) return@invokeLater
            result.complete(runCatching {
                fun text(key: String) = request[key]?.jsonPrimitive?.content ?: error("missing-field")
                val command = text("command")
                when (command) {
                    "status" -> status()
                    "history" -> {
                        // Only the isolated debug session's disk store is touched.
                        request["entries"]?.jsonArray?.let { entries ->
                            require(entries.size <= 100)
                            val items = entries.map { value ->
                                val entry = value.jsonObject
                                val id = entry["contentId"]!!.jsonPrimitive.content
                                val type = entry["contentType"]!!.jsonPrimitive.content
                                require(Regex("(?:tt\\d{5,12}|tmdb:[1-9]\\d*)").matches(id))
                                require(type in listOf("movie", "series"))
                                WatchedItem(id = id, type = type, name = "Debug history",
                                    season = entry["season"]?.jsonPrimitive?.intOrNull,
                                    episode = entry["episode"]?.jsonPrimitive?.intOrNull,
                                    markedAtEpochMs = entry["watchedAtMs"]!!.jsonPrimitive.long.also { require(it > 0) })
                            }
                            WatchedStorage.savePayload(1, Json.encodeToString(StoredWatchedPayload(items = items)))
                            WatchedRepository.clearLocalState()
                            WatchedRepository.onProfileChanged(1)
                        }
                        val queries = request["queries"]?.jsonArray ?: JsonArray(emptyList())
                        require(queries.size <= 10)
                        buildJsonObject {
                            put("ok", true); put("scope", "local-debug-only")
                            put("matches", buildJsonArray { queries.forEach { add(historyLookup(it.jsonObject)) } })
                            put("timestamps", buildJsonArray { WatchedRepository.uiState.value.items.forEach { add(it.markedAtEpochMs) } })
                        }
                    }
                    "load" -> {
                        val source = text("url")
                        require(java.net.URI(source).scheme in listOf("http", "https", "file"))
                        headers = request["headers"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content } ?: emptyMap()
                        controller = null; snapshot = PlayerPlaybackSnapshot(); playerError = false
                        url = source; revision++; debugOk()
                    }
                    "stop" -> { url = null; controller = null; snapshot = PlayerPlaybackSnapshot(isLoading = false); debugOk() }
                    "play", "pause", "seek", "speed", "audio", "subtitle", "mute" -> {
                        val player = controller as? NativePlayerController ?: error("player-not-ready")
                        require(player.isDebugReady())
                        when (command) {
                            "play" -> player.play()
                            "pause" -> player.pause()
                            "seek" -> player.seekTo(text("positionMs").toLong().also { require(it >= 0) })
                            "speed" -> player.setPlaybackSpeed(text("speed").toFloat().also { require(it in 0.25f..4f) })
                            "audio" -> player.selectAudioTrack(text("index").toInt().also { require(it in player.getAudioTracks().indices) })
                            "subtitle" -> player.selectSubtitleTrack(text("index").toInt().also { require(it == -1 || it in player.getSubtitleTracks().indices) })
                            "mute" -> {
                                muted = text("muted").toBooleanStrict()
                                player.setDebugMuted(muted)
                            }
                        }
                        debugOk()
                    }
                    "preview" -> {
                        val imageUrl = text("url")
                        require(java.net.URI(imageUrl).scheme in listOf("http", "https", "file"))
                        val width = request["width"]?.jsonPrimitive?.int ?: 320
                        val height = request["height"]?.jsonPrimitive?.int ?: 540
                        require(width in 32..4096 && height in 32..4096)
                        val shape = when (request["shape"]?.jsonPrimitive?.content ?: "poster") {
                            "poster" -> NuvioPosterShape.Poster
                            "landscape" -> NuvioPosterShape.Landscape
                            "square" -> NuvioPosterShape.Square
                            else -> error("invalid-shape")
                        }
                        val watched = request["lookup"]?.jsonObject?.let(::historyLookup)
                            ?: (request["watched"]?.jsonPrimitive?.boolean ?: false)
                        val baseWidth = if (shape == NuvioPosterShape.Landscape) kotlin.math.floor(width * 110.0 / 180.0).toInt() else width
                        previewScene?.close(); previewFrames = 0
                        previewScene = ImageComposeScene(width, height, coroutineContext = Dispatchers.Main) {
                            NuvioTheme {
                                Box(Modifier.fillMaxSize().background(Color(0xff0d0d0d))) {
                                    NuvioPosterCard(title = "Debug preview", imageUrl = imageUrl,
                                        basePosterWidthDp = baseWidth, shape = shape, isWatched = watched)
                                }
                            }
                        }
                        buildJsonObject { put("ok", true); put("watched", watched); put("historyLookup", request.containsKey("lookup")) }
                    }
                    "render" -> {
                        val scene = previewScene ?: error("preview-not-ready")
                        val output = File(directory, "preview.png")
                        scene.render(System.nanoTime()).use { frame ->
                            frame.encodeToData(EncodedImageFormat.PNG)!!.use { output.writeBytes(it.bytes) }
                        }
                        buildJsonObject { put("ok", true); put("file", output.absolutePath); put("frames", previewFrames) }
                    }
                    "capture" -> {
                        val player = controller as? NativePlayerController ?: error("player-not-ready")
                        val output = File(directory, "video-frame.png")
                        require(player.captureDebugFrame(output.absolutePath))
                        buildJsonObject { put("ok", true); put("file", output.absolutePath) }
                    }
                    "shutdown" -> {
                        javax.swing.Timer(300) { exit = true }.apply { isRepeats = false; start() }
                        debugOk()
                    }
                    else -> debugError("unknown-command")
                }
            }.getOrElse { debugError("command-failed") })
        }
        runCatching { result.get(10, TimeUnit.SECONDS) }.getOrElse {
            result.cancel(false)
            debugError("command-timeout")
        }
    }

    application {
        LaunchedEffect(exit) { if (exit) { previewScene?.close(); server.close(); exitApplication() } }
        LaunchedEffect(Unit) {
            while (true) {
                previewScene?.render(System.nanoTime())?.close()
                if (previewScene != null) previewFrames++
                delay(50)
            }
        }
        val state = rememberWindowState(width = 960.dp, height = 540.dp,
            position = if ("--background" in args) WindowPosition.Absolute((-32000).dp, (-32000).dp) else WindowPosition.PlatformDefault)
        Window(onCloseRequest = { exit = true }, title = "Nuvio Desktop Debug CLI", state = state,
            focusable = "--background" !in args) {
            NuvioTheme {
                val source = url
                if (source != null) key(revision) {
                    PlatformPlayerSurface(sourceUrl = source, sourceHeaders = headers, modifier = Modifier.fillMaxSize(),
                        onControllerReady = { controller = it; (it as NativePlayerController).setDebugMuted(muted) },
                        onSnapshot = { snapshot = it }, onError = { playerError = it != null })
                }
            }
        }
    }
}
