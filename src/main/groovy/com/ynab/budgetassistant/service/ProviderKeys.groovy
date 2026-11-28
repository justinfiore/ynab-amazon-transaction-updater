package com.ynab.budgetassistant.service

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.file.*
import java.nio.file.attribute.PosixFilePermissions

/** Local connection identity is random, never derived from a secret. No network probes. */
class ProviderKeys {
    private static final JSON = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)

    static Map read(String file, String provider) {
        try { return readRecord(file,provider) }
        catch (Exception ignored) { throw new IllegalStateException('Unsafe or invalid provider credential record; rerun setup in a supported owner-only directory') }
    }

    private static Map readRecord(String file, String provider) {
        Path path = Paths.get(file).toAbsolutePath().normalize()
        checkDirectory(path.parent)
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > 16384 ||
            Files.getPosixFilePermissions(path) != PosixFilePermissions.fromString('rw-------') ||
            Files.getOwner(path) != Files.getOwner(path.parent)) throw new IllegalStateException('Unsafe provider credential record')
        Map record = JSON.readValue(Files.readAllBytes(path), Map)
        if (record.version != 1 || record.provider != provider || record.consent != true ||
            !(record.connection_id ==~ /[a-f0-9-]{36}/) || !(record.key instanceof String) ||
            !record.key || record.key.length() > 8192 || record.key.any { Character.isISOControl((char)it) }) throw new IllegalStateException('Invalid provider connection; rerun setup')
        record
    }

    static Path directory(Path config, String retailer = 'walmart') {
        if (!(retailer in ['walmart', 'amazon'])) throw new IllegalArgumentException('Unknown retailer')
        Path dir = config.toAbsolutePath().normalize().resolveSibling('.' + retailer + '-credentials')
        if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(dir,
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString('rwx------')))
        checkDirectory(dir)
        dir
    }

    private static void checkDirectory(Path dir) {
        if (!Files.getFileStore(dir).supportsFileAttributeView('posix')) throw new IllegalStateException('Provider keys require verified owner-only POSIX permissions; Windows ACL qualification is pending')
        if (Files.isSymbolicLink(dir) || dir.toRealPath() != dir.toAbsolutePath().normalize() || !Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS) ||
            Files.getPosixFilePermissions(dir) != PosixFilePermissions.fromString('rwx------') ||
            Files.getOwner(dir) != Files.getOwner(Paths.get(System.getProperty('user.home')))) throw new IllegalStateException('Unsafe provider credential directory')
    }

    static Path stage(Path config, String provider, char[] secret, String retailer = 'walmart') {
        Path target = null
        try {
            if (!RemoteItemClassifier.ROUTES.containsKey(provider)) throw new IllegalArgumentException('Unknown provider')
            target = directory(config, retailer).resolve(provider + '-' + UUID.randomUUID() + '.json')
            ProtectedJournal.atomic(target, JSON.writeValueAsBytes([version:1, provider:provider,
                connection_id:UUID.randomUUID().toString(), consent:true, key:new String(secret)]))
            read(target.toString(), provider)
            target
        } catch (Exception ignored) {
            if (target) Files.deleteIfExists(target)
            throw new IllegalStateException('Cannot save protected provider key')
        } finally { Arrays.fill(secret, (char)0) }
    }

    static void disconnect(String file, String provider) {
        read(file, provider) // never delete an arbitrary unvalidated config path
        Files.delete(Paths.get(file))
    }
}
