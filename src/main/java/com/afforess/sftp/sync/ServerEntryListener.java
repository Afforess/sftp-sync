package com.afforess.sftp.sync;

import java.awt.Color;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.File;
import java.io.IOException;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JPasswordField;
import javax.swing.SwingUtilities;

import org.apache.commons.lang.StringUtils;
import org.formbuilder.Form;
import org.formbuilder.FormBuilder;
import org.formbuilder.TypeMapper;
import org.formbuilder.mapping.change.ChangeHandler;
import org.formbuilder.mapping.typemapper.impl.StringMapper;
import org.formbuilder.util.GridBagPanel;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpException;

public class ServerEntryListener implements ActionListener{
	private final ServerEntry defaultEntry;
	private final boolean editing;
	public ServerEntryListener() {
		defaultEntry = new ServerEntry();
		defaultEntry.setPort(22);
		defaultEntry.setRemoteDir(".");
		editing = false;
	}

	public ServerEntryListener(ServerEntry entry) {
		defaultEntry = entry;
		editing = true;
	}

	@Override
	public void actionPerformed(ActionEvent e) {
		SwingUtilities.invokeLater(new Runnable() {
			@Override
			public void run() {
				//Set up form
				final JFrame frame = new JFrame();
				final Form<ServerEntry> form = FormBuilder.map(ServerEntry.class)
						.useForProperty("password", new PasswordTypeMapper())
						.useForProperty("localDir", new FilePickerTypeMatter())
						.buildForm();
				GridBagPanel panel = (GridBagPanel) form.asComponent();
				frame.add(panel);
				
				//Add test conn button
				final JButton button = new JButton("Save Connection");
				button.addActionListener(new ConnectionTestListener(form, frame, button));
				panel.add(button, 9, 1);
				
				//Size of form
				frame.setIconImage(SFTPService.DOWNLOADING_IMAGE);
				frame.setSize(300, 220);
				frame.setResizable(false);
				frame.setVisible(true);
				
				//Set default form values
				form.setValue(defaultEntry);
			}
		});
	}

	private static class PasswordTypeMapper extends StringMapper<JPasswordField> implements TypeMapper<JPasswordField, String> {
		@Override
		@Nonnull
		public JPasswordField createEditorComponent() {
			return new JPasswordField();
		}
	}

	private static class FilePickerTypeMatter implements TypeMapper<JButton, String> {
		private File directory = new File(".");
		@Override
		@Nonnull
		public JButton createEditorComponent() {
			JButton openDirectories = new JButton("Select Directory");
			openDirectories.addActionListener(new DirectoryButtonListener(openDirectories));
			return openDirectories;
		}

		@Override
		public void handleChanges(@Nonnull JButton editorComponent, @Nonnull ChangeHandler changeHandler) {
			
		}

		@Override
		@Nullable
		public String getValue(@Nonnull JButton editorComponent) {
			try {
				return directory.getCanonicalPath();
			} catch (IOException e) {
				return directory.getAbsolutePath();
			}
		}

		@Override
		@Nonnull
		public Class<String> getValueClass() {
			return String.class;
		}

		@Override
		public void setValue(@Nonnull JButton editorComponent, @Nullable String value) {
			if (value == null) {
				value = ".";
			}
			directory = new File(value);
			editorComponent.setToolTipText("Selected Directory: " + getValue(editorComponent));
		}

		private class DirectoryButtonListener implements ActionListener {
			private final JButton parent;
			public DirectoryButtonListener(JButton parent) {
				this.parent = parent;
			}

			@Override
			public void actionPerformed(ActionEvent e) {
				JFileChooser chooser = new JFileChooser();
				chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
				chooser.setSelectedFile(directory.getAbsoluteFile());
				int selection = chooser.showOpenDialog(null);
				if (selection == JFileChooser.APPROVE_OPTION) {
					setValue(parent, chooser.getSelectedFile().getAbsolutePath());
				}
			}
		}
	}

	private class ConnectionTestListener implements ActionListener {
		private final Form<ServerEntry> form;
		private final JFrame frame;
		private final JButton button;
		public ConnectionTestListener(Form<ServerEntry> form, JFrame frame, JButton button) {
			this.form = form;
			this.frame = frame;
			this.button = button;
		}

		@Override
		public void actionPerformed(ActionEvent e) {
			JSch jsch = new JSch();
			try {
				Session session = jsch.getSession(form.getValue().getUsername(), form.getValue().getServerHostname(), form.getValue().getPort());
				session.setPassword(form.getValue().getPassword());
				java.util.Properties config = new java.util.Properties();
				config.put("StrictHostKeyChecking", "no");
				session.setConfig(config);
				session.connect();
				ChannelSftp channel = (ChannelSftp) session.openChannel("sftp");
				channel.connect();
				if (channel.isConnected()) {
					channel.cd(form.getValue().getRemoteDir());
					success(frame, button, form.getValue());
				} else {
					failure(button, "Unable to connect!");
				}
			} catch (JSchException e1) {
				failure(button, e1.getMessage());
				e1.printStackTrace();
			} catch (SftpException e1) {
				if ("no such file".equalsIgnoreCase(e1.getMessage())) {
					failure(button, "Invalid remote directory");
				} else {
					failure(button, e1.getMessage());
				}
				e1.printStackTrace();
			}
		}
	}

	private void success(final JFrame frame, final JButton button, final ServerEntry server) {
		button.setText("Valid Connection");
		button.setOpaque(true);
		button.setForeground(Color.GREEN);
		button.setEnabled(false);
		if (!editing) {
			SFTPService.addServer(server);
		} else {
			defaultEntry.set(server);
			SFTPService.updateServer(defaultEntry);
		}
		new DelayedSwingTask(new Runnable() {
			@Override
			public void run() {
				frame.setVisible(false);
			}
		}, 5000L).start();
	}

	private void failure(final JButton button, String msg) {
		button.setText(StringUtils.capitalize(msg));
		button.setOpaque(true);
		button.setForeground(Color.RED);
		button.setEnabled(false);
		new DelayedSwingTask(new Runnable() {
			@Override
			public void run() {
				button.setEnabled(true);
				button.setText("Save Connection");
				button.setOpaque(false);
				button.setForeground(Color.BLACK);
			}
		}, 5000L).start();
	}
}