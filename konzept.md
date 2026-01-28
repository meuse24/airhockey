# Android-Spiel – Konzeptcanvas

## 1. Grundidee (High Concept)
Ein ultraschnelles Echtzeit-Duell, bei dem zwei Smartphones über Wi-Fi Direct ein unsichtbares Spielfeld aufspannen und ohne Internet-Lag direkt gegeneinander antreten.

- Arbeitstitel: **AirHockey P2P**
- Genre: **2D Arcade / Multiplayer**
- Plattform: **Android (API 29+)**
- Zielgefühl: **schnell, reaktionsstark, kompetitiv**
- Ausrichtung: **Portrait (Hochkant) mit dynamischem Landscape-Support**
- High Concept: **Zwei Spieler spielen klassisches Airhockey auf ihren eigenen Smartphones. Peer-to-Peer-Verbindung sorgt für minimale Latenz.**

---

## 2. Zielgruppe
- Alter: **ca. 10+**
- Spielertyp: **Casual bis Core (kurze Sessions, schnelle Duelle)**
- Nutzungskontext: **unterwegs / Couch-Coop ohne Router**

---

## 3. Core Gameplay Loop
1. **P2P-Verbindung herstellen** (Discovery & Handshake)
2. **Match starten** – beide Spieler bestätigen Start (Ready-Handshake)
3. **Pusher steuern**, Puk abwehren und angreifen
4. **7-Sekunden-Regel:** Puk darf nur begrenzte Zeit im eigenen Feld bleiben
5. **Tor / Punkt** → Reset
6. **Matchende (Best of 5)** → Rematch oder Disconnect

---

## 4. Spielmechaniken (Umgesetzt)
- **Multiplayer-Sync**: Unreliable UDP-Datenübertragung für Game-Frames (Echtzeit-Positionen)
- **Verbindungs-Management**: Automatisches Finden und Verbinden von Peers
- **Echtzeit-Stats**: Überwachung von Ping (RTT), Paketanzahl und Datenvolumen
- **Robustheit**: Die Verbindung überlebt Orientierungswechsel und System-Events (Lifecycle Proof)
- **Rollen-Handshake**: Host = PLAYER1, Client = PLAYER2, verifizierter Handshake vor Spielstart
- **Start-Game-Sync**: Beide Spieler bestätigen Start/Retry; Match beginnt erst bei beidseitiger Bestätigung
- **HUD**: Anzeige der eigenen Rolle (PLAYER 1 / PLAYER 2), Netzwerkstatus, Score
- **Test-Puck (Box2D)**: Spawn per HUD-Button (Double-Tap deaktiviert)
- **Puck-Sync (Host → Client)**: Host sendet regelmäßige Positions-/Velocity-Updates
- **Scoring (Best of 5)**: Tor-Overlay (3s) + Game-Over-Overlay mit Retry/Quit
- **Synthetisches Audio**: Realtime-PCM-Beep-Sounds für Wand, Pusher und Tor
- **Rückkehr-Sync**: „Back“-Signal wird an beide Geräte verteilt (Lobby-Wechsel)

**Wichtig:** Physik-Synchronisation ist aktuell nur als **Test-Puck-Spawn** umgesetzt (kein permanenter State-Abgleich).

---

## 8. Technische Umsetzung (Aktueller Stand)

### Netzwerk-Architektur (Bulletproof-Implementierung)
**Ziel:** Maximale Stabilität bei minimaler Latenz.

**Transport-Schicht:**
- **UDP (Custom Protocol)**: Eigenes latenzoptimiertes Protokoll auf Basis von DatagramSockets.
- **Actor Model**: Dedizierte Sende-Channels mit Backpressure-Handling (`DROP_OLDEST`), um Lag-Anhäufung zu vermeiden.
- **Security & Sanity**: Magic-Byte Header (`0x42`) zur Identifikation valider Pakete.
- **Sequencing**: Sequenznummern zur Eliminierung veralteter oder out-of-order Pakete.

**Framework & State:**
- **Wi-Fi Direct (P2P)**: Nutzung des Android `WifiP2pManager` für routerlose Direktverbindungen.
- **State Machine**: Klare Trennung der Zustände (IDLE, SCANNING, CONNECTING, CONNECTED_HOST, CONNECTED_CLIENT, DISCONNECTED, ERROR).
- **NetworkViewModel**: Architektur-Komponente, die den Netzwerk-Stack über Konfigurationsänderungen (z.B. Displaydrehung) hinweg stabil hält.
- **Atomic Statistics**: Thread-sichere Erfassung von Bytes und Paketen in Echtzeit.

### Netzwerk-Stacks (Implementierung - Zusammenfassung)
**Ziel:** Minimal-Latenz fuer Echtzeitdaten, aber sichere Zustellung fuer kritische Events.

**Wi-Fi Direct Control-Stack:**
- Discovery/Connect ueber `WifiP2pManager` mit State-Tracking und Fehlermeldung (`ERROR`).
- Automatisches Recovery: Re-Discovery bei IDLE/DISCONNECTED, manuelle Suche bei ERROR moeglich.
- BroadcastReceiver fuer Peer-Updates und Connection-Changes; sauberes Release im ViewModel.

**UDP Game-Data Stack (Low Latency):**
- DatagramSocket mit festen MTU-Limits (MTU 1400, Header 6 Byte, Payload strikt begrenzt).
- Paketformat: Magic-Byte `0x42`, MessageType, Sequenznummer, Payload.
- Sequencing/Out-of-Order-Filter fuer Game-Frames; veraltete Frames werden gedroppt.
- Backpressure ueber Channel (`DROP_OLDEST`) zur Vermeidung von Lag-Aufstau.
- Ping/Pong fuer RTT-Anzeige; Bytes/Packets/Overruns als Live-Stats.

**UDP Critical-Events Stack (Reliable Mini-Layer):**
- MessageType `CRITICAL_EVENT` + `ACK` mit `eventId` und Payload.
- Sender haelt Pending-Queue und resend alle 250 ms, max 8 Versuche.
- Empfaenger dedupliziert per `eventId` und bestaetigt mit ACK.
- UI-Stati: `lastAckedEventId`, `lastReceivedCriticalEventId`, `pendingCriticalCount`.
- **Wichtig:** Event-Handler müssen idempotent sein - gleiche eventId darf nicht zweimal verarbeitet werden (dedupliziert auf Netzwerk-Ebene, aber Handler sollten defensiv implementiert werden).

**Game Signals (Critical Events):**
- **ROLE_REQUEST / ROLE_ASSIGN / ROLE_CONFIRM / ROLE_CONFIRMED**: Verifizierter Rollen-Handshake (Host=PLAYER1, Client=PLAYER2).
- **START_GAME**: Ready-Signal (Start/Retry) – Match beginnt erst bei beidseitiger Bestätigung.
- **PUCK_REQUEST / PUCK_SPAWN**: Host-Authoritative Test-Puck-Spawn mit Winkel + Geschwindigkeit.
- **GOAL_SCORED**: Tor-Event + synchroner Score-Abgleich.
- **RETURN_TO_LOBBY**: Synchroner Rücksprung in die Lobby.

### Physik (Phase 2 – aktueller Teststand)
- **Box2D World** im `GameScreen` (0-G-Topdown).
- **Fixed Time Step** (1/60s) mit Accumulator, Begrenzung auf max 5 Steps/Frame.
- **Puck Body**: DynamicBody (CircleShape), hohe Restitution, geringe Dämpfung.
- **Walls**: Statische Edges mit Tor-Öffnungen + Goal-Sensoren hinter der Torlinie.
- **Input**: Spawn per HUD-Button; Back/ESC kehrt zur Lobby zurück.
- **Puck-Sync**: Host sendet regelmäßige Syncs (Position + Velocity) per Game-Data.
- **Viewport/Feld**: Physik arbeitet in einer festen World-Größe (2:1), Rendering skaliert pro Gerät → konsistentes Abprallverhalten auf unterschiedlichen Auflösungen.
- **Sync-Frequenz (Test)**: Aktuell alle ~100 ms ein Host-Sync (Position/Velocity).
- **HUD**: „Back“-Button sendet Rückkehr-Signal an beide Geräte; „Spawn Puck“-Button platziert den Puck; Netzwerk-Statusanzeige im HUD.
- **Start-Overlay**: „START MATCH“ mit Ready-Handshake; Eingaben/Sync werden erst in PLAYING aktiv.
- **Game-Over**: Best-of-5 mit Overlay (Retry/Quit).

### Berechtigungen (Android 13+ Ready)
- `NEARBY_WIFI_DEVICES` (mit `neverForLocation` Flag)
- `ACCESS_FINE_LOCATION` (für Legacy Support)
- `ACCESS_COARSE_LOCATION` (für Android <13)
- `ACCESS_NETWORK_STATE` & `ACCESS_WIFI_STATE`
- `CHANGE_WIFI_STATE`
- `INTERNET`

---

## 9. USP – Warum dieses Spiel?
- **Echtes 2‑Player Airhockey auf zwei Geräten** (jeder hat sein eigenes Smartphone als Controller/Anzeige).
- **Enterprise-Grade Network Stack**: Ultra-robuste UDP-Verbindung, die speziell für High-Speed-Physik optimiert ist.
- **Zero Configuration**: App öffnen → Suchen → Spielen. Kein Account, kein Internet nötig.

---

## 10. Nächste Schritte (Roadmap)
- [x] **Phase 1: Netzwerk-Härtung** (Erfolgreich abgeschlossen: Bulletproof UDP Stack steht).
- [x] **Phase 1.5: Build & Deployment** (Erfolgreich abgeschlossen: APK läuft stabil auf Android 12+).
- [ ] **Phase 2: Spiel-Physik Integration** (In Arbeit: Box2D + Test-Puck + Fixed Step).
- [ ] **Phase 3: Host-Authoritative Simulation** (Kontinuierliche Puk-Synchronisation vom Host zum Client).
- [ ] **Phase 4: UI/UX Polishing** (Minimalistischer Arcade-Look).

---

## 11. Build & Deployment (2026-01-28)

### Gelöste technische Probleme

#### 1. LibGDX Native Libraries Extraktion
**Problem:** `copyAndroidNatives` Task schlug fehl beim Entpacken der nativen `.so` Dateien.
```
Cannot expand ZIP 'gdx-platform-1.13.0-natives-armeabi-v7a.jar'
```

**Ursache:**
- Gradle 9.1 Configuration Cache Inkompatibilität
- Pfad-Handling unter Windows mit Backslashes
- ABI-Verzeichnisse wurden nicht korrekt extrahiert

**Lösung:**
```kotlin
val copyAndroidNatives by tasks.registering(Copy::class) {
    for (file in natives.files) {
        val abiMatch = Regex("""natives-([^.]+)\.jar""").find(file.name)
        if (abiMatch != null) {
            val abi = abiMatch.groupValues[1]
            from(zipTree(file)) {
                include("**/libgdx.so")
                into(abi)
            }
        }
    }
    includeEmptyDirs = false
    into(layout.buildDirectory.dir("natives"))
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
}
```
- ABI-Namen werden aus JAR-Dateinamen extrahiert (armeabi-v7a, arm64-v8a, x86, x86_64)
- Jede native Library wird in das korrekte ABI-Unterverzeichnis kopiert

#### 2. Deprecation-Warnungen (Android 13+ API Changes)
**Problem:** Veraltete APIs in NetworkModule.kt und MainActivity.kt
- `Divider()` → deprecated
- `getParcelableExtra()` → deprecated
- `NetworkInfo` class → deprecated

**Lösung:**
```kotlin
// UI Update
HorizontalDivider() statt Divider()

// API Level Check für getParcelableExtra
@Suppress("DEPRECATION")
val networkInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
    intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO, NetworkInfo::class.java)
} else {
    intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO)
}

// File-Level Suppression für unvermeidbare Deprecations
@file:Suppress("DEPRECATION")
```

#### 3. LibGDX UI Crash beim Start
**Problem:** `IllegalArgumentException: Missing LabelStyle font`

**Ursache:** LibGDX-Ressourcen (BitmapFont, Texture) wurden vor GL-Thread-Initialisierung erstellt.

**Lösung:** Lazy Initialization Pattern
```kotlin
object SimpleUi : Disposable {
    private var _skin: Skin? = null

    val skin: Skin
        get() {
            if (_skin == null) {
                initialize()
            }
            return _skin!!
        }

    private fun initialize() {
        _font = BitmapFont().apply {
            data.setScale(3f)  // Skalierung für High-DPI Displays
        }
        // ... Skin-Initialisierung
    }
}
```

#### 4. Fehlende ScrollPaneStyle
**Problem:** `GdxRuntimeException: No ScrollPane$ScrollPaneStyle registered`

**Lösung:** Style zu SimpleUi.skin hinzugefügt:
```kotlin
val scrollPaneStyle = ScrollPane.ScrollPaneStyle().apply {
    background = TextureRegionDrawable(_pixel).tint(Color(0.1f, 0.1f, 0.1f, 0.5f))
    vScroll = TextureRegionDrawable(_pixel).tint(Color(0.3f, 0.3f, 0.3f, 1f))
    vScrollKnob = TextureRegionDrawable(_pixel).tint(Color(0.6f, 0.6f, 0.6f, 1f))
}
add("default", scrollPaneStyle)
```

#### 5. Unlesbarer Text auf High-DPI Displays
**Problem:** Text erschien winzig klein auf 1080x2280 Display (480 DPI).

**Lösung:** Font-Skalierung für Android:
```kotlin
_font = BitmapFont().apply {
    data.setScale(3f)  // 3x Vergrößerung für moderne Displays
}
```

#### 6. Wi-Fi Direct Peer Discovery Issues
**Problem:** Keine Peers gefunden beim Scanning.

**Lösung:**
- Automatische Peer-Discovery nach Initialisierung (500ms delay)
- Umfangreiches Debug-Logging für alle Wi-Fi P2P Events
- Wi-Fi P2P State-Check (enabled/disabled)
- Bessere Fehlermeldungen mit Hinweisen auf Wi-Fi/Standort

```kotlin
override fun initialize() {
    registerReceiver()
    if (hasP2pPermissions()) {
        p2pManager.removeGroup(channel, null)
        scope.launch {
            delay(500)
            discoverPeers()  // Auto-start
        }
    }
}
```

**Checkliste für erfolgreiche Peer Discovery:**
- ✅ Wi-Fi aktiviert (nicht nur mobile Daten)
- ✅ Standortdienste aktiviert (Android-Anforderung)
- ✅ Berechtigungen erteilt (NEARBY_WIFI_DEVICES, ACCESS_FINE_LOCATION)
- ✅ Zweites Gerät mit gleicher App läuft

#### 7. UI/UX Verbesserungen
**Problem:** Buttons zu klein für Touch-Bedienung.

**Lösung:**
- Hauptbuttons: 400x80 Pixel (vorher 220 Pixel breit)
- Peer-Buttons: Volle Breite x 70 Pixel
- ScrollPane: 300 Pixel hoch
- Disconnect-Button hinzugefügt für bessere Kontrolle

### Android Manifest (Final)
```xml
<uses-permission android:name="android.permission.NEARBY_WIFI_DEVICES"
    android:usesPermissionFlags="neverForLocation" />
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
<uses-permission android:name="android.permission.CHANGE_WIFI_STATE" />
<uses-permission android:name="android.permission.INTERNET" />
```

### Build-Konfiguration
- **Gradle:** 9.1.0
- **Android Gradle Plugin (AGP):** 9.0.0
- **Kotlin:** 2.0.21
- **LibGDX:** 1.13.0
- **LibGDX Box2D:** 1.13.0
- **Min SDK:** 29 (Android 10)
- **Target SDK:** 36 (Android 14)
- **Compile SDK:** 36

**Notiz:** AGP 9.0.0 mit Gradle 9.1.0 ist eine getestete, stabile Kombination (Stand Januar 2026). AGP-Versionierung ist seit Version 7.x von Gradle-Versionen entkoppelt.

### Erfolgreiche Deployment-Verifizierung
- ✅ Build ohne Fehler oder Warnungen
- ✅ APK startet ohne Crashes
- ✅ UI gut lesbar auf High-DPI Displays
- ✅ Wi-Fi Direct Peer Discovery funktioniert
- ✅ Verbindungsaufbau erfolgreich
- ✅ UDP-Transport läuft stabil
- ✅ Stats werden korrekt angezeigt (RTT, Bytes/s, Packet Loss)
- ✅ Critical Events mit ACK-System funktionieren

### Bekannte Einschränkungen & Technische Risiken

**Netzwerk:**
- Wi-Fi Direct benötigt aktives Wi-Fi (nicht nur mobile Daten)
- Android 10+ erfordert aktivierte Standortdienste für Wi-Fi Scanning
- Geräte müssen Wi-Fi Direct unterstützen (die meisten Android-Geräte seit 4.0+)
- `p2pManager.removeGroup()` beim App-Start beendet evtl. andere aktive P2P-Sessions auf dem Gerät (UX-Risiko)

**Spiel-Synchronisation (noch nicht implementiert):**
- **Clock Skew/Drift:** Keine Kompensation für unterschiedliche Geräte-Uhren implementiert. Bei reiner Client-Side Prediction ohne Server-Authoritative Physik besteht Desync-Risiko.
- **Tick-Rate:** Aktuell nur RTT-Messung via Ping/Pong, keine feste Tick-Rate mit Korrekturmechanismus.
- **Physik-Synchronisation:** Aktuell nur **Puck-Spawn** synchronisiert (kein kontinuierlicher State-Stream). Host-Authoritative Ansatz für permanente Sync geplant.
- **Event-Idempotenz:** Critical Events werden auf Netzwerk-Ebene dedupliziert (`eventId`), aber Spiel-Event-Handler (z.B. „Tor", „Matchende") müssen zusätzlich defensiv gegen Doppelausführung implementiert werden.

**Geplante Lösung (Phase 3):**
- Host-Authoritative Simulation: Der Host berechnet Physik, Client sendet nur Eingaben
- Client-Side Prediction mit Server-Reconciliation für responsives Gefühl
- Deterministische Physik-Engine (gleicher Input → gleicher Output) als Fallback
