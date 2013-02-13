package com.afforess.sftp.sync;

import javax.swing.SwingUtilities;

public class DelayedSwingTask extends Thread{
	private final Runnable run;
	private final long delay;
	public DelayedSwingTask(Runnable run, long delay) {
		super("Delayed Task");
		this.run = run;
		this.delay = delay;
		setDaemon(true);
	}

	@Override
	public void run() {
		try {
			Thread.sleep(delay);
		} catch (InterruptedException e) { }
		SwingUtilities.invokeLater(run);
	}
}
