package io.github.llm4j.agent.schedule;

import io.github.llm4j.agent.ReActAgent;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A scheduler that allows ReActAgents to register background tasks to be executed in the future
 * or on a recurring basis. This is critical for building autonomous assistants that perform 
 * actions while the user is absent.
 */
public class AgentScheduler {

    private static final Logger logger = LoggerFactory.getLogger(AgentScheduler.class);

    private final ScheduledExecutorService executorService;
    private final ReActAgent agent; // The agent instance to invoke for tasks

    /**
     * Constructs an AgentScheduler.
     * @param agent the agent instance used to fulfill scheduled background tasks
     * @param threadPoolSize the number of threads for background execution
     */
    public AgentScheduler(ReActAgent agent, int threadPoolSize) {
        this.agent = Objects.requireNonNull(agent, "agent cannot be null");
        this.executorService = Executors.newScheduledThreadPool(threadPoolSize);
    }
    
    /**
     * Constructs an AgentScheduler with a default single-thread pool.
     * @param agent the agent instance used to fulfill scheduled background tasks
     */
    public AgentScheduler(ReActAgent agent) {
        this(agent, 1);
    }

    /**
     * Schedules a task to be executed once after a designated delay.
     *
     * @param instructions the problem or instructions for the agent to resolve
     * @param delay        the time from now to delay execution
     * @return a ScheduledFuture representing pending completion of the task
     */
    public ScheduledFuture<?> scheduleTask(String instructions, Duration delay) {
        Objects.requireNonNull(instructions, "instructions cannot be null");
        Objects.requireNonNull(delay, "delay cannot be null");

        String taskId = UUID.randomUUID().toString();
        logger.info("Scheduling Agent execution (taskId: {}) to run in {} seconds.", taskId, delay.getSeconds());

        return executorService.schedule(() -> executeBackgroundAgent(taskId, instructions), delay.toMillis(), TimeUnit.MILLISECONDS);
    }

    /**
     * Schedules a task to be executed repeatedly with a fixed rate.
     *
     * @param instructions the problem or instructions for the agent to resolve
     * @param initialDelay the time to delay first execution
     * @param period       the period between successive executions
     * @return a ScheduledFuture representing pending completion of the task
     */
    public ScheduledFuture<?> scheduleRecurringTask(String instructions, Duration initialDelay, Duration period) {
        Objects.requireNonNull(instructions, "instructions cannot be null");
        Objects.requireNonNull(initialDelay, "initialDelay cannot be null");
        Objects.requireNonNull(period, "period cannot be null");

        String taskId = UUID.randomUUID().toString();
        logger.info("Scheduling recurring Agent execution (taskId: {}) every {} seconds.", taskId, period.getSeconds());

        return executorService.scheduleAtFixedRate(() -> executeBackgroundAgent(taskId, instructions), 
                initialDelay.toMillis(), 
                period.toMillis(), 
                TimeUnit.MILLISECONDS);
    }

    private void executeBackgroundAgent(String taskId, String instructions) {
        logger.info("Waking up agent to execute background task: {}", taskId);
        try {
            // Run the agent. Because it's a background task, the output goes to logs/events
            agent.run(instructions);
            logger.info("Background task {} completed successfully.", taskId);
        } catch (Exception e) {
            logger.error("Background task {} failed due to exception: {}", taskId, e.getMessage(), e);
        }
    }

    /**
     * Shuts down the scheduler gracefully.
     */
    public void shutdown() {
        logger.info("Shutting down AgentScheduler...");
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
