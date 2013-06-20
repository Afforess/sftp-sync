package com.afforess.sftp.sync;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class ResumableExecutorService implements ExecutorService {
	private final ThreadFactory factory;
	private final int maxThreads;
	private ExecutorService service;
	public ResumableExecutorService(ThreadFactory factory, int maxThreads) {
		this.factory = factory;
		this.maxThreads = maxThreads;
		this.service = Executors.newFixedThreadPool(maxThreads, factory);
	}

	public void resume() {
		if (isShutdown()) {
			if (!isTerminated()) {
				try {
					this.service.awaitTermination(10, TimeUnit.SECONDS);
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
			}
			this.service = Executors.newFixedThreadPool(maxThreads, factory);
		} else {
			throw new IllegalStateException("Can not resume service if it has not already been shutdown");
		}
	}

	@Override
	public void execute(Runnable command) {
		service.execute(command);
	}

	@Override
	public void shutdown() {
		service.shutdown();
	}

	@Override
	public List<Runnable> shutdownNow() {
		return service.shutdownNow();
	}

	@Override
	public boolean isShutdown() {
		return service.isShutdown();
	}

	@Override
	public boolean isTerminated() {
		return service.isTerminated();
	}

	@Override
	public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
		return service.awaitTermination(timeout, unit);
	}

	@Override
	public <T> Future<T> submit(Callable<T> task) {
		return service.submit(task);
	}

	@Override
	public <T> Future<T> submit(Runnable task, T result) {
		return service.submit(task, result);
	}

	@Override
	public Future<?> submit(Runnable task) {
		return service.submit(task);
	}

	@Override
	public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks) throws InterruptedException {
		return service.invokeAll(tasks);
	}

	@Override
	public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit) throws InterruptedException {
		return service.invokeAll(tasks, timeout, unit);
	}

	@Override
	public <T> T invokeAny(Collection<? extends Callable<T>> tasks) throws InterruptedException, ExecutionException {
		return service.invokeAny(tasks);
	}

	@Override
	public <T> T invokeAny(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
		return service.invokeAny(tasks, timeout, unit);
	}
}
