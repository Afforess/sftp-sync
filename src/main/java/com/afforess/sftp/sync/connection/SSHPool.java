package com.afforess.sftp.sync.connection;

import java.util.LinkedList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import com.afforess.sftp.sync.ServerEntry;
import com.afforess.sftp.sync.exception.SFTPConnectionException;

public class SSHPool {
	private static final Logger logger = Logger.getLogger("sftp-sync");
	private final ServerEntry server;
	private final LinkedList<SFTPConnection> pool = new LinkedList<SFTPConnection>();
	private final AtomicInteger connections = new AtomicInteger(0);
	public SSHPool(ServerEntry server) {
		this.server = server;
	}

	public SFTPConnection getConnection() {
		synchronized(pool) {
			SFTPConnection first = pool.poll();
			while(first != null) {
				if (first.isConnected()) {
					return first;
				} else {
					first.shutdown();
					connections.getAndDecrement();
				}
				first = pool.poll();
			}
		}
		SFTPConnection connection;
		int tries = 0;
		while(true) {
			try {
				tries++;
				connection = new SFTPConnection(server, this);
				break;
			} catch (SFTPConnectionException e) {
				try {
					Thread.sleep(10);
				} catch (InterruptedException e1) {	}
			}
		}
		if (tries != 1) {
			logger.info("Took " + tries + " to connect!");
		}
		connections.incrementAndGet();
		return connection;
	}

	public void clear() {
		synchronized(pool) {
			connections.set(0);
			SFTPConnection connection;
			while((connection = pool.poll()) != null) {
				connection.shutdown();
			}
		}
	}

	public int getNumConnections() {
		return connections.get();
	}

	protected void free(SFTPConnection conn) {
		synchronized(pool) {
			conn.reclaim();
			pool.add(conn);
		}
	}
}
