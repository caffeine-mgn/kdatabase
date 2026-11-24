package pw.binom.db.pool

import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class DebugProbe {
    @Test
    fun probe() = runTest {
        val p = ConnectionPoolImpl(
            config = PoolConfig(maxConnections = 2, pingQuery = null),
            factory = { FakeConnection() },
        )
        println("=== after withConnection ===")
        p.withConnection { it.executeUpdate("SELECT 1") }
        println("idle=${p.idleCount} inUse=${p.connectionCount - p.idleCount} total=${p.totalCreated}")

        println("=== manual close ===")
        val c = p.acquire()
        println("after acquire: idle=${p.idleCount} conn=${p.connectionCount}")
        c.close()
        println("after close: idle=${p.idleCount} conn=${p.connectionCount}")
    }
}
