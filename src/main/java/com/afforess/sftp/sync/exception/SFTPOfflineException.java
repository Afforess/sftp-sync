package com.afforess.sftp.sync.exception;

public class SFTPOfflineException extends SFTPException{
	private static final long serialVersionUID = 1L;
	public SFTPOfflineException(String message) {
		super(message, null);
	}
}
