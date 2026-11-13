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
| Cloud tramite rclone e iCloud Foto tramite icloudpd | Le **cartelle aggiunte** usano il selettore di Android: schede SD, USB e le app cloud che espongono le cartelle (kDrive, Nextcloud, OneDrive…; non Proton Drive, che non permette di scegliere cartelle ad altre app). **iCloud Foto** si copia direttamente su una di queste cartelle (vedi sotto) |

Formati: quelli che Android sa leggere (JPEG, PNG, HEIC/HEIF, AVIF, WebP, GIF, BMP, DNG; gli altri RAW con l'anteprima
incorporata) e i video MP4, MOV, MKV, WebM, 3GP.

## iCloud Foto → cloud

**Raccolte → iCloud Foto** (o Sorgenti → iCloud Foto) copia tutta la fototeca di iCloud in una cartella di un cloud già
collegato all'app, organizzata per data, e la tiene aggiornata:

1. **Accesso**: Apple ID e password, poi il codice a sei cifre che compare sui dispositivi Apple. L'accesso è quello del
   sito di iCloud (SRP: la password non viene inviata, resta cifrata nel Keystore del telefono per i rinnovi); il
   codice serve di nuovo solo quando Apple lo richiede (in genere ogni uno o due mesi). Servono *Accedi ai dati iCloud
   sul web* attivo e la *Protezione avanzata dei dati* disattivata.
2. **Destinazione**: una delle cartelle collegate in Sorgenti (app cloud che si vedono nel selettore di sistema:
   kDrive, Nextcloud, OneDrive…), oppure una nuova.
3. **Organizzazione**: Anno/Mese, Anno/Mese/Giorno, Anno, Anno-Mese, con il nome del mese, nessuna cartella o uno schema
   personalizzato; le cartelle anno/mese già esistenti vengono riconosciute (come in Importa).
4. **Copia in background** (WorkManager, con notifica): ogni originale passa direttamente da iCloud alla cartella, senza
   file temporanei sul telefono, e compare subito nella libreria con data, dimensioni, posizione e preferito di iCloud;
   le Live Photo arrivano con il loro video. A ogni apertura dell'app si copiano solo le foto nuove (la lettura
   dell'elenco si ferma appena trova solo foto già copiate); una copia interrotta riprende da dove era arrivata.
   La copia parte appena si sceglie la cartella; la scheda mostra lo stato del lavoro in background e
   **Condividi il registro** invia il registro di accesso e copia (senza password né dati delle foto) se qualcosa non va.

Lo spazio usato sul telefono dipende anche dall'app del cloud: alcune tengono una copia locale dei file finché non li
hanno caricati. Album condivisi e libreria condivisa di iCloud non sono inclusi. Il protocollo è quello di
[icloudpd](https://github.com/icloud-photos-downloader/icloud_photos_downloader), non un'API ufficiale di Apple.

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
  ICloud.kt Srp.kt ICloudSync.kt   accesso a iCloud Foto (come pyicloud/icloudpd) e copia su un cloud
  RawPreview.kt  anteprime JPEG incorporate nei RAW (rawpreview.py)
  Faces.kt Geo.kt MediaInfo.kt Fmt.kt
app/       app Android (Jetpack Compose)
  data/    scansione (MediaStore e cartelle), metadati, miniature, volti (OpenCV), luoghi, esportazione,
           importazione, scrittura EXIF, eliminazione
  ui/      libreria, ricordi, raccolte, ricerca, visore, editor, album, persone, luoghi, utilità, preferenze
```

## Licenza

GPL-3.0 o successiva, come ArchPhotos. L'elenco dei luoghi (`core/src/main/resources/gazetteer.tsv.gz`) deriva dai dati
[GeoNames](https://www.geonames.org) (CC BY 4.0). Mappe © OpenStreetMap contributors.
