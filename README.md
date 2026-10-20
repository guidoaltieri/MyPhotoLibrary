# MyPhotoLibrary

Fototeca per **Android** ispirata all'app **Foto** di Apple: il porting di
[ArchPhotos](https://github.com/guidoaltieri/archphotos) (Arch Linux, Qt/GTK) in Kotlin e Jetpack Compose, con lo stesso
motore (ricerca, ricordi, album smart, duplicati, suggerimenti di date e luoghi, modifiche non distruttive) e le
stesse ricette di modifica.

## Scaricare l'app

Ogni push compila l'app su GitHub Actions: in **Actions → Android → ultimo run → Artifacts** c'è
`MyPhotoLibrary-apk` con `app-release.apk` (e `app-debug.apk`). Le due versioni sono firmate con la stessa chiave di
prova inclusa nel repository (`keystore/debug.keystore`), quindi gli aggiornamenti si installano sopra la versione
precedente. Requisiti: Android 10 o successivo, telefono a 64 bit (arm64) o emulatore x86_64.

Per compilarla in locale: Android Studio oppure `./gradlew :app:assembleDebug` con l'Android SDK installato.
Il motore si verifica anche senza SDK: `CORE_ONLY=1 ./gradlew :core:test`.

## Funzioni

| ArchPhotos (desktop) | MyPhotoLibrary (Android) |
|---|---|
| Temi sistema, chiaro, scuro, **nero** | Uguali (Preferenze → Aspetto), con i colori di Material You |
| Libreria per **Anni, Mesi, Giorni, Tutte le foto**, zoom delle miniature, data durante lo scorrimento | Uguale: pizzico con due dita per cambiare le colonne |
| **Ricordi** (un giorno come oggi, viaggi, giornate speciali, il meglio dell'anno/estate/mese, persone, luoghi), preferiti, rinomina, salva come album, **presentazione** | Uguale (scheda «Per te») |
| **Persone**: volti riconosciuti sul computer (OpenCV YuNet + SFace); nome, unisci, nascondi, preferite | Uguale, sul telefono (OpenCV per Android, stessi modelli scaricati una volta) |
| **Luoghi**: mappa con i punti raggruppati, nomi da OpenStreetMap Nominatim (coordinate arrotondate a ~1 km) | Uguale (osmdroid), nomi disattivabili |
| Preferiti, Aggiunti di recente, **Tipi di media** (Video, Selfie, Live Photo, Ritratti, Panoramiche, Rallentatore, Raffiche, Animate, Istantanee e registrazioni dello schermo, RAW) | Uguale |
| **Live Photo** di iPhone (HEIC + MOV) e Motion Photo di Android | Uguale: tasto «LIVE» nel visore |
| Utilità: Importazioni, **Duplicati** (hash percettivo, unione che conserva preferiti, album e parole chiave), Nascosti, **Eliminati di recente** (30 giorni) | Uguale; l'eliminazione definitiva usa il cestino di Android (con il consenso o «Gestione contenuti multimediali») |
| **Album**, cartelle di album, **album smart** con criteri | Uguale |
| **Visore**: zoom fino all'800 % a piena risoluzione, video, Informazioni (didascalia, data, fotocamera, obiettivo, scatto, mappa, luogo, persone, parole chiave, album, tutti i metadati EXIF) | Uguale |
| **Modifica non distruttiva**: Regola (16 cursori), Migliora, Filtri con intensità, Ritaglia (raddrizza, ruota, rifletti, proporzioni), Confronta, Ripristina; video: taglio e audio | Uguale (tieni premuta la foto per l'originale); ricette compatibili con ArchPhotos |
| **Esporta** con o senza modifiche, formato, qualità, dimensioni, senza posizione; Copia; Usa come sfondo; Apri con | Uguale (JPEG, PNG, WebP; TIFF non è supportato da Android), più Condividi |
| **Importa** da fotocamere, telefoni, schede SD e cartelle, organizzando per data (Anno/Mese, schemi personalizzati, cartelle esistenti riconosciute) | Uguale: da schede SD, chiavette/dischi USB, fotocamere con adattatore e cartelle, verso Immagini/MyPhotoLibrary o qualsiasi cartella (anche di un'app cloud) |
| **Modifica data e luogo** (stessa data, «la prima diventa…», sposta), luogo dall'elenco offline o da OpenStreetMap, **Scrivi le modifiche nei file** | Uguale (EXIF di JPEG, PNG, WebP e DNG con ExifInterface) |
| **Suggerimenti**: date in base alle cartelle (`2024/05`, `Maggio 2024`…) e luoghi dai nomi di cartelle e file (elenco GeoNames offline) | Uguale |
| **Ricerca** in linguaggio naturale e sintassi avanzata (`camera:`, `year:`, `is:`, `has:`, `place:`, `person:`, `added:<30d`, `OR`, `-`…) | Uguale, con alias italiani |
| Cloud tramite rclone e iCloud Foto tramite icloudpd | Le **cartelle aggiunte** usano il selettore di Android: schede SD, USB e le app cloud che espongono le cartelle (Drive, Proton Drive, Nextcloud…). Il desktop resta lo strumento per iCloud Foto e rclone |

Formati: quelli che Android sa leggere (JPEG, PNG, HEIC/HEIF, AVIF, WebP, GIF, BMP, DNG; gli altri RAW con l'anteprima
incorporata) e i video MP4, MOV, MKV, WebM, 3GP.

## Struttura del codice

```
core/      motore in Kotlin puro (porting di archphotos/*.py), verificato con i test di ArchPhotos
  Library.kt     database SQLite, viste, album, persone, duplicati, ricordi, scansione (library.py)
  Search.kt      linguaggio di ricerca → SQL (search.py)
  Memories.kt    ricordi automatici (memories.py)
  Edit.kt        ricette di modifica, regolazioni sui pixel, Migliora, hash percettivo (edit.py, thumbs.py)
  Dates.kt       date nei nomi delle cartelle e suggerimenti (dates.py)
  PlaceNames.kt  luoghi nei nomi di cartelle e file, elenco GeoNames (placenames.py)
  Planner.kt     schemi delle cartelle per le importazioni (uploader.py)
  Faces.kt Geo.kt MediaInfo.kt Fmt.kt
app/       app Android (Jetpack Compose)
  data/    scansione (MediaStore e cartelle), metadati, miniature, volti (OpenCV), luoghi, esportazione,
           importazione, scrittura EXIF, eliminazione
  ui/      libreria, ricordi, raccolte, ricerca, visore, editor, album, persone, luoghi, utilità, preferenze
```

## Licenza

GPL-3.0 o successiva, come ArchPhotos. L'elenco dei luoghi (`core/src/main/resources/gazetteer.tsv.gz`) deriva dai dati
[GeoNames](https://www.geonames.org) (CC BY 4.0). Mappe © OpenStreetMap contributors.
