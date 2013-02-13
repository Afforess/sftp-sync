package com.afforess.sftp.sync;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.Vector;
import java.util.concurrent.TimeUnit;

import org.apache.commons.io.FileUtils;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.SftpException;
import com.jcraft.jsch.SftpProgressMonitor;
import com.jcraft.jsch.ChannelSftp.LsEntry;

public class TransferThread extends Thread {
	private final ServerEntry entry;
	private final ChannelSftp channel;
	private final File dir;
	private volatile String fileName;
	private volatile ProgressMonitor monitor;
	private volatile boolean finished;
	private volatile Exception ex;
	public TransferThread(ServerEntry entry, ChannelSftp channel, File dir) {
		super("SFTP Transfer Thread");
		this.entry = entry;
		this.channel = channel;
		this.dir = dir;
		setDaemon(true);
	}

	public ServerEntry getServer() {
		return entry;
	}

	@Override
	public void run() {
		try {
			copyRemoteFolder(channel, dir);
		} catch (Exception e) {
			this.ex = e;
		}
		finished = true;
	}
	
	public boolean isFinished() {
		return finished;
	}

	public Exception getException() {
		return ex;
	}

	@SuppressWarnings("unchecked")
	private void copyRemoteFolder(ChannelSftp channel, File localDir) throws SftpException, IOException {
		if (entry.isSyncDeletions()) {
			cleanLocal(channel, localDir);
		}
		Vector<LsEntry> files = (Vector<LsEntry>)channel.ls(".");
		for (int j = 0; j < files.size(); j++) {
			LsEntry file = files.get(j);
			if (file.getFilename().equals(".") || file.getFilename().equals("..")) {
				continue;
			}
			if (file.getAttrs().isLink()) {
				continue;
			}
			if (file.getFilename() == null) {
				continue;
			}
			if (file.getAttrs().isDir()) {
				File innerDir = new File(localDir, file.getFilename());
				innerDir.mkdirs();
				channel.cd(file.getFilename());
				copyRemoteFolder(channel, innerDir);
				channel.cd("..");
			} else {
				final File localFile = new File(localDir, file.getFilename());
				final long totalSize = file.getAttrs().getSize();
				final long aTime = file.getAttrs().getATime();
				final long mTime = file.getAttrs().getMTime();
				final long localCTime = getCreatedTime(localFile);
				long len = localFile.length();
				if (localCTime != mTime) {
					this.fileName = localFile.getName();
					this.monitor = new ProgressMonitor(totalSize);
					try {
						channel.get(file.getFilename(), new FileOutputStream(localFile), monitor);
					} finally {
						if (localFile.exists()) {
							localFile.setLastModified(mTime);
							Path path = Paths.get(localFile.toURI());
							BasicFileAttributeView att = Files.getFileAttributeView(path, BasicFileAttributeView.class);
							att.setTimes(FileTime.from(mTime, TimeUnit.SECONDS), FileTime.from(aTime, TimeUnit.SECONDS), FileTime.from(mTime, TimeUnit.SECONDS));
						}
					}
				} else if (localFile.length() != totalSize) {
					this.fileName = localFile.getName();
					this.monitor = new ProgressMonitor(totalSize);
					try {
						channel.get(file.getFilename(), new FileOutputStream(localFile, true), monitor, ChannelSftp.RESUME, len);
					} finally {
						localFile.setLastModified(mTime);
						Path path = Paths.get(localFile.toURI());
						BasicFileAttributeView att = Files.getFileAttributeView(path, BasicFileAttributeView.class);
						att.setTimes(FileTime.from(mTime, TimeUnit.SECONDS), FileTime.from(aTime, TimeUnit.SECONDS), FileTime.from(mTime, TimeUnit.SECONDS));
					}
				} else {
					System.out.println(localFile.getName() + " already mirrored");
				}
			}
		}
	}

	private int getCreatedTime(File file) throws IOException {
		if (!file.exists()) {
			return -1;
		}
		Path path = Paths.get(file.toURI());
		BasicFileAttributes att = Files.readAttributes(path, BasicFileAttributes.class);
		return (int) att.creationTime().to(TimeUnit.SECONDS);
	}

	private void cleanLocal(ChannelSftp channel, File localDir) throws SftpException {
		@SuppressWarnings("unchecked")
		Vector<LsEntry> files = (Vector<LsEntry>)channel.ls(".");
		for (File localFile : localDir.listFiles()) {
			boolean found = false;
			for (int j = 0; j < files.size(); j++) {
				LsEntry remoteFile = files.get(j);
				if (remoteFile.getFilename().equals(localFile.getName())) {
					found = true;
					break;
				}
			}
			//Not present on remote server, delete
			if (!found) {
				if (localFile.isDirectory()) {
					try {
						FileUtils.deleteDirectory(localFile);
					} catch (IOException ignore) { }
				} else {
					localFile.delete();
				}
			}
		}
	}

	public String getDescription() {
		if (monitor != null) {
			return "Downloading " + fileName + ", " + (100 * monitor.getPercent()) + "%  complete";
		}
		return "";
	}

	private class ProgressMonitor implements SftpProgressMonitor {
		private final long total;
		private long written;
		ProgressMonitor(long total) {
			this.total = total;
		}

		@Override
		public boolean count(long count) {
			written += count;
			return true;
		}

		public float getPercent() {
			return written / (float) total;
		}

		@Override
		public void end() {
		}

		@Override
		public void init(int op, String src, String dest, long max) {
		}
	}
}
