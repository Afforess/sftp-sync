package com.afforess.sftp.sync.connection;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Vector;

import org.apache.commons.io.IOUtils;

import com.afforess.sftp.sync.ServerEntry;
import com.afforess.sftp.sync.exception.SFTPConnectionException;
import com.afforess.sftp.sync.exception.SFTPException;
import com.afforess.sftp.sync.exception.SFTPOfflineException;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.ChannelSftp.LsEntry;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpException;

public class SFTPConnection {
	private final SSHPool pool;
	private final Session session;
	private final ChannelSftp channel;
	private boolean closed = false;
	private boolean shutdown = false;
	protected SFTPConnection(ServerEntry server, SSHPool pool) {
		this.pool = pool;
		try {
			JSch jsch = new JSch();
			this.session = jsch.getSession(server.getUsername(), server.getServerHostname(), server.getPort());
			this.session.setPassword(server.getPassword());
			java.util.Properties config = new java.util.Properties();
			config.put("StrictHostKeyChecking", "no");
			this.session.setConfig(config);
			this.session.connect();
			this.channel = (ChannelSftp) session.openChannel("sftp");
			this.channel.connect();
			this.channel.cd(server.getRemoteDir());
		} catch (Exception e) {
			if (e.getCause() instanceof ConnectException || e.getCause() instanceof UnknownHostException || e.getCause() instanceof NoRouteToHostException || (e.getCause() instanceof SocketException && e.getCause().getMessage().equalsIgnoreCase("Network is unreachable"))) {
				throw new SFTPOfflineException("No connection to " + server.getServerHostname() + ":" + server.getPort());
			} else {
				throw new SFTPConnectionException("Unable to connect", e);
			}
		}
	}

	@SuppressWarnings("unchecked")
	public List<RemoteFile> listFiles(String directory) {
		List<RemoteFile> files = new ArrayList<RemoteFile>();
		Vector<LsEntry> vector;
		try {
			vector = channel.ls(directory);
		} catch (SftpException e) {
			if (e.getMessage().contains("No such file")) {
				return Collections.emptyList();
			} else {
				throw new SFTPException(e);
			}
		}
		for (LsEntry entry : vector) {
			if (entry.getFilename().equals("..")) {
				continue;
			}
			if (entry.getFilename().equals(".")) {
				int lastSlash = directory.lastIndexOf('/');
				files.add(new RemoteFile(directory.substring(lastSlash + 1), directory.substring(0, lastSlash), channel, entry.getAttrs()));
				continue;
			}
			if (directory.endsWith(entry.getFilename())) {
				directory = directory.substring(0, directory.length() - entry.getFilename().length() -1); 
			}
			files.add(new RemoteFile(entry.getFilename(), directory, channel, entry.getAttrs()));
		}
		return files;
	}

	public RemoteFile getFile(String path) {
		List<RemoteFile> files = listFiles(path);
		if (files.size() == 1) {
			return files.get(0);
		} else if (files.size() > 1) {
			for (RemoteFile file : files) {
				if (file.getPath().equals(path)) {
					return file;
				}
			}
		}
		return null;
	}

	public void mkdir(String path) {
		try {
			channel.mkdir(path);
		} catch (SftpException e) {
			throw new SFTPException("Unable to make directory for [" + path + "]", e);
		}
	}

	public RemoteFile uploadFile(String directory, File file) {
		FileInputStream fis = null;
		try {
			fis = new FileInputStream(file);
			ProgressMonitor monitor = new ProgressMonitor(file.length());
			channel.put(fis, directory + "/" + file.getName(), monitor);
			RemoteFile remote = getFile(directory + "/" + file.getName());
			remote.setMonitor(monitor);
			return remote;
		} catch (IOException | SftpException e) { 
			throw new SFTPException(e);
		} finally {
			IOUtils.closeQuietly(fis);
		}
	}

	public void close() {
		if (!closed) {
			closed = true;
			pool.free(this);
		}
	}

	protected void reclaim() {
		closed = false;
	}

	protected boolean isConnected() {
		try {
			return listFiles(".") != null;
		} catch (Exception e) {
			return false;
		}
	}

	protected void shutdown() {
		if (!shutdown) {
			shutdown = true;
			try {
				channel.disconnect();
			} catch (Exception ignore) { }
			try {
				session.disconnect();
			} catch (Exception ignore) { }
		}
	}
}
