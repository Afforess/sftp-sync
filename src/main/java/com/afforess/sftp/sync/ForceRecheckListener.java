package com.afforess.sftp.sync;

import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;

public class ForceRecheckListener implements ActionListener{
	@Override
	public void actionPerformed(ActionEvent e) {
		SFTPService.forceRecheck();
	}
}
