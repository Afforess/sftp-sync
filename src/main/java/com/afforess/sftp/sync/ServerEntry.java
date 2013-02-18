package com.afforess.sftp.sync;

import org.formbuilder.annotations.UIOrder;
import org.formbuilder.annotations.UITitle;

public class ServerEntry {
	@UITitle("  Server Alias")
	@UIOrder(1)
	private String alias;
	@UITitle("  Server Host")
	@UIOrder(2)
	private String serverHostname;
	@UIOrder(3)
	@UITitle("  Port Number")
	private int port;
	@UIOrder(4)
	@UITitle("  Username")
	private String username;
	@UIOrder(5)
	@UITitle("  Password")
	private String password;
	@UIOrder(6)
	@UITitle("  Remote Directory")
	private String remoteDir;
	@UIOrder(7)
	@UITitle("  Local Directory")
	private String localDir;
	@UIOrder(8)
	@UITitle("  Sync deletions?")
	private boolean syncDeletions;
	@UIOrder(9)
	@UITitle("  Recheck Cooldown")
	private int recheckMinutes;

	public void set(ServerEntry entry) {
		this.alias = entry.alias;
		this.serverHostname = entry.serverHostname;
		this.port = entry.port;
		this.username = entry.username;
		this.password = entry.password;
		this.remoteDir = entry.remoteDir;
		this.localDir = entry.localDir;
		this.syncDeletions = entry.syncDeletions;
		this.recheckMinutes = entry.recheckMinutes;
	}

	public String getServerHostname() {
		return serverHostname;
	}

	public void setServerHostname(String serverHostname) {
		this.serverHostname = serverHostname;
	}

	public int getPort() {
		return port;
	}

	public void setPort(int port) {
		this.port = port;
	}

	public String getUsername() {
		return username;
	}

	public void setUsername(String username) {
		this.username = username;
	}

	public String getPassword() {
		return password;
	}

	public void setPassword(String password) {
		this.password = password;
	}

	public String getAlias() {
		return alias;
	}

	public void setAlias(String alias) {
		this.alias = alias;
	}

	public String getRemoteDir() {
		return remoteDir;
	}

	public void setRemoteDir(String remoteDir) {
		this.remoteDir = remoteDir;
	}

	public String getLocalDir() {
		return localDir;
	}

	public void setLocalDir(String localDir) {
		this.localDir = localDir;
	}

	public boolean isSyncDeletions() {
		return syncDeletions;
	}

	public void setSyncDeletions(boolean syncDeletions) {
		this.syncDeletions = syncDeletions;
	}

	public int getRecheckMinutes() {
		return recheckMinutes;
	}

	public void setRecheckMinutes(int recheckMinutes) {
		this.recheckMinutes = recheckMinutes;
	}
}
