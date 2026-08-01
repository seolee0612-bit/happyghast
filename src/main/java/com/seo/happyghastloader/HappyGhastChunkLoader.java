package com.seo.happyghastloader;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.entity.Entity;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.world.ChunkTicketType;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

/**
 * Server-side Fabric mod for Minecraft 1.21.1.
 *
 * Registration flow:
 *   /happyghast initialize <custom name>
 *
 * The name is used only for the initial search. The entity is then tracked by
 * UUID, allowing its name to be changed later without losing registration.
 */
public final class HappyGhastChunkLoader implements ModInitializer {
    public static final String MOD_ID = "happyghastchunkloader";
    private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir()
            .resolve("happy-ghast-chunk-loader.json");

    /** Search radius used by /happyghast initialize. */
    private static final double SEARCH_RADIUS = 64.0;

    /**
     * Ticket radius 2 is the same level used for a fully ticking forced chunk
     * in this Minecraft generation. Each ghast UUID is its own ticket argument,
     * so two ghasts sharing a chunk do not remove each other's tickets.
     */
    private static final int TICKET_RADIUS = 2;

    /** Wait this long for a registered entity to appear after its last chunk is loaded. */
    private static final int MISSING_TIMEOUT_TICKS = 200;

    private static final ChunkTicketType<UUID> HAPPY_GHAST_TICKET = ChunkTicketType.create(
            MOD_ID,
            Comparator.comparing(UUID::toString)
    );

    private static final Map<UUID, TrackedGhast> TRACKED = new HashMap<>();
    private static long serverTick;

    @Override
    public void onInitialize() {
        CommandRegistrationCallback.EVENT.register(HappyGhastChunkLoader::registerCommands);
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            loadConfig();
            restoreLastKnownTickets(server);
            LOGGER.info("Loaded {} registered Happy Ghast(s).", TRACKED.size());
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            releaseAllTickets(server);
            saveConfig();
        });
        ServerTickEvents.END_SERVER_TICK.register(HappyGhastChunkLoader::tickServer);
    }

    private static void registerCommands(
            com.mojang.brigadier.CommandDispatcher<ServerCommandSource> dispatcher,
            CommandRegistryAccess registryAccess,
            net.minecraft.server.command.CommandManager.RegistrationEnvironment environment
    ) {
        dispatcher.register(literal("happyghast")
                .requires(source -> source.hasPermissionLevel(2))
                .then(literal("initialize")
                        .then(argument("name", StringArgumentType.greedyString())
                                .executes(context -> initialize(
                                        context.getSource(),
                                        StringArgumentType.getString(context, "name")
                                ))))
                .then(literal("list").executes(context -> list(context.getSource())))
                .then(literal("remove")
                        .then(argument("uuid", StringArgumentType.word())
                                .executes(context -> remove(
                                        context.getSource(),
                                        StringArgumentType.getString(context, "uuid")
                                ))))
                .then(literal("clear").executes(context -> clear(context.getSource()))));
    }

    private static int initialize(ServerCommandSource source, String requestedName) {
        String name = unquote(requestedName.trim());
        if (name.isBlank()) {
            source.sendError(Text.literal("A Happy Ghast name is required."));
            return 0;
        }

        ServerWorld world = source.getWorld();
        Vec3d origin = source.getPosition();
        Box searchBox = Box.of(origin, SEARCH_RADIUS * 2.0, SEARCH_RADIUS * 2.0, SEARCH_RADIUS * 2.0);

        List<Entity> matches = world.getOtherEntities(null, searchBox, entity ->
                isHappyGhast(entity)
                        && entity.hasCustomName()
                        && name.equals(entity.getCustomName().getString())
        );

        if (matches.isEmpty()) {
            source.sendError(Text.literal("No Happy Ghast named \"" + name + "\" was found within "
                    + (int) SEARCH_RADIUS + " blocks."));
            return 0;
        }
        if (matches.size() > 1) {
            source.sendError(Text.literal("Found " + matches.size() + " Happy Ghasts named \"" + name
                    + "\". Move closer so only one is within range, or give them unique names."));
            return 0;
        }

        Entity entity = matches.getFirst();
        UUID uuid = entity.getUuid();
        if (TRACKED.containsKey(uuid)) {
            source.sendError(Text.literal("That Happy Ghast is already registered: " + uuid));
            return 0;
        }

        Identifier dimensionId = world.getRegistryKey().getValue();
        ChunkPos current = entity.getChunkPos();
        TrackedGhast tracked = new TrackedGhast(
                uuid,
                name,
                dimensionId.toString(),
                current.x,
                current.z
        );
        TRACKED.put(uuid, tracked);
        updateTickets(world, tracked, desiredChunks(entity));
        saveConfig();

        source.sendFeedback(() -> Text.literal(
                "Registered Happy Ghast \"" + name + "\" (" + uuid + ") in "
                        + dimensionId + " at chunk " + current.x + ", " + current.z + "."
        ), true);
        return 1;
    }

    private static int list(ServerCommandSource source) {
        if (TRACKED.isEmpty()) {
            source.sendFeedback(() -> Text.literal("No Happy Ghasts are registered."), false);
            return 1;
        }

        source.sendFeedback(() -> Text.literal("Registered Happy Ghasts: " + TRACKED.size()), false);
        TRACKED.values().stream()
                .sorted(Comparator.comparing(tracked -> tracked.displayName))
                .forEach(tracked -> source.sendFeedback(() -> Text.literal(
                        "- " + tracked.displayName + " | " + tracked.uuid + " | "
                                + tracked.dimension + " | chunk " + tracked.lastChunkX + ","
                                + tracked.lastChunkZ + " | " + tracked.status
                ), false));
        return TRACKED.size();
    }

    private static int remove(ServerCommandSource source, String uuidText) {
        final UUID uuid;
        try {
            uuid = UUID.fromString(uuidText);
        } catch (IllegalArgumentException exception) {
            source.sendError(Text.literal("Invalid UUID: " + uuidText));
            return 0;
        }

        TrackedGhast tracked = TRACKED.remove(uuid);
        if (tracked == null) {
            source.sendError(Text.literal("No registered Happy Ghast has UUID " + uuid));
            return 0;
        }

        releaseTickets(source.getServer(), tracked);
        saveConfig();
        source.sendFeedback(() -> Text.literal("Removed " + tracked.displayName + " (" + uuid + ")."), true);
        return 1;
    }

    private static int clear(ServerCommandSource source) {
        int count = TRACKED.size();
        releaseAllTickets(source.getServer());
        TRACKED.clear();
        saveConfig();
        source.sendFeedback(() -> Text.literal("Removed all " + count + " registered Happy Ghast(s)."), true);
        return count;
    }

    private static void tickServer(MinecraftServer server) {
        serverTick++;
        if (TRACKED.isEmpty()) {
            return;
        }

        for (TrackedGhast tracked : List.copyOf(TRACKED.values())) {
            ServerWorld world = getWorld(server, tracked.dimension);
            if (world == null) {
                tracked.status = "missing dimension";
                continue;
            }

            Entity entity = world.getEntity(tracked.uuid);
            if (entity == null) {
                tracked.missingTicks++;
                tracked.status = "searching";

                // Keep only the last known chunk during the recovery window.
                if (tracked.missingTicks <= MISSING_TIMEOUT_TICKS) {
                    updateTickets(world, tracked, Set.of(new ChunkPos(tracked.lastChunkX, tracked.lastChunkZ)));
                } else {
                    removeTickets(world, tracked, Set.copyOf(tracked.loadedChunks));
                    tracked.status = "missing";
                }
                continue;
            }

            if (!isHappyGhast(entity) || entity.isRemoved()) {
                removeTickets(world, tracked, Set.copyOf(tracked.loadedChunks));
                tracked.status = "invalid entity";
                continue;
            }

            tracked.missingTicks = 0;
            tracked.status = "active";
            ChunkPos current = entity.getChunkPos();
            tracked.lastChunkX = current.x;
            tracked.lastChunkZ = current.z;
            tracked.dimension = world.getRegistryKey().getValue().toString();
            updateTickets(world, tracked, desiredChunks(entity));
        }

        // Save position/status periodically without writing every tick.
        if (serverTick % 200 == 0) {
            saveConfig();
        }
    }

    private static Set<ChunkPos> desiredChunks(Entity entity) {
        ChunkPos current = entity.getChunkPos();
        Set<ChunkPos> desired = new HashSet<>();
        desired.add(current);

        Vec3d velocity = entity.getVelocity();
        double horizontalSquared = velocity.x * velocity.x + velocity.z * velocity.z;
        if (horizontalSquared < 0.0004) {
            return desired;
        }

        int dx;
        int dz;
        if (Math.abs(velocity.x) >= Math.abs(velocity.z)) {
            dx = velocity.x > 0.0 ? 1 : -1;
            dz = 0;
        } else {
            dx = 0;
            dz = velocity.z > 0.0 ? 1 : -1;
        }
        desired.add(new ChunkPos(current.x + dx, current.z + dz));
        return desired;
    }

    private static void updateTickets(ServerWorld world, TrackedGhast tracked, Set<ChunkPos> desired) {
        Set<ChunkPos> toAdd = new HashSet<>(desired);
        toAdd.removeAll(tracked.loadedChunks);

        Set<ChunkPos> toRemove = new HashSet<>(tracked.loadedChunks);
        toRemove.removeAll(desired);

        for (ChunkPos chunk : toAdd) {
            world.getChunkManager().addTicket(HAPPY_GHAST_TICKET, chunk, TICKET_RADIUS, tracked.uuid);
            tracked.loadedChunks.add(chunk);
        }
        removeTickets(world, tracked, toRemove);
    }

    private static void removeTickets(ServerWorld world, TrackedGhast tracked, Collection<ChunkPos> chunks) {
        for (ChunkPos chunk : chunks) {
            world.getChunkManager().removeTicket(HAPPY_GHAST_TICKET, chunk, TICKET_RADIUS, tracked.uuid);
            tracked.loadedChunks.remove(chunk);
        }
    }

    private static void restoreLastKnownTickets(MinecraftServer server) {
        for (TrackedGhast tracked : TRACKED.values()) {
            ServerWorld world = getWorld(server, tracked.dimension);
            if (world == null) {
                tracked.status = "missing dimension";
                continue;
            }
            updateTickets(world, tracked, Set.of(new ChunkPos(tracked.lastChunkX, tracked.lastChunkZ)));
            tracked.status = "searching";
        }
    }

    private static void releaseTickets(MinecraftServer server, TrackedGhast tracked) {
        ServerWorld world = getWorld(server, tracked.dimension);
        if (world != null) {
            removeTickets(world, tracked, Set.copyOf(tracked.loadedChunks));
        } else {
            tracked.loadedChunks.clear();
        }
    }

    private static void releaseAllTickets(MinecraftServer server) {
        for (TrackedGhast tracked : TRACKED.values()) {
            releaseTickets(server, tracked);
        }
    }

    private static ServerWorld getWorld(MinecraftServer server, String dimension) {
        Identifier id = Identifier.tryParse(dimension);
        if (id == null) {
            return null;
        }
        RegistryKey<World> key = RegistryKey.of(RegistryKeys.WORLD, id);
        return server.getWorld(key);
    }

    /**
     * Avoids a compile-time dependency on Vanilla Backport. Any entity whose
     * registry path is exactly "happy_ghast" can be registered.
     */
    private static boolean isHappyGhast(Entity entity) {
        Identifier id = Registries.ENTITY_TYPE.getId(entity.getType());
        return "happy_ghast".equals(id.getPath());
    }

    private static String unquote(String value) {
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private static void loadConfig() {
        TRACKED.clear();
        if (!Files.exists(CONFIG_PATH)) {
            return;
        }

        try (Reader reader = Files.newBufferedReader(CONFIG_PATH)) {
            Config config = GSON.fromJson(reader, Config.class);
            if (config == null || config.tracked == null) {
                return;
            }
            for (TrackedGhast tracked : config.tracked) {
                if (tracked != null && tracked.uuid != null && tracked.dimension != null) {
                    tracked.loadedChunks = new HashSet<>();
                    tracked.status = "saved";
                    tracked.missingTicks = 0;
                    TRACKED.put(tracked.uuid, tracked);
                }
            }
        } catch (IOException | JsonSyntaxException exception) {
            LOGGER.error("Could not read {}", CONFIG_PATH, exception);
        }
    }

    private static synchronized void saveConfig() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            Path temporary = CONFIG_PATH.resolveSibling(CONFIG_PATH.getFileName() + ".tmp");
            Config config = new Config(new ArrayList<>(TRACKED.values()));
            try (Writer writer = Files.newBufferedWriter(temporary)) {
                GSON.toJson(config, writer);
            }
            try {
                Files.move(temporary, CONFIG_PATH, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicMoveFailure) {
                Files.move(temporary, CONFIG_PATH, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            LOGGER.error("Could not write {}", CONFIG_PATH, exception);
        }
    }

    private record Config(List<TrackedGhast> tracked) {
    }

    private static final class TrackedGhast {
        private UUID uuid;
        private String displayName;
        private String dimension;
        private int lastChunkX;
        private int lastChunkZ;

        private transient Set<ChunkPos> loadedChunks = new HashSet<>();
        private transient int missingTicks;
        private transient String status = "new";

        private TrackedGhast(UUID uuid, String displayName, String dimension, int lastChunkX, int lastChunkZ) {
            this.uuid = uuid;
            this.displayName = displayName;
            this.dimension = dimension;
            this.lastChunkX = lastChunkX;
            this.lastChunkZ = lastChunkZ;
        }
    }
}
