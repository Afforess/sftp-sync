package com.afforess.sftp.sync;

import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;

public class ServerDaemon extends Thread {
	private final ServerEntry server;
	private final ThreadFactory factory;
	private volatile DaemonJob job = null;
	private final AtomicLong paused = new AtomicLong(-1L);
	private final AtomicLong nextUpdate = new AtomicLong(0L);
	public ServerDaemon(ServerEntry server, ThreadFactory factory) {
		super("Server [" + server.getAlias() + "] Daemon");
		this.server = server;
		this.factory = factory;
	}

	public String getAlias() {
		return server.getAlias();
	}

	public synchronized long getPaused() {
		return paused.get();
	}

	public void forceUpdate() {
		nextUpdate.set(0L);
	}

	public synchronized void setPaused(long time) {
		long prev = paused.getAndSet(time);
		if (prev == -1L && time > 0L) {
			if (job != null) {
				job.shutdown();
				job = null;
			}
		}
	}

	public synchronized void shutdown() {
		if (job != null) {
			job.shutdown();
			job = null;
		}
		this.interrupt();
	}

	@Override
	public void run() {
		while(!this.isInterrupted()) {
			long pausedTime = paused.get();
			if (pausedTime != -1L) {
				if (System.currentTimeMillis() < pausedTime) {
					try {
						Thread.sleep(60000L);
					} catch (InterruptedException e) { }
					continue;
				} else {
					setPaused(-1L);
				}
			}
			final long nextUpdateTime = nextUpdate.get();
			if (System.currentTimeMillis() > nextUpdateTime && job == null) {
				job = new DaemonJob(Executors.newFixedThreadPool(server.getSyncMode() == SyncMode.UPLOAD.getMode() ? 2 : Runtime.getRuntime().availableProcessors(), factory), server);
				job.run();
				nextUpdate.compareAndSet(nextUpdateTime, System.currentTimeMillis() + (server.getRecheckMinutes() * 60 * 1000L));
			}
			try {
				Thread.sleep(60000L);
			} catch (InterruptedException e) { }
		}
	}
}
