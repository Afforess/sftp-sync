package com.afforess.sftp.sync;

import java.awt.AWTException;
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
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.StreamHandler;

import javax.imageio.ImageIO;
import javax.swing.UIManager;

import com.afforess.sftp.sync.yml.YAMLProcessor;

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
	private static final List<ServerEntry> servers = new ArrayList<ServerEntry>();
	private static final List<ServerDaemon> daemons = new ArrayList<ServerDaemon>();
	private static final AtomicLong pausedTime = new AtomicLong(-1L);
	private static final PopupMenu popup = new PopupMenu();
	private static TrayIcon trayIcon;
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
		SystemTray tray = SystemTray.getSystemTray();
		setupTray();
		trayIcon = new TrayIcon(DOWNLOADING_IMAGE, "SFTP Sync", popup);
		trayIcon.setImageAutoSize(true);
		tray.add(trayIcon);
		StringBuilder builder = new StringBuilder("");
		while(true) {
			try {
				Thread.sleep(100);
				long paused = pausedTime.get();
				if (paused != -1L && System.currentTimeMillis() > paused) {
					setTrayIcon(DOWNLOADING_IMAGE);
					pausedTime.set(-1L);
					setupTray();
				}
				builder.delete(0, builder.length());
				synchronized(SFTPService.class) {
					boolean first = true;
					for (TooltipLine line : lines) {
						String text = line.getTooltip();
						if (text != null) {
							if (!first) {
								builder.append('\n');
							}
							first = false;
							builder.append(text);
						}
					}
				}
				if (builder.length() == 0) {
					builder.append("SFTP Sync");
					setTrayIcon(IDLE_IMAGE);
				} else {
					setTrayIcon(DOWNLOADING_IMAGE);
				}
				trayIcon.setToolTip(builder.toString());
			} catch (Exception e) {
				logger.log(Level.SEVERE, "Unhandled exception", e);
			}
		}
	}

	public static synchronized void setTrayIcon(Image image) {
		trayIcon.setImage(image);
	}

	public static synchronized void addServer(ServerEntry entry) {
		servers.add(entry);
		saveServers();
		setupTray();
		addDaemon(entry);
	}

	public static synchronized void removeServer(ServerEntry entry) {
		servers.remove(entry);
		removeDaemon(entry);
		saveServers();
		setupTray();
	}

	public static synchronized void updateServer(ServerEntry entry) {
		removeDaemon(entry);
		addDaemon(entry);
		saveServers();
		setupTray();
	}

	private static List<TooltipLine> lines = new ArrayList<TooltipLine>();
	public static synchronized void addTooltip(TooltipLine line) {
		lines.add(line);
	}

	public static synchronized void removeTooltip(TooltipLine line) {
		lines.remove(line);
	}

	private static synchronized void addDaemon(ServerEntry entry) {
		ThreadFactory factory = new NamedThreadFactory("Server [" + entry.getAlias() + "] Daemon - %1");
		ResumableExecutorService service = new ResumableExecutorService(factory, entry.getSyncMode() == SyncMode.UPLOAD.getMode() ? 2 : Runtime.getRuntime().availableProcessors());
		ServerDaemon daemon = new ServerDaemon(entry, service);
		daemons.add(daemon);
		long paused = pausedTime.get();
		if (paused != -1L) {
			daemon.setPaused(paused);
		}
		daemon.start();
	}

	private static synchronized void removeDaemon(ServerEntry entry) {
		Iterator<ServerDaemon> i = daemons.iterator();
		while(i.hasNext()) {
			ServerDaemon daemon = i.next();
			if (daemon.getAlias().equals(daemon.getAlias())) {
				i.remove();
				daemon.shutdown();
			}
		}
	}

	public static synchronized void forceRecheck() {
		for (ServerDaemon server : daemons) {
			server.forceUpdate();
		}
	}

	private static synchronized void setupTray() {
		popup.removeAll();

		if (pausedTime.get() != -1L) {
			MenuItem resume = new MenuItem("Resume");
			resume.addActionListener(new PauseActionListener(-1L));
			popup.add(resume);
		} else {
			MenuItem recheck = new MenuItem("Force Recheck");
			recheck.addActionListener(new ForceRecheckListener());
			popup.add(recheck);
			
			Menu pauseMenu = new Menu("Pause");
			
			MenuItem pause = new MenuItem("Pause");
			pause.addActionListener(new PauseActionListener(365 * 24 * 60 * 60 * 1000L));
			pauseMenu.add(pause);
			
			MenuItem pause1 = new MenuItem("Pause - 1 hour");
			pause1.addActionListener(new PauseActionListener(1 * 60 * 60 * 1000L));
			pauseMenu.add(pause1);
			
			MenuItem pause3 = new MenuItem("Pause - 3 hours");
			pause3.addActionListener(new PauseActionListener(3 * 60 * 60 * 1000L));
			pauseMenu.add(pause3);
			
			MenuItem pause6 = new MenuItem("Pause - 6 hours");
			pause6.addActionListener(new PauseActionListener(6 * 60 * 60 * 1000L));
			pauseMenu.add(pause6);
			
			MenuItem pause12 = new MenuItem("Pause - 12 hours");
			pause12.addActionListener(new PauseActionListener(12 * 60 * 60 * 1000L));
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

		MenuItem exit = new MenuItem("Exit");
		exit.addActionListener(new ExitActionListener());
		popup.add(exit);
	}

	private static class PauseActionListener implements ActionListener {
		final long time;
		PauseActionListener(long time) {
			this.time = time;
		}

		@Override
		public void actionPerformed(ActionEvent e) {
			pausedTime.set(System.currentTimeMillis() + time);
			setupTray();
			if (time != -1L) {
				setTrayIcon(PAUSED_IMAGE);
			} else {
				setTrayIcon(IDLE_IMAGE);
			}
			for (ServerDaemon server : daemons) {
				if (time != -1L) {
					server.setPaused(System.currentTimeMillis() + time);
				} else {
					server.setPaused(-1L);
				}
			}
		}
	}

	private static class ExitActionListener implements ActionListener {
		@Override
		public void actionPerformed(ActionEvent e) {
			System.exit(0);
		}
	}

	private static void saveServers() {
		synchronized(servers) {
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
	}

	private static void loadServers() {
		synchronized(servers) {
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
			for (ServerEntry entry : servers) {
				addDaemon(entry);
			}
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

	@Override
	public void publish(LogRecord record) {
		super.publish(record);
		flush();
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