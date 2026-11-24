package pw.binom.db.pool

import pw.binom.db.Connection

/**
 * Соединение, выданное из пула.
 *
 * ## Кто и когда возвращает
 *
 * Штатно возврат делает [ConnectionPoolImpl.withConnection] в `finally`, уже
 * в `suspend`-контексте и с мьютексом — там всё просто.
 *
 * Проблема в ручном `close()`: клиент может взять соединение через
 * `acquire()` и закрыть сам, вне `withConnection`. `Connection.close()`
 * синхронный (`AutoCloseable`), поэтому мьютекс пула взять не может.
 *
 * Решение: [close] не просто ставит флаг, а зовёт [onClose] — пул передаёт
 * колбэк, который **синхронно** складывает соединение в потокобезопасную
 * очередь возвратов и будит ожидающего. Очередь разбирается под мьютексом
 * при следующем входе в пул.
 *
 * Почему не «просто флаг»: тогда слот освобождался бы только при следующем
 * `acquire`/`cleanUp`. Вызывающий код, который закрыл соединение и сразу
 * смотрит `idleCount`, видел бы ноль — и не мог бы отличить утечку от
 * нормального возврата.
 */
class PooledConnection internal constructor(
    private val delegate: Connection,
    internal val entryForPool: ConnectionPoolImpl.Entry,
    private val onClose: (PooledConnection) -> Unit,
) : Connection by delegate {

    internal var returned: Boolean = false
        private set

    internal var broken: Boolean = false
        private set

    internal val isReturned: Boolean get() = returned
    internal val isBrokenForPool: Boolean get() = broken

    /**
     * Пометить соединение непригодным.
     *
     * Вызывается пулом при [pw.binom.db.ConnectionLostException] и отмене
     * корутины, а также клиентом, если он знает, что состояние соединения
     * испорчено.
     */
    fun markBroken() {
        broken = true
    }

    override fun close() {
        if (returned) return
        returned = true
        onClose(this)
    }

    /** Внутренний вызов пула: пометить возвращённым без колбэка. */
    internal fun markReturned() {
        returned = true
    }
}
