package com.ynab.amazon.service

import java.time.Duration
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

interface ProcessRunner {
    ProcessResult run(List<String> command, Duration timeout, int maxOutputBytes)
}

class ProcessResult {
    enum Status { SUCCESS, START_FAILED, NON_ZERO_EXIT, TIMED_OUT, INTERRUPTED, OUTPUT_LIMIT_EXCEEDED }

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

    @Override
    ProcessResult run(List<String> command, Duration timeout, int maxOutputBytes) {
        Process process
        try {
            process = new ProcessBuilder(command).start()
            process.outputStream.close() // The bridge is non-interactive.
        } catch (IOException e) {
            return new ProcessResult(ProcessResult.Status.START_FAILED, -1, '', '', e.message ?: e.class.simpleName)
        }

        ExecutorService readers = Executors.newFixedThreadPool(2)
        AtomicBoolean outputExceeded = new AtomicBoolean(false)
        Future<String> stdout = readers.submit { drain(process.inputStream, maxOutputBytes, outputExceeded) }
        Future<String> stderr = readers.submit { drain(process.errorStream, maxOutputBytes, outputExceeded) }
        try {
            long deadline = System.nanoTime() + timeout.toNanos()
            while (process.isAlive() && !outputExceeded.get()) {
                long remaining = deadline - System.nanoTime()
                if (remaining <= 0) {
                    terminateTree(process)
                    return result(ProcessResult.Status.TIMED_OUT, process, stdout, stderr, 'process exceeded configured timeout')
                }
                process.waitFor(Math.min(TimeUnit.NANOSECONDS.toMillis(remaining) + 1, 100), TimeUnit.MILLISECONDS)
            }
            if (outputExceeded.get()) {
                terminateTree(process)
                return result(ProcessResult.Status.OUTPUT_LIMIT_EXCEEDED, process, stdout, stderr, 'process output exceeded configured limit')
            }
            int exitCode = process.exitValue()
            return result(exitCode == 0 ? ProcessResult.Status.SUCCESS : ProcessResult.Status.NON_ZERO_EXIT,
                process, stdout, stderr, '')
        } catch (InterruptedException e) {
            terminateTree(process)
            Thread.currentThread().interrupt()
            return result(ProcessResult.Status.INTERRUPTED, process, stdout, stderr, 'process wait interrupted')
        } finally {
            readers.shutdown()
            try {
                if (!readers.awaitTermination(TERMINATION_GRACE.toMillis(), TimeUnit.MILLISECONDS)) readers.shutdownNow()
            } catch (InterruptedException ignored) {
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

    private static void terminateTree(Process process) {
        List<ProcessHandle> handles = process.toHandle().descendants().toList()
        handles.reverseEach { it.destroy() }
        process.destroy()
        waitForExit(handles + process.toHandle(), TERMINATION_GRACE)
        (handles + process.toHandle()).findAll { it.isAlive() }.reverseEach { it.destroyForcibly() }
    }

    private static void waitForExit(List<ProcessHandle> handles, Duration grace) {
        long deadline = System.nanoTime() + grace.toNanos()
        while (handles.any { it.isAlive() } && System.nanoTime() < deadline) {
            try { Thread.sleep(10) } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); return }
        }
    }
}
