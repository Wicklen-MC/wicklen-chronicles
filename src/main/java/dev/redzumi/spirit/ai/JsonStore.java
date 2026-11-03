package dev.redzumi.spirit.ai;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import dev.redzumi.spirit.RedzSpirit;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;
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

    private final String fileName;
    private final TypeToken<T> type;
    private T data;
    private boolean loaded;

    public JsonStore(String fileName, TypeToken<T> type) {
        this.fileName = fileName;
        this.type = type;
    }

    private Path file() {
        return FabricLoader.getInstance().getGameDir().resolve("redzspirit").resolve(fileName);
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
        try {
            Files.createDirectories(file().getParent());
            Files.writeString(file(), GSON.toJson(data, type.getType()));
        } catch (Exception e) {
            RedzSpirit.LOGGER.warn("[redz-spirit] {} save failed: {}", fileName, e.toString());
        }
    }
}
