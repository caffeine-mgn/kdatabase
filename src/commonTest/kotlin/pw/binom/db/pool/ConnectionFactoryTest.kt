package pw.binom.db.pool

import kotlinx.coroutines.test.runTest
import pw.binom.db.Connection
import pw.binom.db.ConnectionLostException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * Контракт [ConnectionFactory]: что пул требует от драйвера и что драйвер
 * получает взамен.
 *
 * ## Почему отдельным файлом
 *
 * [ConnectionPoolTest] проверяет механику на лямбде — ровно так, как её видят
 * тесты `korm`. Здесь проверяются сами хуки: ленивость открытия, слово
 * драйвера при проверке живости, извещение об уничтожении. Ради них
 * `PgConnectionPool` и `SQLitePool` не копируют механику.
 */
class ConnectionFactoryTest {

    private var fakeNow = Instant.parse("2026-01-01T00:00:00Z")

    /**
     * Драйвер-наблюдатель: считает открытия, отвечает на validate и помнит,
     * какие соединения ушли в onDiscard.
     */
    private inner class Spy {
        val opened = mutableListOf<FakeConnection>()
        val discarded = mutableListOf<Connection>()
        var validateAnswer = true
        var validateCalls = 0
        var onDiscardThrows = false

        val factory = object : ConnectionFactory {
            override suspend fun open(): Connection = FakeConnection().also { opened += it }

            override suspend fun validate(connection: Connection): Boolean {
                validateCalls++
                return validateAnswer
            }

            override suspend fun onDiscard(connection: Connection) {
                if (onDiscardThrows) error("очистка в драйвере упала")
                discarded += connection
            }
        }
    }

    private fun pool(
        factory: ConnectionFactory,
        maxConnections: Int = 2,
        idleTimeout: Duration = 30.minutes,
        pingInterval: Duration = 30.minutes,
        pingQuery: String? = "SELECT 1",
    ) = ConnectionPoolImpl(
        config = PoolConfig(
            maxConnections = maxConnections,
            idleTimeout = idleTimeout,
            maxLifetime = 1.hours,
            pingInterval = pingInterval,
            pingQuery = pingQuery,
            clock = { fakeNow },
        ),
        factory = factory,
    )

    @Test
    fun `открытие ленивое и переиспользуется`() = runTest {
        val spy = Spy()
        val p = pool(spy.factory)

        // Конструктор пула не открывает ничего: база может быть ещё не
        // готова, и падать на старте приложения раньше первого запроса — плохо.
        assertTrue(spy.opened.isEmpty(), "на старте пул не открывает соединений")

        p.acquire().close()
        assertEquals(1, spy.opened.size)

        p.acquire().close()
        assertEquals(1, spy.opened.size, "второй должен взять то же соединение из idle")
        p.close()
    }

    @Test
    fun `validate снимает соединение с обслуживания`() = runTest {
        val spy = Spy()
        val p = pool(spy.factory, pingInterval = 1.milliseconds)
        p.acquire().close()
        assertEquals(1, p.connectionCount)

        fakeNow = fakeNow.plus(10.milliseconds)
        spy.validateAnswer = false
        assertEquals(1, p.cleanUp())
        assertEquals(0, p.connectionCount, "неживое по слову драйвера уходит из набора")
        assertTrue(spy.opened.single().closed, "уходит оно через close")

        p.acquire().close()
        assertEquals(2, spy.opened.size, "следующий получит свежее соединение")
        p.close()
    }

    @Test
    fun `validate вызывается и без pingQuery`() = runTest {
        val spy = Spy()
        val p = pool(spy.factory, pingInterval = 1.milliseconds, pingQuery = null)
        p.acquire().close()

        fakeNow = fakeNow.plus(10.milliseconds)
        assertEquals(0, p.cleanUp())
        assertEquals(1, spy.validateCalls, "без пинга проверка живости всё равно нужна драйверу")
        assertEquals(1, p.connectionCount)
        p.close()
    }

    @Test
    fun `на уничтоженное соединение приходит onDiscard`() = runTest {
        val spy = Spy()
        val p = pool(spy.factory, idleTimeout = 1.milliseconds)
        p.acquire().close()
        val stale = spy.opened.single()
        // Возврат разбирается на следующем suspend-входе: без этого шага
        // lastUsedAt проставился бы уже сдвинутыми часами, и просрочки бы не
        // случилось.
        assertEquals(1, p.idleCount)

        fakeNow = fakeNow.plus(10.milliseconds)
        // Первый вызов закрывает соединение и ставит его в очередь очистки,
        // следующий suspend-вход доносит onDiscard до драйвера.
        p.cleanUp()
        assertTrue(stale.closed)
        assertTrue(spy.discarded.isEmpty(), "очистка отложенная, на этом входе её ещё нет")

        p.cleanUp()
        assertEquals(listOf<Connection>(stale), spy.discarded)
        p.close()
    }

    @Test
    fun `просроченное соединение чистится по дороге в acquire`() = runTest {
        val spy = Spy()
        val p = pool(spy.factory, idleTimeout = 1.milliseconds)
        p.acquire().close()
        val stale = spy.opened.single()
        assertEquals(1, p.idleCount)

        fakeNow = fakeNow.plus(10.milliseconds)
        // Здесь уборку делает сам acquire, без явного cleanUp.
        p.withConnection { it.executeUpdate("SELECT 1") }

        assertTrue(stale.closed)
        assertEquals(listOf<Connection>(stale), spy.discarded)
        p.close()
    }

    @Test
    fun `ошибка в onDiscard пул не ломает`() = runTest {
        val spy = Spy()
        spy.onDiscardThrows = true
        val p = pool(spy.factory, idleTimeout = 1.milliseconds)
        p.acquire().close()
        assertEquals(1, p.idleCount)

        fakeNow = fakeNow.plus(10.milliseconds)
        p.cleanUp()
        p.cleanUp()
        assertEquals(0, p.connectionCount)

        // Пул уже решил, что соединения нет, поэтому жаловаться некому —
        // но работать не перестаёт.
        p.acquire().close()
        assertEquals(2, spy.opened.size)
        assertTrue(spy.discarded.isEmpty())
        p.close()
    }

    @Test
    fun `битое соединение тоже доходит до onDiscard`() = runTest {
        val spy = Spy()
        val p = pool(spy.factory, pingQuery = null)

        runCatching { p.withConnection { throw ConnectionLostException("обрыв") } }
        assertEquals(0, p.connectionCount)

        p.cleanUp()
        assertEquals(listOf<Connection>(spy.opened.single()), spy.discarded)
        p.close()
    }

    @Test
    fun `конструктор с лямбдой работает как прежде`() = runTest {
        var opened = 0
        val p = ConnectionPoolImpl(
            config = PoolConfig(maxConnections = 1, pingQuery = null, clock = { fakeNow }),
        ) {
            opened++
            FakeConnection()
        }

        p.acquire().close()
        p.acquire().close()
        assertEquals(1, opened, "лямбда вызывается один раз на соединение")
        assertEquals(1L, p.totalCreated, "переиспользование не создаёт второе соединение")
        p.close()
    }
}
