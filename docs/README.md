# XAU/EUR Wear OS — Spot & Sparkline Tiles

> **HR**: Brze pločice (tiles) za Wear OS koje prikazuju **XAU/EUR spot**, dnevni **min/max**, te **RSI(14)** uz glatki dnevni graf.  
> **EN**: Wear OS tiles showing **XAU/EUR spot**, daily **min/max**, and **RSI(14)** with a smooth intraday sparkline.

<p align="center">
  <img src="docs/screenshots/sparkline_1.png" width="240" />
  <img src="docs/screenshots/spot_1.png" width="240" />
  <img src="docs/screenshots/settings_1.png" width="240" />
</p>

---

## 🪙 Opis / Overview
**HR**  
Aplikacija donosi dvije Wear OS pločice:
- **Sparkline Tile**: intradnevni graf XAU/EUR s točkom zadnje cijene, RSI(14), min/max vodičima, te indikatorom otvorenosti tržišta.
- **Spot Tile**: fokus na trenutnu XAU/EUR cijenu, današnji min/max i RSI(14).

Radi offline zahvaljujući internom **SnapshotStore** cacheu, „global last” vrijednosti i sanitarnim zaštitama (raspon, max skok, prozor oko open-a).

**EN**  
The app provides two Wear OS tiles:
- **Sparkline Tile**: intraday XAU/EUR chart with last-price dot, RSI(14), min/max guides, and market-open indicator.
- **Spot Tile**: focuses on the current XAU/EUR price, today’s min/max, and RSI(14).

It’s resilient offline thanks to an internal **SnapshotStore** cache and “global last” fallback.

---

## ⚙️ Značajke / Features
- **Dva tilea**: Sparkline + Spot
- **RSI(14)**, **dnevni min/max**, **točka zadnje cijene**
- **Glatke krivulje** (monotone Bézier / Catmull-Rom)
- **Market session** (otvoreno/zatvoreno + ETA)
- **SnapshotStore** s 5-min i 30-min serijama
- **Fallbackovi**: mreža → global last → zadnja točka → open
- **PNG prerender** za proto tiles (brzo učitavanje)

---

## 🌐 Izvori podataka / Data Sources
**HR**  
Aplikacija koristi **Twelve Data** servis za tržišne podatke.  
API ključ se **unosi direktno na satu** putem sučelja u *Setup* ekranu (ne kroz XML).

**EN**  
The app uses **Twelve Data** as its market data backend.  
An **API key must be entered on the watch** via the *Setup* screen (not in XML).

> ⚠️ Without a valid Twelve Data API key, some features will be limited.

---

## 📱 Zahtjevi / Requirements
- **Wear OS 3+**
- **Twelve Data API key**
- Internet connection (for live data)
- Optional: works offline using cached data

---

## 🧭 Instalacija / Installation
**HR**
1. Klonirajte repo.
2. U Android Studio: `Run → Select Device → Deploy to Watch`.
3. Na satu dodajte pločicu:  
   dugi pritisak → *Add tile* → odaberite **Gold Spot** ili **Gold Chart**.

**EN**
1. Clone the repository.
2. In Android Studio: `Run → Select Device → Deploy to Watch`.
3. On your watch:  
   long-press → *Add tile* → choose **Gold Spot** or **Gold Chart**.

---

## 🔑 Postavljanje API ključa na satu / Entering API Key on the Watch
**HR**
1. Otvorite aplikaciju na satu.
2. Uđite u **Setup** ekran.
3. Odaberite **API Key** i unesite vaš Twelve Data ključ.
4. Kliknite **Save** i pričekajte prvo ažuriranje.

**EN**
1. Open the app on your watch.
2. Go to the **Setup** screen.
3. Tap **API Key** and enter your Twelve Data key.
4. Tap **Save** and wait for the first data refresh.

> 🔒 Ključ se pohranjuje lokalno na uređaju i koristi isključivo za Twelve Data API pozive.  
> 🔒 The key is stored locally and used only for Twelve Data API calls.

---
## Komplikacie / Complications

### Komplikacija Spot / Spot Complication
<p align="center">
  <img src="docs/screenshots/comp_1.png" width="240" />
</p>
**HR**: Prikazuje spot cijenu na watchface-u.  
**EN**: Shows spot price on watchface.

### Komplikacija Min Max / Min Max Complication

<p align="center">
  <img src="docs/screenshots/comp_1.png" width="240" />
</p>

**HR**: Prikazuje poziciju spot cijene na watchface-u, odnosu na min i max cijenu.
**EN**: Shows spot price position in min. max. range.

## 🧩 Pločice (Tiles) / Tiles

### Sparkline Tile
<p align="center">
  <img src="docs/screenshots/sparkline_1.png" width="240" />
  <img src="docs/screenshots/sparkline_2.png" width="240" />
</p>

**HR**: Intradnevni graf s RSI(14), min/max vodičima i točkom zadnje cijene.  
**EN**: Intraday chart with RSI(14), min/max guides, and last-price dot.

---

### Spot Tile
<p align="center">
  <img src="docs/screenshots/spot_1.png" width="240" />
</p>

**HR**: Prikazuje aktualni XAU/EUR spot, dnevni min/max i RSI(14).  
**EN**: Displays current XAU/EUR spot, daily min/max, and RSI(14).

---

### Setup Screen
<p align="center">
  <img src="docs/screenshots/settings_1.png" width="240" />
</p>

**HR**: Ekran za unos API ključa i spremanje u lokalni DataStore.  
**EN**: Screen for entering API key and saving it to local DataStore.

---

## 🔒 Privatnost / Privacy
- API ključ se pohranjuje **isključivo lokalno**.
- Nema analytics SDK-ova.
- Cache se može ručno obrisati iz aplikacije.

---

## 🛠️ Rješavanje problema / Troubleshooting
| Problem | Rješenje |
|----------|-----------|
| Crni ekran | Provjerite API ključ i pričekajte refresh |
| Prazni podaci | Tile se osvježava automatski svakih 60 sekundi |
| Market zatvoren | Prikazuje se “Closed” s ETA tekstom do otvaranja |
| Skokovite vrijednosti | SnapshotStore filtrira ekstremne spikeove |

---

## 🧠 Tehnički detalji / Technical Notes
- Renderer: Monotone Bézier, median filter, resampling po X-koraku
- Cache: PNG prerender za Tile Service
- SnapshotStore: 5-min i 30-min CSV serije
- Guardrails: max jump, open window, valid range
- RSI(14), min/max, EMA/SMA indikatori iz `Indicators.kt`

---

## 📜 Licenca / License
MIT License  
Free for personal and educational use.

---