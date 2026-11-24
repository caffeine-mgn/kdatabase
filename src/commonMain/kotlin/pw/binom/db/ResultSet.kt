package pw.binom.db

import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Курсор по результату запроса.
 *
 * ## Почему методы чтения не `suspend`
 *
 * Драйвер уже держит все строки ответа в памяти: Postgres отдаёт `DataRow`
 * пачками в одном потоке кадра, SQLite — через `sqlite3_step`. Отдельного
 * сетевого ожидания на каждую строку нет, поэтому `suspend` здесь был бы
 * ложью и заставлял бы городить лишние обёртки.
 *
 * Ожидание есть только у [next] в сетевом драйвере — но и оно уже
 * произошло: `ResultSet` создаётся после того, как ответ получен целиком.
 * Если когда-нибудь понадобится потоковая выдача, появится отдельный
 * `StreamingResultSet`, а этот останется простым.
 *
 * ## Доступ по индексу и по имени
 *
 * Оба варианта обязательны. ORM читает по индексу (быстрее, порядок известен
 * из сгенерированного SQL), а человек в отладке — по имени. Реализация по
 * имени по умолчанию идёт через [columnIndex], чтобы драйверу не писать
 * двадцать методов.
 */
interface ResultSet : AutoCloseable {
    /** Имена колонок в порядке выдачи. Регистр — как вернула СУБД. */
    val columns: List<String>

    fun columnType(index: Int): ColumnType

    fun columnType(column: String): ColumnType = columnType(columnIndex(column))

    /** Индекс колонки по имени, без учёта регистра. Бросает, если нет. */
    fun columnIndex(column: String): Int {
        val i = columns.indexOfFirst { it.equals(column, ignoreCase = true) }
        if (i < 0) throw SqlException("колонка \"$column\" не найдена в ${columns}")
        return i
    }

    /** Сдвиг на следующую строку. `false` — строк больше нет. */
    fun next(): Boolean

    fun isNull(index: Int): Boolean

    fun getString(index: Int): String?
    fun getBoolean(index: Int): Boolean?
    fun getByte(index: Int): Byte?
    fun getShort(index: Int): Short?
    fun getInt(index: Int): Int?
    fun getLong(index: Int): Long?
    fun getFloat(index: Int): Float? = getDouble(index)?.toFloat()
    fun getDouble(index: Int): Double?
    fun getBytes(index: Int): ByteArray?
    fun getInstant(index: Int): Instant?
    fun getUuid(index: Int): Uuid?

    fun isNull(column: String): Boolean = isNull(columnIndex(column))

    fun getString(column: String): String? = getString(columnIndex(column))
    fun getBoolean(column: String): Boolean? = getBoolean(columnIndex(column))
    fun getByte(column: String): Byte? = getByte(columnIndex(column))
    fun getShort(column: String): Short? = getShort(columnIndex(column))
    fun getInt(column: String): Int? = getInt(columnIndex(column))
    fun getLong(column: String): Long? = getLong(columnIndex(column))
    fun getFloat(column: String): Float? = getFloat(columnIndex(column))
    fun getDouble(column: String): Double? = getDouble(columnIndex(column))
    fun getBytes(column: String): ByteArray? = getBytes(columnIndex(column))
    fun getInstant(column: String): Instant? = getInstant(columnIndex(column))
    fun getUuid(column: String): Uuid? = getUuid(columnIndex(column))

    /**
     * Чтение значения без знания типа — для ORM, который решает по
     * [columnType] или по дескриптору сериализатора.
     *
     * Возвращает уже приведённое значение: `String`, `Long`, `Double`,
     * `Boolean`, `ByteArray`, `Instant`, `Uuid` или `null`.
     */
    fun getValue(index: Int): Any? = when (columnType(index)) {
        ColumnType.BIT -> getBoolean(index)
        ColumnType.TINYINT -> getByte(index)
        ColumnType.SMALLINT -> getShort(index)
        ColumnType.INTEGER -> getInt(index)
        ColumnType.BIGINT -> getLong(index)
        ColumnType.FLOAT, ColumnType.REAL -> getFloat(index)
        ColumnType.DOUBLE, ColumnType.NUMERIC, ColumnType.DECIMAL -> getDouble(index)
        ColumnType.DATE, ColumnType.TIME, ColumnType.TIMESTAMP -> getInstant(index)
        ColumnType.BINARY, ColumnType.VARBINARY, ColumnType.LONGVARBINARY -> getBytes(index)
        ColumnType.UUID -> getUuid(index)
        ColumnType.CHAR, ColumnType.VARCHAR, ColumnType.LONGVARCHAR -> getString(index)
        ColumnType.NULL -> null
        ColumnType.OTHER -> getString(index)
    }
}
