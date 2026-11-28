package com.ynab.budgetassistant.config

import org.yaml.snakeyaml.Yaml
import java.nio.file.*
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions

/** Offline conversion only; never opens retailer, budget or classifier services. */
class ConfigMigration {
    static void main(String[] args) {
        try { run(args) }
        catch (InvalidPathException ignored) { System.err.println('Migration failed: Invalid path'); System.exit(1) }
        catch (Exception e) { System.err.println("Migration failed: ${e.message}"); System.exit(1) }
    }

    static void run(String[] args) {
        Map<String,String> options = [:]
        boolean check = false, apply = false
        for (int i=0; i<args.length; i++) {
            String arg = args[i]
            if (arg == '--check') { if (check) throw new IllegalArgumentException('Duplicate --check'); check = true }
            else if (arg == '--apply') { if (apply) throw new IllegalArgumentException('Duplicate --apply'); apply = true }
            else if (arg in ['--input','--output','--legacy-working-directory','--old-checkout','--new-checkout','--legacy-walmart-helper']) {
                if (options.containsKey(arg) || ++i >= args.length) throw new IllegalArgumentException("Invalid option ${arg}")
                options[arg] = args[i]
            } else throw new IllegalArgumentException('Usage: migrate-config --input OLD --output NEW --legacy-working-directory OLD_CWD [--old-checkout ROOT --new-checkout ROOT] [--legacy-walmart-helper PATH] (--check | --apply)')
        }
        if (check == apply || !options['--input'] || !options['--output'] || !options['--legacy-working-directory'] ||
            (!!options['--old-checkout'] != !!options['--new-checkout'])) throw new IllegalArgumentException('Provide input, output, legacy working directory, paired checkout roots and exactly one of --check or --apply')
        Path input = Paths.get(options['--input']).toAbsolutePath().normalize()
        Path output = Paths.get(options['--output']).toAbsolutePath().normalize()
        Path cwd = Paths.get(options['--legacy-working-directory']).toRealPath()
        if (!Files.isDirectory(cwd)) throw new IllegalArgumentException('Legacy working directory must be a directory')
        if (!Files.isRegularFile(input, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(input) || Files.size(input) > 1048576) throw new IllegalArgumentException('Input must be a regular non-symlink file under 1 MiB')
        secure(input, false)
        secure(input.parent, true)
        secure(output.parent, true)
        Path oldRoot = options['--old-checkout'] ? Paths.get(options['--old-checkout']).toRealPath() : null
        Path newRoot = options['--new-checkout'] ? Paths.get(options['--new-checkout']).toAbsolutePath().normalize() : null
        Map legacy = ConfigSchema.parse(new String(Files.readAllBytes(input), 'UTF-8'), input.toString())
        if (legacy.containsKey('schema_version')) {
            ConfigSchema.validate(legacy, input.toString())
            println 'Configuration is already migrated; no output written.'
            return
        }
        if (Files.exists(output, LinkOption.NOFOLLOW_LINKS) || input == output) throw new IllegalArgumentException('Output already exists or aliases input')
        ConfigSchema.validateKeys(legacy, ConfigSchema.LEGACY, '')
        if (!(legacy.ynab instanceof Map)) throw new IllegalArgumentException('Missing ynab mapping')
        Map result = [schema_version:2, budget:[ynab:legacy.ynab], runtime:legacy.app ?: [:],
            retailers:[amazon:legacy.amazon ?: [:], walmart:legacy.walmart ?: [:], sheetz:legacy.sheetz ?: [:]]]
        if (result.retailers.walmart.enabled && !result.retailers.walmart.helper_executable?.trim()) {
            if (!options['--legacy-walmart-helper']) throw new IllegalArgumentException('The legacy Walmart helper was discovered from the old distribution; provide its absolute path with --legacy-walmart-helper')
            if (!Paths.get(options['--legacy-walmart-helper']).isAbsolute()) throw new IllegalArgumentException('--legacy-walmart-helper must be absolute')
            result.retailers.walmart.helper_executable = options['--legacy-walmart-helper']
        } else if (options['--legacy-walmart-helper']) throw new IllegalArgumentException('--legacy-walmart-helper is only valid for an enabled Walmart source using the bundled helper')
        if (!result.runtime.containsKey('dry_run')) result.runtime.dry_run = false
        if (!result.retailers.amazon.journal_file?.trim()) result.retailers.amazon.journal_file = 'amazon-journal.json'
        if (!result.retailers.walmart.journal_file?.trim()) result.retailers.walmart.journal_file = 'walmart-journal.json'
        if (!result.retailers.sheetz.bridge_script?.trim()) result.retailers.sheetz.bridge_script = 'scripts/sheetz_bridge.py'
        if (result.retailers.amazon.categorization?.credentials_file instanceof String) {
            result.runtime.amazon_fingerprint_credentials_file = result.retailers.amazon.categorization.credentials_file
        }
        List<List> paths = [[result.runtime,'processed_transactions_file',false],
            [result.retailers.amazon,'csv_file_path',false], [result.retailers.amazon,'journal_file',true],
            [result.retailers.amazon.python,'executable',true], [result.retailers.amazon.python,'bridge_script',true],
            [result.retailers.amazon.python,'config_path',true], [result.retailers.walmart,'helper_executable',true],
            [result.retailers.walmart,'session_file',true], [result.retailers.walmart,'journal_file',true],
            [result.retailers.sheetz,'python_executable',true], [result.retailers.sheetz,'bridge_script',true],
            [result.retailers.sheetz,'credentials_file',true], [result.retailers.sheetz,'token_file',true]]
        result.retailers.values().each { retailer -> paths << [retailer.categorization,'credentials_file',false] }
        paths.each { entry ->
            Map map = entry[0] as Map
            String key = entry[1]
            if (map && map[key] instanceof String && map[key].trim()) {
                String value = entry[2] ? map[key].trim() : map[key]
                if (entry[2] && (value == '~' || value.startsWith('~/'))) value = System.getProperty('user.home') + value.substring(1)
                Path resolved
                try { resolved = cwd.resolve(value).normalize().toAbsolutePath() }
                catch (InvalidPathException ignored) { throw new IllegalArgumentException("Invalid path for ${key}") }
                if (oldRoot && resolved.startsWith(oldRoot)) resolved = newRoot.resolve(oldRoot.relativize(resolved)).normalize()
                map[key] = resolved.toString()
                if (!Files.exists(resolved)) println "Resource not found for ${key}: ${resolved}"
                if (key in ['executable','python_executable'] && oldRoot) println "Check whether ${key} requires a recreated Python environment."
            }
        }
        ConfigSchema.validate(result, output.toString())
        if (check) { println 'Configuration validated; no output written.'; return }
        Path temporary = Files.createTempFile(output.parent, '.config-migration-', '.tmp', PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString('rw-------')))
        try {
            Files.write(temporary, new Yaml().dump(result).getBytes('UTF-8'), StandardOpenOption.TRUNCATE_EXISTING)
            Files.createLink(output, temporary)
        } finally { Files.deleteIfExists(temporary) }
        println "Migrated configuration written to ${output}. Stage and verify referenced resources before use."
    }

    private static void secure(Path path, boolean directory) {
        if (Files.isSymbolicLink(path) || (directory ? !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) : !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))) throw new IllegalArgumentException('Unsafe protected path')
        if (path.toRealPath() != path.toAbsolutePath().normalize()) throw new IllegalArgumentException('Protected paths must not traverse symlinks')
        Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS)
        if (permissions.any { it.name().startsWith('GROUP_') || it.name().startsWith('OTHERS_') }) throw new IllegalArgumentException('Input and output parent must be owner-only')
        if (Files.getOwner(path, LinkOption.NOFOLLOW_LINKS) != Files.getOwner(Paths.get(System.getProperty('user.home')))) throw new IllegalArgumentException('Protected path must be owned by the current user')
    }
}
