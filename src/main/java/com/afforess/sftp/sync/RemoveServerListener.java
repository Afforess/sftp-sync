package com.afforess.sftp.sync;

import java.awt.MenuItem;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;

public class RemoveServerListener implements ActionListener{
	private static final String REMOVED = "Removed";
	private final ServerEntry entry;
	private final MenuItem item;
	public RemoveServerListener(ServerEntry entry, MenuItem item) {
		this.entry = entry;
		this.item = item;
	}

	@Override
	public void actionPerformed(ActionEvent e) {
		SFTPService.removeServer(entry);
		item.setLabel(REMOVED);
	}
}
