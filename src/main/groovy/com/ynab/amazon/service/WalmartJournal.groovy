package com.ynab.amazon.service

import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.channels.FileChannel
import java.nio.file.*
import java.nio.file.attribute.PosixFilePermissions
import static java.nio.file.StandardOpenOption.*

/** Protected atomic plans, serialized for the entire read/plan/apply operation. */
class WalmartJournal {
    private static final ObjectMapper JSON = new ObjectMapper()
    final Path path

    WalmartJournal(String file) { path = Paths.get(file).toAbsolutePath().normalize() }

    def locked(boolean dryRun, Closure action) {
        if (dryRun) return action(read(), { Map ignored -> })
        Files.createDirectories(path.parent)
        Path lock = path.resolveSibling(path.fileName.toString() + '.lock')
        if (Files.isSymbolicLink(lock)) throw new IllegalStateException('Unsafe Walmart journal lock')
        if (!Files.exists(lock)) {
            try { Files.createFile(lock, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString('rw-------'))) }
            catch (FileAlreadyExistsException ignored) { }
        }
        FileChannel.open(lock, WRITE).withCloseable { channel ->
            def held = channel.tryLock()
            if (held == null) throw new IllegalStateException('Another Walmart application is running')
            held.withCloseable { action(read(), { Map data -> save(data) }) }
        }
    }

    private Map read() {
        if (!Files.exists(path)) return [version: 1, plans: [:], mappings: [:]]
        if (Files.isSymbolicLink(path) || Files.size(path) > 32 * 1024 * 1024 ||
            Files.getPosixFilePermissions(path).any { it.name().startsWith('GROUP_') || it.name().startsWith('OTHERS_') }) throw new IllegalStateException('Unsafe Walmart journal permissions')
        Map data = JSON.readValue(Files.readAllBytes(path), Map)
        if (data.version != 1 || !(data.plans instanceof Map) || !(data.mappings instanceof Map)) throw new IllegalStateException('Unsupported Walmart journal')
        data
    }

    private void save(Map data) { atomic(path, JSON.writeValueAsBytes(data)) }

    static void atomic(Path target, byte[] content) {
        Path temporary = Files.createTempFile(target.parent, '.walmart-', '.tmp',
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString('rw-------')))
        try {
            FileChannel.open(temporary, WRITE).withCloseable { channel ->
                def buffer=java.nio.ByteBuffer.wrap(content)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { Files.deleteIfExists(temporary) }
    }
}
