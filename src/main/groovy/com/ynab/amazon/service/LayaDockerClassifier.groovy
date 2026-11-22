package com.ynab.amazon.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.ynab.amazon.model.WalmartOrderItem
import java.nio.file.*
import java.security.MessageDigest
import java.time.Duration

/** The registry, not operator-edited ready flags, authorizes a measured profile. */
class LayaDockerClassifier {
    private static final JSON = new ObjectMapper()
    private final Map config
    private final BoundedProcessRunner runner

    LayaDockerClassifier(Map config, BoundedProcessRunner runner = new BoundedProcessRunner(GoWalmartOrderFetcher.ENVIRONMENT)) {
        this.config = config
        this.runner = runner
    }

    static List<Map> profiles() {
        JSON.readValue(LayaDockerClassifier.getResourceAsStream('/walmart-laya-profiles.json'), Map).profiles as List
    }

    static List<Map> fittingProfiles(List<Map> profiles, String host, String engineArch, long budget) {
        if (budget < 1 || budget > 2000000000L) throw new IllegalArgumentException('Model RAM must be positive and at most 2 GB (2,000,000,000 bytes)')
        profiles.findAll { p ->
            p.qualified == true && p.provider == 'laya-classic' && p.hosts?.contains(host) && p.arch == engineArch &&
                p.loading_peak_bytes instanceof Number && p.inference_peak_bytes instanceof Number && p.headroom_bytes instanceof Number &&
                p.headroom_bytes > 0 && Math.max(p.loading_peak_bytes as long, p.inference_peak_bytes as long) + (long)p.headroom_bytes <= budget
        }.sort { a, b -> (Math.max(a.loading_peak_bytes as long,a.inference_peak_bytes as long) <=> Math.max(b.loading_peak_bytes as long,b.inference_peak_bytes as long)) ?: (a.warm_ms <=> b.warm_ms) }
    }

    Map classify(List<WalmartOrderItem> items, Map categories) {
        Map manifest = JSON.readValue(new File(config.model_manifest.toString()), Map)
        Map profile = profiles().find { it.id == manifest.profile_id }
        if (!profile || config.provider != 'laya') throw new IllegalStateException('Laya profile is unqualified/unavailable')
        String host = hostTarget()
        long budget = config.memory_limit_bytes != null ? new BigDecimal(config.memory_limit_bytes.toString()).longValueExact() : 1000000000L
        def info = runner.run(['docker','info','--format','{{.OSType}}/{{.Architecture}}'],Duration.ofSeconds(10),4096)
        String arch = info.stdout.trim().replace('x86_64','amd64').replace('aarch64','arm64')
        if (!info.success || arch != 'linux/' + profile.arch || !fittingProfiles([profile],host,profile.arch,budget)) throw new IllegalStateException('Unqualified Docker host or model RAM budget')
        if (manifest.image != profile.image || !(profile.image ==~ /[a-zA-Z0-9.\/:_-]+@sha256:[a-f0-9]{64}/)) throw new IllegalStateException('Unpinned Laya image')
        Path model = Paths.get(manifest.model_directory.toString()).toRealPath()
        if (model == Paths.get(System.getProperty('user.home')).toRealPath() || !Files.isDirectory(model)) throw new IllegalStateException('Unsafe Laya model mount')
        profile.artifacts.each { artifact ->
            Path file = model.resolve(artifact.path.toString()).normalize()
            if (!file.startsWith(model) || Files.isSymbolicLink(file) || !file.toRealPath().startsWith(model) ||
                !Files.isRegularFile(file) || hash(file) != artifact.sha256) throw new IllegalStateException('Laya artifact missing/modified')
        }
        def choices = categories.values().collect { [id: it.id, label: it.name, description: it.description ?: ''] } + [[id: '__abstain__', label: 'Insufficient or ambiguous information', description: '']]
        if (choices.size() > profile.max_choices || items.size() > profile.max_batch) throw new IllegalStateException('Laya choice/batch limit')
        String batch = UUID.randomUUID().toString()
        def requests = items.collectWithIndex { item, index -> [version:1, id:"${batch}-${index}", product:[title:item.title], choices:choices] }
        String input = requests.collect { JSON.writeValueAsString(it) }.join('\n') + '\n'
        if (input.getBytes('UTF-8').length > profile.max_input_bytes) throw new IllegalStateException('Laya input limit')
        String name = 'ynab-laya-' + batch
        def command = ['docker','run','--rm','-i','--name',name,'--pull=never','--network=none','--log-driver=none',
            '--user','65532:65532','--read-only','--cap-drop=ALL','--security-opt=no-new-privileges',
            '--memory',budget.toString(),'--memory-swap',budget.toString(),'--cpus','1','--pids-limit','64',
            '--tmpfs','/tmp:rw,noexec,nosuid,size=16777216','--mount',"type=bind,source=${model},target=/model,readonly",
            '--env','HF_HUB_OFFLINE=1','--env','TRANSFORMERS_OFFLINE=1','--env','HF_HUB_DISABLE_TELEMETRY=1',
            '--env','OMP_NUM_THREADS=1','--env','MKL_NUM_THREADS=1',profile.image.toString()]
        ProcessResult result
        try { result = runner.runWithInput(command,Duration.ofSeconds(profile.batch_timeout_seconds as long), profile.max_output_bytes as int,input) }
        finally {
            boolean interrupted = Thread.interrupted()
            try { runner.run(['docker','rm','--force',name],Duration.ofSeconds(10),4096) }
            finally { if (interrupted) Thread.currentThread().interrupt() }
        }
        if (!result.success) throw new IllegalStateException('Laya batch failed')
        def lines = result.stdout.readLines()
        if (lines.size() != requests.size()) throw new IllegalStateException('Laya partial batch')
        def outputs = lines.collect { JSON.readValue(it,Map) }
        if (outputs*.id.toSet() != requests*.id.toSet() || outputs*.id.toSet().size() != outputs.size()) throw new IllegalStateException('Laya correlation failure')
        Map decisions = [:]
        requests.eachWithIndex { request,index ->
            def output = outputs.find { it.id == request.id }
            if (output.version != 1 || !(output.scores instanceof Map) || output.scores.keySet() != choices*.id.toSet()) throw new IllegalStateException('Laya invalid scores')
            if (output.scores.values().any { !(it instanceof Number) || !Double.isFinite(it.doubleValue()) || it < 0 || it > 1 }) throw new IllegalStateException('Laya nonfinite score')
            def ranked = output.scores.entrySet().sort { a,b -> b.value <=> a.value }
            def winner = ranked.first()
            String selected = winner.key != '__abstain__' && winner.value >= profile.acceptance && winner.value - ranked[1].value >= profile.margin ? winner.key : null
            decisions[items[index].itemId] = [category_id:selected,source:'laya-classic',scores:output.scores,
                profile:profile.id,fingerprint:WalmartEnrichment.fingerprint([profile,categories,items[index].title])]
        }
        decisions
    }

    static String hostTarget() {
        String os = System.getProperty('os.name').toLowerCase(Locale.ROOT)
        String arch = System.getProperty('os.arch') in ['aarch64','arm64'] ? 'arm64' : System.getProperty('os.arch') in ['amd64','x86_64'] ? 'amd64' : 'unsupported'
        (os.contains('windows') ? 'windows' : os.contains('mac') ? 'darwin' : os.contains('linux') ? 'linux' : 'unsupported') + '-' + arch
    }

    private static String hash(Path path) {
        def digest = MessageDigest.getInstance('SHA-256')
        Files.newInputStream(path).withCloseable { stream -> byte[] buffer = new byte[65536]; int n; while ((n=stream.read(buffer)) != -1) digest.update(buffer,0,n) }
        digest.digest().encodeHex().toString()
    }
}
