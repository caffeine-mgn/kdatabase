package pw.binom.db

/**
 * Подготовленный запрос: SQL разобран и закэширован драйвером.
 *
 * ## Зачем параметры, а не склейка
 *
 * Склейка значений в SQL — это SQL-инъекция и потеря плана запроса. Даже
 * если все значения приходят из кода, а не от пользователя, склейка не даёт
 * СУБД переиспользовать план, а на Postgres ещё и ломает extended protocol.
 *
 * ## Про `:name` и `$1`
 *
 * Контракт работает с **позиционными** параметрами: индекс с нуля, порядок
 * совпадает с порядком подстановки. Стиль записи (`$1`, `?`, `:name`) — дело
 * драйвера и диалекта ORM.
 *
 * ORM генерирует SQL с `:name`, диалект переводит его в позиционный вид и
 * возвращает список имён в порядке появления; драйвер получает готовый
 * позиционный SQL и список значений. Это разделение важно: драйвер не должен
 * знать про именованные параметры, а ORM — про синтаксис конкретной СУБД.
 */
interface PreparedStatement : AutoCloseable {
    val connection: Connection

    /** Сбрасывает курсор и привязки. Позволяет выполнить запрос повторно. */
    suspend fun reset()

    suspend fun bind(index: Int, value: SqlValue)

    suspend fun executeQuery(): ResultSet

    suspend fun executeUpdate(): Long

    /**
     * Привязка списка значений с нуля.
     *
     * Отдельный метод, потому что ORM всегда знает весь список заранее, и
     * повторять `bind(0..n)` в каждом драйвере не нужно.
     */
    suspend fun bindAll(values: List<SqlValue>) {
        values.forEachIndexed { i, v -> bind(i, v) }
    }

    /** `executeQuery` с привязкой за один вызов. */
    suspend fun query(vararg values: SqlValue): ResultSet {
        reset()
        bindAll(values.toList())
        return executeQuery()
    }

    suspend fun query(values: List<SqlValue>): ResultSet {
        reset()
        bindAll(values)
        return executeQuery()
    }

    /** `executeUpdate` с привязкой за один вызов. */
    suspend fun update(vararg values: SqlValue): Long {
        reset()
        bindAll(values.toList())
        return executeUpdate()
    }

    suspend fun update(values: List<SqlValue>): Long {
        reset()
        bindAll(values)
        return executeUpdate()
    }
}
