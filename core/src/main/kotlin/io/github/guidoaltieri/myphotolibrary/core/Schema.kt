package io.github.guidoaltieri.myphotolibrary.core

/** Schema SQLite della fototeca (stesso modello di ArchPhotos, adattato ad Android). */
object Schema {
    const val VERSION = 1

    val STATEMENTS = listOf(
        """CREATE TABLE IF NOT EXISTS sources (
            id INTEGER PRIMARY KEY,
            kind TEXT NOT NULL,             -- 'device' (MediaStore) | 'folder' (cartella scelta: SD, USB, app cloud)
            provider TEXT DEFAULT '',       -- autorità del documento (es. com.android.externalstorage.documents)
            name TEXT NOT NULL,
            fname TEXT DEFAULT '',
            path TEXT NOT NULL,             -- URI dell'albero SAF ('' per il dispositivo)
            enabled INTEGER DEFAULT 1,
            last_scan REAL,
            pending_gone INTEGER DEFAULT 0, -- file spariti in massa: rimossi solo se la scansione successiva conferma
            scan_token TEXT
        )""",
        """CREATE TABLE IF NOT EXISTS media (
            id INTEGER PRIMARY KEY,
            source_id INTEGER REFERENCES sources(id) ON DELETE CASCADE,
            path TEXT UNIQUE NOT NULL,      -- URI content:// del file
            name TEXT,                      -- nome del file
            relpath TEXT,                   -- percorso leggibile, dalla cartella radice («DCIM/Camera/IMG_1.jpg»)
            ext TEXT,
            kind TEXT NOT NULL DEFAULT 'photo',
            mime TEXT, filesize INTEGER, mtime REAL,
            width INTEGER, height INTEGER, orientation INTEGER,
            taken REAL, taken_guess INTEGER DEFAULT 0, added REAL,
            make TEXT, model TEXT, lens TEXT, software TEXT,
            iso INTEGER, exposure REAL, fnumber REAL, focal REAL, focal35 REAL, flash INTEGER,
            lat REAL, lon REAL, alt REAL, place TEXT,
            duration REAL, fps REAL, codec TEXT,
            flags TEXT DEFAULT '',
            caption TEXT, title TEXT, keywords TEXT DEFAULT '[]', rating INTEGER DEFAULT 0,
            favorite INTEGER DEFAULT 0, hidden INTEGER DEFAULT 0, deleted REAL,
            live_video TEXT, motion_offset INTEGER, paired INTEGER DEFAULT 0,
            dhash INTEGER, edits TEXT, edited REAL,
            exif TEXT, faces_done INTEGER DEFAULT 0, import_id INTEGER,
            user TEXT DEFAULT ',',
            suggest_ignored INTEGER DEFAULT 0,
            place_suggest_ignored INTEGER DEFAULT 0,
            scan_seen TEXT,
            f_name TEXT, f_path TEXT, f_caption TEXT, f_title TEXT, f_keywords TEXT, f_place TEXT,
            f_camera TEXT, f_lens TEXT, f_software TEXT
        )""",
        "CREATE INDEX IF NOT EXISTS idx_media_taken ON media(taken)",
        "CREATE INDEX IF NOT EXISTS idx_media_source ON media(source_id)",
        "CREATE INDEX IF NOT EXISTS idx_media_dhash ON media(dhash)",
        "CREATE INDEX IF NOT EXISTS idx_media_added ON media(added)",
        """CREATE TABLE IF NOT EXISTS albums (
            id INTEGER PRIMARY KEY,
            name TEXT NOT NULL,
            fname TEXT DEFAULT '',
            kind TEXT NOT NULL DEFAULT 'album',  -- album | smart | folder
            query TEXT DEFAULT '',
            parent_id INTEGER REFERENCES albums(id) ON DELETE CASCADE,
            cover_id INTEGER,
            created REAL
        )""",
        """CREATE TABLE IF NOT EXISTS album_items (
            album_id INTEGER REFERENCES albums(id) ON DELETE CASCADE,
            media_id INTEGER REFERENCES media(id) ON DELETE CASCADE,
            pos REAL,
            PRIMARY KEY (album_id, media_id)
        )""",
        """CREATE TABLE IF NOT EXISTS persons (
            id INTEGER PRIMARY KEY,
            name TEXT DEFAULT '',
            fname TEXT DEFAULT '',
            favorite INTEGER DEFAULT 0,
            hidden INTEGER DEFAULT 0,
            cover_face INTEGER,
            created REAL
        )""",
        """CREATE TABLE IF NOT EXISTS faces (
            id INTEGER PRIMARY KEY,
            media_id INTEGER REFERENCES media(id) ON DELETE CASCADE,
            x REAL, y REAL, w REAL, h REAL,
            score REAL,
            embedding BLOB,
            person_id INTEGER REFERENCES persons(id) ON DELETE SET NULL,
            manual INTEGER DEFAULT 0
        )""",
        "CREATE INDEX IF NOT EXISTS idx_faces_media ON faces(media_id)",
        "CREATE INDEX IF NOT EXISTS idx_faces_person ON faces(person_id)",
        """CREATE TABLE IF NOT EXISTS places (
            key TEXT PRIMARY KEY, name TEXT, city TEXT, region TEXT, country TEXT, fetched REAL
        )""",
        "CREATE TABLE IF NOT EXISTS imports (id INTEGER PRIMARY KEY, started REAL, origin TEXT, count INTEGER DEFAULT 0)",
        // foto di iCloud già copiate nella destinazione (si copiano una volta sola)
        "CREATE TABLE IF NOT EXISTS icloud_assets (record TEXT PRIMARY KEY, path TEXT, synced REAL)",
        "CREATE TABLE IF NOT EXISTS memory_state (key TEXT PRIMARY KEY, favorite INTEGER DEFAULT 0, hidden INTEGER DEFAULT 0, title TEXT)",
    )

    fun create(db: Db) {
        for (s in STATEMENTS) db.exec(s)
    }
}
