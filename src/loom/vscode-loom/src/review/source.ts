import { WeaveGraphRunner } from '../graph/runner';
import { EngineClient } from '../engine/client';
import { EngineDiedError, EngineRpcError, EngineTimeoutError, EngineUnavailableError } from '../engine/errors';
import { parseReview, ReviewData } from './model';

/** How much of the review to ask for: `instant` is the validity lens only (fast, while typing); `full` is everything. */
export type Tier = 'instant' | 'full';

export interface ReviewRequest {
    file: string;
    tier: Tier;
    /** The settings that change what is reviewed. */
    lenses?: string;
    minSeverity?: string;
    /** Unsaved text of the file, when it has some. */
    text?: string;
    signal: AbortSignal;
}

export interface ReviewResult {
    review: ReviewData;
    /** The JSON of `weave graph`, or undefined when no graph could be drawn (the panel then shows findings alone). */
    graph: unknown;
}

/** What the controller needs: a review and a graph of one script. The engine is the first source, the commands the fallback. */
export interface ReviewSource {
    review(request: ReviewRequest): Promise<ReviewResult>;
}

/** The review that was asked for did not come out as a review. */
export class ReviewFailed extends Error {
    constructor(message: string) {
        super(message);
        this.name = 'ReviewFailed';
    }
}

/** Reviews through the engine's `loom/review` and `loom/graph` (one cache, no process start). */
export class EngineReviewSource implements ReviewSource {
    constructor(private readonly engine: () => EngineClient | undefined, private readonly fallback: ReviewSource) {}

    async review(request: ReviewRequest): Promise<ReviewResult> {
        const engine = this.engine();
        if (!engine) {
            return this.fallback.review(request);
        }
        try {
            const params = { file: request.file, tier: request.tier, lenses: request.lenses, minSeverity: request.minSeverity, text: request.text };
            const opts = { signal: request.signal };
            const [answer, graphAnswer] = await Promise.all([
                engine.latest(`review:${request.file}`, 'loom/review', params, opts),
                engine
                    .latest(`reviewgraph:${request.file}`, 'loom/graph', { file: request.file, text: request.text }, opts)
                    .then((g) => g.result)
                    .catch((e: unknown) => {
                        if (e instanceof EngineRpcError && e.kind === 'engine-failure') {
                            return undefined; // a script that cannot be drawn is still reviewed
                        }
                        throw e;
                    }),
            ]);
            const review = parseReview(answer.result);
            if (!review) {
                throw new ReviewFailed(`the engine's review of ${request.file} could not be read`);
            }
            return { review, graph: graphAnswer };
        } catch (e) {
            if (e instanceof EngineUnavailableError || e instanceof EngineDiedError || e instanceof EngineTimeoutError || (e instanceof EngineRpcError && (e.kind === 'outside-workspace' || e.kind === 'unknown-method'))) {
                return this.fallback.review(request);
            }
            throw e;
        }
    }
}

/** Reviews by running `weave review` and `weave graph` as processes: slower, the same JSON. Unsaved text is not seen. */
export class ProcessReviewSource implements ReviewSource {
    constructor(private readonly weave: WeaveGraphRunner) {}

    async review(request: ReviewRequest): Promise<ReviewResult> {
        const args = ['review', request.file, '--format', 'json', '--no-env'];
        if (request.tier === 'instant') {
            args.push('--lens', 'validity');
        } else if (request.lenses) {
            args.push('--lens', request.lenses);
        }
        if (request.minSeverity) {
            args.push('--min-severity', request.minSeverity);
        }
        const out = await this.weave.runWeave(args, request.signal, [0, 1]);
        let parsed: unknown;
        try {
            parsed = JSON.parse(out);
        } catch {
            throw new ReviewFailed(out.trim().slice(0, 300) || 'weave review printed nothing');
        }
        const review = parseReview(parsed);
        if (!review) {
            throw new ReviewFailed('weave review did not print a review');
        }
        let graph: unknown;
        try {
            graph = JSON.parse(await this.weave.runWeave(['graph', request.file], request.signal, [0]));
        } catch {
            graph = undefined;
        }
        return { review, graph };
    }
}
