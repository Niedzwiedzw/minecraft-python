package com.macuyiko.minecraftpyserver.servers;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.java_websocket.WebSocket;
import org.java_websocket.framing.CloseFrame;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.python.core.PyException;

import com.macuyiko.minecraftpyserver.PyInterpreter;
import com.macuyiko.minecraftpyserver.PyPlugin;

public class PyWebSocketServer extends WebSocketServer {
	private PyPlugin plugin;
	private Map<WebSocket, PyInterpreter> connections;
	private Map<WebSocket, MyOutputStream> outstreams;
	private Map<WebSocket, String> buffers;
	
	public PyWebSocketServer(PyPlugin caller, int port) {
		super(new InetSocketAddress(port));
		this.plugin = caller;
		this.connections = new HashMap<WebSocket, PyInterpreter>();
		this.outstreams = new HashMap<WebSocket, MyOutputStream>();
		this.buffers = new HashMap<WebSocket, String>();
	}
	
	public void cleanup() {
		Set<Entry<WebSocket, PyInterpreter>> entries = connections.entrySet();
		for (Entry<WebSocket, PyInterpreter> e : entries){
			if (!connections.get(e.getKey()).isAlive()) {
				plugin.log("Cleaning up an idle connection");
				close(e.getKey());
			}
		}
	}
	
	public void setupInterpreter(WebSocket ws) {
		PyInterpreter interpreter = new PyInterpreter();
		MyOutputStream os = new MyOutputStream(ws);
		interpreter.setOut(os);
		interpreter.setErr(os);
		outstreams.put(ws, os);
		connections.put(ws, interpreter);
		buffers.put(ws, "");
	}
	
	public void close(WebSocket ws) {
		if (connections.containsKey(ws))
			connections.get(ws).cleanAndClose();
		connections.remove(ws);
		buffers.remove(ws);
		ws.close(0);
	}

	@Override
	public void onOpen(WebSocket ws, ClientHandshake chs) {
		plugin.log("New websocket connection");
		cleanup();
		plugin.log("Starting interpreter");
		setupInterpreter(ws);
	}

	@Override
	public void onClose(WebSocket ws, int arg1, String arg2, boolean arg3) {
		close(ws);
	}

	@Override
	public void onMessage(WebSocket ws, final String message) {
		if (message.equals("!exit")) {
			ws.close(CloseFrame.NORMAL);
			return;
		}
		if (message.equals("!restart")) {
			plugin.log("Restarting interpreter");
			setupInterpreter(ws);
			return;
		}
		
		final PyInterpreter interpreter = connections.get(ws);
		boolean more = false;
		try {
			if (message.contains("\n")) {
				more = plugin.parse(interpreter, message, true);
			} else {
				buffers.put(ws, buffers.get(ws)+"\n"+message); 
				more = plugin.parse(interpreter, buffers.get(ws), false);
			}
		} catch (PyException e) {
			ws.send(e.toString()+"\n");
		}
		outstreams.get(ws).flush();
		if (!more) {
			interpreter.resetbuffer();
			buffers.put(ws, "");
		}
		if (more) ws.send("... ");
		else ws.send(">>> ");
	}

	@Override
	public void onError(WebSocket ws, Exception exc) {
		close(ws);
	}

	public class MyOutputStream extends OutputStream {
		WebSocket ws;
		StringBuffer buffer = new StringBuffer("");
		public MyOutputStream(WebSocket ws) {
			this.ws = ws;
		}
		@Override
		public void write(int b) {
			byte[] bytes = { (byte) b };
			write(bytes, 0, bytes.length);
		}
		@Override
		public void write(byte[] bytes, int offset, int length) {
			String s = new String(bytes, offset, length);
			buffer.append(s);
			if (buffer.toString().endsWith("\n"))
				this.flush();
		}
		@Override
		public void flush() {
			this.ws.send(buffer.toString());
			buffer.delete(0, buffer.length());
		}
	}

}
