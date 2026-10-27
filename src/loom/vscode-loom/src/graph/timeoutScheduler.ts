import { Scheduler, TimerHandle } from './controller';

/** The real clock: `setTimeout`, behind the interface tests replace with a clock they move by hand. */
export class TimeoutScheduler implements Scheduler {
    after(ms: number, run: () => void): TimerHandle {
        const handle = setTimeout(run, ms);
        return { cancel: () => clearTimeout(handle) };
    }
}
