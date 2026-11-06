package dev.wicklen.chronicles.terrain;

import dev.wicklen.chronicles.core.map.*;
import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.CRC32;

/** Bounded regional files, immutable cache, checked replacement. Owned by the map IO worker. */
public final class TerrainMapStore {
    private static final int MAGIC = 0x574D4150, SCHEMA = 1, MAX_BYTES = 24_000;
    private final Path root;
    private final UUID worldId;
    private record Key(UUID owner, String dimension, int x, int z) {}
    private final Map<Key, MapRegion> cache = new LinkedHashMap<>(128, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<Key, MapRegion> entry) { return size() > 128; }
    };
    private final Map<Key, IOException> failures = new HashMap<>();
    public TerrainMapStore(Path worldModDirectory, UUID worldId) {
        this.root = worldModDirectory.resolve("terrain-map").toAbsolutePath().normalize();
        this.worldId = Objects.requireNonNull(worldId);
    }
    public synchronized MapRegion region(UUID owner, MapCell cell) throws IOException {
        var key = new Key(owner, cell.dimension(), cell.regionX(), cell.regionZ());
        if (failures.containsKey(key)) throw new IOException("Unreadable terrain region; source preserved", failures.get(key));
        var cached = cache.get(key); if (cached != null) return cached;
        Path file = file(key);
        MapRegion region;
        try { region = Files.exists(file) ? decode(Files.readAllBytes(requireBounded(file)), key) : MapRegion.empty(worldId, owner, cell); }
        catch (IOException | RuntimeException error) {
            var failure = new IOException("Cannot read terrain region " + file + "; source preserved", error);
            failures.put(key, failure); throw failure;
        }
        cache.put(key, region); return region;
    }
    public synchronized MapTransitions.Change record(UUID owner, SurveyChunk observed) throws IOException {
        var old = region(owner, observed.cell());
        var change = MapTransitions.record(old, owner, observed);
        if (!change.changed()) return change;
        var key = new Key(owner, observed.cell().dimension(), observed.cell().regionX(), observed.cell().regionZ());
        Path file = file(key);
        Files.createDirectories(file.getParent());
        Path pending = file.resolveSibling(file.getFileName() + ".tmp");
        Files.write(pending, encode(change.region()));
        try (var channel = FileChannel.open(pending, StandardOpenOption.WRITE)) { channel.force(true); }
        try { Files.move(pending, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (AtomicMoveNotSupportedException ignored) { Files.move(pending, file, StandardCopyOption.REPLACE_EXISTING); }
        cache.put(key, change.region()); // publish only after checked persistence
        return change;
    }
    public synchronized byte[] viewport(UUID owner, String dimension, MapViewport view) throws IOException {
        var regions = new HashMap<String, MapRegion>();
        // Load once per region, never once per pixel. Absent files do not allocate files/directories.
        int minCx = Math.floorDiv(view.minX(), 16), minCz = Math.floorDiv(view.minZ(), 16);
        int maxCx = Math.floorDiv(view.minX() + view.span() - 1, 16), maxCz = Math.floorDiv(view.minZ() + view.span() - 1, 16);
        for (int rz = Math.floorDiv(minCz, 16); rz <= Math.floorDiv(maxCz, 16); rz++)
            for (int rx = Math.floorDiv(minCx, 16); rx <= Math.floorDiv(maxCx, 16); rx++) {
                var cell = new MapCell(dimension, Math.clamp(rx * 16, -MapCell.LIMIT, MapCell.LIMIT), Math.clamp(rz * 16, -MapCell.LIMIT, MapCell.LIMIT));
                regions.put(rx + ":" + rz, region(owner, cell));
            }
        return MapProjection.pixels(dimension, view, cell -> regions.get(cell.regionX() + ":" + cell.regionZ()).chunks().get(cell.index()));
    }
    private Path file(Key key) {
        String dimension;
        try { dimension = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.dimension.getBytes(StandardCharsets.UTF_8))).substring(0, 24); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
        Path path = root.resolve(key.owner.toString()).resolve(dimension).resolve(key.x + "_" + key.z + ".wmap").normalize();
        if (!path.startsWith(root)) throw new IllegalStateException("Terrain path outside world store");
        return path;
    }
    private Path requireBounded(Path file) throws IOException {
        if (!Files.isRegularFile(file) || Files.size(file) > MAX_BYTES) throw new IOException("Terrain region size/type bounds");
        return file;
    }
    private byte[] encode(MapRegion region) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var data = new DataOutputStream(bytes)) {
            data.writeLong(region.worldId().getMostSignificantBits()); data.writeLong(region.worldId().getLeastSignificantBits());
            data.writeLong(region.ownerId().getMostSignificantBits()); data.writeLong(region.ownerId().getLeastSignificantBits());
            data.writeUTF(region.dimension()); data.writeInt(region.regionX()); data.writeInt(region.regionZ()); data.writeLong(region.revision());
            data.writeShort(region.chunks().size());
            for (var entry : new TreeMap<>(region.chunks()).entrySet()) {
                data.writeShort(entry.getKey()); data.writeLong(entry.getValue().firstVisitedAt()); data.write(entry.getValue().colors());
            }
        }
        byte[] payload = bytes.toByteArray(); var crc = new CRC32(); crc.update(payload);
        bytes = new ByteArrayOutputStream();
        try (var data = new DataOutputStream(bytes)) { data.writeInt(MAGIC); data.writeInt(SCHEMA); data.writeInt(payload.length); data.write(payload); data.writeLong(crc.getValue()); }
        return bytes.toByteArray();
    }
    private MapRegion decode(byte[] source, Key key) throws IOException {
        try (var in = new DataInputStream(new ByteArrayInputStream(source))) {
            if (in.readInt() != MAGIC || in.readInt() != SCHEMA) throw new IOException("Unsupported terrain format");
            int length = in.readInt(); if (length < 0 || length > MAX_BYTES || length != source.length - 20) throw new IOException("Terrain length bounds");
            byte[] payload = in.readNBytes(length); long expected = in.readLong(); var crc = new CRC32(); crc.update(payload);
            if (crc.getValue() != expected || in.available() != 0) throw new IOException("Terrain checksum mismatch");
            try (var data = new DataInputStream(new ByteArrayInputStream(payload))) {
                var world = new UUID(data.readLong(), data.readLong()); var owner = new UUID(data.readLong(), data.readLong());
                String dim = data.readUTF(); int rx = data.readInt(), rz = data.readInt(); long revision = data.readLong();
                if (!world.equals(worldId) || !owner.equals(key.owner) || !dim.equals(key.dimension) || rx != key.x || rz != key.z)
                    throw new IOException("Terrain world/owner/dimension mismatch");
                int count = data.readUnsignedShort(); if (count > 256) throw new IOException("Terrain cell count bounds");
                var chunks = new HashMap<Integer, SurveyChunk>();
                for (int i = 0; i < count; i++) {
                    int index = data.readUnsignedShort(); if (index >= 256 || chunks.containsKey(index)) throw new IOException("Duplicate/invalid terrain cell");
                    long first = data.readLong(); byte[] colors = data.readNBytes(64);
                    chunks.put(index, new SurveyChunk(new MapCell(dim, rx * 16 + index % 16, rz * 16 + index / 16), first, colors));
                }
                if (data.available() != 0) throw new IOException("Unexpected trailing terrain data");
                return new MapRegion(world, owner, dim, rx, rz, revision, chunks);
            }
        }
    }
}
