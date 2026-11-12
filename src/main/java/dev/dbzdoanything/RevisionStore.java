package dev.dbzdoanything;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.Set;

final class RevisionStore {
    static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    record Change(JsonObject baseline, JsonObject value) {}

    record Revision(long number, Map<String, Change> changes, Map<String, String> names,
                    Set<String> disabledForms, Set<String> retiredRaces, Set<String> specialRules) {
        Revision {
            specialRules = specialRules == null ? Set.of() : Set.copyOf(specialRules);
            specialRules.forEach(SpecialRule::fromId);
        }

        static Revision initial() {
            return new Revision(0, Map.of(), Map.of(), Set.of(), Set.of(), Set.of());
        }
    }

    private final Path directory;

    RevisionStore(Path directory) {
        this.directory = directory;
    }

    Revision load() throws IOException {
        Path pointer = directory.resolve("current");
        if (Files.notExists(pointer)) return Revision.initial();
        return read(Long.parseLong(Files.readString(pointer).strip()));
    }

    Revision read(long number) throws IOException {
        if (number == 0) return Revision.initial();
        if (number < 0) throw new IllegalArgumentException("Revision must be nonnegative.");
        Revision revision = GSON.fromJson(Files.readString(file(number)), Revision.class);
        if (revision == null || revision.number() != number || revision.changes() == null
                || revision.names() == null || revision.disabledForms() == null || revision.retiredRaces() == null) {
            throw new IOException("Invalid DMZ Do Anything revision " + number);
        }
        return revision;
    }

    void commit(Revision revision) throws IOException {
        Files.createDirectories(directory);
        // An orphaned revision can only be left by a crash before the pointer was published.
        writeAtomic(file(revision.number()), GSON.toJson(revision));
        writeAtomic(directory.resolve("current"), Long.toString(revision.number()));
    }

    private Path file(long number) {
        return directory.resolve("revision-" + number + ".json");
    }

    private void writeAtomic(Path target, String content) throws IOException {
        Path staged = Files.createTempFile(directory, ".revision-", ".tmp");
        try {
            var bytes = ByteBuffer.wrap(content.getBytes(StandardCharsets.UTF_8));
            try (var channel = FileChannel.open(staged, StandardOpenOption.WRITE)) {
                while (bytes.hasRemaining()) channel.write(bytes);
                channel.force(true);
            }
            Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(staged);
        }
    }
}
