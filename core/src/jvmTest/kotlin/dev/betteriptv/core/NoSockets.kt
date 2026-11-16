package dev.betteriptv.core

import org.junit.rules.ExternalResource
import java.security.Permission
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Keeps the Core's tests off the network (#580): once [arm]ed, which the fakes' network does when it is
 * first used, opening, accepting or connecting a socket in this JVM throws, unless the test class
 * running says it needs real ones with the [RealSockets] rule. The tests that don't are the ones meant
 * to run where no local server can be opened (Apple, JavaScript).
 *
 * It is the JDK's security manager, the one hook that sees every socket (java.net and NIO alike).
 * Java 24 took it away; there the guard is off, and SocketUseTest's reading of the sources stands alone.
 */
internal object NoSockets {
    private val allowed = AtomicInteger()
    private val refused = CopyOnWriteArrayList<String>()
    private var armed = false

    /** Whether this JVM can refuse sockets at all: false from Java 24 on. */
    val guards: Boolean get() = System.getProperty("java.security.manager") == "allow"

    @Synchronized fun arm() {
        if (armed) return
        armed = true
        // build.gradle.kts sets the property on the JVMs that still have a security manager.
        if (!guards) return
        @Suppress("DEPRECATION", "removal")
        System.setSecurityManager(Guard)
    }

    /** Forgets the refusals so far: for the test that asks to be refused. */
    fun forget() = refused.clear()

    /** Fails the test if a socket was refused since the last check: the refusal itself may have been caught as an unreachable host. */
    fun check() {
        val seen = refused.toList().ifEmpty { return }
        refused.removeAll(seen)
        throw AssertionError("a test without the RealSockets rule used a socket:\n" + seen.joinToString("\n"))
    }

    fun allow() {
        allowed.incrementAndGet()
    }

    fun disallow() {
        allowed.decrementAndGet()
    }

    private fun refuse(what: String) {
        if (allowed.get() > 0) return
        val refusal = SecurityException("$what: this test opens no socket (#580). A JVM-only test of real sockets carries the RealSockets rule.")
        refused += refusal.stackTraceToString()
        throw refusal
    }

    @Suppress("DEPRECATION", "removal")
    private object Guard : SecurityManager() {
        // Everything else is allowed, as without a security manager.
        override fun checkPermission(permission: Permission) {}
        override fun checkPermission(permission: Permission, context: Any?) {}
        override fun checkListen(port: Int) = refuse("listening on port $port")
        override fun checkAccept(host: String, port: Int) = refuse("accepting a connection from $host:$port")
        // Port -1 is a name being resolved, which opens nothing.
        override fun checkConnect(host: String, port: Int) {
            if (port != -1) refuse("connecting to $host:$port")
        }
        override fun checkConnect(host: String, port: Int, context: Any?) = checkConnect(host, port)
    }
}

/**
 * Marks a test class as one that needs real sockets, so it runs on the JVM only: as a `@ClassRule`,
 * since a test's fields may open a server before any `@Rule` runs. [SocketTest] carries it for a class
 * with no other parent.
 */
class RealSockets : ExternalResource() {
    override fun before() = NoSockets.allow()
    override fun after() = NoSockets.disallow()
}
