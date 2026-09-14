package com.ynab.amazon.service

import spock.lang.Specification
import spock.lang.TempDir

import java.time.Duration

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
