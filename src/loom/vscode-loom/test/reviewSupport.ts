import * as fs from 'fs';
import * as path from 'path';
import { ReviewData, parseReview } from '../src/review/model';
import { ReviewRequest, ReviewResult, ReviewSource } from '../src/review/source';

const FIX = path.join(__dirname, '..', '..', 'test', 'fixtures', 'review');

/** Real `weave review` / `weave graph` output (scripts/regen-editor-fixtures.sh). */
export function reviewFixture(name: string): { review: ReviewData; graph: unknown } {
    const review = parseReview(JSON.parse(fs.readFileSync(path.join(FIX, name + '.review.json'), 'utf8')));
    if (!review) {
        throw new Error(`fixture ${name} is not a review`);
    }
    return { review, graph: JSON.parse(fs.readFileSync(path.join(FIX, name + '.graph.json'), 'utf8')) };
}

/** A source a test drives: each request waits until the test answers it, in any order. */
export class ManualSource implements ReviewSource {
    readonly requests: Array<ReviewRequest & { resolve(r: ReviewResult): void; reject(e: Error): void }> = [];

    review(request: ReviewRequest): Promise<ReviewResult> {
        return new Promise((resolve, reject) => {
            this.requests.push({ ...request, resolve, reject });
            request.signal.addEventListener('abort', () => reject(new Error('aborted')));
        });
    }

    answer(index: number, name: string): void {
        const f = reviewFixture(name);
        this.requests[index].resolve({ review: f.review, graph: f.graph });
    }
}
