package ru.rasulov.common.impl.undo;

import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import com.atlassian.scheduler.JobRunner;
import com.atlassian.scheduler.JobRunnerRequest;
import com.atlassian.scheduler.JobRunnerResponse;
import com.atlassian.scheduler.SchedulerService;
import com.atlassian.scheduler.config.JobConfig;
import com.atlassian.scheduler.config.JobId;
import com.atlassian.scheduler.config.JobRunnerKey;
import com.atlassian.scheduler.config.RunMode;
import com.atlassian.scheduler.config.Schedule;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;

import javax.inject.Inject;
import javax.inject.Named;
import java.util.Date;

/** Scheduler-owned lifecycle: one hourly job across the cluster, bounded transactions and bounded work. */
@Named
public final class JournalCleanup implements InitializingBean, DisposableBean, JobRunner {
    private static final JobRunnerKey RUNNER = JobRunnerKey.of("ru.rasulov.control.undo.cleanup");
    private static final JobId JOB = JobId.of("ru.rasulov.control.undo.cleanup.hourly");
    private final SchedulerService scheduler;
    private final ChangeJournal journal;

    @Inject
    public JournalCleanup(@ComponentImport SchedulerService scheduler, ChangeJournal journal) {
        this.scheduler = scheduler;
        this.journal = journal;
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        scheduler.registerJobRunner(RUNNER, this);
        try {
            if (scheduler.getJobDetails(JOB) == null) {
                scheduler.scheduleJob(JOB, JobConfig.forJobRunnerKey(RUNNER).withRunMode(RunMode.RUN_ONCE_PER_CLUSTER)
                        .withSchedule(Schedule.forInterval(60L * 60 * 1000, new Date())));
            }
        } catch (Exception failure) {
            scheduler.unregisterJobRunner(RUNNER);
            throw failure;
        }
    }

    @Override
    public JobRunnerResponse runJob(JobRunnerRequest request) {
        try {
            int deleted = 0;
            for (int batch = 0; batch < 20; batch++) {
                int count = journal.cleanupBatch(100);
                deleted += count;
                if (count < 100) break;
            }
            return JobRunnerResponse.success("Removed " + deleted + " ordinary expired journal rows; "
                    + "runner fallback reclamation uses an independent bounded pass.");
        } catch (RuntimeException failure) { return JobRunnerResponse.failed(failure); }
    }

    @Override
    public void destroy() {
        // The stable cluster schedule survives node/plugin restarts. Removing it from one node
        // would disable cleanup on every other node; unregister only this node's runner.
        scheduler.unregisterJobRunner(RUNNER);
    }
}
