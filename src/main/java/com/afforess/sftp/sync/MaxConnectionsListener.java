package com.afforess.sftp.sync;

import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;

public class MaxConnectionsListener implements ActionListener{
	private final int amt;
	private final long time;
	public MaxConnectionsListener(int amt) {
		this(amt, -1L);
	}

	public MaxConnectionsListener(int amt, long time) {
		this.amt = amt;
		this.time = time;
	}

	@Override
	public void actionPerformed(ActionEvent e) {
		SFTPService.setMaxConnections(amt);
		SFTPService.setPauseTime(time);
	}
}
