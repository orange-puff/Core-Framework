package com.openrsc.server;

import com.openrsc.server.constants.Constants;
import com.openrsc.server.content.achievement.AchievementSystem;
import com.openrsc.server.database.GameDatabase;
import com.openrsc.server.database.JDBCDatabase;
import com.openrsc.server.database.impl.mysql.MySqlGameDatabase;
import com.openrsc.server.database.impl.mysql.MySqlGameLogger;
import com.openrsc.server.database.impl.sqlite.SqliteGameDatabase;
import com.openrsc.server.database.patches.JDBCPatchApplier;
import com.openrsc.server.database.patches.PatchApplier;
import com.openrsc.server.event.custom.DailyShutdownEvent;
import com.openrsc.server.event.custom.HourlyResetEvent;
import com.openrsc.server.event.rsc.FinitePeriodicEvent;
import com.openrsc.server.event.rsc.handler.GameEventHandler;
import com.openrsc.server.event.rsc.GameTickEvent;
import com.openrsc.server.event.rsc.impl.combat.scripts.CombatScriptLoader;
import com.openrsc.server.external.EntityHandler;
import com.openrsc.server.model.entity.npc.Npc;
import com.openrsc.server.model.entity.player.Player;
import com.openrsc.server.model.world.World;
import com.openrsc.server.net.*;
import com.openrsc.server.net.rsc.ActionSender;
import com.openrsc.server.net.rsc.Crypto;
import com.openrsc.server.plugins.handler.PluginHandler;
import com.openrsc.server.plugins.triggers.StartupTrigger;
import com.openrsc.server.service.IPlayerService;
import com.openrsc.server.service.PcapLoggerService;
import com.openrsc.server.service.PlayerService;
import com.openrsc.server.util.LogUtil;
import com.openrsc.server.util.NamedThreadFactory;
import com.openrsc.server.util.ServerAwareThreadFactory;
import com.openrsc.server.util.SystemUtil;
import com.openrsc.server.util.languages.I18NService;
import com.openrsc.server.util.rsc.CaptchaGenerator;
import com.openrsc.server.util.rsc.MessageType;
import com.openrsc.server.util.rsc.StringUtil;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.apache.logging.log4j.util.Unbox.box;

public class Server implements Runnable {

	/**
	 * The asynchronous logger.
	 */
	private static final Logger LOGGER;

	public static final ConcurrentHashMap<String, Server> serversList = new ConcurrentHashMap<>();

	private final GameStateUpdater gameUpdater;
	private final GameEventHandler gameEventHandler;
	private final DiscordService discordService;
	private final LoginExecutor loginExecutor;
	private final ServerConfiguration config;
	private ScheduledExecutorService scheduledExecutor;
	private final PluginHandler pluginHandler;
	private final CombatScriptLoader combatScriptLoader;
	private final EntityHandler entityHandler;
	private final MySqlGameLogger gameLogger;
	private final PcapLoggerService pcapLogger;
	private final GameDatabase database;
	private final AchievementSystem achievementSystem;
	private final Constants constants;
	private final RSCPacketFilter packetFilter;
	private final IPlayerService playerService;
	private final I18NService i18nService;

	private final World world;
	private final String name;

	private GameTickEvent shutdownEvent;
	private ChannelFuture serverChannel;
	private EventLoopGroup workerGroup;
	private EventLoopGroup bossGroup;

	private volatile Boolean running = false;
	private boolean restarting = false;
	private boolean shuttingDown = false;

	private long serverStartedTime = 0;

	private long lastIncomingPacketsDuration = 0;
	private long lastEventsDuration = 0;
	private long lastOutgoingPacketsDuration = 0;
	private long lastWorldUpdateDuration = 0;
	private long lastProcessPlayersDuration = 0;
	private long lastProcessNpcsDuration = 0;
	private long lastProcessMessageQueuesDuration = 0;
	private long lastUpdateClientsDuration = 0;
	private long lastDoCleanupDuration = 0;
	private long lastExecuteWalkToActionsDuration = 0;

	private long lastTickDuration = 0;
	private long timeLate = 0;
	private long lastTickTimestamp = 0;
	private final Map<Integer, Long> incomingTimePerPacketOpcode = new HashMap<>();
	private final Map<Integer, Integer> incomingCountPerPacketOpcode = new HashMap<>();
	private final Map<Integer, Long> outgoingTimePerPacketOpcode = new HashMap<>();
	private final Map<Integer, Integer> outgoingCountPerPacketOpcode = new HashMap<>();
	private int privateMessagesSent = 0;

	private volatile int maxItemId;

	static {
		Thread.currentThread().setName("InitThread");
		LogUtil.configure();
		LOGGER = LogManager.getLogger();
	}

	private static String getDefaultConfigFileName() {
		return "default.conf";
	}

	public static Server startServer(final String confName) throws IOException {
		final long startTime = System.currentTimeMillis();
		final Server server = new Server(confName);
		if (!server.isRunning()) {
			server.start();
		}
		final long endTime = System.currentTimeMillis();
		final long bootTime = endTime - startTime;
		LOGGER.info(server.getName() + " started in " + bootTime + "ms");

		return server;
	}

	public static boolean closeProcess(final int seconds, final String message) {
		for (final Server server : serversList.values()) {
			if (server.shutdownEvent != null) {
				return false;
			}
		}

		for (final Server server : serversList.values()) {
			if (message != null) {
				String[] messages = message.split(": % %");
				for (final Player playerToUpdate : server.getWorld().getPlayers()) {
					if (playerToUpdate.getClientLimitations().supportsMessageBox) {
						ActionSender.sendBox(playerToUpdate, message, false);
					} else {
						for (String msg : messages) {
							playerToUpdate.playerServerMessage(MessageType.QUEST, msg);
						}
					}
				}
			}

			server.shutdown(seconds);
		}

		return true;
	}

	public static void main(final String[] args) {
		LOGGER.info("Launching Game Server...");
		try {
			List<String> configurationFiles = new ArrayList<>();
			Optional.ofNullable(System.getProperty("conf")).ifPresent(files -> {
				configurationFiles.addAll(
						Arrays.stream(files.split(",")).map(file -> file + ".conf").collect(Collectors.toList())
				);
			});

			configurationFiles.addAll(Arrays.asList(args));

			if (configurationFiles.size() == 0) {
				LOGGER.info(
					"Server Configuration file not provided. Loading from {} or local.conf.",
					getDefaultConfigFileName()
			);

				try {
					startServer(getDefaultConfigFileName());
				} catch (final Throwable t) {
					LOGGER.catching(t);
					SystemUtil.exit(1);
				}
			} else {
				for (String configuration : configurationFiles) {
					try {
						startServer(configuration);
					} catch (final Throwable t) {
						LOGGER.catching(t);
						SystemUtil.exit(1);
					}
				}
			}

			while (serversList.size() > 0) {
				try {
					Thread.sleep(1000);
				} catch (final InterruptedException e) {
					e.printStackTrace();
				}

				for (final Server server : serversList.values()) {
					server.checkShutdown();
				}
			}
		} catch(Exception ex) {
			LOGGER.error("Error starting server: ", ex);
			SystemUtil.exit(1);
		}

		LOGGER.info("Exiting server process...");
		SystemUtil.exit(0);
	}

	public Server(final String configFile) throws IOException {
		config = new ServerConfiguration();
		getConfig().initConfig(configFile);
		LOGGER.info("Server configuration loaded: " + getConfig().configFile);

		name = getConfig().SERVER_NAME;

		packetFilter = new RSCPacketFilter(this);

		pluginHandler = new PluginHandler(this);
		combatScriptLoader = new CombatScriptLoader(this);
		constants = new Constants(this);
		switch (getConfig().DB_TYPE){
			case MYSQL:
				database = new MySqlGameDatabase(this);
				break;
			case SQLITE:
				database = new SqliteGameDatabase(this);
				break;
			default:
				database = null;
				LOGGER.error("No database type");
				SystemUtil.exit(1);
				break;
		}

		final boolean wantDiscordBot = getConfig().WANT_DISCORD_BOT;
		final boolean wantDiscordAuctionUpdates = getConfig().WANT_DISCORD_AUCTION_UPDATES;
		final boolean wantDiscordMonitoringUpdates = getConfig().WANT_DISCORD_MONITORING_UPDATES;
		discordService = wantDiscordBot || wantDiscordAuctionUpdates || wantDiscordMonitoringUpdates ? new DiscordService(this) : null;
		loginExecutor = new LoginExecutor(this);
		world = new World(this);
		gameEventHandler = new GameEventHandler(this);
		gameUpdater = new GameStateUpdater(this);
		gameLogger = new MySqlGameLogger(this, (MySqlGameDatabase)database);
		pcapLogger = new PcapLoggerService(this);
		entityHandler = new EntityHandler(this);
		achievementSystem = new AchievementSystem(this);
		playerService = new PlayerService(world, config, database);
		i18nService = new I18NService(this);

		maxItemId = 0;
	}

	public void checkShutdown() {
		if (isShuttingDown()) {
			stop();
			if (isRestarting()) {
				start();
				restarting = false;
			}
			shuttingDown = false;
		}
	}

	public void start() {
		synchronized (running) {
			try {
				if (isRunning()) {
					return;
				}

				scheduledExecutor = Executors.newSingleThreadScheduledExecutor(
						new ServerAwareThreadFactory(
								getName() + " : GameThread",
								config
						)
				);
				scheduledExecutor.scheduleAtFixedRate(this, 0, 10, TimeUnit.MILLISECONDS);

				// Do not allow two servers to be started with the same name
				// We will bypass that if we are restarting because we never removed this server from the list.
				if (!isRestarting() && serversList.get(this.getName()) != null) {
					throw new IllegalArgumentException("Can not initialize. Server " + this.getName() + " already exists.");
				}

				LOGGER.info("Connecting to Database...");
				try {
					getDatabase().open();
				} catch (final Exception ex) {
					LOGGER.catching(ex);
					SystemUtil.exit(1);
				}
				LOGGER.info("Database Connection Completed");

				LOGGER.info("Checking For Database Structure Changes...");
				PatchApplier patchApplier = new JDBCPatchApplier(
						(JDBCDatabase) getDatabase(),
						getConfig().DB_TABLE_PREFIX
				);
				if (!patchApplier.applyPatches()) {
					LOGGER.error("Unable to apply database patches");
					SystemUtil.exit(1);
				}

				if (getConfig().LOAD_PRERENDERED_SLEEPWORDS) {
					LOGGER.info("Loading Prerendered Sleepword Images...");
					CaptchaGenerator.loadPrerenderedCaptchas();
					LOGGER.info("Loaded " + CaptchaGenerator.prerenderedSleepwordsSize + " Prerendered Sleepword Images");
				}

				if (getConfig().LOAD_SPECIAL_PRERENDERED_SLEEPWORDS) {
					LOGGER.info("Loading Special Prerendered Sleepword Images...");
					CaptchaGenerator.loadSpecialPrerenderedCaptchas();
					LOGGER.info("Loaded " + CaptchaGenerator.prerenderedSleepwordsSpecialSize + " Special Prerendered Sleepword Images");
				}

				LOGGER.info("Loading Game Definitions...");
				getEntityHandler().load();
				LOGGER.info("Definitions Completed");

				LOGGER.info("Loading Game State Updater...");
				getGameUpdater().load();
				LOGGER.info("Game State Updater Completed");

				LOGGER.info("Loading Game Event Handler...");
				getGameEventHandler().load();
				LOGGER.info("Game Event Handler Completed");

				LOGGER.info("Loading Combat Scripts...");
				getCombatScriptLoader().load();
				LOGGER.info("Combat Scripts Completed");

				LOGGER.info("Loading World...");
				getWorld().load();
				LOGGER.info("World Completed");

				LOGGER.info("Loading Plugins...");
				getPluginHandler().load();
				LOGGER.info("Plugins Completed");

				/*LOGGER.info("Loading Achievements...");
				getAchievementSystem().load();
				LOGGER.info("Achievements Completed");*/

				LOGGER.info("Loading LoginExecutor...");
				getLoginExecutor().start();
				LOGGER.info("LoginExecutor Completed");

				if (getDiscordService() != null) {
					LOGGER.info("Loading DiscordService...");
					getDiscordService().start();
					LOGGER.info("DiscordService Completed");
				}

				LOGGER.info("Loading GameLogger...");
				getGameLogger().start();
				LOGGER.info("GameLogger Completed");

				LOGGER.info("Loading PcapLogger...");
				getPcapLogger().start();
				LOGGER.info("PcapLogger Completed");

				LOGGER.info("Loading Packet Filter...");
				getPacketFilter().load();
				LOGGER.info("Packet Filter Completed");

                Crypto.init();

				maxItemId = getDatabase().getMaxItemID();
				LOGGER.info("Set max item ID to : " + maxItemId);

				bossGroup = new NioEventLoopGroup(
						0,
						new NamedThreadFactory(getName() + " : IOBossThread", getConfig())
				);
				workerGroup = new NioEventLoopGroup(
						0,
						new NamedThreadFactory(getName() + " : IOWorkerThread", getConfig())
				);
				final ServerBootstrap bootstrap = new ServerBootstrap();
				final Server serverOwner = this;

				bootstrap.group(bossGroup, workerGroup).channel(NioServerSocketChannel.class).childHandler(
					new ChannelInitializer<SocketChannel>() {
						@Override
						protected void initChannel(final SocketChannel channel) {
							final ChannelPipeline pipeline = channel.pipeline();
							pipeline.addLast("decoder", new RSCProtocolDecoder());
							pipeline.addLast("encoder", new RSCProtocolEncoder());
							pipeline.addLast("handler", new RSCConnectionHandler(serverOwner));
						}
					}
				);

				bootstrap.childOption(ChannelOption.TCP_NODELAY, true);
				bootstrap.childOption(ChannelOption.SO_KEEPALIVE, false);
				bootstrap.childOption(ChannelOption.SO_RCVBUF, 10000);
				bootstrap.childOption(ChannelOption.SO_SNDBUF, 10000);
				try {
					getPluginHandler().handlePlugin(StartupTrigger.class);
					serverChannel = bootstrap.bind(new InetSocketAddress(getConfig().SERVER_PORT)).sync();
					LOGGER.info("Game world is now online on port {}!", box(getConfig().SERVER_PORT));
                    LOGGER.info("RSA exponent: " + Crypto.getPublicExponent());
                    LOGGER.info("RSA modulus: " + Crypto.getPublicModulus());
				} catch (final InterruptedException e) {
					LOGGER.error(e);
				}

				// Only add this server to the active servers list if it's not already there
				if (!isRestarting()) {
					serversList.put(this.getName(), this);
				}

				lastTickTimestamp = serverStartedTime = System.nanoTime();
				running = true;
			} catch (final Throwable t) {
				LOGGER.catching(t);
				SystemUtil.exit(1);
			}
		}
	}

	public void stop() {
		synchronized (running) {
			try {
				if (!isRunning()) {
					return;
				}

				getWorld().unloadPlayers();

				scheduledExecutor.shutdown();
				try {
					final boolean terminationResult = scheduledExecutor.awaitTermination(1, TimeUnit.MINUTES);
					if (!terminationResult) {
						LOGGER.error("Server thread termination failed");
						List<Runnable> skippedTasks = scheduledExecutor.shutdownNow();
						LOGGER.error("{} task(s) never commenced execution", skippedTasks.size());
					}
				} catch (final InterruptedException e) {
					LOGGER.catching(e);
				}
				getLoginExecutor().stop();
				if (getDiscordService() != null) {
					getDiscordService().stop();
				}
				getGameLogger().stop();
				getGameUpdater().unload();
				getGameEventHandler().unload();
				getEntityHandler().unload();
				getPluginHandler().unload();
				getCombatScriptLoader().unload();
				getPacketFilter().unload();
				getPcapLogger().stop();
				//getAchievementSystem().unload();
				getWorld().unload();
				getDatabase().close();
				bossGroup.shutdownGracefully().sync();
				workerGroup.shutdownGracefully().sync();
				serverChannel.channel().closeFuture().sync();

				shutdownEvent = null;
				serverChannel = null;
				bossGroup = null;
				workerGroup = null;
				scheduledExecutor = null;

				maxItemId = 0;
				serverStartedTime = 0;
				lastIncomingPacketsDuration = 0;
				lastEventsDuration = 0;
				lastOutgoingPacketsDuration = 0;
				lastTickDuration = 0;
				timeLate = 0;
				lastTickTimestamp = 0;
				incomingTimePerPacketOpcode.clear();
				incomingCountPerPacketOpcode.clear();
				outgoingTimePerPacketOpcode.clear();
				outgoingCountPerPacketOpcode.clear();

				// Don't remove this server from the active servers list if we are just restarting.
				if (!isRestarting()) {
					serversList.remove(this.getName());
				}

				running = false;

				LOGGER.info("Server unloaded");
			} catch (final Throwable t) {
				LOGGER.catching(t);
				SystemUtil.exit(1);
			}
		}
	}

	public long bench(final Runnable r) {
		final long start = System.nanoTime();
		r.run();
		final long end = System.nanoTime();
		return end - start;
	}

	@Override
	public void run() {
		LogUtil.populateThreadContext(getConfig());
		synchronized (running) {
			try {
				this.timeLate = System.nanoTime() - lastTickTimestamp;
				if (getTimeLate() >= getConfig().GAME_TICK * 1000000L) {
					this.timeLate -= getConfig().GAME_TICK * 1000000L;

					// Doing the set in two stages here such that the whole tick has access to the same values for profiling information.
					this.lastTickDuration = bench(() -> {
						try {
							resetBenchmarkDurations();
							incrementLastEventsDuration(getGameEventHandler().processNonPlayerEvents());
							incrementLastWorldUpdateDuration(getGameUpdater().updateWorld());
							for (final Player player : getWorld().getPlayers()) {
								player.processTick();
							}
							incrementLastExecuteWalkToActionsDuration(getGameUpdater().executePidlessCatching());
							incrementLastProcessMessageQueuesDuration(getWorld().processGlobalMessageQueue());
							incrementLastProcessNpcsDuration(getGameUpdater().processNpcs());
							for (final Player player : getWorld().getPlayers()) {
								player.sendUpdates();
							}
							incrementLastDoCleanupDuration(getGameUpdater().doCleanup());
							getGameEventHandler().cleanupEvents();
						} catch (final Throwable t) {
							LOGGER.catching(t);
						}
					});

					monitorTickPerformance();

					dailyShutdownEvent();
					// not ideal location but is safe guarded to only keep 1
					resetEvent();

					// Set us to be in the next tick.
					advanceTicks(1);

					// Clear out the outgoing and incoming packet processing time frames
					incomingTimePerPacketOpcode.clear();
					incomingCountPerPacketOpcode.clear();
					outgoingTimePerPacketOpcode.clear();
					outgoingCountPerPacketOpcode.clear();

					//LOGGER.info("Tick " + getCurrentTick() + " processed.");
				} else {
					if (getConfig().WANT_CUSTOM_WALK_SPEED) {
						World world = getWorld();
						for (final Player p : getWorld().getPlayers()) {
							p.updatePosition();
							getGameUpdater().executeWalkToActions(p);
						}

						world.getNpcs().forEach(Npc::updatePosition);
					}
				}
			} catch (final Throwable t) {
				LOGGER.catching(t);
			}
		}
	}

	private void dailyShutdownEvent() {
		try {
			if (getConfig().WANT_AUTO_SERVER_SHUTDOWN) {
				List<GameTickEvent> events = getWorld().getServer().getGameEventHandler().getEvents();
				for (GameTickEvent event : events) {
					if (!(event instanceof DailyShutdownEvent)) continue;

					// There is already a daily shutdown running!;
					// do nothing!
					return;
				}
				getWorld().getServer().getGameEventHandler().add(new DailyShutdownEvent(getWorld(), 1, getConfig().RESTART_HOUR));
				/*int hour = LocalDateTime.now().getHour();
				int minute = LocalDateTime.now().getMinute();

				if (hour == getConfig().RESTART_HOUR && minute == 0)
					getWorld().getServer().shutdown(300);*/
			}
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	private void resetEvent() {
		if (getConfig().WANT_RESET_EVENT) {
			List<GameTickEvent> events = getWorld().getServer().getGameEventHandler().getEvents();
			for (GameTickEvent event : events) {
				if (!(event instanceof HourlyResetEvent)) continue;

				// There is already an hourly reset running!;
				// do nothing!
				return;
			}
			getWorld().getServer().getGameEventHandler().add(new HourlyResetEvent(getWorld(), 48, 0));
		}
	}

	private void monitorTickPerformance() {
		// Store the current tick because we can modify it by calling skipTicks()
		final long currentTick = getCurrentTick();
		// Check if processing game tick took longer than the tick
		final boolean isLastTickLate = (getLastTickDuration() / 1000000) > getConfig().GAME_TICK;
		final long ticksLate = (getTimeLate() / 1000000) / getConfig().GAME_TICK;
		final boolean isServerLate = ticksLate >= 1;

		if (isLastTickLate) {
			// Current tick processing took too long.
			final String message = "Tick " + currentTick + " is late: " +
				(getLastTickDuration() / 1000000) + "ms " +
				(getLastIncomingPacketsDuration() / 1000000) + "ms " +
				(getLastEventsDuration() / 1000000) + "ms " +
				(getLastOutgoingPacketsDuration() / 1000000) + "ms";

			sendMonitoringWarning(message, true);
		}
		if (isServerLate) {
			// Server fell behind, skip ticks
			advanceTicks(ticksLate);
			final String ticksSkipped = ticksLate > 1 ? "ticks (" + (currentTick+1) + " - " + (currentTick+ticksLate) + ")" : "tick (" + (currentTick+ticksLate) + ")";
			final String message = "Tick " + currentTick + " " + getTimeLate() / 1000000 + "ms behind. Skipping " + ticksLate + " " + ticksSkipped;
			sendMonitoringWarning(message, false);
		}
	}

	private void sendMonitoringWarning(final String message, final boolean showEventData) {
		if (getConfig().DEBUG) { // only displays in-client to logged in staff players if server config debug is true
			for (Player p : getWorld().getPlayers()) {
				if (!p.isDev())
					continue;

				p.playerServerMessage(MessageType.QUEST, getWorld().getServer().getConfig().MESSAGE_PREFIX + message);
			}
		}

		LOGGER.warn(message);
		if (getWorld().getServer().getDiscordService() != null) {
			getWorld().getServer().getDiscordService().monitoringSendServerBehind(message, showEventData);
		}
	}

	public boolean shutdown(final int seconds) {
		if (shutdownEvent != null) {
			return false;
		}
		shutdownEvent = new FinitePeriodicEvent(getWorld(), null, seconds * 1000 / getConfig().GAME_TICK, 1, "Server shut down") {
			int ticksElapsed = 0;

			@Override
			public void action() {
				int secs = (int) (getTimeLeftMillis() / 1000);
				if (ticksElapsed % 10 == 0) {
					for (final Player playerToUpdate : getWorld().getPlayers()) {
						if (playerToUpdate.getClientLimitations().supportsSystemUpdateTimer) {
							ActionSender.sendSystemUpdateTimer(playerToUpdate,  secs);
						} else if (ticksElapsed % 50 == 0) {
							ActionSender.sendSystemMessage(playerToUpdate, "System update in " + StringUtil.formatTime(secs));
						}
					}
				}
				if (ticksElapsed >= getNumIterations()) {
					shuttingDown = true;
				}
				ticksElapsed++;
			}
		};
		getGameEventHandler().add(shutdownEvent);

		return true;
	}

	public boolean restart(final int seconds) {
		if (shutdownEvent != null) {
			return false;
		}
		shutdownEvent = new FinitePeriodicEvent(getWorld(), null, seconds * 1000 / getConfig().GAME_TICK, 1, "Server shut down") {
			int ticksElapsed = 0;

			@Override
			public void action() {
				int secs = (int) (getTimeLeftMillis() / 1000);
				if (ticksElapsed % 10 == 0) {
					for (final Player playerToUpdate : getWorld().getPlayers()) {
						if (playerToUpdate.getClientLimitations().supportsSystemUpdateTimer) {
							ActionSender.sendSystemUpdateTimer(playerToUpdate,  secs);
						} else if (ticksElapsed % 50 == 0) {
							ActionSender.sendSystemMessage(playerToUpdate, "System update in: " + StringUtil.formatTime(secs));
						}
					}
				}
				if (ticksElapsed >= getNumIterations()) {
					shuttingDown = true;
					restarting = true;
				}
				ticksElapsed++;
			}
		};
		getGameEventHandler().add(shutdownEvent);

		return true;
	}

	public long getTimeUntilShutdown() {
		if (shutdownEvent == null) {
			return -1;
		}
		return Math.max(((FinitePeriodicEvent)shutdownEvent).getTimeLeftMillis(), 0);
	}

	public final long getLastEventsDuration() {
		return lastEventsDuration;
	}

	public final long getLastTickDuration() {
		return lastTickDuration;
	}

	public final GameEventHandler getGameEventHandler() {
		return gameEventHandler;
	}

	public final GameStateUpdater getGameUpdater() {
		return gameUpdater;
	}

	public final DiscordService getDiscordService() {
		return discordService;
	}

	public final LoginExecutor getLoginExecutor() {
		return loginExecutor;
	}

	public final RSCPacketFilter getPacketFilter() {
		return packetFilter;
	}

  	public final int clearAllIpBans() {
    return packetFilter.clearAllIpBans();
  }

	public final int recalculateLoggedInCounts() {
		return packetFilter.recalculateLoggedInCounts();
	}

	public final int getPlayersCount(String hostAddress) {
		return packetFilter.getPlayersCount(hostAddress);
	}

	public final long getLastIncomingPacketsDuration() {
		return lastIncomingPacketsDuration;
	}

	public final long getLastOutgoingPacketsDuration() {
		return lastOutgoingPacketsDuration;
	}

	public long getLastWorldUpdateDuration() {
		return lastWorldUpdateDuration;
	}

	public long getLastProcessPlayersDuration() {
		return lastProcessPlayersDuration;
	}

	public long getLastProcessNpcsDuration() {
		return lastProcessNpcsDuration;
	}

	public long getLastProcessMessageQueuesDuration() {
		return lastProcessMessageQueuesDuration;
	}

	public long getLastUpdateClientsDuration() {
		return lastUpdateClientsDuration;
	}

	public long getLastDoCleanupDuration() {
		return lastDoCleanupDuration;
	}

	public long getLastExecuteWalkToActionsDuration() {
		return lastExecuteWalkToActionsDuration;
	}

	public final long getTimeLate() {
		return timeLate;
	}

	public final long getServerStartedTime() {
		return serverStartedTime;
	}

	public final long getCurrentTick() {
		return (lastTickTimestamp - getServerStartedTime()) / (getConfig().GAME_TICK * 1000000);
	}

	private void advanceTicks(final long ticks) {
		lastTickTimestamp += ticks * getConfig().GAME_TICK * 1000000;
	}

	public final ServerConfiguration getConfig() {
		return config;
	}

	public final boolean isRunning() {
		return running;
	}

	public final Constants getConstants() {
		return constants;
	}

	public synchronized World getWorld() {
		return world;
	}

	public String getName() {
		return name;
	}

	public PluginHandler getPluginHandler() {
		return pluginHandler;
	}

	public CombatScriptLoader getCombatScriptLoader() {
		return combatScriptLoader;
	}

	public MySqlGameLogger getGameLogger() {
		return gameLogger;
	}

	public PcapLoggerService getPcapLogger() {
		return pcapLogger;
	}

	public EntityHandler getEntityHandler() {
		return entityHandler;
	}

	public GameDatabase getDatabase() {
		return database;
	}

	public IPlayerService getPlayerService() { return playerService; }

	public AchievementSystem getAchievementSystem() {
		return achievementSystem;
	}

	public I18NService getI18nService() {
		return i18nService;
	}

	public boolean isRestarting() {
		return restarting;
	}

	public boolean isShuttingDown() {
		return shuttingDown;
	}

	public Map<Integer, Long> getIncomingTimePerPacketOpcode() {
		return incomingTimePerPacketOpcode;
	}

	public Map<Integer, Integer> getIncomingCountPerPacketOpcode() {
		return incomingCountPerPacketOpcode;
	}

	public Map<Integer, Long> getOutgoingTimePerPacketOpcode() {
		return outgoingTimePerPacketOpcode;
	}

	public Map<Integer, Integer> getOutgoingCountPerPacketOpcode() {
		return outgoingCountPerPacketOpcode;
	}

	public void addIncomingPacketDuration(final int packetOpcode, final long additionalTime) {
		if (!incomingTimePerPacketOpcode.containsKey(packetOpcode)) {
			incomingTimePerPacketOpcode.put(packetOpcode, 0L);
		}
		incomingTimePerPacketOpcode.put(packetOpcode, incomingTimePerPacketOpcode.get(packetOpcode) + additionalTime);
	}

	public void incrementIncomingPacketCount(final int packetOpcode) {
		if (!incomingCountPerPacketOpcode.containsKey(packetOpcode)) {
			incomingCountPerPacketOpcode.put(packetOpcode, 0);
		}
		incomingCountPerPacketOpcode.put(packetOpcode, incomingCountPerPacketOpcode.get(packetOpcode) + 1);
	}

	public void addOutgoingPacketDuration(final int packetOpcode, final long additionalTime) {
		if (!outgoingTimePerPacketOpcode.containsKey(packetOpcode)) {
			outgoingTimePerPacketOpcode.put(packetOpcode, 0L);
		}
		outgoingTimePerPacketOpcode.put(packetOpcode, outgoingTimePerPacketOpcode.get(packetOpcode) + additionalTime);
	}

	public void incrementOutgoingPacketCount(final int packetOpcode) {
		if (!outgoingCountPerPacketOpcode.containsKey(packetOpcode)) {
			outgoingCountPerPacketOpcode.put(packetOpcode, 0);
		}
		outgoingCountPerPacketOpcode.put(packetOpcode, outgoingCountPerPacketOpcode.get(packetOpcode) + 1);
	}

	public synchronized int getMaxItemID() {
		return maxItemId;
	}

	public synchronized int incrementMaxItemID() {
		return ++maxItemId;
	}

	public synchronized int incrementPrivateMessagesSent() {
		return ++privateMessagesSent;
	}

	public synchronized void incrementLastIncomingPacketsDuration(final long duration) {
		this.lastIncomingPacketsDuration += duration;
	}

	public synchronized void incrementLastEventsDuration(final long duration) {
		this.lastEventsDuration += duration;
	}

	public synchronized void incrementLastOutgoingPacketsDuration(final long duration) {
		this.lastOutgoingPacketsDuration += duration;
	}

	public synchronized void incrementLastWorldUpdateDuration(final long duration) {
		this.lastWorldUpdateDuration += duration;
	}

	public synchronized void incrementLastProcessPlayersDuration(final long duration) {
		this.lastProcessPlayersDuration += duration;
	}

	public synchronized void incrementLastProcessNpcsDuration(final long duration) {
		this.lastProcessNpcsDuration += duration;
	}

	public synchronized void incrementLastProcessMessageQueuesDuration(final long duration) {
		this.lastProcessMessageQueuesDuration += duration;
	}

	public synchronized void incrementLastUpdateClientsDuration(final long duration) {
		this.lastUpdateClientsDuration += duration;
	}

	public synchronized void incrementLastDoCleanupDuration(final long duration) {
		this.lastDoCleanupDuration += duration;
	}

	public synchronized void incrementLastExecuteWalkToActionsDuration(final long duration) {
		this.lastExecuteWalkToActionsDuration += duration;
	}

	public synchronized void resetBenchmarkDurations() {
		this.lastIncomingPacketsDuration = 0;
		this.lastEventsDuration = 0;
		this.lastOutgoingPacketsDuration = 0;
		this.lastWorldUpdateDuration = 0;
		this.lastProcessPlayersDuration = 0;
		this.lastProcessNpcsDuration = 0;
		this.lastProcessMessageQueuesDuration = 0;
		this.lastUpdateClientsDuration = 0;
		this.lastDoCleanupDuration = 0;
		this.lastExecuteWalkToActionsDuration = 0;
	}
}
