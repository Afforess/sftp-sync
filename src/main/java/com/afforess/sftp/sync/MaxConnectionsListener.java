package com.afforess.sftp.sync;

import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;

public class MaxConnectionsListener implements ActionListener{
	private final int amt;
	public MaxConnectionsListener(int amt) {
		this.amt = amt;
	}

	@Override
	public void actionPerformed(ActionEvent e) {
		SFTPService.setMaxConnections(amt);
	}
}
