package com.afforess.sftp.sync.exception;

public class SFTPException extends RuntimeException{
	private static final long serialVersionUID = 1L;
	private final String message;
	private final Throwable cause;
	public SFTPException(String message, Throwable cause) {
		this.message = message;
		this.cause = cause;
	}

	public SFTPException(Throwable cause) {
		this("", cause);
	}

	public SFTPException(String message) {
		this(message, null);
	}

	@Override
	public String getMessage() {
		return message;
	}

	@Override
	public Throwable getCause() {
		return cause;
	}
}
