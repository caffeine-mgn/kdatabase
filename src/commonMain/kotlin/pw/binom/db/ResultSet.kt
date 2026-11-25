package pw.binom.db

import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Курсор по результату запроса.
 *
 * ## Почему [next] — `suspend`
 *
 * Драйвер вправе не держать весь результат в памяти. Postgres отдаёт строки
 * порциями через портал, SQLite — по одной через `sqlite3_step`. В обоих
 * случаях следующая строка может потребовать сети или работы движка, а это
 * ожидание.
 *
 * Синхронный `next()` заставлял бы драйвер либо вычитывать всё заранее (то
 * есть терять смысл курсора), либо блокировать поток, что в native и JS
 * недопустимо. Поэтому ожидание вынесено в сигнатуру — там, где оно есть, и
 * только там.
 *
 * Цена — `next()` нельзя вызвать из синхронного кода. Это правильная цена:
 * код, читающий из сети, и так должен быть в корутине.
 *
 * ## Один способ чтения
 *
 * Порционность — внутреннее дело драйвера. Вызывающий пишет
 * `while (rs.next()) { ... }` и не думает о том, откуда пришла строка: из
 * уже скачанной пачки или из нового запроса к серверу. Отдельного API для
 * потокового чтения не нужно и вредно — оно заставляло бы прикладной код
 * знать о внутренностях транспорта.
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

    /** Номер колонки по имени. Бросает, если такой нет. */
    fun columnIndex(column: String): Int {
        val i = columns.indexOfFirst { it.equals(column, ignoreCase = true) }
        if (i < 0) throw SqlException("колонка «$column» отсутствует, есть: ${columns.joinToString()}")
        return i
    }

    /**
     * Переходит к следующей строке. `false` — строк больше нет.
     *
     * `suspend`, потому что строка может потребовать сетевого обмена:
     * драйвер докачивает порцию, когда текущая исчерпана.
     *
     * После `false` набор можно закрывать; повторный вызов вернёт `false`.
     */
    suspend fun next(): Boolean

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

    override fun close()
}
