package pw.binom.db.pool

import pw.binom.db.ColumnType
import pw.binom.db.Connection
import pw.binom.db.DatabaseEngine
import pw.binom.db.DatabaseInfo
import pw.binom.db.PreparedStatement
import pw.binom.db.ResultSet
import pw.binom.db.SqlValue
import pw.binom.db.TransactionMode
import pw.binom.db.ConnectionLostException
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Фейковое соединение для тестов пула и ORM.
 *
 * ## Зачем ещё один фейк, если есть настоящие драйверы
 *
 * Пул надо проверять без БД: тесты на живом Postgres медленные, требуют
 * контейнера и падают по причинам, не связанным с пулом. Здесь проверяется
 * именно логика пула: потолок, очередь, возврат, выброс битых.
 *
 * ## Что фейк обязан соблюдать
 *
 * Контракт [Connection] целиком, включая [isReadyForQuery]: пул по нему
 * решает, возвращать ли соединение в набор. Если фейк всегда возвращает
 * `true`, тест на «соединение с открытой транзакцией не переиспользуется»
 * пройдёт вхолостую.
 */
class FakeConnection(
    private val info: DatabaseInfo = FakeDatabaseInfo,
) : Connection {

    var closed = false
        private set

    /** Открыта ли транзакция. Управляется begin/commit/rollback. */
    var inTransaction = false
        private set

    /** Сколько раз соединение реально закрывали — для проверок утечек. */
    var closeCount = 0
        private set

    /** Сколько запросов выполнено — проверка переиспользования. */
    var executions = 0
        private set

    /** Если задано, следующий запрос бросит это исключение. */
    var failNext: Throwable? = null

    /** Если `true`, проверка `SELECT 1` провалится (мёртвое соединение). */
    var dead = false

    override val isClosed: Boolean get() = closed
    override val dbInfo: DatabaseInfo get() = info
    override val isReadyForQuery: Boolean get() = !inTransaction && !closed

    override var transactionMode: TransactionMode = TransactionMode.READ_COMMITTED
        private set

    override suspend fun setTransactionMode(mode: TransactionMode) {
        checkOpen()
        transactionMode = mode
    }

    override suspend fun beginTransaction() {
        checkOpen()
        inTransaction = true
    }

    override suspend fun commit() {
        checkOpen()
        inTransaction = false
    }

    override suspend fun rollback() {
        checkOpen()
        inTransaction = false
    }

    override suspend fun prepare(sql: String): PreparedStatement {
        checkOpen()
        failNext?.let {
            failNext = null
            throw it
        }
        executions++
        return FakeStatement(this, sql)
    }

    override suspend fun executeUpdate(sql: String): Long {
        checkOpen()
        if (dead) throw ConnectionLostException("фейковое соединение мертво")
        failNext?.let {
            failNext = null
            throw it
        }
        executions++
        return 1L
    }

    override fun close() {
        if (closed) return
        closed = true
        closeCount++
    }

    private fun checkOpen() {
        if (closed) throw pw.binom.db.ConnectionClosedException()
    }
}

object FakeDatabaseInfo : DatabaseInfo {
    override val engine: DatabaseEngine get() = DatabaseEngine.POSTGRESQL
    override val tableNameQuotesStart: String get() = "\""
    override val tableNameQuotesEnd: String get() = "\""
}

private class FakeStatement(
    override val connection: Connection,
    private val sql: String,
) : PreparedStatement {

    private var bound: List<SqlValue> = emptyList()

    override suspend fun reset() {
        bound = emptyList()
    }

    override suspend fun bind(index: Int, value: SqlValue) {
        bound = bound.toMutableList().also { list ->
            while (list.size <= index) list.add(SqlValue.Null)
            list[index] = value
        }
    }

    override suspend fun executeQuery(): ResultSet = FakeResultSet(sql, bound)

    override suspend fun executeUpdate(): Long = 1L

    override fun close() = Unit
}

private class FakeResultSet(
    private val sql: String,
    private val bound: List<SqlValue>,
) : ResultSet {

    override val columns: List<String> = listOf("value")

    private var row = -1
    private val rows = mutableListOf<SqlValue>()

    init {
        // Отдаём первый привязанный аргумент как единственную строку — этого
        // достаточно, чтобы проверить, что значение дошло до результата.
        bound.firstOrNull()?.let { rows.add(it) }
    }

    override fun columnType(index: Int): ColumnType = when (rows.firstOrNull()) {
        is SqlValue.Text -> ColumnType.VARCHAR
        is SqlValue.Int32 -> ColumnType.INTEGER
        is SqlValue.Int64 -> ColumnType.BIGINT
        else -> ColumnType.OTHER
    }

    override fun next(): Boolean {
        row++
        return row < rows.size
    }

    override fun isNull(index: Int): Boolean = rows.getOrNull(row) == SqlValue.Null

    override fun getString(index: Int): String? = (rows.getOrNull(row) as? SqlValue.Text)?.value
    override fun getBoolean(index: Int): Boolean? = (rows.getOrNull(row) as? SqlValue.Bool)?.value
    override fun getByte(index: Int): Byte? = (rows.getOrNull(row) as? SqlValue.Int8)?.value
    override fun getShort(index: Int): Short? = (rows.getOrNull(row) as? SqlValue.Int16)?.value
    override fun getInt(index: Int): Int? = (rows.getOrNull(row) as? SqlValue.Int32)?.value
    override fun getLong(index: Int): Long? = (rows.getOrNull(row) as? SqlValue.Int64)?.value
    override fun getDouble(index: Int): Double? = (rows.getOrNull(row) as? SqlValue.Float64)?.value
    override fun getBytes(index: Int): ByteArray? = (rows.getOrNull(row) as? SqlValue.Bytes)?.value
    override fun getInstant(index: Int): Instant? = (rows.getOrNull(row) as? SqlValue.Time)?.value
    override fun getUuid(index: Int): Uuid? = (rows.getOrNull(row) as? SqlValue.UuidValue)?.value

    override fun close() = Unit
}
