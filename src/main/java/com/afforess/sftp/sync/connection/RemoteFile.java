package com.afforess.sftp.sync.connection;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.attribute.FileTime;
import java.util.concurrent.TimeUnit;

import org.apache.commons.io.IOUtils;

import com.afforess.sftp.sync.exception.SFTPException;
import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.SftpATTRS;
import com.jcraft.jsch.SftpException;

public class RemoteFile {
	private final String name;
	private final String directory;
	private final ChannelSftp channel;
	private final SftpATTRS attributes;
	private ProgressMonitor monitor = null;
	protected RemoteFile(String name, String directory, ChannelSftp channel, SftpATTRS attributes) {
		this.name = name;
		this.directory = directory;
		this.channel = channel;
		this.attributes = attributes;
	}

	public String getName() {
		return name;
	}

	public String getPath() {
		return directory + "/" + name;
	}

	public String getParent() {
		return directory;
	}

	public boolean isDirectory() {
		return attributes.isDir();
	}

	public long getModifiedTime() {
		return FileTime.from(attributes.getMTime(), TimeUnit.SECONDS).toMillis();
	}

	public long getAccessedTime() {
		return FileTime.from(attributes.getATime(), TimeUnit.SECONDS).toMillis();
	}

	public String getMD5() {
		ChannelExec exec = null;
		InputStream stream = null;
		try {
			exec = (ChannelExec) channel.getSession().openChannel("exec");
			exec.setCommand("md5sum \"" + getPath() + "\"");
			exec.setInputStream(null);
			exec.setErrStream(System.err);
			stream = exec.getInputStream();
			exec.connect();
			String result = IOUtils.toString(stream);
			if (result.toLowerCase().contains("no such file or directory")) {
				return null;
			}
			return result.split(" ")[0].toLowerCase();
		} catch (JSchException | IOException e) {
			throw new SFTPException(e);
		} finally {
			IOUtils.closeQuietly(stream);
			if (exec != null) {
				try {
					exec.disconnect();
				} catch (Exception ignore) { }
			}
		}
	}

	public void remove() {
		try {
			channel.rm(getPath());
		} catch (SftpException e) {
			throw new SFTPException(e);
		}
	}

	public InputStream openStream() {
		try {
			this.monitor = new ProgressMonitor(attributes.getSize());
			return channel.get(getPath(), monitor);
		} catch (SftpException e) {
			throw new SFTPException(e);
		}
	}

	protected void setMonitor(ProgressMonitor monitor) {
		this.monitor = monitor;
	}

	public float getProgress() {
		return (this.monitor != null ? this.monitor.getPercent() : 0F);
	}
}
