# Piant 🎨💖

**Pia + paint.** Eine Mal-App für Babys und Kleinkinder, benannt nach Pia.

Piant ist für kleine Hände gemacht, die ein Handy mit allen Fingern gleichzeitig benutzen:

- **Multitouch-Malen:** jeder Finger malt gleichzeitig, auch Handballen. Die Pinselbreite folgt der Größe der Berührung.
- **Regenbogen-Pinsel** als Standard: jeder Finger bekommt eine eigene Farbe, die beim Malen weiterläuft.
- **Große Farbklekse** unten zum Antippen (Pink, Gelb, Grün, Blau, Lila, Regenbogen).
- **Spielerisches Feedback:** Funkel-Burst bei jeder Berührung, sanfte Marimba-Töne in einer Pentatonik (klingt immer schön), kurze Vibration.
- **Kein Text, keine Menüs für das Kind:** Vollbild, Bildschirm bleibt an, „Zurück“ beendet nichts, Wischgesten am Rand werden abgefangen.
- **Elternbereich:** das kleine Herz oben links **2 Sekunden mit genau einem Finger halten**. Sobald ein zweiter Finger oder eine Hand dazukommt, bricht es ab. Dort gibt es: Weitermalen, Neues Bild, Bild in der Galerie speichern, Töne, Vibration, Farbleiste, App anheften, Beenden.
- **App anheften (Kindersicherung):** nutzt Androids Bildschirmfixierung, damit das Kind die App nicht verlassen kann.
- Das Bild bleibt erhalten, wenn die App geschlossen wird.

## Design

Das Logo ist ein Farbklecks mit einem Herz in Pia-Pink (`#FF4F8B`) auf einem Pink-Orange-Verlauf. Im Schriftzug ist **Pia** im Markenverlauf hervorgehoben, **nt** steht leiser daneben.

## Installieren

**Neueste APK direkt aufs Handy:** https://github.com/Hakenadu/piant/releases/latest/download/piant.apk

Am Handy im Browser öffnen, Download antippen und die Installation aus „unbekannten Quellen“ erlauben.

Jeder Push baut die App über GitHub Actions (Artefakt `piant-apk` am Workflow-Lauf). Jeder Stand auf `main` wird automatisch als Release veröffentlicht.

Hinweis: Die APK ist vorerst mit einem Debug-Schlüssel signiert, der bei jedem CI-Lauf neu entsteht. Für ein Update muss die alte Version deshalb erst deinstalliert werden. Für dauerhafte Updates einen eigenen Release-Keystore als Secret hinterlegen.

## Selbst bauen

```bash
./gradlew assembleDebug
```

Voraussetzungen: JDK 17 und Android SDK (compileSdk 35). Läuft ab Android 8.0.

## Technik

- Kotlin, eigene `View` fürs Malen (Bitmap-Leinwand, MotionEvent-Historie für flüssige Linien)
- Jetpack Compose + Material 3 für Intro und Elternbereich
- Töne werden beim Start synthetisiert, keine Audio-Dateien nötig
