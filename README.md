# AiPBX Android app

<p align="left">
  <a href="https://play.google.com/store/apps/details?id=com.mhrgl.AiPBX" target="_blank" rel="noopener">
    <img src="https://play.google.com/intl/en_us/badges/static/images/badges/en_badge_web_generic.png" alt="Get it on Google Play" height="54">
  </a>
</p>

- **Package:** `com.mhrgl.AiPBX` (debug builds: `com.mhrgl.AiPBX.debug`)
- **Android:** 8.0 (API 26) to 16 (API 36)
- **Version:** this repository’s root `VERSION`; Gradle derives `versionName` and `versionCode` from it
- **Distribution:** Google Play (CI uploads releases to the production track), or the signed
  APK directly (requires release signing)

A native Android client for an AiPBX server: calls (audio and video) over WebRTC, chat, voicemail,
call history and the company directory. It talks only to the user's own PBX; push through Firebase
is optional.

## Screens (`DialerActivity`, five tabs)

1. **Keypad:** dialling, DTMF; the call screen (`CallActivity`) has mute, speaker, hold, transfer,
   camera and a button that hides the controls in a video call. Incoming calls wake the locked screen
   (`IncomingCallActivity`).
2. **History:** the server's call records (`/api/mobile/call_history.php`), filters for all, missed,
   incoming and outgoing; call, video call or chat from a row.
3. **Contacts:** the PBX directory with presence (online/offline) and the device's contacts; company
   extensions (fax lines are not listed) have chat, video call and call buttons, a device contact only
   call. A tap on a row offers the same choices.
4. **Chat:** direct and group chats over the chat service's WebSocket, photos and files, group
   management, and group voice/video conference calls (`POST /chat/api/conversations/call`,
   `call_started`/`call_ended` cards, active-call banner, and dialling `cc<room>` with the built-in
   SIP/WebRTC engine when `features.php` reports `chat_calls: true`).
5. **PBX:** do-not-disturb, call forwarding, voicemail, the in-app log viewer (`LogViewerActivity`)
   for troubleshooting.

## How calls reach the phone

- **SIP engine:** `engine/SipWebRtcEngine.kt` runs JsSIP (`assets/phone_engine.html`,
  `assets/jssip.min.js`) in a headless WebView; it registers as `<extension>-mob-webrtc` over
  `wss://<server>/ws`.
- **Staying connected:** `service/PbxForegroundService.kt` keeps the engine connected in a
  foreground service. A watchdog (every 2 minutes, an `AlarmManager` alarm that also fires in Doze,
  `ResurrectionReceiver`) reconnects when the app has not been registered for 45 s and refreshes the
  registration. Exempting the app from battery optimisation (`util/BatteryPrompt.kt`) makes this
  reliable.
- **Push (optional):** with Firebase configured on the server (*System → Push*),
  `AiPbxFirebaseMessagingService` wakes the app for calls and messages when it is not connected.
  Without it, the foreground service connection is the only path (the default).

## Build

Requirements: Android SDK 36, JDK 17; the Gradle wrapper is included.

```bash
./gradlew testDebugUnitTest lintRelease  # CI unit tests and release lint
./gradlew assembleDebug            # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease          # release APK (unsigned without signing configuration)
./gradlew bundleRelease            # AAB; Play upload requires signing configuration
bin/tls_audit.sh                   # checks that no TLS bypass came back (Play policy)
bin/edge_to_edge_audit.sh          # checks Android 15 edge-to-edge compliance & deprecations
```

## Android 15 (API 35+) Edge-to-Edge Standards

Android 15 enforces edge-to-edge display by default and deprecates explicit bar color and decor fitting APIs. To permanently satisfy Google Play Console policies:

1. **`BaseActivity` Pattern:** All UI screens inherit from `com.mhrgl.aipbx.ui.BaseActivity`.
2. **System-managed edge-to-edge:** Android 15+ handles it for the target SDK; Android 8–14 retains classic bars. Never call AndroidX `enableEdgeToEdge()`, `EdgeToEdge.enable()` or `WindowCompat.enableEdgeToEdge()`; their older-platform code introduces deprecated window APIs into the built app.
3. **No Deprecated APIs:** Never call `Window.setDecorFitsSystemWindows`, `WindowCompat.setDecorFitsSystemWindows`, `setStatusBarColor`, or `setNavigationBarColor`.
4. **Window Insets:** Root layouts must call `applyEdgeToEdgeInsets(rootView, consumeIme = true/false)` to prevent system bars or keyboards from overlapping UI elements.
5. **Continuous verification:** run `EdgeToEdgeComplianceTest`, `bin/edge_to_edge_audit.sh`, and the built-release audit below. Dependencies count as well as app source.

After building a release, audit its APK and R8 mapping:

```bash
bin/check_deprecated_window_apis.sh app/build/outputs/apk/release/app-release-unsigned.apk app/build/outputs/mapping/release/mapping.txt
```

Use `app-release.apk` instead when the build is signed. Release signing reads `keystore.properties`
and `release-key.jks` (both private, not committed). `sdk.dir` goes into `local.properties`.

### Releasing

1. Update this repository's `VERSION` and matching `play-release-notes.json` (`version` must equal
   `versionName`). Version codes are `major * 1,000,000 + minor * 10,000 + patch * 100 + androidBuild`.
   An Android-only rebuild can set `-PandroidBuild=N` (0–99); Play accepts each version code once.
2. Run the unit, lint and built-APK audits above. Configure the signing and Play credentials in CI.
3. Publish a GitHub Release in **this Android repository**, or manually run **Android CI** with
   `upload_to_play=true`. The default track is **production**; a manual run can select another track
   and release status. Pushes and pull requests build/test without uploading to Play.

Publishing an AiPBX server release does not publish the Android app. Missing signing or Play
credentials skip upload; a successful build alone does not mean a store version is available.

Texts live in `app/src/main/res/values*/strings.xml` (23 languages). Release lint checks formatting
arguments; keep the same numbered placeholders and compatible argument types in every language.

## Sign-in and session changes

Browser sign-in callbacks (`aipbx://auth`) always show a confirmation before replacing or creating
an app session. Refresh requests are tied to the server, token and sign-in that made the original
request: a late response cannot overwrite another account or borrow a new account's token.
Signing out invalidates that session; FCM cleanup uses the credentials captured for that sign-out.
Chat attachment and media URLs (`util/MediaUrls.kt`) are resolved against the configured PBX base
URL so the session token is only sent to that PBX.

## Connection troubleshooting

Portal sign-in, chat and SIP registration use different connections. A successful login does not
prove that `/ws` (SIP signaling) and `/chat/ws` (chat) can complete a WebSocket upgrade. A chat error
such as “Too many follow-up requests” warrants checking proxy redirects and authentication rules
for both paths. Another SIP app may use a different transport and does not verify these endpoints.
See the server's [reverse proxy guide](https://github.com/mahirgul/AiPBX/blob/main/docs/reverse-proxy.md)
and [WebRTC/NAT guide](https://github.com/mahirgul/AiPBX/blob/main/docs/webrtc-nat.md).

Use the in-app log viewer for diagnosis; remove tokens, credentials and personal numbers before
sharing a report. Exempt the app from battery optimisation if background registration repeatedly stops.

## Layout

```
AiPBX-Android/
├── app/build.gradle.kts            # version calculation, SDK levels, dependencies
├── app/src/main/
│   ├── AndroidManifest.xml         # permissions, services, deep links aipbx://login and aipbx://auth
│   ├── assets/phone_engine.html    # JsSIP engine (WebView)
│   └── java/com/mhrgl/aipbx/
│       ├── data/                   # ApiClient (REST), AppPreferences, ChatWebSocketManager
│       ├── engine/                 # SipWebRtcEngine (WebView bridge to JsSIP)
│       ├── model/                  # data classes
│       ├── service/                # PbxForegroundService, push, notifications, boot, watchdog
│       ├── ui/                     # activities and adapters
│       └── util/                   # logging, battery/full-screen prompts, search, L10n
├── app/src/test/                   # unit tests
├── bin/tls_audit.sh                # TLS bypass check
├── play-release-notes.json         # Play "What's new" text per version
├── server_api/                     # old copy of the mobile API; the live one is web/api/mobile/
└── *.py, *.png                     # Play store screenshots and graphics
```

## ☁️ Cloud Infrastructure Partner

[![UpCloud](https://img.shields.io/badge/UpCloud-$250_Free_Credit-7b1fa2?style=flat&logo=cloud&logoColor=white)](https://signup.upcloud.com/?promo=affiliate-aipbx250&amp;utm_source=aipbx&amp;utm_medium=affiliate&amp;utm_campaign=affiliate-aipbx250)

Host your own private [AiPBX](https://github.com/mahirgul/AiPBX) server on **[UpCloud](https://signup.upcloud.com/?promo=affiliate-aipbx250&amp;utm_source=aipbx&amp;utm_medium=affiliate&amp;utm_campaign=affiliate-aipbx250)** with high-speed MaxIOPS block storage. Get **$250 in free cloud credits** with promo code `affiliate-aipbx250`.

