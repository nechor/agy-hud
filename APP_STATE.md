# Stan Aplikacji Mobilnej HUD (Offline)

Ten dokument opisuje obecny stan aplikacji HUD, jej strukturę oraz dokładne działanie każdego widżetu na ekranie.

---

## Główne Widżety i Komponenty Interfejsu

Interfejs graficzny jest zrealizowany w systemie **Jetpack Compose** o ciemnej, neonowej estetyce sci-fi zoptymalizowanej pod wyświetlacz przezierny (HUD).

### 1. Panel GPS i Diagnostyki Pobierania (Górny lewy róg)
*   **Wskaźnik Siły Sygnału (`SignalStrengthIndicator`)**:
    *   Wizualizuje stan połączenia GPS w postaci 4 pionowych kresek o zmiennej wysokości.
    *   **Kolory**: Zielony (GPS Fix / połączenie stabilne), Żółty (Searching / wyszukiwanie sygnału), Czerwony (Offline / brak uprawnień lub GPS wyłączony).
*   **Wykres Historii Zapytań (`FetchHistoryBars`)**:
    *   Znajduje się bezpośrednio pod sygnałem GPS.
    *   Przedstawia 10 ostatnich prób pobrania limitu prędkości za pomocą małych pionowych słupków.
    *   **Kolory**: 
        *   **Zielony**: Świeże, udane pobranie limitu bezpośrednio z API sieciowego Overpass.
        *   **Żółty**: Trafienie w cache (lokalny zapis ważny do 24 godzin) lub dopasowanie z lokalnej bazy danych offline GraphHopper.
        *   **Czerwony**: Nieudana próba pobrania (np. brak internetu, błąd serwera lub brak drogi w bazie).
        *   **Ciemnoszary**: Wolny slot (oczekiwanie na kolejne próby).
*   **Licznik Sesji (`API: X  CACHE: Y`)**:
    *   Znajduje się po prawej stronie słupków historii zapytań.
    *   Przedstawia sumaryczną ilość żądań wysłanych do API internetowego (`API: X`) oraz odczytanych z pamięci cache/bazy lokalnej (`CACHE: Y`) od momentu uruchomienia aplikacji.

### 2. Kompas (`CompassWidget`) (Górny prawy róg)
*   Pokazuje kierunek jazdy kierowcy w postaci obrotowej tarczy kompasu.
*   Kierunek (bearing) wyliczany jest na podstawie ostatnich współrzędnych GPS przy prędkości powyżej 3 km/h (w celu odfiltrowania szumów postoju).

### 3. Prędkościomierz Centralny (`SpeedometerGauge`)
*   Centralny, największy element ekranu. Wyświetla bieżącą prędkość pojazdu w km/h przy użyciu stylizowanej czcionki segmentowej (DSEG7).
*   **Tarcza neonowa wokół cyfr**: Składa się z kresek, które zapalają się proporcjonalnie do prędkości i zmieniają barwy w zależności od relacji do bieżącego limitu drogowego:
    *   *Mniej niż 80% limitu*: Cyjanowy/Niebieski.
    *   *80% - 100% limitu*: Zielony.
    *   *100% - 110% limitu*: Pomarańczowy/Żółty (bufor histerezy).
    *   *Powyżej 110% limitu*: Pulsujący Czerwony (ostrzeżenie o przekroczeniu).

### 4. Panel Informacyjny Limitów i Wysokości (Dolna sekcja prędkościomierza)
*   **Altitude (Wysokościomierz SRTM)**:
    *   Wyświetla aktualną wysokość n.p.m. odczytaną bezpośrednio z binarnych plików terenu NASA SRTM (`.hgt`). 
    *   Wysokość określana jest w 100% offline.
*   **Speed Limit (Holograficzny Znak Ograniczenia)**:
    *   Klasyczny okrągły znak ograniczenia prędkości z czerwoną obwódką.
    *   Wyświetla aktualny limit prędkości przypisany do drogi, po której porusza się kierowca.

### 5. Wykresy Liniowe Historii (`SteppedNeonChart`)
*   **Speed Historical Timeline**: Neonowy, niebiesko-zielono-czerwony wykres przedstawiający profil prędkości z ostatnich kilkudziesięciu sekund jazdy.
*   **Altitude Historical Timeline**: Zielony wykres prezentujący profil zmian wysokości n.p.m.

### 6. Dolna Konsola Sterowania (`SciFiBottomConsole`)
*   **Przycisk MIRROR: ON/OFF**:
    *   Odwraca symetrycznie w osi poziomej (odbicie lustrzane) całą zawartość ekranu HUD, umożliwiając położenie telefonu na desce rozdzielczej pod przednią szybą samochodu.
*   **Przycisk DOWNLOAD OFFLINE DATA**:
    *   Uruchamia animowany pasek postępu pobierania map offline i siatki wysokości SRTM dla bieżącego regionu.

---

## Kluczowe Mechanizmy Logiki Biznesowej

*   **Offline Map Matching (GraphHopper)**:
    Aplikacja próbuje zainicjalizować silnik lokalnego wyszukiwania dróg w katalogu `filesDir/graphhopper`. Jeśli katalog nie istnieje, automatycznie aktywuje się bezpieczny tryb **Mock**, symulujący zachowanie silnika dla trasy w Warszawie i pozwalający na pobieranie danych online w innych lokalizacjach.
*   **Trwały Cache z TTL 24h**:
    Limity pobrane z Overpass API są zapisywane w formacie JSON w pliku `speed_limits_cache.json` wraz ze znacznikiem czasu (timestamp). Przy kolejnych uruchomieniach aplikacja automatycznie odrzuca wpisy starsze niż 24 godziny, zapobiegając używaniu nieaktualnych znaków.
*   **Bezpiecznik Postoju (Stationary Timeout)**:
    Uruchomiony w tle wątek co 1 sekundę weryfikuje czas ostatniej aktywności GPS. Jeśli przez ponad 2 sekundy telefon nie zgłosi nowej pozycji (co dzieje się przy braku ruchu), prędkościomierz zostaje natychmiast zresetowany do `0.0`, a oś czasu wykresu prędkości płynnie przesuwa się na wartość zero, zapobiegając zamrożeniu ekranu na ostatniej prędkości z jazdy.
