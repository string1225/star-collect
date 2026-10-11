package com.starcollect.app;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public final class UsageJobService extends JobService {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private Future<?> job;
    public static void schedule(Context context) {
        context.getSystemService(JobScheduler.class).schedule(new JobInfo.Builder(2026, new ComponentName(context, UsageJobService.class))
                .setPeriodic(15 * 60_000L).setPersisted(true).build());
    }
    @Override public boolean onStartJob(JobParameters params) {
        job = executor.submit(() -> {
            boolean retry = false;
            try { UsageTracker.sync(this); } catch (RuntimeException e) { retry = true; }
            jobFinished(params, retry);
        }); return true;
    }
    @Override public boolean onStopJob(JobParameters params) { if (job != null) job.cancel(true); return true; }
    @Override public void onDestroy() { executor.shutdownNow(); super.onDestroy(); }
}
