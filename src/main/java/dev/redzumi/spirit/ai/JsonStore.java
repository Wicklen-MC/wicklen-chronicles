package dev.redzumi.spirit.ai;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import dev.redzumi.spirit.RedzSpirit;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.AtomicMoveNotSupportedException;
import java.util.List;
import java.util.ArrayList;
import java.util.function.Supplier;

/**
 * Крошечное JSON-хранилище в redzspirit/ (DRY для персистентных данных мода):
 * само читает файл при первом обращении, отдаёт живой объект, save() пишет на диск.
 * Все методы synchronized — владельцы могут звать из любого потока.
 *
 * Тип указывается TypeToken: new JsonStore<>(..., new TypeToken<Map<String,String>>(){})
 */
public final class JsonStore<T> {
    private static final Gson GSON = new Gson();
    private static final List<JsonStore<?>> STORES = new ArrayList<>();

    private final String fileName;
    private final TypeToken<T> type;
    private final Supplier<Path> directory;
    private T data;
    private boolean loaded;

    public JsonStore(String fileName, TypeToken<T> type) {
        this(fileName, type, () -> FabricLoader.getInstance().getGameDir().resolve("redzspirit"));
    }

    JsonStore(String fileName, TypeToken<T> type, Path directory) {
        this(fileName, type, () -> directory);
    }

    private JsonStore(String fileName, TypeToken<T> type, Supplier<Path> directory) {
        this.fileName = fileName;
        this.type = type;
        this.directory = directory;
        synchronized (STORES) { STORES.add(this); }
    }

    private Path file() {
        return directory.get().resolve(fileName);
    }

    /** Данные: лениво читает файл, при ошибке/отсутствии — def (один раз). */
    @SuppressWarnings("unchecked")
    public synchronized T get(Supplier<T> def) {
        if (loaded) return data;
        loaded = true;
        try {
            if (Files.exists(file())) {
                T value = (T) GSON.fromJson(Files.readString(file()), type.getType());
                if (value != null) {
                    data = value;
                    return data;
                }
            }
        } catch (Exception e) {
            RedzSpirit.LOGGER.warn("[redz-spirit] {} load failed: {}", fileName, e.toString());
        }
        data = def.get();
        return data;
    }

    /** Записать текущие данные на диск. */
    public synchronized void save() {
        if (!loaded) return; // flushing an unused store must not overwrite existing data with null
        try {
            Files.createDirectories(file().getParent());
            Path pending = file().resolveSibling(fileName + ".tmp");
            Files.writeString(pending, GSON.toJson(data, type.getType()));
            try {
                Files.move(pending, file(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(pending, file(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            RedzSpirit.LOGGER.warn("[redz-spirit] {} save failed: {}", fileName, e.toString());
        }
    }

    public static void resetAll() {
        synchronized (STORES) {
            for (var store : STORES) store.reset();
        }
    }

    private synchronized void reset() { loaded = false; data = null; }
}
