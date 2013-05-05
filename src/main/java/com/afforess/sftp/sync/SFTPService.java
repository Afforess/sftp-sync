package com.afforess.sftp.sync;

import java.awt.AWTException;
import java.awt.Font;
import java.awt.Image;
import java.awt.Menu;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.StreamHandler;

import javax.imageio.ImageIO;
import javax.swing.UIManager;

import org.apache.bval.jsr303.util.IOUtils;

import com.afforess.sftp.sync.yml.YAMLProcessor;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;

public class SFTPService {
	public static final Image DOWNLOADING_IMAGE;
	public static final Image IDLE_IMAGE;
	public static final Image PAUSED_IMAGE;
	static {
		try {
			DOWNLOADING_IMAGE = ImageIO.read(getResourceAsStream("/resources/downloading.png"));
			IDLE_IMAGE = ImageIO.read(getResourceAsStream("/resources/idle.png"));
			PAUSED_IMAGE = ImageIO.read(getResourceAsStream("/resources/paused.png"));
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}
	private static final Map<ServerEntry, Integer> fullyTransfered = new HashMap<ServerEntry, Integer>();
	private static final List<TransferThread> transfers = new ArrayList<TransferThread>();
	private static final List<ServerEntry> servers = new ArrayList<ServerEntry>();
	private static final PopupMenu popup = new PopupMenu();
	private static final AtomicInteger maxConnections = new AtomicInteger(2);
	private static final AtomicInteger prevConnections = new AtomicInteger(2);
	private static final AtomicLong pauseTime = new AtomicLong(-1L);
	private static final AtomicBoolean forceRecheck = new AtomicBoolean(false);
	public static void main(String[] args) {
		Logger logger = setupLogger();
		try {
			safeMain(logger);
		} catch (Exception e) {
			e.printStackTrace();
		}
		fileHandler.flush();
	}

	private static void safeMain(Logger logger) throws IOException, InterruptedException, AWTException {
		try {
			UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
		} catch (Exception e) {
			logger.log(Level.WARNING, "Failed to setup look and feel", e);
		}

		loadServers();
		loadSettings();
		SystemTray tray = SystemTray.getSystemTray();
		setupTray();
		TrayIcon trayIcon = new TrayIcon(DOWNLOADING_IMAGE, "SFTP Sync", popup);
		trayIcon.setImageAutoSize(true);
		tray.add(trayIcon);

		//Start the dl'ing
		while(true) {
			final boolean recheck = forceRecheck.get();
			int maxTransfers = getMaxConnections();
			int curTransfers = 0;
			String tooltip = "SFTP Sync";
			Iterator<TransferThread> i = transfers.iterator();
			while(i.hasNext()) {
				TransferThread thread = i.next();
				if (thread.isFinished()) {
					fullyTransfered.put(thread.getServer(), thread.getServer().getRecheckMinutes() * 60);
					i.remove();
				} else {
					//We have too many active transfers, kill some
					if (maxTransfers <= 0) {
						thread.interrupt();
						i.remove();
					} else {
						curTransfers++;
						tooltip += "\n" + thread.getDescription();
					}
					maxTransfers--;
				}
			}
			trayIcon.setToolTip(tooltip);
			//Still have available connections, find another server to check
			if (maxTransfers > 0) {
				synchronized(servers) {
				loop: for (int index = 0; index < servers.size(); index++) {
						ServerEntry entry = servers.get(index);
						//Check to see if the recheck period has expired
						int skips = fullyTransfered.containsKey(entry) ? fullyTransfered.get(entry) : 0;
						if (forceRecheck.get()) {
							fullyTransfered.remove(entry);
						} else if (skips > 0) {
							fullyTransfered.put(entry, skips - 1);
							continue;
						}
						//Already have an open connection
						for (TransferThread t : transfers) {
							if (t.getServer().equals(entry)) {
								continue loop;
							}
						}
						if (openSFTPConnection(logger, entry)) {
							curTransfers++;
							maxTransfers--;
							if (maxTransfers <= 0) {
								break;
							}
						}
					}
				}
			}
			if (getMaxConnections() == 0) {
				trayIcon.setImage(PAUSED_IMAGE);
			} else if (curTransfers > 0) {
				trayIcon.setImage(DOWNLOADING_IMAGE);
			} else {
				trayIcon.setImage(IDLE_IMAGE);
			}
			fileHandler.flush();
			if (recheck) {
				forceRecheck.set(false);
			}
			Thread.sleep(1000);
			long pause = pauseTime.get();
			if (pause > 0) {
				if (pauseTime.getAndSet(pause - 1000) <= 1000) {
					maxConnections.set(prevConnections.get());
					setupTray();
				}
			}
		}
	}

	private static boolean openSFTPConnection(Logger logger, ServerEntry entry) {
		for (int tries = 3; tries > 0; tries--) {
			//Open SFTP connection
			try {
				JSch jsch = new JSch();
				Session session = jsch.getSession(entry.getUsername(), entry.getServerHostname(), entry.getPort());
				session.setPassword(entry.getPassword());
				java.util.Properties config = new java.util.Properties();
				config.put("StrictHostKeyChecking", "no");
				session.setConfig(config);
				session.connect();
				ChannelSftp channel = (ChannelSftp) session.openChannel("sftp");
				channel.connect();
				channel.cd(entry.getRemoteDir());
				File localDir = new File(entry.getLocalDir());
				localDir.mkdirs();
				TransferThread thread = new TransferThread(entry, channel, localDir);
				transfers.add(thread);
				thread.start();
				return true;
			} catch (Exception e) {
				//Bug in JSCH, retry connection anyway
				if (e.getMessage().toLowerCase().contains("verify: false")) {
					continue;
				}
				if (e.getCause() instanceof ConnectException || e.getCause() instanceof UnknownHostException || e.getCause() instanceof NoRouteToHostException || (e.getCause() instanceof SocketException && e.getCause().getMessage().equalsIgnoreCase("Network is unreachable"))) {
					logger.warning("No connection to " + entry.getServerHostname() + ":" + entry.getPort() + ", retry in 60 seconds");
					fullyTransfered.put(entry, 60);
				} else {
					e.printStackTrace();
				}
			}
		}
		return false;
	}

	public static void addServer(ServerEntry entry) {
		synchronized(servers) {
			servers.add(entry);
			saveServers();
			setupTray();
		}
	}

	public static void removeServer(ServerEntry entry) {
		synchronized(servers) {
			servers.remove(entry);
			saveServers();
			setupTray();
		}
	}

	public static void updateServer(ServerEntry entry) {
		synchronized(servers) {
			saveServers();
			setupTray();
		}
	}

	public static void forceRecheck() {
		forceRecheck.set(true);
	}

	private static void loadSettings() {
		File settingsFile = new File(getWorkingDirectory("sftp-sync"), "settings.properties");
		if (!settingsFile.exists()) {
			return;
		}
		FileInputStream fis = null;
		try {
			Properties settings = new Properties();
			fis = new FileInputStream(settingsFile);
			settings.load(fis);
			if (settings.containsKey("max-connections")) {
				setMaxConnections(Integer.valueOf((String) settings.get("max-connections")));
			}
			if (settings.containsKey("prev-connections")) {
				prevConnections.set(Integer.valueOf((String) settings.get("prev-connections")));
			}
		} catch (IOException e) {
			e.printStackTrace();
		} finally {
			IOUtils.closeQuietly(fis);
		}
	}

	private static void saveSettings() {
		File settingsFile = new File(getWorkingDirectory("sftp-sync"), "settings.properties");
		if (!settingsFile.exists()) {
			settingsFile.getParentFile().mkdirs();
		}
		FileOutputStream fos = null;
		try {
			Properties settings = new Properties();
			settings.put("max-connections", String.valueOf(getMaxConnections()));
			settings.put("prev-connections", String.valueOf(prevConnections.get()));
			fos = new FileOutputStream(settingsFile);
			settings.store(fos, null);
		} catch (IOException e) {
			e.printStackTrace();
		} finally {
			IOUtils.closeQuietly(fos);
		}
	}

	public static int getMaxConnections() {
		return maxConnections.get();
	}

	public static void setMaxConnections(int max) {
		prevConnections.set(maxConnections.getAndSet(max));
		saveSettings();
		setupTray();
	}

	public static void setPauseTime(long time) {
		pauseTime.set(time);
	}

	private static void setupTray() {
		popup.removeAll();

		if (getMaxConnections() == 0) {
			MenuItem resume = new MenuItem("Resume");
			resume.addActionListener(new MaxConnectionsListener(prevConnections.get()));
			popup.add(resume);
		} else {
			MenuItem recheck = new MenuItem("Force Recheck");
			recheck.addActionListener(new ForceRecheckListener());
			popup.add(recheck);
			
			Menu pauseMenu = new Menu("Pause");
			
			MenuItem pause = new MenuItem("Pause");
			pause.addActionListener(new MaxConnectionsListener(0));
			pauseMenu.add(pause);
			
			MenuItem pause1 = new MenuItem("Pause - 1 hour");
			pause1.addActionListener(new MaxConnectionsListener(0, 1 * 60 * 60 * 1000L));
			pauseMenu.add(pause1);
			
			MenuItem pause3 = new MenuItem("Pause - 3 hours");
			pause3.addActionListener(new MaxConnectionsListener(0, 3 * 60 * 60 * 1000L));
			pauseMenu.add(pause3);
			
			MenuItem pause6 = new MenuItem("Pause - 6 hours");
			pause6.addActionListener(new MaxConnectionsListener(0, 6 * 60 * 60 * 1000L));
			pauseMenu.add(pause6);
			
			MenuItem pause12 = new MenuItem("Pause - 12 hours");
			pause12.addActionListener(new MaxConnectionsListener(0, 12 * 60 * 60 * 1000L));
			pauseMenu.add(pause12);

			popup.add(pauseMenu);
		}

		Menu serverList = new Menu("Server List");
		MenuItem addServer = new MenuItem("Add Server");
		addServer.addActionListener(new ServerEntryListener());
		serverList.add(addServer);
		serverList.addSeparator();

		popup.add(serverList);

		for (ServerEntry entry : servers) {
			Menu server = new Menu(entry.getAlias());
			
			MenuItem edit = new MenuItem("Edit");
			server.add(edit);
			edit.addActionListener(new ServerEntryListener(entry));
			
			MenuItem remove = new MenuItem("Remove");
			server.add(remove);
			remove.addActionListener(new RemoveServerListener(entry, remove));

			serverList.add(server);
		}
		
		Menu maxConn = new Menu("Max Connections ");
		for (int i = 1; i <= 10; i++) {
			MenuItem amt = new MenuItem(i + (i > 1 ? " Connections" : " Connection"));
			if (i == getMaxConnections()) {
				amt.setFont(UIManager.getDefaults().getFont("TabbedPane.font").deriveFont(Font.BOLD | Font.ITALIC));
			}
			amt.addActionListener(new MaxConnectionsListener(i));
			maxConn.add(amt);
		}
		
		popup.add(maxConn);

		MenuItem exit = new MenuItem("Exit");
		exit.addActionListener(new ExitActionListener());
		popup.add(exit);
	}

	private static class ExitActionListener implements ActionListener {
		@Override
		public void actionPerformed(ActionEvent e) {
			System.exit(0);
		}
	}

	private static void saveServers() {
		File serverList = new File(getWorkingDirectory("sftp-sync"), "servers.yml");
		YAMLProcessor yml = new YAMLProcessor(serverList, false);
		
		List<String> serverAliases = new ArrayList<String>();
		for (ServerEntry entry : servers) {
			serverAliases.add(entry.getAlias());
		}
		yml.setProperty("servers", serverAliases);
		for (ServerEntry entry : servers) {
			final String alias = entry.getAlias();
			yml.setProperty("server." + alias + ".host", entry.getServerHostname());
			yml.setProperty("server." + alias + ".port", entry.getPort());
			yml.setProperty("server." + alias + ".user", entry.getUsername());
			yml.setProperty("server." + alias + ".pass", entry.getPassword());
			yml.setProperty("server." + alias + ".remote", entry.getRemoteDir());
			yml.setProperty("server." + alias + ".local", entry.getLocalDir());
			yml.setProperty("server." + alias + ".sync", entry.getSyncMode());
			yml.setProperty("server." + alias + ".cooldown", entry.getRecheckMinutes());
		}
		
		yml.save();
	}

	private static void loadServers() {
		File serverList = new File(getWorkingDirectory("sftp-sync"), "servers.yml");
		YAMLProcessor yml = new YAMLProcessor(serverList, false);
		try {
			yml.load();
			List<String> aliases = yml.getStringList("servers", Collections.<String>emptyList());
			for (String alias : aliases) {
				ServerEntry server = new ServerEntry();
				server.setAlias(alias);
				server.setServerHostname(yml.getString("server." + alias + ".host"));
				server.setPort(yml.getInt("server." + alias + ".port"));
				server.setUsername(yml.getString("server." + alias + ".user"));
				server.setPassword(yml.getString("server." + alias + ".pass"));
				server.setRemoteDir(yml.getString("server." + alias + ".remote"));
				server.setLocalDir(yml.getString("server." + alias + ".local"));
				if (yml.getBoolean("server." + alias + ".sync") != null) {
					server.setSyncMode(yml.getBoolean("server." + alias + ".sync") ? 1 : 0);
				} else {
					server.setSyncMode(yml.getInt("server." + alias + ".sync", 0));
				}
				server.setRecheckMinutes(yml.getInt("server." + alias + ".cooldown"));
				servers.add(server);
			}
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	private static InputStream getResourceAsStream(String path) {
		InputStream stream = SFTPService.class.getResourceAsStream(path);
		String[] split = path.split("/");
		path = split[split.length - 1];
		if (stream == null) {
			File resource = new File(".\\src\\main\\resources\\" + path);
			if (resource.exists()) {
				try {
					stream = new BufferedInputStream(new FileInputStream(resource));
				} catch (IOException ignore) { }
			}
		}
		return stream;
	}

	private static RotatingFileHandler fileHandler;
	private static Logger setupLogger() {
		Logger logger = Logger.getLogger("sftp-sync");
		File logDirectory = new File(getWorkingDirectory("sftp-sync"), "logs");
		if (!logDirectory.exists()) {
			logDirectory.mkdirs();
		}
		File logs = new File(logDirectory, "sftp-sync_%D.log");
		fileHandler = new RotatingFileHandler(logs.getPath());

		fileHandler.setFormatter(new DateOutputFormatter(new SimpleDateFormat("yyyy/MM/dd HH:mm:ss")));

		for (Handler h : logger.getHandlers()) {
			logger.removeHandler(h);
		}
		logger.addHandler(fileHandler);

		logger.setUseParentHandlers(false);

		System.setOut(new PrintStream(new LoggerOutputStream(Level.INFO, logger), true));
		System.setErr(new PrintStream(new LoggerOutputStream(Level.SEVERE, logger), true));
		return logger;
	}

	public static File getWorkingDirectory(String applicationName) {
		String userHome = System.getProperty("user.home", ".");
		File workingDirectory;

		switch (getOS()) {
			case LINUX:
			case SOLARIS:
				workingDirectory = new File(userHome, '.' + applicationName + '/');
				break;
			case WINDOWS:
				String applicationData = System.getenv("APPDATA");
				if (applicationData != null) {
					workingDirectory = new File(applicationData, "." + applicationName + '/');
				} else {
					workingDirectory = new File(userHome, '.' + applicationName + '/');
				}
				break;
			case MAC_OS:
				workingDirectory = new File(userHome, "Library/Application Support/" + applicationName);
				break;
			default:
				workingDirectory = new File(userHome, applicationName + '/');
		}
		if ((!workingDirectory.exists()) && (!workingDirectory.mkdirs())) {
			throw new RuntimeException("The working directory could not be created: " + workingDirectory);
		}
		return workingDirectory;
	}

	private static OS getOS() {
		String osName = System.getProperty("os.name").toLowerCase();
		if (osName.contains("win")) {
			return OS.WINDOWS;
		}
		if (osName.contains("mac")) {
			return OS.MAC_OS;
		}
		if (osName.contains("solaris")) {
			return OS.SOLARIS;
		}
		if (osName.contains("sunos")) {
			return OS.SOLARIS;
		}
		if (osName.contains("linux")) {
			return OS.LINUX;
		}
		if (osName.contains("unix")) {
			return OS.LINUX;
		}
		return OS.UNKNOWN;
	}

	private enum OS {
		LINUX,
		SOLARIS,
		WINDOWS,
		MAC_OS,
		UNKNOWN;
	}

}

class LoggerOutputStream extends ByteArrayOutputStream {
	private final String separator = System.getProperty("line.separator");
	private final Level level;
	private final Logger log;

	public LoggerOutputStream(Level level, Logger log) {
		super();
		this.level = level;
		this.log = log;
	}

	@Override
	public synchronized void flush() throws IOException {
		super.flush();
		String record = this.toString();
		super.reset();

		if (record.length() > 0 && !record.equals(separator)) {
			log.logp(level, "LoggerOutputStream", "log" + level, record);
		}
	}
}

class RotatingFileHandler extends StreamHandler {
	private final SimpleDateFormat date;
	private final String logFile;
	private String filename;

	public RotatingFileHandler(String logFile) {
		this.logFile = logFile;
		date = new SimpleDateFormat("yyyy-MM-dd");
		filename = calculateFilename();
		try {
			setOutputStream(new FileOutputStream(filename, true));
		} catch (FileNotFoundException ex) {
			ex.printStackTrace();
		}
	}

	@Override
	public synchronized void flush() {
		if (!filename.equals(calculateFilename())) {
			filename = calculateFilename();
			try {
				setOutputStream(new FileOutputStream(filename, true));
			} catch (FileNotFoundException ex) {
				ex.printStackTrace();
			}
		}
		super.flush();
	}

	private String calculateFilename() {
		return logFile.replace("%D", date.format(new Date()));
	}
}

class DateOutputFormatter extends Formatter {
	private final SimpleDateFormat date;

	public DateOutputFormatter(SimpleDateFormat date) {
		this.date = date;
	}

	@Override
	public String format(LogRecord record) {
		StringBuilder builder = new StringBuilder();

		builder.append(date.format(record.getMillis()));
		builder.append(" [");
		builder.append(record.getLevel().getLocalizedName().toUpperCase());
		builder.append("] ");
		builder.append(formatMessage(record));
		builder.append('\n');

		if (record.getThrown() != null) {
			StringWriter writer = new StringWriter();
			record.getThrown().printStackTrace(new PrintWriter(writer));
			builder.append(writer.toString());
		}

		return builder.toString();
	}
}