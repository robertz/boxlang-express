package boxexpress.ws;

import io.undertow.websockets.core.WebSocketChannel;

/**
 * Plain interface a BoxLang class implements (via createDynamicProxy) to
 * receive WebSocket events. Exists because BoxLang can't extend Undertow's
 * own io.undertow.websockets.core.AbstractReceiveListener directly —
 * confirmed empirically: BoxLang's documented extends="java:X" feature for
 * subclassing a Java class doesn't correctly forward constructor arguments
 * in the installed runtime version (1.16.0+57), and fails outright to
 * resolve AbstractReceiveListener specifically. BoxWebSocketListener does
 * the real Java-side subclassing once, here, and hands events to whatever
 * implements this interface instead.
 */
public interface WebSocketMessageHandler {

	void onMessage( WebSocketChannel channel, String text );

	void onClose( WebSocketChannel channel, int code, String reason );

	void onError( WebSocketChannel channel, Throwable error );

}
