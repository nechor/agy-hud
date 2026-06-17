# GEMINI.md - Mobilna Aplikacja HUD (Offline)

## Rola i Podstawowa Zasada
Jesteś zaawansowanym asystentem programistycznym w ekosystemie Antigravity.
- **Złota Zasada:** Myśl jak najbardziej zaawansowany model Claude firmy Anthropic.
- **Cytat do pamiętania:** "Jest czas żeby łowić ryby, jest czas, żeby suszyć sieci".

---

## Koncepcja Projektu
Aplikacja mobilna (Android) pełniąca rolę samochodowego wyświetlacza przeziernego (HUD), działająca w 100% offline.

### Główne Funkcje:
1. **Prędkościomierz HUD**: Wyświetla bieżącą prędkość z wbudowanego GPS z opcją symetrycznego odbicia lustrzanego (Windshield HUD Mirroring).
2. **Wysokościomierz SRTM**: Parsuje lokalne pliki binarne terenu NASA SRTM (`.hgt`) do natychmiastowego wyznaczania wysokości n.p.m. bez użycia internetu.
3. **Map Matching / Speed Limits**: Używa osadzonej biblioteki **GraphHopper** do map-matchingu wektora GPS i odczytywania tagu `maxspeed` z lokalnie wygenerowanego grafu dróg.
4. **Wizualne Ostrzeżenia i Histereza**:
   - Pulsująca czerwona obwódka przy przekroczeniu limitu prędkości (+10 km/h bufor).
   - Podtrzymanie ostatniego znanego znaku limitu przez 5 sekund (histereza) przy chwilowej utracie sygnału.
   - Dedykowany tryb symulatora jazdy (Demo Drive) do testowania zachowania UI offline.

---

## Wytyczne Technologiczne i Konfiguracyjne
- **Środowisko:** Android Native (Kotlin + Jetpack Compose)
- **Kompatybilność:** compileSdk 35 / minSdk 26
- **Zależności:**
  - `com.graphhopper:graphhopper-core:8.0` dla offline map matching.
  - Pliki terenu SRTM `.hgt` ładowane z `filesDir/srtm/`.
  - Dane grafu drogowego GraphHopper ładowane z `filesDir/graphhopper/`.

---

## Struktura Katalogów
- [app/src/main/kotlin/com/offlinehud/app/](file:///home/chips/repo/agy-apps/app/src/main/kotlin/com/offlinehud/app/)
  - [MainActivity.kt](file:///home/chips/repo/agy-apps/app/src/main/kotlin/com/offlinehud/app/MainActivity.kt) - Interfejs graficzny i pętla logiki.
  - [SrtmElevationProvider.kt](file:///home/chips/repo/agy-apps/app/src/main/kotlin/com/offlinehud/app/SrtmElevationProvider.kt) - Odczyt wysokości z NASA SRTM.
  - [MapMatchingEngine.kt](file:///home/chips/repo/agy-apps/app/src/main/kotlin/com/offlinehud/app/MapMatchingEngine.kt) - Silnik GraphHopper.
- [gradle/libs.versions.toml](file:///home/chips/repo/agy-apps/gradle/libs.versions.toml) - Wersjonowanie zależności i wtyczek.
- [.gemini/settings.json](file:///home/chips/repo/agy-apps/.gemini/settings.json) - Ustawienia Antigravity dla ujednoliconego filtrowania.
