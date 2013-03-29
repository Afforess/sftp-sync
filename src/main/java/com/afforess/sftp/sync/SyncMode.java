package com.afforess.sftp.sync;

public enum SyncMode {
	CLONE(0),
	MIRROR(1),
	UPLOAD(2);
	
	final int mode;
	SyncMode(int mode) {
		this.mode = mode;
	}

	public int getMode() {
		return mode;
	}

	public static SyncMode getModeById(int mode) {
		for (SyncMode m : values()) {
			if (m.getMode() == mode) {
				return m;
			}
		}
		return CLONE;
	}
}
