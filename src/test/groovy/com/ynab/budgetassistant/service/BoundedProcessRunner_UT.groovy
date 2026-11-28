package com.ynab.budgetassistant.service

import spock.lang.Specification
import spock.lang.TempDir

import java.time.Duration
import java.util.concurrent.TimeUnit

class BoundedProcessRunner_UT extends Specification {
    private final BoundedProcessRunner runner = new BoundedProcessRunner()
    @TempDir File tempDir

    def "captures stdout and stderr concurrently for a successful argument-list process"() {
        when:
        def result = runner.run(['/bin/sh', '-c', 'printf stdout; printf stderr >&2'], Duration.ofSeconds(1), 1024)

        then:
        result.status == ProcessResult.Status.SUCCESS
        result.stdout == 'stdout'
        result.stderr == 'stderr'
    }

    def "child inherits only Amazon credentials and essential runtime variables"() {
        given:
        File source = new File(tempDir, 'EnvironmentProbe.java')
        source.text = '''
import com.ynab.budgetassistant.service.BoundedProcessRunner;
import java.time.Duration;
import java.util.List;
class EnvironmentProbe {
    public static void main(String[] args) {
        var result = new BoundedProcessRunner().run(
            List.of("python3", "-I", "-c", "import json,os; print(json.dumps(dict(os.environ)))"),
            Duration.ofSeconds(5), 65536);
        if (!result.isSuccess()) throw new RuntimeException(result.getStatus().name());
        System.out.print(result.getStdout());
    }
}
'''
        String classpath = [BoundedProcessRunner, GroovyObject].collect {
            new File(it.protectionDomain.codeSource.location.toURI()).absolutePath
        }.join(File.pathSeparator)
        def builder = new ProcessBuilder(
            new File(System.getProperty('java.home'), 'bin/java').absolutePath,
            '--class-path', classpath, source.absolutePath
        ).redirectErrorStream(true)
        // Use a completely synthetic environment; never inspect actual secrets.
        builder.environment().clear()
        builder.environment().putAll([
            PATH: System.getenv('PATH'), HOME: tempDir.absolutePath,
            AMAZON_USERNAME: 'fixture@example.invalid', AMAZON_PASSWORD: 'fixture-password',
            AMAZON_OTP_SECRET_KEY: 'fixture-seed', AMAZON_ACCOUNT: 'spouse', AMAZON_DOMAIN: 'amazon.co.uk',
            AMAZON_COOKIE_JAR_PATH: new File(tempDir, 'session.json').absolutePath,
            YNAB_API_TOKEN: 'fake-ynab', EMAIL_PASSWORD: 'fake-email', AWS_SECRET_ACCESS_KEY: 'fake-cloud',
            SELENIUM_REMOTE_URL: 'https://remote.invalid', AMAZON_DEBUG: 'true',
            AMAZON_AUTH_FORMS_CLASSES: '[hostile.Payload]', PYTHONPATH: '/untrusted',
            PYTHONSTARTUP: '/untrusted.py', HTTPS_PROXY: 'https://proxy.invalid', NODE_OPTIONS: '--inspect'
        ])

        when:
        Process probe = builder.start()
        boolean finished = probe.waitFor(15, TimeUnit.SECONDS)

        then:
        finished
        probe.exitValue() == 0
        def environment = new com.fasterxml.jackson.databind.ObjectMapper().readValue(probe.inputStream.text, Map)
        environment.AMAZON_USERNAME == 'fixture@example.invalid'
        environment.AMAZON_PASSWORD == 'fixture-password'
        environment.AMAZON_OTP_SECRET_KEY == 'fixture-seed'
        environment.AMAZON_ACCOUNT == 'spouse'
        environment.AMAZON_DOMAIN == 'amazon.co.uk'
        environment.AMAZON_COOKIE_JAR_PATH == new File(tempDir, 'session.json').absolutePath
        environment.HOME == tempDir.absolutePath
        environment.keySet().intersect(['YNAB_API_TOKEN', 'EMAIL_PASSWORD', 'AWS_SECRET_ACCESS_KEY',
            'SELENIUM_REMOTE_URL', 'AMAZON_DEBUG', 'AMAZON_AUTH_FORMS_CLASSES', 'PYTHONPATH',
            'PYTHONSTARTUP', 'HTTPS_PROXY', 'NODE_OPTIONS']).empty

        cleanup:
        probe?.destroyForcibly()
    }

    def "contains output beyond the configured stream limit"() {
        when:
        def result = runner.run(['/bin/sh', '-c', 'dd if=/dev/zero bs=256 count=1 2>/dev/null'], Duration.ofSeconds(1), 32)

        then:
        result.status == ProcessResult.Status.OUTPUT_LIMIT_EXCEEDED
        result.stdout.size() <= 32
    }

    def "drains both streams and enforces the stderr limit"() {
        when:
        def result = runner.run(['/bin/sh', '-c', 'i=0; while [ $i -lt 500 ]; do printf x; printf y >&2; i=$((i+1)); done'], Duration.ofSeconds(2), 64)

        then:
        result.status == ProcessResult.Status.OUTPUT_LIMIT_EXCEEDED
        result.stdout.size() <= 64
        result.stderr.size() <= 64
    }

    def "rejects excess buffered output from fast exiting children"() {
        expect:
        (1..40).every {
            def result = runner.run(['/bin/sh', '-c', command], Duration.ofSeconds(2), 32)
            result.status == expected
        }

        where:
        command                                                                  || expected
        'printf "%032d" 0'                                                       || ProcessResult.Status.SUCCESS
        'printf "%033d" 0'                                                       || ProcessResult.Status.OUTPUT_LIMIT_EXCEEDED
        'printf "{}"; printf "%032d" 0 >&2'                                      || ProcessResult.Status.SUCCESS
        'printf "{}"; printf "%033d" 0 >&2'                                      || ProcessResult.Status.OUTPUT_LIMIT_EXCEEDED
    }

    def "terminates a process that exceeds its wall-clock timeout"() {
        when:
        long started = System.nanoTime()
        def result = runner.run(['/bin/sh', '-c', 'sleep 5'], Duration.ofMillis(50), 1024)
        long elapsedMillis = (System.nanoTime() - started) / 1_000_000

        then:
        result.status == ProcessResult.Status.TIMED_OUT
        elapsedMillis < 2500
    }

    def "forcibly cleans up a descendant that ignores graceful termination"() {
        given:
        File pidFile = new File(tempDir, 'descendant.pid')
        String command = "(trap '' TERM; while :; do sleep 1; done) & child=\$!; printf '%s' \$child > '${pidFile.absolutePath}'; wait \$child"

        when:
        def result = runner.run(['/bin/sh', '-c', command], Duration.ofMillis(150), 1024)
        long pid = pidFile.text.trim().toLong()
        boolean exited = waitForExit(pid)

        then:
        result.status == ProcessResult.Status.TIMED_OUT
        exited
        !alive(pid)
    }

    def "cleans up inherited pipes after the parent exits and lets the JVM finish"() {
        given:
        File pidFile = new File(tempDir, 'orphan.pid')
        String python = '''
import os, signal, sys, time
child = os.fork()
if child == 0:
    signal.signal(signal.SIGTERM, signal.SIG_IGN)
    print('holding stdout', flush=True)
    print('holding stderr', file=sys.stderr, flush=True)
    time.sleep(30)
    os._exit(0)
with open(sys.argv[1], 'w') as output:
    output.write(str(child))
time.sleep(0.5)
os._exit(0)
'''
        // An isolated JVM detects non-daemon readers that outlive run(), not
        // merely whether run() returned a timeout to the calling test worker.
        File source = new File(tempDir, 'RunnerProbe.java')
        source.text = '''
import com.ynab.budgetassistant.service.BoundedProcessRunner;
import java.time.Duration;
import java.util.List;
class RunnerProbe {
    public static void main(String[] args) {
        var runner = new BoundedProcessRunner();
        // Link Groovy's runner/reader call sites before the short-lived parent.
        runner.run(List.of("/bin/sh", "-c", "printf warmup; printf warmup >&2"),
            Duration.ofSeconds(2), 1024);
        long started = System.nanoTime();
        var result = runner.run(
            List.of("python3", "-c", args[0], args[1]), Duration.ofMillis(1500), 1024);
        System.out.println(result.getStatus().name());
        System.out.println((System.nanoTime() - started) / 1_000_000);
    }
}
'''
        String classpath = [BoundedProcessRunner, GroovyObject].collect {
            new File(it.protectionDomain.codeSource.location.toURI()).absolutePath
        }.join(File.pathSeparator)
        Process probe = new ProcessBuilder(
            new File(System.getProperty('java.home'), 'bin/java').absolutePath,
            '--class-path', classpath, source.absolutePath, python, pidFile.absolutePath
        ).redirectErrorStream(true).start()

        when:
        boolean finished = probe.waitFor(10, TimeUnit.SECONDS)

        then:
        finished
        probe.exitValue() == 0
        def lines = probe.inputStream.text.readLines()
        lines[0] == 'TIMED_OUT'
        lines[1].toLong() < 4000
        pidFile.exists()
        waitForExit(pidFile.text.trim().toLong())

        cleanup:
        if (pidFile.exists()) {
            ProcessHandle.of(pidFile.text.trim().toLong()).ifPresent { it.destroyForcibly() }
        }
        probe.destroyForcibly()
    }

    @spock.lang.Requires({ os.linux })
    def "Linux containment kills children even when their parent exits immediately"() {
        given:
        String python = '''
import os, signal, sys, time
child = os.fork()
if child == 0:
    signal.signal(signal.SIGTERM, signal.SIG_IGN)
    print('holding pipes', flush=True)
    time.sleep(30)
    os._exit(0)
with open(sys.argv[1], 'w') as output:
    output.write(str(child))
os._exit(0)
'''
        runner.run(['/bin/sh', '-c', 'printf warmup'], Duration.ofSeconds(2), 1024)

        expect:
        (1..10).every { iteration ->
            File pidFile = new File(tempDir, "immediate-${iteration}.pid")
            try {
                def result = runner.run(['python3', '-c', python, pidFile.absolutePath], Duration.ofMillis(300), 1024)
                assert result.status == ProcessResult.Status.TIMED_OUT
                assert pidFile.exists()
                assert waitForExit(pidFile.text.trim().toLong())
                true
            } finally {
                if (pidFile.exists()) ProcessHandle.of(pidFile.text.trim().toLong()).ifPresent { it.destroyForcibly() }
            }
        }
    }

    def "returns typed failures for launch and non-zero exit errors"() {
        expect:
        runner.run(['/definitely/not/a/program'], Duration.ofSeconds(1), 1024).status == ProcessResult.Status.START_FAILED
        runner.run(['/bin/sh', '-c', 'printf failed >&2; exit 7'], Duration.ofSeconds(1), 1024).with {
            status == ProcessResult.Status.NON_ZERO_EXIT && exitCode == 7 && stderr == 'failed'
        }
    }

    def "cleans up and restores interruption status when its waiting thread is interrupted"() {
        given:
        ProcessResult result
        boolean interrupted
        Thread worker = Thread.start {
            result = runner.run(['/bin/sh', '-c', 'sleep 5'], Duration.ofSeconds(10), 1024)
            interrupted = Thread.currentThread().isInterrupted()
        }
        sleep 100

        when:
        worker.interrupt()
        worker.join(3000)

        then:
        !worker.alive
        result.status == ProcessResult.Status.INTERRUPTED
        interrupted
    }

    private static boolean waitForExit(long pid) {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos()
        while (alive(pid) && System.nanoTime() < deadline) sleep 10
        !alive(pid)
    }

    private static boolean alive(long pid) {
        ProcessHandle.of(pid).map { it.isAlive() }.orElse(false)
    }
}
