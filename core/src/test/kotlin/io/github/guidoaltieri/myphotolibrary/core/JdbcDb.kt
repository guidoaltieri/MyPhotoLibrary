package io.github.guidoaltieri.myphotolibrary.core

import java.sql.Connection
import java.sql.DriverManager
import java.sql.Types

/** [Db] su SQLite JDBC, per verificare il motore senza Android. */
class JdbcDb(url: String = "jdbc:sqlite::memory:") : Db {
    val con: Connection = DriverManager.getConnection(url)
    private var depth = 0

    private fun bind(st: java.sql.PreparedStatement, args: List<Any?>) {
        args.forEachIndexed { i, a ->
            when (a) {
                null -> st.setNull(i + 1, Types.NULL)
                is Int -> st.setLong(i + 1, a.toLong())
                is Long -> st.setLong(i + 1, a)
                is Double -> st.setDouble(i + 1, a)
                is Float -> st.setDouble(i + 1, a.toDouble())
                is Boolean -> st.setLong(i + 1, if (a) 1 else 0)
                is ByteArray -> st.setBytes(i + 1, a)
                else -> st.setString(i + 1, a.toString())
            }
        }
    }

    override fun query(sql: String, args: List<Any?>): List<Row> = con.prepareStatement(sql).use { st ->
        bind(st, args)
        st.executeQuery().use { rs ->
            val md = rs.metaData
            val out = ArrayList<Row>()
            while (rs.next()) {
                val row = LinkedHashMap<String, Any?>()
                for (c in 1..md.columnCount) {
                    val v = rs.getObject(c)
                    row[md.getColumnLabel(c)] = when (v) {
                        is Int -> v.toLong()
                        is Float -> v.toDouble()
                        else -> v
                    }
                }
                out += row
            }
            out
        }
    }

    override fun exec(sql: String, args: List<Any?>) {
        con.prepareStatement(sql).use { bind(it, args); it.execute() }
    }

    override fun insert(sql: String, args: List<Any?>): Long {
        exec(sql, args)
        return con.createStatement().use { s -> s.executeQuery("SELECT last_insert_rowid()").use { it.next(); it.getLong(1) } }
    }

    override fun update(sql: String, args: List<Any?>): Int = con.prepareStatement(sql).use { bind(it, args); it.executeUpdate() }

    override fun <T> transaction(block: () -> T): T {
        if (depth > 0) return block()
        con.autoCommit = false
        depth++
        try {
            val r = block()
            con.commit()
            return r
        } catch (e: Throwable) {
            con.rollback()
            throw e
        } finally {
            depth--
            con.autoCommit = true
        }
    }
}
