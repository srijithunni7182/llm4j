package io.github.llm4j.agent.tool;

import io.github.llm4j.agent.Tool;
import io.github.llm4j.agent.schedule.AgentScheduler;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A tool that allows an agent to schedule future tasks. 
 * Enables autonomous "background" capabilities (e.g. "Remind me to check X in 1 hour").
 */
public class ScheduledActionTool implements Tool {

    private static final Logger logger = LoggerFactory.getLogger(ScheduledActionTool.class);

    private final AgentScheduler scheduler;

    public ScheduledActionTool(AgentScheduler scheduler) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler cannot be null");
    }

    @Override
    public String getName() {
        return "schedule_action";
    }

    @Override
    public String getDescription() {
        return "Use this tool to schedule a background task to be executed at a later time. " +
               "This allows you to 'wake up' later and perform an action like checking a status or sending a reminder. " +
               "Arguments: \n" +
               "- instructions (string): What you should do when you wake up.\n" +
               "- delaySeconds (integer): How many seconds into the future to wait before executing.\n" +
               "- isRecurring (boolean, optional): If true, this task will repeat indefinitely every 'delaySeconds'. Default is false.";
    }

    @Override
    public String execute(Map<String, Object> args) throws Exception {
        String instructions = (String) args.get("instructions");
        Number delaySecondsNum = (Number) args.get("delaySeconds");
        Boolean isRecurringObj = (Boolean) args.get("isRecurring");
        
        boolean isRecurring = isRecurringObj != null ? isRecurringObj : false;

        if (instructions == null || instructions.trim().isEmpty()) {
            return "Error: instructions missing.";
        }
        if (delaySecondsNum == null || delaySecondsNum.longValue() <= 0) {
            return "Error: delaySeconds must be a positive integer.";
        }

        Duration delay = Duration.ofSeconds(delaySecondsNum.longValue());

        if (isRecurring) {
            scheduler.scheduleRecurringTask(instructions, delay, delay);
            logger.info("Scheduled recurring action for {} seconds from now.", delaySecondsNum);
            return "Successfully scheduled a recurring background task to execute every " + delaySecondsNum + " seconds.";
        } else {
            scheduler.scheduleTask(instructions, delay);
            logger.info("Scheduled one-time action for {} seconds from now.", delaySecondsNum);
            return "Successfully scheduled a single background task to execute in " + delaySecondsNum + " seconds.";
        }
    }
}
