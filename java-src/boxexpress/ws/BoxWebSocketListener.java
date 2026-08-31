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
 * bug, worse failure mode (flaky instead of loud). One virtual-thread-per-
 * task executor per listener instance, matching the one-thread-per-request
 * model the rest of this project already uses.
 *
 * Deliberately minimal: text messages only (no binary), full-message
 * buffering only (no streaming/fragmented handling) — matches this
 * project's existing SseEmitter/res.sse() scope (text/JSON payloads,
 * nothing binary) and keeps this shim small and easy to re-verify by
 * reading it, rather than a general-purpose WebSocket toolkit.
 */
public class BoxWebSocketListener extends AbstractReceiveListener {

	private final WebSocketMessageHandler handler;
	private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

	public BoxWebSocketListener( WebSocketMessageHandler handler ) {
		this.handler = handler;
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
	}

	@Override
	protected void onError( WebSocketChannel channel, Throwable error ) {
		executor.submit( _guard( () -> handler.onError( channel, error ) ) );
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
