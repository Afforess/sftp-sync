package com.afforess.sftp.sync;

import java.lang.Thread.UncaughtExceptionHandler;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

public class NamedThreadFactory implements ThreadFactory {
	private final AtomicInteger count = new AtomicInteger(0);
	private final String name;
	public NamedThreadFactory(String name) {
		this.name = name;
	}

	@Override
	public Thread newThread(Runnable r) {
		Thread thread = new Thread(r, name.replaceAll("%1", String.valueOf(count.getAndIncrement())));
		thread.setUncaughtExceptionHandler(new ExceptionLogger());
		return thread;
	}
	
	private static class ExceptionLogger implements UncaughtExceptionHandler {
		private static final Logger logger = Logger.getLogger("sftp-sync");

		@Override
		public void uncaughtException(Thread t, Throwable e) {
			logger.log(Level.SEVERE, "Unhandled exception in [" + t.getName() + "]", e);
		}
	}
}
