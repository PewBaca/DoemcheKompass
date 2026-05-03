# 🏛️ Dömche-Kompass

> **"Dä Dom es immer noh hüm!"** – Kölsch Navigation App für Android

Eine native Android App in Kotlin, die jederzeit die Richtung und Entfernung zum Kölner Dom anzeigt.
Mit echtem Kompass (Magnetfeldsensor), GPS und Live-Webcam-Link.

---

## ✨ Features

| Feature | Beschreibung |
|---|---|
| 🧭 **Echtzeit-Kompass** | Magnetfeldsensor + Accelerometer – zeigt immer auf den Dom |
| 📍 **GPS-Entfernung** | Präzise Entfernung zum Kölner Dom in km oder Metern |
| 🗼 **Dom-Nadel** | Gotischer Kirchturm-Pfeil dreht sich sanft animiert |
| 📡 **Live-Webcam** | Link zur SkylineWebcams Dom-Kamera |
| 🎨 **KI-Logo** | Vector Drawable – Kölner Dom Silhouette als App-Icon |
| 🗣️ **Kölsch UI** | Alle Texte im Kölner Dialekt |

---

## 🛠️ Setup in Android Studio

### Voraussetzungen
- Android Studio **Hedgehog** oder neuer (2023.1.1+)
- JDK **17**
- Android SDK **34**
- Gradle **8.4** (wird automatisch heruntergeladen)

### Projekt öffnen
1. Android Studio starten
2. **File → Open** → diesen Ordner `DoemcheKompass/` auswählen
3. Gradle sync abwarten (Internet erforderlich beim ersten Mal)
4. Gerät oder Emulator auswählen → ▶ **Run**

### Build für Play Store (Release APK / AAB)

```bash
# In Android Studio:
Build → Generate Signed Bundle / APK

# Oder per Kommandozeile:
./gradlew bundleRelease      # → AAB für Play Store (empfohlen)
./gradlew assembleRelease    # → APK
```

**Signing:** Beim ersten Release-Build einen neuen Keystore anlegen:
- `Build → Generate Signed Bundle / APK → Create new...`
- Keystore sicher aufbewahren! Ohne ihn können keine Updates veröffentlicht werden.

---

## 📱 Play Store Upload

1. [Google Play Console](https://play.google.com/console) öffnen
2. **App erstellen** → App-Name: *Dömche-Kompass*
3. Inhaltsbewertung ausfüllen → Kategorie: **Navigation**
4. Unter **Releases → Production → Create release** die `.aab` Datei hochladen
5. Store-Eintrag ausfüllen (Screenshots, Beschreibung auf Deutsch & Englisch)
6. **Veröffentlichen** 🎉

### Empfohlene Store-Beschreibung (DE)
> Der Dömche-Kompass zeigt dir jederzeit die Richtung und Entfernung zum Kölner Dom –
> egal wo du auf der Welt bist! Mit echtem Magnetkompass, GPS und Live-Webcam.
> Voll op Kölsch! 🏛️

---

## 🔧 Technische Details

### Kompass-Berechnung
```kotlin
// Azimuth vom Gerät (SensorManager)
SensorManager.getRotationMatrix(rotationMat, null, accelReading, magReading)
SensorManager.getOrientation(rotationMat, orientation)
val azimuth = Math.toDegrees(orientation[0].toDouble())  // 0° = Nord

// Bearing zum Dom (Haversine)
val bearing = atan2(sin(Δλ)*cos(φ2), cos(φ1)*sin(φ2) - sin(φ1)*cos(φ2)*cos(Δλ))

// Nadel-Rotation
val needleAngle = bearingToDom - deviceAzimuth
```

### Permissions
| Permission | Verwendung |
|---|---|
| `ACCESS_FINE_LOCATION` | GPS-Koordinaten für Entfernungsberechnung |
| `ACCESS_COARSE_LOCATION` | Fallback wenn GPS nicht verfügbar |
| `INTERNET` | Webcam-Link öffnen |

### Kompass-Sensor
- `TYPE_ACCELEROMETER` + `TYPE_MAGNETIC_FIELD` → Rotationsmatrix → Azimuth
- Low-Pass-Filter (α = 0.08) für stabile Anzeige
- `ObjectAnimator` mit `DecelerateInterpolator` für weiche Nadelbewegung

---

## 🗺️ Dom-Koordinaten
```
Kölner Dom: 50.94136° N, 6.95827° E
```

## 📷 Webcam
[SkylineWebcams – Kölner Dom](https://www.skylinewebcams.com/en/webcam/deutschland/north-rhine-westphalia/cologne/cathedral.html)

---

## 📁 Projektstruktur

```
DoemcheKompass/
├── app/
│   └── src/main/
│       ├── java/de/doem/kompass/
│       │   └── MainActivity.kt        # Hauptlogik: Kompass + GPS
│       ├── res/
│       │   ├── drawable/
│       │   │   ├── ic_dom_logo.xml    # KI App-Logo (Kölner Dom)
│       │   │   ├── ic_compass_rose.xml # Kompassrose mit N/S/E/W
│       │   │   └── ic_dom_needle.xml  # Dom-Spitze als Nadel
│       │   ├── layout/
│       │   │   └── activity_main.xml  # UI Layout
│       │   └── values/
│       │       ├── strings.xml        # Kölsch-Texte
│       │       ├── colors.xml         # Gothic-Farbpalette
│       │       └── themes.xml         # Dark Material Theme
│       └── AndroidManifest.xml
├── build.gradle.kts
└── settings.gradle.kts
```

---

*Joot Zopp un viel Spaß! 🍺*
