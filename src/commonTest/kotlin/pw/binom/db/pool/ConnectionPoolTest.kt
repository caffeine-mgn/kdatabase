package pw.binom.db.pool

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import pw.binom.db.ConnectionLostException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Тесты пула на фейковом драйвере.
 *
 * Ни одна проверка не требует живой БД: пул работает с [Connection] и не
 * знает, Postgres под ним или заглушка. Это и есть причина, по которой
 * контракт вынесен в `kdatabase`.
 */
class ConnectionPoolTest {

    private fun pool(
        max: Int = 2,
        timeoutMillis: Long = 500,
        factory: suspend () -> FakeConnection = { FakeConnection() },
    ) = ConnectionPoolImpl(
        config = PoolConfig(
            maxConnections = max,
            acquireTimeout = timeoutMillis.milliseconds,
            pingQuery = null, // фейк не умеет SELECT 1, проверка живости не нужна
        ),
        factory = factory,
    )

    @Test
    fun `одно соединение переиспользуется`() = runTest {
        val created = mutableListOf<FakeConnection>()
        val p = pool(1) { FakeConnection().also { created += it } }

        val first = p.acquire()
        first.close()

        val second = p.acquire()
        second.close()

        assertEquals(1, created.size, "второе соединение открываться не должно")
        assertEquals(1L, p.totalCreated)
    }

    @Test
    fun `потолок не превышается`() = runTest {
        val created = mutableListOf<FakeConnection>()
        val p = pool(max = 3) { FakeConnection().also { created += it } }

        val held = (1..3).map { p.acquire() }
        assertEquals(3, created.size)
        assertEquals(3, p.connectionCount)

        // Четвёртое не выдаётся: ждём таймаут.
        val failed = runCatching { p.acquire() }
        assertTrue(failed.isFailure, "при исчерпании пул обязан бросить, а не открыть лишнее")
        assertEquals(3, created.size, "сверх потолка не открываем")
        assertEquals(1L, p.timeouts)

        held.forEach { it.close() }
    }

    @Test
    fun `ожидающий получает освободившееся соединение`() = runTest {
        val p = pool(max = 1, timeoutMillis = 5_000)
        val c = p.acquire()

        val waiter = async { p.withConnection { it.executeUpdate("SELECT 1") } }
        // Даём ожидающему встать в очередь.
        delay(50)
        assertEquals(1, p.waitingCount, "ожидающий должен быть в очереди")

        c.close()
        val result = waiter.await()

        assertEquals(1L, result)
        assertEquals(1L, p.totalCreated, "ожидающий должен получить то же соединение")
    }

    @Test
    fun `битое соединение не возвращается в набор`() = runTest {
        val created = mutableListOf<FakeConnection>()
        val p = pool(2) { FakeConnection().also { created += it } }

        runCatching {
            p.withConnection<Unit> {
                throw ConnectionLostException("обрыв")
            }
        }

        assertEquals(1, created.size)
        assertTrue(created[0].closed, "битое соединение должно быть закрыто")

        // Следующий вызов обязан открыть новое, а не взять мёртвое.
        p.withConnection { it.executeUpdate("SELECT 1") }
        assertEquals(2, created.size, "после обрыва открывается новое соединение")
    }

    @Test
    fun `обычное исключение возвращает соединение в набор`() = runTest {
        val created = mutableListOf<FakeConnection>()
        val p = pool(2) { FakeConnection().also { created += it } }

        runCatching {
            p.withConnection<Unit> { throw IllegalStateException("ошибка в коде") }
        }

        assertEquals(1, created.size)
        assertFalse(created[0].closed, "соединение живое, закрывать нельзя")
        assertEquals(1, p.idleCount, "должно вернуться в набор")
    }

    @Test
    fun `соединение с открытой транзакцией не переиспользуется`() = runTest {
        val created = mutableListOf<FakeConnection>()
        val p = pool(2) { FakeConnection().also { created += it } }

        // Клиент открыл транзакцию и вернул соединение, не закрыв её.
        p.withConnection { it.beginTransaction() }

        assertTrue(created[0].closed, "с транзакцией в набор не возвращаем — закрываем")
        assertEquals(0, p.idleCount)

        p.withConnection { it.executeUpdate("SELECT 1") }
        assertEquals(2, created.size, "следующий клиент получает свежее соединение")
    }

    @Test
    fun `двойной возврат не дублирует соединение`() = runTest {
        val p = pool(1)

        val c = p.acquire()
        c.close()
        c.close() // повторный close должен быть безвредным

        assertEquals(1, p.idleCount, "соединение в наборе должно быть ровно одно")
        val again = p.acquire()
        assertNotNull(again)
        again.close()
    }

    @Test
    fun `параллельные клиенты не превышают потолок`() = runTest {
        val created = mutableListOf<FakeConnection>()
        val p = pool(max = 3, timeoutMillis = 10_000) {
            FakeConnection().also { created += it }
        }

        val jobs = (1..10).map {
            async {
                p.withConnection { conn ->
                    delay(20)
                    conn.executeUpdate("SELECT 1")
                }
            }
        }

        val results = jobs.awaitAll()
        assertEquals(10, results.size)
        assertTrue(created.size <= 3, "открыто ${created.size}, потолок 3")
        assertEquals(3, created.size, "при 10 параллельных пул упрётся в потолок")
    }

    @Test
    fun `cleanUp закрывает просроченные по простою`() = runTest {
        var virtualNow = kotlin.time.Instant.parse("2026-01-01T00:00:00Z")
        val created = mutableListOf<FakeConnection>()
        val p = ConnectionPoolImpl(
            config = PoolConfig(
                maxConnections = 2,
                acquireTimeout = 1.seconds,
                idleTimeout = 100.milliseconds,
                pingInterval = 10.seconds,
                pingQuery = null,
                clock = { virtualNow },
            ),
            factory = { FakeConnection().also { created += it } },
        )

        p.withConnection { it.executeUpdate("SELECT 1") }
        assertEquals(1, p.idleCount)

        // Сдвигаем инжектированные часы, а не реальное время: `runTest`
        // подменяет `delay`, но не `Clock.System`.
        virtualNow = virtualNow + 150.milliseconds
        val closed = p.cleanUp()

        assertEquals(1, closed)
        assertEquals(0, p.idleCount)
        assertTrue(created[0].closed)
    }

    @Test
    fun `закрытый пул не выдаёт соединения`() = runTest {
        val p = pool(1)
        p.close()

        val failed = runCatching { p.acquire() }
        assertTrue(failed.isFailure)
    }

    @Test
    fun `транзакция занимает соединение на всё время блока`() = runTest {
        val p = pool(max = 1, timeoutMillis = 5_000)
        val events = mutableListOf<String>()

        p.withConnection { conn ->
            conn.beginTransaction()
            events += "begin"
            conn.executeUpdate("INSERT")
            events += "insert"
            conn.commit()
            events += "commit"
        }

        assertEquals(listOf("begin", "insert", "commit"), events)
        assertEquals(1, p.idleCount, "после коммита соединение вернулось в набор")
    }
}
