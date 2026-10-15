package dev.aerogel.server;

import dev.aerogel.common.AeroGelConfig;
import dev.aerogel.common.ProxyTopology;
import dev.aerogel.net.ClientSettingPayload;
import dev.aerogel.net.ClientSwitchPayload;
import dev.aerogel.proxy.BiomeTint;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.util.TriState;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.EntityTeleportEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Game-bus handlers for the server side (own class: NeoForge bus registration is per-bus strict).
 */
public final class ServerHandlers {
    private static final Logger LOGGER = LoggerFactory.getLogger("aerogel");
    private static final String[] TIER_NAMES = { "5", "9", "25", "81" };
    /** client bisect switches plus the server-side toggles, all under {@code /aerogel switch}. */
    private static final String[] SWITCH_FEATURES = {
        "proxy", "travel", "anim", "sync", "store", "clamp", "reconcile", "wireframe", "neighbors",
        "colors", "movecheck", "promote", "gate", "stream", "resend", "adaptive"
    };

    private ServerHandlers() {
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        MinecraftServer server = event.getServer();
        AeroGelConfig.validate(); // cross-field config sanity, before anything reads a value
        ProxyStreamer.init(server);
        DevProbes.onServerStarted(server);
    }

    private static long tickStartNanos;

    @SubscribeEvent
    public static void onServerTickPre(ServerTickEvent.Pre event) {
        tickStartNanos = System.nanoTime();
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        long dt = System.nanoTime() - tickStartNanos;
        if (dt >= 100_000_000L) {
            LOGGER.warn("server tick stall: {} ms", String.format("%.2f", dt / 1e6));
        }
        DevProbes.onServerTick(event.getServer());
        ProxyStreamer.onServerTick(event);
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(
            Commands.literal("aerogel")
                .then(Commands.literal("flyspeed")
                    .requires(source -> source.hasPermission(2))
                    .then(Commands.argument("speed", FloatArgumentType.floatArg(0.05f, 10.0f))
                        .executes(ctx -> {
                            ServerPlayer player = ctx.getSource().getPlayerOrException();
                            float speed = FloatArgumentType.getFloat(ctx, "speed");
                            player.getAbilities().setFlyingSpeed(speed);
                            player.onUpdateAbilities();
                            ctx.getSource().sendSuccess(() -> Component.literal("AeroGel: creative flying speed = " + speed), false);
                            return 1;
                        })))
                .then(Commands.literal("generate")
                    .requires(source -> source.hasPermission(2))
                    .executes(ctx -> {
                        ServerPlayer player = ctx.getSource().getPlayerOrException();
                        ServerLevel level = (ServerLevel) player.level();
                        ChunkPos pos = player.chunkPosition();
                        boolean ok = ProxyChunkGate.promote(level, pos);
                        ctx.getSource().sendSuccess(() -> Component.literal(ok
                            ? "AeroGel: promoting proxy chunk " + pos + " to real terrain"
                            : "AeroGel: cannot promote here (proxy inactive)"), false);
                        return ok ? 1 : 0;
                    }))
                .then(Commands.literal("biomes")
                    .executes(ctx -> {
                        String dump = BiomeTint.dump();
                        LOGGER.info(dump);
                        ctx.getSource().sendSuccess(() -> Component.literal(dump), false);
                        return 1;
                    }))
                .then(Commands.literal("diag")
                    .executes(ctx -> {
                        ServerPlayer player = ctx.getSource().getPlayerOrException();
                        if (!clientHasAeroGel(ctx, player)) {
                            return 0;
                        }
                        PacketDistributor.sendToPlayer(player, new ClientSettingPayload(ClientSettingPayload.DIAG, 0));
                        ctx.getSource().sendSuccess(() -> Component.literal(
                            "AeroGel: client proxy diagnostic written to the client log"), false);
                        return 1;
                    }))
                .then(Commands.literal("switch")
                    .then(Commands.argument("feature", StringArgumentType.word())
                        .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(SWITCH_FEATURES, builder))
                        .executes(ctx -> switchFeature(ctx, null))
                        .then(Commands.argument("state", BoolArgumentType.bool())
                            .executes(ctx -> switchFeature(ctx, BoolArgumentType.getBool(ctx, "state"))))))
                .then(Commands.literal("detail")
                    .then(Commands.literal("near")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.argument("tier", StringArgumentType.word())
                            .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(TIER_NAMES, builder))
                            .executes(ctx -> setNear(ctx, StringArgumentType.getString(ctx, "tier")))))
                    .then(Commands.literal("far")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.argument("tier", StringArgumentType.word())
                            .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(TIER_NAMES, builder))
                            .executes(ctx -> setFar(ctx, StringArgumentType.getString(ctx, "tier")))))
                    .then(Commands.literal("neardist")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.argument("chunks", IntegerArgumentType.integer(1, 1000))
                            .executes(ctx -> setNearDist(ctx, IntegerArgumentType.getInteger(ctx, "chunks")))))
                    .then(Commands.literal("radius")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.argument("chunks", IntegerArgumentType.integer(4, 1000))
                            .executes(ctx -> setRadius(ctx, IntegerArgumentType.getInteger(ctx, "chunks")))))
                    .then(Commands.literal("draw")
                        .then(Commands.argument("chunks", IntegerArgumentType.integer(0, 1000))
                            .executes(ctx -> setDraw(ctx, IntegerArgumentType.getInteger(ctx, "chunks")))))
                    .then(Commands.literal("threads")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.argument("count", IntegerArgumentType.integer(1, 64))
                            .executes(ctx -> setThreads(ctx, IntegerArgumentType.getInteger(ctx, "count"))))))
                .then(Commands.literal("promote")
                    .requires(source -> source.hasPermission(2))
                    .then(Commands.literal("range")
                        .then(Commands.argument("chunks", IntegerArgumentType.integer(-1, 64))
                            .executes(ctx -> setMaxRange(ctx, IntegerArgumentType.getInteger(ctx, "chunks")))))
                    .then(Commands.literal("linger")
                        .then(Commands.argument("minIdle", IntegerArgumentType.integer(1, 600))
                            .then(Commands.argument("minBusy", IntegerArgumentType.integer(1, 600))
                                .then(Commands.argument("maxIdle", IntegerArgumentType.integer(1, 600))
                                    .then(Commands.argument("maxBusy", IntegerArgumentType.integer(1, 600))
                                        .executes(ctx -> setLinger(ctx,
                                            IntegerArgumentType.getInteger(ctx, "minIdle"),
                                            IntegerArgumentType.getInteger(ctx, "minBusy"),
                                            IntegerArgumentType.getInteger(ctx, "maxIdle"),
                                            IntegerArgumentType.getInteger(ctx, "maxBusy"))))))))
                    .then(Commands.literal("landing")
                        .then(Commands.argument("blocks", DoubleArgumentType.doubleArg(0.0, 64.0))
                            .executes(ctx -> setLanding(ctx, DoubleArgumentType.getDouble(ctx, "blocks")))))
                    .then(Commands.literal("concurrency")
                        .then(Commands.argument("count", IntegerArgumentType.integer(1, 16))
                            .executes(ctx -> setConcurrency(ctx, IntegerArgumentType.getInteger(ctx, "count")))))
                    .then(Commands.literal("budget")
                        .then(Commands.argument("fraction", DoubleArgumentType.doubleArg(0.05, 1.0))
                            .executes(ctx -> setBudget(ctx, DoubleArgumentType.getDouble(ctx, "fraction")))))
                    .then(Commands.literal("cost")
                        .then(Commands.argument("ms", DoubleArgumentType.doubleArg(1.0, 5000.0))
                            .executes(ctx -> setCost(ctx, DoubleArgumentType.getDouble(ctx, "ms")))))
                    .then(Commands.literal("hardgate")
                        .then(Commands.argument("ms", DoubleArgumentType.doubleArg(0.0, 1000.0))
                            .executes(ctx -> setHardGate(ctx, DoubleArgumentType.getDouble(ctx, "ms")))))
                    .then(Commands.literal("replay")
                        .then(Commands.argument("ms", DoubleArgumentType.doubleArg(0.1, 50.0))
                            .executes(ctx -> setReplay(ctx, DoubleArgumentType.getDouble(ctx, "ms")))))
                    .then(Commands.literal("refuse")
                        .then(Commands.argument("state", BoolArgumentType.bool())
                            .executes(ctx -> setRefuseStubWrites(ctx, BoolArgumentType.getBool(ctx, "state")))))
                    .then(Commands.literal("status")
                        .executes(ServerHandlers::promoteStatus)))
                .then(Commands.literal("adaptive")
                    .requires(source -> source.hasPermission(2))
                    .then(Commands.literal("status")
                        .executes(ServerHandlers::adaptiveStatus))
                    .then(Commands.literal("activate")
                        .then(Commands.argument("ms", DoubleArgumentType.doubleArg(1.0, 200.0))
                            .executes(ctx -> setAdaptiveActivate(ctx, DoubleArgumentType.getDouble(ctx, "ms")))))
                    .then(Commands.literal("deactivate")
                        .then(Commands.argument("ms", DoubleArgumentType.doubleArg(1.0, 200.0))
                            .executes(ctx -> setAdaptiveDeactivate(ctx, DoubleArgumentType.getDouble(ctx, "ms")))))
                    .then(Commands.literal("hold")
                        .then(Commands.argument("seconds", IntegerArgumentType.integer(0, 600))
                            .executes(ctx -> setAdaptiveHold(ctx, IntegerArgumentType.getInteger(ctx, "seconds")))))
                    .then(Commands.literal("teleport")
                        .then(Commands.argument("on", BoolArgumentType.bool())
                            .executes(ctx -> setAdaptiveTeleport(ctx, BoolArgumentType.getBool(ctx, "on")))))
                    .then(Commands.literal("window")
                        .then(Commands.argument("seconds", IntegerArgumentType.integer(0, 60))
                            .executes(ctx -> setAdaptiveWindow(ctx, IntegerArgumentType.getInteger(ctx, "seconds")))))
                    .then(Commands.literal("backlog")
                        .then(Commands.argument("activate", IntegerArgumentType.integer(1, 100000))
                            .then(Commands.argument("deactivate", IntegerArgumentType.integer(0, 100000))
                                .executes(ctx -> setAdaptiveBacklog(ctx,
                                    IntegerArgumentType.getInteger(ctx, "activate"),
                                    IntegerArgumentType.getInteger(ctx, "deactivate"))))))));
    }

    private static int setNear(CommandContext<CommandSourceStack> ctx, String requested) {
        byte tier = ProxyTopology.parse(requested);
        if (!ProxyTopology.valid(tier)) {
            ctx.getSource().sendFailure(Component.literal("AeroGel: near tier must be one of five, nine, 25, 81"));
            return 0;
        }
        ProxyStreamer.setTopology(tier);
        ctx.getSource().sendSuccess(() -> Component.literal("AeroGel: near tier = " + ProxyTopology.name(tier)
            + " (within " + ProxyStreamer.nearDistance() + " chunks)"), false);
        return 1;
    }

    private static int setFar(CommandContext<CommandSourceStack> ctx, String requested) {
        byte tier = ProxyTopology.parse(requested);
        if (!ProxyTopology.valid(tier)) {
            ctx.getSource().sendFailure(Component.literal("AeroGel: far tier must be one of five, nine, 25, 81"));
            return 0;
        }
        ProxyStreamer.setFarTier(tier);
        ctx.getSource().sendSuccess(() -> Component.literal("AeroGel: far tier = " + ProxyTopology.name(tier)), false);
        return 1;
    }

    /** client-only: extend the proxy draw range beyond the vanilla render-distance option (0 = option). */
    private static int setDraw(CommandContext<CommandSourceStack> ctx, int chunks) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        if (!clientHasAeroGel(ctx, player)) {
            return 0;
        }
        PacketDistributor.sendToPlayer(player, new ClientSettingPayload(ClientSettingPayload.DRAW_RADIUS, chunks));
        ctx.getSource().sendSuccess(() -> Component.literal("AeroGel: client proxy draw radius = "
            + (chunks == 0 ? "vanilla option" : chunks + " chunks")), false);
        return 1;
    }

    private static int setRadius(CommandContext<CommandSourceStack> ctx, int requested) {
        ProxyStreamer.setRadius(requested);
        ctx.getSource().sendSuccess(() -> Component.literal("AeroGel: proxy radius = " + ProxyStreamer.radius() + " chunks"), false);
        return 1;
    }

    private static int setNearDist(CommandContext<CommandSourceStack> ctx, int chunks) {
        ProxyStreamer.setNearDistance(chunks);
        ctx.getSource().sendSuccess(() -> Component.literal("AeroGel: near distance = " + ProxyStreamer.nearDistance() + " chunks"), false);
        return 1;
    }

    private static int setThreads(CommandContext<CommandSourceStack> ctx, int count) {
        ProxyStreamer.setThreads(count);
        ctx.getSource().sendSuccess(() -> Component.literal("AeroGel: sampler threads = " + ProxyStreamer.threads()), false);
        return 1;
    }

    private static int setRefuseStubWrites(CommandContext<CommandSourceStack> ctx, boolean on) {
        PromotionManager.setRefuseStubWrites(on);
        ctx.getSource().sendSuccess(() -> Component.literal("AeroGel: refusing writes into not-yet-generated chunks = "
            + (on ? "on (placements fail until the chunk is real)" : "off (writes are deferred and replayed)")), false);
        return 1;
    }

    private static int setMaxRange(CommandContext<CommandSourceStack> ctx, int chunks) {
        PromotionManager.setMaxRange(chunks);
        MinecraftServer server = ctx.getSource().getServer();
        ctx.getSource().sendSuccess(() -> Component.literal("AeroGel: promotion range = "
            + (chunks < 0 ? "auto (" + PromotionManager.effectiveMaxRange(server) + " chunks)" : chunks + " chunks")), false);
        return 1;
    }

    private static int setLinger(CommandContext<CommandSourceStack> ctx, int minIdle, int minBusy, int maxIdle, int maxBusy) {
        PromotionManager.setLingerSeconds(minIdle, minBusy, maxIdle, maxBusy);
        ctx.getSource().sendSuccess(() -> Component.literal("AeroGel: linger = "
            + PromotionManager.lingerMinIdleSeconds() + ".." + PromotionManager.lingerMaxIdleSeconds() + "s idle / "
            + PromotionManager.lingerMinBusySeconds() + ".." + PromotionManager.lingerMaxBusySeconds() + "s busy"), false);
        return 1;
    }

    private static int setLanding(CommandContext<CommandSourceStack> ctx, double blocks) {
        PromotionManager.setLandingMargin(blocks);
        ctx.getSource().sendSuccess(() -> Component.literal("AeroGel: landing margin = "
            + String.format("%.1f", PromotionManager.landingMargin()) + " blocks"), false);
        return 1;
    }

    private static int setConcurrency(CommandContext<CommandSourceStack> ctx, int count) {
        PromotionManager.setMaxInFlight(count);
        ctx.getSource().sendSuccess(() -> Component.literal("AeroGel: promotion concurrency = " + PromotionManager.maxInFlight()), false);
        return 1;
    }

    private static int setBudget(CommandContext<CommandSourceStack> ctx, double fraction) {
        PromotionManager.setMaxGenFraction(fraction);
        ctx.getSource().sendSuccess(() -> Component.literal("AeroGel: generation budget = "
            + (int) (PromotionManager.maxGenFraction() * 1000) + " ms/s"), false);
        return 1;
    }

    private static int setCost(CommandContext<CommandSourceStack> ctx, double ms) {
        PromotionManager.setChunkCostMs(ms);
        ctx.getSource().sendSuccess(() -> Component.literal("AeroGel: promotion cost = "
            + String.format("%.1f", PromotionManager.chunkCostMs()) + " ms"), false);
        return 1;
    }

    private static int setHardGate(CommandContext<CommandSourceStack> ctx, double ms) {
        PromotionManager.setHardGateMs(ms);
        ctx.getSource().sendSuccess(() -> Component.literal("AeroGel: promotion hard gate = "
            + String.format("%.1f", PromotionManager.hardGateMs()) + " ms"), false);
        return 1;
    }

    private static int setReplay(CommandContext<CommandSourceStack> ctx, double ms) {
        PromotionManager.setReplayBudgetMs(ms);
        ctx.getSource().sendSuccess(() -> Component.literal("AeroGel: deferred-write replay budget = "
            + String.format("%.1f", PromotionManager.replayBudgetMs()) + " ms/tick"), false);
        return 1;
    }

    private static int promoteStatus(CommandContext<CommandSourceStack> ctx) {
        MinecraftServer server = ctx.getSource().getServer();
        float tickMs = server.getCurrentSmoothedTickTime();
        String status = PromotionManager.status(server, tickMs);
        ctx.getSource().sendSuccess(() -> Component.literal("AeroGel: " + status), false);
        return 1;
    }

    private static int adaptiveStatus(CommandContext<CommandSourceStack> ctx) {
        MinecraftServer server = ctx.getSource().getServer();
        String status = AdaptiveMode.status(server, server.getCurrentSmoothedTickTime());
        ctx.getSource().sendSuccess(() -> Component.literal("AeroGel: " + status), false);
        return 1;
    }

    private static int setAdaptiveActivate(CommandContext<CommandSourceStack> ctx, double ms) {
        AdaptiveMode.setActivateTickMs(ms);
        ctx.getSource().sendSuccess(() -> Component.literal("AeroGel: adaptive activate = "
            + String.format("%.1f", AdaptiveMode.activateTickMs()) + " ms"), false);
        return 1;
    }

    private static int setAdaptiveDeactivate(CommandContext<CommandSourceStack> ctx, double ms) {
        AdaptiveMode.setDeactivateTickMs(ms);
        ctx.getSource().sendSuccess(() -> Component.literal("AeroGel: adaptive deactivate = "
            + String.format("%.1f", AdaptiveMode.deactivateTickMs()) + " ms"), false);
        return 1;
    }

    private static int setAdaptiveHold(CommandContext<CommandSourceStack> ctx, int seconds) {
        AdaptiveMode.setHoldSeconds(seconds);
        ctx.getSource().sendSuccess(() -> Component.literal("AeroGel: adaptive hold = "
            + AdaptiveMode.holdSeconds() + " s"), false);
        return 1;
    }

    private static int setAdaptiveTeleport(CommandContext<CommandSourceStack> ctx, boolean on) {
        AdaptiveMode.setEngageOnTeleport(on);
        ctx.getSource().sendSuccess(() -> Component.literal("AeroGel: adaptive engage on teleport = "
            + AdaptiveMode.engageOnTeleport()), false);
        return 1;
    }

    private static int setAdaptiveWindow(CommandContext<CommandSourceStack> ctx, int seconds) {
        AdaptiveMode.setWindowSeconds(seconds);
        ctx.getSource().sendSuccess(() -> Component.literal("AeroGel: adaptive window = "
            + AdaptiveMode.windowSeconds() + " s"), false);
        return 1;
    }

    private static int setAdaptiveBacklog(CommandContext<CommandSourceStack> ctx, int activate, int deactivate) {
        AdaptiveMode.setBacklogActivate(activate);
        AdaptiveMode.setBacklogDeactivate(deactivate);
        ctx.getSource().sendSuccess(() -> Component.literal("AeroGel: adaptive backlog = activate "
            + AdaptiveMode.backlogActivate() + ", deactivate " + AdaptiveMode.backlogDeactivate()), false);
        return 1;
    }

    /** per-player server-side mirror of each client switch, so {@code /aerogel switch <feature>} can toggle. */
    private static final Map<java.util.UUID, Map<String, Boolean>> switchState = new HashMap<>();

    /** client→server sync of its persisted switch state on join, so a bare toggle flips the right way. */
    public static void onClientSwitch(ServerPlayer player, ClientSwitchPayload payload) {
        switchState.computeIfAbsent(player.getUUID(), id -> new HashMap<>()).put(payload.feature(), payload.on());
        AdaptiveMode.onClientSwitch(player, payload);
    }

    private static int switchFeature(CommandContext<CommandSourceStack> ctx, Boolean requested) throws CommandSyntaxException {
        String feature = StringArgumentType.getString(ctx, "feature");
        boolean client = ClientSwitchPayload.isFeature(feature);
        boolean known = client || feature.equals("colors") || feature.equals("movecheck")
            || feature.equals("promote") || feature.equals("gate") || feature.equals("stream")
            || feature.equals("resend") || feature.equals("adaptive");
        if (!known) {
            ctx.getSource().sendFailure(Component.literal("AeroGel: unknown feature '" + feature + "'"));
            return 0;
        }
        // per-player client toggles stay open to everyone; server-wide features need op level 2
        if (!client && !ctx.getSource().hasPermission(2)) {
            ctx.getSource().sendFailure(Component.literal("AeroGel: '" + feature + "' is server-wide and needs permission level 2"));
            return 0;
        }
        ServerPlayer actor = ctx.getSource().getEntity() instanceof ServerPlayer player ? player : null;
        if (client && !clientHasAeroGel(ctx, ctx.getSource().getPlayerOrException())) {
            return 0;
        }
        boolean on = requested != null ? requested : !currentSwitch(ctx, feature, client);
        if (actor != null) {
            switchState.computeIfAbsent(actor.getUUID(), id -> new HashMap<>()).put(feature, on);
        }
        if (client) {
            ServerPlayer player = ctx.getSource().getPlayerOrException();
            AdaptiveMode.setPlayerSwitch(player, feature, on);
            PacketDistributor.sendToPlayer(player, new ClientSwitchPayload(feature, on));
        } else if (feature.equals("colors")) {
            ProxyStreamer.setBiomeColors(on);
        } else if (feature.equals("promote")) {
            PromotionManager.setEnabled(on);
        } else if (feature.equals("gate")) {
            ProxyChunkGate.setGateEnabled(on);
        } else if (feature.equals("stream")) {
            ProxyStreamer.setStreamEnabled(on);
        } else if (feature.equals("resend")) {
            ProxyChunkGate.setResendEnabled(on);
        } else if (feature.equals("adaptive")) {
            AdaptiveMode.setEnabled(on);
        } else {
            ProxyStreamer.setBypassMoveCheck(on);
        }
        ctx.getSource().sendSuccess(() -> Component.literal("AeroGel: " + feature + " = " + (on ? "on" : "off")), false);
        return 1;
    }

    private static boolean currentSwitch(CommandContext<CommandSourceStack> ctx, String feature, boolean client) {
        if (feature.equals("colors")) {
            return ProxyStreamer.biomeColors();
        }
        if (feature.equals("movecheck")) {
            return ProxyStreamer.bypassMoveCheck();
        }
        if (feature.equals("promote")) {
            return PromotionManager.enabled();
        }
        if (feature.equals("gate")) {
            return ProxyChunkGate.gateEnabled();
        }
        if (feature.equals("stream")) {
            return ProxyStreamer.streamEnabled();
        }
        if (feature.equals("resend")) {
            return ProxyChunkGate.resendEnabled();
        }
        if (feature.equals("adaptive")) {
            return AdaptiveMode.enabled();
        }
        if ((feature.equals("travel") || feature.equals("proxy"))
            && ctx.getSource().getEntity() instanceof ServerPlayer player) {
            return AdaptiveMode.playerSwitch(player, feature);
        }
        if (ctx.getSource().getEntity() instanceof ServerPlayer player) {
            return switchState.getOrDefault(player.getUUID(), Map.of())
                .getOrDefault(feature, ClientSwitchPayload.defaultOn(feature));
        }
        return ClientSwitchPayload.defaultOn(feature);
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        PromotionManager.finishPendingWrites(event.getServer()); // before the save; the streamer shutdown clears state
        ProxyStreamer.shutdown();
    }

    /**
     * #6: a player's click on a proxy block is refused (item kept), placement into a proxy on the clicked face
     * only loses the item use (the clicked block's own use still runs); the stub chunk is promoted, not deferred.
     */
    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        if (PromotionManager.refusePlayerUse(level, event.getPos())) {
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.FAIL);
        } else if (PromotionManager.refusePlayerUse(level, event.getPos().relative(event.getHitVec().getDirection()))) {
            event.setUseItem(TriState.FALSE);
        }
    }

    /** #14: every {@link EntityTeleportEvent} subtype fires before the move; LOWEST so a cancel or new target wins. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onTeleport(EntityTeleportEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            AdaptiveMode.onTeleport(player, event.getTargetX(), event.getTargetZ());
        }
    }

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        ProxyStreamer.onPlayerTick(event);
    }

    /** client-only settings need AeroGel on the client (#7); a vanilla client gets a message, not a packet. */
    private static boolean clientHasAeroGel(CommandContext<CommandSourceStack> ctx, ServerPlayer player) {
        if (AdaptiveMode.hasAeroGel(player)) {
            return true;
        }
        ctx.getSource().sendFailure(Component.literal("AeroGel: your client does not have AeroGel installed"));
        return false;
    }

    @SubscribeEvent
    public static void onPlayerLogin(net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            AdaptiveMode.onLogin(player);
        }
    }

    @SubscribeEvent
    public static void onPlayerLogout(net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent event) {
        switchState.remove(event.getEntity().getUUID());
        ProxyStreamer.onPlayerLogout(event);
    }
}
