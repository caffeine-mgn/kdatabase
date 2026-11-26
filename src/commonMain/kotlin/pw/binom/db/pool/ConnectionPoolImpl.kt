package pw.binom.db.pool

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import pw.binom.db.Connection
import pw.binom.db.ConnectionLostException
import pw.binom.db.SqlException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Настройки пула.
 *
 * Значения по умолчанию — для OLTP средней руки. Провижинеру хватает
 * `maxConnections = 4`: он не обслуживает поток запросов, ему нужна
 * устойчивость к обрывам.
 */
data class PoolConfig(
    /** Жёсткий потолок одновременных соединений. */
    val maxConnections: Int = 8,
    /** Сколько ждать свободное соединение, прежде чем бросить. */
    val acquireTimeout: Duration = 15.seconds,
    /** Простой дольше этого — соединение закрывается при уборке. */
    val idleTimeout: Duration = 5.minutes,
    /** Соединение живёт не дольше этого, даже если активно: смена пароля, DNS. */
    val maxLifetime: Duration = 30.minutes,
    /** Через сколько простаивающее соединение проверяется запросом. */
    val pingInterval: Duration = 1.minutes,
    /**
     * Чем проверять живость.
     *
     * `SELECT 1` работает и в Postgres, и в SQLite. `null` — не проверять
     * вовсе; нужно фейковому драйверу в тестах `korm`.
     */
    val pingQuery: String? = "SELECT 1",
    /**
     * Источник времени.
     *
     * Инжектируется, а не берётся напрямую из `Clock.System`, по двум
     * причинам:
     *
     * * тесты уборки по простою и по времени жизни должны управлять
     *   временем. `runTest` подменяет только задержки, а `Clock.System.now()`
     *   остаётся реальным — проверка таймаутов на нём невозможна;
     * * в продакшене может понадобиться монотонное время вместо системного,
     *   чтобы перевод часов не закрывал соединения пачкой.
     *
     * По умолчанию — системные часы.
     */
    val clock: () -> Instant = { Clock.System.now() },
) {
    init {
        require(maxConnections >= 1) { "maxConnections должен быть >= 1, получено $maxConnections" }
        require(acquireTimeout.isPositive()) { "acquireTimeout должен быть > 0" }
    }
}

/**
 * Пул соединений поверх любого драйвера.
 *
 * ## Как ограничивается число соединений
 *
 * Открыть новое можно, только если `idle` пуст **и**
 * `idle + inUse + creating < maxConnections`. Иначе вызывающий встаёт в
 * очередь. Превысить потолок нельзя даже при ошибке в вызывающем коде.
 *
 * ## Почему состояние под одним `Mutex`
 *
 * `idle`, `inUse` и `creating` меняются согласованно. Атомарные счётчики
 * (которых в `commonMain` и нет) дали бы окно, где `idle` уже пуст, а
 * `creating` ещё не увеличен — и два клиента открыли бы лишние соединения.
 *
 * ## Синхронный `close()` против `suspend`-мьютекса
 *
 * `Connection.close()` синхронный (`AutoCloseable`), а мьютекс требует
 * `suspend`. Разрыв закрыт так:
 *
 * * `close()` кладёт соединение в неограниченный [Channel] через `trySend`
 *   (не блокирует, потокобезопасно, есть в native) и будит ожидающего;
 * * фактический возврат в `idle` делает [drainLocked] — он вызывается из
 *   `suspend`-путей ([acquire], [cleanUp], [withConnection]) и **дополнительно
 *   из счётчиков** через `Mutex.tryLock()`: если мьютекс свободен прямо
 *   сейчас, состояние приводится в порядок немедленно. `tryLock` не
 *   `suspend`, поэтому доступен из геттеров.
 *
 * Почему последнее важно: вызывающий, закрывший соединение вручную и
 * посмотревший `idleCount`, должен увидеть `1`, а не `0`. Иначе утечку не
 * отличить от нормального возврата.
 *
 * ## Об отмене корутин
 *
 * Отмена посреди запроса оставляет протокол в неизвестном состоянии:
 * недочитанный ответ будет прочитан следующим клиентом как его собственный.
 * Поэтому `CancellationException` помечает соединение битым — оно
 * уничтожается, а не возвращается в набор.
 *
 * ## Чем пул отличается от драйверного
 *
 * Здесь нет ни одного знания о конкретной СУБД: всё, что у неё специфично,
 * приезжает через [ConnectionFactory] — открытие, дополнительная проверка
 * живости, очистка при уничтожении. Поэтому `PgConnectionPool` и `SQLitePool`
 * тонкие, а эта механика пишется один раз.
 */
class ConnectionPoolImpl(
    private val config: PoolConfig,
    private val factory: ConnectionFactory,
) : ConnectionPool {

    /**
     * Пул поверх голой лямбды — для тех, кому не нужны хуки [ConnectionFactory]
     * (тесты на фейковом драйвере, скрипты).
     *
     * Внутри это всё равно [ConnectionFactory]: отдельной ветки логики нет,
     * чтобы «простой» конструктор не разъезжался с основным.
     */
    constructor(
        config: PoolConfig,
        factory: suspend () -> Connection,
    ) : this(
        config,
        object : ConnectionFactory {
            override suspend fun open(): Connection = factory()
        },
    )

    private val mutex = Mutex()

    internal class Entry(
        val connection: Connection,
        val createdAt: Instant,
        var lastUsedAt: Instant,
        var lastPingAt: Instant,
    )

    /**
     * Результат ожидания слота.
     *
     * `completed` нужен вместо экспериментального `CompletableDeferred.getCompleted`:
     * при гонке таймаута и выдачи надо понять, успели ли выдать соединение
     * ровно в этот момент. Без этого соединение потерялось бы навсегда.
     */
    private class Waiter {
        val deferred = CompletableDeferred<Handoff>()
        var result: Handoff? = null
        var timedOut = false
    }

    private sealed interface Handoff {
        class Take(val connection: PooledConnection) : Handoff
        /** Слот свободен — открывай сам. */
        data object Create : Handoff
    }

    private class Returned(val pooled: PooledConnection)

    private val idle = ArrayDeque<Entry>()
    private val inUse = mutableSetOf<PooledConnection>()
    private val waiters = ArrayDeque<Waiter>()

    /**
     * Уничтоженные соединения, ждущие [ConnectionFactory.onDiscard].
     *
     * Очередь, а не немедленный вызов, потому что `onDiscard` — `suspend`, а
     * уничтожить соединение приходится и из синхронных путей, и из-под
     * мьютекса. Разбирается на следующем [drainSuspend].
     */
    private val discards = ArrayDeque<Connection>()

    private var creating = 0
    private var closed = false
    private var _totalCreated = 0L
    private var _timeouts = 0L

    private val returns = Channel<Returned>(Channel.UNLIMITED)

    override val maxConnections: Int get() = config.maxConnections

    override val connectionCount: Int
        get() {
            drainNow()
            return idle.size + inUse.size + creating
        }

    override val idleCount: Int
        get() {
            drainNow()
            return idle.size
        }

    override val waitingCount: Int get() = waiters.size
    override val totalCreated: Long get() = _totalCreated
    override val timeouts: Long get() = _timeouts

    /** Текущий момент по инжектированным часам. */
    private fun now(): Instant = config.clock()

    // ---------------------------------------------------------------- возврат

    /**
     * Разбор возвратов без `suspend`.
     *
     * `Mutex.tryLock()` доступен из обычных функций. Если мьютекс занят —
     * уборку сделает тот, кто его держит, либо следующий `suspend`-вход.
     * Для наблюдения состояния этого достаточно: конкуренция здесь короткая
     * и наблюдение всегда может повториться.
     */
    private fun drainNow() {
        // `Channel.isEmpty` экспериментален; `tryReceive` в цикле решает ту же
        // задачу и не требует opt-in.
        if (!mutex.tryLock()) return
        try {
            drainLocked()
        } finally {
            mutex.unlock()
        }
    }

    private suspend fun drainSuspend() {
        val doomed = mutex.withLock {
            drainLocked()
            // Забираем список под мьютексом, а зовём onDiscard вне его: это
            // код драйвера, он может ждать сеть, а держать под замком всю
            // раздачу соединений из-за уборки нельзя.
            if (discards.isEmpty()) {
                emptyList()
            } else {
                val batch = discards.toList()
                discards.clear()
                batch
            }
        }
        for (c in doomed) {
            runCatching { factory.onDiscard(c) }
        }
    }

    /**
     * Закрыть соединение и поставить его на очистку.
     *
     * Только под мьютексом — [discards] разделяется с синхронным [drainNow].
     */
    private fun discardLocked(connection: Connection) {
        runCatching { connection.close() }
        discards.addLast(connection)
    }

    private fun drainLocked() {
        while (true) {
            val r = returns.tryReceive().getOrNull() ?: break
            processReturnLocked(r.pooled)
        }
    }

    private fun onPooledClosed(pooled: PooledConnection) {
        returns.trySend(Returned(pooled))
        // Будим ожидающего: он проснётся, вызовет acquire, а тот разберёт
        // возврат под мьютексом. Без пробуждения он проспал бы до таймаута.
        waiters.forEach {
            it.result = Handoff.Create
            it.deferred.complete(Handoff.Create)
        }
        waiters.clear()
    }

    // ---------------------------------------------------------------- acquire

    override suspend fun acquire(): PooledConnection {
        drainSuspend()
        val deadline = now() + config.acquireTimeout

        while (true) {
            var create = false
            val waiter = Waiter()

            mutex.withLock {
                check(!closed) { "пул закрыт" }
                takeIdleLocked()?.let { return it }

                if (connectionCount < config.maxConnections) {
                    creating++
                    create = true
                } else {
                    waiters.addLast(waiter)
                }
            }

            if (create) {
                try {
                    return createAndWrap()
                } catch (e: Throwable) {
                    mutex.withLock {
                        creating--
                        handoffLocked()
                    }
                    throw e
                }
            }

            val remaining = deadline - now()
            if (remaining <= Duration.ZERO) {
                mutex.withLock {
                    if (waiters.remove(waiter)) _timeouts++
                }
                throw timeoutError()
            }

            val handoff = withTimeoutOrNull(remaining) {
                waiter.deferred.await().also { waiter.result = it }
            }

            if (handoff == null) {
                // Гонка: соединение могли выдать ровно в момент таймаута.
                // Если оно в очереди — снимаем и считаем таймаут; если уже
                // выдано — забираем, иначе потерялось бы навсегда.
                val raced = mutex.withLock {
                    if (waiters.remove(waiter)) {
                        _timeouts++
                        null
                    } else {
                        waiter.result
                    }
                }
                if (raced is Handoff.Take) return raced.connection
                throw timeoutError()
            }

            when (handoff) {
                is Handoff.Take -> return handoff.connection
                Handoff.Create -> {
                    drainSuspend()
                    continue
                }
            }
        }
    }

    /** Забирает соединение из `idle`, попутно закрывая просроченные. */
    private fun takeIdleLocked(): PooledConnection? {
        while (idle.isNotEmpty()) {
            val e = idle.removeLast()
            if ((now() - e.createdAt) > config.maxLifetime || (now() - e.lastUsedAt) > config.idleTimeout) {
                discardLocked(e.connection)
                continue
            }
            return wrapLocked(e)
        }
        return null
    }

    private fun wrapLocked(e: Entry): PooledConnection {
        val pooled = PooledConnection(e.connection, e, ::onPooledClosed)
        inUse.add(pooled)
        return pooled
    }

    private suspend fun createAndWrap(): PooledConnection {
        val conn = factory.open()
        return mutex.withLock {
            creating--
            _totalCreated++
            val now = now()
            wrapLocked(Entry(conn, now, now, now)).also { handoffLocked() }
        }
    }

    /** Обрабатывает возврат. Вызывается только под мьютексом. */
    private fun processReturnLocked(pooled: PooledConnection) {
        if (!inUse.remove(pooled)) return // уже разобран
        val e = pooled.entryForPool

        val reusable = !pooled.isBrokenForPool &&
            !pooled.isClosed &&
            runCatching { e.connection.isReadyForQuery }.getOrDefault(false)

        if (!reusable) {
            discardLocked(e.connection)
            handoffLocked()
            return
        }

        e.lastUsedAt = now()
        idle.addLast(e)
        handoffLocked()
    }

    /**
     * Раздаёт освободившиеся слоты ожидающим.
     *
     * Сначала отдать готовое из `idle`, потом разрешить открыть новое:
     * ожидающий, получивший [Handoff.Create], на следующем витке сам
     * увеличит `creating` под мьютексом.
     */
    private fun handoffLocked() {
        while (waiters.isNotEmpty()) {
            val w = waiters.removeFirst()
            val fromIdle = takeIdleLocked()
            if (fromIdle != null) {
                val h = Handoff.Take(fromIdle)
                w.result = h
                w.deferred.complete(h)
                continue
            }
            if (connectionCount < config.maxConnections) {
                w.result = Handoff.Create
                w.deferred.complete(Handoff.Create)
                continue
            }
            waiters.addFirst(w)
            return
        }
    }

    // ------------------------------------------------------------ withConnection

    override suspend fun <T> withConnection(block: suspend (PooledConnection) -> T): T {
        drainSuspend()
        val c = acquire()
        try {
            return block(c)
        } catch (e: Throwable) {
            if (e is ConnectionLostException || e is CancellationException) {
                c.markBroken()
            }
            throw e
        } finally {
            c.close()
            drainSuspend()
        }
    }

    // ---------------------------------------------------------------- cleanUp

    override suspend fun cleanUp(): Int {
        drainSuspend()
        return mutex.withLock {
            var closedCount = 0
            val keep = ArrayDeque<Entry>()
            val ping = config.pingQuery

            for (e in idle) {
                if ((now() - e.createdAt) > config.maxLifetime || (now() - e.lastUsedAt) > config.idleTimeout) {
                    discardLocked(e.connection)
                    closedCount++
                    continue
                }

                // Проверка живости: сначала запрос из конфигурации, затем слово
                // драйвера. Основание то же, что у пинга — простой дольше
                // `pingInterval`: иначе [ConnectionFactory.validate] превращался
                // в лишний запрос на каждом вызове.
                if ((now() - e.lastPingAt) > config.pingInterval) {
                    val alive =
                        (ping == null || runCatching { e.connection.executeUpdate(ping) }.isSuccess) &&
                            runCatching { factory.validate(e.connection) }.getOrDefault(false)
                    if (!alive) {
                        discardLocked(e.connection)
                        closedCount++
                        continue
                    }
                    e.lastPingAt = now()
                }

                keep.addLast(e)
            }

            idle.clear()
            idle.addAll(keep)
            handoffLocked()
            closedCount
        }
    }

    // ------------------------------------------------------------------ close

    /**
     * Закрывает простаивающие соединения и запрещает брать новые.
     *
     * [ConnectionFactory.onDiscard] здесь не вызывается: close синхронный, а
     * очистка — `suspend`, и заводить её в обход очереди означало бы гонку с
     * тем, кто уже стоит на мьютексе. Драйверу, которому нужно что-то мыть на
     * завершении, хватает собственного `close()` на соединении — он здесь
     * вызывается.
     */
    override fun close() {
        closed = true
        returns.close()
        while (idle.isNotEmpty()) {
            runCatching { idle.removeFirst().connection.close() }
        }
    }

    private fun timeoutError() = SqlException(
        "не дождались свободного соединения за ${config.acquireTimeout}: " +
            "открыто ${connectionCount}, занято ${inUse.size}, ждут ${waiters.size}",
    )
}
