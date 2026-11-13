package io.github.guidoaltieri.myphotolibrary.data

import android.content.Context
import android.database.Cursor
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import io.github.guidoaltieri.myphotolibrary.core.Db
import io.github.guidoaltieri.myphotolibrary.core.Row
import io.github.guidoaltieri.myphotolibrary.core.Schema

/** [Db] su SQLite di Android, con parametri tipizzati (numeri come numeri, non come testo). */
class AndroidDb(context: Context, name: String = "library.db") : Db {
    private val helper: SupportSQLiteOpenHelper = FrameworkSQLiteOpenHelperFactory().create(
        SupportSQLiteOpenHelper.Configuration.builder(context).name(name).callback(object : SupportSQLiteOpenHelper.Callback(Schema.VERSION) {
            override fun onCreate(db: SupportSQLiteDatabase) {}
            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
            override fun onConfigure(db: SupportSQLiteDatabase) {
                db.enableWriteAheadLogging()
            }
        }).build()
    )
    val db: SupportSQLiteDatabase get() = helper.writableDatabase

    private fun args(a: List<Any?>): Array<Any?> = a.map {
        when (it) {
            is Boolean -> if (it) 1L else 0L
            is Int -> it.toLong()
            is Float -> it.toDouble()
            else -> it
        }
    }.toTypedArray()

    override fun query(sql: String, args: List<Any?>): List<Row> =
        db.query(SimpleSQLiteQuery(sql, args(args))).use { c -> readAll(c) }

    private fun readAll(c: Cursor): List<Row> {
        val out = ArrayList<Row>(c.count)
        val names = c.columnNames
        while (c.moveToNext()) {
            val row = HashMap<String, Any?>(names.size * 2)
            for (i in names.indices) {
                row[names[i]] = when (c.getType(i)) {
                    Cursor.FIELD_TYPE_NULL -> null
                    Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
                    Cursor.FIELD_TYPE_FLOAT -> c.getDouble(i)
                    Cursor.FIELD_TYPE_BLOB -> c.getBlob(i)
                    else -> c.getString(i)
                }
            }
            out += row
        }
        return out
    }

    override fun exec(sql: String, args: List<Any?>) {
        if (args.isEmpty()) db.execSQL(sql) else db.execSQL(sql, args(args))
    }

    override fun insert(sql: String, args: List<Any?>): Long = db.compileStatement(sql).use { st ->
        bind(st, args)
        st.executeInsert()
    }

    override fun update(sql: String, args: List<Any?>): Int = db.compileStatement(sql).use { st ->
        bind(st, args)
        st.executeUpdateDelete()
    }

    private fun bind(st: androidx.sqlite.db.SupportSQLiteStatement, a: List<Any?>) {
        args(a).forEachIndexed { i, v ->
            when (v) {
                null -> st.bindNull(i + 1)
                is Long -> st.bindLong(i + 1, v)
                is Double -> st.bindDouble(i + 1, v)
                is ByteArray -> st.bindBlob(i + 1, v)
                else -> st.bindString(i + 1, v.toString())
            }
        }
    }

    override fun <T> transaction(block: () -> T): T {
        db.beginTransaction()
        try {
            val r = block()
            db.setTransactionSuccessful()
            return r
        } finally {
            db.endTransaction()
        }
    }
}
