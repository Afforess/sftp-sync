package com.afforess.sftp.sync;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.io.IOUtils;

import com.afforess.sftp.sync.connection.RemoteFile;
import com.afforess.sftp.sync.connection.SFTPConnection;
import com.afforess.sftp.sync.connection.SSHPool;
import com.google.common.collect.Sets;

public class ServerDaemon extends Thread {
	private static final Logger logger = Logger.getLogger("sftp-sync");
	private final ServerEntry server;
	private final ResumableExecutorService executor;
	private final SSHPool pool;
	private final Set<String> lockedFiles = Sets.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
	private final AtomicLong paused = new AtomicLong(-1L);
	private final AtomicLong nextUpdate = new AtomicLong(0L);
	public ServerDaemon(ServerEntry server, ResumableExecutorService executor) {
		super("Server [" + server.getAlias() + "] Daemon");
		this.server = server;
		this.executor = executor;
		this.pool = new SSHPool(server);
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
			pool.clear();
			executor.shutdownNow();
		} else if (prev > -1L && time == -1L) {
			executor.resume();
		}
	}

	public synchronized void shutdown() {
		pool.clear();
		Thread shutdown = new Thread(new Runnable() {
			public void run() {
				executor.shutdownNow();
			}
		});
		shutdown.start();
		try {
			shutdown.join(10 * 1000L);
		} catch (InterruptedException e) { }
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
			if (System.currentTimeMillis() > nextUpdateTime) {
				File localDir = new File(server.getLocalDir());
				localDir.mkdirs();
				Runnable root;
				if (server.getSyncMode() == SyncMode.CLONE.getMode()) {
					root = new CloneTraversalRunnable(localDir, server.getRemoteDir());
				} else {
					root = new UploadTraversalRunnable(localDir, server.getRemoteDir());
				}
				try {
					root.run();
				} catch (Exception e) {
					logger.log(Level.SEVERE, "Unexpected exception", e);
				}
				nextUpdate.compareAndSet(nextUpdateTime, System.currentTimeMillis() + (server.getRecheckMinutes() * 60 * 1000L));
			}
			try {
				Thread.sleep(60000L);
			} catch (InterruptedException e) { }
		}
	}

	private class CloneTraversalRunnable implements Runnable {
		final String directory;
		final File localDirectory;
		CloneTraversalRunnable(File localDirectory, String directory) {
			this.localDirectory = localDirectory;
			this.directory = directory;
		}

		@Override
		public void run() {
			if (!localDirectory.exists()) {
				localDirectory.mkdirs();
			} else if (localDirectory.exists() && !localDirectory.isDirectory()) {
				throw new IllegalStateException("Local file exists but is not a directory: " + localDirectory.getAbsolutePath());
			}
			SFTPConnection conn = pool.getConnection();
			try {
				List<RemoteFile> files = conn.listFiles(directory);
				for (RemoteFile file : files) {
					//Ignore ourselves
					if (file.getPath().equals(directory)) {
						continue;
					}
					File local = new File(localDirectory, file.getName());
					Runnable task;
					if (file.isDirectory()) {
						task = new CloneTraversalRunnable(local, file.getPath());
					} else {
						task = new FileDownloadRunnable(local, file.getPath());
					}
					//logger.info("Remote file: [" + file.getPath() + "]. Task: " + (task != null ? task.getClass().getSimpleName() : "null"));
					if (task != null) {
						executor.execute(task);
					}
				}
			} finally {
				conn.close();
			}
		}
	}

	private class UploadTraversalRunnable implements Runnable {
		final String directory;
		final File localDirectory;
		UploadTraversalRunnable(File localDirectory, String directory) {
			this.localDirectory = localDirectory;
			this.directory = directory;
		}

		@Override
		public void run() {
			SFTPConnection conn = pool.getConnection();
			try {
				if (conn.getFile(directory) == null) {
					conn.mkdir(directory);
				}
				File[] localFiles = localDirectory.listFiles();
				for (File local : localFiles) {
					Runnable task;
					if (local.isDirectory()) {
						task = new UploadTraversalRunnable(local, directory + "/" + local.getName());
					} else {
						task = new FileUploadRunnable(local, directory + "/" + local.getName());
					}
					executor.execute(task);
				}
			} finally {
				conn.close();
			}
		}
	}

	private class FileUploadRunnable implements Runnable, TooltipLine {
		final String path;
		final File localFile;
		RemoteFile file;
		FileUploadRunnable(File localFile, String path) {
			this.localFile = localFile;
			this.path = path;
		}

		@Override
		public void run() {
			SFTPConnection conn = pool.getConnection();
			try {
				file = conn.getFile(path);
				String localMd5 = md5(localFile);
				String remoteMd5 = file == null ? null : file.getMD5();
				if (remoteMd5 == null || !localMd5.equals(remoteMd5)) {
					if (lockedFiles.add(path)) {
						try {
							logger.info("Uploading [" + localFile.getAbsolutePath() + "]. MD5 mismatch. Remote MD5 [" + remoteMd5 + "] Local MD5 [" + localMd5 + "]");
							if (file != null) {
								file.remove();
							}
							String dir = path.substring(0, path.lastIndexOf("/"));
							file = conn.uploadFile(dir, localFile);
							SFTPService.addTooltip(this);
							//There has to be a better way to monitor upload tooltips
							try {
								int count = 0;
								float lastProgress = 0;
								while (true) {
									try {
										Thread.sleep(1000);
									} catch (InterruptedException e) { return; }
									if (lastProgress == file.getProgress()) {
										count++;
										if (count > 5) break;
									}
									lastProgress = file.getProgress();
								}
							} finally {
								SFTPService.removeTooltip(this);
							}
						} finally {
							lockedFiles.remove(path);
						}
					}
				}
			} finally {
				conn.close();
			}
		}

		@Override
		public String getTooltip() {
			if (file != null) {
				String path = file.getPath();
				if (path.length() > 35) {
					path = "..." + path.substring(path.length() - 30);
				}
				return "UP [" + path + "] - " + (int)(file.getProgress() * 10000) / 100F + "%";
			}
			return null;
		}
	}

	private class FileDownloadRunnable implements Runnable, TooltipLine {
		final String path;
		final File localFile;
		RemoteFile file;
		FileDownloadRunnable(File localFile, String path) {
			this.localFile = localFile;
			this.path = path;
		}

		@Override
		public void run() {
			SFTPConnection conn = pool.getConnection();
			try {
				file = conn.getFile(path);
				if (file == null) {
					return; //Moved since we started the task
				}
				String localMd5 = md5(localFile);
				String remoteMd5 = file.getMD5();
				if (localMd5 == null || !localMd5.equals(remoteMd5)) {
					FileOutputStream fos = null;
					InputStream stream = null;
					if (lockedFiles.add(path)) {
						logger.info("Downloading [" + file.getPath() + "]. MD5 mismatch. Remote MD5 [" + remoteMd5 + "] Local MD5 [" + localMd5 + "]");
						SFTPService.addTooltip(this);
						try {
							localFile.getParentFile().mkdirs();
							fos = new FileOutputStream(localFile);
							stream = file.openStream();
							ReadableByteChannel rbc = Channels.newChannel(stream);
							fos.getChannel().transferFrom(rbc, 0, Long.MAX_VALUE);
						} catch (IOException e) {
							logger.log(Level.SEVERE, "Unable to copy remote file [" + file.getPath() + "] to [" + localFile.getAbsolutePath() + "]", e);
						} finally {
							IOUtils.closeQuietly(fos);
							IOUtils.closeQuietly(stream);
							SFTPService.removeTooltip(this);
							lockedFiles.remove(path);
						}
					}
				}
			} finally {
				conn.close();
			}
		}

		@Override
		public String getTooltip() {
			if (file != null) {
				String path = file.getPath();
				if (path.length() > 35) {
					path = "..." + path.substring(path.length() - 30);
				}
				return "DL [" + path + "] - " + (int)(file.getProgress() * 10000) / 100F + "%";
			}
			return null;
		}
	}

	private static String md5(File file) {
		if (!file.exists()) {
			return null;
		}
		FileInputStream fis = null;
		try {
			fis = new FileInputStream(file);
			return DigestUtils.md5Hex(fis).toLowerCase();
		} catch (IOException e) {
			e.printStackTrace();
			return null;
		} finally {
			IOUtils.closeQuietly(fis);
		}
	}
}
