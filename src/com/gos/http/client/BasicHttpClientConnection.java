package com.gos.http.client;

import org.apache.http.impl.DefaultBHttpClientConnection;

public class BasicHttpClientConnection extends DefaultBHttpClientConnection {
	private String name;

	public void setName(String name) {
		this.name = name;
	}

	public String getName() {
		return this.name;
	}

	public BasicHttpClientConnection(int buffersize) {
		super(buffersize);
	}

}
