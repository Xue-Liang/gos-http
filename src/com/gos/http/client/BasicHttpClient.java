package com.gos.http.client;

import java.io.IOException;
import java.net.Socket;
import java.net.URL;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;

import org.apache.http.Consts;
import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpException;
import org.apache.http.HttpHost;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.ParseException;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.message.BasicHeader;
import org.apache.http.message.BasicHttpEntityEnclosingRequest;
import org.apache.http.message.BasicHttpRequest;
import org.apache.http.protocol.HTTP;
import org.apache.http.protocol.HttpCoreContext;
import org.apache.http.protocol.HttpProcessor;
import org.apache.http.protocol.HttpProcessorBuilder;
import org.apache.http.protocol.HttpRequestExecutor;
import org.apache.http.protocol.RequestConnControl;
import org.apache.http.protocol.RequestContent;
import org.apache.http.protocol.RequestExpectContinue;
import org.apache.http.protocol.RequestTargetHost;
import org.apache.http.protocol.RequestUserAgent;
import org.apache.http.util.EntityUtils;

public class BasicHttpClient {
	private static final HttpRequestExecutor httpExecutor = new HttpRequestExecutor();
	private static final HttpProcessor httpProcessor = HttpProcessorBuilder.create().add(new RequestContent()).add(new RequestTargetHost()).add(new RequestConnControl()).add(new RequestUserAgent("Mozilla/5.0")).add(new RequestExpectContinue(true)).build();
	private static final Map<String, LinkedBlockingQueue<BasicHttpClientConnection>> pool = new ConcurrentHashMap<>();
	private static final Header[] basicHeaders = {
			new BasicHeader(HTTP.USER_AGENT, "Mozilla/5.0"),
			new BasicHeader("Content-Type", "application/x-www-form-urlencoded")};
	private static final String empty = "";

	private enum method {
		GET, HEAD, POST, PUT, DELETE
	};

	public static String get(URL url, Header... headers) {
		String path = url.getPath().length() < 1 ? "/" : url.getPath();
		HttpRequest req = new BasicHttpRequest(method.GET.name(), path);
		req.setHeaders(basicHeaders);
		if (headers.length > 0) {
			req.setHeaders(headers);
		}
		HttpResponse resp = execute(url, req);
		String body = empty;

		if (resp != null) {
			try {
				body = EntityUtils.toString(resp.getEntity());
			} catch (ParseException | IOException e) {
			} finally {
				EntityUtils.consumeQuietly(resp.getEntity());
			}
		}
		return body;
	}

	public static String post(URL url, Map<String, Object> parameters, Header... headers) {
		String path = url.getPath().length() < 1 ? "/" : url.getPath();
		BasicHttpEntityEnclosingRequest req = null;
		req = new BasicHttpEntityEnclosingRequest(method.POST.name(), path);
		req.setHeaders(basicHeaders);
		if (headers.length > 0) {
			req.setHeaders(headers);
		}
		if (parameters != null && parameters.size() > 0) {
			StringBuilder cup = new StringBuilder(128);
			int counter = 0;
			for (Map.Entry<String, Object> kv : parameters.entrySet()) {
				if (counter++ > 0)
					cup.append("&");
				cup.append(kv.getKey()).append("=").append(kv.getValue());
			}
			ContentType contentType = ContentType.create("text/html", Consts.UTF_8);

			HttpEntity entity = new StringEntity(cup.toString(), contentType);
			req.setEntity(entity);
		}
		HttpResponse resp = null;
		String body = empty;
		try {
			resp = execute(url, req);
			body = EntityUtils.toString(resp.getEntity());
		} catch (ParseException | IOException | IllegalStateException e) {
		} finally {
			if (resp != null) {
				EntityUtils.consumeQuietly(resp.getEntity());
			}
		}
		return body;
	}

	/**
	 * 发送请求,得到服务器端响应
	 * 
	 * @param url
	 *            url
	 * @param request
	 *            HttpRequest
	 * @return HttpResponse
	 */
	public static HttpResponse execute(URL url, HttpRequest request) {
		HttpResponse response = null;
		if (url == null) {
			return response;
		}
		int port = url.getPort();
		HttpHost host = new HttpHost(url.getHost(), port < 1 ? 80 : port);

		HttpCoreContext coreContext = HttpCoreContext.create();
		coreContext.setTargetHost(host);

		BasicHttpClientConnection conn = null;
		conn = pullHttpClientConnection(host.getHostName(), host.getPort());

		try {
			httpExecutor.preProcess(request, httpProcessor, coreContext);
			response = httpExecutor.execute(request, conn, coreContext);
			httpExecutor.postProcess(response, httpProcessor, coreContext);
			pushHttpClientConnection(url.getHost(), conn);
		} catch (HttpException | IOException e) {
			try {
				conn.close();
			} catch (IOException ioException) {
			}
		}

		return response;
	}

	/**
	 * 从连接池中拉出一个连接
	 * 
	 * @param host
	 *            主机地址
	 * @param port
	 *            端口号
	 * @return
	 */
	private static BasicHttpClientConnection pullHttpClientConnection(String host, int port) {
		LinkedBlockingQueue<BasicHttpClientConnection> queue = pool.get(host);
		if (queue == null) {
			queue = new LinkedBlockingQueue<BasicHttpClientConnection>();
			pool.put(host, queue);
		}

		BasicHttpClientConnection conn = queue.poll();
		if (conn == null) {
			conn = new BasicHttpClientConnection(1024 * 5);
			conn.setName(String.valueOf(System.nanoTime()));
			if (!conn.isOpen()) {
				Socket socket = null;
				try {
					socket = new Socket(host, port);
					socket.setKeepAlive(true);
					conn.bind(socket);
				} catch (IOException e) {
				}
			}
		}
		return conn;
	}

	/**
	 * 把一条链接压入连接池
	 * 
	 * @param host
	 *            主机地址
	 * @param conn
	 *            连接
	 */
	private static void pushHttpClientConnection(String host, BasicHttpClientConnection conn) {
		LinkedBlockingQueue<BasicHttpClientConnection> conns = pool.get(host);
		if (conns == null) {
			conns = new LinkedBlockingQueue<BasicHttpClientConnection>();
			pool.put(host, conns);
		}
		try {
			conns.put(conn);
		} catch (Exception e) {

		}
	}

	public static void shutdown() {
		for (Map.Entry<String, LinkedBlockingQueue<BasicHttpClientConnection>> kv : pool.entrySet()) {
			for (BasicHttpClientConnection conn : kv.getValue()) {
				try {
					conn.shutdown();
				} catch (IOException e) {
				}
			}
		}
	}
}
