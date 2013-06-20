package com.afforess.sftp.sync.connection;

import com.jcraft.jsch.SftpProgressMonitor;

class ProgressMonitor implements SftpProgressMonitor {
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
