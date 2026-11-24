package pw.binom.db

/**
 * Соединение с базой.
 *
 * ## Почему `suspend` и нет синхронного двойника
 *
 * Сетевой драйвер ждёт ответа БД — это ожидание. Синхронный вариант в
 * Kotlin/Native невозможен (блокировать нельзя), а в JVM он всё равно был бы
 * обёрткой над `runBlocking`. Один контракт проще двух: нет нужды держать в
 * голове, какой из наборов где.
 *
 * Если вызывающему нужен блокирующий вызов, он делает `runBlocking` у себя —
 * это его решение и его риск.
 *
 * ## Транзакции
 *
 * [beginTransaction]/[commit]/[rollback] — ручные. ORM-удобства живут в
 * расширении [transaction], чтобы драйверу не повторять обработку ошибок.
 */
interface Connection : AutoCloseable {
    val isClosed: Boolean

    val dbInfo: DatabaseInfo

    /** Готово ли соединение принять запрос (не в середине чужой операции). */
    val isReadyForQuery: Boolean

    val transactionMode: TransactionMode

    suspend fun setTransactionMode(mode: TransactionMode)

    suspend fun beginTransaction()

    suspend fun commit()

    suspend fun rollback()

    suspend fun prepare(sql: String): PreparedStatement

    /** Быстрый путь без подготовленного запроса. */
    suspend fun executeUpdate(sql: String): Long {
        prepare(sql).use { return it.executeUpdate() }
    }

    suspend fun query(sql: String): ResultSet {
        prepare(sql).use { return it.executeQuery() }
    }
}

/**
 * Транзакция с гарантированным откатом при исключении.
 *
 * Откат глушит свои ошибки намеренно: если `commit` упал из-за обрыва
 * соединения, `rollback` на том же соединении тоже упадёт, и его исключение
 * затёрло бы настоящую причину.
 */
suspend fun <T> Connection.transaction(block: suspend (Connection) -> T): T {
    beginTransaction()
    try {
        val result = block(this)
        commit()
        return result
    } catch (e: Throwable) {
        runCatching { rollback() }
        throw e
    }
}

/**
 * Закрытие с гарантией освобождения даже при исключении.
 *
 * Своё расширение, а не `kotlin.io.use`: интерфейс `AutoCloseable`, но метод
 * закрытия объявлен здесь, потому что драйверу нужно успеть освободить
 * ресурсы асинхронно.
 */
suspend inline fun <T : Connection, R> T.useConnection(block: (T) -> R): R = try {
    block(this)
} finally {
    close()
}
