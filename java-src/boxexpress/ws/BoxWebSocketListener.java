package boxexpress.ws;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.undertow.websockets.core.AbstractReceiveListener;
import io.undertow.websockets.core.BufferedTextMessage;
import io.undertow.websockets.core.CloseMessage;
import io.undertow.websockets.core.WebSocketChannel;

/**
 * The one piece of this project that has to be real, compiled Java rather
 * than BoxLang: Undertow's intended extension point for receiving WebSocket
 * messages, io.undertow.websockets.core.AbstractReceiveListener, is an
 * abstract class with protected hook methods (onFullTextMessage, etc.),
 * not an interface. BoxLang can implement a Java interface via
 * createDynamicProxy, but can't subclass an abstract Java class reliably
 * in the current runtime (see WebSocketMessageHandler's docblock for what
 * was actually tried and how it failed). This class does that subclassing
 * once, here, and re-exposes every event through the plain
 * WebSocketMessageHandler interface, which BoxLang code CAN implement.
 *
 * Every callback runs on a virtual thread, not Undertow's I/O thread —
 * confirmed necessary the hard way, not assumed: AbstractReceiveListener's
 * hooks fire directly on the I/O thread by default, same as the plain HTTP
 * request path did before UndertowVirtualThreadHandler existed, and a
 * handler that does any blocking work there (a blocking send back to the
 * client, in the very first version of this shim's own test) intermittently
 * reset the connection instead of throwing a clean error — same class of
 * bug, worse failure mode (flaky instead of loud).
 *
 * One virtual thread PER CONNECTION, reused for every frame that
 * connection sends — not Executors.newVirtualThreadPerTaskExecutor(),
 * which this originally used and which spins up a brand new virtual
 * thread for every individual submit(), running them fully concurrently
 * with no ordering guarantee at all. That matters here specifically
 * because handler (e.g. models/middleware/Stomp.bx's per-connection frame
 * dispatch) closes over plain, unlocked BoxLang structs — mySubscriptions,
 * activeTransactions, the connected/login flags — that assume frames from
 * ONE connection are handled one at a time, in the order they arrived, the
 * same assumption STOMP's own transaction semantics (BEGIN, one or more
 * SENDs, then COMMIT)
 * depend on. A client sending several frames in a burst (rapid-fire
 * SUBSCRIBE+SEND, or several SENDs inside one transaction) could have them
 * picked up by different concurrent virtual threads and processed out of
 * order, or race each other mutating that per-connection state — a single
 * reused virtual thread draining a queue keeps per-connection ordering
 * intact while still never touching Undertow's I/O thread, and still
 * costs nothing idle (virtual threads park for free) between messages.
 * Different connections still run fully in parallel, each on its own
 * listener instance and own single-thread executor.
 *
 * Deliberately minimal: text messages only (no binary), full-message
 * buffering only (no streaming/fragmented handling) — matches this
 * project's existing SseEmitter/res.sse() scope (text/JSON payloads,
 * nothing binary) and keeps this shim small and easy to re-verify by
 * reading it, rather than a general-purpose WebSocket toolkit.
 */
public class BoxWebSocketListener extends AbstractReceiveListener {

	private final WebSocketMessageHandler handler;
	private final ExecutorService executor = Executors.newSingleThreadExecutor( Thread.ofVirtual().name( "ws-conn-", 0 ).factory() );

	private final long maxMessageSize;

	public BoxWebSocketListener( WebSocketMessageHandler handler, long maxMessageSize ) {
		this.handler = handler;
		this.maxMessageSize = maxMessageSize;
	}

	// Undertow's own default is unlimited (-1), so an unauthenticated client
	// could make the server buffer an arbitrarily large message (CVE-2026-81624).
	@Override
	protected long getMaxTextBufferSize() {
		return maxMessageSize;
	}

	@Override
	protected long getMaxBinaryBufferSize() {
		return maxMessageSize;
	}

	@Override
	protected void onFullTextMessage( WebSocketChannel channel, BufferedTextMessage message ) {
		String data = message.getData();
		executor.submit( _guard( () -> handler.onMessage( channel, data ) ) );
	}

	@Override
	protected void onCloseMessage( CloseMessage cm, WebSocketChannel channel ) {
		int code = cm.getCode();
		String reason = cm.getReason();
		executor.submit( _guard( () -> handler.onClose( channel, code, reason ) ) );
		// Connection is done — shutdown() lets this already-submitted (and
		// any still-queued, earlier) task finish before the thread exits,
		// it just refuses anything submitted after this point. Without it,
		// newSingleThreadExecutor()'s one thread parks forever waiting for
		// work that will never come, leaking a thread for the life of the
		// process instead of per-connection like the previous
		// newVirtualThreadPerTaskExecutor() (whose threads were ephemeral
		// by construction, so this was never a concern before).
		executor.shutdown();
	}

	@Override
	protected void onError( WebSocketChannel channel, Throwable error ) {
		executor.submit( _guard( () -> handler.onError( channel, error ) ) );
		// onError doesn't always come paired with onCloseMessage for the
		// same connection — shut down here too so an error-only connection
		// end doesn't leak its thread either. Idempotent: shutdown() on an
		// already-shutdown executor (the onCloseMessage case) is a no-op.
		executor.shutdown();
	}

	/**
	 * ExecutorService.submit(Runnable) returns a Future that silently
	 * swallows any exception the task throws unless something calls
	 * get() on it — nothing here ever does, so an exception thrown inside
	 * BoxLang code (a bug in an app's own onMessage callback, say) would
	 * otherwise vanish with no trace at all: no stack trace, no log line,
	 * just a connection that stops responding for no visible reason.
	 * Confirmed this the hard way, not assumed: a real bug in this
	 * project's own STOMP middleware during development hung silently
	 * until this wrapper was added and the real exception finally printed.
	 */
	private static Runnable _guard( Runnable task ) {
		return () -> {
			try {
				task.run();
			} catch ( Throwable t ) {
				System.err.println( "[boxexpress-ws-shim] Unhandled exception in WebSocket callback:" );
				t.printStackTrace();
			}
		};
	}

}
