# Nuvio Desktop debug CLI

This fork adds an opt-in diagnostic session using the real desktop native MPV
player and `NuvioPosterCard`. It runs independently of the installed Nuvio app.
The ordinary start does not expose a command server.

Requirements: PowerShell 7, JDK 17 or newer, and the normal upstream Windows
build prerequisites (Visual Studio C++ tools, WebView2 SDK and bundled MPV).
Set `JAVA_HOME`; use the repository Gradle wrapper.

```powershell
./scripts/debug-desktop.ps1 -Action start -Build
./scripts/debug-desktop.ps1 -Command status
```

The default session directory is `.debug-cli`, which is ignored by Git.
The app owns a non-focusing window outside the desktop. This is background
playback, not a headless/native-decoder replacement. `-Visible` shows the debug
window. `-SessionDirectory` starts independent sessions with isolated preferences
and caches. No login, account sync, remote history writes or telemetry are started.

Use private JSON files to pass media URLs and optional request headers, so signed
URLs and credentials are not put on the process command line:

```json
{"url":"file:///D:/media/test.mp4","headers":{}}
```

```powershell
./scripts/debug-desktop.ps1 -Command load -RequestFile ./media-request.json
./scripts/debug-desktop.ps1 -Command pause
./scripts/debug-desktop.ps1 -Command seek -PositionMs 10000
./scripts/debug-desktop.ps1 -Command speed -Speed 1.5
./scripts/debug-desktop.ps1 -Command play
./scripts/debug-desktop.ps1 -Command capture
./scripts/debug-desktop.ps1 -Command audio -Index 0
./scripts/debug-desktop.ps1 -Command subtitle -Index -1
./scripts/debug-desktop.ps1 -Command mute -Muted $false
```

Playback is muted initially. `status` reports live loading/playback/end state,
position, duration, buffering, speed, errors and audio/subtitle tracks. Desktop
video dimensions are currently unavailable from the native controller and return
zero. State changes are asynchronous; poll status rather than treating an accepted
command as proof of playback. Seeking uses the player's existing keyframe behavior.
On Windows, `capture` exports the decoded native MPV video frame to
`video-frame.png`, without capturing the OS screen.
`stop` releases the current player, and `shutdown` closes the diagnostic session.

Poster preview request:

`history` accepts a private JSON file with up to 100 `entries` containing
`contentId`, `contentType`, nullable `season`/`episode` and `watchedAtMs`.
Supplying entries replaces only this isolated session's local watched store,
preserving the supplied dates. Up to 10 `queries` use the same identity fields
without the date. The response contains real `WatchedRepository.isWatched`
matches and loaded timestamps. This replays client matching; it does not test
cloud delivery or series completion. `preview` may use a `lookup` query instead
of the explicit `watched` flag to render the badge from that real matcher.

```json
{"url":"https://example.org/poster.png","shape":"poster","width":320,"height":540,"watched":true}
```

```powershell
./scripts/debug-desktop.ps1 -Command preview -RequestFile ./poster-request.json
./scripts/debug-desktop.ps1 -Command render
./scripts/debug-desktop.ps1 -Command shutdown
```

Shapes are `poster`, `landscape` and `square`. Rendering exports
`.debug-cli/preview.png` from an offscreen Compose scene using the real card,
image loader and watched badge. It does not capture the OS screen or the native
video surface. The `watched` input is an explicit preview fixture; it is not
evidence that a real account's history matches a title. Network images load
asynchronously; render again after loading or inspect the output.

The protocol is bounded newline-delimited JSON over localhost TCP. The client
reads a random per-session token from `session.json`; tokens, URLs and headers
are omitted from normal JSON status. Keep session/request files private. Commands
run on the UI thread, without pointer, keyboard or computer-control automation.
Never expose or forward the port to a network interface.
