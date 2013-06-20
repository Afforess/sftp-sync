package com.afforess.sftp.sync.exception;

public class SFTPConnectionException extends SFTPException{
	private static final long serialVersionUID = 1L;

	public SFTPConnectionException(String message, Throwable cause) {
		super(message, cause);
	}
}
