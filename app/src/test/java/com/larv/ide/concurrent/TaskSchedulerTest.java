package com.larv.ide.concurrent;

import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class TaskSchedulerTest {

    @Test
    public void exposesTheExecutorsItOwns() {
        IOExecutor io = new IOExecutor(1);
        CPUExecutor cpu = new CPUExecutor(1);
        CompilationExecutor compilation = new CompilationExecutor();
        TaskScheduler scheduler = new TaskScheduler(io, cpu, compilation);

        assertSame(io, scheduler.io());
        assertSame(cpu, scheduler.cpu());
        assertSame(compilation, scheduler.compilation());
        scheduler.shutdown();
    }

    @Test
    public void runsTasksOnAllThreeExecutors() throws Exception {
        TaskScheduler scheduler = new TaskScheduler();
        CountDownLatch latch = new CountDownLatch(3);
        scheduler.io().execute(latch::countDown);
        scheduler.cpu().execute(latch::countDown);
        scheduler.compilation().execute(latch::countDown);

        assertTrue("tasks did not run", latch.await(5, TimeUnit.SECONDS));
        scheduler.shutdown();
    }

    @Test
    public void shutdownClosesEveryExecutor() {
        TaskScheduler scheduler = new TaskScheduler();
        scheduler.shutdown();

        assertTrue(scheduler.io().isShutdown());
        assertTrue(scheduler.cpu().isShutdown());
        assertTrue(scheduler.compilation().isShutdown());
    }
}
