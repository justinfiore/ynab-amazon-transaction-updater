package com.ynab.amazon.service

import java.time.Duration
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeoutException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

interface ProcessRunner {
    ProcessResult run(List<String> command, Duration timeout, int maxOutputBytes)
}

class ProcessResult {
    enum Status { SUCCESS, START_FAILED, NON_ZERO_EXIT, TIMED_OUT, INTERRUPTED, OUTPUT_LIMIT_EXCEEDED, OUTPUT_READ_FAILED }

    final Status status
    final int exitCode
    final String stdout
    final String stderr
    final String detail

    ProcessResult(Status status, int exitCode = -1, String stdout = '', String stderr = '', String detail = '') {
        this.status = status
        this.exitCode = exitCode
        this.stdout = stdout
        this.stderr = stderr
        this.detail = detail
    }

    boolean isSuccess() { status == Status.SUCCESS }
}

/** Executes an argument-list process with bounded, concurrently drained output. */
class BoundedProcessRunner implements ProcessRunner {
    private static final Duration TERMINATION_GRACE = Duration.ofSeconds(1)
    // This runner launches only the Amazon bridge. Never hand it YNAB/email/cloud
    // secrets, Python injection hooks, proxy settings or remote-browser overrides.
    private static final Set<String> ENVIRONMENT_KEYS = [
        'PATH', 'HOME', 'USERPROFILE', 'HOMEDRIVE', 'HOMEPATH', 'SYSTEMROOT', 'WINDIR',
        'TEMP', 'TMP', 'TMPDIR', 'LANG', 'LC_ALL', 'LC_CTYPE', 'TZ',
        'AMAZON_DOMAIN', 'AMAZON_ACCOUNT', 'AMAZON_COOKIE_JAR_PATH',
        'AMAZON_USERNAME', 'AMAZON_PASSWORD', 'AMAZON_OTP_SECRET_KEY'
    ] as Set

    private final Set<String> environmentKeys

    BoundedProcessRunner(Set<String> environmentKeys = ENVIRONMENT_KEYS) {
        this.environmentKeys = environmentKeys.collect { it.toUpperCase(Locale.ROOT) } as Set
    }

    @Override
    ProcessResult run(List<String> command, Duration timeout, int maxOutputBytes) {
        runWithInput(command, timeout, maxOutputBytes, '')
    }

    ProcessResult runWithInput(List<String> command, Duration timeout, int maxOutputBytes, String input) {
        Process process
        boolean isolatedGroup = System.getProperty('os.name').toLowerCase(Locale.ROOT).contains('linux')
        try {
            List<String> launchCommand = command
            if (isolatedGroup) {
                // Resolve before wrapping so a missing executable remains START_FAILED.
                File executable = command[0].contains('/') ? new File(command[0]) :
                    (System.getenv('PATH') ?: '').split(File.pathSeparator).collect {
                        new File(it ?: '.', command[0])
                    }.find { it.isFile() && it.canExecute() }
                if (!executable?.isFile() || !executable.canExecute()) throw new IOException('Executable not available')
                // setsid execs the command as a new session/group leader. Children
                // remain in that group even if their original parent exits.
                launchCommand = ['/usr/bin/setsid', '--', executable.absolutePath] + command.drop(1)
                if (!new File('/bin/kill').canExecute()) throw new IOException('Process group signalling unavailable')
            }
            ProcessBuilder builder = new ProcessBuilder(launchCommand)
            // Windows environment keys such as SystemRoot are case-insensitive.
            builder.environment().keySet().removeIf { !environmentKeys.contains(it.toUpperCase(Locale.ROOT)) }
            process = builder.start()
        } catch (IOException e) {
            return new ProcessResult(ProcessResult.Status.START_FAILED, -1, '', '', e.message ?: e.class.simpleName)
        }

        // A descendant can escape discovery by exiting/reparenting between samples.
        // Its inherited pipe must never keep the JVM alive after bounded cleanup.
        ExecutorService readers = Executors.newFixedThreadPool(3, { Runnable task ->
            Thread thread = new Thread(task, 'bounded-process-output')
            thread.daemon = true
            thread
        } as ThreadFactory)
        Set<ProcessHandle> descendants = new LinkedHashSet<>()
        AtomicBoolean outputExceeded = new AtomicBoolean(false)
        Future<String> stdout = readers.submit { drain(process.inputStream, maxOutputBytes, outputExceeded) }
        Future<String> stderr = readers.submit { drain(process.errorStream, maxOutputBytes, outputExceeded) }
        Future writer = readers.submit {
            process.outputStream.withCloseable { it.write(input.getBytes('UTF-8')) }
        }
        try {
            long deadline = System.nanoTime() + timeout.toNanos()
            while (process.isAlive() && !outputExceeded.get()) {
                // Retain handles while the parent lives: after it exits, its
                // pipe-holding children may no longer appear in descendants().
                rememberDescendants(process.toHandle(), descendants)
                long remaining = deadline - System.nanoTime()
                if (remaining <= 0) {
                    terminateTree(process, descendants, isolatedGroup)
                    return result(ProcessResult.Status.TIMED_OUT, process, stdout, stderr, 'process exceeded configured timeout')
                }
                process.waitFor(Math.min(TimeUnit.NANOSECONDS.toMillis(remaining) + 1, 10), TimeUnit.MILLISECONDS)
            }
            if (outputExceeded.get()) {
                terminateTree(process, descendants, isolatedGroup)
                return result(ProcessResult.Status.OUTPUT_LIMIT_EXCEEDED, process, stdout, stderr, 'process output exceeded configured limit')
            }
            // Readers can detect buffered excess after the child has exited.
            // Resolve both within the same deadline before accepting any output.
            String capturedStdout = stdout.get(Math.max(0L, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)
            String capturedStderr = stderr.get(Math.max(0L, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)
            writer.get(Math.max(0L, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)
            int exitCode = process.exitValue()
            ProcessResult.Status status = outputExceeded.get() ? ProcessResult.Status.OUTPUT_LIMIT_EXCEEDED :
                (exitCode == 0 ? ProcessResult.Status.SUCCESS : ProcessResult.Status.NON_ZERO_EXIT)
            return new ProcessResult(status, exitCode, capturedStdout, capturedStderr,
                outputExceeded.get() ? 'process output exceeded configured limit' : '')
        } catch (TimeoutException e) {
            terminateTree(process, descendants, isolatedGroup)
            return new ProcessResult(ProcessResult.Status.TIMED_OUT, -1, '', '', 'process output did not complete within configured timeout')
        } catch (ExecutionException e) {
            terminateTree(process, descendants, isolatedGroup)
            return new ProcessResult(ProcessResult.Status.OUTPUT_READ_FAILED, -1, '', '', 'unable to read process output')
        } catch (InterruptedException e) {
            terminateTree(process, descendants, isolatedGroup)
            Thread.currentThread().interrupt()
            return result(ProcessResult.Status.INTERRUPTED, process, stdout, stderr, 'process wait interrupted')
        } finally {
            // Also clean helpers that closed their pipes before their parent exited.
            if (isolatedGroup) signalGroup(process.pid(), 'KILL')
            readers.shutdown()
            try {
                if (!readers.awaitTermination(TERMINATION_GRACE.toMillis(), TimeUnit.MILLISECONDS)) {
                    stdout.cancel(true)
                    stderr.cancel(true)
                    readers.shutdownNow()
                }
            } catch (InterruptedException ignored) {
                stdout.cancel(true)
                stderr.cancel(true)
                readers.shutdownNow()
                Thread.currentThread().interrupt()
            }
        }
    }

    private static String drain(InputStream stream, int limit, AtomicBoolean exceeded) {
        ByteArrayOutputStream captured = new ByteArrayOutputStream()
        byte[] buffer = new byte[8192]
        int read
        while ((read = stream.read(buffer)) != -1) {
            int remaining = limit - captured.size()
            if (remaining > 0) captured.write(buffer, 0, Math.min(read, remaining))
            if (read > remaining) exceeded.set(true)
        }
        return captured.toString('UTF-8')
    }

    private static ProcessResult result(ProcessResult.Status status, Process process, Future<String> stdout, Future<String> stderr, String detail) {
        int exitCode = process.isAlive() ? -1 : process.exitValue()
        return new ProcessResult(status, exitCode, futureValue(stdout), futureValue(stderr), detail)
    }

    private static String futureValue(Future<String> future) {
        try {
            return future.get(TERMINATION_GRACE.toMillis(), TimeUnit.MILLISECONDS)
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt()
            return ''
        } catch (Exception ignored) {
            return ''
        }
    }

    private static void rememberDescendants(ProcessHandle parent, Set<ProcessHandle> descendants) {
        try (def stream = parent.descendants()) {
            stream.forEach { descendants.add(it) }
        }
    }

    private static void signalGroup(long groupId, String signal) {
        Process sender
        boolean interrupted = Thread.interrupted()
        try {
            sender = new ProcessBuilder('/bin/kill', "-${signal}", '--', "-${groupId}")
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start()
            if (!sender.waitFor(TERMINATION_GRACE.toMillis(), TimeUnit.MILLISECONDS)) sender.destroyForcibly()
        } catch (InterruptedException ignored) {
            sender?.destroyForcibly()
            interrupted = true
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    private static void terminateTree(Process process, Set<ProcessHandle> descendants, boolean isolatedGroup) {
        if (isolatedGroup) signalGroup(process.pid(), 'TERM')
        rememberDescendants(process.toHandle(), descendants)
        // A retained child may itself have spawned more processes since sampling.
        new ArrayList<ProcessHandle>(descendants).each { rememberDescendants(it, descendants) }
        List<ProcessHandle> handles = new ArrayList<>(descendants)
        handles.reverseEach { it.destroy() }
        process.destroy()
        waitForExit(handles + process.toHandle(), TERMINATION_GRACE)
        if (isolatedGroup) signalGroup(process.pid(), 'KILL')
        (handles + process.toHandle()).findAll { it.isAlive() }.reverseEach { it.destroyForcibly() }
    }

    private static void waitForExit(List<ProcessHandle> handles, Duration grace) {
        long deadline = System.nanoTime() + grace.toNanos()
        while (handles.any { it.isAlive() } && System.nanoTime() < deadline) {
            try { Thread.sleep(10) } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); return }
        }
    }
}
